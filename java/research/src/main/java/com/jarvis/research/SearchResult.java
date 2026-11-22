package com.jarvis.research;

import java.util.Objects;

/**
 * One web search hit: title, URL, and a snippet (or fetched excerpt). The URL is the citation
 * anchor — it is preserved end-to-end so every synthesized claim can be traced to a source.
 *
 * @param title the result title
 * @param url the source URL (citation anchor)
 * @param snippet a short excerpt (search snippet, or fetched-and-trimmed content)
 */
public record SearchResult(String title, String url, String snippet) {

    public SearchResult {
        Objects.requireNonNull(url, "url");
        title = title == null ? "" : title;
        snippet = snippet == null ? "" : snippet;
    }

    /** A copy with {@code snippet} replaced (used after deep-fetch enrichment). */
    public SearchResult withSnippet(String newSnippet) {
        return new SearchResult(title, url, newSnippet);
    }
}
