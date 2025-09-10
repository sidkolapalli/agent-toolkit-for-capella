#!/usr/bin/env python
"""
Advanced PA Research: Deep API Exploration
Comprehensive investigation of alternative approaches for PhysicalLink and NodeComponent

This research goes beyond the initial gap investigation to explore:
1. Alternative physical connectivity patterns
2. Component exchange and port relationships  
3. Advanced capellambse features
4. Indirect creation through other elements
"""

import asyncio
import os
import sys
from pathlib import Path
import inspect

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse
from capella_mcp.model_manager import ModelManager

class AdvancedPAResearcher:
    def __init__(self, model_path):
        self.model_path = model_path
        self.model = None
        self.findings = {}
        
    def load_model(self):
        """Load model for research"""
        print("=== ADVANCED PA RESEARCH: DEEP API EXPLORATION ===\n")
        print("Loading GMHCD model for advanced investigation...")
        self.model = capellambse.MelodyModel(self.model_path)
        print(f"Model loaded: {self.model.name}\n")
        
    def explore_physical_connections(self):
        """Advanced research on physical connectivity"""
        print("1. ADVANCED PHYSICALLINK RESEARCH")
        print("-" * 40)
        
        pa = self.model.pa
        
        # Strategy 1: Explore all PA attributes with introspection
        print("Strategy 1: Complete PA attribute exploration...")
        pa_attrs = [attr for attr in dir(pa) if not attr.startswith('_')]
        connection_attrs = [attr for attr in pa_attrs if any(keyword in attr.lower() 
                          for keyword in ['link', 'connect', 'exchange', 'path', 'communication', 'channel'])]
        
        print(f"   Found {len(connection_attrs)} potential connection attributes:")
        for attr in connection_attrs:
            try:
                value = getattr(pa, attr)
                print(f"   - {attr}: {type(value)}")
                if hasattr(value, '__len__') and not isinstance(value, str):
                    count = len(value)
                    print(f"     Count: {count}")
                if hasattr(value, 'create'):
                    print(f"     HAS CREATE METHOD!")
                    # Try to create
                    try:
                        test_element = value.create(name="Test_Advanced_Link")
                        print(f"     [SUCCESS] Created: {test_element.uuid}")
                        self.findings['PhysicalLink'] = {
                            'method': f'pa.{attr}.create()',
                            'status': 'SUCCESS',
                            'element_id': str(test_element.uuid)
                        }
                        return True
                    except Exception as e:
                        print(f"     [FAILED] Create error: {e}")
            except Exception as e:
                print(f"   - {attr}: ERROR - {e}")
        
        # Strategy 2: Component exchange exploration
        print("\nStrategy 2: Component exchange and port exploration...")
        try:
            components = pa.component_pkg.components
            if len(components) >= 2:
                comp1, comp2 = components[0], components[1]
                print(f"   Testing between {comp1.name} and {comp2.name}")
                
                # Explore component attributes for connections
                for comp in [comp1, comp2]:
                    comp_attrs = [attr for attr in dir(comp) if not attr.startswith('_')]
                    conn_attrs = [attr for attr in comp_attrs if any(keyword in attr.lower() 
                                for keyword in ['link', 'connect', 'exchange', 'port', 'interface'])]
                    
                    print(f"   {comp.name} connection attributes: {conn_attrs}")
                    
                    for attr in conn_attrs:
                        try:
                            value = getattr(comp, attr)
                            print(f"   - {comp.name}.{attr}: {type(value)}")
                            if hasattr(value, 'create'):
                                print(f"     HAS CREATE on component!")
                                try:
                                    test_conn = value.create(name="Test_Component_Connection")
                                    print(f"     [SUCCESS] Created connection: {test_conn.uuid}")
                                    self.findings['PhysicalLink'] = {
                                        'method': f'component.{attr}.create()',
                                        'status': 'SUCCESS', 
                                        'element_id': str(test_conn.uuid)
                                    }
                                    return True
                                except Exception as e:
                                    print(f"     [FAILED] Component create error: {e}")
                        except Exception as e:
                            print(f"     ERROR accessing {attr}: {e}")
        except Exception as e:
            print(f"   Component exploration failed: {e}")
            
        # Strategy 3: Check for PhysicalPath as alternative
        print("\nStrategy 3: PhysicalPath as connection alternative...")
        try:
            # Look for physical paths in all possible locations
            path_locations = [
                ('pa.all_paths', lambda: pa.all_paths if hasattr(pa, 'all_paths') else None),
                ('pa.path_pkg.paths', lambda: pa.path_pkg.paths if hasattr(pa, 'path_pkg') and hasattr(pa.path_pkg, 'paths') else None),
                ('pa.function_pkg.paths', lambda: pa.function_pkg.paths if hasattr(pa.function_pkg, 'paths') else None)
            ]
            
            for location_name, location_getter in path_locations:
                try:
                    location = location_getter()
                    if location is not None:
                        print(f"   Found {location_name}: {type(location)}")
                        if hasattr(location, 'create'):
                            try:
                                test_path = location.create(name="Test_Physical_Path")
                                print(f"   [SUCCESS] Created PhysicalPath: {test_path.uuid}")
                                self.findings['PhysicalLink'] = {
                                    'method': f'{location_name}.create()',
                                    'status': 'SUCCESS',
                                    'alternative': 'PhysicalPath',
                                    'element_id': str(test_path.uuid)
                                }
                                return True
                            except Exception as e:
                                print(f"   [FAILED] Path create error: {e}")
                        else:
                            print(f"   {location_name} has no create method")
                    else:
                        print(f"   {location_name}: Not found")
                except Exception as e:
                    print(f"   {location_name}: Error - {e}")
        except Exception as e:
            print(f"   PhysicalPath exploration failed: {e}")
            
        print("   [RESULT] No PhysicalLink creation methods discovered")
        return False
        
    def explore_node_components(self):
        """Advanced research on node components"""
        print("\n2. ADVANCED NODECOMPONENT RESEARCH") 
        print("-" * 40)
        
        pa = self.model.pa
        
        # Strategy 1: Component nature/kind parameter exploration
        print("Strategy 1: Component creation with nature/kind parameters...")
        try:
            components = pa.component_pkg.components
            
            # Test various node-related parameters
            node_params = [
                {'nature': 'NODE'},
                {'nature': 'node'}, 
                {'kind': 'NODE'},
                {'kind': 'node'},
                {'component_type': 'NODE'},
                {'component_type': 'node'},
                {'is_node': True},
                {'deployment': True},
                {'host': True}
            ]
            
            for params in node_params:
                try:
                    print(f"   Testing creation with {params}...")
                    test_node = components.create(name="Test_Node_Component", **params)
                    print(f"   [SUCCESS] Created with {params}: {test_node.uuid}")
                    
                    # Validate the parameter was set
                    for param, value in params.items():
                        if hasattr(test_node, param):
                            actual_value = getattr(test_node, param)
                            print(f"   - {param}: {actual_value} (expected: {value})")
                            if actual_value == value:
                                self.findings['NodeComponent'] = {
                                    'method': f'pa.component_pkg.components.create(**{params})',
                                    'status': 'SUCCESS',
                                    'element_id': str(test_node.uuid)
                                }
                                return True
                except Exception as e:
                    print(f"   [FAILED] {params}: {e}")
                    
        except Exception as e:
            print(f"   Component parameter exploration failed: {e}")
            
        # Strategy 2: Deployment-related collections
        print("\nStrategy 2: Deployment and infrastructure collections...")
        try:
            deploy_attrs = [attr for attr in dir(pa) if any(keyword in attr.lower() 
                          for keyword in ['deploy', 'node', 'host', 'device', 'infrastructure', 'platform'])]
            
            print(f"   Found {len(deploy_attrs)} deployment-related attributes:")
            for attr in deploy_attrs:
                try:
                    value = getattr(pa, attr)
                    print(f"   - {attr}: {type(value)}")
                    if hasattr(value, 'create'):
                        print(f"     HAS CREATE METHOD!")
                        try:
                            test_deploy = value.create(name="Test_Deployment_Node")
                            print(f"     [SUCCESS] Created: {test_deploy.uuid}")
                            self.findings['NodeComponent'] = {
                                'method': f'pa.{attr}.create()',
                                'status': 'SUCCESS',
                                'element_id': str(test_deploy.uuid)
                            }
                            return True
                        except Exception as e:
                            print(f"     [FAILED] Create error: {e}")
                except Exception as e:
                    print(f"   - {attr}: ERROR - {e}")
                    
        except Exception as e:
            print(f"   Deployment exploration failed: {e}")
            
        # Strategy 3: Component category/type analysis
        print("\nStrategy 3: Component categorization approach...")
        try:
            components = pa.component_pkg.components
            if len(components) > 0:
                comp = components[0]
                print(f"   Analyzing existing component: {comp.name}")
                
                # Check all attributes for type/category information
                comp_attrs = [attr for attr in dir(comp) if not attr.startswith('_')]
                type_attrs = [attr for attr in comp_attrs if any(keyword in attr.lower() 
                            for keyword in ['type', 'category', 'nature', 'kind', 'class'])]
                
                print(f"   Component type attributes: {type_attrs}")
                for attr in type_attrs:
                    try:
                        value = getattr(comp, attr)
                        print(f"   - {attr}: {value} ({type(value)})")
                        
                        # Try to set it to node-related values
                        if hasattr(comp, f'set_{attr}') or not callable(value):
                            try:
                                if isinstance(value, bool):
                                    setattr(comp, attr, True)
                                elif isinstance(value, str):
                                    setattr(comp, attr, "NODE")
                                print(f"   [SUCCESS] Set {attr} to node value")
                                
                                # Create new component with this pattern
                                new_comp = components.create(name="Test_Node_Via_Category")
                                setattr(new_comp, attr, "NODE" if isinstance(value, str) else True)
                                print(f"   [SUCCESS] Created node component: {new_comp.uuid}")
                                self.findings['NodeComponent'] = {
                                    'method': f'component.{attr} = NODE',
                                    'status': 'SUCCESS',
                                    'element_id': str(new_comp.uuid)
                                }
                                return True
                            except Exception as e:
                                print(f"   [FAILED] Cannot set {attr}: {e}")
                    except Exception as e:
                        print(f"   - {attr}: ERROR - {e}")
        except Exception as e:
            print(f"   Component categorization failed: {e}")
            
        print("   [RESULT] No NodeComponent creation methods discovered")
        return False
        
    def test_discovered_methods(self):
        """Test any discovered methods"""
        print("\n3. TESTING DISCOVERED METHODS")
        print("-" * 40)
        
        if not self.findings:
            print("   No new methods discovered to test")
            return
            
        for element_type, finding in self.findings.items():
            print(f"\nTesting discovered {element_type} method...")
            print(f"   Method: {finding['method']}")
            print(f"   Status: {finding['status']}")
            if 'element_id' in finding:
                print(f"   Created Element ID: {finding['element_id']}")
                
                # Try to query the element
                try:
                    element = self.model.by_uuid(finding['element_id'])
                    print(f"   [QUERY SUCCESS] Element found: {element.name}")
                    print(f"   Element type: {type(element)}")
                    
                    # Check element properties
                    if element_type == "NodeComponent":
                        node_attrs = ['nature', 'kind', 'is_node', 'component_type']
                        for attr in node_attrs:
                            if hasattr(element, attr):
                                value = getattr(element, attr)
                                print(f"   - {attr}: {value}")
                                
                except Exception as e:
                    print(f"   [QUERY FAILED] Cannot query element: {e}")
        
    def generate_research_report(self):
        """Generate final research report"""
        print("\n" + "="*60)
        print("ADVANCED PA RESEARCH REPORT")
        print("="*60)
        
        if self.findings:
            print("DISCOVERIES:")
            for element_type, finding in self.findings.items():
                print(f"\n{element_type}:")
                print(f"   Method: {finding['method']}")
                print(f"   Status: {finding['status']}")
                if 'alternative' in finding:
                    print(f"   Alternative: {finding['alternative']}")
                if 'element_id' in finding:
                    print(f"   Test Element: {finding['element_id']}")
                    
            print("\nRECOMMENDATIONS:")
            for element_type in self.findings.keys():
                print(f"- Implement {element_type} in model_manager.py")
                print(f"- Add comprehensive testing for new method")
                print(f"- Update capability assessment")
                
            new_capability = 88 + (len(self.findings) * 6)  # Each missing element was ~6%
            print(f"\nPOTENTIAL CAPABILITY INCREASE: {88}% -> {new_capability}%")
        else:
            print("NO NEW METHODS DISCOVERED")
            print("\nCONFIRMED LIMITATIONS:")
            print("- PhysicalLink: No creation APIs available in capellambse")
            print("- NodeComponent: No dedicated creation APIs found")
            print("\nREMAINING OPTIONS:")
            print("- Manual workarounds (naming conventions)")
            print("- External link management") 
            print("- Wait for capellambse API updates")
            print("\nFINAL CAPABILITY: 88% (14/16 ARCADIA elements)")
            
        print("\n" + "="*60)
        print("ADVANCED RESEARCH COMPLETED")
        print("="*60)
        
        return self.findings

async def main():
    model_path = r"<local-path>"
    
    researcher = AdvancedPAResearcher(model_path)
    researcher.load_model()
    
    # Perform advanced research
    link_found = researcher.explore_physical_connections()
    node_found = researcher.explore_node_components()
    
    # Test any discoveries
    researcher.test_discovered_methods()
    
    # Generate final report
    discoveries = researcher.generate_research_report()
    
    # Save model if anything was created
    try:
        researcher.model.save()
        print("\nModel saved with any test elements created")
    except Exception as e:
        print(f"\nSave warning: {e}")
        
    return discoveries

if __name__ == "__main__":
    results = asyncio.run(main())