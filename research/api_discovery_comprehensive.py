#!/usr/bin/env python3
"""
Comprehensive API Discovery for Capellambse Model Interface
This script investigates the actual capellambse model interface to understand
correct patterns for UUID-based element access and reference finding.
"""

import asyncio
import sys
import json
from pathlib import Path
from typing import Any, Dict, List

# Add src to path for imports
sys.path.insert(0, 'src')

async def discover_capellambse_api():
    """Comprehensive API discovery for capellambse model interface"""
    
    print("=== CAPELLAMBSE API DISCOVERY ===")
    
    try:
        # Import MCP tools
        from capella_mcp.server import (
            load_model, 
            search_elements_advanced,
            ENHANCED_AVAILABLE,
            model_manager
        )
        
        print(f"Enhanced tools available: {ENHANCED_AVAILABLE}")
        
        # Load model through MCP system
        model_path = '<local-path>'
        load_result = await load_model(model_path)
        print(f"Model loaded: {load_result}")
        
        if isinstance(load_result, str) and "Successfully loaded" in load_result:
            # Extract model ID from result string
            # Format: "Successfully loaded model 'GMHCD' with ID: model_XXXX"
            # Handle potential multi-line output by taking only the model ID part
            id_part = load_result.split("ID: ")[1].strip()
            model_id = id_part.split('\n')[0].strip()  # Take only first line if multi-line
            print(f"Extracted model_id: {model_id}")
            
            # Use working search to get sample elements
            search_result = await search_elements_advanced(
                model_id=model_id,
                search_spec={'element_types': ['Entity'], 'layer': 'all'}
            )
            
            if search_result.get('success'):
                entities = search_result.get('elements', [])
                print(f"Found {len(entities)} entities for testing")
                
                if entities:
                    test_element = entities[0]
                    test_uuid = test_element['id']
                    test_name = test_element['name']
                    print(f"Using test element: {test_name} (UUID: {test_uuid})")
                    
                    # Now access the model directly for API discovery
                    discovery_results = {}
                    
                    if ENHANCED_AVAILABLE and model_manager:
                        wrapper = model_manager.get_model(model_id)
                        if wrapper:
                            model = wrapper.model
                            
                            # Discover model interface (safely)
                            discovery_results['model_type'] = str(type(model))
                            
                            # Safely get methods without triggering viewpoint errors
                            safe_methods = []
                            for attr in dir(model):
                                if not attr.startswith('_'):
                                    try:
                                        attr_val = getattr(model, attr, None)
                                        if callable(attr_val):
                                            safe_methods.append(attr)
                                    except (RuntimeError, Exception) as e:
                                        if "viewpoint" in str(e).lower():
                                            # Skip viewpoint-related attributes
                                            continue
                                        else:
                                            # Log other errors but continue
                                            print(f"Warning: Could not access {attr}: {e}")
                            
                            discovery_results['model_methods'] = safe_methods
                            
                            # Categorize methods
                            discovery_results['uuid_methods'] = [
                                m for m in discovery_results['model_methods'] if 'uuid' in m.lower()
                            ]
                            discovery_results['find_methods'] = [
                                m for m in discovery_results['model_methods'] if 'find' in m.lower()
                            ]
                            discovery_results['search_methods'] = [
                                m for m in discovery_results['model_methods'] if 'search' in m.lower()
                            ]
                            discovery_results['reference_methods'] = [
                                m for m in discovery_results['model_methods'] if 'ref' in m.lower()
                            ]
                            
                            print(f"Model type: {discovery_results['model_type']}")
                            print(f"UUID-related methods: {discovery_results['uuid_methods']}")
                            print(f"Find-related methods: {discovery_results['find_methods']}")
                            print(f"Search-related methods: {discovery_results['search_methods']}")
                            print(f"Reference-related methods: {discovery_results['reference_methods']}")
                            
                            # Test specific methods
                            print("\n=== TESTING SPECIFIC METHODS ===")
                            
                            # Test by_uuid if it exists
                            if hasattr(model, 'by_uuid'):
                                try:
                                    element = model.by_uuid(test_uuid)
                                    print(f"PASS model.by_uuid() works: {type(element)}")
                                    discovery_results['by_uuid_works'] = True
                                    discovery_results['element_type'] = str(type(element))
                                    
                                    # Examine element interface
                                    element_attrs = [attr for attr in dir(element) if not attr.startswith('_')]
                                    discovery_results['element_attributes'] = element_attrs[:20]  # First 20
                                    print(f"Element attributes (first 20): {element_attrs[:20]}")
                                    
                                except Exception as e:
                                    print(f"FAIL model.by_uuid() failed: {e}")
                                    discovery_results['by_uuid_works'] = False
                                    discovery_results['by_uuid_error'] = str(e)
                            else:
                                print("FAIL model.by_uuid() not found")
                                discovery_results['by_uuid_works'] = False
                                
                            # Test find_references if it exists
                            if hasattr(model, 'find_references'):
                                try:
                                    # First get an element to test references
                                    if hasattr(model, 'by_uuid'):
                                        element = model.by_uuid(test_uuid)
                                        refs = model.find_references(element)
                                        print(f"PASS model.find_references() works: {type(refs)}, count: {len(refs) if hasattr(refs, '__len__') else 'N/A'}")
                                        discovery_results['find_references_works'] = True
                                        discovery_results['references_type'] = str(type(refs))
                                    else:
                                        print("FAIL Cannot test find_references without by_uuid")
                                        discovery_results['find_references_works'] = False
                                except Exception as e:
                                    print(f"FAIL model.find_references() failed: {e}")
                                    discovery_results['find_references_works'] = False
                                    discovery_results['find_references_error'] = str(e)
                            else:
                                print("FAIL model.find_references() not found")
                                discovery_results['find_references_works'] = False
                            
                            # Test wrapper's get_element method (this should work based on model_manager code)
                            print("\n=== TESTING WRAPPER METHODS ===")
                            try:
                                element = wrapper.get_element(test_uuid)
                                print(f"PASS wrapper.get_element() works: {type(element)}")
                                discovery_results['wrapper_get_element_works'] = True
                                
                                if element:
                                    # Test element properties and methods
                                    element_methods = [attr for attr in dir(element) if not attr.startswith('_')]
                                    discovery_results['element_methods'] = element_methods[:30]
                                    print(f"Element methods (first 30): {element_methods[:30]}")
                                    
                                    # Check for UUID property
                                    if hasattr(element, 'uuid'):
                                        print(f"PASS Element has uuid property: {element.uuid}")
                                        discovery_results['element_has_uuid'] = True
                                    else:
                                        print("FAIL Element missing uuid property")
                                        discovery_results['element_has_uuid'] = False
                                        
                            except Exception as e:
                                print(f"FAIL wrapper.get_element() failed: {e}")
                                discovery_results['wrapper_get_element_works'] = False
                                discovery_results['wrapper_get_element_error'] = str(e)
                            
                            # Test model hierarchy access patterns from Context7 docs
                            print("\n=== TESTING MODEL HIERARCHY PATTERNS ===")
                            
                            # Test if model has layer attributes (oa, sa, la, pa)
                            layer_attrs = ['oa', 'sa', 'la', 'pa']
                            discovery_results['layer_attributes'] = {}
                            for layer in layer_attrs:
                                if hasattr(model, layer):
                                    layer_obj = getattr(model, layer)
                                    discovery_results['layer_attributes'][layer] = {
                                        'exists': True,
                                        'type': str(type(layer_obj)),
                                        'methods': [attr for attr in dir(layer_obj) if not attr.startswith('_') and callable(getattr(layer_obj, attr, None))][:10]
                                    }
                                    print(f"PASS model.{layer} exists: {type(layer_obj)}")
                                else:
                                    discovery_results['layer_attributes'][layer] = {'exists': False}
                                    print(f"FAIL model.{layer} not found")
                            
                            # Save discovery results
                            output_file = Path("api_discovery_results.json")
                            with open(output_file, 'w') as f:
                                json.dump(discovery_results, f, indent=2)
                            print(f"\nDiscovery results saved to: {output_file}")
                            
                        else:
                            print("FAIL Could not get wrapper from model_manager")
                    else:
                        print("FAIL Enhanced tools not available or model_manager is None")
                        
                else:
                    print("FAIL No entities found for testing")
            else:
                print(f"FAIL Search failed: {search_result}")
        else:
            print(f"FAIL Failed to load model: {load_result}")
            
    except Exception as e:
        print(f"CRITICAL ERROR: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(discover_capellambse_api())
