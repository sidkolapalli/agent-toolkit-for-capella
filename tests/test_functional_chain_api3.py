#!/usr/bin/env python
"""Test creating FunctionalChains via capabilities."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== FUNCTIONAL CHAIN CREATION TEST ===\n")

la = model.la
capability_pkg = la.capability_pkg

# Check if there are existing capabilities
print("Existing capabilities:")
for cap in capability_pkg.capabilities:
    print(f"  - {cap.name}")
print()

# Create a new capability
print("Creating new capability...")
new_capability = capability_pkg.capabilities.create(name="Test_Capability_With_Chain")
print(f"  Created: {new_capability.name}")
print(f"  UUID: {new_capability.uuid}")
print()

# Check functional_chains on the capability
print("Checking functional_chains on capability:")
if hasattr(new_capability, 'functional_chains'):
    chains = new_capability.functional_chains
    print(f"  Type: {type(chains)}")
    print(f"  Count: {len(chains)}")
    print(f"  Is coupled: {chains.is_coupled()}")
    
    # Try creating a functional chain
    print("\n  Attempting to create functional chain...")
    try:
        new_chain = chains.create(name="Test_FunctionalChain_In_Capability")
        print(f"  [OK] Created: {new_chain.name}")
        print(f"       UUID: {new_chain.uuid}")
        print(f"       Type: {type(new_chain).__name__}")
        
        # Check if it appears in all_functional_chains
        print("\n  Checking la.all_functional_chains:")
        print(f"    Count: {len(la.all_functional_chains)}")
        for chain in la.all_functional_chains:
            print(f"      - {chain.name}")
            
    except Exception as e:
        print(f"  [FAILED] {e}")
else:
    print("  Capability doesn't have functional_chains attribute")

# Alternative: Try direct creation in function_pkg
print("\n\nAlternative: Check function_pkg for functional chains:")
function_pkg = la.function_pkg
print(f"  Type: {type(function_pkg)}")

# Check for functional_chains attribute
if hasattr(function_pkg, 'functional_chains'):
    chains = function_pkg.functional_chains
    print(f"  function_pkg.functional_chains exists!")
    print(f"    Type: {type(chains)}")
    print(f"    Is coupled: {chains.is_coupled()}")
    
    if chains.is_coupled():
        try:
            new_chain = chains.create(name="Test_FunctionalChain_In_FunctionPkg")
            print(f"    [OK] Created: {new_chain.name}")
        except Exception as e:
            print(f"    [FAILED] {e}")

print("\n=== END TEST ===")