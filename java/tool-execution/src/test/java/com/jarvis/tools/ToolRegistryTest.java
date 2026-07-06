package com.jarvis.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    /** Minimal test tool whose behavior is supplied per test. */
    private static Tool tool(String name, java.util.function.Function<ToolCall, ToolResult> fn) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return fn.apply(call);
            }
        };
    }

    @Test
    void registerAndLookup() {
        ToolRegistry registry = new ToolRegistry();
        Tool echo = tool("echo", c -> ToolResult.ok(String.valueOf(c.arguments().get("text"))));
        registry.register(echo);

        assertTrue(registry.lookup("echo").isPresent());
        assertEquals("echo", registry.lookup("echo").orElseThrow().name());
        assertTrue(registry.lookup("absent").isEmpty());
    }

    @Test
    void listReturnsSnapshotOfRegisteredTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("a", c -> ToolResult.ok("a")));
        registry.register(tool("b", c -> ToolResult.ok("b")));

        assertEquals(2, registry.list().size());
    }

    @Test
    void duplicateRegistrationFailsFast() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("echo", c -> ToolResult.ok("first")));

        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> registry.register(tool("echo", c -> ToolResult.ok("second"))));
        assertTrue(e.getMessage().contains("echo"));
        // Original registration is untouched.
        assertEquals(ToolResult.ok("first"), registry.execute(ToolCall.of("echo")));
    }

    @Test
    void executeSuccessCarriesOutput() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("echo", c -> ToolResult.ok(String.valueOf(c.arguments().get("text")))));

        ToolResult result = registry.execute(new ToolCall("echo", Map.of("text", "hello")));
        assertTrue(result.success());
        assertEquals("hello", result.output());
    }

    @Test
    void executeFailureCarriesError() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("fails", c -> ToolResult.error("bad input")));

        ToolResult result = registry.execute(ToolCall.of("fails"));
        assertFalse(result.success());
        assertEquals("bad input", result.error());
    }

    @Test
    void unknownToolReturnsFailureNotThrow() {
        ToolRegistry registry = new ToolRegistry();

        ToolResult result = registry.execute(ToolCall.of("nope"));
        assertFalse(result.success());
        assertTrue(result.error().contains("unknown tool: nope"));
    }

    @Test
    void throwingToolIsConvertedToFailureResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("boom", c -> {
            throw new IllegalStateException("kaboom");
        }));

        ToolResult result = registry.execute(ToolCall.of("boom"));
        assertFalse(result.success());
        assertTrue(result.error().contains("boom"));
        assertTrue(result.error().contains("kaboom"));
    }

    @Test
    void toolResultRejectsInconsistentStates() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(true, "out", "err"));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(false, "out", "err"));
        assertThrows(NullPointerException.class, () -> new ToolResult(true, null, null));
        assertThrows(NullPointerException.class, () -> new ToolResult(false, null, null));
    }

    @Test
    void concurrentRegistrationOfDistinctToolsKeepsAll() throws InterruptedException {
        ToolRegistry registry = new ToolRegistry();
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.register(
                                tool("tool-" + threadId + "-" + i, c -> ToolResult.ok("ok")));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool did not terminate");

        assertEquals(threads * perThread, registry.list().size());
    }

    @Test
    void concurrentDuplicateRegistrationHasExactlyOneWinner() throws InterruptedException {
        ToolRegistry registry = new ToolRegistry();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final String marker = "writer-" + t;
            pool.execute(() -> {
                try {
                    start.await();
                    registry.register(tool("contended", c -> ToolResult.ok(marker)));
                    wins.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IllegalArgumentException expected) {
                    // losers fail fast by contract
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool did not terminate");

        assertEquals(1, wins.get());
        assertEquals(1, registry.list().size());
        assertTrue(registry.execute(ToolCall.of("contended")).success());
    }
}
