package com.jarvis.app;

import com.jarvis.audit.AuditCategory;
import com.jarvis.audit.AuditEvent;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditOutcome;
import com.jarvis.audit.AuditTrigger;
import com.jarvis.research.ResearchPipeline;
import com.jarvis.research.ResearchReport;
import com.jarvis.tools.RiskTier;
import java.util.Objects;

/**
 * App facade for the internet-research flow. Feature-flagged OFF by default. Delegates to the
 * citation-preserving {@link ResearchPipeline} (injected, so tests use fake seams and production
 * wires {@code web_search} + {@code api.chat}), and audits every run as an outbound EXTERNAL_API
 * action.
 */
final class ResearchService {

    private final ResearchPipeline pipeline;
    private final AuditLog audit;   // nullable
    private final boolean enabled;

    ResearchService(ResearchPipeline pipeline, AuditLog audit, boolean enabled) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.audit = audit;
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    /** Runs research on {@code question}; audits the outbound call. */
    ResearchReport research(String question) throws Exception {
        ResearchReport report = pipeline.run(question);
        if (audit != null) {
            audit.record(new AuditEvent(AuditCategory.EXTERNAL_API, "research",
                    AuditTrigger.USER, RiskTier.READ_ONLY, AuditOutcome.SUCCESS,
                    "q: " + question + " (" + report.sources().size() + " sources)"));
        }
        return report;
    }
}
