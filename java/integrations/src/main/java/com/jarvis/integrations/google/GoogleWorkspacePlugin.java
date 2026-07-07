package com.jarvis.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Gmail + Google Calendar agent tools, delegating to {@link GoogleWorkspaceService} so the tools
 * and the dashboard MAIL/CALENDAR panels share one data path. Any failure is a failed
 * {@link ToolResult}, never a throw.
 */
public final class GoogleWorkspacePlugin implements Plugin {

    private final GoogleWorkspaceService service;

    public GoogleWorkspacePlugin(GoogleClient client) {
        this(new GoogleWorkspaceService(client));
    }

    public GoogleWorkspacePlugin(GoogleWorkspaceService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("google-workspace", "0.2.0",
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
                    JsonNode emails = service.listEmails(str(call, "query", "in:inbox"),
                            intArg(call, "max", 5));
                    if (emails.isEmpty()) {
                        return ToolResult.ok("No emails matched.");
                    }
                    StringBuilder out = new StringBuilder();
                    int n = 0;
                    for (JsonNode e : emails) {
                        String subject = e.path("subject").asText("");
                        out.append(++n).append(". ").append(subject.isBlank() ? "(no subject)" : subject)
                                .append("\n   from ").append(e.path("from").asText())
                                .append("  ·  ").append(e.path("date").asText())
                                .append("\n   ").append(trim(e.path("snippet").asText(""), 140)).append('\n');
                    }
                    return ToolResult.ok(out.toString().strip());
                });
    }

    private Tool emailSend() {
        return tool("email_send",
                "Send an email. Args: to (address), subject, body. Confirm with the user first.",
                call -> {
                    String to = str(call, "to", "");
                    if (to.isBlank() || !to.contains("@")) {
                        return ToolResult.error("email_send needs a valid 'to' address");
                    }
                    service.sendEmail(to, str(call, "subject", ""), str(call, "body", ""));
                    return ToolResult.ok("Email sent to " + to + ".");
                });
    }

    private Tool calendarList() {
        return tool("calendar_list",
                "List upcoming calendar events. Args: max (optional, default 5).",
                call -> {
                    JsonNode events = service.listEvents(intArg(call, "max", 5));
                    if (events.isEmpty()) {
                        return ToolResult.ok("No upcoming events.");
                    }
                    StringBuilder out = new StringBuilder();
                    int n = 0;
                    for (JsonNode ev : events) {
                        out.append(++n).append(". ").append(ev.path("summary").asText("(untitled)"))
                                .append("  ·  ").append(ev.path("start").asText()).append('\n');
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
                    try {
                        OffsetDateTime.parse(start);
                    } catch (Exception e) {
                        return ToolResult.error("start must be ISO-8601 with a timezone offset, e.g. "
                                + "2026-07-10T14:00:00-05:00");
                    }
                    String title = service.createEvent(summary, start, intArg(call, "durationMinutes", 60));
                    return ToolResult.ok("Event created: " + title + " at " + start);
                });
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
}
