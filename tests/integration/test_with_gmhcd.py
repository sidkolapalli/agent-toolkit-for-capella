#!/usr/bin/env python
"""
Test the MCP server with the actual GMHCD Capella model
"""
import sys
import asyncio
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

from capella_mcp.server import CapellaMCPServer, ServerConfig
from capella_mcp.model_manager import ModelManager
from capella_mcp.context_manager import ContextManager
from capella_mcp.tools import ToolRegistry
from capella_mcp.transaction_manager import TransactionManager
from capella_mcp.resources import ResourceManager


# Path to the actual GMHCD model
GMHCD_MODEL_PATH = r"<local-path>"


# Removed duplicate function - now integrated in main()


async def test_context_optimization(model_manager: ModelManager, model_id: str):
    """Test context optimization for the model"""
    print("\n2. Testing Context Optimization")

    # Use the passed model_manager instead of creating new one
    context_manager = ContextManager()

    # Get model wrapper
    wrapper = model_manager.get_model(model_id)
    if not wrapper:
        print("   [FAIL] Model not found")
        return

    # Test different optimization strategies
    strategies = ["overview", "detailed", "hierarchical"]

    for strategy in strategies:
        print(f"\n   Testing '{strategy}' strategy:")
        try:
            optimized = context_manager.optimize_context(
                wrapper.model, strategy_name=strategy
            )

            metadata = optimized.get("metadata", {})
            print(f"   - Total elements: {metadata.get('total_elements', 0)}")
            print(f"   - Included elements: {metadata.get('included_elements', 0)}")
            print(f"   - Token estimate: {metadata.get('token_estimate', 0)}")
            print(f"   [OK] Strategy '{strategy}' completed")

        except Exception as e:
            print(f"   [FAIL] Strategy '{strategy}' failed: {e}")


async def test_resource_listing(model_manager: ModelManager, model_id: str):
    """Test resource listing for the model"""
    print("\n3. Testing Resource Management")

    # Use passed model_manager
    context_manager = ContextManager()
    resource_manager = ResourceManager(
        model_manager=model_manager, context_manager=context_manager
    )

    try:
        # List resources
        resources = await resource_manager.list_resources()
        print(f"   Found {len(resources)} resources")

        # Display first few resources
        for i, resource in enumerate(resources[:5]):
            print(f"   - {resource.uri}: {resource.name}")

        if len(resources) > 5:
            print(f"   ... and {len(resources) - 5} more")

        print("   [OK] Resource listing completed")

    except Exception as e:
        print(f"   [FAIL] Resource listing failed: {e}")


async def test_tool_operations(model_manager: ModelManager, model_id: str):
    """Test tool operations on the model"""
    print("\n4. Testing Tool Operations")

    # Use passed model_manager
    tool_registry = ToolRegistry(model_manager=model_manager)

    # List available tools
    tools = tool_registry.list_tools()
    print(f"   Available tools: {len(tools)}")
    for tool in tools:
        print(f"   - {tool['name']}: {tool['description']}")

    # Test query operation
    print("\n   Testing query_components tool:")
    try:
        result = await tool_registry.call_tool(
            "query_components", {"model_id": model_id, "filters": {}}
        )

        components = result.get("components", [])
        print(f"   Found {result.get('count', 0)} components")
        for comp in components[:3]:
            print(f"   - {comp['name']} ({comp['type']})")

        print("   [OK] Query operation completed")

    except Exception as e:
        print(f"   [FAIL] Query operation failed: {e}")

    # Test validation
    print("\n   Testing validate_model tool:")
    try:
        result = await tool_registry.call_tool(
            "validate_model", {"model_id": model_id, "rules": ["basic"]}
        )

        print(f"   Model valid: {result.get('valid', False)}")
        print(f"   Rules checked: {result.get('rules_checked', [])}")

        issues = result.get("issues", [])
        if issues:
            print(f"   Issues found: {len(issues)}")
            for issue in issues[:3]:
                print(f"   - {issue}")
        else:
            print("   No issues found")

        print("   [OK] Validation completed")

    except Exception as e:
        print(f"   [FAIL] Validation failed: {e}")


async def test_transaction_safety(model_id: str):
    """Test transaction safety with the model"""
    print("\n5. Testing Transaction Safety")

    transaction_manager = TransactionManager()

    print("   Testing transaction creation and commit:")
    try:
        # Begin transaction
        async with transaction_manager.transaction(
            model_data={"model_id": model_id}
        ) as txn:
            print(f"   - Transaction created: {txn.id}")
            print(f"   - Status: {txn.status}")

            # Add some operations
            txn.add_operation(
                {"type": "query", "target": "components", "model_id": model_id}
            )

            print(f"   - Added {len(txn.operations)} operations")

        print(f"   - Transaction committed: {txn.status}")
        print("   [OK] Transaction completed successfully")

    except Exception as e:
        print(f"   [FAIL] Transaction failed: {e}")


async def test_server_integration():
    """Test full server integration with GMHCD model"""
    print("\n6. Testing Full Server Integration")

    config = ServerConfig(name="capella-gmhcd-server", version="1.0.0")

    try:
        server = CapellaMCPServer(config)
        print(
            f"   [OK] Server initialized: {server.config.name} v{server.config.version}"
        )

        # Test server capabilities
        capabilities = await server.get_capabilities()
        print("   Server capabilities:")
        print(f"   - Resources: {capabilities.resources is not None}")
        print(f"   - Tools: {capabilities.tools is not None}")
        print(f"   - Prompts: {capabilities.prompts is not None}")

        print("   [OK] Server integration successful")

    except Exception as e:
        print(f"   [FAIL] Server integration failed: {e}")


async def main():
    """Run all GMHCD model tests"""
    print("\n" + "=" * 60)
    print("CAPELLA MCP SERVER - GMHCD MODEL INTEGRATION TEST")
    print("=" * 60)

    # Create shared model manager
    model_manager = ModelManager()

    # Test loading the model
    print("\n" + "=" * 60)
    print("Testing GMHCD Model Loading")
    print("=" * 60)

    print(f"\n1. Loading model from: {GMHCD_MODEL_PATH}")
    try:
        model_id = await model_manager.load_model(GMHCD_MODEL_PATH)
        print(f"   [OK] Model loaded with ID: {model_id}")

        wrapper = model_manager.get_model(model_id)
        if wrapper:
            print("   [OK] Model wrapper created")
            print(f"   Model ID: {wrapper.model_id}")
            print(f"   Model Path: {wrapper.model['path']}")
        else:
            print("   [FAIL] Could not get model wrapper")
            model_id = None
    except Exception as e:
        print(f"   [FAIL] Error loading model: {e}")
        model_id = None

    if model_id:
        # Run tests with the loaded model
        await test_context_optimization(model_manager, model_id)
        await test_resource_listing(model_manager, model_id)
        await test_tool_operations(model_manager, model_id)
        await test_transaction_safety(model_id)
        await test_server_integration()
    else:
        print("\n[ERROR] Could not load model, skipping remaining tests")

    print("\n" + "=" * 60)
    print("TEST SUITE COMPLETED")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
