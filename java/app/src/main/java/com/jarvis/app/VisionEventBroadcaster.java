package com.jarvis.app;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-Sent Events fan-out for vision presence greetings, feeding the dashboard's chat log +
 * browser TTS ({@code /vision/events}). {@link #publish} is non-blocking and safe to call from any
 * thread (including the virtual thread {@link MotionEventService#handle} runs on); each subscribed
 * browser gets its own bounded queue and blocking writer loop so one slow/stalled client can never
 * back up another subscriber or the publisher.
 */
final class VisionEventBroadcaster {

    private static final int QUEUE_CAPACITY = 16;
    private static final long PING_INTERVAL_SECONDS = 20;

    private record Subscriber(HttpExchange exchange, BlockingQueue<String> queue) {
    }

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    // Global debounce, independent of camera identity. MotionEventService's own cooldown only
    // throttles face-recognition calls per camera -- a second camera, a spoofed cameraId, or several
    // physical cameras can each stay within that per-camera window while still spamming a new
    // greeting/TTS at the dashboard every few seconds. This gate protects the one thing a human
    // actually experiences (the chat log + spoken greeting), regardless of how many cameras feed it.
    private final AtomicReference<Instant> lastPublishedAt = new AtomicReference<>(Instant.EPOCH);

    /**
     * Publishes one JSON payload to every currently-connected subscriber, unless fewer than
     * {@code minIntervalSeconds} have elapsed since the last successful publish -- in which case this
     * call is silently suppressed (returns {@code false}, nothing sent). Never blocks; drops (never
     * queues indefinitely) on a full per-subscriber queue so a stalled client can't back up another
     * subscriber or the publisher.
     */
    boolean publish(String json, int minIntervalSeconds) {
        Instant now = Instant.now();
        Instant previous = lastPublishedAt.get();
        if (now.isBefore(previous.plusSeconds(minIntervalSeconds))) {
            return false;
        }
        if (!lastPublishedAt.compareAndSet(previous, now)) {
            return false; // another thread just published this instant; respect its window
        }
        for (Subscriber s : subscribers) {
            s.queue().offer(json);
        }
        return true;
    }

    /**
     * Blocks the calling thread for the lifetime of the SSE connection, writing each published event
     * as it arrives plus a periodic comment ping (keeps proxies/browsers from timing out an idle
     * stream and doubles as dead-socket detection). Returns once the client disconnects. Callers must
     * run this on a virtual thread -- it blocks for as long as the browser tab stays open.
     */
    void subscribe(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0); // 0 -> chunked, unbounded response length
        Subscriber self = new Subscriber(exchange, new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        subscribers.add(self);
        try {
            while (true) {
                String event = self.queue().poll(PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                String frame = event == null ? ": ping\n\n" : "data: " + event + "\n\n";
                exchange.getResponseBody().write(frame.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
        } catch (IOException e) {
            // Client disconnected (tab closed / navigated away) -- normal end of a long-lived stream.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            subscribers.remove(self);
            exchange.close();
        }
    }
}
