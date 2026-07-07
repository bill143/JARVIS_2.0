package com.jarvis.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Gmail + Google Calendar tools over the {@link GoogleClient}: read the inbox, send mail, list
 * upcoming events, and create events. Raw REST + Jackson (no Google SDK). Any API/parse failure is
 * returned as a failed {@link ToolResult}, never thrown, so the agent loop degrades gracefully.
 */
public final class GoogleWorkspacePlugin implements Plugin {

    private static final String GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String CAL = "https://www.googleapis.com/calendar/v3/calendars/primary";

    private final GoogleClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleWorkspacePlugin(GoogleClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("google-workspace", "0.1.0",
                "Gmail (read/send) and Google Calendar (list/create)");
    }

    @Override
    public List<Tool> tools() {
        return List.of(emailList(), emailSend(), calendarList(), calendarCreate());
    }

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

    private Tool emailList() {
        return tool("email_list",
                "List recent emails. Args: query (optional Gmail search, e.g. 'is:unread'; "
                        + "default 'in:inbox'), max (optional, default 5).",
                call -> {
                    String q = str(call, "query", "in:inbox");
                    int max = Math.max(1, Math.min(10, intArg(call, "max", 5)));
                    JsonNode ids = mapper.readTree(client.get(GMAIL + "/messages?maxResults=" + max
                            + "&q=" + enc(q))).path("messages");
                    if (!ids.isArray() || ids.isEmpty()) {
                        return ToolResult.ok("No emails matched " + q + ".");
                    }
                    StringBuilder out = new StringBuilder();
                    int n = 0;
                    for (JsonNode idNode : ids) {
                        if (n >= max) {
                            break;
                        }
                        JsonNode msg = mapper.readTree(client.get(GMAIL + "/messages/"
                                + idNode.path("id").asText()
                                + "?format=metadata&metadataHeaders=From&metadataHeaders=Subject"
                                + "&metadataHeaders=Date"));
                        String from = header(msg, "From");
                        String subject = header(msg, "Subject");
                        String date = header(msg, "Date");
                        String snippet = msg.path("snippet").asText("");
                        out.append(++n).append(". ").append(subject.isBlank() ? "(no subject)" : subject)
                                .append("\n   from ").append(from).append("  ·  ").append(date)
                                .append("\n   ").append(trim(snippet, 140)).append("\n");
                    }
                    return ToolResult.ok(out.toString().strip());
                });
    }

    private Tool emailSend() {
        return tool("email_send",
                "Send an email. Args: to (address), subject, body. Confirm with the user first.",
                call -> {
                    String to = str(call, "to", "");
                    String subject = str(call, "subject", "");
                    String body = str(call, "body", "");
                    if (to.isBlank() || !to.contains("@")) {
                        return ToolResult.error("email_send needs a valid 'to' address");
                    }
                    String raw = "To: " + to + "\r\nSubject: " + subject
                            + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body;
                    String encoded = Base64.getUrlEncoder()
                            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("raw", encoded);
                    client.postJson(GMAIL + "/messages/send", payload.toString());
                    return ToolResult.ok("Email sent to " + to + ".");
                });
    }

    private Tool calendarList() {
        return tool("calendar_list",
                "List upcoming calendar events. Args: max (optional, default 5).",
                call -> {
                    int max = Math.max(1, Math.min(15, intArg(call, "max", 5)));
                    String now = OffsetDateTime.now().toString();
                    JsonNode items = mapper.readTree(client.get(CAL + "/events?singleEvents=true"
                            + "&orderBy=startTime&maxResults=" + max
                            + "&timeMin=" + enc(now))).path("items");
                    if (!items.isArray() || items.isEmpty()) {
                        return ToolResult.ok("No upcoming events.");
                    }
                    StringBuilder out = new StringBuilder();
                    int n = 0;
                    for (JsonNode ev : items) {
                        String when = ev.path("start").path("dateTime").asText(
                                ev.path("start").path("date").asText(""));
                        out.append(++n).append(". ").append(ev.path("summary").asText("(untitled)"))
                                .append("  ·  ").append(when).append('\n');
                    }
                    return ToolResult.ok(out.toString().strip());
                });
    }

    private Tool calendarCreate() {
        return tool("calendar_create",
                "Create a calendar event. Args: summary (title), start (ISO-8601 datetime with "
                        + "offset, e.g. 2026-07-10T14:00:00-05:00), durationMinutes (optional, default 60).",
                call -> {
                    String summary = str(call, "summary", "");
                    String start = str(call, "start", "");
                    if (summary.isBlank() || start.isBlank()) {
                        return ToolResult.error("calendar_create needs 'summary' and 'start'");
                    }
                    ZonedDateTime startZ;
                    try {
                        startZ = OffsetDateTime.parse(start).toZonedDateTime();
                    } catch (Exception e) {
                        return ToolResult.error("start must be ISO-8601 with a timezone offset, e.g. "
                                + "2026-07-10T14:00:00-05:00");
                    }
                    int minutes = Math.max(1, intArg(call, "durationMinutes", 60));
                    ZonedDateTime endZ = startZ.plus(Duration.ofMinutes(minutes));
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("summary", summary);
                    payload.putObject("start").put("dateTime", startZ.toOffsetDateTime().toString());
                    payload.putObject("end").put("dateTime", endZ.toOffsetDateTime().toString());
                    JsonNode created = mapper.readTree(client.postJson(CAL + "/events", payload.toString()));
                    return ToolResult.ok("Event created: " + created.path("summary").asText(summary)
                            + " at " + startZ.toOffsetDateTime());
                });
    }

    private static String header(JsonNode message, String name) {
        for (JsonNode h : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(h.path("name").asText())) {
                return h.path("value").asText("");
            }
        }
        return "";
    }

    private static String str(ToolCall call, String key, String dflt) {
        Object v = call.arguments().get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static int intArg(ToolCall call, String key, int dflt) {
        Object v = call.arguments().get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dflt : Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String trim(String s, int max) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
