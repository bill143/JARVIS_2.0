package com.jarvis.solicitations;

/**
 * An attachment/notice document linked to a solicitation. {@code size} is a {@link Long} because the
 * source often omits it. {@code source} records which system produced the link (e.g. {@code sam.gov})
 * so the UI can badge it and every open can be attributed.
 */
public record SolicitationDocument(String name, String url, String type, Long size,
        String publishedAt, String source) {

    public SolicitationDocument {
        name = name == null ? "" : name.strip();
        url = url == null ? "" : url.strip();
        type = type == null ? "" : type.strip();
        publishedAt = publishedAt == null ? "" : publishedAt.strip();
        source = source == null ? "" : source.strip();
    }
}
