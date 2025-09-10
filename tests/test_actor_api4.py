#!/usr/bin/env python
"""Verify Actor creation API - test is_actor flag."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== ACTOR CREATION VERIFICATION ===\n")

la = model.la
comp_pkg = la.component_pkg

# Check initial state
print("Initial state:")
print(f"  all_actors count: {len(la.all_actors)}")
print(f"  components count: {len(comp_pkg.components)}")
for actor in la.all_actors:
    print(f"    Actor: {actor.name} (is_actor={actor.is_actor})")
print()

# Create a component with is_actor=True
print("Creating component with is_actor=True...")
new_actor = comp_pkg.components.create(name="Test_Actor_Verified", is_actor=True)
print(f"  Created: {new_actor.name}")
print(f"  UUID: {new_actor.uuid}")
print(f"  is_actor: {new_actor.is_actor}")
print(f"  Type: {type(new_actor).__name__}")
print()

# Check if it appears in all_actors
print("After creation:")
print(f"  all_actors count: {len(la.all_actors)}")
print(f"  components count: {len(comp_pkg.components)}")
print()

print("All actors now:")
for actor in la.all_actors:
    print(f"  - {actor.name} (is_actor={actor.is_actor})")
print()

# Test persistence
print("Testing persistence...")
try:
    model.save()
    print("  [OK] Model saved successfully")
    
    # Reload and verify
    print("\nReloading model...")
    model2 = capellambse.MelodyModel(model_path)
    la2 = model2.la
    
    print(f"  all_actors count after reload: {len(la2.all_actors)}")
    print("  Actors after reload:")
    for actor in la2.all_actors:
        print(f"    - {actor.name}")
        
except Exception as e:
    print(f"  [FAILED] {e}")

print("\n=== END VERIFICATION ===")