package com.jarvis.app;

import com.jarvis.api.ChatRequest;
import com.jarvis.api.ChatResponse;
import com.jarvis.api.JarvisApi;
import com.jarvis.ui.PlainTextRenderer;
import com.jarvis.ui.UiMessage;
import com.jarvis.ui.UiRenderer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Console launcher: the platform's first runnable entry point. Reads prompts from stdin, answers
 * through {@link JarvisApi}, renders with the ui module. Type {@code exit} to quit.
 */
public final class Main {

    private static final String DEFAULT_MODEL = "claude-sonnet-5";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv().getOrDefault("JARVIS_MODEL", DEFAULT_MODEL);
        JarvisApi api = AppWiring.buildApi(apiKey, model);
        UiRenderer renderer = new PlainTextRenderer(System.out);

        renderer.render(new UiMessage("jarvis", AppWiring.isOnline(apiKey)
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
