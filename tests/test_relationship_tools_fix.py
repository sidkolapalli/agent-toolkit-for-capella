"""
TDD tests for fixing the 4 failing relationship management tools.

These tests verify that the tools correctly handle:
1. Model access through wrapper.model.by_uuid() 
2. Proper error handling
3. Successful operation completion

Based on API discovery findings and error analysis.
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
    establish_element_relationship,
    create_allocation_links,
    manage_function_exchanges,
    manage_port_connections,
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

class TestEstablishElementRelationship:
    """Test fix for establish_element_relationship tool."""
    
    async def test_functional_exchange_creation_with_valid_functions(self, loaded_model):
        """Test that establish_element_relationship can create FunctionalExchange."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get some functions from the model for testing
        functions = []
        if hasattr(model, 'la') and hasattr(model.la, 'all_functions'):
            functions = list(model.la.all_functions)[:2]
        
        if len(functions) < 2:
            pytest.skip("Need at least 2 functions for exchange creation test")
        
        source_func = functions[0]
        target_func = functions[1]
        
        relationship_spec = {
            "source_element_id": source_func.uuid,
            "target_element_id": target_func.uuid,
            "relationship_type": "FunctionalExchange",
            "properties": {
                "name": "TestFunctionalExchange",
                "description": "Test functional exchange"
            }
        }
        
        # This should NOT fail with "CapellaModelWrapper has no attribute by_uuid"
        result = await establish_element_relationship(
            model_id=TEST_MODEL_ID,
            relationship_spec=relationship_spec
        )
        
        # Verify the result indicates proper model access
        assert "success" in result
        if result["success"]:
            assert "relationship_id" in result
            assert result["relationship_type"] == "FunctionalExchange"
        else:
            # If it fails, it should NOT be due to wrapper.by_uuid error
            assert "has no attribute 'by_uuid'" not in result.get("error", "")
    
    async def test_model_access_pattern(self, loaded_model):
        """Test that the tool uses correct model access pattern."""
        wrapper = loaded_model
        
        # Test with invalid UUIDs to verify error handling
        relationship_spec = {
            "source_element_id": "invalid-uuid-1",
            "target_element_id": "invalid-uuid-2", 
            "relationship_type": "FunctionalExchange",
            "properties": {"name": "TestExchange"}
        }
        
        result = await establish_element_relationship(
            model_id=TEST_MODEL_ID,
            relationship_spec=relationship_spec
        )
        
        # Should fail gracefully, NOT with wrapper.by_uuid error
        assert "success" in result
        if not result["success"]:
            error_msg = result.get("error", "")
            assert "has no attribute 'by_uuid'" not in error_msg
            # Should indicate elements not found instead
            assert "not found" in error_msg.lower() or "not accessible" in error_msg.lower()

class TestCreateAllocationLinks:
    """Test fix for create_allocation_links tool."""
    
    async def test_allocation_with_valid_elements(self, loaded_model):
        """Test allocation creation with proper model access."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get function and component for testing
        functions = []
        components = []
        
        if hasattr(model, 'la'):
            if hasattr(model.la, 'all_functions'):
                functions = list(model.la.all_functions)[:1]
            if hasattr(model.la, 'all_components'):
                components = list(model.la.all_components)[:1]
        
        if not functions or not components:
            pytest.skip("Need at least 1 function and 1 component for allocation test")
        
        allocation_spec = {
            "function_id": functions[0].uuid,
            "component_id": components[0].uuid,
            "allocation_type": "realizes"
        }
        
        # This should NOT fail with "CapellaModelWrapper has no attribute by_uuid"
        result = await create_allocation_links(
            model_id=TEST_MODEL_ID,
            operation="allocate",
            allocation_spec=allocation_spec
        )
        
        assert "success" in result
        if result["success"]:
            assert result["operation"] == "allocate"
            assert "allocation_id" in result
        else:
            # Should NOT be wrapper.by_uuid error
            assert "has no attribute 'by_uuid'" not in result.get("error", "")
    
    async def test_model_wrapper_error_fix(self, loaded_model):
        """Verify the specific wrapper.by_uuid error is fixed."""
        
        allocation_spec = {
            "function_id": "invalid-function-id",
            "component_id": "invalid-component-id"
        }
        
        result = await create_allocation_links(
            model_id=TEST_MODEL_ID,
            operation="allocate", 
            allocation_spec=allocation_spec
        )
        
        # Should fail gracefully, but NOT with the wrapper.by_uuid error
        assert "success" in result
        if not result["success"]:
            error_msg = result.get("error", "")
            assert "has no attribute 'by_uuid'" not in error_msg

class TestManageFunctionExchanges:
    """Test fix for manage_function_exchanges tool."""
    
    async def test_exchange_creation_with_model_access_fix(self, loaded_model):
        """Test function exchange creation with fixed model access."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get functions for testing
        functions = []
        if hasattr(model, 'la') and hasattr(model.la, 'all_functions'):
            functions = list(model.la.all_functions)[:2]
        
        if len(functions) < 2:
            pytest.skip("Need at least 2 functions for exchange test")
        
        exchange_spec = {
            "name": "TestFunctionExchange",
            "source_function_id": functions[0].uuid,
            "target_function_id": functions[1].uuid,
            "description": "Test exchange"
        }
        
        # This should NOT fail with wrapper.by_uuid error
        result = await manage_function_exchanges(
            model_id=TEST_MODEL_ID,
            operation="create",
            exchange_spec=exchange_spec
        )
        
        assert "success" in result
        if result["success"]:
            assert result["operation"] == "create"
            assert "exchange_id" in result
        else:
            # Should NOT be the wrapper.by_uuid error
            assert "has no attribute 'by_uuid'" not in result.get("error", "")
    
    async def test_list_exchanges_model_access(self, loaded_model):
        """Test exchange listing with corrected model access."""
        
        result = await manage_function_exchanges(
            model_id=TEST_MODEL_ID,
            operation="list"
        )
        
        # Should succeed without wrapper errors
        assert "success" in result
        if result["success"]:
            assert "exchanges" in result
            assert "total_count" in result
        else:
            # Should NOT be wrapper access error
            error_msg = result.get("error", "")
            assert "has no attribute 'by_uuid'" not in error_msg

