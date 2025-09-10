#!/usr/bin/env python
"""Test script to discover correct Actor creation API in capellambse."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== ACTOR API DISCOVERY ===\n")

# Get logical architecture
la = model.la
print(f"LogicalArchitecture: {la}")
print(f"Type: {type(la)}")
print()

# Try to find actor-related attributes
print("Actor-related attributes in la:")
for attr in dir(la):
    if 'actor' in attr.lower():
        value = getattr(la, attr)
        print(f"  {attr}: {type(value).__name__}")
        if hasattr(value, '__len__'):
            try:
                print(f"    Length: {len(value)}")
            except:
                pass
print()

# Check if there's an actors collection
if hasattr(la, 'actors'):
    actors = la.actors
    print(f"la.actors exists: {type(actors)}")
    print(f"  Number of existing actors: {len(actors)}")
    
    # Check available methods
    print(f"  Available methods: {[m for m in dir(actors) if not m.startswith('_') and callable(getattr(actors, m))]}")
    
    # Check if it supports create
    if hasattr(actors, 'create'):
        print(f"  ✓ actors.create method exists!")
        
        # Try creating an actor
        try:
            new_actor = actors.create(name="Test_Actor_Discovery")
            print(f"  Created actor: {new_actor}")
            print(f"  Actor UUID: {new_actor.uuid}")
            print(f"  Actor name: {new_actor.name}")
            
            # Don't save - this is just discovery
            print("\n  (Not saving to preserve original model)")
        except Exception as e:
            print(f"  Error creating actor: {e}")
print()

# Check all_actors
if hasattr(la, 'all_actors'):
    all_actors = la.all_actors
    print(f"la.all_actors exists: {type(all_actors)}")
    print(f"  Number of existing actors: {len(all_actors)}")
    print(f"  Available methods: {[m for m in dir(all_actors) if not m.startswith('_') and callable(getattr(all_actors, m))]}")
print()

# Check for other actor collections or methods
print("Checking for other actor-related collections:")
for attr in ['entity_pkg', 'actor_pkg', 'system_actors']:
    if hasattr(la, attr):
        value = getattr(la, attr)
        print(f"  la.{attr}: {type(value)}")
        
        # Check sub-attributes
        for sub_attr in dir(value):
            if 'actor' in sub_attr.lower() and not sub_attr.startswith('_'):
                sub_value = getattr(value, sub_attr)
                print(f"    .{sub_attr}: {type(sub_value).__name__}")
                if hasattr(sub_value, 'create'):
                    print(f"      ✓ Has create method!")

print("\n=== END DISCOVERY ===")