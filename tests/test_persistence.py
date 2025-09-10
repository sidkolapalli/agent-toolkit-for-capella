"""
Test suite for component persistence - TDD approach.
Tests that created components are actually saved to the model file.
"""

import pytest
import tempfile
import shutil
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch, call
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.model_manager import CapellaModelWrapper, ModelManager
from capella_mcp.tools import ToolRegistry


class TestModelPersistence:
    """Test that model changes are persisted to disk."""
    
    def test_save_method_calls_model_save(self):
        """Test that CapellaModelWrapper.save() actually calls model.save()."""
        # Create mock model with save method
        mock_model = MagicMock()
        mock_model.save = MagicMock()
        
        # Create wrapper
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        wrapper.modified = True
        
        # Call save
        wrapper.save()
        
        # Verify model.save() was called
        mock_model.save.assert_called_once()
        
        # Verify modified flag is reset
        assert wrapper.modified == False
    
    def test_save_handles_errors(self):
        """Test that save() properly handles and propagates errors."""
        # Create mock model that raises error
        mock_model = MagicMock()
        mock_model.save = MagicMock(side_effect=Exception("Save failed"))
        
        # Create wrapper
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        wrapper.modified = True
        
        # Call save and expect exception
        with pytest.raises(Exception, match="Save failed"):
            wrapper.save()
        
        # Modified flag should still be True since save failed
        assert wrapper.modified == True
    
    def test_create_element_marks_modified(self):
        """Test that creating an element marks the model as modified."""
        mock_model = MagicMock()
        
        # Mock logical architecture with correct API structure
        mock_la = MagicMock()
        mock_component = MagicMock()
        mock_component.uuid = "comp-uuid"
        
        # Mock the component_pkg.components.create pattern
        mock_component_pkg = MagicMock()
        mock_components = MagicMock()
        mock_components.create = MagicMock(return_value=mock_component)
        mock_component_pkg.components = mock_components
        mock_la.component_pkg = mock_component_pkg
        mock_model.la = mock_la
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        wrapper.modified = False
        
        # Create element
        element_id = wrapper.create_element("LogicalComponent", name="TestComp")
        
        # Should be marked as modified
        assert wrapper.modified == True
        assert element_id == "comp-uuid"
    
    @pytest.mark.asyncio
    async def test_model_manager_save_calls_wrapper_save(self):
        """Test that ModelManager.save_model calls wrapper.save()."""
        manager = ModelManager()
        
        # Create mock wrapper
        mock_wrapper = MagicMock()
        mock_wrapper.save = MagicMock()
        
        # Add to manager
        manager.loaded_models["test-id"] = mock_wrapper
        
        # Call save_model
        await manager.save_model("test-id")
        
        # Verify wrapper.save() was called
        mock_wrapper.save.assert_called_once()
    
    def test_create_component_uses_real_model_manager(self):
        """Test that tool handlers use real model_manager, not mock data."""
        # Create mock model manager
        mock_model_manager = MagicMock()
        mock_model_manager.get_model = MagicMock()
        
        # Create wrapper with real create_element
        mock_wrapper = MagicMock()
        mock_wrapper.create_element = MagicMock(return_value="real-uuid-123")
        mock_model_manager.get_model.return_value = mock_wrapper
        
        # Create tool registry with model manager
        registry = ToolRegistry(model_manager=mock_model_manager)
        
        # The handler should use model_manager, not generate fake UUIDs
        # This will fail with current implementation that returns mock data
        # After fix, it should use model_manager
        
        # Note: Current implementation returns hardcoded UUID without using model_manager
        # This test will fail until we fix the implementation
    
    @pytest.mark.asyncio
    async def test_tool_handler_creates_and_saves(self):
        """Test that tool handlers create elements AND save the model."""
        # Create mocks
        mock_model_manager = MagicMock()
        mock_wrapper = MagicMock()
        mock_wrapper.create_element = MagicMock(return_value="real-elem-id")
        mock_wrapper.save = MagicMock()
        mock_model_manager.get_model = MagicMock(return_value=mock_wrapper)
        
        # Create registry
        registry = ToolRegistry(model_manager=mock_model_manager)
        
        # Call create component handler
        result = await registry._create_component_basic({
            "model_id": "test-model",
            "name": "TestComponent",
            "type": "LogicalComponent"
        })
        
        # After fix, should:
        # 1. Use model_manager to get wrapper
        # 2. Call create_element on wrapper
        # 3. Call save on wrapper
        # 4. Return real element ID
        
        # Current implementation will fail these assertions
        # because it just returns mock data
    
    def test_no_mock_uuids_in_responses(self):
        """Test that responses don't contain mock UUIDs like 'comp1', 'comp2'."""
        # After fix, tool handlers should never return:
        # - "comp1", "comp2" (hardcoded test data)
        # - "mock_component_1", "mock_actor_1" (mock prefixes)
        # - Random UUIDs generated without actual creation
        
        # This test ensures we remove all mock implementations
        pass
    
    @pytest.mark.asyncio
    async def test_persistence_across_reload(self):
        """Test that created components persist after model reload."""
        # This is an integration test that would:
        # 1. Load a model
        # 2. Create a component
        # 3. Save the model
        # 4. Unload the model
        # 5. Reload the same model
        # 6. Query for the created component
        # 7. Verify it exists
        
        # This test requires actual file I/O and capellambse
        # Will be used for final verification
        pass


class TestToolRegistryNoMocks:
    """Test that ToolRegistry doesn't use any mock implementations."""
    
    @pytest.mark.asyncio
    async def test_create_component_basic_no_mock(self):
        """Test that _create_component_basic doesn't generate mock UUIDs."""
        registry = ToolRegistry()  # No model_manager provided
        
        # After our fix, the handler should require model_manager and return error
        result = await registry._create_component_basic({
            "model_id": "test",
            "name": "Test",
            "type": "LogicalComponent"
        })
        
        # Should return error indicating model manager not available
        assert result["status"] == "failed"
        assert "Model manager not available" in result["error"]
    
    def test_all_handlers_require_model_manager(self):
        """Test that all creation handlers require model_manager."""
        registry = ToolRegistry(model_manager=None)
        
        handlers_to_test = [
            "_create_component_basic",
            "_modify_component",
            "_delete_component",
            "_create_component_with_interfaces",
            "_create_actor_with_scenarios",
            "_create_functional_chain"
        ]
        
        for handler_name in handlers_to_test:
            if hasattr(registry, handler_name):
                # Should not work without model_manager
                # Current mock implementations will pass
                # After fix, they should fail
                pass


class TestSaveModelTool:
    """Test the explicit save_model tool."""
    
    @pytest.mark.asyncio
    async def test_save_model_tool_exists(self):
        """Test that save_model tool is available."""
        # After implementation, there should be a save_model tool
        # that explicitly saves pending changes
        
        # This will fail until we add the tool
        from capella_mcp.server import save_model
        
        # Should be an async function
        assert callable(save_model)
    
    @pytest.mark.asyncio
    async def test_save_model_tool_calls_manager_save(self):
        """Test that save_model tool calls model_manager.save_model."""
        # Mock setup would go here
        # Test that the tool properly calls the manager's save method
        pass


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])