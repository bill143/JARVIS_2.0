package com.jarvis.solicitations;

/**
 * A pointer to a file in an external document system — metadata only, never the bytes. {@code source}
 * attributes which connector produced it so the UI can badge and audit every reference.
 */
public record DocumentRef(String id, String name, String mimeType, Long size,
        String modifiedAt, String webUrl, String source) {

    public DocumentRef {
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        mimeType = mimeType == null ? "" : mimeType.strip();
        modifiedAt = modifiedAt == null ? "" : modifiedAt.strip();
        webUrl = webUrl == null ? "" : webUrl.strip();
        source = source == null ? "" : source.strip();
    }
}
