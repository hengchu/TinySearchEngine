package com.tinysearchengine.searchengine.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.tartarus.snowball.SnowballStemmer;
import org.tartarus.snowball.ext.englishStemmer;

import com.tinysearchengine.database.DdbConnector;
import com.tinysearchengine.database.DdbDocument;
import com.tinysearchengine.database.DdbIdfScore;
import com.tinysearchengine.database.DdbPageRankScore;
import com.tinysearchengine.database.DdbWordDocTfTuple;
import com.tinysearchengine.indexer.StopWordList;
import com.tinysearchengine.searchengine.othersites.AmazonItemResult;
import com.tinysearchengine.searchengine.othersites.EbayItemResult;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

public class SearchServlet extends HttpServlet {

	final int k_MAX_3RDPARTY_RESULTS = 15;
	final int k_MAX_SEARCH_RESULTS = 50;
	final int k_MAX_TITLE_LENGTH = 60;
	final int k_MAX_SUMMARY_LENGTH = 300;
	final double k_TF_WEIGHT = 0.7;

	Configuration d_templateConfiguration;

	Template d_searchResultTemplate;

	SnowballStemmer d_stemmer = new englishStemmer();

	DdbConnector d_connector = new DdbConnector();

	public class UrlWordPair {
		String url;
		String word;
		double tf;

		@Override
		public int hashCode() {
			return url.hashCode() ^ word.hashCode();
		}
	}

	public class UrlScorePair {
		String url;
		double score;
		double pgRankScore;
		double totalScore;
	}

	public class SearchResult {
		private String d_title;
		private String d_url;
		private String d_summary;
		private String d_score;
		private String d_pgRankScore;
		private String d_totalScore;

		public void setTitle(String title) {
			d_title = title;
		}

		public String getTitle() {
			return d_title;
		}

		public void setUrl(String url) {
			d_url = url;
		}

		public String getUrl() {
			return d_url;
		}

		public void setSummary(String summary) {
			d_summary = summary;
		}

		public String getSummary() {
			return d_summary;
		}

		public String getScore() {
			return d_score;
		}

		public void setScore(String score) {
			d_score = score;
		}

		public String getPgRankScore() {
			return d_pgRankScore;
		}

		public void setPgRankScore(String pgRankScore) {
			d_pgRankScore = pgRankScore;
		}

		public String getTotalScore() {
			return d_totalScore;
		}

		public void setTotalScore(String totalScore) {
			d_totalScore = totalScore;
		}

	}

	public class ThirdPartyResult {
		private String d_itemUrl;
		private String d_imgUrl;
		private String d_title;
		private String d_price;

		public ThirdPartyResult(AmazonItemResult amzr) {
			setItemUrl(amzr.itemUrl);
			setImageUrl(amzr.imgUrl);
			setTitle(amzr.title);
			setPrice(amzr.price);
		}

		public ThirdPartyResult(EbayItemResult ebr) {
			setItemUrl(ebr.itemUrl);
			setImageUrl(ebr.imgUrl);
			setTitle(ebr.title);
			setPrice(ebr.price);
		}

		public void setItemUrl(String url) {
			d_itemUrl = url;
		}

		public String getItemUrl() {
			return d_itemUrl;
		}

		public void setImageUrl(String url) {
			d_imgUrl = url;
		}

		public String getImageUrl() {
			return d_imgUrl;
		}

		public void setTitle(String title) {
			if (title.length() > 100) {
				title = title.substring(0, 97) + "...";
			}
			d_title = title;
		}

		public String getTitle() {
			return d_title;
		}

		public void setPrice(String p) {
			d_price = p;
		}

		public String getPrice() {
			return d_price;
		}
	}

