package com.jarvis.app;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.jarvis.ui.PlainTextRenderer;
import com.jarvis.ui.UiMessage;
import com.jarvis.ui.UiRenderer;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Entry point. Default mode starts the web dashboard on {@code http://localhost:8080} (override
 * with {@code JARVIS_PORT}) and tries to open the browser; {@code --console} runs the original
 * terminal chat loop instead.
 */
public final class Main {

    private static final String DEFAULT_MODEL = "claude-sonnet-5";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--connect-google")) {
            com.jarvis.integrations.google.GoogleAuth auth =
                    AppWiring.googleAuth(AppWiring.memoryStore());
            if (auth == null) {
                System.out.println("Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET first "
                        + "(see the setup steps), then re-run with --connect-google.");
                return;
            }
            GoogleConnect.run(auth);
            return;
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv().getOrDefault("JARVIS_MODEL", DEFAULT_MODEL);
        AppWiring.Runtime runtime = AppWiring.build(apiKey, model);
        JarvisApi api = runtime.api();
        boolean online = runtime.online();

        if (Arrays.asList(args).contains("--console")) {
            runConsole(api, online, model);
            return;
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("JARVIS_PORT", "8080"));
        if (online) {
            runtime.monitor().start(30);   // hardware telemetry every 30s
        }
        WebServer server = WebServer.start(api, online, model, port,
                runtime.monitor(), runtime.vision(), runtime.googleConnected());
        String url = "http://localhost:" + server.port();
        System.out.println();
        System.out.println("  J.A.R.V.I.S. is running.");
        System.out.println("  Open this in your browser:  " + url);
        System.out.println("  Mode: " + (online ? "ONLINE (" + model + ")" : "offline echo - set ANTHROPIC_API_KEY to enable AI"));
        System.out.println("  Google (Gmail/Calendar): "
                + (runtime.googleConnected() ? "CONNECTED" : "not connected (run --connect-google)"));
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
        tryOpenBrowser(url);
        Thread.currentThread().join();
    }

    private static void tryOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // Headless or no default browser — the printed URL is the fallback.
        }
    }

    private static void runConsole(JarvisApi api, boolean online, String model) throws IOException {
        UiRenderer renderer = new PlainTextRenderer(System.out);
        renderer.render(new UiMessage("jarvis", online
                ? "Online and ready (model: " + model + "). Type your question, or 'exit' to quit."
                : "Running in offline echo mode - set ANTHROPIC_API_KEY to enable AI. "
                        + "Type something, or 'exit' to quit."));
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("you> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.strip().equalsIgnoreCase("exit")) {
                renderer.render(new UiMessage("jarvis", "Goodbye."));
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            ChatResponse response = api.chat(new ChatRequest("console", line.strip()));
            renderer.render(new UiMessage("jarvis", response.completed()
                    ? response.response()
                    : "(no answer - my step budget ran out; please try rephrasing)"));
        }
    }
}
