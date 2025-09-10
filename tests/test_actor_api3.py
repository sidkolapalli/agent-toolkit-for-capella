#!/usr/bin/env python
"""Discover Actor creation API - check component_pkg structure."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== COMPONENT PKG STRUCTURE ===\n")

la = model.la
comp_pkg = la.component_pkg

print(f"LogicalComponentPkg: {comp_pkg}")
print(f"Type: {type(comp_pkg)}")
print()

# List all attributes
print("All attributes containing 'actor':")
for attr in dir(comp_pkg):
    if 'actor' in attr.lower() and not attr.startswith('_'):
        value = getattr(comp_pkg, attr, None)
        if value is not None:
            print(f"  .{attr}: {type(value).__name__}")
print()

# Check for components attribute
print("Checking component_pkg.components:")
if hasattr(comp_pkg, 'components'):
    components = comp_pkg.components
    print(f"  Type: {type(components)}")
    print(f"  Count: {len(components)}")
    print(f"  Is coupled: {components.is_coupled()}")
    
    # Check what types are in components
    if len(components) > 0:
        print("  Component types:")
        for comp in components[:5]:  # First 5
            print(f"    - {type(comp).__name__}: {comp.name}")
print()

# Check for owned_entities
print("Checking for entities or owned_entities:")
for attr in ['entities', 'owned_entities', 'owned_actors']:
    if hasattr(comp_pkg, attr):
        value = getattr(comp_pkg, attr)
        print(f"  component_pkg.{attr}:")
        print(f"    Type: {type(value)}")
        if hasattr(value, '__len__'):
            print(f"    Count: {len(value)}")
        if hasattr(value, 'is_coupled'):
            print(f"    Is coupled: {value.is_coupled()}")
        if hasattr(value, 'create'):
            print(f"    [OK] Has create method!")
print()

# Try different ways to create an actor
print("Attempting different creation patterns:")
print()

# Pattern 1: Create as component then set as actor
print("Pattern 1: Create LogicalComponent with is_actor=True")
try:
    new_comp = comp_pkg.components.create(name="Test_Actor_As_Component", is_actor=True)
    print(f"  [OK] Created: {new_comp.name} (is_actor={getattr(new_comp, 'is_actor', 'N/A')})")
except Exception as e:
    print(f"  [FAILED] {e}")
print()

# Pattern 2: Create with xtype
print("Pattern 2: Create with xtype='org.polarsys.capella.core.data.la:LogicalActor'")
try:
    new_actor = comp_pkg.components.create(
        name="Test_Actor_With_Xtype",
        xtype="org.polarsys.capella.core.data.la:LogicalActor"
    )
    print(f"  [OK] Created: {new_actor.name} (type={type(new_actor).__name__})")
except Exception as e:
    print(f"  [FAILED] {e}")
print()

# Check what xtype actors have
print("Checking existing actor xtype:")
if len(la.all_actors) > 0:
    actor = la.all_actors[0]
    print(f"  Existing actor: {actor.name}")
    print(f"  xtype: {actor.xtype}")
    print(f"  Type: {type(actor).__name__}")

print("\n=== END DISCOVERY ===")