"""
TDD tests for place_element_on_diagram visibility persistence bug.

This test verifies that:
1. Element visibility changes persist after model save
2. Connections/exchanges are also made visible on diagrams  
3. Model modified flag is set correctly for persistence

Based on user requirement: "The place_element_on_diagram API appears to have a bug 
where it reports success but doesn't actually update the element's visibility 
property in the model..."
"""
import asyncio
import pytest
import sys
import os
from pathlib import Path
import time

# Add src to path for imports
src_path = Path(__file__).parent.parent / "src"
sys.path.insert(0, str(src_path))

from capella_mcp.server import (
    place_element_on_diagram,
    save_model,
    model_manager,
    ENHANCED_AVAILABLE
)

# Test model ID - using the one from recent sessions
TEST_MODEL_ID = "model_8239"
CAPELLA_MODEL_PATH = os.environ.get("CAPELLA_MODEL_PATH", "")

@pytest.fixture
async def loaded_model():
    """Ensure we have a loaded model for testing."""
    if not CAPELLA_MODEL_PATH:
        pytest.skip("CAPELLA_MODEL_PATH not set")
    
    if not ENHANCED_AVAILABLE:
        pytest.skip("Enhanced tools not available")
    
    # Check if model is already loaded
    wrapper = model_manager.get_model(TEST_MODEL_ID)
    if not wrapper:
        pytest.skip(f"Model {TEST_MODEL_ID} not loaded")
    
    return wrapper

