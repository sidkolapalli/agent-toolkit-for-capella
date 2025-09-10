#!/usr/bin/env python
"""
Simplified API Research: Scenario Creation and Dependency Analysis
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

print("SCENARIO AND DEPENDENCY API RESEARCH")
print("====================================")

# Research scenarios in each layer
def explore_layer_attributes(layer_name, layer_obj):
    print(f"\n{layer_name} Layer Attributes:")
    attrs = [attr for attr in dir(layer_obj) if not attr.startswith('_')]
    scenario_related = [attr for attr in attrs if 'scenario' in attr.lower()]
    
    print(f"  All attributes: {len(attrs)}")
    print(f"  Scenario-related: {scenario_related}")
    
    # Check each scenario-related attribute
    for attr in scenario_related:
        try:
            obj = getattr(layer_obj, attr)
            print(f"    {attr}: {type(obj)}")
            if hasattr(obj, '__len__'):
                print(f"      Count: {len(obj)}")
            if hasattr(obj, 'create'):
                print(f"      Has create method: YES")
                
                # Try creating a test scenario
                try:
                    test_scenario = obj.create(name=f"Test_{layer_name}_Scenario")
                    print(f"      Creation test: SUCCESS - {test_scenario.name}")
                    return True, f"{layer_name.lower()}.{attr}.create()"
                except Exception as e:
                    print(f"      Creation test: FAILED - {e}")
            else:
                print(f"      Has create method: NO")
        except Exception as e:
            print(f"    {attr}: ERROR - {e}")
    
    return False, None

# Test each layer
oa_result = explore_layer_attributes("OA", model.oa)
sa_result = explore_layer_attributes("SA", model.sa) 
la_result = explore_layer_attributes("LA", model.la)

print(f"\nSCENARIO RESEARCH SUMMARY:")
print(f"OA Scenarios: {oa_result}")
print(f"SA Scenarios: {sa_result}")
print(f"LA Scenarios: {la_result}")

# Research dependency analysis
print(f"\nDEPENDENCY ANALYSIS RESEARCH:")

# Get a test component
components = list(model.la.all_components)
if components:
    test_comp = components[0]
    print(f"Test component: {test_comp.name}")
    
    # Look for relationship attributes
    rel_attrs = []
    for attr in dir(test_comp):
        if not attr.startswith('_'):
            try:
                value = getattr(test_comp, attr)
                if 'interface' in attr.lower() or 'function' in attr.lower() or 'exchange' in attr.lower():
                    rel_attrs.append((attr, type(value)))
                    if hasattr(value, '__len__'):
                        print(f"  {attr}: {type(value)} (count: {len(value)})")
                    else:
                        print(f"  {attr}: {type(value)}")
            except:
                pass
    
    print(f"Found {len(rel_attrs)} relationship attributes")
else:
    print("No components found for dependency testing")

# Try to save
try:
    model.save()
    print("Model save: SUCCESS")
except Exception as e:
    print(f"Model save: FAILED - {e}")

print("\nRESEARCH COMPLETE")