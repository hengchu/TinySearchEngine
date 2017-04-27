package com.tinysearchengine.crawler;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.dynamodbv2.datamodeling.S3Link;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.tinysearchengine.crawler.RobotInfoCache.RobotInfo;
import com.tinysearchengine.crawler.frontier.URLFrontier;
import com.tinysearchengine.database.DBEnv;
import com.tinysearchengine.database.DdbConnector;
import com.tinysearchengine.database.DdbDocument;

import spark.Spark;

/**
 * @author MIAO
 *
 */
public class Crawler {

	static Logger logger = Logger.getLogger(Crawler.class);

	public final static String k_USER_AGENT = "cis455crawler-g05";

	private static int k_MAX_DOC_SIZE = 5 * 1024 * 1024;
	private static int k_MAX_DUE_SIZE = 10000;
	private static int k_DOC_COUNT = 1000000;
	private static int k_TIMEOUT = 2000;

	private DBEnv m_dbEnv = null;
	private URLFrontier m_URLFrontier = null;
	private CrawlerContext m_context = null;
	private CrawlerCluster m_cluster = null;
	private Map<URL, Boolean> m_LRUdue = null;
	private RobotInfoCache m_robotCache = new RobotInfoCache();
	private Thread[] m_downloadThreads = null;
	private DdbConnector d_ddbConnector = new DdbConnector();
	private DocumentFingerprintCache d_contentSeenCache =
		new DocumentFingerprintCache(d_ddbConnector);
	private PoolingHttpClientConnectionManager m_manager = null;
	private HttpClient m_client = null;

	/**
	 * @param dbDir
	 *            database directory
	 * @param port
	 *            crawler cluster listening port
	 * @param nThread
	 *            number of threads in the thread pool
	 * @throws IOReactorException
	 */
	public Crawler(String dbDir,
			int port,
			int nThread,
			Set<URL> seedUrls,
			ArrayList<String> workerList,
			int crawlerIndex) throws IOReactorException {
		m_downloadThreads = new Thread[nThread];
		File dbRoot = new File(dbDir);
		dbRoot.mkdirs();
		m_dbEnv = new DBEnv(dbRoot);

		ConcurrentLinkedHashMap.Builder<URL, Boolean> builder =
			new ConcurrentLinkedHashMap.Builder<>();
		m_LRUdue = builder.concurrencyLevel(nThread).initialCapacity(1000)
				.maximumWeightedCapacity(k_MAX_DUE_SIZE).build();

		m_manager = new PoolingHttpClientConnectionManager();
		m_manager.setMaxTotal(20 * nThread);
		m_manager.setDefaultMaxPerRoute(20);

		RequestConfig.Builder reqCfgBuilder = RequestConfig.custom();
		reqCfgBuilder.setSocketTimeout(k_TIMEOUT);
		reqCfgBuilder.setConnectTimeout(k_TIMEOUT);
		reqCfgBuilder.setConnectionRequestTimeout(k_TIMEOUT);
		reqCfgBuilder.setCookieSpec(CookieSpecs.STANDARD);

		m_client = HttpClients
				.custom()
				.setConnectionManager(m_manager)
				.setDefaultRequestConfig(reqCfgBuilder.build())
				.disableAutomaticRetries()
				.build();

		Spark.port(port);

		try {
			m_URLFrontier = new URLFrontier(nThread * 3, seedUrls, m_dbEnv);
		} catch (InterruptedException e) {
			logger.error("Frontier creation interrupted", e);
			System.exit(1);
		}
		String[] workerConfig = workerList.toArray(new String[0]);
		m_cluster = new CrawlerCluster(m_URLFrontier,
				m_robotCache,
				m_LRUdue,
				workerConfig,
				crawlerIndex);
		m_context = new CrawlerContext(m_URLFrontier,
				m_LRUdue,
				workerConfig,
				m_cluster,
				m_robotCache);
	}

