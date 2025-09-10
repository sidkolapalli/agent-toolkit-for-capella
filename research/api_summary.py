#!/usr/bin/env python
"""
API Summary - Key Findings from Comprehensive Assessment
========================================================

Focused exploration of the most important missing ARCADIA elements
and advanced features we can implement.
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = os.getenv("CAPELLA_MODEL_PATH", r"<local-path>")
model = capellambse.MelodyModel(model_path)

print("CAPELLAMBSE API SUMMARY - KEY FINDINGS")
print("=" * 50)

# Test key discoveries from the comprehensive assessment
key_discoveries = []

# 1. System Missions (Alternative to Scenarios)
print("\n1. SYSTEM MISSIONS (Scenario Alternative):")
try:
    missions = model.sa.all_missions
    print(f"   Found: {len(missions)} existing missions")
    print(f"   Can create: {hasattr(missions, 'create')}")
    
    if hasattr(missions, 'create'):
        test_mission = missions.create(name="API_Test_Mission")
        print(f"   Created: {test_mission.name} ({test_mission.__class__.__name__})")
        key_discoveries.append(("Mission", "sa.all_missions.create()"))
except Exception as e:
    print(f"   Error: {e}")

# 2. Data Types and Values
print("\n2. DATA MODELING:")
try:
    if hasattr(model.la, 'data_pkg') and model.la.data_pkg:
        data_pkg = model.la.data_pkg
        
        # Data Types
        if hasattr(data_pkg, 'data_types'):
            data_types = data_pkg.data_types
            print(f"   Data Types: {len(data_types)} existing, can create: {hasattr(data_types, 'create')}")
            if hasattr(data_types, 'create'):
                test_type = data_types.create(name="API_Test_DataType")
                print(f"   Created: {test_type.name} ({test_type.__class__.__name__})")
                key_discoveries.append(("DataType", "la.data_pkg.data_types.create()"))
        
        # Classes
        if hasattr(data_pkg, 'classes'):
            classes = data_pkg.classes
            print(f"   Classes: {len(classes)} existing, can create: {hasattr(classes, 'create')}")
            if hasattr(classes, 'create'):
                test_class = classes.create(name="API_Test_Class")
                print(f"   Created: {test_class.name} ({test_class.__class__.__name__})")
                key_discoveries.append(("Class", "la.data_pkg.classes.create()"))
        
        # Exchange Items
        if hasattr(data_pkg, 'exchange_items'):
            exchange_items = data_pkg.exchange_items
            print(f"   Exchange Items: {len(exchange_items)} existing, can create: {hasattr(exchange_items, 'create')}")
            if hasattr(exchange_items, 'create'):
                test_item = exchange_items.create(name="API_Test_ExchangeItem")
                print(f"   Created: {test_item.name} ({test_item.__class__.__name__})")
                key_discoveries.append(("ExchangeItem", "la.data_pkg.exchange_items.create()"))
                
except Exception as e:
    print(f"   Error: {e}")

# 3. State Machines
print("\n3. STATE MACHINES (Behavioral Modeling):")
try:
    # Check each layer for state machines
    for layer_name, layer in [("OA", model.oa), ("SA", model.sa), ("LA", model.la), ("PA", model.pa)]:
        if hasattr(layer, 'entity_pkg') and layer.entity_pkg:
            if hasattr(layer.entity_pkg, 'state_machines'):
                state_machines = layer.entity_pkg.state_machines
                print(f"   {layer_name} State Machines: {len(state_machines)} existing, can create: {hasattr(state_machines, 'create')}")
                if hasattr(state_machines, 'create'):
                    test_sm = state_machines.create(name=f"API_Test_{layer_name}_StateMachine")
                    print(f"   Created: {test_sm.name} ({test_sm.__class__.__name__})")
                    key_discoveries.append((f"{layer_name}StateMachine", f"{layer_name.lower()}.entity_pkg.state_machines.create()"))
except Exception as e:
    print(f"   Error: {e}")

# 4. Property Values (Configuration Management)
print("\n4. PROPERTY VALUES (Configuration):")
try:
    for layer_name, layer in [("OA", model.oa), ("SA", model.sa), ("LA", model.la), ("PA", model.pa)]:
        if hasattr(layer, 'entity_pkg') and layer.entity_pkg:
            if hasattr(layer.entity_pkg, 'property_values'):
                prop_values = layer.entity_pkg.property_values
                print(f"   {layer_name} Property Values: {len(prop_values)} existing, can create: {hasattr(prop_values, 'create')}")
                if hasattr(prop_values, 'create'):
                    test_pv = prop_values.create(name=f"API_Test_{layer_name}_PropertyValue")
                    print(f"   Created: {test_pv.name} ({test_pv.__class__.__name__})")
                    key_discoveries.append((f"{layer_name}PropertyValue", f"{layer_name.lower()}.entity_pkg.property_values.create()"))
                    break  # Only test one layer
except Exception as e:
    print(f"   Error: {e}")

# 5. Constraints (Requirements-like)
print("\n5. CONSTRAINTS (Requirements Alternative):")
try:
    for layer_name, layer in [("OA", model.oa), ("SA", model.sa), ("LA", model.la), ("PA", model.pa)]:
        if hasattr(layer, 'entity_pkg') and layer.entity_pkg:
            if hasattr(layer.entity_pkg, 'constraints'):
                constraints = layer.entity_pkg.constraints
                print(f"   {layer_name} Constraints: {len(constraints)} existing, can create: {hasattr(constraints, 'create')}")
                if hasattr(constraints, 'create'):
                    test_constraint = constraints.create(name=f"API_Test_{layer_name}_Constraint")
                    print(f"   Created: {test_constraint.name} ({test_constraint.__class__.__name__})")
                    key_discoveries.append((f"{layer_name}Constraint", f"{layer_name.lower()}.entity_pkg.constraints.create()"))
                    break  # Only test one layer
except Exception as e:
    print(f"   Error: {e}")

# 6. Exchange Categories (Connection Modeling)
print("\n6. EXCHANGE CATEGORIES:")
try:
    for layer_name, layer in [("OA", model.oa), ("SA", model.sa), ("LA", model.la), ("PA", model.pa)]:
        if hasattr(layer, 'entity_pkg') and layer.entity_pkg:
            if hasattr(layer.entity_pkg, 'exchange_categories'):
                exchange_cats = layer.entity_pkg.exchange_categories
                print(f"   {layer_name} Exchange Categories: {len(exchange_cats)} existing, can create: {hasattr(exchange_cats, 'create')}")
                if hasattr(exchange_cats, 'create'):
                    test_cat = exchange_cats.create(name=f"API_Test_{layer_name}_ExchangeCategory")
                    print(f"   Created: {test_cat.name} ({test_cat.__class__.__name__})")
                    key_discoveries.append((f"{layer_name}ExchangeCategory", f"{layer_name.lower()}.entity_pkg.exchange_categories.create()"))
                    break  # Only test one layer
except Exception as e:
    print(f"   Error: {e}")

# Summary of discoveries
print(f"\n" + "=" * 50)
print("KEY DISCOVERY SUMMARY")
print("=" * 50)

print(f"Discovered {len(key_discoveries)} new element types:")
for element_type, api_path in key_discoveries:
    print(f"  + {element_type}: {api_path}")

# Implementation priority
print(f"\nIMPLEMENTATION PRIORITY:")
print("1. HIGH PRIORITY (ARCADIA Gaps):")
print("   - Mission (System-level scenario alternative)")
print("   - DataType, Class, ExchangeItem (Data modeling)")
print("   - StateMachine (Behavioral modeling)")

print("2. MEDIUM PRIORITY (Enhanced Features):")
print("   - PropertyValue (Configuration management)")
print("   - Constraint (Requirements alternative)")
print("   - ExchangeCategory (Connection modeling)")

print("3. CURRENT STATUS:")
current_elements = 22  # Our current implementation count
total_possible = current_elements + len(key_discoveries)
coverage = (current_elements / total_possible) * 100

print(f"   Current elements: {current_elements}")
print(f"   Newly discovered: {len(key_discoveries)}")
print(f"   Total possible: {total_possible}")
print(f"   Current coverage: {coverage:.1f}%")
print(f"   With new elements: 100.0%")

# Save model
try:
    model.save()
    print(f"\nModel saved with {len(key_discoveries)} test elements")
except Exception as e:
    print(f"\nModel save failed: {e}")

print(f"\nRECOMMENDATION:")
print("Implement Mission as scenario alternative to achieve near-100% ARCADIA coverage")