package com.jarvis.research;

import java.util.Objects;

/**
 * A numbered citation in a {@link ResearchReport}. The {@code index} is the {@code [n]} marker the
 * synthesized answer references; {@code title}/{@code url} let a reader follow it.
 *
 * @param index 1-based citation number
 * @param title source title
 * @param url source URL
 */
public record Source(int index, String title, String url) {

    public Source {
        if (index < 1) {
            throw new IllegalArgumentException("index must be >= 1, got " + index);
        }
        Objects.requireNonNull(url, "url");
        title = title == null ? "" : title;
    }
}
