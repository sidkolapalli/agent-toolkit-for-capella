#!/usr/bin/env python
"""
API Discovery: Operational Architecture (OA) Layer - Safe Version

This script tests what Operational Architecture elements can be created
using the capellambse library with the GMHCD model.

Required OA Elements per Full-Scale Assessment:
- OperationalEntity
- OperationalActivity 
- OperationalInteraction
- OperationalCapability
- OperationalScenario
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== OPERATIONAL ARCHITECTURE (OA) API DISCOVERY (SAFE) ===\n")

# Check if OA exists
print("1. Checking Operational Architecture access...")
if hasattr(model, 'oa') and model.oa:
    oa = model.oa
    print(f"   [OK] OA accessible: {oa.name}")
    print(f"   Type: {type(oa)}")
    print(f"   UUID: {oa.uuid}")
else:
    print("   [FAILED] No Operational Architecture found in model")
    exit(1)

print()

# Test key collections we know exist
print("2. Testing known OA collections...")
known_collections = [
    ('all_activities', 'Activities'),
    ('all_actors', 'Actors'), 
    ('all_capabilities', 'Capabilities'),
    ('entity_pkg', 'Entity Package'),
    ('function_pkg', 'Function Package')
]

for attr_name, description in known_collections:
    try:
        if hasattr(oa, attr_name):
            collection = getattr(oa, attr_name)
            if hasattr(collection, '__len__'):
                count = len(collection)
                print(f"   [OK] {description}: {type(collection).__name__} (count: {count})")
            else:
                print(f"   [OK] {description}: {type(collection).__name__}")
        else:
            print(f"   [MISSING] {description}: Not found")
    except Exception as e:
        print(f"   [ERROR] {description}: {e}")

print()

# Test OperationalEntity creation
print("3. Testing OperationalEntity creation...")
entity_creation_works = False
try:
    if hasattr(oa, 'entity_pkg') and oa.entity_pkg:
        entity_pkg = oa.entity_pkg
        print(f"   Found entity_pkg: {type(entity_pkg)}")
        
        if hasattr(entity_pkg, 'entities'):
            entities = entity_pkg.entities
            print(f"   Found entities collection: {type(entities)} (count: {len(entities)})")
            
            if hasattr(entities, 'create'):
                print(f"   [OK] entities.create() method available")
                
                # Try creating an entity
                try:
                    test_entity = entities.create(name="Test_OA_Entity")
                    print(f"   [SUCCESS] Created: {test_entity.name} ({test_entity.uuid})")
                    entity_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
            else:
                print(f"   [FAILED] No create method on entities")
        else:
            print(f"   [FAILED] No entities attribute in entity_pkg")
    else:
        print(f"   [FAILED] No entity_pkg found in OA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test OperationalActivity creation
print("4. Testing OperationalActivity creation...")
activity_creation_works = False
try:
    if hasattr(oa, 'function_pkg') and oa.function_pkg:
        function_pkg = oa.function_pkg
        print(f"   Found function_pkg: {type(function_pkg)}")
        
        # Check for activities in all_activities
        if hasattr(oa, 'all_activities'):
            all_activities = oa.all_activities
            print(f"   Found all_activities: {type(all_activities)} (count: {len(all_activities)})")
            
            # Try to find where activities are created
            # Check if function_pkg has activities
            if hasattr(function_pkg, 'activities'):
                activities = function_pkg.activities
                print(f"   Found function_pkg.activities: {type(activities)} (count: {len(activities)})")
                
                if hasattr(activities, 'create'):
                    try:
                        test_activity = activities.create(name="Test_OA_Activity")
                        print(f"   [SUCCESS] Created: {test_activity.name} ({test_activity.uuid})")
                        activity_creation_works = True
                    except Exception as e:
                        print(f"   [FAILED] Create failed: {e}")
                else:
                    print(f"   [FAILED] No create method on function_pkg.activities")
            else:
                print(f"   [INFO] No activities attribute in function_pkg")
                print(f"   [INFO] all_activities exists but may be read-only collection")
        else:
            print(f"   [FAILED] No all_activities found")
    else:
        print(f"   [FAILED] No function_pkg found in OA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test OperationalCapability creation
print("5. Testing OperationalCapability creation...")
capability_creation_works = False
try:
    # Use the all_capabilities collection we know exists
    if hasattr(oa, 'all_capabilities'):
        all_capabilities = oa.all_capabilities
        print(f"   Found all_capabilities: {type(all_capabilities)} (count: {len(all_capabilities)})")
        
        # Check if it's a creatable collection
        if hasattr(all_capabilities, 'create'):
            try:
                test_capability = all_capabilities.create(name="Test_OA_Capability")
                print(f"   [SUCCESS] Created: {test_capability.name} ({test_capability.uuid})")
                capability_creation_works = True
            except Exception as e:
                print(f"   [FAILED] Create on all_capabilities failed: {e}")
        else:
            print(f"   [INFO] all_capabilities has no create method")
            
        # Look for capability_pkg
        if hasattr(oa, 'capability_pkg'):
            capability_pkg = oa.capability_pkg
            print(f"   Found capability_pkg: {type(capability_pkg)}")
            
            if hasattr(capability_pkg, 'capabilities'):
                capabilities = capability_pkg.capabilities
                print(f"   Found capabilities: {type(capabilities)} (count: {len(capabilities)})")
                
                if hasattr(capabilities, 'create'):
                    try:
                        test_capability = capabilities.create(name="Test_OA_Capability")
                        print(f"   [SUCCESS] Created via capability_pkg: {test_capability.name} ({test_capability.uuid})")
                        capability_creation_works = True
                    except Exception as e:
                        print(f"   [FAILED] Create via capability_pkg failed: {e}")
                else:
                    print(f"   [INFO] No create method on capability_pkg.capabilities")
            else:
                print(f"   [INFO] No capabilities in capability_pkg")
        else:
            print(f"   [INFO] No capability_pkg found")
            
    else:
        print(f"   [FAILED] No all_capabilities found")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test saving changes
print("6. Testing model save...")
save_works = False
try:
    model.save()
    print("   [OK] Model saved successfully")
    save_works = True
except Exception as e:
    print(f"   [FAILED] Save failed: {e}")

print()

# Summary
print("=== OA API DISCOVERY SUMMARY ===")
print(f"✅ OA Access: Working")
print(f"{'✅' if entity_creation_works else '❌'} OperationalEntity Creation: {'Working' if entity_creation_works else 'Failed'}")
print(f"{'✅' if activity_creation_works else '❌'} OperationalActivity Creation: {'Working' if activity_creation_works else 'Failed'}")
print(f"{'✅' if capability_creation_works else '❌'} OperationalCapability Creation: {'Working' if capability_creation_works else 'Failed'}")
print(f"{'✅' if save_works else '❌'} Model Persistence: {'Working' if save_works else 'Failed'}")

# Calculate success rate
successes = sum([entity_creation_works, activity_creation_works, capability_creation_works, save_works])
total_tests = 4
success_rate = (successes / total_tests) * 100

print(f"\nOA Implementation Readiness: {success_rate:.0f}% ({successes}/{total_tests} capabilities working)")

if success_rate >= 75:
    print("✅ HIGH - OA layer ready for implementation")
elif success_rate >= 50:
    print("⚠️ MEDIUM - OA layer partially ready, some workarounds needed")
else:
    print("❌ LOW - OA layer needs significant work or alternative approach")

print("\n=== NEXT STEPS ===")
if entity_creation_works:
    print("- Implement OperationalEntity in model_manager.py")
if activity_creation_works:
    print("- Implement OperationalActivity in model_manager.py")
if capability_creation_works:
    print("- Implement OperationalCapability in model_manager.py")
if not save_works:
    print("- Investigate model save issues")
    
print("- Test OperationalInteraction and OperationalScenario creation")
print("- Create comprehensive OA test suite")