	private HttpResponse doRequest(String method, URL url) throws IOException {
		if (method.equals("HEAD")) {
			HttpHead req = new HttpHead(url.toString());
			req.addHeader("User-agent", k_USER_AGENT);
			HttpResponse response = m_client.execute(req);

			logger.debug(url.toString() + ", rc: "
					+ response.getStatusLine().getStatusCode());

			return response;
		} else {
			HttpGet req = new HttpGet(url.toString());
			req.addHeader("User-agent", k_USER_AGENT);
			HttpResponse response = m_client.execute(req);

			logger.debug(url.toString() + ", rc: "
					+ response.getStatusLine().getStatusCode());

			return response;
		}
	}

	/**
	 * Return the content length header value. If none exists, return -1.
	 * 
	 * @param headers
	 * @return
	 */
	private int getContentLength(Header[] headers) {
		if (headers == null || headers.length == 0) {
			return -1;
		}

		Header header = headers[0];
		try {
			return Integer.parseInt(header.getValue());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Return the canonical content type, null if it doesn't exist.
	 * 
	 * @param headers
	 * @return
	 */
	private String getCanonicalContentType(Header[] headers) {
		if (headers == null || headers.length == 0) {
			return null;
		}

		Header header = headers[0];
		HeaderElement[] elements = header.getElements();
		if (elements.length > 0) {
			return elements[0].getName();
		} else {
			return null;
		}
	}

	private String getCharset(Header[] headers) {
		if (headers == null || headers.length == 0) {
			return null;
		}

		Header header = headers[0];
		HeaderElement[] elements = header.getElements();
		for (int i = 0; i < elements.length; ++i) {
			NameValuePair p = elements[i].getParameterByName("charset");
			if (p != null) {
				return p.getValue();
			}
		}

		return null;
	}

	public static boolean isEnglish(HttpResponse headResp) {
		Header[] headers = headResp.getHeaders("content-language");
		if (headers == null || headers.length == 0) {
			return true;
		}

		for (int i = 0; i < headers.length; ++i) {
			Header hd = headers[i];
			HeaderElement[] elmts = hd.getElements();
			for (int j = 0; j < elmts.length; ++j) {
				if (elmts[j].getName().toLowerCase().startsWith("en")) {
					return true;
				}
			}
		}

		return false;
	}

	private URLFrontier.Priority computePriority(URL url,
			HttpResponse headResp) {
		if (isEnglish(headResp)) {
			return URLFrontier.Priority.High;
		} else {
			return URLFrontier.Priority.Low;
		}
	}

	private long computeReleaseTime(URL url) {
		long lastScheduledTime =
			m_URLFrontier.lastScheduledTime(url.getAuthority());
		int delay = 10;
		RobotInfo info = m_robotCache.getInfoForUrl(url);
		if (info != null) {
			int robotDelay = info.getCrawlDelay(k_USER_AGENT);
			delay = robotDelay == 0 ? delay : robotDelay;
			logger.debug(url.toString() + " has delay: " + delay + " seconds");
		}

		return lastScheduledTime + delay * 1000;
	}

	public void start() {
		logger.info("Starting crawler");

		m_URLFrontier.start();

		for (int i = 0; i < m_downloadThreads.length; ++i) {
			m_downloadThreads[i] =
				new Thread(() -> threadLoop(), "CrawlerThread" + i);
		}

		for (int i = 0; i < m_downloadThreads.length; ++i) {
			m_downloadThreads[i].start();
		}

		logger.info("Started crawler");
	}

	private void threadLoop() {
		while (m_context.getDocsCounter() < k_DOC_COUNT) {
			try {
				URLFrontier.Request req = m_URLFrontier.get();

				logger.debug("Processing: " + req.url.toString());

				if (!req.method.equals("HEAD") && !req.method.equals("GET")) {
					logger.warn("Unknown method: " + req.method);
					continue;
				}

				try {
					logger.debug("Requesting for: " + req.url.toString());

					HttpResponse resp = null;
					try {
						resp = doRequest(req.method, req.url);
					} catch (IOException e) {
						logger.debug("IOException when requesting: "
								+ req.url.toString(), e);
						continue;
					}

					logger.debug("Finished request: " + req.url.toString());

					if (req.method.equals("HEAD")) {
						String contentType = getCanonicalContentType(
								resp.getHeaders("content-type"));
						if (contentType == null
								|| !contentType.endsWith("html")) {
							logger.debug(req.url.toString() + " is not html: "
									+ contentType);
							continue;
						}

						RobotInfo info = m_robotCache.getInfoForUrl(req.url);

						int contentLength =
							getContentLength(resp.getHeaders("content-length"));

						if (contentLength > k_MAX_DOC_SIZE
								&& contentLength < 0) {
							logger.info(req.url.toString()
									+ " has bad content-length: "
									+ contentLength);
							continue;
						}

						if (info == null || RobotInfoCache.canCrawl(info,
								req.url,
								k_USER_AGENT)) {
							logger.debug("Adding " + req.url.toString()
									+ " for GET.");
							URLFrontier.Priority prio =
								computePriority(req.url, resp);
							long releaseTime = computeReleaseTime(req.url);
							m_cluster.putUrlThreadPool().submit(() -> {
								try {
									m_URLFrontier.put(req.url,
											prio,
											releaseTime);
								} catch (InterruptedException e) {
									logger.warn("Frontier put interrupted", e);
								}
							});
						}
					} else if (req.method.equals("GET")) {

						byte[] content =
							EntityUtils.toByteArray(resp.getEntity());

						String contentType = getCanonicalContentType(
								resp.getHeaders("content-type"));
						if (contentType == null) {
							contentType = "unknown";
						}
						String charset =
							getCharset(resp.getHeaders("content-type"));
						if (charset == null) {
							charset = "unknown";
						}

						if (!d_contentSeenCache.hasSeen(content)) {
							// Store into Ddb.
							DdbDocument ddbDoc = new DdbDocument();
							S3Link contentLink = d_ddbConnector.createS3Link(
									DdbConnector.urlToS3LinkKey(req.url));
							ddbDoc.setContentLink(contentLink);
							ddbDoc.setContent(content);
							ddbDoc.setCharset(charset);

							long now = (new Date()).getTime();
							ddbDoc.setCrawledTime(now);
							ddbDoc.setUrl(req.url);
							ddbDoc.setUrlAsString(req.url.toString());
							ddbDoc.setFingerprint(DdbDocument
									.computeFingerprint(ddbDoc.getContent()));
							ddbDoc.setContentType(contentType);

							logger.debug("Stored " + req.url.toString()
									+ " to the database.");

							m_context.incDocsCounter();

							// Extract the URLs
							String[] urls =
								URLExtractor.extract(ddbDoc.getContent());
							HashSet<String> extractedLinks = new HashSet<>();
							logger.debug(
									"Extracted urls: " + Arrays.toString(urls));
							for (int i = 0; i < urls.length; ++i) {
								try {
									URL resolvedUrl = new URL(req.url, urls[i]);
									extractedLinks.add(resolvedUrl.toString());
									m_context.putTask(resolvedUrl);
								} catch (MalformedURLException e) {
									// Ignore.
								}
							}

							ddbDoc.setLinks(extractedLinks);
							// new documents have links
							ddbDoc.setRepaired(true);
							d_ddbConnector.putDocument(ddbDoc);
						} else {
							logger.info(
									"Has already seen: " + req.url.toString());
						}
					}
				} catch (IOException e) {
					logger.warn(
							"IOException when requesting " + req.url.toString(),
							e);
				}

			} catch (InterruptedException e) {
				logger.info("Interrupted!", e);
			} catch (Throwable e) {
				logger.warn("Unexpected exception", e);
			}
		}
	}
}