class TestManagePortConnections:
    """Test fix for manage_port_connections tool."""
    
    async def test_port_creation_required_fields(self, loaded_model):
        """Test that port creation validates required fields correctly."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get a component for testing
        components = []
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)[:1]
        
        if not components:
            pytest.skip("Need at least 1 component for port creation test")
        
        # Test with missing required fields (name, direction)
        port_spec = {
            "component_id": components[0].uuid
            # Missing "name" and "direction" - should trigger validation error
        }
        
        result = await manage_port_connections(
            model_id=TEST_MODEL_ID,
            operation="create",
            port_spec=port_spec
        )
        
        # Should fail due to missing required fields
        assert "success" in result
        assert not result["success"]
        error_msg = result.get("error", "")
        assert "Missing required fields" in error_msg
        assert "name" in error_msg and "direction" in error_msg
    
    async def test_port_creation_with_valid_spec(self, loaded_model):
        """Test port creation with complete specification."""
        wrapper = loaded_model
        model = wrapper.model
        
        # Get a component for testing
        components = []
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)[:1]
        
        if not components:
            pytest.skip("Need at least 1 component for port creation test")
        
        port_spec = {
            "component_id": components[0].uuid,
            "name": "TestPort",
            "direction": "IN"
        }
        
        # This should NOT fail with wrapper.by_uuid error
        result = await manage_port_connections(
            model_id=TEST_MODEL_ID,
            operation="create", 
            port_spec=port_spec
        )
        
        assert "success" in result
        if result["success"]:
            assert "port_id" in result
            assert result["port_name"] == "TestPort" 
            assert result["port_direction"] == "IN"
        else:
            # Should NOT be wrapper access error
            error_msg = result.get("error", "")
            assert "has no attribute 'by_uuid'" not in error_msg
    
    async def test_connection_model_access_fix(self, loaded_model):
        """Test connection operations use correct model access."""
        
        connection_spec = {
            "source_port_id": "invalid-port-1",
            "target_port_id": "invalid-port-2"
        }
        
        result = await manage_port_connections(
            model_id=TEST_MODEL_ID,
            operation="connect",
            connection_spec=connection_spec
        )
        
        # Should fail gracefully, NOT with wrapper.by_uuid error  
        assert "success" in result
        if not result["success"]:
            error_msg = result.get("error", "")
            assert "has no attribute 'by_uuid'" not in error_msg

# Performance regression test
class TestPerformanceRegression:
    """Ensure fixes don't introduce performance regressions."""
    
    async def test_all_tools_complete_quickly(self, loaded_model):
        """All tools should complete within reasonable time."""
        
        start_time = time.time()
        
        # Test all 4 tools with minimal specs
        tasks = [
            establish_element_relationship(
                TEST_MODEL_ID,
                {"source_element_id": "test", "target_element_id": "test", 
                 "relationship_type": "FunctionalExchange", "properties": {}}
            ),
            create_allocation_links(
                TEST_MODEL_ID, "list"
            ),
            manage_function_exchanges(
                TEST_MODEL_ID, "list"  
            ),
            manage_port_connections(
                TEST_MODEL_ID, "list"
            )
        ]
        
        results = await asyncio.gather(*tasks, return_exceptions=True)
        
        execution_time = time.time() - start_time
        
        # All tools should complete within 5 seconds combined
        assert execution_time < 5.0, f"Tools took {execution_time:.2f}s - too slow!"
        
        # All should return dict results (not exceptions)
        for result in results:
            assert isinstance(result, dict), f"Tool returned exception: {result}"

if __name__ == "__main__":
    # Run the tests
    pytest.main([__file__, "-v"])