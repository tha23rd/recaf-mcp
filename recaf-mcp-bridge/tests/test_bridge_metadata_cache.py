from __future__ import annotations

from types import SimpleNamespace

import pytest

from recaf_mcp_bridge.bridge import RecafMcpBridge


class FakeBackend:
    def __init__(self, *, tools: list[str], resources: list[str]):
        self._tools = tools
        self._resources = resources
        self.list_tools_calls = 0
        self.list_resources_calls = 0

    async def list_tools(self):
        self.list_tools_calls += 1
        return SimpleNamespace(tools=self._tools)

    async def list_resources(self):
        self.list_resources_calls += 1
        return SimpleNamespace(resources=self._resources)


@pytest.mark.asyncio
async def test_list_tools_uses_ttl_cache(monkeypatch: pytest.MonkeyPatch):
    bridge = RecafMcpBridge()
    backend = FakeBackend(tools=["tool-a"], resources=[])
    bridge._set_backend(backend)

    now = 100.0
    monkeypatch.setattr("recaf_mcp_bridge.bridge.time.monotonic", lambda: now)

    first = await bridge._list_tools()
    second = await bridge._list_tools()

    assert first == ["tool-a"]
    assert second == ["tool-a"]
    assert backend.list_tools_calls == 1


@pytest.mark.asyncio
async def test_list_resources_uses_ttl_cache(monkeypatch: pytest.MonkeyPatch):
    bridge = RecafMcpBridge()
    backend = FakeBackend(tools=[], resources=["recaf://classes"])
    bridge._set_backend(backend)

    now = 200.0
    monkeypatch.setattr("recaf_mcp_bridge.bridge.time.monotonic", lambda: now)

    first = await bridge._list_resources()
    second = await bridge._list_resources()

    assert first == ["recaf://classes"]
    assert second == ["recaf://classes"]
    assert backend.list_resources_calls == 1


@pytest.mark.asyncio
async def test_backend_reconnect_clears_metadata_cache(monkeypatch: pytest.MonkeyPatch):
    bridge = RecafMcpBridge()

    now = 300.0
    monkeypatch.setattr("recaf_mcp_bridge.bridge.time.monotonic", lambda: now)

    backend_one = FakeBackend(tools=["tool-a"], resources=["res-a"])
    bridge._set_backend(backend_one)
    assert await bridge._list_tools() == ["tool-a"]
    assert await bridge._list_resources() == ["res-a"]

    backend_two = FakeBackend(tools=["tool-b"], resources=["res-b"])
    bridge._set_backend(backend_two)

    assert await bridge._list_tools() == ["tool-b"]
    assert await bridge._list_resources() == ["res-b"]
    assert backend_one.list_tools_calls == 1
    assert backend_one.list_resources_calls == 1
    assert backend_two.list_tools_calls == 1
    assert backend_two.list_resources_calls == 1
