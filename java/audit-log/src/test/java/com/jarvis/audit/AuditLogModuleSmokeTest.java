package com.jarvis.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.tools.RiskTier;
import org.junit.jupiter.api.Test;

class AuditLogModuleSmokeTest {

    @Test
    void moduleWiresRecordAndQueryEndToEnd() {
        AuditLog log = new RecordStoreAuditLog(new InMemoryRecordStore());
        log.record(AuditEvent.toolSuccess("clock", RiskTier.READ_ONLY, "-"));
        assertEquals(1, log.query(AuditQuery.all()).size());
    }
}
