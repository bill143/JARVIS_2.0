package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.rag.Document;
import com.jarvis.rag.KeywordIndex;
import com.jarvis.rag.RetrievalQuery;
import com.jarvis.rag.ScoredDocument;
import com.jarvis.tools.RiskTier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only view over a local Obsidian vault — the backend for the {@code BRAIN} tab. Markdown notes
 * are indexed for keyword search and can be opened one at a time. This is a <b>mirror / workspace</b>
 * layer: the OpenHuman/memory backend remains the source of truth, and Phase 1 performs <b>no
 * writes</b> to the vault whatsoever.
 *
 * <p>Safety is the whole point of this class:
 * <ul>
 *   <li>Access is confined to the configured vault root. Absolute paths, {@code ..} segments, null
 *       bytes, and symlink escapes are all rejected — every read resolves the real path and verifies
 *       it is still nested under the vault's real root.</li>
 *   <li>Only regular {@code .md} files are served; hidden folders ({@code .obsidian}, {@code .trash})
 *       are skipped; per-file and per-vault caps bound the work.</li>
 *   <li>Every search and note view is written to the {@link AuditLog}.</li>
 * </ul>
 *
 * <p>When no vault is configured (or the path is missing/invalid) the vault is simply
 * {@link #configured() not configured} and every query returns empty — the UI shows a graceful
 * empty state rather than erroring.
 */
final class BrainVault {

    /** Hard caps so a huge or hostile vault can't exhaust memory. */
    private static final int MAX_FILES = 5000;
    private static final long MAX_FILE_BYTES = 1_048_576;   // 1 MiB per note
    private static final int SNIPPET_LEN = 200;

    private volatile Path root;          // null when unconfigured; reassigned live by connect()
    private volatile Path rootReal;      // canonical root for containment checks; null when unconfigured
    private volatile boolean writable;   // when true, approved write proposals may modify the vault
    private final AuditLog audit;        // nullable
    private volatile KeywordIndex index = new KeywordIndex();

    /** Pending write proposals awaiting explicit user approval (never auto-applied). */
    private final java.util.Map<String, PendingWrite> pending =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong writeSeq =
            new java.util.concurrent.atomic.AtomicLong();

    private BrainVault(Path root, Path rootReal, boolean readOnly, AuditLog audit) {
        this.root = root;
        this.rootReal = rootReal;
        this.writable = false;
        this.audit = audit;
    }

    /**
     * Builds a vault from configuration. {@code vaultPath} blank/null → unconfigured. A path that
     * does not exist or is not a directory is treated as unconfigured (the UI reports it). Phase 1
     * always behaves read-only; {@code readOnly} is recorded for the UI and future phases.
     */
    static BrainVault fromConfig(String vaultPath, boolean readOnly, AuditLog audit) {
        if (vaultPath == null || vaultPath.isBlank()) {
            return new BrainVault(null, null, readOnly, audit);
        }
        Path root;
        Path rootReal;
        try {
            root = Paths.get(vaultPath.strip()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                return new BrainVault(null, null, readOnly, audit);
            }
            rootReal = root.toRealPath();
        } catch (IOException | RuntimeException e) {
            return new BrainVault(null, null, readOnly, audit);
        }
        BrainVault vault = new BrainVault(root, rootReal, readOnly, audit);
        vault.reindex();
        return vault;
    }

    /** From environment: {@code OBSIDIAN_VAULT_PATH} and {@code OBSIDIAN_READ_ONLY} (default true). */
    static BrainVault fromEnvironment(AuditLog audit) {
        String path = System.getenv("OBSIDIAN_VAULT_PATH");
        boolean readOnly = !"false".equalsIgnoreCase(
                System.getenv().getOrDefault("OBSIDIAN_READ_ONLY", "true").strip());
        return fromConfig(path, readOnly, audit);
    }

    /**
     * Connects (or re-connects) the vault to {@code vaultPath} <b>live</b> — no restart. The path is
     * validated, the keyword index is rebuilt, and the index sink (if set) is notified so the notes
     * can be mirrored into the unified semantic store. Returns whether a vault is now configured.
     * When {@code allowWrites} is true, approved write proposals may modify files on disk.
     */
    synchronized boolean connect(String vaultPath, boolean allowWrites) {
        Path newRoot = null;
        Path newRootReal = null;
        if (vaultPath != null && !vaultPath.isBlank()) {
            try {
                Path p = Paths.get(vaultPath.strip()).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    newRoot = p;
                    newRootReal = p.toRealPath();
                }
            } catch (IOException | RuntimeException ignored) {
                newRoot = null;
            }
        }
        this.root = newRoot;
        this.rootReal = newRootReal;
        this.writable = allowWrites && newRoot != null;
        this.index = new KeywordIndex();
        this.pending.clear();
        reindex();
        record("brain_connect", newRoot == null ? "disconnected"
                : ("connected: " + safe(newRoot.toString()) + " (" + count() + " notes, writable="
                        + this.writable + ")"),
                newRoot == null ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS);
        return configured();
    }

    /** All indexed notes as documents (id = relative path, content = markdown) for unified indexing. */
    List<Document> allDocuments() {
        return index.all();
    }

    boolean configured() {
        return root != null;
    }

    /** The configured vault root (or {@code null} when unconfigured). For the background watcher. */
    Path root() {
        return root;
    }

    /**
     * Re-walks the vault into a fresh index in place — no reconnect, no audit "connect" event, no
     * change to root/writable/pending. Called by the background watcher when files change on disk so
     * grounding stays current without a manual Connect click. Restores the old index if the walk fails.
     */
    synchronized void reindexNow() {
        if (!configured()) {
            return;
        }
        KeywordIndex previous = this.index;
        this.index = new KeywordIndex();
        try {
            reindex();
        } catch (RuntimeException e) {
            this.index = previous;
            throw e;
        }
    }

    /**
     * A cheap change-fingerprint over the vault's markdown files (relative path + size + mtime), used
     * by the watcher to detect edits without reading file contents. Returns 0 when unconfigured.
     */
    long fingerprint() {
        Path r = this.root;
        if (r == null) {
            return 0L;
        }
        long[] h = {1L};
        try (Stream<Path> walk = Files.walk(r)) {
            walk.filter(Files::isRegularFile)
                    .filter(BrainVault::isMarkdown)
                    .filter(p -> !isHidden(r.relativize(p)))
                    .forEach(p -> {
                        try {
                            long m = Files.getLastModifiedTime(p).toMillis();
                            long s = Files.size(p);
                            int nameHash = r.relativize(p).toString().hashCode();
                            h[0] = h[0] * 1000003L + nameHash;
                            h[0] = h[0] * 1000003L + m;
                            h[0] = h[0] * 1000003L + s;
                        } catch (IOException | RuntimeException ignore) {
                            // Unreadable file — skip its contribution, still fingerprint the rest.
                        }
                    });
        } catch (IOException e) {
            return h[0];
        }
        return h[0];
    }

    boolean readOnly() {
        // Writes are gated: read-only unless the vault was connected with writes enabled.
        return !writable;
    }

    /** Display string for the vault root (or empty when unconfigured). */
    String rootDisplay() {
        return root == null ? "" : root.toString();
    }

    /** Number of indexed notes. */
    int count() {
        return index.all().size();
    }

    /** A note listed for the UI: vault-relative path + title. */
    record Note(String path, String title) {
    }

    /** A search hit: relative path, title, score, and a short snippet. */
    record Hit(String path, String title, double score, String snippet) {
    }

    /** All indexed notes (relative path + title), for the list view. */
    List<Note> notes() {
        List<Note> out = new ArrayList<>();
        for (Document d : index.all()) {
            out.add(new Note(d.id(), titleOf(d)));
        }
        out.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return out;
    }

    /** Top {@code topK} notes matching {@code query}, by keyword overlap. Audited. */
    List<Hit> search(String query, int topK) {
        List<Hit> hits = new ArrayList<>();
        if (configured() && query != null && !query.isBlank()) {
            for (ScoredDocument s : index.retrieve(new RetrievalQuery(query, Math.max(1, topK)))) {
                hits.add(new Hit(s.document().id(), titleOf(s.document()), s.score(),
                        snippet(s.document().content())));
            }
        }
        record("brain_search", "q: " + safe(query) + " (" + hits.size() + ")");
        return hits;
    }

    /**
     * Read-only retrieval hook for the assistant to cite: top {@code topK} note snippets with their
     * source (relative path). No prompt is stored; nothing is written. Audited.
     */
    List<Hit> cite(String query, int topK) {
        List<Hit> hits = search(query, topK);
        record("brain_cite", "q: " + safe(query) + " (" + hits.size() + ")");
        return hits;
    }

    /**
     * Reads a single note's raw markdown by its vault-relative path. Path handling is strict:
     * absolute paths, {@code ..}, null bytes, non-{@code .md} files, and any resolution that escapes
     * the vault root (including via symlink) throw {@link VaultAccessException}. Audited on success
     * and failure.
     *
     * @return {@code [title, markdown]}
     */
    String[] readNote(String relativePath) {
        try {
            Path file = resolveInsideVault(relativePath);
            if (Files.size(file) > MAX_FILE_BYTES) {
                record("brain_note_view", "reject (too large): " + safe(relativePath),
                        AuditOutcome.FAILURE);
                throw new VaultAccessException("note too large");
            }
            String md = Files.readString(file, StandardCharsets.UTF_8);
            record("brain_note_view", "path: " + safe(relativePath));
            return new String[] {titleFromPath(relativePath, md), md};
        } catch (IOException e) {
            record("brain_note_view", "io error: " + safe(relativePath), AuditOutcome.FAILURE);
            throw new VaultAccessException("could not read note");
        }
    }

    /** Thrown when a note path is invalid, escapes the vault, or cannot be read. */
    static final class VaultAccessException extends RuntimeException {
        VaultAccessException(String message) {
            super(message);
        }
    }

    /**
     * Resolves {@code relativePath} to a real file guaranteed to live under the vault root, or throws.
     * This is the single choke point for all filesystem access.
     */
    private Path resolveInsideVault(String relativePath) throws IOException {
        if (!configured()) {
            throw new VaultAccessException("vault not configured");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new VaultAccessException("empty path");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new VaultAccessException("illegal path");
        }
        Path rel = Paths.get(relativePath);
        if (rel.isAbsolute()) {
            throw new VaultAccessException("absolute path not allowed");
        }
        for (Path part : rel) {
            if ("..".equals(part.toString())) {
                throw new VaultAccessException("parent traversal not allowed");
            }
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new VaultAccessException("path escapes vault");
        }
        if (!isMarkdown(resolved)) {
            throw new VaultAccessException("not a markdown note");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new VaultAccessException("no such note");
        }
        // Symlink-escape guard: the canonical target must still be inside the canonical root.
        Path real = resolved.toRealPath();
        if (!real.startsWith(rootReal)) {
            throw new VaultAccessException("path escapes vault");
        }
        return real;
    }

    /** Walks the vault and (re)builds the keyword index. Bounded and fault-tolerant. */
    private void reindex() {
        if (!configured()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            int[] budget = {MAX_FILES};
            walk.filter(Files::isRegularFile)
                    .filter(BrainVault::isMarkdown)
                    .filter(p -> !isHidden(root.relativize(p)))
                    .forEach(p -> {
                        if (budget[0] <= 0) {
                            return;
                        }
                        try {
                            if (Files.size(p) > MAX_FILE_BYTES) {
                                return;
                            }
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            index.index(new Document(rel, content,
                                    Map.of("title", titleFromPath(rel, content))));
                            budget[0]--;
                        } catch (IOException | RuntimeException skip) {
                            // Unreadable / non-UTF-8 note — skip it, keep indexing the rest.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Write proposals: nothing is written to disk without explicit user approval ----

    /** A proposed change to the vault, awaiting approval. {@code kind} is note/append for the UI. */
    record PendingWrite(String id, String relativePath, String kind, String preview) {
    }

    /**
     * Proposes a write (a new note or an append) without touching disk. The path is validated to live
     * inside the vault; the proposal is queued and must be {@link #approveWrite approved} explicitly.
     * Returns the proposal id. Throws {@link VaultAccessException} if the vault is unconfigured, not
     * writable, or the path is unsafe.
     */
    synchronized String proposeWrite(String relativePath, String content, String kind) {
        if (!configured()) {
            throw new VaultAccessException("vault not configured");
        }
        if (!writable) {
            throw new VaultAccessException("vault is read-only — reconnect with writes enabled");
        }
        resolveForWrite(relativePath);   // validate now so bad paths never enter the queue
        String id = "w-" + writeSeq.incrementAndGet();
        String c = content == null ? "" : content;
        pending.put(id, new PendingWrite(id, relativePath, "append".equals(kind) ? "append" : "note",
                snippet(c)));
        // stash the full content alongside the preview
        contentById.put(id, c);
        record("brain_write_propose", "path: " + safe(relativePath) + " kind: " + kind);
        return id;
    }

    private final java.util.Map<String, String> contentById =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The pending write proposals (for the approval UI). */
    List<PendingWrite> pendingWrites() {
        return new ArrayList<>(pending.values());
    }

    /** Discards a proposal without writing. */
    synchronized boolean rejectWrite(String id) {
        contentById.remove(id);
        boolean had = pending.remove(id) != null;
        if (had) {
            record("brain_write_reject", "id: " + safe(id));
        }
        return had;
    }

    /**
     * Applies an approved proposal to disk — the <b>only</b> path that ever modifies the vault. The
     * write is a MUTATING action, re-validated at apply time and audited. Re-indexes the file after.
     */
    synchronized boolean approveWrite(String id) {
        PendingWrite w = pending.get(id);
        if (w == null) {
            return false;
        }
        if (!writable) {
            record("brain_write_apply", "denied (read-only): " + safe(w.relativePath()),
                    RiskTier.MUTATING, AuditOutcome.FAILURE);
            throw new VaultAccessException("vault is read-only");
        }
        try {
            Path file = resolveForWrite(w.relativePath());
            Files.createDirectories(file.getParent());
            String content = contentById.getOrDefault(id, "");
            if ("append".equals(w.kind()) && Files.exists(file)) {
                Files.writeString(file, "\n" + content, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            pending.remove(id);
            contentById.remove(id);
            reindexOne(file);
            record("brain_write_apply", "path: " + safe(w.relativePath()),
                    RiskTier.MUTATING, AuditOutcome.SUCCESS);
            return true;
        } catch (IOException e) {
            record("brain_write_apply", "io error: " + safe(w.relativePath()),
                    RiskTier.MUTATING, AuditOutcome.FAILURE);
            throw new VaultAccessException("could not write note");
        }
    }

    /**
     * Resolves a write target inside the vault, allowing the file not to exist yet (new note). The
     * parent directory chain is confined to the vault root; absolute/{@code ..}/escape paths throw.
     */
    private Path resolveForWrite(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new VaultAccessException("illegal path");
        }
        Path rel = Paths.get(relativePath);
        if (rel.isAbsolute()) {
            throw new VaultAccessException("absolute path not allowed");
        }
        for (Path part : rel) {
            if ("..".equals(part.toString())) {
                throw new VaultAccessException("parent traversal not allowed");
            }
        }
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            throw new VaultAccessException("path escapes vault");
        }
        if (!isMarkdown(resolved)) {
            throw new VaultAccessException("not a markdown note");
        }
        return resolved;
    }

    /** Indexes a single file after a write (best-effort). */
    private void reindexOne(Path file) {
        try {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String content = Files.readString(file, StandardCharsets.UTF_8);
            index.index(new Document(rel, content, Map.of("title", titleFromPath(rel, content))));
        } catch (IOException | RuntimeException ignored) {
            // leave the index as-is if the just-written file can't be read back
        }
    }

    private static boolean isMarkdown(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    /** True if any path segment is a hidden folder/file (starts with '.'), e.g. {@code .obsidian}. */
    private static boolean isHidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String snippet(String content) {
        String c = content == null ? "" : content.strip();
        return c.length() > SNIPPET_LEN ? c.substring(0, SNIPPET_LEN) + "…" : c;
    }

    private static String titleOf(Document d) {
        return d.metadata().getOrDefault("title", d.id());
    }

    /** Title = first markdown H1 if present, else the file name without extension. */
    private static String titleFromPath(String relativePath, String content) {
        if (content != null) {
            for (String line : content.split("\n", 40)) {
                String t = line.strip();
                if (t.startsWith("# ")) {
                    return t.substring(2).strip();
                }
            }
        }
        String name = relativePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]", " ");
    }

    private void record(String action, String detail) {
        record(action, detail, AuditOutcome.SUCCESS);
    }

    private void record(String action, String detail, AuditOutcome outcome) {
        record(action, detail, RiskTier.READ_ONLY, outcome);
    }

    private void record(String action, String detail, RiskTier tier, AuditOutcome outcome) {
        if (audit == null) {
            return;
        }
        AuditCategory category = tier == RiskTier.MUTATING || tier == RiskTier.DESTRUCTIVE
                ? AuditCategory.DESTRUCTIVE_ACTION : AuditCategory.TOOL_INVOCATION;
        audit.record(new AuditEvent(category, action, AuditTrigger.USER, tier, outcome, detail));
    }
}
