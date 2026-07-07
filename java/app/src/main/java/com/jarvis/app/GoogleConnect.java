package com.jarvis.app;

import com.jarvis.integrations.google.GoogleAuth;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One-time interactive Google consent flow for a desktop app. Spins up a loopback listener,
 * opens the consent page in the browser, catches the redirect with the authorization code,
 * exchanges it for tokens (persisting the refresh token), then shuts down.
 */
final class GoogleConnect {

    private GoogleConnect() {
    }

    static void run(GoogleAuth auth) throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String redirect = "http://127.0.0.1:" + port;
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String code = param(query, "code");
            String message;
            boolean ok = false;
            if (code == null || code.isBlank()) {
                message = "Authorization was cancelled or denied. Nothing changed.";
            } else {
                // Do the token exchange NOW so the page reflects the real outcome.
                try {
                    auth.exchangeCode(code, redirect);
                    ok = true;
                    message = "JARVIS is now connected to Google. You can close this tab.";
                } catch (Exception e) {
                    message = "Connection failed during token exchange: " + e.getMessage()
                            + "  —  double-check your Client ID/Secret and try again.";
                }
            }
            byte[] body = ("<html><body style='font-family:sans-serif;background:#05101d;color:#d7e8f7;"
                    + "text-align:center;padding-top:80px'><h2>J.A.R.V.I.S.</h2><p>" + escape(message)
                    + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            result.offer((ok ? "OK:" : "FAIL:") + message);
        });
        server.start();

        String url = auth.authUrl(redirect);
        System.out.println();
        System.out.println("  Connecting JARVIS to your Google account…");
        System.out.println("  A browser window should open. If it doesn't, open this URL:");
        System.out.println();
        System.out.println("  " + url);
        System.out.println();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // The printed URL is the fallback.
        }

        String outcome = result.poll(5, TimeUnit.MINUTES);
        server.stop(0);
        if (outcome == null) {
            System.out.println("  No authorization received (timed out). Nothing changed.");
        } else if (outcome.startsWith("OK:")) {
            System.out.println("  ✓ Connected. Verifying…");
            if (auth.isConnected()) {
                System.out.println("  ✓ Refresh token saved. JARVIS can now read/send Gmail and manage Calendar.");
                System.out.println("  Now start JARVIS normally:  java -jar app\\target\\jarvis.jar");
            } else {
                System.out.println("  ✗ Token was not saved — please re-run --connect-google.");
            }
        } else {
            System.out.println("  ✗ " + outcome.substring("FAIL:".length()));
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
