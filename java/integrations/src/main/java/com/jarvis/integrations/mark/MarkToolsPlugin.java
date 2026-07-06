package com.jarvis.integrations.mark;

import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.memory.MemoryStore;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.awt.Desktop;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Assistant utility tools inspired by the capability catalog of {@code FatihMakes/Mark-XLVIII}
 * (clock, system awareness, reminders, opening things). Ideas only — that project is Python under
 * a non-commercial license; every line here is an original Java implementation on this platform's
 * own {@link Tool} contract.
 */
public final class MarkToolsPlugin implements Plugin {

    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, h:mm a zzz");
    private static final String REMINDER_SCOPE = "reminders";

    private final MemoryStore<String> memory;

    /** @param memory store used for reminder persistence (scope {@code "reminders"}) */
    public MarkToolsPlugin(MemoryStore<String> memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    private static final String PREFS_SCOPE = "preferences";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("mark-tools", "0.3.0",
                "Clock, system info, reminders, news, weather, web search, files, hardware, memory");
    }

    @Override
    public List<Tool> tools() {
        return List.of(clock(), systemInfo(), reminderSet(), reminderList(), openUrl(),
                rememberPreference(), new NewsTool(), new WeatherTool(), new WebSearchTool(),
                new FileTool(), new HardwareTool());
    }

    private Tool rememberPreference() {
        return tool("remember",
                "Save something to remember about the user long-term (a preference, project, or"
                        + " personal fact) so it is recalled in future conversations. Args: fact.",
                call -> {
                    Object fact = call.arguments().get("fact");
                    if (fact == null || String.valueOf(fact).isBlank()) {
                        return ToolResult.error("remember needs a 'fact' argument");
                    }
                    String key = "pref-" + memory.query(PREFS_SCOPE).size();
                    memory.put(PREFS_SCOPE, key, String.valueOf(fact).strip());
                    return ToolResult.ok("Noted, sir. I'll remember that.");
                });
    }

    private static Tool tool(String name, String description,
            java.util.function.Function<ToolCall, ToolResult> body) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return body.apply(call);
            }
        };
    }

    private Tool clock() {
        return tool("clock",
                "Current date and time. Optional arg: zone (e.g. America/Chicago).",
                call -> {
                    Object zone = call.arguments().get("zone");
                    try {
                        ZoneId zoneId = zone == null
                                ? ZoneId.systemDefault()
                                : ZoneId.of(String.valueOf(zone));
                        return ToolResult.ok(ZonedDateTime.now(zoneId).format(CLOCK_FORMAT));
                    } catch (Exception e) {
                        return ToolResult.error("unknown time zone: " + zone);
                    }
                });
    }

    private Tool systemInfo() {
        return tool("system_info",
                "Operating system, CPU, and memory information for this machine.",
                call -> {
                    Runtime runtime = Runtime.getRuntime();
                    String info = "OS: " + System.getProperty("os.name")
                            + " " + System.getProperty("os.version")
                            + " (" + System.getProperty("os.arch") + ")\n"
                            + "CPU cores: " + runtime.availableProcessors() + "\n"
                            + "Java: " + System.getProperty("java.version") + "\n"
                            + "JVM memory: " + (runtime.totalMemory() / (1024 * 1024)) + " MB used pool, "
                            + (runtime.maxMemory() / (1024 * 1024)) + " MB max\n"
                            + "System load: " + ManagementFactory.getOperatingSystemMXBean()
                                    .getSystemLoadAverage();
                    return ToolResult.ok(info);
                });
    }

    private Tool reminderSet() {
        return tool("reminder_set",
                "Save a reminder. Args: text (what to remember), when (optional, freeform).",
                call -> {
                    Object text = call.arguments().get("text");
                    if (text == null || String.valueOf(text).isBlank()) {
                        return ToolResult.error("reminder needs a 'text' argument");
                    }
                    Object when = call.arguments().get("when");
                    String entry = when == null
                            ? String.valueOf(text)
                            : String.valueOf(text) + " (when: " + when + ")";
                    String key = "reminder-" + memory.query(REMINDER_SCOPE).size();
                    memory.put(REMINDER_SCOPE, key, entry);
                    return ToolResult.ok("Saved reminder: " + entry);
                });
    }

    private Tool reminderList() {
        return tool("reminder_list", "List all saved reminders. No args.",
                call -> {
                    var reminders = memory.query(REMINDER_SCOPE);
                    if (reminders.isEmpty()) {
                        return ToolResult.ok("No reminders saved.");
                    }
                    return ToolResult.ok(reminders.stream()
                            .map(entry -> "- " + entry.value())
                            .collect(Collectors.joining("\n")));
                });
    }

    private Tool openUrl() {
        return tool("open_url",
                "Open a web page in the user's default browser. Args: url (must be http/https).",
                call -> {
                    String url = String.valueOf(call.arguments().get("url"));
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return ToolResult.error("only http/https URLs can be opened, got: " + url);
                    }
                    try {
                        if (Desktop.isDesktopSupported()
                                && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI.create(url));
                            return ToolResult.ok("Opened " + url);
                        }
                        return ToolResult.error("no browser available on this system");
                    } catch (Exception e) {
                        return ToolResult.error("could not open " + url + ": " + e.getMessage());
                    }
                });
    }
}
