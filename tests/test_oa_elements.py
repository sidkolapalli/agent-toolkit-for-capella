#!/usr/bin/env python
"""
Test-Driven Development: Operational Architecture (OA) Elements

Tests for OA elements that should be implemented:
- OperationalEntity
- OperationalActivity  
- OperationalCapability

Following TDD: These tests will initially FAIL until implementation is added to model_manager.py
"""

import asyncio
import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse
from capella_mcp.model_manager import ModelManager


async def main():
    # Load the GMHCD model
    model_path = r"<local-path>"
    
    print("=== OA ELEMENTS TDD TEST SUITE ===\n")
    
    # Initialize model manager
    manager = ModelManager()
    
    # Load the model
    print("1. Loading GMHCD model...")
    model_id = await manager.load_model(model_path)
    print(f"   [OK] Model loaded with ID: {model_id}")
    print()
    
    # Test creating OA elements (TDD - these should FAIL initially)
    print("2. Testing OA element creation (TDD - expecting failures)...")
    
    oa_tests = [
        ("OperationalEntity", "Test_OA_Entity_TDD"),
        ("OperationalActivity", "Test_OA_Activity_TDD"), 
        ("OperationalCapability", "Test_OA_Capability_TDD")
    ]
    
    created_elements = {}
    
    for element_type, element_name in oa_tests:
        print(f"   Creating {element_type}...")
        try:
            element_id = await manager.create_element(model_id, element_type, name=element_name)
            print(f"   [OK] Created {element_type}: {element_id}")
            created_elements[element_type] = element_id
        except Exception as e:
            print(f"   [EXPECTED FAILURE] {element_type}: {e}")
            created_elements[element_type] = None
    
    print()
    
    # Test querying OA elements
    print("3. Testing OA element queries...")
    
    query_tests = [
        ("OperationalEntity", "OA Entity"),
        ("OperationalActivity", "OA Activity"),
        ("OperationalCapability", "OA Capability")
    ]
    
    for element_type, description in query_tests:
        print(f"   Querying {description}...")
        try:
            elements = await manager.query_elements(model_id, {"type": element_type})
            test_elements = [e for e in elements if hasattr(e, 'name') and e.name and 'Test_OA_' in e.name]
            count = len(test_elements)
            print(f"   [INFO] Found {count} test {description} elements")
            
            # Look for our created element
            if created_elements[element_type]:
                found_our_element = any(e for e in elements 
                                      if hasattr(e, 'name') and e.name and 'TDD' in e.name)
                if found_our_element:
                    print(f"   [OK] Our created {description} found in query")
                else:
                    print(f"   [WARN] Created {description} not found in query results")
            else:
                print(f"   [INFO] No element created to search for")
                
        except Exception as e:
            print(f"   [EXPECTED QUERY FAILURE] {description}: {e}")
    
    print()
    
    # Test saving the model
    print("4. Testing model persistence...")
    try:
        await manager.save_model(model_id)
        print("   [OK] Model saved successfully")
        save_success = True
    except Exception as e:
        print(f"   [FAILED] Save error: {e}")
        save_success = False
    
    print()
    
    # Test persistence across reload
    print("5. Testing persistence across reload...")
    if save_success:
        try:
            # Create new manager and reload
            manager2 = ModelManager()
            model_id2 = await manager2.load_model(model_path)
            print(f"   [OK] Model reloaded with ID: {model_id2}")
            
            # Check if created elements persist
            for element_type, element_name in oa_tests:
                if created_elements[element_type]:
                    print(f"   Checking persistence of {element_type}...")
                    try:
                        elements = await manager2.query_elements(model_id2, {"type": element_type})
                        persisted = any(e for e in elements 
                                      if hasattr(e, 'name') and e.name == element_name)
                        if persisted:
                            print(f"   [OK] {element_type} persisted across reload")
                        else:
                            print(f"   [WARN] {element_type} not found after reload")
                    except Exception as e:
                        print(f"   [EXPECTED PERSISTENCE FAILURE] {element_type}: {e}")
                else:
                    print(f"   [INFO] No {element_type} created to check persistence")
        except Exception as e:
            print(f"   [FAILED] Reload failed: {e}")
    else:
        print("   [SKIPPED] Save failed, cannot test persistence")
    
    print()
    
    # Summary
    print("=== TDD TEST SUMMARY ===")
    
    success_count = sum(1 for elem_id in created_elements.values() if elem_id is not None)
    total_tests = len(oa_tests)
    
    print(f"OA Element Creation: {success_count}/{total_tests} types working")
    
    for element_type, element_id in created_elements.items():
        status = "WORKING" if element_id else "NOT IMPLEMENTED"
        print(f"  {element_type}: {status}")
    
    print(f"\nImplementation Progress: {(success_count/total_tests)*100:.0f}%")
    
    if success_count == 0:
        print("\n[TDD RED PHASE] All tests failing as expected - ready for implementation")
    elif success_count < total_tests:
        print(f"\n[TDD PARTIAL] {total_tests - success_count} elements still need implementation")
    else:
        print("\n[TDD GREEN PHASE] All OA elements implemented successfully!")
    
    print("\nNext Steps:")
    if success_count == 0:
        print("1. Implement OA elements in model_manager.py")
        print("2. Use exact API patterns from discovery scripts:")
        print("   - OperationalEntity: oa.entity_pkg.entities.create()")
        print("   - OperationalActivity: oa.function_pkg.activities.create()")
        print("   - OperationalCapability: oa.capability_pkg.capabilities.create()")
    elif success_count < total_tests:
        print("1. Complete remaining OA element implementations")
        print("2. Run this test again to verify all pass")
    else:
        print("1. Move to Phase 2A Layer 2 - SA elements")
        print("2. Create test_sa_elements.py")


if __name__ == "__main__":
    asyncio.run(main())