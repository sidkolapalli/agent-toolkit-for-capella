#!/usr/bin/env python
"""
Simple test runner to verify basic functionality
"""
import sys
import asyncio
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

from capella_mcp.server import CapellaMCPServer, ServerConfig
from capella_mcp.tools import ToolRegistry
from capella_mcp.resources import ResourceManager
from capella_mcp.transaction_manager import TransactionManager
from capella_mcp.model_manager import ModelManager
from capella_mcp.context_manager import ContextManager


async def test_server_initialization():
    """Test server can be initialized"""
    print("Testing server initialization...")
    config = ServerConfig(name="test-server", version="0.1.0")
    server = CapellaMCPServer(config)
    assert server.config.name == "test-server"
    print("[OK] Server initialization passed")


async def test_tool_registry():
    """Test tool registry"""
    print("Testing tool registry...")
    registry = ToolRegistry()

    # Check default tools are registered
    tools = registry.list_tools()
    assert len(tools) > 0
    assert any(t["name"] == "create_component" for t in tools)

    # Test tool execution
    result = await registry.call_tool(
        "create_component",
        {
            "model_id": "test-model",
            "name": "TestComponent",
            "type": "PhysicalComponent",
        },
    )
    assert "component_id" in result
    print("[OK] Tool registry passed")


async def test_transaction_manager():
    """Test transaction manager"""
    print("Testing transaction manager...")
    tm = TransactionManager()

    # Begin transaction
    txn = await tm.begin_transaction("test-data")
    assert txn.id is not None
    assert txn.status == "pending"

    # Commit transaction
    await tm.commit_transaction()
    assert txn.status == "committed"
    print("[OK] Transaction manager passed")


async def test_model_manager():
    """Test model manager"""
    print("Testing model manager...")
    mm = ModelManager()

    # Load model
    model_id = await mm.load_model("test-model.aird")
    assert model_id is not None

    # Get model
    wrapper = mm.get_model(model_id)
    assert wrapper is not None
    print("[OK] Model manager passed")


async def test_context_manager():
    """Test context manager"""
    print("Testing context manager...")
    cm = ContextManager()

    # Test optimization
    model_data = {"id": "test", "name": "TestModel", "type": "Model"}
    optimized = cm.optimize_context(model_data)

    assert "elements" in optimized
    assert "metadata" in optimized
    print("[OK] Context manager passed")


async def test_resource_manager():
    """Test resource manager"""
    print("Testing resource manager...")
    mm = ModelManager()
    cm = ContextManager()
    rm = ResourceManager(model_manager=mm, context_manager=cm)

    # List resources
    resources = await rm.list_resources()
    assert isinstance(resources, list)

    # Test pagination
    resources_page = await rm.list_resources(cursor="0")
    assert isinstance(resources_page, list)
    print("[OK] Resource manager passed")


async def main():
    """Run all tests"""
    print("=" * 50)
    print("Running Capella MCP Server Tests")
    print("=" * 50)

    tests = [
        test_server_initialization,
        test_tool_registry,
        test_transaction_manager,
        test_model_manager,
        test_context_manager,
        test_resource_manager,
    ]

    failed = []
    for test in tests:
        try:
            await test()
        except Exception as e:
            print(f"[FAIL] {test.__name__} failed: {e}")
            failed.append(test.__name__)

    print("=" * 50)
    if not failed:
        print("All tests passed!")
        return 0
    else:
        print(f"Failed tests: {', '.join(failed)}")
        return 1


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
