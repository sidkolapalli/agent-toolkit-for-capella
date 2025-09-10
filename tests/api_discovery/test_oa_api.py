#!/usr/bin/env python
"""
API Discovery: Operational Architecture (OA) Layer

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

print("=== OPERATIONAL ARCHITECTURE (OA) API DISCOVERY ===\n")

# Check if OA exists
print("1. Checking Operational Architecture access...")
if hasattr(model, 'oa') and model.oa:
    oa = model.oa
    print(f"   [OK] OA accessible: {oa.name}")
    print(f"   Type: {type(oa)}")
    print(f"   UUID: {oa.uuid}")
else:
    print("   [FAILED] No Operational Architecture found in model")
    print("   Available architectures:", [attr for attr in dir(model) if not attr.startswith('_')])
    exit(1)

print()

# Explore OA structure
print("2. Exploring OA structure...")
print("   Available attributes:")
for attr in sorted(dir(oa)):
    if not attr.startswith('_') and hasattr(oa, attr):
        value = getattr(oa, attr)
        if hasattr(value, '__len__') and not callable(value):
            try:
                count = len(value)
                print(f"     .{attr}: {type(value).__name__} (count: {count})")
            except:
                print(f"     .{attr}: {type(value).__name__}")
        elif not callable(value):
            print(f"     .{attr}: {type(value).__name__}")

print()

# Test OperationalEntity creation
print("3. Testing OperationalEntity creation...")
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
                    entity_creation_works = False
            else:
                print(f"   [FAILED] No create method on entities")
                entity_creation_works = False
        else:
            print(f"   [FAILED] No entities attribute in entity_pkg")
            entity_creation_works = False
    else:
        print(f"   [FAILED] No entity_pkg found in OA")
        entity_creation_works = False
except Exception as e:
    print(f"   [ERROR] {e}")
    entity_creation_works = False

print()

# Test OperationalActivity creation
print("4. Testing OperationalActivity creation...")
try:
    if hasattr(oa, 'function_pkg') and oa.function_pkg:
        function_pkg = oa.function_pkg
        print(f"   Found function_pkg: {type(function_pkg)}")
        
        # Check for activities
        activities_attr = None
        for attr in ['activities', 'operational_activities', 'functions']:
            if hasattr(function_pkg, attr):
                activities_attr = attr
                break
        
        if activities_attr:
            activities = getattr(function_pkg, activities_attr)
            print(f"   Found {activities_attr}: {type(activities)} (count: {len(activities)})")
            
            if hasattr(activities, 'create'):
                print(f"   [OK] {activities_attr}.create() method available")
                
                try:
                    test_activity = activities.create(name="Test_OA_Activity")
                    print(f"   [SUCCESS] Created: {test_activity.name} ({test_activity.uuid})")
                    activity_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
                    activity_creation_works = False
            else:
                print(f"   [FAILED] No create method on {activities_attr}")
                activity_creation_works = False
        else:
            print(f"   [FAILED] No activities/functions found in function_pkg")
            activity_creation_works = False
    else:
        print(f"   [FAILED] No function_pkg found in OA")
        activity_creation_works = False
except Exception as e:
    print(f"   [ERROR] {e}")
    activity_creation_works = False

print()

# Test OperationalCapability creation
print("5. Testing OperationalCapability creation...")
try:
    capability_creation_works = False
    
    # Check for capability_pkg or similar
    capability_attrs = ['capability_pkg', 'capabilities', 'operational_capabilities']
    found_capabilities = False
    
    for attr in capability_attrs:
        if hasattr(oa, attr):
            capability_container = getattr(oa, attr)
            print(f"   Found {attr}: {type(capability_container)}")
            
            # Check for capabilities collection
            if hasattr(capability_container, 'capabilities'):
                capabilities = capability_container.capabilities
                print(f"   Found capabilities: {type(capabilities)} (count: {len(capabilities)})")
                found_capabilities = True
                
                if hasattr(capabilities, 'create'):
                    try:
                        test_capability = capabilities.create(name="Test_OA_Capability")
                        print(f"   [SUCCESS] Created: {test_capability.name} ({test_capability.uuid})")
                        capability_creation_works = True
                    except Exception as e:
                        print(f"   [FAILED] Create failed: {e}")
                else:
                    print(f"   [FAILED] No create method on capabilities")
            elif hasattr(capability_container, 'create'):
                # Direct creation
                try:
                    test_capability = capability_container.create(name="Test_OA_Capability")
                    print(f"   [SUCCESS] Created: {test_capability.name} ({test_capability.uuid})")
                    capability_creation_works = True
                    found_capabilities = True
                except Exception as e:
                    print(f"   [FAILED] Direct create failed: {e}")
            break
    
    if not found_capabilities:
        print(f"   [FAILED] No capability structures found in OA")
        
except Exception as e:
    print(f"   [ERROR] {e}")
    capability_creation_works = False

print()

# Test saving changes
print("6. Testing model save...")
try:
    model.save()
    print("   [SUCCESS] Model saved successfully")
    save_works = True
except Exception as e:
    print(f"   [FAILED] Save failed: {e}")
    save_works = False

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