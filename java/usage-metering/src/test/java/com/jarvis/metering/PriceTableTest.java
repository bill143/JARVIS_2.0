package com.jarvis.metering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceTableTest {

    @Test
    void computesCostFromPerMillionRates() {
        PriceTable table = new PriceTable(
                Map.of("m", new PriceTable.Rate(3.0, 15.0)), new PriceTable.Rate(1.0, 1.0));
        // 1,000,000 input @ $3/M + 2,000,000 output @ $15/M = 3 + 30 = 33
        assertEquals(33.0, table.cost("m", 1_000_000, 2_000_000), 1e-9);
    }

    @Test
    void unknownModelUsesTheFallbackRate() {
        PriceTable table = new PriceTable(Map.of(), new PriceTable.Rate(2.0, 4.0));
        assertEquals(6.0, table.cost("mystery", 1_000_000, 1_000_000), 1e-9);
    }

    @Test
    void defaultsPriceKnownModels() {
        assertEquals(3.0 + 15.0, PriceTable.defaults().cost("claude-sonnet-5", 1_000_000, 1_000_000), 1e-9);
    }
}
