"""Multi-agent: message bus trace, bounded debate, arbitration, single-agent fallback."""

from jarvis_agents import MessageBus, MultiAgentOrchestrator


async def test_multi_agent_produces_traceable_messages(settings):
    bus = MessageBus(settings.sqlite_path)
    orch = MultiAgentOrchestrator(bus, retriever=None, max_rounds=3, arbitration="critic-override")
    result = await orch.run("investigate the topic", owner="u", tenant="t")
    assert result["mode"] == "multi-agent"
    assert result["decision"] in ("approved", "needs_revision")
    session = bus.get_session(result["session_id"])
    senders = {m["sender"] for m in session["messages"]}
    assert {"coordinator", "researcher", "coder", "verifier", "memory_steward"} <= senders
    bus.close()


async def test_arbitration_majority_vote(settings):
    bus = MessageBus(settings.sqlite_path)
    orch = MultiAgentOrchestrator(bus, retriever=None, max_rounds=2, arbitration="majority")
    result = await orch.run("some goal", owner="u", tenant="t")
    assert "majority vote" in result["arbitration"]
    bus.close()


async def test_researcher_uses_retriever(settings):
    bus = MessageBus(settings.sqlite_path)

    def retriever(q):
        return {"answer": "grounded finding [1]", "citations": [{"marker": "[1]", "source": "doc"}], "confidence": 0.8}

    orch = MultiAgentOrchestrator(bus, retriever=retriever, max_rounds=2)
    result = await orch.run("research something", owner="u", tenant="t")
    assert result["citations"]
    assert result["decision"] == "approved"
    bus.close()


async def test_fallback_to_single_agent(settings):
    bus = MessageBus(settings.sqlite_path)

    async def broken_tool_runner(goal):
        raise RuntimeError("toolsmith exploded")

    # A specialist raising an unhandled error triggers the single-agent fallback;
    # the fallback researcher still returns a graceful result.
    orch = MultiAgentOrchestrator(bus, retriever=None, tool_runner=broken_tool_runner, max_rounds=2)
    result = await orch.run("goal", owner="u", tenant="t")
    assert result["mode"] == "single-agent"
    assert result["decision"] == "single-agent-fallback"
    bus.close()
