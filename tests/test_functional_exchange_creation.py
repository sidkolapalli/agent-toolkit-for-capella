#!/usr/bin/env python
"""Test creating FunctionalExchanges."""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== FUNCTIONAL EXCHANGE CREATION TEST ===\n")

la = model.la

# Create functions first (to connect with exchanges)
print("1. Creating test functions...")
functions = la.function_pkg.functions
func1 = functions.create(name="Test_Function_Source")
func2 = functions.create(name="Test_Function_Target")
print(f"   Created: {func1.name} ({func1.uuid})")
print(f"   Created: {func2.name} ({func2.uuid})")
print()

# Now create a functional exchange
print("2. Creating functional exchange...")
exchanges = la.function_pkg.exchanges

try:
    # Create exchange
    exchange = exchanges.create(
        name="Test_FunctionalExchange",
        source=func1,
        target=func2
    )
    print(f"   [OK] Created: {exchange.name}")
    print(f"        UUID: {exchange.uuid}")
    print(f"        Type: {type(exchange).__name__}")
    print(f"        Source: {exchange.source.name if hasattr(exchange, 'source') and exchange.source else 'None'}")
    print(f"        Target: {exchange.target.name if hasattr(exchange, 'target') and exchange.target else 'None'}")
except Exception as e:
    print(f"   [FAILED] {e}")
    # Try without source/target
    try:
        print("   Trying without source/target...")
        exchange = exchanges.create(name="Test_FunctionalExchange_Simple")
        print(f"   [OK] Created: {exchange.name}")
        print(f"        UUID: {exchange.uuid}")
        print(f"        Type: {type(exchange).__name__}")
    except Exception as e2:
        print(f"   [FAILED] {e2}")
print()

# Save the model
print("3. Saving model...")
try:
    model.save()
    print("   [OK] Model saved")
except Exception as e:
    print(f"   [FAILED] {e}")
print()

# Reload and verify
print("4. Reloading model to verify persistence...")
model2 = capellambse.MelodyModel(model_path)
la2 = model2.la

# Check if exchange persists
exchanges2 = la2.function_pkg.exchanges
print(f"   Exchange count: {len(exchanges2)}")
for ex in exchanges2:
    if 'Test_FunctionalExchange' in ex.name:
        print(f"   [OK] Found persisted: {ex.name} ({ex.uuid})")

print("\n=== END TEST ===")