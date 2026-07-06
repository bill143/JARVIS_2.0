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
        return new PluginDescriptor("system-control", "0.2.0",
                "Launch apps, volume, brightness, WiFi, hotkeys, power, lock (Windows)");
    }

    @Override
    public List<Tool> tools() {
        return List.of(appLaunch(), volume(), brightness(), wifi(), hotkey(), power(), lockScreen());
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

    private Tool brightness() {
        return new SimpleTool("brightness",
                "Set screen brightness. Args: level - a percentage 0 to 100.",
                call -> {
                    Integer level = intArg(call.arguments().get("level"));
                    if (level == null || level < 0 || level > 100) {
                        return ToolResult.error("level must be a number from 0 to 100");
                    }
                    String script = "(Get-WmiObject -Namespace root/WMI"
                            + " -Class WmiMonitorBrightnessMethods).WmiSetBrightness(1," + level + ")";
                    return run(List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden",
                            "-Command", script), "Brightness set to " + level + "%.");
                });
    }

    private Tool wifi() {
        return new SimpleTool("wifi",
                "Control Wi-Fi. Args: action - status, disconnect, or connect (with 'network' name).",
                call -> {
                    String action = String.valueOf(call.arguments().get("action"))
                            .toLowerCase(Locale.ROOT).strip();
                    return switch (action) {
                        case "status" -> run(List.of("netsh", "wlan", "show", "interfaces"),
                                "Checked Wi-Fi status.");
                        case "disconnect" -> run(List.of("netsh", "wlan", "disconnect"),
                                "Wi-Fi disconnected.");
                        case "connect" -> {
                            Object network = call.arguments().get("network");
                            if (network == null || String.valueOf(network).isBlank()) {
                                yield ToolResult.error("connect needs a 'network' (saved profile) name");
                            }
                            yield run(List.of("netsh", "wlan", "connect",
                                    "name=" + network), "Connecting to " + network + ".");
                        }
                        default -> ToolResult.error("action must be status, disconnect, or connect");
                    };
                });
    }

    private Tool hotkey() {
        return new SimpleTool("hotkey",
                "Send a desktop shortcut. Args: action - copy, paste, switch_window,"
                        + " show_desktop, screenshot, minimize_all, or task_view.",
                call -> {
                    String action = String.valueOf(call.arguments().get("action"))
                            .toLowerCase(Locale.ROOT).strip();
                    String keys = switch (action) {
                        case "copy" -> "^c";
                        case "paste" -> "^v";
                        case "switch_window" -> "%{TAB}";
                        case "show_desktop", "minimize_all" -> "^{ESC}";
                        case "screenshot" -> "+#s";
                        case "task_view" -> "#{TAB}";
                        default -> null;
                    };
                    if (keys == null) {
                        return ToolResult.error("unknown shortcut '" + action + "'");
                    }
                    String script = "$w=New-Object -ComObject WScript.Shell;"
                            + " $w.SendKeys('" + keys + "')";
                    return run(List.of("powershell", "-NoProfile", "-WindowStyle", "Hidden",
                            "-Command", script), "Sent shortcut: " + action + ".");
                });
    }

    private Tool power() {
        return new SimpleTool("power",
                "Power actions. Args: action - sleep, lock, sign_out, restart, or shutdown."
                        + " restart and shutdown are delayed 15s so they can be cancelled.",
                call -> {
                    String action = String.valueOf(call.arguments().get("action"))
                            .toLowerCase(Locale.ROOT).strip();
                    return switch (action) {
                        case "sleep" -> run(List.of("rundll32.exe",
                                "powrprof.dll,SetSuspendState", "0,1,0"), "Going to sleep.");
                        case "lock" -> run(List.of("rundll32.exe", "user32.dll,LockWorkStation"),
                                "Screen locked.");
                        case "sign_out" -> run(List.of("shutdown", "/l"), "Signing out.");
                        case "restart" -> run(List.of("shutdown", "/r", "/t", "15", "/c",
                                "JARVIS restart - run 'shutdown /a' to cancel"),
                                "Restarting in 15 seconds, sir. Say cancel to abort.");
                        case "shutdown" -> run(List.of("shutdown", "/s", "/t", "15", "/c",
                                "JARVIS shutdown - run 'shutdown /a' to cancel"),
                                "Shutting down in 15 seconds, sir. Say cancel to abort.");
                        case "cancel" -> run(List.of("shutdown", "/a"), "Power action cancelled.");
                        default -> ToolResult.error(
                                "action must be sleep, lock, sign_out, restart, shutdown, or cancel");
                    };
                });
    }

    private Tool lockScreen() {
        return new SimpleTool("lock_screen", "Lock the Windows session immediately. No args.",
                call -> run(List.of("rundll32.exe", "user32.dll,LockWorkStation"),
                        "Screen locked."));
    }

    private static Integer intArg(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return null;
        }
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
