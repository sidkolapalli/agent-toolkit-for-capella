#!/usr/bin/env python
"""Test script to discover correct Actor creation API in capellambse - more detailed."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== DETAILED ACTOR API DISCOVERY ===\n")

# Get logical architecture
la = model.la
print(f"LogicalArchitecture: {la}")
print()

# Check all_actors in detail
print("Checking la.all_actors:")
all_actors = la.all_actors
print(f"  Type: {type(all_actors)}")
print(f"  Is coupled: {all_actors.is_coupled()}")
print(f"  Count: {len(all_actors)}")

# List existing actors
if len(all_actors) > 0:
    print("  Existing actors:")
    for actor in all_actors:
        print(f"    - {actor.name} ({actor.uuid})")
print()

# Try to create an actor using all_actors
print("Attempting to create actor via all_actors.create():")
try:
    new_actor = all_actors.create(name="Test_Actor_Via_AllActors")
    print(f"  [OK]")
except Exception as e:
    print(f"  [FAILED] {e}")
    print(f"    Exception type: {type(e).__name__}")
print()

# Check for component_pkg.actors
print("Checking la.component_pkg for actor collections:")
comp_pkg = la.component_pkg
print(f"  component_pkg type: {type(comp_pkg)}")
for attr in dir(comp_pkg):
    if 'actor' in attr.lower() and not attr.startswith('_'):
        value = getattr(comp_pkg, attr, None)
        if value is not None:
            print(f"    .{attr}: {type(value).__name__}")
            if hasattr(value, 'is_coupled'):
                print(f"      Is coupled: {value.is_coupled()}")
            if hasattr(value, 'create'):
                print(f"      [OK] Has create method!")
                # Try creating
                try:
                    test_actor = value.create(name="Test_Actor_Via_ComponentPkg")
                    print(f"      [OK] Created: {test_actor.name}")
                except Exception as e:
                    print(f"      [FAILED] Create: {e}")
print()

# Check for actors directly in component_pkg
if hasattr(comp_pkg, 'actors'):
    print("Found component_pkg.actors:")
    actors = comp_pkg.actors
    print(f"  Type: {type(actors)}")
    print(f"  Count: {len(actors)}")
    if hasattr(actors, 'is_coupled'):
        print(f"  Is coupled: {actors.is_coupled()}")
    if hasattr(actors, 'create'):
        print("  [OK] Has create method!")
        try:
            new_actor = actors.create(name="Test_Actor_Via_ComponentPkg_Actors")
            print(f"  [OK]")
        except Exception as e:
            print(f"  [FAILED] Create: {e}")
print()

# Alternative: Check for entity pattern
print("Checking for entity-based patterns (LogicalActor as Entity):")
if hasattr(comp_pkg, 'entities'):
    entities = comp_pkg.entities
    print(f"  component_pkg.entities exists: {type(entities)}")
    if hasattr(entities, 'create'):
        print("  [OK] Has create method!")

print("\n=== END DISCOVERY ===")