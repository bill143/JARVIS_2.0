package com.jarvis.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.FileRecordStore;
import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.tools.RiskTier;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordStoreAuditLogTest {

    private static AuditEvent evt(String action, AuditCategory cat, RiskTier tier, AuditOutcome out) {
        return new AuditEvent(cat, action, AuditTrigger.USER, tier, out, "detail for " + action);
    }

    @Test
    void recordAssignsMonotonicSequenceAndTimestamp() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        AuditEntry a = log.record(AuditEvent.toolSuccess("clock", RiskTier.READ_ONLY, "-"));
        AuditEntry b = log.record(AuditEvent.toolSuccess("weather", RiskTier.READ_ONLY, "-"));
        assertEquals(0, a.seq());
        assertEquals(1, b.seq());
        assertTrue(!b.at().isBefore(a.at()));
        assertEquals("clock", a.event().action());
    }

    @Test
    void queryFiltersByActionCategoryAndRiskTier() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        log.record(evt("email_trash", AuditCategory.DESTRUCTIVE_ACTION, RiskTier.DESTRUCTIVE, AuditOutcome.SUCCESS));
        log.record(evt("email_list", AuditCategory.TOOL_INVOCATION, RiskTier.READ_ONLY, AuditOutcome.SUCCESS));
        log.record(evt("clock", AuditCategory.TOOL_INVOCATION, RiskTier.READ_ONLY, AuditOutcome.SUCCESS));

        assertEquals(2, log.query(AuditQuery.all().action("email")).size());
        assertEquals(1, log.query(AuditQuery.all().riskTier(RiskTier.DESTRUCTIVE)).size());
        assertEquals(1, log.query(AuditQuery.all().category(AuditCategory.DESTRUCTIVE_ACTION)).size());
        assertEquals(3, log.query(AuditQuery.all()).size());
    }

    @Test
    void queryReturnsChronologicalAndRecentReturnsNewestFirst() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        log.record(AuditEvent.toolSuccess("one", RiskTier.READ_ONLY, "-"));
        log.record(AuditEvent.toolSuccess("two", RiskTier.READ_ONLY, "-"));
        log.record(AuditEvent.toolSuccess("three", RiskTier.READ_ONLY, "-"));

        assertEquals(List.of("one", "two", "three"),
                log.query(AuditQuery.all()).stream().map(e -> e.event().action()).toList());
        assertEquals(List.of("three", "two"),
                log.recent(2).stream().map(e -> e.event().action()).toList());
    }

    @Test
    void entriesSurviveARestartWhenBackedByAFile(@TempDir Path dir) {
        FileRecordStore store = new FileRecordStore(dir);
        new RecordStoreAuditLog(store).record(
                AuditEvent.toolFailure("power", RiskTier.DESTRUCTIVE, "user cancelled"));

        AuditLog reopened = new RecordStoreAuditLog(new FileRecordStore(dir));
        List<AuditEntry> all = reopened.query(AuditQuery.all());
        assertEquals(1, all.size());
        assertEquals("power", all.get(0).event().action());
        assertEquals(AuditOutcome.FAILURE, all.get(0).event().outcome());
        assertEquals(RiskTier.DESTRUCTIVE, all.get(0).event().riskTier());
        assertEquals("user cancelled", all.get(0).event().detail());
    }

    @Test
    void recentRejectsNegativeMax() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        assertThrows(IllegalArgumentException.class, () -> log.recent(-1));
    }
}
