package com.jarvis.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
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
