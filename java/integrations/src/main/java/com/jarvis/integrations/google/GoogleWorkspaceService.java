package com.jarvis.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Objects;

/**
 * Structured Gmail + Google Calendar access over a {@link GoogleClient}. Returns Jackson nodes so
 * the same data feeds both the agent tools ({@link GoogleWorkspacePlugin}) and the dashboard's
 * MAIL/CALENDAR panels (via the web server). Raw REST + Jackson — no Google SDK.
 */
public final class GoogleWorkspaceService {

    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String CAL = "https://www.googleapis.com/calendar/v3/calendars/primary";

    private final GoogleClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleWorkspaceService(GoogleClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Returns up to {@code max} messages matching {@code query} as [{id,from,subject,date,snippet}]. */
    public ArrayNode listEmails(String query, int max) throws IOException, InterruptedException {
        int limit = Math.max(1, Math.min(25, max));
        ArrayNode out = mapper.createArrayNode();
        JsonNode ids = mapper.readTree(client.get(GMAIL + "/messages?maxResults=" + limit
                + "&q=" + enc(query == null || query.isBlank() ? "in:inbox" : query))).path("messages");
        if (!ids.isArray()) {
            return out;
        }
        int n = 0;
        for (JsonNode idNode : ids) {
            if (n++ >= limit) {
                break;
            }
            JsonNode msg = mapper.readTree(client.get(GMAIL + "/messages/" + idNode.path("id").asText()
                    + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"));
            ObjectNode e = out.addObject();
            e.put("id", idNode.path("id").asText());
            e.put("from", header(msg, "From"));
            e.put("subject", header(msg, "Subject"));
            e.put("date", header(msg, "Date"));
            e.put("snippet", msg.path("snippet").asText(""));
        }
        return out;
    }

    /** Returns up to {@code max} upcoming events as [{summary,start,end,location}]. */
    public ArrayNode listEvents(int max) throws IOException, InterruptedException {
        int limit = Math.max(1, Math.min(50, max));
        ArrayNode out = mapper.createArrayNode();
        JsonNode items = mapper.readTree(client.get(CAL + "/events?singleEvents=true&orderBy=startTime"
                + "&maxResults=" + limit + "&timeMin=" + enc(OffsetDateTime.now().toString()))).path("items");
        if (!items.isArray()) {
            return out;
        }
        for (JsonNode ev : items) {
            ObjectNode e = out.addObject();
            e.put("summary", ev.path("summary").asText("(untitled)"));
            e.put("start", ev.path("start").path("dateTime").asText(ev.path("start").path("date").asText("")));
            e.put("end", ev.path("end").path("dateTime").asText(ev.path("end").path("date").asText("")));
            e.put("location", ev.path("location").asText(""));
        }
        return out;
    }

    /** Sends a plain-text email. */
    public void sendEmail(String to, String subject, String body)
            throws IOException, InterruptedException {
        String raw = "To: " + to + "\r\nSubject: " + subject
                + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("raw", Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        client.postJson(GMAIL + "/messages/send", payload.toString());
    }

    /** Moves a message to Trash. */
    public void trashEmail(String id) throws IOException, InterruptedException {
        client.postJson(GMAIL + "/messages/" + id + "/trash", "{}");
    }

    /** Removes a message from the inbox (keeps it, filed away). */
    public void archiveEmail(String id) throws IOException, InterruptedException {
        client.postJson(GMAIL + "/messages/" + id + "/modify", "{\"removeLabelIds\":[\"INBOX\"]}");
    }

    /**
     * Attempts to unsubscribe from a message using its List-Unsubscribe header. An https link is
     * requested with a plain (un-authenticated) client so the Google token is never sent to a third
     * party; a mailto-only option is reported back for the user to confirm sending.
     */
    public String unsubscribe(String id) throws IOException, InterruptedException {
        JsonNode msg = mapper.readTree(client.get(GMAIL + "/messages/" + id
                + "?format=metadata&metadataHeaders=List-Unsubscribe"));
        String hdr = header(msg, "List-Unsubscribe");
        if (hdr.isBlank()) {
            return "That email has no unsubscribe option I can use automatically.";
        }
        String http = angleLink(hdr, "http");
        String mailto = angleLink(hdr, "mailto:");
        if (http != null) {
            HttpClient plain = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            try {
                HttpResponse<Void> r = plain.send(HttpRequest.newBuilder(URI.create(http))
                        .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding());
                return r.statusCode() / 100 == 2
                        ? "Unsubscribe request sent successfully."
                        : "Unsubscribe link returned " + r.statusCode() + ": " + http;
            } catch (Exception e) {
                return "Couldn't reach the unsubscribe link automatically: " + http;
            }
        }
        if (mailto != null) {
            return "To unsubscribe, an email must be sent to " + mailto.substring("mailto:".length())
                    + " — say the word and I'll send it, sir.";
        }
        return "Found an unsubscribe header but no usable link: " + hdr;
    }

    private static String angleLink(String header, String scheme) {
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.startsWith("<") && p.endsWith(">")) {
                p = p.substring(1, p.length() - 1);
            }
            if (p.toLowerCase(java.util.Locale.ROOT).startsWith(scheme)) {
                return p;
            }
        }
        return null;
    }

    /** Creates a calendar event; returns the created event's title. */
    public String createEvent(String summary, String startIso, int durationMinutes)
            throws IOException, InterruptedException {
        ZonedDateTime startZ = OffsetDateTime.parse(startIso).toZonedDateTime();
        ZonedDateTime endZ = startZ.plus(Duration.ofMinutes(Math.max(1, durationMinutes)));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("summary", summary);
        payload.putObject("start").put("dateTime", startZ.toOffsetDateTime().toString());
        payload.putObject("end").put("dateTime", endZ.toOffsetDateTime().toString());
        JsonNode created = mapper.readTree(client.postJson(CAL + "/events", payload.toString()));
        return created.path("summary").asText(summary);
    }

    private static String header(JsonNode message, String name) {
        for (JsonNode h : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(h.path("name").asText())) {
                return h.path("value").asText("");
            }
        }
        return "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
