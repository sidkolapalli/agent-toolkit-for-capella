import os
import pathlib
import types
import pytest


class DummyClient:
    def __init__(self, available=True):
        self.available = available
        self.created = []

    def is_available(self):
        return self.available

    def create_diagram(self, name, diagram_type, layer, model_path, description=None, initial_elements=None):
        item = {"diagramId": f"{layer}_{diagram_type}_id", "name": name, "type": diagram_type, "layer": layer, "modelPath": model_path}
        self.created.append(item)
        return item


@pytest.mark.asyncio
async def test_create_standard_view_set_success(monkeypatch):
    from capella_mcp.view_sets import create_view_set_tools

    # Fake MCP server with tool registry
    class FakeMCP:
        def __init__(self):
            self.tools = {}

        def tool(self, *args, **kwargs):
            def deco(fn):
                self.tools[fn.__name__] = fn
                return fn
            return deco

    mcp = FakeMCP()
    create_view_set_tools(mcp)

    # Monkeypatch java client factory
    from capella_mcp import view_sets
    dummy = DummyClient()
    monkeypatch.setattr(view_sets, "get_java_client", lambda: dummy)

    tool = mcp.tools["create_standard_view_set"]
    model_path = str(pathlib.Path("test_gmhcd_copy/GMHCD.aird").absolute())
    result = await tool(model_path=model_path, layers=["LA"], include_common=True)

    assert result["success"] is True
    assert "LA" in result["created"]
    assert any(itm["type"] == "LAB" for itm in result["created"]["LA"])  # at least LAB in LA


@pytest.mark.asyncio
async def test_create_standard_view_set_service_unavailable(monkeypatch):
    from capella_mcp.view_sets import create_view_set_tools

    class FakeMCP:
        def __init__(self):
            self.tools = {}
        def tool(self, *args, **kwargs):
            def deco(fn):
                self.tools[fn.__name__] = fn
                return fn
            return deco

    mcp = FakeMCP()
    create_view_set_tools(mcp)

    from capella_mcp import view_sets
    monkeypatch.setattr(view_sets, "get_java_client", lambda: DummyClient(available=False))

    tool = mcp.tools["create_standard_view_set"]
    model_path = str(pathlib.Path("test_gmhcd_copy/GMHCD.aird").absolute())
    result = await tool(model_path=model_path)
    assert result["success"] is False
    assert result["created"] == {}

