#!/usr/bin/env python
"""
Safe Capellambse API Assessment
===============================

Comprehensive but safe exploration of capellambse APIs to discover
additional ARCADIA elements and features we can implement.
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import capellambse

# Load the GMHCD model
model_path = os.getenv("CAPELLA_MODEL_PATH", r"<local-path>")
model = capellambse.MelodyModel(model_path)

print("SAFE CAPELLAMBSE API ASSESSMENT")
print("================================")

def safe_explore_layer(layer_name, layer_obj):
    """Safely explore a layer for creation APIs."""
    print(f"\n{layer_name} LAYER EXPLORATION:")
    print("-" * 40)
    
    creation_apis = {}
    
    # Known safe packages to explore
    safe_packages = [
        'entity_pkg', 'function_pkg', 'component_pkg', 'capability_pkg', 
        'interface_pkg', 'requirement_modules', 'data_pkg'
    ]
    
    # Explore safe packages
    for pkg_name in safe_packages:
        if hasattr(layer_obj, pkg_name):
            try:
                pkg_obj = getattr(layer_obj, pkg_name)
                print(f"  {pkg_name}: {type(pkg_obj)}")
                
                if pkg_obj:
                    # Look for collections with create methods
                    pkg_attrs = [attr for attr in dir(pkg_obj) if not attr.startswith('_')]
                    for attr in pkg_attrs:
                        try:
                            collection = getattr(pkg_obj, attr)
                            if hasattr(collection, 'create'):
                                print(f"    {attr}: Can create elements")
                                
                                # Test creation
                                try:
                                    test_name = f"APITest_{layer_name}_{attr}"
                                    test_element = collection.create(name=test_name)
                                    element_type = test_element.__class__.__name__
                                    api_path = f"{layer_name.lower()}.{pkg_name}.{attr}.create()"
                                    creation_apis[element_type] = api_path
                                    print(f"      ✓ Creates: {element_type}")
                                except Exception as e:
                                    print(f"      ✗ Creation failed: {e}")
                        except:
                            pass
            except Exception as e:
                print(f"  {pkg_name}: Error - {e}")
    
    # Look for direct collections that are safe
    safe_direct_collections = [
        'all_components', 'all_functions', 'all_actors', 'all_interfaces',
        'activities', 'functions', 'components', 'entities', 'capabilities'
    ]
    
    print(f"  Direct collections:")
    for collection_name in safe_direct_collections:
        if hasattr(layer_obj, collection_name):
            try:
                collection = getattr(layer_obj, collection_name)
                if hasattr(collection, '__len__'):
                    count = len(collection)
                    has_create = hasattr(collection, 'create')
                    print(f"    {collection_name}: {count} items, create: {has_create}")
                    
                    if has_create:
                        try:
                            test_element = collection.create(name=f"APITest_{layer_name}_{collection_name}")
                            element_type = test_element.__class__.__name__
                            api_path = f"{layer_name.lower()}.{collection_name}.create()"
                            creation_apis[element_type] = api_path
                            print(f"      ✓ Creates: {element_type}")
                        except Exception as e:
                            print(f"      ✗ Creation failed: {e}")
            except:
                pass
    
    return creation_apis

# Explore each layer safely
all_creation_apis = {}

# Operational Architecture
oa_apis = safe_explore_layer("OA", model.oa)
all_creation_apis.update({f"OA_{k}": v for k, v in oa_apis.items()})

# System Architecture  
sa_apis = safe_explore_layer("SA", model.sa)
all_creation_apis.update({f"SA_{k}": v for k, v in sa_apis.items()})

# Logical Architecture
la_apis = safe_explore_layer("LA", model.la)
all_creation_apis.update({f"LA_{k}": v for k, v in la_apis.items()})

# Physical Architecture
pa_apis = safe_explore_layer("PA", model.pa)
all_creation_apis.update({f"PA_{k}": v for k, v in pa_apis.items()})

# Explore model-level APIs safely
print(f"\n\nMODEL-LEVEL EXPLORATION:")
print("-" * 40)

# Safe model attributes to check
safe_model_attrs = [
    'all_requirements', 'all_interfaces', 'all_functions', 'all_components',
    'requirement_modules', 'diagrams'
]

for attr in safe_model_attrs:
    if hasattr(model, attr):
        try:
            obj = getattr(model, attr)
            if hasattr(obj, '__len__'):
                count = len(obj)
                has_create = hasattr(obj, 'create')
                print(f"  {attr}: {count} items, create: {has_create}")
                
                if has_create:
                    try:
                        test_element = obj.create(name=f"APITest_Model_{attr}")
                        element_type = test_element.__class__.__name__
                        api_path = f"model.{attr}.create()"
                        all_creation_apis[f"Model_{element_type}"] = api_path
                        print(f"    ✓ Creates: {element_type}")
                    except Exception as e:
                        print(f"    ✗ Creation failed: {e}")
        except Exception as e:
            print(f"  {attr}: Error - {e}")

# Look for scenario alternatives
print(f"\n\nSCENARIO ALTERNATIVES EXPLORATION:")
print("-" * 40)

# Check each layer for scenario-like elements
scenario_keywords = ['scenario', 'interaction', 'behavior', 'mission', 'use_case']

for layer_name, layer_obj in [("OA", model.oa), ("SA", model.sa), ("LA", model.la)]:
    print(f"\n{layer_name} scenario alternatives:")
    
    # Look through all attributes for scenario-related terms
    layer_attrs = dir(layer_obj)
    scenario_attrs = []
    
    for keyword in scenario_keywords:
        matching = [attr for attr in layer_attrs if keyword in attr.lower() and not attr.startswith('_')]
        scenario_attrs.extend(matching)
    
    scenario_attrs = list(set(scenario_attrs))  # Remove duplicates
    
    if scenario_attrs:
        print(f"  Found scenario-related attributes: {scenario_attrs}")
        
        for attr in scenario_attrs:
            try:
                obj = getattr(layer_obj, attr)
                if hasattr(obj, 'create'):
                    print(f"    {attr}: Can create")
                    try:
                        test_element = obj.create(name=f"ScenarioTest_{layer_name}_{attr}")
                        element_type = test_element.__class__.__name__
                        print(f"      ✓ Creates: {element_type}")
                    except Exception as e:
                        print(f"      ✗ Creation failed: {e}")
                elif hasattr(obj, '__len__'):
                    print(f"    {attr}: Collection with {len(obj)} items")
            except:
                pass
    else:
        print(f"  No scenario-related attributes found")

# Advanced feature exploration
print(f"\n\nADVANCED FEATURES EXPLORATION:")
print("-" * 40)

# Test for advanced modeling capabilities
advanced_tests = [
    ("Functional Chains", lambda: model.la.all_functional_chains),
    ("Data Types", lambda: model.la.data_pkg if hasattr(model.la, 'data_pkg') else None),
    ("State Machines", lambda: getattr(model.la, 'state_machines', None)),
    ("Modes", lambda: getattr(model.la, 'modes', None)),
]

for test_name, test_func in advanced_tests:
    try:
        result = test_func()
        if result:
            if hasattr(result, '__len__'):
                print(f"  {test_name}: {len(result)} items")
                if hasattr(result, 'create'):
                    print(f"    Can create new {test_name}")
            else:
                print(f"  {test_name}: Available")
        else:
            print(f"  {test_name}: Not found")
    except Exception as e:
        print(f"  {test_name}: Error - {e}")

# Summary
print(f"\n\n" + "=" * 60)
print("API ASSESSMENT SUMMARY")
print("=" * 60)

print(f"Total Creation APIs Found: {len(all_creation_apis)}")
print(f"\nDiscovered APIs:")
for element_type, api_path in sorted(all_creation_apis.items()):
    print(f"  ✓ {element_type}: {api_path}")

# Compare with our current implementation
current_elements = [
    "OperationalEntity", "OperationalActivity", "OperationalCapability",
    "SystemFunction", "SystemComponent", "SystemCapability", "SystemActor",
    "LogicalComponent", "LogicalActor", "LogicalFunction", "Interface", 
    "FunctionalChain", "FunctionalExchange",
    "PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent",
    "Entity", "Capability", "Actor", "Requirements"
]

print(f"\nCurrent Implementation: {len(current_elements)} elements")
print(f"Newly Discovered: {len(all_creation_apis)} APIs")

# Check for gaps
print(f"\nGAP ANALYSIS:")
new_discoveries = []
for api_name in all_creation_apis.keys():
    # Extract element type from API name (remove layer prefix)
    element_type = api_name.split('_', 1)[1] if '_' in api_name else api_name
    if element_type not in current_elements and element_type not in [elem.replace('Operational', '').replace('System', '').replace('Logical', '').replace('Physical', '') for elem in current_elements]:
        new_discoveries.append(element_type)

if new_discoveries:
    print(f"  New elements to implement: {new_discoveries}")
else:
    print(f"  No major new elements discovered")

# Save model
try:
    model.save()
    print(f"\n✓ Model saved with test elements")
except Exception as e:
    print(f"\n✗ Model save failed: {e}")

print(f"\nASSESSMENT COMPLETE")
print(f"Recommendation: Focus on scenario alternatives and advanced behavioral modeling")