#!/usr/bin/env python
"""
Comprehensive Capellambse API Assessment
=========================================

This script performs a complete exploration of the capellambse API to discover:
1. All available ARCADIA elements across all layers
2. Advanced modeling features we haven't implemented
3. Alternative approaches for scenarios and behavioral modeling
4. Hidden or undocumented APIs that could enhance our capability

Goal: Achieve maximum possible ARCADIA methodology implementation
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

print("=" * 80)
print("COMPREHENSIVE CAPELLAMBSE API ASSESSMENT")
print("=" * 80)
print("Goal: Discover all possible ARCADIA elements and advanced features")
print()

# Track discovered APIs
discovered_apis = {
    "creation_apis": {},
    "advanced_features": {},
    "behavioral_models": {},
    "requirements_apis": {},
    "validation_apis": {},
    "scenario_alternatives": {}
}

def explore_layer_comprehensively(layer_name, layer_obj):
    """Comprehensive exploration of a layer for all possible APIs."""
    print(f"\n{layer_name} LAYER - COMPREHENSIVE EXPLORATION")
    print("=" * 60)
    
    layer_apis = {
        "packages": {},
        "collections": {},
        "creation_methods": {},
        "advanced_features": {}
    }
    
    # Get all attributes
    all_attrs = [attr for attr in dir(layer_obj) if not attr.startswith('_')]
    print(f"Total attributes: {len(all_attrs)}")
    
    # Categorize attributes
    pkg_attrs = [attr for attr in all_attrs if 'pkg' in attr]
    collection_attrs = [attr for attr in all_attrs if not 'pkg' in attr and not callable(getattr(layer_obj, attr, None))]
    
    print(f"Package attributes: {len(pkg_attrs)}")
    print(f"Collection attributes: {len(collection_attrs)}")
    
    # Explore packages in detail
    print(f"\n{layer_name} PACKAGES:")
    for pkg_attr in pkg_attrs:
        try:
            pkg_obj = getattr(layer_obj, pkg_attr)
            print(f"  {pkg_attr}: {type(pkg_obj)}")
            
            # Explore package contents
            if pkg_obj:
                pkg_contents = [attr for attr in dir(pkg_obj) if not attr.startswith('_')]
                creation_attrs = [attr for attr in pkg_contents if hasattr(getattr(pkg_obj, attr, None), 'create')]
                
                for creation_attr in creation_attrs:
                    collection = getattr(pkg_obj, creation_attr)
                    print(f"    {creation_attr}: {type(collection)} (create: YES)")
                    
                    # Test creation capabilities
                    try:
                        if hasattr(collection, 'create'):
                            # Try to discover what can be created
                            test_name = f"API_Test_{layer_name}_{creation_attr}"
                            test_element = collection.create(name=test_name)
                            element_type = test_element.__class__.__name__
                            api_path = f"{layer_name.lower()}.{pkg_attr}.{creation_attr}.create()"
                            
                            layer_apis["creation_methods"][element_type] = api_path
                            print(f"      ✓ Can create: {element_type}")
                            
                            # Explore element capabilities
                            element_attrs = [attr for attr in dir(test_element) if not attr.startswith('_')]
                            relationship_attrs = [attr for attr in element_attrs if any(keyword in attr.lower() 
                                                for keyword in ['exchange', 'interface', 'allocation', 'function', 'requirement', 'scenario', 'behavior'])]
                            
                            if relationship_attrs:
                                print(f"        Relationships: {relationship_attrs[:5]}")  # Show first 5
                                
                    except Exception as e:
                        print(f"      ✗ Creation failed: {e}")
                        
        except Exception as e:
            print(f"  {pkg_attr}: ERROR - {e}")
    
    # Explore direct collections
    print(f"\n{layer_name} DIRECT COLLECTIONS:")
    for col_attr in collection_attrs:
        try:
            col_obj = getattr(layer_obj, col_attr)
            if hasattr(col_obj, '__len__'):
                count = len(col_obj)
                has_create = hasattr(col_obj, 'create')
                print(f"  {col_attr}: {type(col_obj)} (count: {count}, create: {has_create})")
                
                if has_create:
                    try:
                        test_element = col_obj.create(name=f"API_Test_{layer_name}_{col_attr}")
                        element_type = test_element.__class__.__name__
                        api_path = f"{layer_name.lower()}.{col_attr}.create()"
                        layer_apis["creation_methods"][element_type] = api_path
                        print(f"    ✓ Can create: {element_type}")
                    except Exception as e:
                        print(f"    ✗ Creation failed: {e}")
                        
        except Exception as e:
            print(f"  {col_attr}: ERROR - {e}")
    
    # Look for advanced features
    advanced_keywords = ['scenario', 'behavior', 'interaction', 'mission', 'mode', 'state', 'transition']
    print(f"\n{layer_name} ADVANCED FEATURES:")
    for keyword in advanced_keywords:
        matching_attrs = [attr for attr in all_attrs if keyword in attr.lower()]
        if matching_attrs:
            print(f"  {keyword.upper()} related: {matching_attrs}")
            for attr in matching_attrs:
                try:
                    obj = getattr(layer_obj, attr)
                    if hasattr(obj, 'create'):
                        try:
                            test_element = obj.create(name=f"Test_{keyword}")
                            print(f"    ✓ {attr} can create: {test_element.__class__.__name__}")
                        except:
                            pass
                except:
                    pass
    
    return layer_apis

# Explore all layers comprehensively
layer_results = {}

# Operational Architecture (OA)
layer_results['OA'] = explore_layer_comprehensively("OA", model.oa)

# System Architecture (SA)
layer_results['SA'] = explore_layer_comprehensively("SA", model.sa)

# Logical Architecture (LA)
layer_results['LA'] = explore_layer_comprehensively("LA", model.la)

# Physical Architecture (PA)
layer_results['PA'] = explore_layer_comprehensively("PA", model.pa)

# Explore model-level APIs
print(f"\n\nMODEL-LEVEL APIS")
print("=" * 60)

model_attrs = [attr for attr in dir(model) if not attr.startswith('_')]
interesting_attrs = [attr for attr in model_attrs if any(keyword in attr.lower() 
                    for keyword in ['requirement', 'scenario', 'behavior', 'validation', 'analysis', 'trace'])]

print(f"Model-level interesting attributes: {interesting_attrs}")

for attr in interesting_attrs:
    try:
        obj = getattr(model, attr)
        print(f"  {attr}: {type(obj)}")
        if hasattr(obj, '__len__'):
            print(f"    Count: {len(obj)}")
        if hasattr(obj, 'create'):
            print(f"    Has create method: YES")
    except Exception as e:
        print(f"  {attr}: ERROR - {e}")

# Test behavioral modeling alternatives
print(f"\n\nBEHAVIORAL MODELING ALTERNATIVES")
print("=" * 60)

# Look for state machines, modes, scenarios in different ways
behavioral_searches = [
    ("Operational Activities", model.oa, 'activities'),
    ("System Functions", model.sa, 'functions'),
    ("Logical Functions", model.la, 'functions'),
    ("Functional Chains", model.la, 'functional_chains'),
]

for search_name, layer, attr_name in behavioral_searches:
    print(f"\n{search_name}:")
    try:
        if hasattr(layer, attr_name):
            collection = getattr(layer, attr_name)
            print(f"  Found {attr_name}: {type(collection)} (count: {len(collection)})")
            
            # Check for behavioral attributes
            if len(collection) > 0:
                sample = list(collection)[0]
                behavioral_attrs = [attr for attr in dir(sample) if not attr.startswith('_') 
                                  and any(keyword in attr.lower() for keyword in ['scenario', 'behavior', 'state', 'mode'])]
                if behavioral_attrs:
                    print(f"    Behavioral attributes: {behavioral_attrs}")
        else:
            print(f"  No {attr_name} found")
    except Exception as e:
        print(f"  Error exploring {search_name}: {e}")

# Requirements and validation exploration
print(f"\n\nREQUIREMENTS & VALIDATION APIS")
print("=" * 60)

# Look for requirements at model level
req_attrs = [attr for attr in dir(model) if 'requirement' in attr.lower()]
print(f"Model requirement attributes: {req_attrs}")

for attr in req_attrs:
    try:
        obj = getattr(model, attr)
        print(f"  {attr}: {type(obj)}")
        if hasattr(obj, '__len__'):
            print(f"    Count: {len(obj)}")
    except:
        pass

# Look for validation capabilities
validation_methods = ['validate', 'check', 'verify', 'analyze']
model_methods = [attr for attr in dir(model) if callable(getattr(model, attr)) and not attr.startswith('_')]
validation_attrs = [attr for attr in model_methods if any(keyword in attr.lower() for keyword in validation_methods)]

print(f"Model validation methods: {validation_attrs}")

# Summary of discoveries
print(f"\n\n" + "=" * 80)
print("API ASSESSMENT SUMMARY")
print("=" * 80)

total_creation_apis = 0
for layer, results in layer_results.items():
    creation_count = len(results.get('creation_methods', {}))
    total_creation_apis += creation_count
    print(f"{layer} Layer: {creation_count} creation APIs discovered")
    
    for element_type, api_path in results.get('creation_methods', {}).items():
        print(f"  ✓ {element_type}: {api_path}")

print(f"\nTotal Creation APIs Discovered: {total_creation_apis}")

# Check what we're missing from full ARCADIA
expected_arcadia_elements = [
    "OperationalEntity", "OperationalActivity", "OperationalCapability", "OperationalScenario",
    "SystemFunction", "SystemComponent", "SystemCapability", "SystemActor", "SystemScenario", 
    "LogicalComponent", "LogicalActor", "LogicalFunction", "Interface", "FunctionalChain", "FunctionalExchange", "LogicalScenario",
    "PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent", "PhysicalScenario"
]

print(f"\nARCADIA COVERAGE ANALYSIS:")
print(f"Expected ARCADIA elements: {len(expected_arcadia_elements)}")

implemented = []
missing = []

for element in expected_arcadia_elements:
    found = False
    for layer_results_data in layer_results.values():
        if element in layer_results_data.get('creation_methods', {}):
            implemented.append(element)
            found = True
            break
    if not found:
        missing.append(element)

print(f"Implemented: {len(implemented)} elements")
print(f"Missing: {len(missing)} elements")

if missing:
    print(f"\nMissing Elements: {missing}")

coverage_percentage = (len(implemented) / len(expected_arcadia_elements)) * 100
print(f"\nEstimated ARCADIA Coverage: {coverage_percentage:.1f}%")

# Recommendations
print(f"\n" + "=" * 80)
print("IMPLEMENTATION RECOMMENDATIONS")
print("=" * 80)

if missing:
    print("Priority 1: Implement missing ARCADIA elements")
    for element in missing:
        print(f"  - {element}")

print("\nPriority 2: Advanced features to implement")
print("  - Enhanced dependency analysis (already done)")
print("  - Behavioral modeling (scenarios as functional chains)")
print("  - Requirements traceability")
print("  - Model validation and analysis")
print("  - Performance optimization")

# Save model
try:
    model.save()
    print(f"\n✓ Model saved with {total_creation_apis} test elements")
except Exception as e:
    print(f"\n✗ Model save failed: {e}")

print(f"\nAPI ASSESSMENT COMPLETE")