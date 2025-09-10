#!/usr/bin/env python
"""
API Research: Scenario Creation and Dependency Analysis
TDD Phase 2 (RESEARCH) - Discovering capellambse APIs for final 100% ARCADIA capability

This script systematically explores capellambse APIs to find:
1. Scenario creation APIs (OperationalScenario, SystemScenario, LogicalScenario)
2. Dependency analysis APIs (element relationships, impacts, traversal)
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

print("=== SCENARIO AND DEPENDENCY API RESEARCH ===\n")
print("TDD Phase 2 (RESEARCH): Finding APIs for 100% ARCADIA capability\n")

# =====================================================
# SCENARIO API RESEARCH
# =====================================================

print("1. SCENARIO API RESEARCH")
print("=" * 50)

def explore_scenario_apis(layer_name, layer_obj):
    """Explore a layer for scenario-related APIs."""
    print(f"\n1.{layer_name}: Exploring {layer_name} for scenario APIs...")
    
    # Look for scenario_pkg
    if hasattr(layer_obj, 'scenario_pkg') and layer_obj.scenario_pkg:
        scenario_pkg = layer_obj.scenario_pkg
        print(f"   [FOUND] {layer_name}.scenario_pkg: {type(scenario_pkg)}")
        
        # Look for scenarios collection
        if hasattr(scenario_pkg, 'scenarios'):
            scenarios = scenario_pkg.scenarios
            print(f"   [FOUND] scenarios collection: {type(scenarios)} (count: {len(scenarios)})")
            
            if hasattr(scenarios, 'create'):
                print(f"   [API] scenarios.create() method available")
                
                # Test scenario creation
                try:
                    test_scenario = scenarios.create(name=f"Research_{layer_name}_Scenario")
                    print(f"   [SUCCESS] Created: {test_scenario.name} ({test_scenario.uuid})")
                    scenario_api = f"{layer_name.lower()}.scenario_pkg.scenarios.create()"
                    return True, scenario_api
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
                    return False, None
            else:
                print(f"   [MISSING] No create method on scenarios")
        else:
            print(f"   [MISSING] No scenarios attribute in scenario_pkg")
    else:
        print(f"   [MISSING] No scenario_pkg found in {layer_name}")
    
    # Look for alternative scenario attributes
    scenario_attrs = ['scenarios', 'operational_scenarios', 'system_scenarios', 'logical_scenarios']
    for attr in scenario_attrs:
        if hasattr(layer_obj, attr):
            attr_obj = getattr(layer_obj, attr)
            print(f"   [FOUND] Alternative: {layer_name}.{attr}: {type(attr_obj)}")
            if hasattr(attr_obj, 'create'):
                try:
                    test_scenario = attr_obj.create(name=f"Research_{layer_name}_{attr}")
                    print(f"   [SUCCESS] Created via {attr}: {test_scenario.name}")
                    return True, f"{layer_name.lower()}.{attr}.create()"
                except Exception as e:
                    print(f"   [FAILED] {attr} create failed: {e}")
    
    return False, None

# Test each layer for scenario APIs
scenario_results = {}

# Operational Architecture (OA)
oa_success, oa_api = explore_scenario_apis("OA", model.oa)
scenario_results["OperationalScenario"] = (oa_success, oa_api)

# System Architecture (SA)  
sa_success, sa_api = explore_scenario_apis("SA", model.sa)
scenario_results["SystemScenario"] = (sa_success, sa_api)

# Logical Architecture (LA)
la_success, la_api = explore_scenario_apis("LA", model.la)
scenario_results["LogicalScenario"] = (la_success, la_api)

print(f"\nScenario API Research Summary:")
for scenario_type, (success, api) in scenario_results.items():
    status = "✅ FOUND" if success else "❌ NOT FOUND"
    api_info = f" - {api}" if api else ""
    print(f"  {status} {scenario_type}{api_info}")

# =====================================================
# DEPENDENCY ANALYSIS API RESEARCH  
# =====================================================

print(f"\n\n2. DEPENDENCY ANALYSIS API RESEARCH")
print("=" * 50)

def explore_dependency_apis():
    """Explore dependency and relationship APIs."""
    print("\n2.1: Exploring element relationship APIs...")
    
    # Get a test component to analyze
    components = list(model.la.all_components)
    if not components:
        print("   [ERROR] No components found for dependency testing")
        return False, []
    
    test_component = components[0]
    print(f"   [TEST] Using component: {test_component.name} ({test_component.uuid})")
    
    dependency_methods = []
    
    # Explore common relationship attributes
    relationship_attrs = [
        'incoming_links', 'outgoing_links', 'related_exchanges', 'allocated_functions',
        'provided_interfaces', 'required_interfaces', 'interfaces', 'exchanges',
        'dependencies', 'dependents', 'related_elements', 'connections',
        'incoming_flows', 'outgoing_flows', 'flows', 'links'
    ]
    
    for attr in relationship_attrs:
        if hasattr(test_component, attr):
            try:
                attr_value = getattr(test_component, attr)
                if hasattr(attr_value, '__len__'):
                    count = len(attr_value)
                    print(f"   [FOUND] {attr}: {type(attr_value)} (count: {count})")
                    dependency_methods.append(f"element.{attr}")
                else:
                    print(f"   [FOUND] {attr}: {type(attr_value)}")
                    dependency_methods.append(f"element.{attr}")
            except Exception as e:
                print(f"   [ERROR] {attr}: {e}")
    
    # Explore model-level dependency methods
    print(f"\n2.2: Exploring model-level dependency methods...")
    
    model_methods = [
        'get_all_related_objects', 'get_references', 'find_references',
        'get_dependencies', 'get_dependents', 'trace_requirements',
        'analyze_impacts', 'get_relationships'
    ]
    
    for method in model_methods:
        if hasattr(model, method):
            print(f"   [FOUND] model.{method}")
            dependency_methods.append(f"model.{method}")
    
    # Test specific capellambse relationship patterns
    print(f"\n2.3: Testing relationship navigation patterns...")
    
    try:
        # Test interface relationships
        if hasattr(test_component, 'provided_interfaces'):
            provided = test_component.provided_interfaces
            print(f"   [SUCCESS] Provided interfaces: {len(provided)}")
            dependency_methods.append("component.provided_interfaces")
        
        if hasattr(test_component, 'required_interfaces'):
            required = test_component.required_interfaces  
            print(f"   [SUCCESS] Required interfaces: {len(required)}")
            dependency_methods.append("component.required_interfaces")
        
        # Test function allocation relationships
        if hasattr(test_component, 'allocated_functions'):
            allocated = test_component.allocated_functions
            print(f"   [SUCCESS] Allocated functions: {len(allocated)}")
            dependency_methods.append("component.allocated_functions")
            
        # Test parent/child relationships
        if hasattr(test_component, 'parts'):
            parts = test_component.parts
            print(f"   [SUCCESS] Component parts: {len(parts)}")
            dependency_methods.append("component.parts")
            
        if hasattr(test_component, 'parent'):
            parent = test_component.parent
            print(f"   [SUCCESS] Component parent: {parent}")
            dependency_methods.append("component.parent")
    
    except Exception as e:
        print(f"   [ERROR] Relationship testing failed: {e}")
    
    return len(dependency_methods) > 0, dependency_methods

dep_success, dep_methods = explore_dependency_apis()

print(f"\nDependency Analysis API Summary:")
if dep_success:
    print(f"   ✅ FOUND {len(dep_methods)} dependency methods:")
    for method in dep_methods[:10]:  # Show first 10
        print(f"     - {method}")
    if len(dep_methods) > 10:
        print(f"     ... and {len(dep_methods) - 10} more")
else:
    print(f"   ❌ NO dependency methods found")

# =====================================================
# SAVE AND SUMMARY
# =====================================================

print(f"\n\n3. SAVING RESEARCH RESULTS")
print("=" * 50)

try:
    model.save()
    print("   [SUCCESS] Model saved with research elements")
except Exception as e:
    print(f"   [FAILED] Save failed: {e}")

print(f"\n=== RESEARCH SUMMARY ===")
print(f"Scenario APIs Found: {sum(1 for success, _ in scenario_results.values() if success)}/3")
print(f"Dependency Methods Found: {len(dep_methods) if dep_success else 0}")

scenario_success_rate = (sum(1 for success, _ in scenario_results.values() if success) / 3) * 100
dependency_readiness = "Ready" if dep_success else "Needs Alternative Approach"

print(f"\nScenario Implementation Readiness: {scenario_success_rate:.0f}%")
print(f"Dependency Analysis Readiness: {dependency_readiness}")

if scenario_success_rate == 100 and dep_success:
    print(f"\n🎉 100% RESEARCH SUCCESS - Ready for Phase 3 (GREEN) Implementation!")
elif scenario_success_rate >= 67 and dep_success:
    print(f"\n✅ HIGH READINESS - Proceed to implementation with minor gaps")
else:
    print(f"\n⚠️ RESEARCH GAPS - Need alternative approaches for missing APIs")

print(f"\n=== IMPLEMENTATION GUIDE ===")
print(f"For Phase 3 (GREEN) implementation:")

for scenario_type, (success, api) in scenario_results.items():
    if success:
        print(f"✅ {scenario_type}: Use {api}")
    else:
        print(f"❌ {scenario_type}: NEEDS ALTERNATIVE APPROACH")

if dep_success:
    print(f"✅ Dependency Analysis: Use combination of element relationship methods")
    print(f"   Recommended: provided_interfaces, required_interfaces, allocated_functions, parts, parent")
else:
    print(f"❌ Dependency Analysis: NEEDS CUSTOM IMPLEMENTATION")