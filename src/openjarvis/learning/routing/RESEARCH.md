# Routing layer — research notes

Stage-1 research for the RouteLLM-inspired SLM/LLM routing upgrade
(classifier router, cost dial, swappable strategies, decoupled
architecture, recalibration). Written before any code in this pass was
added, so the design decisions below can be checked against it.

## 1. External reference: RouteLLM (lm-sys)

- Paper: *RouteLLM: Learning to Route LLMs with Preference Data*,
  Ong et al., 2024 — [arXiv:2406.18665](https://arxiv.org/abs/2406.18665).
- Code: [github.com/lm-sys/RouteLLM](https://github.com/lm-sys/RouteLLM)
  (Apache-2.0).
- Announcement: [lmsys.org/blog/2024-07-01-routellm](https://www.lmsys.org/blog/2024-07-01-routellm/).

Core ideas that shaped this module:

1. **Binary strong/weak framing.** RouteLLM doesn't rank many models — it
   picks between exactly one "strong" model (expensive, high quality) and
   one "weak" model (cheap, lower quality) per request. Every router
   in their framework outputs `P(strong model wins)` for a query.
2. **Threshold, not a hard decision.** The router's raw output is a
   continuous win-probability. A single scalar **cost threshold** `t`
   converts that into a routing decision: route to the strong model iff
   `P(strong wins) >= t`. Raising `t` sends fewer queries to the strong
   model (cheaper, lower average quality); lowering it does the
   opposite. This is exactly the "cost threshold dial" in this project's
   feature list, so we reuse the name and semantics directly.
3. **Four router implementations**, in increasing order of cost/complexity:
   - `sw_ranking` — similarity-weighted ranking against labeled examples
     (nearest-neighbor style, no training beyond storing embeddings).
   - `mf` — matrix factorization over (query embedding, model) pairs;
     the cheapest *learned* router and the closest in spirit to what we
     ship as the default classifier backend.
   - `bert` — a fine-tuned BERT classifier over the raw query text.
   - `causal_llm` — a fine-tuned causal LM classifier (most expensive,
     highest quality).
4. **Training data**: human preference labels from Chatbot Arena,
   augmented with GPT-4-as-judge labels comparing the strong/weak model
   pair. Reported results: ~85% cost reduction while retaining ~95% of
   GPT-4's quality on MT-Bench when routing GPT-4 vs. Mixtral-8x7B.

### Why we didn't vendor RouteLLM's actual checkpoints

The initial plan (confirmed with the user) was "pretrained checkpoint +
fine-tune hook." After inspecting this repository's dependency policy,
that plan doesn't fit as-is and was adapted — flagged here explicitly
rather than silently substituted:

- `pyproject.toml` keeps the **base** install dependency-light (no
  `numpy`, `torch`, `transformers`, or `scikit-learn`). Those only show
  up in *optional* extras (`orchestrator-training`, `memory-faiss`,
  `memory-colbert`). RouteLLM's `bert`/`causal_llm` routers need
  `transformers` + `torch` + a multi-hundred-MB-to-multi-GB checkpoint
  download, which would force those heavy deps into a routing feature
  that today has zero ML dependencies.
- This is a Windows dev workstation with no confirmed GPU/inference
  server reachable from this task, so downloading and running a
  transformer checkpoint isn't verifiable from here.
- RouteLLM's own `mf` (matrix factorization) router is explicitly their
  cheapest *trained* router and is conceptually a small linear model
  over hashed/embedded query features — that's directly reproducible
  in pure Python with no new required dependency.

**Decision:** ship a `RouteClassifier` that is architecturally the same
shape as RouteLLM's `mf` router (a trained linear model over query
features producing `P(strong wins)`, thresholded by the cost dial) with
two backends:

- `simple` (default, zero extra deps): hashed bag-of-words/n-gram
  features + logistic regression trained with plain-Python gradient
  descent. Ships in the base install.
- `sklearn` (optional, `pip install openjarvis[learning-router-classifier]`):
  `TfidfVectorizer` + `sklearn.linear_model.LogisticRegression` for
  better accuracy when the extra is installed. Same interface, so
  callers can swap backends without touching `ClassifierRouterPolicy`.

A pretrained-checkpoint path (`bert`/`causal_llm`-equivalent) is left as
a documented extension point in `classifier.py` rather than implemented,
since it would need real GPU/network resources this task can't verify.

## 2. Existing codebase inspection

Before writing anything, the repo was searched for prior art. Findings
that directly shaped where code went:

- **`src/openjarvis/intelligence/router.py` is a backward-compat shim.**
  Its docstring says *"canonical location is learning.router"* and it
  just re-exports from `openjarvis.learning.routing.router`. Building a
  fresh `intelligence/routing/` package as originally scoped would have
  created a second "canonical" routing location next to one that
  explicitly says it isn't canonical — confirmed with the user and
  rejected in favor of extending the real canonical location.
- **`src/openjarvis/learning/routing/` already exists** with:
  - `router.py` — `HeuristicRouter` (rule-based; registered as
    `"heuristic"` in `RouterPolicyRegistry` via `heuristic_policy.py`).
  - `complexity.py` — `score_complexity()`, a hand-tuned weighted-signal
    query complexity scorer (code/math/reasoning/length/multi-part
    regex signals → 0–1 score + token-budget tier). Reused as a feature
    source and as the bootstrap-teacher signal for the classifier (see
    `training.py`).
  - `learned_router.py` — `LearnedRouterPolicy` (registered as
    `"learned"`): trace-driven *online* policy that maps a coarse
    `query_class` (from `_utils.classify_query`) to the best-observed
    model, learned incrementally from `Trace` outcomes/feedback. This is
    complementary to, not a replacement for, a supervised classifier:
    it has no notion of "which model is *generally* stronger," only
    "which model happened to work for this class of query so far."
  - `heuristic_reward.py` — `HeuristicRewardFunction`, a weighted
    latency/cost/efficiency reward, useful as a training signal.
- **`openjarvis.core.registry.RouterPolicyRegistry`** — a generic
  decorator/dict registry (`register`, `register_value`, `get`,
  `create`, `keys`). Every routing strategy self-registers via an
  idempotent `ensure_registered()` call at import time. New strategies
  plug into this directly instead of inventing a second registry.
- **`openjarvis.core.types.RoutingContext` / `ModelSpec`** — the shared,
  model-agnostic context and model-metadata dataclasses used by every
  existing policy. `ModelSpec` already carries `parameter_count_b`,
  `active_parameter_count_b`, `requires_api_key`, `provider`, and a free
  `metadata` dict with `pricing_input`/`pricing_output` for cloud
  models — enough to bucket *any* registered model (local or cloud, gguf
  or proprietary) into a strong/weak tier without hardcoding model
  names. This is what `model_tiers.py` builds on for the "decoupled,
  works with any model" requirement.
- **`openjarvis.intelligence.model_catalog`** — `BUILTIN_MODELS` /
  `ModelRegistry`: ~40 built-in `ModelSpec` entries spanning local GGUF
  models (0.8B–397B params) and cloud proprietary models (GPT, Claude,
  Gemini, MiniMax) with real per-token pricing in metadata.
- **`openjarvis.traces.store.TraceStore` / `traces.analyzer.TraceAnalyzer`**
  — SQLite-backed trace persistence + read-only aggregation, already the
  data source `LearnedRouterPolicy.update_from_traces()` uses. Reused
  as-is for the classifier's `training.py` recalibration pipeline rather
  than inventing a parallel trace format.
- **`configs/openjarvis/config.toml`** — TOML-based, single config file.
  `[learning.routing]` already exists (`policy = "heuristic"`,
  `min_samples = 5`). `core/config.py::load_config()` maps TOML sections
  onto dataclasses **generically** (`_apply_toml_section`, driven by a
  `top_sections` tuple that includes `"learning"`) — any new field added
  to the `RoutingLearningConfig` dataclass is automatically picked up
  from `[learning.routing]` in the TOML with no loader changes needed.
  There is no YAML anywhere in this repo's config system and no `yaml`
  dependency in `pyproject.toml`. **Deviation from the original ask**:
  the cost-threshold dial lives in `[learning.routing]` in
  `configs/openjarvis/config.toml`, not a new `configs/routing.yaml` —
  a second config format/file would fragment the one config surface the
  rest of the app already uses, which contradicts the same
  "no duplicate canonical location" principle applied to the module
  path above.
- **`src/openjarvis/server/api_routes.py`** — FastAPI routers registered
  in `api_routes.py`/mounted in `app.py`. `learning_router` (prefix
  `/v1/learning`) already exposes `GET /v1/learning/policy` returning
  `lc.routing.policy` / `min_samples`. The cost-dial API (feature 2)
  extends this router with `GET`/`PUT` endpoints on
  `/v1/learning/routing/config` rather than creating a new router,
  matching the existing pattern.
- **Dashboard's "routing"** (`java/app/src/main/resources/dashboard.html`,
  `RoutingSettings.java`) refers to something unrelated: Tier-2
  OpenHuman failover/circuit-breaker routing between providers on
  errors. Not touched by this work and not the same concept as
  model-selection cost routing — flagged here so the two aren't
  conflated in docs or code review.

## 3. Design decisions carried into implementation

| Requirement | Decision |
|---|---|
| Trained classifier, not keyword rules | `RouteClassifier`: hashed-feature logistic regression (pure Python default, optional sklearn backend), trained via `training.py`, not branching keyword logic. `HeuristicRouter` (rules) stays untouched as the `"heuristic"` strategy. |
| Configurable cost threshold dial | `cost_threshold: float` field on `RoutingLearningConfig` (`[learning.routing]` in `config.toml`) + `GET`/`PUT /v1/learning/routing/config` + `ClassifierRouterPolicy.set_cost_threshold()`. |
| Swappable strategies | `"classifier"` and `"similarity"` added to `RouterPolicyRegistry` alongside existing `"heuristic"`/`"learned"`. `create_router_policy(strategy, ...)` factory. |
| Decoupled, any model local/cloud | `model_tiers.py` buckets strategy purely from `ModelSpec` metadata already in `ModelRegistry`, no hardcoded model names. |
| Recalibration/retraining | `training.py`: `build_dataset_from_traces()` (real usage data) + `bootstrap_dataset()` (heuristic-distilled seed data, no paid API calls) + `retrain()` / `fine_tune()` with versioned persistence. |