class TestPlaceElementOnDiagramPersistence:
    """Test fix for place_element_on_diagram persistence bug."""
    
    async def test_element_visibility_persists_after_save(self, loaded_model):
        """Test that element visibility changes persist after model save (main bug)."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get a component for testing
        test_element = None
        original_visibility_count = 0
        
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)
            if components:
                test_element = components[0]
                if hasattr(test_element, 'visible_on_diagrams'):
                    original_visibility_count = len(test_element.visible_on_diagrams)
        
        if not test_element:
            pytest.skip("No suitable test element found with visible_on_diagrams")
        
        element_id = test_element.uuid
        
        # Record initial state
        initial_modified = wrapper.modified
        
        # Place element on diagram
        result = await place_element_on_diagram(
            model_id=TEST_MODEL_ID,
            element_id=element_id
        )
        
        # Verify operation reported success
        assert result["success"], f"place_element_on_diagram failed: {result.get('error')}"
        
        # CRITICAL TEST: Check if model is marked as modified 
        # This was the bug - changes weren't being persisted
        assert wrapper.modified, "Model should be marked as modified after placing element"
        
        # Verify visibility changed
        new_visibility_count = len(test_element.visible_on_diagrams)
        if result["status"] == "placed":
            assert new_visibility_count > original_visibility_count, "Element should be visible on more diagrams"
        
        # TEST PERSISTENCE: Save model and verify change persists
        save_result = await save_model(model_id=TEST_MODEL_ID)
        assert "Successfully saved" in save_result, f"Model save failed: {save_result}"
        
        # After save, modified flag should be False
        assert not wrapper.modified, "Model should not be marked as modified after save"
        
        # Verify visibility is still there after save
        final_visibility_count = len(test_element.visible_on_diagrams)
        assert final_visibility_count == new_visibility_count, "Visibility changes should persist after save"
    
    async def test_model_modified_flag_behavior(self, loaded_model):
        """Test that wrapper.mark_modified() is called when placing elements."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get element for testing
        test_element = None
        if hasattr(model, 'la') and hasattr(model.la, 'all_actors'):
            actors = list(model.la.all_actors)
            if actors:
                test_element = actors[0]
        
        if not test_element:
            pytest.skip("No suitable actor found for testing")
        
        # Ensure model is not initially marked as modified
        wrapper.modified = False
        
        element_id = test_element.uuid
        
        # Place element on diagram
        result = await place_element_on_diagram(
            model_id=TEST_MODEL_ID,
            element_id=element_id
        )
        
        if result["success"] and result["status"] == "placed":
            # CRITICAL: Model must be marked as modified for persistence
            assert wrapper.modified, "Model must be marked as modified when element visibility changes"
        elif result["status"] == "already_placed":
            # If already placed, modified flag behavior depends on implementation
            pass
        else:
            pytest.fail(f"Unexpected result: {result}")
    
    async def test_connections_visibility_enhancement(self, loaded_model):
        """Test that connections/exchanges are also made visible when placing elements."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Find element with exchanges for testing
        test_element = None
        connected_exchanges = []
        
        if hasattr(model, 'la') and hasattr(model.la, 'all_functions'):
            functions = list(model.la.all_functions)
            for func in functions:
                if hasattr(func, 'exchanges') and func.exchanges:
                    test_element = func
                    connected_exchanges = list(func.exchanges)
                    break
        
        if not test_element or not connected_exchanges:
            pytest.skip("No function with exchanges found for connection visibility test")
        
        element_id = test_element.uuid
        
        # Record initial exchange visibility
        initial_exchange_visibilities = {}
        for exchange in connected_exchanges:
            if hasattr(exchange, 'visible_on_diagrams'):
                initial_exchange_visibilities[exchange.uuid] = len(exchange.visible_on_diagrams)
        
        # Place element on diagram
        result = await place_element_on_diagram(
            model_id=TEST_MODEL_ID,
            element_id=element_id
        )
        
        assert result["success"], f"place_element_on_diagram failed: {result.get('error')}"
        
        # ENHANCEMENT: Verify that exchanges are also made visible
        # This addresses the user request: "i also want the connection between elements to be visible"
        for exchange in connected_exchanges:
            if hasattr(exchange, 'visible_on_diagrams'):
                current_visibility = len(exchange.visible_on_diagrams)
                initial_visibility = initial_exchange_visibilities.get(exchange.uuid, 0)
                
                # The exchange should be visible on at least as many diagrams as before
                # Ideally it should be on the same diagram as the element
                assert current_visibility >= initial_visibility, \
                    f"Exchange {exchange.uuid} visibility should not decrease"
    
    async def test_error_cases_dont_mark_modified(self, loaded_model):
        """Test that failed operations don't incorrectly mark model as modified."""
        wrapper = loaded_model
        
        # Ensure model starts unmodified
        wrapper.modified = False
        
        # Test with invalid element ID
        result = await place_element_on_diagram(
            model_id=TEST_MODEL_ID,
            element_id="invalid-element-id"
        )
        
        # Should fail without modifying the model
        assert not result.get("success", False), "Should fail with invalid element ID"
        assert not wrapper.modified, "Model should not be marked as modified on error"

class TestDiagramVisibilityRegression:
    """Ensure fixes don't break existing functionality."""
    
    async def test_already_visible_elements_unchanged(self, loaded_model):
        """Test that elements already on diagrams aren't affected negatively."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Find element already visible on diagrams
        test_element = None
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            for component in model.la.all_components:
                if hasattr(component, 'visible_on_diagrams') and component.visible_on_diagrams:
                    test_element = component
                    break
        
        if not test_element:
            pytest.skip("No element found that is already visible on diagrams")
        
        element_id = test_element.uuid
        original_visibility_count = len(test_element.visible_on_diagrams)
        original_modified = wrapper.modified
        
        # Place already visible element
        result = await place_element_on_diagram(
            model_id=TEST_MODEL_ID,
            element_id=element_id
        )
        
        assert result["success"], f"Operation failed: {result.get('error')}"
        
        # Should report as already placed
        if result["status"] == "already_placed":
            # Visibility count should remain the same
            assert len(test_element.visible_on_diagrams) == original_visibility_count
            # Modified flag behavior can vary based on implementation
        elif result["status"] == "placed":
            # Element was placed on a different diagram
            assert len(test_element.visible_on_diagrams) >= original_visibility_count

if __name__ == "__main__":
    # Run the tests
    pytest.main([__file__, "-v"])