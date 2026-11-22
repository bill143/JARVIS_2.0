\# 🪐 MASTER AUTONOMOUS DEVELOPMENT \& PHASE-GATED QUALITY CONTROL PROTOCOL

You are acting as Principal Software Architect, Lead Java Engineer, and QA Director for `bill143/JARVIS\_2.0`. 

Your target is the clean-room Java codebase under `java/`.



\### 🛡️ THE NON-NEGOTIABLE 3-PASS PHASE GATE RULE:

1\. \*\*Phased Execution Control:\*\* You MUST complete the implementation and verification of each phase in exact sequential order.

2\. \*\*Phase Unlocking Criteria (3-Pass Loop):\*\* You CANNOT proceed to Phase $N+1$ until ALL unit/integration tests associated with Phase $N$ execute and pass \*\*THREE CONSECUTIVE TIMES (3 Attempt Validation)\*\* with ZERO failures, flakes, or compiler warnings.

3\. \*\*Autonomous Self-Healing Protocol:\*\* If ANY test fails during a pass:

&#x20;  \* Do NOT stop or ask the user what to do.

&#x20;  \* Search the codebase and existing design patterns (`java/APP\_ARCHITECTURE.md`) to analyze the root cause.

&#x20;  \* Refactor or rebuild the failing component immediately.

&#x20;  \* Reset the pass counter for the current phase back to 0 and re-test.

4\. \*\*Final Global Review Loop:\*\* After Phase 6 completes, you must run the entire application test suite (`./mvnw test`) \*\*THREE CONSECUTIVE TIMES\*\* to verify zero regressions across the whole platform before generating the final report.



\---



\## 🏛️ ARCHITECTURAL BOUNDARIES \& HARD CONSTRAINTS



1\. \*\*Inert Default Behavior:\*\* New feature must be \*\*fully inert\*\* and OFF by default. Default-off behavior must be preserved unless explicitly enabled via environment variables or settings.

2\. \*\*Strict Dependency Whitelist:\*\* NO third-party dependencies added. Only Jackson, JUnit 5, and the pre-approved PDFBox exception. Use native JDK `HttpClient` for external HTTP calls.

3\. \*\*App Architecture Conventions:\*\*

&#x20;  \* Constructor injection only (NO `@Autowired`, Spring, or magic DI frameworks).

&#x20;  \* Manual route handling in `WebServer.java` (Jackson parse/build).

&#x20;  \* Composition-root app wiring in `AppWiring.java` (No static singletons).

&#x20;  \* Flat-file/in-memory store patterns (reuse `PeopleStore`, `MemoryStore`, `RecordStore`).

4\. \*\*Clean Code Policy:\*\* Remove all temporary scaffolding, unused imports, or unused helper methods introduced during implementation before locking a phase.



\---



\## 📋 PHASE-BY-PHASE IMPLEMENTATION SPECIFICATION



┌─────────────────────────────────────────────────────────────────────────────┐

│                                         PHASE EXECUTION FLOW                                             │

└──────────────────────────────────────┬──────────────────────────────────────┘

&#x20;                                                    │

&#x20;                                                    ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 1: Configuration, Feature Flags \& Seams                                                            │

│ └─► Run Phase 1 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x20;                                                                                                   │

&#x20;                                                     ┌─────────────────────────────────┘

&#x20;                                                     ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 2: External Integration (CompreFace Adapter)                                                       │

│ └─► Run Phase 2 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x20;                                                                                                   │

&#x20;                                                     ┌─────────────────────────────────┘

&#x20;                                                     ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 3: Core Domain Services \& Data Persistence                                                         │

│ └─► Run Phase 3 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x20;                                                                                                   │

&#x09;					      ┌─────────────────────────────────┘

&#x20;                                                     ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 4: HTTP Endpoints \& App Wiring                                                                     │

│ └─► Run Phase 4 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x20;                                                                                                   │

&#x20;                                                     ┌─────────────────────────────────┘

&#x20;                                                     ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 5: Audit Logging \& Governance                                                                      │

│ └─► Run Phase 5 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x09;											     │

&#x09;					      ┌─────────────────────────────────┘

&#x20;                                                     ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ PHASE 6: Code Cleanup \& Dead-Code Elimination                                                            │

│ └─► Run Phase 6 Tests 3x Consecutive Times ──► PASS? ──────────────────┐                       │

└────────────────────────────────────────────────────────────────────────│────┘

&#x09;											    │