	public void init() throws ServletException {
		super.init();

		d_templateConfiguration = new Configuration();
		d_templateConfiguration.setDefaultEncoding("UTF-8");
		d_templateConfiguration.setTemplateExceptionHandler(
				TemplateExceptionHandler.RETHROW_HANDLER);

		InputStream idxFileStream =
			getClass().getResourceAsStream("searchresult.ftlh");
		try {
			d_searchResultTemplate = new Template("searchresult-template",
					new InputStreamReader(idxFileStream),
					d_templateConfiguration);
		} catch (IOException e) {
			throw new ServletException(e);
		}

	}

	private Map<String, Double>
			computeQueryTermTfScores(List<String> stemmedTerms) {
		int maxFreq = 0;

		Map<String, Integer> queryTermCounts = new HashMap<>();
		for (String stemmedTerm : stemmedTerms) {
			if (queryTermCounts.containsKey(stemmedTerm)) {
				int c = queryTermCounts.get(stemmedTerm);
				queryTermCounts.put(stemmedTerm, c + 1);
				if (c + 1 > maxFreq) {
					maxFreq = c + 1;
				}
			} else {
				queryTermCounts.put(stemmedTerm, 1);
				if (1 > maxFreq) {
					maxFreq = 1;
				}
			}
		}

		Map<String, Double> queryTermTfs = new HashMap<>();
		for (Map.Entry<String, Integer> kv : queryTermCounts.entrySet()) {
			queryTermTfs.put(kv.getKey(), (1.0 * kv.getValue()) / maxFreq);
		}

		return queryTermTfs;
	}

	private Map<String, Double>
			computeQueryTermScores(List<String> stemmedTerms) {
		Map<String, Double> queryTermTfs =
			computeQueryTermTfScores(stemmedTerms);

		Map<String, DdbIdfScore> queryTermIdfs = cleanupDdbIdfQueryResult(
				d_connector.batchGetWordIdfScores(stemmedTerms));

		Map<String, Double> queryTermScores = new HashMap<>();
		for (Map.Entry<String, Double> kv : queryTermTfs.entrySet()) {
			double idfScore = 0;
			if (queryTermIdfs.containsKey(kv.getKey())) {
				idfScore = queryTermIdfs.get(kv.getKey()).getIdf();
			}
			queryTermScores.put(kv.getKey(), idfScore * kv.getValue());
		}

		return queryTermScores;
	}

	private UrlScorePair[] performSearch(Map<String, Object> dataModel,
			String queryTerm) {
		String[] terms = queryTerm.split("\\s+");
		List<String> stemmedTerms = new LinkedList<>();

		for (String term : terms) {
			if (StopWordList.stopwords.contains(term)) {
				continue;
			}

			d_stemmer.setCurrent(term);
			d_stemmer.stem();
			stemmedTerms.add(d_stemmer.getCurrent());
		}

		dataModel.put("stemmedTerms", stemmedTerms.toString());

		Map<String, Double> queryTermScores =
			computeQueryTermScores(stemmedTerms);

		dataModel.put("queryTermScores", queryTermScores.toString());

		Map<UrlWordPair, Double> docWordTable = new HashMap<>();
		for (String stemmedTerm : stemmedTerms) {
			List<DdbWordDocTfTuple> tuples =
				d_connector.getWordDocTfTuplesForWord(stemmedTerm);

			for (DdbWordDocTfTuple t : tuples) {
				UrlWordPair p = new UrlWordPair();
				p.url = t.getUrl();
				p.word = t.getWord();

				if (docWordTable.containsKey(p)) {
					double tf = docWordTable.get(p);
					docWordTable.put(p, tf + t.getTf());
				} else {
					docWordTable.put(p, t.getTf());
				}
			}
		}

		Map<String, Double> docScoreTable = new HashMap<>();
		for (UrlWordPair p : docWordTable.keySet()) {
			p.tf = docWordTable.get(p);
			double wordScore = queryTermScores.get(p.word);
			if (docScoreTable.containsKey(p.url)) {
				double score = docScoreTable.get(p.url);
				docScoreTable.put(p.url, score + p.tf * wordScore);
			} else {
				docScoreTable.put(p.url, p.tf * wordScore);
			}
		}

		PriorityQueue<UrlScorePair> queue =
			new PriorityQueue<>(10, (lhs, rhs) -> {
				return lhs.score > rhs.score ? -1 : 1;
			});

		double maxDocScore = 0;
		for (Map.Entry<String, Double> kv : docScoreTable.entrySet()) {
			if (kv.getValue() > maxDocScore) {
				maxDocScore = kv.getValue();
			}
		}

		for (String url : docScoreTable.keySet()) {
			UrlScorePair p = new UrlScorePair();
			p.url = url;
			p.score = docScoreTable.get(url) / maxDocScore;
			queue.offer(p);
		}

		return queue.toArray(new UrlScorePair[0]);
	}

