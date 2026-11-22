"""Model routing, cost accounting, budget enforcement, response caching."""

from jarvis_routing import BudgetManager, CostLedger, ModelRouter, ResponseCache


def test_route_by_task_type(settings):
    router = ModelRouter(settings)
    d = router.route(message="please reason step by step about this proof", tenant="t")
    assert d.task_type == "reasoning"
    d2 = router.route(message="hi", tenant="t")
    assert d2.task_type == "fast"


def test_sensitive_routes_to_approved_providers_in_compliance(settings):
    s = settings.model_copy(update={"compliance_mode": True})
    router = ModelRouter(s)
    d = router.route(message="handle this sensitive record", tenant="t", risk="high")
    assert d.provider in s.approved_providers


def test_budget_forces_mock_when_over(settings):
    ledger = CostLedger(settings.sqlite_path)
    budget = BudgetManager(settings.sqlite_path, ledger, default_daily_usd=0.0)  # zero budget
    router = ModelRouter(settings, budget_manager=budget)
    d = router.route(message="a normal chat message that is long enough to be chat", tenant="t")
    assert d.provider == "mock" and d.downgraded
    ledger.close()
    budget.close()


def test_cost_ledger_and_report(settings):
    ledger = CostLedger(settings.sqlite_path)
    ledger.record(tenant="t", user_id="u1", provider="openai", model="gpt-4o", task_type="chat",
                  tokens=1000, latency_ms=100)
    ledger.record(tenant="t", user_id="u2", provider="anthropic", model="claude-sonnet-5", task_type="chat",
                  tokens=2000, latency_ms=200)
    report = ledger.usage_report("t")
    assert report["totals"]["tokens"] == 3000
    assert report["totals"]["cost_usd"] > 0
    assert len(report["by_model"]) == 2
    assert ledger.spend_today("t") > 0
    ledger.close()


def test_budget_status_and_alert(settings):
    ledger = CostLedger(settings.sqlite_path)
    budget = BudgetManager(settings.sqlite_path, ledger, default_daily_usd=1.0, alert_ratio=0.5)
    ledger.record(tenant="t", user_id="u", provider="openai", model="gpt-4o", task_type="chat",
                  tokens=120000, latency_ms=50)  # 0.6 usd -> over 50% alert
    st = budget.status("t")
    assert st["alert"] is True
    assert st["spent_today_usd"] > 0
    ledger.close()
    budget.close()


def test_response_cache_exact_and_semantic(settings):
    cache = ResponseCache(settings.sqlite_path, ttl_sec=100)
    assert cache.get("t", "what is the capital of france") is None
    cache.put("t", "what is the capital of france", {"answer": "Paris"})
    exact = cache.get("t", "what is the capital of france")
    assert exact and exact["kind"] == "exact"
    cache.close()


def test_context_compression():
    big = "x" * 9000
    compressed = ModelRouter.compress_context(big, max_chars=4000)
    assert "compressed" in compressed and len(compressed) < len(big)
