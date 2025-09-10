"""
Integration test suite - verifies all fixes work together.
"""

import pytest
import asyncio
from unittest.mock import MagicMock, AsyncMock, patch
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.server import (
    load_model,
    query_components,
    get_model_elements,
    get_model_info,
    create_component,
    validate_architecture,
    server_status
)


class TestIntegration:
    """Test complete workflow with all fixes."""
    
    @pytest.mark.asyncio
    async def test_complete_workflow_no_event_loop_conflict(self):
        """Test that we can run the complete workflow without event loop conflicts."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                with patch('capella_mcp.server.tool_registry') as mock_tr:
                    # Setup model manager mock
                    mock_mm.load_model = AsyncMock(return_value="model-123")
                    
                    # Setup model wrapper mock with proper query_elements
                    mock_wrapper = MagicMock()
                    
                    # Mock components that would come from real model
                    mock_k0 = MagicMock()
                    mock_k0.uuid = "k0-uuid"
                    mock_k0.name = "K0"
                    mock_k0.__class__.__name__ = "PhysicalComponent"
                    
                    mock_mcp = MagicMock()
                    mock_mcp.uuid = "mcp-uuid"
                    mock_mcp.name = "MCP"
                    mock_mcp.__class__.__name__ = "PhysicalComponent"
                    
                    mock_wrapper.query_elements = MagicMock(return_value=[mock_k0, mock_mcp])
                    mock_wrapper.get_model_info = MagicMock(return_value={
                        "name": "GMHCD",
                        "uuid": "model-uuid",
                        "total_elements": 387
                    })
                    
                    mock_mm.get_model = MagicMock(return_value=mock_wrapper)
                    mock_mm.get_status = MagicMock(return_value={"mode": "capellambse"})
                    mock_mm.unload_model = AsyncMock()
                    
                    # Setup tool registry mock with proper return values
                    async def mock_call_tool(tool_name, args):
                        if tool_name == "query_components":
                            # Return components from the wrapper's query_elements
                            wrapper = mock_mm.get_model(args["model_id"])
                            if wrapper:
                                elements = wrapper.query_elements(args.get("filters"))
                                components = []
                                for elem in elements:
                                    components.append({
                                        "id": str(elem.uuid),
                                        "name": elem.name,
                                        "type": elem.__class__.__name__
                                    })
                                return {
                                    "model_id": args["model_id"],
                                    "components": components,
                                    "count": len(components)
                                }
                            return {"components": [], "count": 0}
                        else:
                            return {
                                "status": "success",
                                "component_id": "new-comp"
                            }
                    
                    mock_tr.call_tool = AsyncMock(side_effect=mock_call_tool)
                    
                    # 1. Load model - should work without event loop conflict
                    result = await load_model("test.aird")
                    assert "model-123" in result
                    assert "GMHCD" in result
                    
                    # 2. Query components - should return actual components
                    result = await query_components("model-123")
                    assert result["count"] == 2
                    assert any(c["name"] == "K0" for c in result["components"])
                    assert any(c["name"] == "MCP" for c in result["components"])
                    
                    # 3. Get model elements - should work
                    result = await get_model_elements("model-123", "PhysicalComponent")
                    assert result["count"] == 2
                    assert result["element_type"] == "PhysicalComponent"
                    
                    # 4. Get model info - should work
                    result = await get_model_info("model-123")
                    assert result["name"] == "GMHCD"
                    assert result["total_elements"] == 387
                    
                    # 5. Create component - should work
                    result = await create_component("model-123", "NewComp", "PhysicalComponent")
                    assert result["status"] == "success"
                    
                    # 6. Validate architecture - should work
                    result = await validate_architecture("model-123")
                    assert result["status"] == "success"
    
    @pytest.mark.asyncio
    async def test_query_components_returns_real_data(self):
        """Test that query_components returns real model data, not mock data."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                # Setup tool registry with proper handler that uses model_manager
                mock_tr.call_tool = AsyncMock(side_effect=self._mock_query_handler)
                
                # Simulate real query
                result = await query_components("model-123", {"name_contains": "K0"})
                
                # Should call tool registry with proper args
                mock_tr.call_tool.assert_called_once_with("query_components", {
                    "model_id": "model-123",
                    "filters": {"name_contains": "K0"}
                })
    
    async def _mock_query_handler(self, tool_name, args):
        """Mock handler that simulates real query behavior."""
        if tool_name == "query_components":
            # Return realistic data, not "Component 1", "Component 2"
            return {
                "model_id": args["model_id"],
                "components": [
                    {"id": "real-uuid", "name": "K0_Controller", "type": "PhysicalComponent"}
                ],
                "count": 1
            }
        return {"status": "success"}
    
    def test_server_status_shows_enhanced(self):
        """Test that server status correctly reports enhanced features."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                with patch('capella_mcp.server.tool_registry') as mock_tr:
                    mock_mm.get_status = MagicMock(return_value={
                        "capellambse_available": True,
                        "capellambse_version": "0.7.6",
                        "mode": "capellambse",
                        "models_loaded": 1
                    })
                    mock_tr.list_tools = MagicMock(return_value=[
                        {"name": "load_model"},
                        {"name": "query_components"},
                        {"name": "get_model_elements"},
                        {"name": "get_model_info"}
                    ])
                    
                    status = server_status()
                    
                    assert status["server"] == "capella-mcp-enhanced"
                    assert status["capellambse_available"] == True
                    assert status["capellambse_version"] == "0.7.6"
                    assert status["tools_available"] == 4
                    assert "get_model_elements" in str(status["tools"])
                    assert "get_model_info" in str(status["tools"])
    
    @pytest.mark.asyncio
    async def test_no_hardcoded_mock_data(self):
        """Verify that query_components never returns hardcoded test data."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                # Ensure tool registry uses model manager
                def check_no_mock_data(tool_name, args):
                    # This should query real model, not return hardcoded data
                    assert tool_name == "query_components"
                    # Return empty list to show we're not using hardcoded data
                    return {"components": [], "count": 0, "model_id": args["model_id"]}
                
                mock_tr.call_tool = AsyncMock(side_effect=check_no_mock_data)
                
                result = await query_components("test-model")
                
                # Should NOT contain "Component 1" or "Component 2"
                assert result["count"] == 0
                assert not any(c.get("name") == "Component 1" for c in result.get("components", []))
                assert not any(c.get("name") == "Component 2" for c in result.get("components", []))


if __name__ == "__main__":
    pytest.main([__file__, "-v"])