package com.jarvis.integrations.mark;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * News search with the Mark-XLVIII "parallel search, first result wins" pattern: two RSS backends
 * (Google News, Bing News) race and whichever returns a valid result first is used; the loser is
 * discarded. Items are real articles — title, source, link — never homepages.
 *
 * <p>Stdlib only: {@link HttpClient} for fetching, JAXP for RSS parsing. The fetcher is a seam so
 * tests run without network access.
 */
public final class NewsTool implements Tool {

    /** Fetch seam: URL in, response body out. */
    @FunctionalInterface
    public interface UrlFetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    private static final int MAX_ITEMS = 5;
    private static final int RACE_TIMEOUT_SECONDS = 12;

    private final UrlFetcher fetcher;

    public NewsTool() {
        this(defaultFetcher());
    }

    public NewsTool(UrlFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public String name() {
        return "news_search";
    }

    @Override
    public String description() {
        return "Search recent news headlines. Args: query (optional; omit for top headlines).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object rawQuery = call.arguments().get("query");
        String query = rawQuery == null ? "" : String.valueOf(rawQuery).strip();
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);

        String googleUrl = query.isEmpty()
                ? "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"
                : "https://news.google.com/rss/search?q=" + encoded + "&hl=en-US&gl=US&ceid=US:en";
        String bingUrl = query.isEmpty()
                ? "https://www.bing.com/news/search?q=top+stories&format=rss"
                : "https://www.bing.com/news/search?q=" + encoded + "&format=rss";

        List<Callable<List<String>>> backends = List.of(
                () -> parseItems(fetcher.fetch(googleUrl)),
                () -> parseItems(fetcher.fetch(bingUrl)));

        ExecutorService race = Executors.newFixedThreadPool(backends.size());
        try {
            List<String> items = race.invokeAny(backends, RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ToolResult.ok(String.join("\n", items));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("news search interrupted");
        } catch (Exception e) {
            return ToolResult.error("no news backend returned results: " + e.getMessage());
        } finally {
            race.shutdownNow();
        }
    }

    /** Parses RSS into display lines; throws if the feed yields nothing (so the race moves on). */
    private static List<String> parseItems(String rssXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var builder = factory.newDocumentBuilder();
        // Quiet error handler: a backend returning HTML (with a DOCTYPE) rejects cleanly and the
        // race falls back to the other source, without the default parser printing to stderr.
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            public void warning(org.xml.sax.SAXParseException e) { }
            public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        Document doc = builder.parse(
                new ByteArrayInputStream(rssXml.getBytes(StandardCharsets.UTF_8)));

        NodeList items = doc.getElementsByTagName("item");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < items.getLength() && lines.size() < MAX_ITEMS; i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            if (title.isBlank() || link.isBlank()) {
                continue;
            }
            String source = text(item, "source");
            lines.add((lines.size() + 1) + ". " + title
                    + (source.isBlank() ? "" : " (" + source + ")")
                    + "\n   " + link);
        }
        if (lines.isEmpty()) {
            throw new IOException("feed contained no articles");
        }
        return lines;
    }

    private static String text(Element item, String tag) {
        NodeList nodes = item.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static UrlFetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (JARVIS news tool)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("news backend returned " + response.statusCode());
            }
            return response.body();
        };
    }
}
