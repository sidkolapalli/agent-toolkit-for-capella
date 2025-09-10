#!/usr/bin/env python
"""Test script to discover correct Interface creation API in capellambse."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== INTERFACE API DISCOVERY ===\n")

la = model.la
print(f"LogicalArchitecture: {la}")
print()

# Check interface_pkg
print("Checking la.interface_pkg:")
if hasattr(la, 'interface_pkg'):
    interface_pkg = la.interface_pkg
    print(f"  Type: {type(interface_pkg)}")
    print(f"  Available attributes:")
    for attr in dir(interface_pkg):
        if not attr.startswith('_') and 'interface' in attr.lower():
            value = getattr(interface_pkg, attr)
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

# Check for interfaces collection
if hasattr(interface_pkg, 'interfaces'):
    interfaces = interface_pkg.interfaces
    print(f"interface_pkg.interfaces:")
    print(f"  Type: {type(interfaces)}")
    print(f"  Count: {len(interfaces)}")
    print(f"  Is coupled: {interfaces.is_coupled()}")
    
    # Try creating
    print("\n  Attempting to create interface...")
    try:
        new_interface = interfaces.create(name="Test_Interface_Discovery")
        print(f"  [OK] Created: {new_interface.name}")
        print(f"       UUID: {new_interface.uuid}")
        print(f"       Type: {type(new_interface).__name__}")
    except Exception as e:
        print(f"  [FAILED] {e}")
print()

# Check all_interfaces
print("Checking la.all_interfaces:")
if hasattr(la, 'all_interfaces'):
    all_interfaces = la.all_interfaces
    print(f"  Type: {type(all_interfaces)}")
    print(f"  Count: {len(all_interfaces)}")
    print(f"  Is coupled: {all_interfaces.is_coupled()}")

print("\n=== END DISCOVERY ===")