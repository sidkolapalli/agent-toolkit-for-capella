#!/usr/bin/env python
"""Test script to discover correct FunctionalExchange creation API in capellambse."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== FUNCTIONAL EXCHANGE API DISCOVERY ===\n")

la = model.la
print(f"LogicalArchitecture: {la}")
print()

# Check for functional exchanges
print("Checking la.all_functional_exchanges:")
if hasattr(la, 'all_functional_exchanges'):
    exchanges = la.all_functional_exchanges
    print(f"  Type: {type(exchanges)}")
    print(f"  Count: {len(exchanges)}")
    if hasattr(exchanges, 'is_coupled'):
        print(f"  Is coupled: {exchanges.is_coupled()}")
    if hasattr(exchanges, 'create'):
        print(f"  [OK] Has create method!")
    
    # List some existing exchanges
    if len(exchanges) > 0:
        print("  Existing exchanges (first 5):")
        for ex in exchanges[:5]:
            print(f"    - {ex.name}")
            # Check what attributes an exchange has
            if exchanges.index(ex) == 0:
                print(f"      Attributes: {[a for a in dir(ex) if not a.startswith('_')][:10]}")
print()

# Check function_pkg for exchanges
print("Checking function_pkg for exchanges:")
if hasattr(la, 'function_pkg'):
    function_pkg = la.function_pkg
    print(f"  Type: {type(function_pkg)}")
    
    # Look for exchange-related attributes
    for attr in dir(function_pkg):
        if 'exchange' in attr.lower() and not attr.startswith('_'):
            value = getattr(function_pkg, attr)
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

# Check for functions to connect
print("Checking functions to connect with exchanges:")
if hasattr(la, 'all_functions'):
    functions = la.all_functions
    print(f"  Functions count: {len(functions)}")
    if len(functions) > 0:
        print("  First 5 functions:")
        for func in functions[:5]:
            print(f"    - {func.name}")
            # Check for outputs/inputs
            if hasattr(func, 'outputs'):
                print(f"      Outputs: {len(func.outputs)}")
            if hasattr(func, 'inputs'):
                print(f"      Inputs: {len(func.inputs)}")
print()

# Try to find where exchanges should be created
print("Exploring exchange creation patterns:")

# Check if exchanges are created between function ports
if len(functions) > 0:
    func = functions[0]
    print(f"  Function: {func.name}")
    
    # Check for ports
    if hasattr(func, 'outputs') and len(func.outputs) > 0:
        output_port = func.outputs[0]
        print(f"    Output port: {output_port.name}")
        print(f"    Port type: {type(output_port)}")
        
        # Check if port has exchanges attribute
        if hasattr(output_port, 'exchanges'):
            print(f"      Has exchanges: {type(output_port.exchanges)}")
            if hasattr(output_port.exchanges, 'create'):
                print(f"      [OK] Port.exchanges has create method!")

print("\n=== END DISCOVERY ===")