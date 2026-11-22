package com.jarvis.solicitations;

/** One notice/amendment in a solicitation's history (chronological when listed). */
public record Amendment(String id, String title, String date, String summary, String url) {

    public Amendment {
        id = id == null ? "" : id.strip();
        title = title == null ? "" : title.strip();
        date = date == null ? "" : date.strip();
        summary = summary == null ? "" : summary.strip();
        url = url == null ? "" : url.strip();
    }
}
