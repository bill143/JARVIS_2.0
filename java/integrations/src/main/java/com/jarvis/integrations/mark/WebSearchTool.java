package com.jarvis.integrations.mark;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-mode web search over DuckDuckGo Lite (no API key): modes {@code search}, {@code research},
 * {@code price}, {@code compare} shape the query before searching; results are titles + links.
 * (News has its own dedicated {@link NewsTool}.) Fetch seam keeps tests offline.
 */
public final class WebSearchTool implements Tool {

    /** Fetch seam: URL in, HTML out. */
    @FunctionalInterface
    public interface UrlFetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    private static final int MAX_ITEMS = 5;
    // Tolerant: match any anchor, then filter to real result links. Works across DDG Lite/HTML
    // markup variants (class names change; the uddg= redirect and external hrefs do not).
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    private final UrlFetcher fetcher;

    public WebSearchTool() {
        this(defaultFetcher());
    }

    public WebSearchTool(UrlFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web. Args: query, and optional mode - "
                + "search (default), research, price, or compare.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object rawQuery = call.arguments().get("query");
        if (rawQuery == null || String.valueOf(rawQuery).isBlank()) {
            return ToolResult.error("web_search needs a 'query' argument");
        }
        String query = String.valueOf(rawQuery).strip();
        String mode = String.valueOf(call.arguments().getOrDefault("mode", "search"))
                .toLowerCase(Locale.ROOT).strip();
        String shaped = switch (mode) {
            case "research" -> query + " overview explained in depth";
            case "price" -> query + " price buy cost";
            case "compare" -> query + " vs comparison pros and cons";
            case "search", "" -> query;
            default -> query;
        };

        try {
            String html = fetcher.fetch("https://lite.duckduckgo.com/lite/?q="
                    + URLEncoder.encode(shaped, StandardCharsets.UTF_8));
            List<String> results = parse(html);
            if (results.isEmpty()) {
                return ToolResult.error("no results for '" + query + "'");
            }
            return ToolResult.ok("[" + mode + "] results for \"" + query + "\":\n"
                    + String.join("\n", results));
        } catch (IOException e) {
            return ToolResult.error("web search failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("web search interrupted");
        }
    }

    private static List<String> parse(String html) {
        List<String> results = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find() && results.size() < MAX_ITEMS) {
            String rawHref = m.group(1);
            // Keep only genuine result links: DDG redirect wrappers or external http(s) URLs.
            boolean isResult = rawHref.contains("uddg=")
                    || rawHref.startsWith("http://") || rawHref.startsWith("https://");
            if (!isResult || rawHref.contains("duckduckgo.com/settings")
                    || rawHref.contains("duckduckgo.com/about")) {
                continue;
            }
            String href = decodeRedirect(rawHref);
            String title = unescape(TAGS.matcher(m.group(2)).replaceAll("").strip());
            if (title.isBlank() || title.length() < 3 || href.isBlank() || !seen.add(href)) {
                continue;
            }
            results.add((results.size() + 1) + ". " + title + "\n   " + href);
        }
        return results;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }

    /** DDG Lite wraps links as //duckduckgo.com/l/?uddg=<encoded>; unwrap to the real URL. */
    private static String decodeRedirect(String href) {
        int u = href.indexOf("uddg=");
        if (u < 0) {
            return href.startsWith("//") ? "https:" + href : href;
        }
        String enc = href.substring(u + 5);
        int amp = enc.indexOf('&');
        if (amp >= 0) {
            enc = enc.substring(0, amp);
        }
        return URLDecoder.decode(enc, StandardCharsets.UTF_8);
    }

    private static UrlFetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8)).build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (JARVIS web search)")
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("search backend returned " + response.statusCode());
            }
            return response.body();
        };
    }
}