&#x09;					      ┌─────────────────────────────────┘

&#x09;					      ▼

┌─────────────────────────────────────────────────────────────────────────────┐

│ FINAL GLOBAL REVIEW: Run Full Reactor Test Suite 3x Consecutive Times                                    │

│ └─► 3x Clean Passes ──► LOCK FEATURE \& GENERATE FINAL REPORT                                          │

└─────────────────────────────────────────────────────────────────────────────┘



\---



\### 🔹 PHASE 1: Configuration, Feature Flags \& Domain Seams

\* \*\*Tasks:\*\*

&#x20; 1. Add configuration fields with default `false` inert states and environment variable fallbacks:

&#x20;    \* `vision.motion.enabled` (`JARVIS\_VISION\_MOTION\_ENABLED`, default `false`)

&#x20;    \* `vision.motion.webhookSecret` (`JARVIS\_VISION\_MOTION\_WEBHOOK\_SECRET`)

&#x20;    \* `vision.motion.cooldownSec` (`JARVIS\_VISION\_MOTION\_COOLDOWN\_SEC`, default `20`)

&#x20;    \* `vision.face.enabled` (`JARVIS\_FACE\_ENABLED`, default `false`)

&#x20;    \* `vision.face.provider` (`JARVIS\_FACE\_PROVIDER`, default `compreface`)

&#x20;    \* `vision.face.baseUrl` (`JARVIS\_FACE\_BASE\_URL`)

&#x20;    \* `vision.face.apiKey` (`JARVIS\_FACE\_API\_KEY`)

&#x20;    \* `vision.face.similarityThreshold` (`JARVIS\_FACE\_SIMILARITY\_THRESHOLD`, default `0.80`)

&#x20;    \* `vision.face.pendingTtlSec` (`JARVIS\_FACE\_PENDING\_TTL\_SEC`, default `300`)

&#x20;    \* `vision.storage.root` (`JARVIS\_VISION\_STORAGE\_ROOT`, default `\~/.jarvis/vision`)

&#x20; 2. Define `FaceRecognitionClient` interface:

&#x20;    \* `FaceMatchResult recognize(byte\[] imageBytes)`

&#x20;    \* `FaceEnrollResult enroll(byte\[] imageBytes, String personName)`

&#x20; 3. Implement fake/mock `FaceRecognitionClient` for zero-network testing.

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Create `VisionConfigTest.java`.

&#x20; \* Execute: `./mvnw -Dtest=VisionConfigTest test` \*\*3 consecutive times\*\*.

&#x20; \* Must achieve 3/3 clean passes before starting Phase 2.



\---



\### 🔹 PHASE 2: External Integration (CompreFace Adapter)

\* \*\*Tasks:\*\*

&#x20; 1. Implement `CompreFaceClient` using native JDK `HttpClient` and Jackson.

&#x20; 2. Implement HTTP response parsing, mapping provider payloads to internal result records.

&#x20; 3. Ensure non-blocking failure recovery (if provider is down or disabled, fail gracefully without crashing app).

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Create `CompreFaceClientTest.java` (using mock HTTP handlers/responses).

&#x20; \* Execute: `./mvnw -Dtest=CompreFaceClientTest test` \*\*3 consecutive times\*\*.

&#x20; \* Must achieve 3/3 clean passes before starting Phase 3.



\---



\### 🔹 PHASE 3: Core Domain Services \& Data Persistence

\* \*\*Tasks:\*\*

&#x20; 1. Implement `MotionEventService`: Handle payloads, enforce per-camera cooldowns, extract image bytes (base64/URL).

&#x20; 2. Implement `PresenceGreetingService`: Generate personalized greetings for recognized visitors (“Hi {name}, how are you?”) or unknown prompts (“Hi, my name is Jarvis, what is your name?”).

&#x20; 3. Implement `UnknownVisitorEnrollmentService`: Complete enrollment via token, enforce token TTL, persist to provider and `PeopleStore`.

&#x20; 4. Extend data models: Extend `PeopleStore` metadata (`faceSubjects`, `lastSeenAt`, `greetingName`). Add transient file-backed/in-memory store for pending visitor snapshots and tokens.

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Create `MotionEventServiceTest.java`, `PresenceGreetingServiceTest.java`, and `UnknownVisitorEnrollmentServiceTest.java`.

&#x20; \* Execute each test class \*\*3 consecutive times\*\*:

&#x20;   \* `./mvnw -Dtest=MotionEventServiceTest test` (3x)

