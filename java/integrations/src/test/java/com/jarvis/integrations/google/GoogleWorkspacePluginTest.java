package com.jarvis.integrations.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.integrations.PluginManager;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolRegistry;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoogleWorkspacePluginTest {

    /** Fake Google client that returns canned JSON per URL and records POST bodies. */
    private static final class FakeClient implements GoogleClient {
        final List<String> posts = new ArrayList<>();
        final List<String> postUrls = new ArrayList<>();

        @Override
        public String get(String url) {
            if (url.contains("/messages?")) {
                return "{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}";
            }
            if (url.contains("/messages/m1")) {
                return msg("Ann <ann@x.com>", "Invoice due", "Please pay the invoice by Friday.");
            }
            if (url.contains("/messages/m2")) {
                return msg("Bob <bob@x.com>", "Lunch?", "Want to grab lunch tomorrow?");
            }
            if (url.contains("/events?")) {
                return "{\"items\":[{\"summary\":\"Site visit\",\"start\":{\"dateTime\":\"2026-07-10T09:00:00-05:00\"}},"
                        + "{\"summary\":\"Client call\",\"start\":{\"dateTime\":\"2026-07-10T14:00:00-05:00\"}}]}";
            }
            return "{}";
        }

        @Override
        public String postJson(String url, String jsonBody) {
            postUrls.add(url);
            posts.add(jsonBody);
            if (url.contains("/events")) {
                return "{\"summary\":\"Kickoff\"}";
            }
            return "{\"id\":\"sent1\"}";
        }

        private static String msg(String from, String subject, String snippet) {
            return "{\"snippet\":\"" + snippet + "\",\"payload\":{\"headers\":["
                    + "{\"name\":\"From\",\"value\":\"" + from + "\"},"
                    + "{\"name\":\"Subject\",\"value\":\"" + subject + "\"},"
                    + "{\"name\":\"Date\",\"value\":\"Mon, 6 Jul 2026\"}]}}";
        }
    }

    private ToolRegistry installed(GoogleClient client) {
        ToolRegistry registry = new ToolRegistry();
        new PluginManager(registry).install(new GoogleWorkspacePlugin(client));
        return registry;
    }

    @Test
    void contributesTheFourTools() {
        ToolRegistry registry = installed(new FakeClient());
        for (String n : new String[] {"email_list", "email_send", "calendar_list", "calendar_create"}) {
            assertTrue(registry.lookup(n).isPresent(), n + " missing");
        }
    }

    @Test
    void emailListSummarizesMessages() {
        ToolResult r = installed(new FakeClient()).execute(ToolCall.of("email_list"));
        assertTrue(r.success());
        assertTrue(r.output().contains("Invoice due"));
        assertTrue(r.output().contains("ann@x.com"));
        assertTrue(r.output().contains("Lunch?"));
    }

    @Test
    void emailSendBuildsAValidRawMessage() {
        FakeClient client = new FakeClient();
        ToolResult r = installed(client).execute(new ToolCall("email_send",
                Map.of("to", "sara@x.com", "subject", "Hi", "body", "Meeting at 3?")));
        assertTrue(r.success());
        assertEquals(1, client.posts.size());
        // Decode the base64url raw MIME and confirm the headers/body round-tripped.
        String raw = client.posts.get(0);
        String b64 = raw.replaceAll(".*\"raw\":\"", "").replaceAll("\".*", "");
        String mime = new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
        assertTrue(mime.contains("To: sara@x.com"));
        assertTrue(mime.contains("Subject: Hi"));
        assertTrue(mime.contains("Meeting at 3?"));
    }

    @Test
    void emailSendRejectsBadAddress() {
        ToolResult r = installed(new FakeClient()).execute(new ToolCall("email_send",
                Map.of("to", "not-an-email", "subject", "x", "body", "y")));
        assertFalse(r.success());
    }

    @Test
    void calendarListShowsUpcomingEvents() {
        ToolResult r = installed(new FakeClient()).execute(ToolCall.of("calendar_list"));
        assertTrue(r.success());
        assertTrue(r.output().contains("Site visit"));
        assertTrue(r.output().contains("Client call"));
    }

    @Test
    void calendarCreatePostsStartAndEnd() {
        FakeClient client = new FakeClient();
        ToolResult r = installed(client).execute(new ToolCall("calendar_create",
                Map.of("summary", "Kickoff", "start", "2026-07-10T14:00:00-05:00", "durationMinutes", 30)));
        assertTrue(r.success());
        assertEquals(1, client.posts.size());
        String body = client.posts.get(0);
        assertTrue(body.contains("Kickoff"));
        assertTrue(body.contains("2026-07-10T14:00"));
        assertTrue(body.contains("2026-07-10T14:30"));   // start + 30 min
    }

    @Test
    void calendarCreateRejectsBadStart() {
        ToolResult r = installed(new FakeClient()).execute(new ToolCall("calendar_create",
                Map.of("summary", "x", "start", "tomorrow afternoon")));
        assertFalse(r.success());
    }

    @Test
    void apiErrorsBecomeFailedResultsNotThrows() {
        GoogleClient boom = new GoogleClient() {
            public String get(String url) throws IOException {
                throw new IOException("401 Unauthorized");
            }

            public String postJson(String url, String body) throws IOException {
                throw new IOException("401 Unauthorized");
            }
        };
        ToolResult r = installed(boom).execute(ToolCall.of("email_list"));
        assertFalse(r.success());
        assertTrue(r.output() == null);
        assertTrue(r.error().contains("email_list failed"));
    }
}
