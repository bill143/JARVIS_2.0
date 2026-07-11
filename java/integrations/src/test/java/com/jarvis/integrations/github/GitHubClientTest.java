package com.jarvis.integrations.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class GitHubClientTest {

    /** A recording fake transport: captures the last request and returns a scripted response. */
    private static final class FakeTransport implements GitHubTransport {
        String method;
        String path;
        String body;
        GitHubResponse next;

        FakeTransport(GitHubResponse next) {
            this.next = next;
        }

        @Override
        public GitHubResponse send(String method, String path, String jsonBody) {
            this.method = method;
            this.path = path;
            this.body = jsonBody;
            return next;
        }
    }

    @Test
    void listIssuesReadsAndSummarizesAndHitsTheRightEndpoint() throws Exception {
        FakeTransport t = new FakeTransport(new GitHubResponse(200,
                "[{\"number\":7,\"state\":\"open\",\"title\":\"Bug: crash on start\","
                        + "\"user\":{\"login\":\"bill\"}}]"));
        String out = new GitHubClient(t).listIssues("o", "r", "open");
        assertEquals("GET", t.method);
        assertTrue(t.path.startsWith("/repos/o/r/issues?state=open"));
        assertTrue(out.contains("#7"));
        assertTrue(out.contains("Bug: crash on start"));
        assertTrue(out.contains("@bill"));
    }

    @Test
    void listIssuesSkipsPullRequestsReturnedByTheIssuesEndpoint() throws Exception {
        FakeTransport t = new FakeTransport(new GitHubResponse(200,
                "[{\"number\":9,\"state\":\"open\",\"title\":\"a PR\",\"user\":{\"login\":\"x\"},"
                        + "\"pull_request\":{\"url\":\"...\"}}]"));
        assertEquals("No issues.", new GitHubClient(t).listIssues("o", "r", "open"));
    }

    @Test
    void readFileBase64DecodesContent() throws Exception {
        // "aGVsbG8sIHdvcmxk" == "hello, world"
        FakeTransport t = new FakeTransport(new GitHubResponse(200,
                "{\"encoding\":\"base64\",\"content\":\"aGVsbG8sIHdvcmxk\"}"));
        assertEquals("hello, world", new GitHubClient(t).readFile("o", "r", "README.md", null));
        assertTrue(t.path.contains("/contents/README.md"));
    }

    @Test
    void createIssuePostsTheRightBodyAndReturnsTheNewNumber() throws Exception {
        FakeTransport t = new FakeTransport(new GitHubResponse(201,
                "{\"number\":42,\"html_url\":\"https://github.com/o/r/issues/42\"}"));
        String out = new GitHubClient(t).createIssue("o", "r", "Please fix", "details here");
        assertEquals("POST", t.method);
        assertEquals("/repos/o/r/issues", t.path);
        assertTrue(t.body.contains("\"title\":\"Please fix\""));
        assertTrue(t.body.contains("\"body\":\"details here\""));
        assertTrue(out.contains("#42"));
        assertTrue(out.contains("issues/42"));
    }

    @Test
    void openPullRequestSendsHeadAndBase() throws Exception {
        FakeTransport t = new FakeTransport(new GitHubResponse(201,
                "{\"number\":5,\"html_url\":\"https://github.com/o/r/pull/5\"}"));
        String out = new GitHubClient(t)
                .openPullRequest("o", "r", "My PR", "feature", "main", null);
        assertEquals("/repos/o/r/pulls", t.path);
        assertTrue(t.body.contains("\"head\":\"feature\""));
        assertTrue(t.body.contains("\"base\":\"main\""));
        assertTrue(out.contains("PR #5"));
    }

    @Test
    void nonSuccessSurfacesStatusAndGithubMessage() {
        FakeTransport t = new FakeTransport(new GitHubResponse(404, "{\"message\":\"Not Found\"}"));
        IOException e = assertThrows(IOException.class,
                () -> new GitHubClient(t).getIssue("o", "r", 1));
        assertTrue(e.getMessage().contains("HTTP 404"));
        assertTrue(e.getMessage().contains("Not Found"));
    }

    @Test
    void httpTransportIsDormantWithoutAToken() {
        HttpGitHubTransport dormant = new HttpGitHubTransport(null);
        assertFalse(dormant.available());
        IOException e = assertThrows(IOException.class, () -> dormant.send("GET", "/user/repos", null));
        // The error names the env var, never a token value.
        assertTrue(e.getMessage().contains(HttpGitHubTransport.TOKEN_ENV));
    }
}
