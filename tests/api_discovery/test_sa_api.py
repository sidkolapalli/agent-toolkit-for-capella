#!/usr/bin/env python
"""
API Discovery: System Architecture (SA) Layer

Tests what System Architecture elements can be created using capellambse.

Required SA Elements per Full-Scale Assessment:
- SystemActor
- SystemFunction  
- SystemComponent
- SystemCapability
- SystemScenario
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

print("=== SYSTEM ARCHITECTURE (SA) API DISCOVERY ===\n")

# Check if SA exists
print("1. Checking System Architecture access...")
if hasattr(model, 'sa') and model.sa:
    sa = model.sa
    print(f"   [OK] SA accessible: {sa.name}")
    print(f"   Type: {type(sa)}")
    print(f"   UUID: {sa.uuid}")
else:
    print("   [FAILED] No System Architecture found in model")
    exit(1)

print()

# Test key SA collections
print("2. Testing known SA collections...")
known_collections = [
    ('all_actors', 'System Actors'),
    ('all_functions', 'System Functions'), 
    ('all_components', 'System Components'),
    ('all_capabilities', 'System Capabilities'),
    ('actor_pkg', 'Actor Package'),
    ('function_pkg', 'Function Package'),
    ('component_pkg', 'Component Package')
]

for attr_name, description in known_collections:
    try:
        if hasattr(sa, attr_name):
            collection = getattr(sa, attr_name)
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

# Test SystemActor creation
print("3. Testing SystemActor creation...")
actor_creation_works = False
try:
    if hasattr(sa, 'actor_pkg') and sa.actor_pkg:
        actor_pkg = sa.actor_pkg
        print(f"   Found actor_pkg: {type(actor_pkg)}")
        
        if hasattr(actor_pkg, 'actors'):
            actors = actor_pkg.actors
            print(f"   Found actors collection: {type(actors)} (count: {len(actors)})")
            
            if hasattr(actors, 'create'):
                try:
                    test_actor = actors.create(name="Test_SA_Actor")
                    print(f"   [SUCCESS] Created: {test_actor.name} ({test_actor.uuid})")
                    actor_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
            else:
                print(f"   [FAILED] No create method on actors")
        else:
            print(f"   [FAILED] No actors attribute in actor_pkg")
    else:
        print(f"   [FAILED] No actor_pkg found in SA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test SystemFunction creation
print("4. Testing SystemFunction creation...")
function_creation_works = False
try:
    if hasattr(sa, 'function_pkg') and sa.function_pkg:
        function_pkg = sa.function_pkg
        print(f"   Found function_pkg: {type(function_pkg)}")
        
        # Try different possible function collections
        function_collections = ['functions', 'system_functions', 'activities']
        found_functions = False
        
        for func_attr in function_collections:
            if hasattr(function_pkg, func_attr):
                functions = getattr(function_pkg, func_attr)
                print(f"   Found {func_attr}: {type(functions)} (count: {len(functions)})")
                found_functions = True
                
                if hasattr(functions, 'create'):
                    try:
                        test_function = functions.create(name="Test_SA_Function")
                        print(f"   [SUCCESS] Created: {test_function.name} ({test_function.uuid})")
                        function_creation_works = True
                        break
                    except Exception as e:
                        print(f"   [FAILED] Create on {func_attr} failed: {e}")
                else:
                    print(f"   [INFO] No create method on {func_attr}")
        
        if not found_functions:
            print(f"   [INFO] No function collections found in function_pkg")
            
    else:
        print(f"   [FAILED] No function_pkg found in SA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test SystemComponent creation
print("5. Testing SystemComponent creation...")
component_creation_works = False
try:
    if hasattr(sa, 'component_pkg') and sa.component_pkg:
        component_pkg = sa.component_pkg
        print(f"   Found component_pkg: {type(component_pkg)}")
        
        if hasattr(component_pkg, 'components'):
            components = component_pkg.components
            print(f"   Found components: {type(components)} (count: {len(components)})")
            
            if hasattr(components, 'create'):
                try:
                    test_component = components.create(name="Test_SA_Component")
                    print(f"   [SUCCESS] Created: {test_component.name} ({test_component.uuid})")
                    component_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
            else:
                print(f"   [FAILED] No create method on components")
        else:
            print(f"   [FAILED] No components attribute in component_pkg")
    else:
        print(f"   [FAILED] No component_pkg found in SA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test SystemCapability creation
print("6. Testing SystemCapability creation...")
capability_creation_works = False
try:
    if hasattr(sa, 'capability_pkg'):
        capability_pkg = sa.capability_pkg
        print(f"   Found capability_pkg: {type(capability_pkg)}")
        
        if hasattr(capability_pkg, 'capabilities'):
            capabilities = capability_pkg.capabilities
            print(f"   Found capabilities: {type(capabilities)} (count: {len(capabilities)})")
            
            if hasattr(capabilities, 'create'):
                try:
                    test_capability = capabilities.create(name="Test_SA_Capability")
                    print(f"   [SUCCESS] Created: {test_capability.name} ({test_capability.uuid})")
                    capability_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
            else:
                print(f"   [INFO] No create method on capabilities")
        else:
            print(f"   [INFO] No capabilities in capability_pkg")
    else:
        print(f"   [INFO] No capability_pkg found in SA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test saving changes
print("7. Testing model save...")
save_works = False
try:
    model.save()
    print("   [OK] Model saved successfully")
    save_works = True
except Exception as e:
    print(f"   [FAILED] Save failed: {e}")

print()

# Summary  
print("=== SA API DISCOVERY SUMMARY ===")
print(f"[OK] SA Access: Working")
print(f"[{'OK' if actor_creation_works else 'FAIL'}] SystemActor Creation: {'Working' if actor_creation_works else 'Failed'}")
print(f"[{'OK' if function_creation_works else 'FAIL'}] SystemFunction Creation: {'Working' if function_creation_works else 'Failed'}")
print(f"[{'OK' if component_creation_works else 'FAIL'}] SystemComponent Creation: {'Working' if component_creation_works else 'Failed'}")
print(f"[{'OK' if capability_creation_works else 'FAIL'}] SystemCapability Creation: {'Working' if capability_creation_works else 'Failed'}")
print(f"[{'OK' if save_works else 'FAIL'}] Model Persistence: {'Working' if save_works else 'Failed'}")

# Calculate success rate
successes = sum([actor_creation_works, function_creation_works, component_creation_works, capability_creation_works, save_works])
total_tests = 5
success_rate = (successes / total_tests) * 100

print(f"\nSA Implementation Readiness: {success_rate:.0f}% ({successes}/{total_tests} capabilities working)")

if success_rate >= 80:
    print("[HIGH] SA layer ready for implementation")
elif success_rate >= 60:
    print("[MEDIUM] SA layer partially ready, some workarounds needed")
else:
    print("[LOW] SA layer needs significant work or alternative approach")

print("\n=== NEXT STEPS ===")
if actor_creation_works:
    print("- Implement SystemActor in model_manager.py")
if function_creation_works:
    print("- Implement SystemFunction in model_manager.py")
if component_creation_works:
    print("- Implement SystemComponent in model_manager.py")
if capability_creation_works:
    print("- Implement SystemCapability in model_manager.py")
if not save_works:
    print("- Investigate model save issues")
    
print("- Test SystemInteraction and SystemScenario creation")
print("- Create comprehensive SA test suite")