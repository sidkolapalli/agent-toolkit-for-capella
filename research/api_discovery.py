"""
Capellambse API Discovery Script
Load GMHCD model and introspect objects to find correct component creation methods
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

# Load capellambse
try:
    import capellambse
    from capellambse import MelodyModel
    print(f"[OK] capellambse loaded successfully - version: {capellambse.__version__}")
except ImportError as e:
    print(f"[ERROR] Failed to import capellambse: {e}")
    sys.exit(1)

# Set model path
model_path = os.environ.get("CAPELLA_MODEL_PATH", "<local-path>")
print(f"[INFO] Using model path: {model_path}")

if not os.path.exists(model_path):
    print(f"[ERROR] Model file not found: {model_path}")
    sys.exit(1)

print("\n[PHASE 1: LOADING MODEL]")
print("=" * 50)

try:
    # Load the model
    model = MelodyModel(model_path)
    print(f"[OK] Model loaded successfully: {model.name}")
    print(f"   UUID: {model.uuid}")
    
    # Explore model structure
    print(f"\n[MODEL STRUCTURE]:")
    print(f"   Type: {type(model)}")
    print(f"   Has LA (Logical Architecture): {hasattr(model, 'la')}")
    print(f"   Has PA (Physical Architecture): {hasattr(model, 'pa')}")
    print(f"   Has SA (System Architecture): {hasattr(model, 'sa')}")
    
except Exception as e:
    print(f"[ERROR] Failed to load model: {e}")
    sys.exit(1)

print("\n[PHASE 1.1: EXPLORING LOGICAL ARCHITECTURE]")
print("=" * 50)

if hasattr(model, 'la') and model.la:
    la = model.la
    print(f"[OK] Logical Architecture found: {type(la)}")
    
    # Introspect LA object
    print(f"\n[LogicalArchitecture attributes and methods]:")
    la_methods = [attr for attr in dir(la) if not attr.startswith('_')]
    for method in sorted(la_methods):
        try:
            attr_value = getattr(la, method)
            if callable(attr_value):
                print(f"   METHOD: {method}()")
            else:
                print(f"   PROPERTY: {method} - ({type(attr_value).__name__})")
        except Exception as e:
            print(f"   ERROR accessing {method}: {e}")
    
    # Look for component-related attributes
    print(f"\n[COMPONENT-RELATED ATTRIBUTES]:")
    component_attrs = [attr for attr in la_methods if 'component' in attr.lower()]
    for attr in component_attrs:
        try:
            attr_value = getattr(la, attr)
            print(f"   STAR: {attr}: {type(attr_value)} - {attr_value if not callable(attr_value) else 'Method'}")
        except Exception as e:
            print(f"   ERROR accessing {attr}: {e}")
    
    # Check if there are existing components
    if hasattr(la, 'all_components'):
        try:
            components = la.all_components
            print(f"\n[COMPONENTS] Found {len(components)} existing components:")
            for i, comp in enumerate(components[:3]):  # Show first 3
                print(f"   {i+1}. {comp.name} (UUID: {comp.uuid}) - Type: {type(comp)}")
            if len(components) > 3:
                print(f"   ... and {len(components) - 3} more")
                
            # Introspect a component to understand structure
            if components:
                first_comp = components[0]
                print(f"\n[INTROSPECTING COMPONENT]: {first_comp.name}")
                comp_methods = [attr for attr in dir(first_comp) if not attr.startswith('_')]
                comp_methods_filtered = [m for m in comp_methods if any(keyword in m.lower() for keyword in ['create', 'add', 'insert', 'new', 'component'])]
                if comp_methods_filtered:
                    print(f"   Component creation-related methods: {comp_methods_filtered}")
                else:
                    print(f"   Total component methods: {len(comp_methods)}")
        except Exception as e:
            print(f"\n[ERROR] Failed to access components: {e}")

else:
    print("[ERROR] No Logical Architecture found")

print("\n[PHASE 1.2: EXPLORING CAPELLAMBSE CLASSES]")
print("=" * 50)

# Check available classes in capellambse
print("[INFO] Available classes in capellambse.metamodel:")
try:
    import capellambse.metamodel
    metamodel_attrs = [attr for attr in dir(capellambse.metamodel) if not attr.startswith('_')]
    
    # Look for component-related classes
    component_classes = [attr for attr in metamodel_attrs if 'component' in attr.lower() or 'logical' in attr.lower()]
    print(f"   Component-related: {component_classes}")
    
    # Check logical architecture specific
    if hasattr(capellambse.metamodel, 'la'):
        print(f"   Logical Architecture module: {capellambse.metamodel.la}")
        la_classes = [attr for attr in dir(capellambse.metamodel.la) if not attr.startswith('_')]
        component_la_classes = [attr for attr in la_classes if 'component' in attr.lower()]
        print(f"   LA Component classes: {component_la_classes}")

except Exception as e:
    print(f"   Error exploring metamodel: {e}")

print("\n[PHASE 1.3: TESTING CREATION PATTERNS]")
print("=" * 50)

if hasattr(model, 'la') and model.la:
    la = model.la
    
    # Try to find collections that might support creation
    print("[INFO] Looking for component collections:")
    for attr_name in dir(la):
        if not attr_name.startswith('_') and 'component' in attr_name.lower():
            attr_value = getattr(la, attr_name)
            print(f"   {attr_name}: {type(attr_value)}")
            
            # If it's a collection, check if it has create/add methods
            if hasattr(attr_value, '__len__'):
                try:
                    collection_methods = [m for m in dir(attr_value) if not m.startswith('_')]
                    create_methods = [m for m in collection_methods if m in ['create', 'add', 'insert', 'append', 'new']]
                    if create_methods:
                        print(f"      CREATION METHODS: {create_methods}")
                    else:
                        print(f"      TOTAL METHODS: {len(collection_methods)}")
                except:
                    pass

print("\n[OK] API DISCOVERY COMPLETE!")
print("Check the output above to understand the correct API patterns.")