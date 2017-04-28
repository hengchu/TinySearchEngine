package com.tinysearchengine.crawler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.log4j.Logger;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.nio.reactor.IOReactorException;

import com.tinysearchengine.crawler.RobotInfoCache.RobotInfo;
import com.tinysearchengine.crawler.frontier.URLFrontier;

import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;

/**
 * @author MIAO
 *
 */
public class CrawlerCluster {

	private final static int k_MAX_BUFFER_SIZE = 1024;
	private final static int k_MAX_THREADPOOL_CAP = 1000000;

	private int m_myIndex = -1;
	private URLFrontier m_frontier = null;
	private RobotInfoCache m_cache = null;
	private Map<URL, Boolean> m_due = null;
	private String[] m_workerConfig = null;
	private CloseableHttpAsyncClient m_asyncClient = null;

	private AtomicInteger d_urlsSent = new AtomicInteger(0);
	private Map<String, StringBuffer> d_urlBuffer = null;

	private static Logger logger = Logger.getLogger(CrawlerCluster.class);

	private ExecutorService d_threadPool = new ThreadPoolExecutor(2,
			10,
			30,
			TimeUnit.SECONDS,
			new LinkedBlockingDeque<Runnable>(k_MAX_THREADPOOL_CAP),
			Executors.defaultThreadFactory(),
			new RejectedExecutionHandler() {
				@Override
				public void rejectedExecution(Runnable r,
						ThreadPoolExecutor executor) {
					// Just Ignore
				}
			});

	public CrawlerCluster(URLFrontier frontier,
			RobotInfoCache cache,
			Map<URL, Boolean> due,
			String[] workerConfig,
			int myIndex) throws IOReactorException {
		m_frontier = frontier;
		m_cache = cache;
		m_due = due;
		m_workerConfig = workerConfig;
		m_myIndex = myIndex;

		m_asyncClient = HttpAsyncClients.createDefault();
		m_asyncClient.start();

		d_urlBuffer = Collections.synchronizedMap(new HashMap<>());

		Spark.post("/pushdata", new Route() {
			@Override
			public Object handle(Request req, Response resp) {

				BufferedReader reader =
					new BufferedReader(new StringReader(req.body()));
				String line = null;
				try {
					while ((line = reader.readLine()) != null) {
						URL url = null;
						try {
							logger.debug("Received: " + line);
							url = new URL(line);
						} catch (MalformedURLException e) {
							logger.info(line + " is a bad url!", e);
							continue;
						}
						assert url != null;
						final URL urlToInsert = url;
						d_threadPool.submit(() -> {
							return insertReceivedUrl(urlToInsert);
						});
					}
				} catch (IOException e) {
					logger.error("IOException when parsing URLs received", e);
				}
				return "";
			}
		});

		// Populate the URL buffers.
		for (int i = 0; i < workerConfig.length; ++i) {
			d_urlBuffer.put(workerConfig[i], new StringBuffer());
		}
	}

	private boolean insertReceivedUrl(URL url) {
		Map<URL, Boolean> due = m_due;

		// DUE
		if (!due.containsKey(url)) {
			due.put(url, true);

			URLFrontier frontier = m_frontier;

			// put head request into URLFrontier
			URLFrontier.Request headRequest = new URLFrontier.Request();
			headRequest.url = url;
			headRequest.method = "HEAD";

			long lastScheduledTime =
				frontier.lastScheduledTime(url.getAuthority());
			RobotInfo info = m_cache.getInfoForUrl(url);

			int crawlDelaySeconds = 10;
			if (info != null) {
				int robotDelay = info.getCrawlDelay(Crawler.k_USER_AGENT);
				crawlDelaySeconds =
					robotDelay == 0 ? crawlDelaySeconds : robotDelay;
			}
			logger.debug(url.toString() + " has delay: "
					+ crawlDelaySeconds
					+ " seconds.");

			try {
				frontier.put(headRequest,
						URLFrontier.Priority.Medium,
						lastScheduledTime + crawlDelaySeconds * 1000);
				return true;
			} catch (InterruptedException e) {
				logger.error("frontier put interrupted", e);
				return false;
			}
		}

		return false;
	}

	private synchronized void flushSendBuffer(String dest) {
		String strURL = "http://" + dest + "/pushdata";
		// Get all the URLs.
		byte[] body = d_urlBuffer.get(dest).toString().getBytes();
		// Clear the buffer.
		d_urlBuffer.get(dest).setLength(0);

		// Send the request.
		HttpPost httpPost = new HttpPost(strURL);
		HttpEntity entity = new ByteArrayEntity(body);
		httpPost.setEntity(entity);
		// Just execute asynchronously, ignore the response.
		m_asyncClient.execute(httpPost, null);
	}

	// use apache HttpClient instead of HttpURLConnection
	private void sendData(String dest, URL url) throws IOException {
		StringBuffer buf = d_urlBuffer.get(dest);
		assert buf != null;
		buf.append(url.toString() + "\n");

		if (buf.length() > k_MAX_BUFFER_SIZE) {
			flushSendBuffer(dest);
		}

		d_urlsSent.incrementAndGet();
	}

	private int getURLWorkerIndex(URL url) {
		return Math.abs(url.getAuthority().hashCode()) % m_workerConfig.length;
	}

	public void distributeURL(URL url) throws IOException {
		int workerIndex = getURLWorkerIndex(url);
		if (workerIndex == m_myIndex) {
			if (!m_due.containsKey(url)) {
				m_due.put(url, true);
				d_threadPool.submit(() -> {
					insertReceivedUrl(url);
				});
			}
		} else {
			String dest = m_workerConfig[workerIndex];
			sendData(dest, url);
		}
	}

	// currently it's never called
	public void close() {
		try {
			m_asyncClient.close();
			d_threadPool.shutdown();
		} catch (IOException e) {
			logger.error("Failed to close http client in crawler cluster.");
		}
	}

	public long urlsSent() {
		return d_urlsSent.get();
	}

	public ExecutorService putUrlThreadPool() {
		return d_threadPool;
	}
}
