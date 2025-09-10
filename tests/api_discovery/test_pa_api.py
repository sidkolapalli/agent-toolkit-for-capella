#!/usr/bin/env python
"""
API Discovery: Physical Architecture (PA) Layer

Tests what Physical Architecture elements can be created using capellambse.

Required PA Elements per Full-Scale Assessment:
- PhysicalComponent
- PhysicalFunction
- PhysicalLink
- PhysicalPath  
- NodeComponent
"""

import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "src"))

import capellambse

# Load the GMHCD model
model_path = r"<local-path>"
model = capellambse.MelodyModel(model_path)

print("=== PHYSICAL ARCHITECTURE (PA) API DISCOVERY ===\n")

# Check if PA exists
print("1. Checking Physical Architecture access...")
if hasattr(model, 'pa') and model.pa:
    pa = model.pa
    print(f"   [OK] PA accessible: {pa.name}")
    print(f"   Type: {type(pa)}")
    print(f"   UUID: {pa.uuid}")
else:
    print("   [FAILED] No Physical Architecture found in model")
    exit(1)

print()

# Test key PA collections
print("2. Testing known PA collections...")
known_collections = [
    ('all_components', 'Physical Components'),
    ('all_functions', 'Physical Functions'),
    ('all_links', 'Physical Links'),
    ('all_paths', 'Physical Paths'),
    ('component_pkg', 'Component Package'),
    ('function_pkg', 'Function Package')
]

for attr_name, description in known_collections:
    try:
        if hasattr(pa, attr_name):
            collection = getattr(pa, attr_name)
            if hasattr(collection, '__len__'):
                count = len(collection)
                print(f"   [OK] {description}: {type(collection).__name__} (count: {count})")
            else:
                print(f"   [OK] {description}: {type(collection).__name__}")
        else:
            print(f"   [MISSING] {description}: Not found")
    except Exception as e:
        print(f"   [ERROR] {description}: {e}")

print()

