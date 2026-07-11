package com.jarvis.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.FileRecordStore;
import com.jarvis.memory.InMemoryRecordStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageMeterTest {

    private static final PriceTable PRICES = new PriceTable(
            java.util.Map.of("m", new PriceTable.Rate(3.0, 15.0)), new PriceTable.Rate(3.0, 15.0));

    @Test
    void recordComputesCostAndSummaryAggregates() {
        UsageMeter meter = new UsageMeter(new InMemoryRecordStore(), PRICES);
        UsageEvent e = meter.record("anthropic", "m", 1_000_000, 1_000_000);
        assertEquals(18.0, e.costUsd(), 1e-9);   // 3 + 15

        meter.record("anthropic", "m", 500_000, 0);
        UsageSummary s = meter.summary();
        assertEquals(2, s.calls());
        assertEquals(1_500_000, s.inputTokens());
        assertEquals(1_000_000, s.outputTokens());
        assertEquals(18.0 + 1.5, s.costUsd(), 1e-9);
        assertEquals(2_500_000, s.totalTokens());
    }

    @Test
    void recentReturnsNewestFirst() {
        UsageMeter meter = new UsageMeter(new InMemoryRecordStore(), PRICES);
        meter.record("anthropic", "a", 1, 1);
        meter.record("anthropic", "b", 1, 1);
        meter.record("anthropic", "c", 1, 1);
        assertEquals(List.of("c", "b"),
                meter.recent(2).stream().map(UsageEvent::model).toList());
    }

    @Test
    void eventsSurviveARestartWhenFileBacked(@TempDir Path dir) {
        new UsageMeter(new FileRecordStore(dir), PRICES).record("anthropic", "m", 1_000_000, 0);
        UsageSummary s = new UsageMeter(new FileRecordStore(dir), PRICES).summary();
        assertEquals(1, s.calls());
        assertEquals(3.0, s.costUsd(), 1e-9);
    }

    @Test
    void emptyMeterSummarizesToZero() {
        UsageSummary s = new UsageMeter(new InMemoryRecordStore(), PRICES).summary();
        assertEquals(0, s.calls());
        assertEquals(0.0, s.costUsd(), 1e-9);
        assertTrue(new UsageMeter(new InMemoryRecordStore(), PRICES).recent(5).isEmpty());
    }

    @Test
    void recentRejectsNegativeMax() {
        assertThrows(IllegalArgumentException.class,
                () -> new UsageMeter(new InMemoryRecordStore(), PRICES).recent(-1));
    }
}
