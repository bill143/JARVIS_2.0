package com.jarvis.integrations.github;

import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.util.List;
import java.util.Objects;

/**
 * GitHub agent tools, delegating to a {@link GitHubClient}. This is the Phase 5 reference
 * integration: every tool is declared in {@code /manifests/github.json} with an explicit
 * {@link com.jarvis.tools.RiskTier}, so the governance layer gates MUTATING calls behind a user
 * confirmation before they execute. The DESTRUCTIVE actions (merge PR, close issue/PR, delete
 * branch) are intentionally absent from this pass — they arrive after CHECKPOINT 1.
 *
 * <p>Any failure — including "not configured" when no token is present — is a failed
 * {@link ToolResult}, never a throw, so the assistant degrades gracefully.
 */
public final class GitHubPlugin implements Plugin {

    private final GitHubClient client;

    public GitHubPlugin(GitHubTransport transport) {
        this(new GitHubClient(transport));
    }

    public GitHubPlugin(GitHubClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("github", "0.1.0",
                "GitHub: repos, issues, pull requests, file contents and commit history "
                        + "(read); create issue, comment, open PR (write, gated)");
    }

    @Override
    public List<Tool> tools() {
        return List.of(
                // READ_ONLY
                listRepos(), listIssues(), getIssue(), listPullRequests(), getPullRequest(),
                readFile(), listCommits(),
                // MUTATING (permission-gated by risk tier in the manifest)
                createIssue(), commentIssue(), openPullRequest());
    }

    // ---- READ_ONLY tools --------------------------------------------------------------------

    private Tool listRepos() {
        return tool("github_list_repos",
                "List your GitHub repositories. Args: max (optional, default 20).",
                call -> ToolResult.ok(client.listRepos(intArg(call, "max", 20))));
    }

    private Tool listIssues() {
        return tool("github_list_issues",
                "List issues in a repo. Args: owner, repo (required), state (open/closed/all, "
                        + "default open).",
                call -> ToolResult.ok(client.listIssues(
                        str(call, "owner"), str(call, "repo"), str(call, "state"))));
    }

    private Tool getIssue() {
        return tool("github_get_issue",
                "Read one issue. Args: owner, repo, number (required).",
                call -> ToolResult.ok(client.getIssue(
                        str(call, "owner"), str(call, "repo"), intArg(call, "number", 0))));
    }

    private Tool listPullRequests() {
        return tool("github_list_pull_requests",
                "List pull requests in a repo. Args: owner, repo (required), state (open/closed/all, "
                        + "default open).",
                call -> ToolResult.ok(client.listPullRequests(
                        str(call, "owner"), str(call, "repo"), str(call, "state"))));
    }

    private Tool getPullRequest() {
        return tool("github_get_pull_request",
                "Read one pull request. Args: owner, repo, number (required).",
                call -> ToolResult.ok(client.getPullRequest(
                        str(call, "owner"), str(call, "repo"), intArg(call, "number", 0))));
    }

    private Tool readFile() {
        return tool("github_read_file",
                "Read a file's contents (or list a directory) in a repo. Args: owner, repo, path "
                        + "(required), ref (optional branch/tag/sha).",
                call -> ToolResult.ok(client.readFile(
                        str(call, "owner"), str(call, "repo"), str(call, "path"), str(call, "ref"))));
    }

    private Tool listCommits() {
        return tool("github_list_commits",
                "List recent commits in a repo. Args: owner, repo (required), max (optional, "
                        + "default 20).",
                call -> ToolResult.ok(client.listCommits(
                        str(call, "owner"), str(call, "repo"), intArg(call, "max", 20))));
    }

    // ---- MUTATING tools (gated before execution) --------------------------------------------

    private Tool createIssue() {
        return tool("github_create_issue",
                "Create an issue. Args: owner, repo, title (required), body (optional).",
                call -> ToolResult.ok(client.createIssue(
                        str(call, "owner"), str(call, "repo"), str(call, "title"), str(call, "body"))));
    }

    private Tool commentIssue() {
        return tool("github_comment_issue",
                "Comment on an issue or PR. Args: owner, repo, number, body (required).",
                call -> ToolResult.ok(client.commentIssue(
                        str(call, "owner"), str(call, "repo"),
                        intArg(call, "number", 0), str(call, "body"))));
    }

    private Tool openPullRequest() {
        return tool("github_open_pull_request",
                "Open a pull request. Args: owner, repo, title, head, base (required), body "
                        + "(optional). head/base are branch names.",
                call -> ToolResult.ok(client.openPullRequest(
                        str(call, "owner"), str(call, "repo"), str(call, "title"),
                        str(call, "head"), str(call, "base"), str(call, "body"))));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Tool tool(String name, String desc, ThrowingBody body) {
        return new Tool() {
            public String name() {
                return name;
            }

            public String description() {
                return desc;
            }

            public ToolResult execute(ToolCall call) {
                try {
                    return body.run(call);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error(name + " interrupted");
                } catch (Exception e) {
                    return ToolResult.error(name + " failed: " + e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingBody {
        ToolResult run(ToolCall call) throws Exception;
    }

    private static String str(ToolCall call, String key) {
        Object v = call.arguments().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intArg(ToolCall call, String key, int fallback) {
        Object v = call.arguments().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
