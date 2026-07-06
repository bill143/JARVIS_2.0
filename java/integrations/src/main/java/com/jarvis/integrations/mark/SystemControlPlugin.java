package com.jarvis.integrations.mark;

import com.jarvis.integrations.Plugin;
import com.jarvis.integrations.PluginDescriptor;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Windows system-control tools inspired by Mark-XLVIII's system control (ideas only, original
 * code): launch well-known apps, nudge the volume, lock the screen.
 *
 * <p>Deliberately conservative: {@code app_launch} only accepts a fixed whitelist of app names —
 * never an arbitrary command — and volume control synthesizes media keys rather than running
 * shell one-liners with user input. The command runner is a seam so tests never execute anything.
 */
public final class SystemControlPlugin implements Plugin {

    /** Execution seam: runs an OS command. */
    @FunctionalInterface
    public interface CommandRunner {
        void run(List<String> command) throws IOException;
    }

    private static final Map<String, String> WINDOWS_APPS = Map.ofEntries(
            Map.entry("calculator", "calc"),
            Map.entry("notepad", "notepad"),
            Map.entry("paint", "mspaint"),
            Map.entry("explorer", "explorer"),
            Map.entry("files", "explorer"),
            Map.entry("chrome", "chrome"),
            Map.entry("edge", "msedge"),
            Map.entry("browser", "msedge"),
            Map.entry("word", "winword"),
            Map.entry("excel", "excel"),
            Map.entry("task manager", "taskmgr"),
            Map.entry("settings", "ms-settings:"));

    private final boolean windows;
    private final CommandRunner runner;

    /** Production wiring: real OS detection, real process launching. */
    public SystemControlPlugin() {
        this(System.getProperty("os.name", ""), command -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.start();
        });
    }

    public SystemControlPlugin(String osName, CommandRunner runner) {
        this.windows = Objects.requireNonNull(osName, "osName")
                .toLowerCase(Locale.ROOT).contains("windows");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("system-control", "0.1.0",
                "Launch apps, adjust volume, lock the screen (Windows)");
    }

    @Override
    public List<Tool> tools() {
        return List.of(appLaunch(), volume(), lockScreen());
    }

    private ToolResult run(List<String> command, String successMessage) {
        if (!windows) {
            return ToolResult.error("this tool is only supported on Windows");
        }
        try {
            runner.run(command);
            return ToolResult.ok(successMessage);
        } catch (IOException e) {
            return ToolResult.error("command failed: " + e.getMessage());
        }
    }

    private Tool appLaunch() {
        return new SimpleTool("app_launch",
                "Launch an application. Args: app - one of: "
                        + String.join(", ", WINDOWS_APPS.keySet().stream().sorted().toList()),
                call -> {
                    String requested = String.valueOf(call.arguments().get("app"))
                            .toLowerCase(Locale.ROOT).strip();
                    String target = WINDOWS_APPS.get(requested);
                    if (target == null) {
                        return ToolResult.error("unknown app '" + requested + "'; allowed: "
                                + String.join(", ", WINDOWS_APPS.keySet().stream().sorted().toList()));
                    }
                    return run(List.of("cmd", "/c", "start", "", target),
                            "Launched " + requested + ".");
                });
    }

    private Tool volume() {
        return new SimpleTool("volume",
                "Adjust system volume. Args: action - up, down, or mute.",
                call -> {
                    String action = String.valueOf(call.arguments().get("action"))
                            .toLowerCase(Locale.ROOT).strip();
                    String key = switch (action) {
                        case "up" -> "175";
                        case "down" -> "174";
                        case "mute" -> "173";
                        default -> null;
                    };
                    if (key == null) {
                        return ToolResult.error("action must be up, down, or mute");
                    }
                    int presses = "mute".equals(action) ? 1 : 5;
                    String script = "$w=New-Object -ComObject WScript.Shell; 1.." + presses
                            + " | ForEach-Object { $w.SendKeys([char]" + key + ") }";
                    return run(List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden",
                            "-Command", script), "Volume " + action + ".");
                });
    }

    private Tool lockScreen() {
        return new SimpleTool("lock_screen", "Lock the Windows session immediately. No args.",
                call -> run(List.of("rundll32.exe", "user32.dll,LockWorkStation"),
                        "Screen locked."));
    }

    /** Small named-lambda tool holder. */
    private record SimpleTool(String toolName, String toolDescription,
            java.util.function.Function<ToolCall, ToolResult> body) implements Tool {
        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return toolDescription;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return body.apply(call);
        }
    }
}
