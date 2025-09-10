#!/usr/bin/env python
"""Test script to discover correct FunctionalChain creation API in capellambse."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== FUNCTIONAL CHAIN API DISCOVERY ===\n")

la = model.la
print(f"LogicalArchitecture: {la}")
print()

# Check capability_pkg
print("Checking la.capability_pkg:")
if hasattr(la, 'capability_pkg'):
    capability_pkg = la.capability_pkg
    print(f"  Type: {type(capability_pkg)}")
    print(f"  Available attributes containing 'chain':")
    for attr in dir(capability_pkg):
        if not attr.startswith('_') and 'chain' in attr.lower():
            value = getattr(capability_pkg, attr)
            print(f"    .{attr}: {type(value).__name__}")
            if hasattr(value, '__len__'):
                try:
                    print(f"      Count: {len(value)}")
                except:
                    pass
            if hasattr(value, 'is_coupled'):
                print(f"      Is coupled: {value.is_coupled()}")
            if hasattr(value, 'create'):
                print(f"      [OK] Has create method!")
print()

# Check for chains/functional_chains
for attr_name in ['chains', 'functional_chains']:
    if hasattr(capability_pkg, attr_name):
        chains = getattr(capability_pkg, attr_name)
        print(f"capability_pkg.{attr_name}:")
        print(f"  Type: {type(chains)}")
        print(f"  Count: {len(chains)}")
        print(f"  Is coupled: {chains.is_coupled()}")
        
        # Try creating
        print(f"\n  Attempting to create via {attr_name}.create()...")
        try:
            new_chain = chains.create(name=f"Test_FunctionalChain_Via_{attr_name}")
            print(f"  [OK] Created: {new_chain.name}")
            print(f"       UUID: {new_chain.uuid}")
            print(f"       Type: {type(new_chain).__name__}")
        except Exception as e:
            print(f"  [FAILED] {e}")
        print()

# Check all_functional_chains
print("Checking la.all_functional_chains:")
if hasattr(la, 'all_functional_chains'):
    all_chains = la.all_functional_chains
    print(f"  Type: {type(all_chains)}")
    print(f"  Count: {len(all_chains)}")
    print(f"  Is coupled: {all_chains.is_coupled()}")
    
    # List existing chains
    if len(all_chains) > 0:
        print("  Existing chains:")
        for chain in all_chains[:5]:  # First 5
            print(f"    - {chain.name}")

print("\n=== END DISCOVERY ===")