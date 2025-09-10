"""
TDD Research Test for visible_on_diagrams API behavior.

This test investigates the actual capellambse API patterns for diagram visibility
to fix the place_element_on_diagram persistence issue.

User requirement: "still not working the components are still not visible. investige fully"
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

# Use GMHCD model as requested
TEST_MODEL_ID = "model_3397"  # GMHCD model ID from previous sessions
CAPELLA_MODEL_PATH = os.environ.get("CAPELLA_MODEL_PATH", "")

@pytest.fixture
async def loaded_gmhcd_model():
    """Ensure we have the GMHCD model loaded for testing."""
    if not CAPELLA_MODEL_PATH:
        pytest.skip("CAPELLA_MODEL_PATH not set")
    
    if not ENHANCED_AVAILABLE:
        pytest.skip("Enhanced tools not available")
    
    # Check if GMHCD model is loaded
    wrapper = model_manager.get_model(TEST_MODEL_ID)
    if not wrapper:
        pytest.skip(f"GMHCD model {TEST_MODEL_ID} not loaded")
    
    return wrapper

class TestVisibilityAPIResearch:
    """Research the actual capellambse API for diagram visibility."""
    
    async def test_visible_on_diagrams_property_type(self, loaded_gmhcd_model):
        """Research: What type is visible_on_diagrams property?"""
        wrapper = loaded_gmhcd_model
        model = wrapper.model
        
        # Get a component to analyze
        test_element = None
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)
            if components:
                test_element = components[0]
        
        assert test_element is not None, "Need at least one component for research"
        
        print(f"\\n=== VISIBILITY API RESEARCH ===")
        print(f"Element: {test_element.name} ({test_element.uuid})")
        print(f"Element type: {type(test_element)}")
        
        # Research visible_on_diagrams property
        if hasattr(test_element, 'visible_on_diagrams'):
            visible_prop = test_element.visible_on_diagrams
            print(f"visible_on_diagrams type: {type(visible_prop)}")
            print(f"visible_on_diagrams value: {visible_prop}")
            print(f"visible_on_diagrams length: {len(visible_prop) if hasattr(visible_prop, '__len__') else 'No len()'}")
            
            # Check if it's mutable
            try:
                original_length = len(visible_prop) if hasattr(visible_prop, '__len__') else 0
                print(f"Original visibility count: {original_length}")
                
                # Try to understand the structure
                if len(visible_prop) > 0:
                    first_diagram = visible_prop[0]
                    print(f"First visible diagram type: {type(first_diagram)}")
                    print(f"First visible diagram: {first_diagram}")
                    
                print("visible_on_diagrams is accessible and appears to be a list-like object")
                
            except Exception as e:
                print(f"Error accessing visible_on_diagrams: {e}")
        else:
            print("Element does not have visible_on_diagrams attribute")
        
        # Research diagrams property
        print(f"\\n=== DIAGRAMS RESEARCH ===")
        if hasattr(model, 'diagrams'):
            diagrams = model.diagrams
            print(f"model.diagrams type: {type(diagrams)}")
            print(f"model.diagrams length: {len(diagrams) if hasattr(diagrams, '__len__') else 'No len()'}")
            
            if hasattr(diagrams, '__len__') and len(diagrams) > 0:
                first_diagram = diagrams[0]
                print(f"First diagram type: {type(first_diagram)}")
                print(f"First diagram: {first_diagram}")
                
        # Research layer-specific diagrams
        if hasattr(model, 'la') and hasattr(model.la, 'diagrams'):
            la_diagrams = model.la.diagrams
            print(f"model.la.diagrams type: {type(la_diagrams)}")
            print(f"model.la.diagrams length: {len(la_diagrams) if hasattr(la_diagrams, '__len__') else 'No len()'}")
    
    async def test_diagram_visibility_mutation(self, loaded_gmhcd_model):
        """Research: Can we directly mutate visible_on_diagrams?"""
        wrapper = loaded_gmhcd_model
        model = wrapper.model
        
        # Get element and diagram for testing
        test_element = None
        target_diagram = None
        
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)
            if components:
                test_element = components[0]
        
        if hasattr(model, 'la') and hasattr(model.la, 'diagrams') and len(model.la.diagrams) > 0:
            target_diagram = model.la.diagrams[0]
        elif hasattr(model, 'diagrams') and len(model.diagrams) > 0:
            target_diagram = model.diagrams[0]
        
        assert test_element is not None, "Need test element"
        assert target_diagram is not None, "Need target diagram"
        
        print(f"\\n=== MUTATION TEST ===")
        print(f"Test element: {test_element.name}")
        print(f"Target diagram: {target_diagram}")
        
        # Record initial state
        original_visibility = list(test_element.visible_on_diagrams) if hasattr(test_element.visible_on_diagrams, '__iter__') else []
        original_count = len(original_visibility)
        original_modified = wrapper.modified
        
        print(f"Initial visibility count: {original_count}")
        print(f"Initial modified flag: {original_modified}")
        
        # Try direct mutation approach (current implementation)
        try:
            print("\\nTesting direct mutation (current implementation)...")
            
            # Check if diagram is already there
            if target_diagram not in test_element.visible_on_diagrams:
                print("Diagram not currently visible, attempting to add...")
                test_element.visible_on_diagrams.append(target_diagram)
                print("Append operation completed")
                
                # Check immediate result
                new_count = len(test_element.visible_on_diagrams)
                print(f"New visibility count: {new_count}")
                
                if new_count > original_count:
                    print("✅ Direct mutation appears to work")
                    
                    # Mark modified and save
                    wrapper.modified = True
                    print("Set wrapper.modified = True")
                    
                    # Test save
                    print("Testing save operation...")
                    wrapper.save()
                    print("Save completed")
                    
                    # Verify after save
                    final_count = len(test_element.visible_on_diagrams)
                    print(f"Post-save visibility count: {final_count}")
                    
                    if final_count == new_count:
                        print("✅ Changes persisted after save")
                    else:
                        print("❌ Changes lost after save")
                        
                else:
                    print("❌ Direct mutation didn't increase count")
            else:
                print("Diagram already visible on element")
                
        except Exception as e:
            print(f"❌ Error during mutation test: {e}")
            import traceback
            traceback.print_exc()
    
    async def test_alternative_visibility_methods(self, loaded_gmhcd_model):
        """Research: Are there alternative methods for setting visibility?"""
        wrapper = loaded_gmhcd_model
        model = wrapper.model
        
        # Get element for testing
        test_element = None
        if hasattr(model, 'la') and hasattr(model.la, 'all_components'):
            components = list(model.la.all_components)
            if components:
                test_element = components[0]
        
        assert test_element is not None, "Need test element"
        
        print(f"\\n=== ALTERNATIVE METHODS RESEARCH ===")
        print(f"Element: {test_element.name}")
        
        # Check for visibility-related methods
        visibility_methods = []
        for attr_name in dir(test_element):
            if 'visible' in attr_name.lower() or 'diagram' in attr_name.lower():
                attr = getattr(test_element, attr_name)
                if callable(attr):
                    visibility_methods.append((attr_name, attr))
        
        print(f"Found {len(visibility_methods)} visibility/diagram-related methods:")
        for method_name, method in visibility_methods:
            try:
                # Try to get method signature
                import inspect
                sig = inspect.signature(method)
                print(f"  {method_name}{sig}")
            except Exception:
                print(f"  {method_name}()")
        
        # Check diagram methods
        if hasattr(model, 'diagrams'):
            diagrams = model.diagrams
            print(f"\\nDiagram object methods:")
            diagram_methods = []
            for attr_name in dir(diagrams):
                if not attr_name.startswith('_'):
                    attr = getattr(diagrams, attr_name)
                    if callable(attr):
                        diagram_methods.append(attr_name)
            
            for method in diagram_methods[:10]:  # Show first 10
                print(f"  {method}")
            if len(diagram_methods) > 10:
                print(f"  ... and {len(diagram_methods) - 10} more")

if __name__ == "__main__":
    # Run the research tests
    pytest.main([__file__, "-v", "-s"])