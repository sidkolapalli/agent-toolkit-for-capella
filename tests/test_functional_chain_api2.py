#!/usr/bin/env python
"""Test script to discover correct FunctionalChain creation API in capellambse - more detailed."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== DETAILED FUNCTIONAL CHAIN API DISCOVERY ===\n")

la = model.la

# Check capability_pkg structure
print("Checking capability_pkg structure:")
capability_pkg = la.capability_pkg
print(f"  Type: {type(capability_pkg)}")
print(f"  All attributes:")
for attr in dir(capability_pkg):
    if not attr.startswith('_'):
        value = getattr(capability_pkg, attr, None)
        if value is not None and not callable(value):
            value_type = type(value).__name__
            print(f"    .{attr}: {value_type}")
            # Check if it's a list that might contain chains
            if hasattr(value, '__len__') and 'List' in value_type:
                try:
                    if len(value) > 0:
                        print(f"      First item type: {type(value[0]).__name__}")
                except:
                    pass
                if hasattr(value, 'is_coupled'):
                    print(f"      Is coupled: {value.is_coupled()}")
                if hasattr(value, 'create'):
                    print(f"      [OK] Has create method!")
print()

# Check for capability realizations
if hasattr(capability_pkg, 'capabilities'):
    print("Found capability_pkg.capabilities:")
    capabilities = capability_pkg.capabilities
    print(f"  Type: {type(capabilities)}")
    print(f"  Count: {len(capabilities)}")
    if hasattr(capabilities, 'is_coupled'):
        print(f"  Is coupled: {capabilities.is_coupled()}")
    if hasattr(capabilities, 'create'):
        print(f"  [OK] Has create method!")
        # Try creating a capability
        try:
            new_cap = capabilities.create(name="Test_Capability")
            print(f"  Created capability: {new_cap.name}")
            
            # Check if capability has functional_chains
            if hasattr(new_cap, 'functional_chains'):
                print(f"    Capability has functional_chains: {type(new_cap.functional_chains)}")
        except Exception as e:
            print(f"  [FAILED] {e}")
print()

# Check function_pkg for chains
print("Checking function_pkg for functional chains:")
if hasattr(la, 'function_pkg'):
    function_pkg = la.function_pkg
    print(f"  Type: {type(function_pkg)}")
    for attr in dir(function_pkg):
        if 'chain' in attr.lower() and not attr.startswith('_'):
            value = getattr(function_pkg, attr)
            print(f"    .{attr}: {type(value).__name__}")
print()

# Check all_functional_chains more carefully
print("Analyzing all_functional_chains:")
all_chains = la.all_functional_chains
print(f"  Type: {type(all_chains)}")
print(f"  _coupled: {getattr(all_chains, '_coupled', 'N/A')}")

# Try to find where functional chains should be created
print("\nSearching for FunctionalChain in model structure...")
import capellambse.metamodel
for name in dir(capellambse.metamodel.fa):
    if 'chain' in name.lower():
        print(f"  Found in fa: {name}")

print("\n=== END DISCOVERY ===")