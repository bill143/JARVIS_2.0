package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.documents.ExtractedText;
import com.jarvis.documents.FileTextExtractor;
import com.jarvis.tools.RiskTier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the files a user has uploaded in the current session so the assistant can read them.
 *
 * <p>Each upload is run through {@link FileTextExtractor}, the extracted text is kept in memory
 * (never written to disk here), and the metadata is exposed to the dashboard. When the user asks a
 * question with files attached, {@link #contextFor} builds a bounded context block that the chat
 * endpoint prepends to the prompt — that is how "the app can read the file". Every upload and
 * removal is written to the {@link AuditLog}.
 *
 * <p>Local-first and bounded: at most {@link #MAX_DOCS} documents are retained (oldest evicted), and
 * the injected context is capped so a large file can't blow the prompt budget.
 */
public final class UploadedDocsService {

    /** Maximum documents retained at once; the oldest is evicted beyond this. */
    public static final int MAX_DOCS = 50;

    /** Default cap on characters injected into a single chat prompt across all attached docs. */
    public static final int DEFAULT_CONTEXT_BUDGET = 24_000;

    /** Metadata about one uploaded document (never carries the full text). */
    public record Doc(String id, String filename, String kind, int chars, boolean truncated,
            String note, long uploadedAt) {
    }

    private record Stored(Doc meta, String text) {
    }

    private final FileTextExtractor extractor = new FileTextExtractor();
    private final AuditLog audit; // nullable
    private final LinkedHashMap<String, Stored> store = new LinkedHashMap<>();
    private int seq = 0;

    public UploadedDocsService(AuditLog audit) {
        this.audit = audit;
    }

    /** Extracts and stores {@code bytes}, returning the resulting metadata. Never throws. */
    public synchronized Doc add(String filename, byte[] bytes) {
        ExtractedText ex = extractor.extract(filename, bytes);
        String id = "doc" + (++seq);
        Doc meta = new Doc(id, sanitizeName(filename), ex.kind(), ex.chars(), ex.truncated(),
                ex.note(), System.currentTimeMillis());
        store.put(id, new Stored(meta, ex.text()));
        while (store.size() > MAX_DOCS) {
            Iterator<String> it = store.keySet().iterator();
            it.next();
            it.remove();
        }
        boolean ok = !"unsupported".equals(ex.kind());
        record("document_upload", meta.filename() + " (" + ex.kind() + ", " + ex.chars() + " chars)",
                ok ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE);
        return meta;
    }

    /** Metadata for every retained document, oldest first. */
    public synchronized List<Doc> list() {
        List<Doc> out = new ArrayList<>();
        for (Stored s : store.values()) {
            out.add(s.meta());
        }
        return out;
    }

    public synchronized Optional<Doc> get(String id) {
        Stored s = store.get(id);
        return s == null ? Optional.empty() : Optional.of(s.meta());
    }

    /** The extracted text for {@code id}, or {@code null} if unknown. */
    public synchronized String textOf(String id) {
        Stored s = store.get(id);
        return s == null ? null : s.text();
    }

    public synchronized boolean remove(String id) {
        boolean had = store.remove(id) != null;
        if (had) {
            record("document_remove", id, AuditOutcome.SUCCESS);
        }
        return had;
    }

    public synchronized int count() {
        return store.size();
    }

    /**
     * Builds a prompt-ready context block for the given document ids, honouring a total character
     * budget across all of them. Unknown ids and empty extractions are skipped.
     *
     * @return the block (leading with a blank line) or an empty string when nothing is attachable
     */
    public synchronized String contextFor(Collection<String> ids, int maxChars) {
        if (ids == null || ids.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int budget = maxChars;
        for (String id : ids) {
            Stored s = store.get(id);
            if (s == null || s.text().isEmpty()) {
                continue;
            }
            String header = "\n\n[Attached file: " + s.meta().filename() + "]\n";
            String body = s.text();
            if (body.length() > budget) {
                body = body.substring(0, Math.max(0, budget)) + "\n[...truncated...]";
            }
            sb.append(header).append(body);
            budget -= header.length() + body.length();
            if (budget <= 0) {
                break;
            }
        }
        return sb.toString();
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        audit.record(new AuditEvent(AuditCategory.TOOL_INVOCATION, action, AuditTrigger.USER,
                RiskTier.READ_ONLY, outcome, detail));
    }

    /** Strips any path and control characters from a client-supplied filename and bounds its length. */
    private static String sanitizeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload";
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank()) {
            return "upload";
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }
}