# Test PhysicalComponent creation
print("3. Testing PhysicalComponent creation...")
component_creation_works = False
try:
    if hasattr(pa, 'component_pkg') and pa.component_pkg:
        component_pkg = pa.component_pkg
        print(f"   Found component_pkg: {type(component_pkg)}")
        
        if hasattr(component_pkg, 'components'):
            components = component_pkg.components
            print(f"   Found components: {type(components)} (count: {len(components)})")
            
            if hasattr(components, 'create'):
                try:
                    test_component = components.create(name="Test_PA_Component")
                    print(f"   [SUCCESS] Created: {test_component.name} ({test_component.uuid})")
                    component_creation_works = True
                except Exception as e:
                    print(f"   [FAILED] Create failed: {e}")
            else:
                print(f"   [FAILED] No create method on components")
        else:
            print(f"   [FAILED] No components attribute in component_pkg")
    else:
        print(f"   [FAILED] No component_pkg found in PA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test PhysicalFunction creation
print("4. Testing PhysicalFunction creation...")
function_creation_works = False
try:
    if hasattr(pa, 'function_pkg') and pa.function_pkg:
        function_pkg = pa.function_pkg
        print(f"   Found function_pkg: {type(function_pkg)}")
        
        # Try different possible function collections
        function_collections = ['functions', 'physical_functions', 'activities']
        found_functions = False
        
        for func_attr in function_collections:
            if hasattr(function_pkg, func_attr):
                functions = getattr(function_pkg, func_attr)
                print(f"   Found {func_attr}: {type(functions)} (count: {len(functions)})")
                found_functions = True
                
                if hasattr(functions, 'create'):
                    try:
                        test_function = functions.create(name="Test_PA_Function")
                        print(f"   [SUCCESS] Created: {test_function.name} ({test_function.uuid})")
                        function_creation_works = True
                        break
                    except Exception as e:
                        print(f"   [FAILED] Create on {func_attr} failed: {e}")
                else:
                    print(f"   [INFO] No create method on {func_attr}")
        
        if not found_functions:
            print(f"   [INFO] No function collections found in function_pkg")
            
    else:
        print(f"   [FAILED] No function_pkg found in PA")
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test PhysicalLink creation
print("5. Testing PhysicalLink creation...")
link_creation_works = False
try:
    # Check for link packages or collections
    link_attrs = ['all_links', 'link_pkg', 'links']
    found_links = False
    
    for link_attr in link_attrs:
        if hasattr(pa, link_attr):
            link_container = getattr(pa, link_attr)
            print(f"   Found {link_attr}: {type(link_container)}")
            found_links = True
            
            # If it's a package, look for links collection inside
            if hasattr(link_container, 'links'):
                links = link_container.links
                print(f"   Found links collection: {type(links)} (count: {len(links)})")
                
                if hasattr(links, 'create'):
                    try:
                        test_link = links.create(name="Test_PA_Link")
                        print(f"   [SUCCESS] Created: {test_link.name} ({test_link.uuid})")
                        link_creation_works = True
                        break
                    except Exception as e:
                        print(f"   [FAILED] Create on links failed: {e}")
                else:
                    print(f"   [INFO] No create method on links")
            elif hasattr(link_container, 'create'):
                # Direct creation
                try:
                    test_link = link_container.create(name="Test_PA_Link")
                    print(f"   [SUCCESS] Created: {test_link.name} ({test_link.uuid})")
                    link_creation_works = True
                    break
                except Exception as e:
                    print(f"   [FAILED] Direct create on {link_attr} failed: {e}")
            else:
                print(f"   [INFO] {link_attr} has no create capability")
                
    if not found_links:
        print(f"   [INFO] No link structures found in PA")
        
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test NodeComponent creation
print("6. Testing NodeComponent creation...")
node_creation_works = False
try:
    # Look for node-related structures
    node_attrs = ['node_pkg', 'nodes', 'all_nodes']
    found_nodes = False
    
    for node_attr in node_attrs:
        if hasattr(pa, node_attr):
            node_container = getattr(pa, node_attr)
            print(f"   Found {node_attr}: {type(node_container)}")
            found_nodes = True
            
            if hasattr(node_container, 'nodes'):
                nodes = node_container.nodes
                print(f"   Found nodes: {type(nodes)} (count: {len(nodes)})")
                
                if hasattr(nodes, 'create'):
                    try:
                        test_node = nodes.create(name="Test_PA_Node")
                        print(f"   [SUCCESS] Created: {test_node.name} ({test_node.uuid})")
                        node_creation_works = True
                        break
                    except Exception as e:
                        print(f"   [FAILED] Create failed: {e}")
            elif hasattr(node_container, 'create'):
                try:
                    test_node = node_container.create(name="Test_PA_Node")
                    print(f"   [SUCCESS] Created: {test_node.name} ({test_node.uuid})")
                    node_creation_works = True
                    break
                except Exception as e:
                    print(f"   [FAILED] Direct create failed: {e}")
            break
    
    if not found_nodes:
        print(f"   [INFO] No node structures found in PA")
        print(f"   [INFO] NodeComponents might be a subset of PhysicalComponents")
        
except Exception as e:
    print(f"   [ERROR] {e}")

print()

# Test saving changes
print("7. Testing model save...")
save_works = False
try:
    model.save()
    print("   [OK] Model saved successfully")
    save_works = True
except Exception as e:
    print(f"   [FAILED] Save failed: {e}")

print()

# Summary
print("=== PA API DISCOVERY SUMMARY ===")
print(f"[OK] PA Access: Working")
print(f"[{'OK' if component_creation_works else 'FAIL'}] PhysicalComponent Creation: {'Working' if component_creation_works else 'Failed'}")
print(f"[{'OK' if function_creation_works else 'FAIL'}] PhysicalFunction Creation: {'Working' if function_creation_works else 'Failed'}")
print(f"[{'OK' if link_creation_works else 'FAIL'}] PhysicalLink Creation: {'Working' if link_creation_works else 'Failed'}")
print(f"[{'OK' if node_creation_works else 'FAIL'}] NodeComponent Creation: {'Working' if node_creation_works else 'Failed'}")
print(f"[{'OK' if save_works else 'FAIL'}] Model Persistence: {'Working' if save_works else 'Failed'}")

# Calculate success rate
successes = sum([component_creation_works, function_creation_works, link_creation_works, node_creation_works, save_works])
total_tests = 5
success_rate = (successes / total_tests) * 100

print(f"\nPA Implementation Readiness: {success_rate:.0f}% ({successes}/{total_tests} capabilities working)")

if success_rate >= 80:
    print("[HIGH] PA layer ready for implementation")
elif success_rate >= 60:
    print("[MEDIUM] PA layer partially ready, some workarounds needed")
else:
    print("[LOW] PA layer needs significant work or alternative approach")

print("\n=== NEXT STEPS ===")
if component_creation_works:
    print("- Implement PhysicalComponent in model_manager.py")
if function_creation_works:
    print("- Implement PhysicalFunction in model_manager.py")
if link_creation_works:
    print("- Implement PhysicalLink in model_manager.py")
if node_creation_works:
    print("- Implement NodeComponent in model_manager.py")
if not save_works:
    print("- Investigate model save issues")
    
print("- Test PhysicalPath creation")
print("- Create comprehensive PA test suite")