	private String extractMainBody(StringBuilder title, byte[] document)
			throws UnsupportedEncodingException {
		Document d = Jsoup.parse(new String(document, "UTF-8"));
		Elements elmts = d.getElementsMatchingOwnText(".{20,}");
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < elmts.size(); ++i) {
			result.append(elmts.get(i).ownText());
			result.append(" ");
		}

		Elements titleElmts = d.select("head title");
		if (titleElmts.size() > 0) {
			title.append(titleElmts.get(0).text());
		}

		return result.toString();
	}

	private SearchResult makeSearchResultFrom(UrlScorePair p,
			Map<String, DdbDocument> batchDocs)
			throws MalformedURLException, UnsupportedEncodingException {
		SearchResult r = new SearchResult();
		r.setUrl(p.url);
		r.setScore(Double.toString(p.score));

		DdbDocument doc = batchDocs.get(p.url);
		if (doc == null) {
			return null;
		}

		StringBuilder title = new StringBuilder();
		String content = extractMainBody(title, doc.getContent());
		if (content.length() > k_MAX_SUMMARY_LENGTH) {
			content = content.substring(0, k_MAX_SUMMARY_LENGTH - 3) + "...";
		}
		r.setSummary(content);

		String titleText = title.toString();
		if (titleText.length() > k_MAX_TITLE_LENGTH) {
			titleText = titleText.substring(0, k_MAX_TITLE_LENGTH - 3) + "...";
		}
		r.setTitle(titleText);

		if (titleText.contains("404")
				|| titleText.toLowerCase().contains("not found")) {
			return null;
		}

		r.setPgRankScore(Double.toString(p.pgRankScore));
		r.setTotalScore(Double.toString(p.totalScore));

		return r;
	}

	private Map<String, DdbDocument>
			cleanupDdbDocumentQueryResult(Map<String, List<Object>> results) {
		Map<String, DdbDocument> r = new HashMap<>();
		for (Map.Entry<String, List<Object>> kv : results.entrySet()) {
			for (Object obj : kv.getValue()) {
				if (obj instanceof DdbDocument) {
					DdbDocument doc = (DdbDocument) obj;
					r.put(doc.getUrlAsString(), doc);
				}
			}
		}
		return r;
	}

	private Map<String, DdbPageRankScore>
			cleanupDdbPageRankQueryResult(Map<String, List<Object>> results) {
		Map<String, DdbPageRankScore> r = new HashMap<>();
		for (Map.Entry<String, List<Object>> kv : results.entrySet()) {
			for (Object obj : kv.getValue()) {
				if (obj instanceof DdbPageRankScore) {
					DdbPageRankScore doc = (DdbPageRankScore) obj;
					r.put(doc.getUrl(), doc);
				}
			}
		}
		return r;
	}

	private Map<String, DdbIdfScore>
			cleanupDdbIdfQueryResult(Map<String, List<Object>> results) {
		Map<String, DdbIdfScore> r = new HashMap<>();
		for (Map.Entry<String, List<Object>> kv : results.entrySet()) {
			for (Object obj : kv.getValue()) {
				if (obj instanceof DdbIdfScore) {
					DdbIdfScore score = (DdbIdfScore) obj;
					r.put(score.getWord(), score);
				}
			}
		}
		return r;
	}

	private double computeTotalScore(UrlScorePair p) {
		return k_TF_WEIGHT * p.score + (1 - k_TF_WEIGHT) * p.pgRankScore;
	}

	private UrlScorePair[] mergeWithPGRankAndSort(UrlScorePair[] ps,
			Map<String, DdbPageRankScore> pgScores) {
		PriorityQueue<UrlScorePair> sortedPs =
			new PriorityQueue<>(10, (lhs, rhs) -> {
				if (computeTotalScore(lhs) > computeTotalScore(rhs)) {
					return -1;
				} else {
					return 1;
				}
			});

		double maxScore = 0;
		for (Map.Entry<String, DdbPageRankScore> kv : pgScores.entrySet()) {
			if (kv.getValue().getPageRankScore() > maxScore) {
				maxScore = kv.getValue().getPageRankScore();
			}
		}

		for (UrlScorePair p : ps) {
			DdbPageRankScore pgScore = pgScores.get(p.url);
			if (pgScore != null) {
				p.pgRankScore = pgScore.getPageRankScore() / maxScore;
				p.totalScore = computeTotalScore(p);
			}

			sortedPs.add(p);
		}

		return sortedPs.toArray(new UrlScorePair[0]);
	}

	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		long startTime   = System.currentTimeMillis();

		HashMap<String, Object> root = new HashMap<>();

		String queryTerm = request.getParameter("query");
		root.put("query", queryTerm);

		UrlScorePair[] urlScorePairs = performSearch(root, queryTerm);
		List<String> urlsToFetch = new LinkedList<>();

		int maxPairsToGet =
			Math.min(urlScorePairs.length, k_MAX_SEARCH_RESULTS);
		for (int i = 0; i < maxPairsToGet; ++i) {
			urlsToFetch.add(urlScorePairs[i].url);
		}

		Map<String, List<Object>> batchDocumentResults =
			d_connector.batchGetDocuments(urlsToFetch);
		Map<String, DdbDocument> batchDocs =
			cleanupDdbDocumentQueryResult(batchDocumentResults);
		Map<String, List<Object>> batchPgRankScores =
			d_connector.batchGetPageRankScore(urlsToFetch);
		Map<String, DdbPageRankScore> batchPgRanks =
			cleanupDdbPageRankQueryResult(batchPgRankScores);

		urlScorePairs = mergeWithPGRankAndSort(urlScorePairs, batchPgRanks);

		// Put the search results in
		ArrayList<SearchResult> results = new ArrayList<>();

		for (UrlScorePair p : urlScorePairs) {
			SearchResult r = makeSearchResultFrom(p, batchDocs);
			if (r != null) {
				results.add(r);
			}
		}

		root.put("searchResults", results);

		boolean shouldQueryAmazon =
			(request.getParameter("enable-amazon") != null);

		if (shouldQueryAmazon) {
			// Set input checkbox checked
			root.put("amazonChecked", "checked");
		} else {
			root.put("amazonChecked", "");
		}

		boolean shouldQueryEbay = (request.getParameter("enable-ebay") != null);

		if (shouldQueryEbay) {
			// Set input checkbox checked
			root.put("ebayChecked", "checked");
		} else {
			root.put("ebayChecked", "");
		}

		boolean shouldQueryYoutube =
			(request.getParameter("enable-youtube") != null);

		if (shouldQueryYoutube) {
			root.put("youtubeChecked", "checked");
		} else {
			root.put("youtubeChecked", "");
		}

		long endTime   = System.currentTimeMillis();
		long totalTime = endTime - startTime;
		root.put("time", String.valueOf(totalTime / 1000.0));
		try {
			d_searchResultTemplate.process(root, response.getWriter());
		} catch (TemplateException e) {
			throw new ServletException(e);
		}

	}
}
