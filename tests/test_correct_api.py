"""
Test the correct capellambse API patterns discovered in API introspection.
This follows TDD - write failing tests first, then fix implementation.
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import pytest
from capellambse import MelodyModel

# Model path
MODEL_PATH = "<local-path>"

class TestCorrectCapellamseAPI:
    """Test the correct capellambse API patterns discovered via introspection."""
    
    @pytest.fixture
    def model(self):
        """Load the GMHCD model for testing."""
        return MelodyModel(MODEL_PATH)
    
    def test_logical_architecture_access(self, model):
        """Test basic access to logical architecture."""
        assert hasattr(model, 'la')
        assert model.la is not None
        print(f"[OK] LogicalArchitecture type: {type(model.la)}")
        
    def test_component_pkg_collection_exists(self, model):
        """Test that component_pkg.components collection exists and has create method."""
        la = model.la
        assert hasattr(la, 'component_pkg')
        
        component_pkg = la.component_pkg
        print(f"[OK] component_pkg type: {type(component_pkg)}")
        
        assert hasattr(component_pkg, 'components')
        components = component_pkg.components
        print(f"[OK] components collection type: {type(components)}")
        print(f"[OK] Current component count: {len(components)}")
        
        # Check it has creation methods
        assert hasattr(components, 'create')
        assert hasattr(components, 'append') 
        assert hasattr(components, 'insert')
        print(f"[OK] Creation methods available: create, append, insert")
        
    def test_correct_component_creation_api(self, model):
        """Test that we can create a component using the correct API."""
        la = model.la
        initial_count = len(la.all_components)
        print(f"Initial component count: {initial_count}")
        
        # This is the CORRECT API pattern: use component_pkg.components
        new_component = la.component_pkg.components.create(name="Test_API_Discovery_Component")
        
        # Verify component was created
        assert new_component is not None
        assert hasattr(new_component, 'name')
        assert new_component.name == "Test_API_Discovery_Component"
        assert hasattr(new_component, 'uuid')
        
        # Verify it appears in both collections
        updated_count = len(la.all_components)
        pkg_count = len(la.component_pkg.components)
        assert updated_count == initial_count + 1
        
        print(f"[OK] Component created: {new_component.name} (UUID: {new_component.uuid})")
        print(f"[OK] Component count increased: {initial_count} -> {updated_count}")
        print(f"[OK] Package component count: {pkg_count}")
        
    def test_component_persistence_after_save(self, model):
        """Test that created component persists after model.save()."""
        la = model.la
        
        # Create component using correct API
        new_component = la.component_pkg.components.create(name="Test_Persistence_Component")
        created_uuid = new_component.uuid
        
        # Save model
        model.save()
        print(f"[OK] Model saved after creating component {created_uuid}")
        
        # Reload model and verify component exists
        reloaded_model = MelodyModel(MODEL_PATH)
        reloaded_components = reloaded_model.la.all_components
        
        # Find our component by UUID
        found_component = None
        for comp in reloaded_components:
            if str(comp.uuid) == str(created_uuid):
                found_component = comp
                break
                
        assert found_component is not None, f"Component {created_uuid} not found after reload"
        assert found_component.name == "Test_Persistence_Component"
        
        print(f"[OK] Component persisted successfully: {found_component.name}")
        
    def test_model_manager_should_use_correct_api(self):
        """Test what our model_manager should do (this will fail initially)."""
        # Import our current model_manager
        from capella_mcp.model_manager import CapellaModelWrapper, ModelManager
        from capellambse import MelodyModel
        
        # Load model through our manager
        model_manager = ModelManager()
        model_id = "test_model_for_api"
        
        # Create wrapper manually (bypassing async load for testing)
        model = MelodyModel(MODEL_PATH)
        wrapper = CapellaModelWrapper(model, model_id, use_capellambse=True)
        
        # Try to create component using our current (broken) API
        # This should fail with the error we saw: 
        # "'LogicalArchitecture' object has no attribute 'create_component'"
        
        with pytest.raises(Exception) as exc_info:
            wrapper.create_element("LogicalComponent", name="Test_Wrapper_Component")
            
        print(f"Expected error (current broken implementation): {exc_info.value}")
        
        # After we fix it, this test should pass:
        # component_id = wrapper.create_element("LogicalComponent", name="Test_Wrapper_Component")
        # assert component_id is not None


if __name__ == "__main__":
    # Run the tests
    pytest.main([__file__, "-v", "-s"])