&#x20;   \* `./mvnw -Dtest=PresenceGreetingServiceTest test` (3x)

&#x20;   \* `./mvnw -Dtest=UnknownVisitorEnrollmentServiceTest test` (3x)

&#x20; \* Must achieve 3/3 clean passes per test class before starting Phase 4.



\---



\### 🔹 PHASE 4: HTTP Endpoints \& App Wiring

\* \*\*Tasks:\*\*

&#x20; 1. Add manual HTTP context handlers in `WebServer.java`:

&#x20;    \* `POST /vision/motion` (Accepts `cameraId`, `timestamp`, `imageBase64`/`snapshotUrl`. Fast `202 Accepted` response. Enforces webhook secret header if configured).

&#x20;    \* `POST /vision/enroll` (Accepts `pendingToken`, `name`. Completes unknown visitor enrollment).

&#x20;    \* `GET /vision/status` (Returns enabled flags, provider health, cooldown settings).

&#x20;    \* `GET /vision/visits` (Returns bounded recent visit event logs).

&#x20; 2. Update `AppWiring.java` (Composition Root): Construct settings, instantiate services, and inject into `WebServer`.

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Create `VisionEndpointsTest.java` and `VisionAppWiringTest.java`.

&#x20; \* Execute:

&#x20;   \* `./mvnw -Dtest=VisionEndpointsTest test` (3x)

&#x20;   \* `./mvnw -Dtest=VisionAppWiringTest test` (3x)

&#x20; \* Must achieve 3/3 clean passes before starting Phase 5.



\---



\### 🔹 PHASE 5: Audit Logging \& Governance

\* \*\*Tasks:\*\*

&#x20; 1. Emit structured `AuditEvent` instances in `AuditLog` for vision events:

&#x20;    \* `VISION\_MOTION\_RECEIVED`

&#x20;    \* `VISION\_FACE\_RECOGNIZED`

&#x20;    \* `VISION\_FACE\_UNKNOWN`

&#x20;    \* `VISION\_ENROLLMENT\_COMPLETED`

&#x20;    \* `VISION\_ENROLLMENT\_FAILED`

&#x20; 2. Privacy \& Security Rules: Never log raw image base64/bytes in audit entries. Enforce retention cleanup policy for stale pending snapshots.

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Create `VisionAuditTest.java`.

&#x20; \* Execute: `./mvnw -Dtest=VisionAuditTest test` \*\*3 consecutive times\*\*.

&#x20; \* Must achieve 3/3 clean passes before starting Phase 6.



\---



\### 🔹 PHASE 6: Code Cleanup \& Dead-Code Elimination

\* \*\*Tasks:\*\*

&#x20; 1. Audit all newly created packages in `java/`.

&#x20; 2. Remove unused imports, dead methods, and temporary scaffolding.

&#x20; 3. Ensure no existing legacy code was broken or inadvertently altered.

\* \*\*Phase Verification Loop:\*\*

&#x20; \* Run all new vision tests combined: `./mvnw -Dtest=\*Vision\*Test test` \*\*3 consecutive times\*\*.

&#x20; \* Must achieve 3/3 clean passes before entering Final Review.



\---



\## 🏆 FINAL GLOBAL REVIEW LOOP (3x FULL REACTOR AUDIT)



Once Phase 6 is locked, run the complete application test suite \*\*THREE CONSECUTIVE TIMES\*\* from the `java/` directory:



```bash

cd java

./mvnw test





If ANY test fails across the full reactor during any of the 3 runs, analyze the failure, fix the issue, reset the global pass counter, and run the 3x full suite again from run 1.



🏁 FINAL OUTPUT FORMAT REQUIRED

Upon 100% successful completion of the Final Global Review Loop, output a final report containing:



1. Change Summary (Concise summary of the feature)
2. File-by-File Rationale (List of created/modified files with reasons)
3. Default-OFF Verification Evidence (Proof that disabled mode stays completely inert)
4. Phase-by-Phase Test Audit Log (Proof showing 3x consecutive pass runs for every phase and the full reactor suite)
5. Removed/Deprecated List (Scaffolding or dead code cleaned up)
6. Follow-Up Recommendations (Optional non-blocking improvements)



BEGIN PHASE 1 NOW. DO NOT ADVANCE TO ANY PHASE UNTIL THE PREVIOUS PHASE SUCCEEDS 3 CONSECUTIVE TIMES.





