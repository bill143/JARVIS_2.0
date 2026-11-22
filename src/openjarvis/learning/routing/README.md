# `openjarvis.learning.routing`

Model-selection routing for the Intelligence pillar: given a query, pick
which model should answer it. Four swappable strategies, a trainable
classifier in the RouteLLM style, and a configurable cost/quality dial.

> This is the module the original "Intelligence module" upgrade request
> targeted. It lives under `learning/routing/`, not `intelligence/`,
> because `intelligence/router.py` is a documented backward-compat shim
> pointing back here — see `RESEARCH.md` for the full reasoning.

## Strategies

| Strategy key | Class | How it decides | Needs training/data? |
|---|---|---|---|
| `"heuristic"` | `HeuristicRouter` | Fixed rules over query signals (code/math/urgency/complexity) | No |
| `"learned"` | `LearnedRouterPolicy` | Online: best-observed model per coarse query class, from trace outcomes | Learns from live traffic |
| `"classifier"` | `ClassifierRouterPolicy` | Trained P(strong model wins) classifier + cost threshold | Yes — see below |
| `"similarity"` | `SimilarityRouterPolicy` | k-NN vote over labeled example queries | Needs seed exemplars |

## Quick start

```python
from openjarvis.learning.routing import create_router_policy
from openjarvis.learning.routing.router import build_routing_context
from openjarvis.learning.routing.training import retrain

# 1. Train a classifier. With no trace data yet, this uses the built-in
#    heuristic-distilled bootstrap corpus (no LLM calls, no downloads).
result = retrain(include_bootstrap=True)
classifier = result["classifier"]

# 2. Build a policy from it.
policy = create_router_policy(
    "classifier",
    classifier=classifier,
    cost_threshold=0.5,             # the cost/quality dial
    available_models=["qwen3.5:4b", "qwen3.5:397b"],
)

# 3. Route a query.
ctx = build_routing_context("Explain step by step how gradient descent works")
model = policy.select_model(ctx)   # -> "qwen3.5:397b" (routed to the strong model)
```

## The cost threshold dial

`policy.select_model()` routes to the strong model iff
`classifier.predict_proba(query) >= cost_threshold`. Raise the threshold
to save more (fewer queries go to the expensive model); lower it to
spend more for quality. Three equivalent ways to set it:

```python
policy.set_cost_threshold(0.7)   # in-process
```

```bash
jarvis config set learning.routing.cost_threshold 0.7
```

```bash
curl -X PUT localhost:8000/v1/learning/routing/config \
  -H 'Content-Type: application/json' \
  -d '{"cost_threshold": 0.7}'
```

`GET /v1/learning/routing/config` reads the current value back, plus the
list of registered strategies:

```bash
curl localhost:8000/v1/learning/routing/config
```

## Swapping strategies

```python
from openjarvis.learning.routing import create_router_policy

heuristic = create_router_policy("heuristic", default_model="qwen3:8b")
learned = create_router_policy("learned", default_model="qwen3:8b")
classifier = create_router_policy("classifier", cost_threshold=0.5)
similarity = create_router_policy("similarity", k=3)
```

Or via config: `learning.routing.policy = "classifier"` in
`config.toml` (or `PUT /v1/learning/routing/config {"policy": "classifier"}`).

## Recalibrating / retraining

```python
from openjarvis.learning.routing.training import retrain, fine_tune
from openjarvis.traces.store import TraceStore

store = TraceStore("~/.openjarvis/traces.db")

# Full retrain from real usage, topping up with bootstrap data if thin:
result = retrain(trace_store=store, save_path="~/.openjarvis/routing_classifier.json")

# Incremental nudge from newly observed traces (warm-started, no bootstrap):
fine_tune(result["classifier"], store, save_path="~/.openjarvis/routing_classifier.json")
```

Load a previously trained classifier without retraining:

```python
from openjarvis.learning.routing.classifier import RouteClassifier

classifier = RouteClassifier.load("~/.openjarvis/routing_classifier.json")
```

## Similarity strategy — seeding exemplars

`similarity` needs labeled examples to compare against. Use a handful of
*textually varied* examples per class — see `ARCHITECTURE.md`'s "Known
limitations" for why single-word exemplars like `"Hi"` alone are
unreliable:

```python
policy = create_router_policy("similarity", available_models=["small-model", "big-model"])
policy.add_exemplars([
    ("Hi there, how are you?", "small-model"),
    ("What's the capital of France?", "small-model"),
    ("Explain step by step how X works, then compare it to Y", "big-model"),
    ("Write code to do X, then explain its time complexity", "big-model"),
])
```

## Further reading

- `RESEARCH.md` — RouteLLM background and how it shaped these decisions,
  plus the full codebase-inspection trail.
- `ARCHITECTURE.md` — component/sequence diagrams and known limitations.
- `../../../../tests/learning/routing/` — unit + integration tests.
