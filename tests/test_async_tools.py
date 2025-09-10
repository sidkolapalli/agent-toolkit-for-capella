"""
Test suite for async tools - verifies event loop conflict resolution.
"""

import pytest
import asyncio
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.server import (
    load_model,
    list_models,
    close_model,
    create_component_with_interfaces,
    create_component,
    query_components,
    get_model_elements,
    get_model_info,
    validate_architecture,
    analyze_dependencies
)


class TestAsyncTools:
    """Test that all tools are properly async and don't cause event loop conflicts."""
    
    @pytest.mark.asyncio
    async def test_load_model_async(self):
        """Test that load_model is async and can be awaited."""
        # Mock the model_manager
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                mock_mm.load_model = AsyncMock(return_value="model_123")
                mock_mm.get_model = MagicMock(return_value=MagicMock(
                    get_model_info=MagicMock(return_value={"name": "TestModel"})
                ))
                mock_mm.get_status = MagicMock(return_value={"mode": "capellambse"})
                
                # This should work without event loop conflicts
                result = await load_model("test.aird")
                
                assert "model_123" in result
                assert "TestModel" in result
                mock_mm.load_model.assert_called_once_with("test.aird")
    
    @pytest.mark.asyncio
    async def test_list_models_sync(self):
        """Test that list_models works (it's still sync)."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                mock_mm.list_loaded_models = MagicMock(return_value=[])
                mock_mm.get_status = MagicMock(return_value={"mode": "test"})
                
                # list_models is still sync
                result = list_models()
                
                assert result["count"] == 0
                assert "status" in result
    
    @pytest.mark.asyncio
    async def test_close_model_async(self):
        """Test that close_model is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                mock_mm.unload_model = AsyncMock()
                
                result = await close_model("model_123")
                
                assert "Successfully closed" in result
                mock_mm.unload_model.assert_called_once_with("model_123")
    
    @pytest.mark.asyncio
    async def test_create_component_with_interfaces_async(self):
        """Test that create_component_with_interfaces is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                mock_tr.call_tool = AsyncMock(return_value={
                    "status": "success",
                    "component_id": "comp_123"
                })
                
                spec = {"name": "TestComponent", "component_type": "LogicalComponent"}
                result = await create_component_with_interfaces("model_123", spec)
                
                assert result["status"] == "success"
                assert result["component_id"] == "comp_123"
    
    @pytest.mark.asyncio
    async def test_create_component_async(self):
        """Test that create_component is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                mock_tr.call_tool = AsyncMock(return_value={
                    "component_id": "comp_456",
                    "name": "TestComp"
                })
                
                result = await create_component("model_123", "TestComp", "LogicalComponent")
                
                assert result["component_id"] == "comp_456"
    
    @pytest.mark.asyncio
    async def test_query_components_async(self):
        """Test that query_components is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                mock_tr.call_tool = AsyncMock(return_value={
                    "components": [],
                    "count": 0
                })
                
                result = await query_components("model_123")
                
                assert result["count"] == 0
                assert "components" in result
    
    @pytest.mark.asyncio
    async def test_get_model_elements_async(self):
        """Test that get_model_elements is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                mock_wrapper = MagicMock()
                mock_wrapper.query_elements = MagicMock(return_value=[])
                mock_mm.get_model = MagicMock(return_value=mock_wrapper)
                
                result = await get_model_elements("model_123", "LogicalComponent")
                
                assert result["count"] == 0
                assert result["element_type"] == "LogicalComponent"
    
    @pytest.mark.asyncio
    async def test_get_model_info_async(self):
        """Test that get_model_info is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                mock_wrapper = MagicMock()
                mock_wrapper.get_model_info = MagicMock(return_value={
                    "name": "TestModel",
                    "uuid": "123-456"
                })
                mock_mm.get_model = MagicMock(return_value=mock_wrapper)
                
                result = await get_model_info("model_123")
                
                assert result["name"] == "TestModel"
                assert result["uuid"] == "123-456"
    
    @pytest.mark.asyncio
    async def test_validate_architecture_async(self):
        """Test that validate_architecture is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                mock_tr.call_tool = AsyncMock(return_value={
                    "valid": True,
                    "issues": []
                })
                
                result = await validate_architecture("model_123")
                
                assert result["valid"] == True
                assert len(result["issues"]) == 0
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_async(self):
        """Test that analyze_dependencies is async."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.tool_registry') as mock_tr:
                mock_tr.call_tool = AsyncMock(return_value={
                    "element_id": "elem_123",
                    "impact_score": 5
                })
                
                result = await analyze_dependencies("model_123", "elem_123")
                
                assert result["element_id"] == "elem_123"
                assert result["impact_score"] == 5
    
    @pytest.mark.asyncio
    async def test_no_event_loop_conflict(self):
        """Test that we can call multiple async tools without event loop conflicts."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            with patch('capella_mcp.server.model_manager') as mock_mm:
                with patch('capella_mcp.server.tool_registry') as mock_tr:
                    # Setup mocks
                    mock_mm.load_model = AsyncMock(return_value="model_123")
                    mock_mm.get_model = MagicMock(return_value=MagicMock(
                        get_model_info=MagicMock(return_value={"name": "Test"}),
                        query_elements=MagicMock(return_value=[])
                    ))
                    mock_mm.get_status = MagicMock(return_value={"mode": "test"})
                    mock_mm.unload_model = AsyncMock()
                    mock_tr.call_tool = AsyncMock(return_value={"status": "success"})
                    
                    # Run multiple async operations - should not conflict
                    results = await asyncio.gather(
                        load_model("test1.aird"),
                        create_component("model_123", "Comp1", "LogicalComponent"),
                        query_components("model_123"),
                        get_model_elements("model_123"),
                        validate_architecture("model_123")
                    )
                    
                    # All should succeed
                    assert len(results) == 5
                    assert all(r is not None for r in results)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])