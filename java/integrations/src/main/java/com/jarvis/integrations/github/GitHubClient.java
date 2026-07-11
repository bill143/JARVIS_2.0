package com.jarvis.integrations.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * A thin, typed view over the GitHub REST API, built on the {@link GitHubTransport} seam. Each
 * method maps to one documented REST endpoint (api.github.com, API version 2022-11-28), serializes
 * arguments with Jackson, and returns a short human/agent-readable summary. Every method is either
 * READ_ONLY or MUTATING — the destructive operations (merge, close, delete branch) are deliberately
 * not implemented in this pass (Phase 1 CHECKPOINT 1).
 *
 * <p>Errors carry the HTTP status and GitHub's own {@code message} field, never the token.
 */
public final class GitHubClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitHubTransport transport;

    public GitHubClient(GitHubTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    // ---- READ_ONLY -------------------------------------------------------------------------

    /** Lists the authenticated user's repositories (most-recently-updated first). */
    public String listRepos(int perPage) throws IOException, InterruptedException {
        JsonNode repos = getJson("/user/repos?per_page=" + clamp(perPage) + "&sort=updated");
        if (!repos.isArray() || repos.isEmpty()) {
            return "No repositories.";
        }
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (JsonNode r : repos) {
            out.append(++n).append(". ").append(r.path("full_name").asText())
                    .append(r.path("private").asBoolean() ? "  (private)" : "  (public)")
                    .append(desc(r.path("description").asText(""))).append('\n');
        }
        return out.toString().strip();
    }

    /** Lists issues in a repository ({@code state} = open/closed/all). */
    public String listIssues(String owner, String repo, String state)
            throws IOException, InterruptedException {
        JsonNode issues = getJson("/repos/" + seg(owner) + "/" + seg(repo)
                + "/issues?state=" + enc(orDefault(state, "open")) + "&per_page=20");
        if (!issues.isArray() || issues.isEmpty()) {
            return "No issues.";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode i : issues) {
            if (i.has("pull_request")) {
                continue;   // the issues endpoint also returns PRs; skip them here
            }
            out.append("#").append(i.path("number").asInt()).append(" [")
                    .append(i.path("state").asText()).append("] ")
                    .append(i.path("title").asText())
                    .append("  — @").append(i.path("user").path("login").asText()).append('\n');
        }
        String s = out.toString().strip();
        return s.isEmpty() ? "No issues." : s;
    }

    /** Reads a single issue. */
    public String getIssue(String owner, String repo, int number)
            throws IOException, InterruptedException {
        JsonNode i = getJson("/repos/" + seg(owner) + "/" + seg(repo) + "/issues/" + number);
        return "#" + i.path("number").asInt() + " [" + i.path("state").asText() + "] "
                + i.path("title").asText() + "\nby @" + i.path("user").path("login").asText()
                + "\n" + trim(i.path("body").asText(""), 500);
    }

    /** Lists pull requests in a repository ({@code state} = open/closed/all). */
    public String listPullRequests(String owner, String repo, String state)
            throws IOException, InterruptedException {
        JsonNode prs = getJson("/repos/" + seg(owner) + "/" + seg(repo)
                + "/pulls?state=" + enc(orDefault(state, "open")) + "&per_page=20");
        if (!prs.isArray() || prs.isEmpty()) {
            return "No pull requests.";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode p : prs) {
            out.append("#").append(p.path("number").asInt()).append(" [")
                    .append(p.path("state").asText()).append("] ")
                    .append(p.path("title").asText())
                    .append("  (").append(p.path("head").path("ref").asText())
                    .append(" → ").append(p.path("base").path("ref").asText()).append(")\n");
        }
        return out.toString().strip();
    }

    /** Reads a single pull request. */
    public String getPullRequest(String owner, String repo, int number)
            throws IOException, InterruptedException {
        JsonNode p = getJson("/repos/" + seg(owner) + "/" + seg(repo) + "/pulls/" + number);
        return "#" + p.path("number").asInt() + " [" + p.path("state").asText() + "] "
                + p.path("title").asText()
                + "\n" + p.path("head").path("ref").asText() + " → " + p.path("base").path("ref").asText()
                + "\nby @" + p.path("user").path("login").asText()
                + "\n" + trim(p.path("body").asText(""), 500);
    }

    /** Reads a file's contents (or lists a directory) at an optional {@code ref}. */
    public String readFile(String owner, String repo, String path, String ref)
            throws IOException, InterruptedException {
        String url = "/repos/" + seg(owner) + "/" + seg(repo) + "/contents/" + path;
        if (ref != null && !ref.isBlank()) {
            url += "?ref=" + enc(ref);
        }
        JsonNode node = getJson(url);
        if (node.isArray()) {                       // a directory listing
            StringBuilder out = new StringBuilder("Directory listing:\n");
            for (JsonNode e : node) {
                out.append("  ").append(e.path("type").asText()).append("  ")
                        .append(e.path("name").asText()).append('\n');
            }
            return out.toString().strip();
        }
        if (!"base64".equals(node.path("encoding").asText())) {
            return "(" + path + " is not a decodable text file)";
        }
        byte[] decoded = Base64.getMimeDecoder().decode(node.path("content").asText());
        return new String(decoded, StandardCharsets.UTF_8);
    }

    /** Lists recent commits on the default branch. */
    public String listCommits(String owner, String repo, int perPage)
            throws IOException, InterruptedException {
        JsonNode commits = getJson("/repos/" + seg(owner) + "/" + seg(repo)
                + "/commits?per_page=" + clamp(perPage));
        if (!commits.isArray() || commits.isEmpty()) {
            return "No commits.";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode c : commits) {
            String sha = c.path("sha").asText("");
            String msg = c.path("commit").path("message").asText("");
            int nl = msg.indexOf('\n');
            out.append(sha.isEmpty() ? "" : sha.substring(0, Math.min(7, sha.length())))
                    .append("  ").append(nl < 0 ? msg : msg.substring(0, nl))
                    .append("  — ").append(c.path("commit").path("author").path("name").asText())
                    .append('\n');
        }
        return out.toString().strip();
    }

    // ---- MUTATING (gated by the permission layer before they ever run) ----------------------

    /** Creates an issue. */
    public String createIssue(String owner, String repo, String title, String body)
            throws IOException, InterruptedException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("title", orDefault(title, "(no title)"));
        if (body != null && !body.isBlank()) {
            payload.put("body", body);
        }
        JsonNode i = postJson("/repos/" + seg(owner) + "/" + seg(repo) + "/issues", payload);
        return "Created issue #" + i.path("number").asInt() + ": " + i.path("html_url").asText();
    }

    /** Adds a comment to an issue or pull request. */
    public String commentIssue(String owner, String repo, int number, String body)
            throws IOException, InterruptedException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("body", orDefault(body, ""));
        JsonNode c = postJson("/repos/" + seg(owner) + "/" + seg(repo)
                + "/issues/" + number + "/comments", payload);
        return "Commented on #" + number + ": " + c.path("html_url").asText();
    }

    /** Opens a pull request from {@code head} into {@code base}. */
    public String openPullRequest(String owner, String repo, String title,
            String head, String base, String body) throws IOException, InterruptedException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("title", orDefault(title, "(no title)"));
        payload.put("head", orDefault(head, ""));
        payload.put("base", orDefault(base, ""));
        if (body != null && !body.isBlank()) {
            payload.put("body", body);
        }
        JsonNode p = postJson("/repos/" + seg(owner) + "/" + seg(repo) + "/pulls", payload);
        return "Opened PR #" + p.path("number").asInt() + ": " + p.path("html_url").asText();
    }

    // ---- internals --------------------------------------------------------------------------

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        return parse(require(transport.send("GET", path, null), "GitHub request"));
    }

    private JsonNode postJson(String path, JsonNode payload)
            throws IOException, InterruptedException {
        return parse(require(transport.send("POST", path, payload.toString()), "GitHub write"));
    }

    /** Ensures a 2xx response, else throws with the status + GitHub's message (never the token). */
    private static GitHubResponse require(GitHubResponse r, String action) throws IOException {
        if (r.ok()) {
            return r;
        }
        String message = "";
        try {
            message = MAPPER.readTree(r.body()).path("message").asText("");
        } catch (IOException ignore) {
            // non-JSON error body; fall back to the status alone
        }
        throw new IOException(action + " failed (HTTP " + r.status() + ")"
                + (message.isBlank() ? "" : ": " + message));
    }

    private static JsonNode parse(GitHubResponse r) throws IOException {
        return MAPPER.readTree(r.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Encodes a single path segment (owner/repo names) without turning '/' into %2F. */
    private static String seg(String s) {
        return enc(orDefault(s, "").strip());
    }

    private static String orDefault(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static int clamp(int perPage) {
        return Math.min(Math.max(1, perPage), 100);
    }

    private static String desc(String d) {
        return d.isBlank() ? "" : "  — " + trim(d, 100);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
