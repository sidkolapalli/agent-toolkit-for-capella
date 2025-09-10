#!/usr/bin/env python
"""
Phase 2B: Gap Investigation Script

Systematic exploration of 3 missing ARCADIA element types:
1. SystemActor (SA layer)
2. PhysicalLink (PA layer) 
3. NodeComponent (PA layer)

Each investigation is time-boxed with multiple search strategies.
Results are documented in real-time for analysis.
"""

import asyncio
import os
import sys
from pathlib import Path
import time
import traceback

# Add src to path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

import capellambse
from capella_mcp.model_manager import ModelManager


class GapInvestigator:
    def __init__(self, model_path):
        self.model_path = model_path
        self.model = None
        self.findings = {}
        self.start_time = None
        
    def load_model(self):
        """Load the Capella model"""
        print("Loading GMHCD model for gap investigation...")
        self.model = capellambse.MelodyModel(self.model_path)
        print(f"Model loaded: {self.model.name}")
        print()
        
    def log_finding(self, category, subcategory, status, details, elapsed_time=None):
        """Log investigation findings"""
        if category not in self.findings:
            self.findings[category] = {}
        
        self.findings[category][subcategory] = {
            "status": status,
            "details": details,
            "elapsed_time": elapsed_time
        }
        
        time_str = f" ({elapsed_time:.1f}s)" if elapsed_time else ""
        print(f"   [{status}] {subcategory}: {details}{time_str}")
        
    def investigate_system_actor(self):
        """
        SystemActor Investigation (Time-boxed: 60 minutes)
        
        Search strategies:
        1. Direct access: model.sa.all_actors
        2. Package exploration: sa.actor_pkg variations
        3. Component pattern: SystemComponent with is_actor=True
        4. Entity relationship: Operational entities vs System actors
        5. Alternative names: participants, stakeholders, users
        """
        print("=== SYSTEMACTOR INVESTIGATION ===")
        investigation_start = time.time()
        category = "SystemActor"
        
        # Strategy 1: Direct access
        try:
            if hasattr(self.model, 'sa') and self.model.sa:
                sa = self.model.sa
                
                # Check for all_actors collection
                if hasattr(sa, 'all_actors'):
                    actors = sa.all_actors
                    count = len(actors) if hasattr(actors, '__len__') else 0
                    self.log_finding(category, "sa.all_actors", "EXISTS", 
                                   f"Found collection with {count} actors")
                    
                    # Test creation if empty collection with create method
                    if hasattr(actors, 'create'):
                        try:
                            test_actor = actors.create(name="Test_SystemActor_Gap")
                            self.log_finding(category, "sa.all_actors.create()", "SUCCESS", 
                                           f"Created test actor: {test_actor.uuid}")
                            return True
                        except Exception as e:
                            self.log_finding(category, "sa.all_actors.create()", "FAILED", 
                                           f"Create failed: {e}")
                    else:
                        self.log_finding(category, "sa.all_actors.create", "MISSING", 
                                       "Collection exists but no create method")
                else:
                    self.log_finding(category, "sa.all_actors", "MISSING", "No all_actors attribute")
                    
        except Exception as e:
            self.log_finding(category, "sa.all_actors", "ERROR", f"Access error: {e}")
            
        # Strategy 2: Package exploration
        try:
            sa = self.model.sa
            actor_pkg_variants = [
                'actor_pkg', 'actors_pkg', 'stakeholder_pkg', 'participant_pkg'
            ]
            
            for pkg_name in actor_pkg_variants:
                if hasattr(sa, pkg_name):
                    pkg = getattr(sa, pkg_name)
                    self.log_finding(category, f"sa.{pkg_name}", "EXISTS", 
                                   f"Found package: {type(pkg)}")
                    
                    # Look for actor collections inside
                    actor_collections = ['actors', 'stakeholders', 'participants', 'entities']
                    for coll_name in actor_collections:
                        if hasattr(pkg, coll_name):
                            collection = getattr(pkg, coll_name)
                            count = len(collection) if hasattr(collection, '__len__') else 0
                            self.log_finding(category, f"{pkg_name}.{coll_name}", "EXISTS",
                                           f"Collection with {count} items")
                            
                            if hasattr(collection, 'create'):
                                try:
                                    test_item = collection.create(name="Test_SystemActor_Gap2")
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "SUCCESS", f"Created: {test_item.uuid}")
                                    return True
                                except Exception as e:
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "FAILED", f"Create failed: {e}")
                else:
                    self.log_finding(category, f"sa.{pkg_name}", "MISSING", "Package not found")
                    
        except Exception as e:
            self.log_finding(category, "package_exploration", "ERROR", f"Error: {e}")
            
        # Strategy 3: SystemComponent with is_actor pattern
        try:
            sa = self.model.sa
            if hasattr(sa, 'component_pkg') and sa.component_pkg:
                components = sa.component_pkg.components
                self.log_finding(category, "sa.component_pkg.components", "EXISTS",
                               f"Found components collection with {len(components)} items")
                
                # Test creating SystemComponent with is_actor=True
                try:
                    test_actor_component = components.create(
                        name="Test_SystemActor_Component", 
                        is_actor=True
                    )
                    self.log_finding(category, "SystemComponent(is_actor=True)", "SUCCESS",
                                   f"Created actor component: {test_actor_component.uuid}")
                    return True
                except Exception as e:
                    self.log_finding(category, "SystemComponent(is_actor=True)", "FAILED",
                                   f"Failed: {e}")
                    
                # Test creating regular SystemComponent and setting is_actor later
                try:
                    test_component = components.create(name="Test_SystemActor_Component2")
                    if hasattr(test_component, 'is_actor'):
                        test_component.is_actor = True
                        self.log_finding(category, "SystemComponent.is_actor=True", "SUCCESS",
                                       f"Set is_actor on component: {test_component.uuid}")
                        return True
                    else:
                        self.log_finding(category, "SystemComponent.is_actor", "MISSING",
                                       "No is_actor attribute on SystemComponent")
                except Exception as e:
                    self.log_finding(category, "SystemComponent.is_actor", "FAILED", f"Error: {e}")
                    
        except Exception as e:
            self.log_finding(category, "SystemComponent_pattern", "ERROR", f"Error: {e}")
            
        # Strategy 4: Check if actors exist at architecture level
        try:
            # Check direct SA properties for actor-like collections
            sa = self.model.sa  
            sa_attrs = [attr for attr in dir(sa) if 'actor' in attr.lower() or 'participant' in attr.lower()]
            
            if sa_attrs:
                for attr_name in sa_attrs:
                    try:
                        attr_value = getattr(sa, attr_name)
                        self.log_finding(category, f"sa.{attr_name}", "EXISTS",
                                       f"Found attribute: {type(attr_value)}")
                        
                        if hasattr(attr_value, '__len__'):
                            count = len(attr_value)
                            self.log_finding(category, f"sa.{attr_name}_count", "INFO",
                                           f"Collection size: {count}")
                            
                        if hasattr(attr_value, 'create'):
                            try:
                                test_item = attr_value.create(name="Test_SystemActor_Direct")
                                self.log_finding(category, f"sa.{attr_name}.create()", "SUCCESS",
                                               f"Created: {test_item.uuid}")
                                return True
                            except Exception as e:
                                self.log_finding(category, f"sa.{attr_name}.create()", "FAILED",
                                               f"Create failed: {e}")
                    except Exception as e:
                        self.log_finding(category, f"sa.{attr_name}_access", "ERROR", f"Error: {e}")
            else:
                self.log_finding(category, "sa_actor_attributes", "MISSING",
                               "No actor-related attributes found")
                
        except Exception as e:
            self.log_finding(category, "architecture_level", "ERROR", f"Error: {e}")
            
        elapsed = time.time() - investigation_start
        self.log_finding(category, "INVESTIGATION_COMPLETE", "SUMMARY", 
                        f"No working SystemActor API found after {elapsed:.1f}s")
        return False
        
    def investigate_physical_link(self):
        """
        PhysicalLink Investigation (Time-boxed: 60 minutes)
        
        Search strategies:
        1. Component links: pa.component_links, pa.all_links
        2. Physical paths: pa.physical_paths, pa.all_paths  
        3. Port connections: component port relationships
        4. Exchange patterns: physical exchanges, communications
        5. Alternative names: connections, communications, channels
        """
        print("\n=== PHYSICALLINK INVESTIGATION ===")
        investigation_start = time.time()
        category = "PhysicalLink"
        
        try:
            pa = self.model.pa
            self.log_finding(category, "pa_access", "SUCCESS", f"PA layer accessible: {pa.name}")
        except Exception as e:
            self.log_finding(category, "pa_access", "FAILED", f"Cannot access PA: {e}")
            return False
            
        # Strategy 1: Direct link collections
        link_collection_names = [
            'all_links', 'component_links', 'physical_links', 'links',
            'connections', 'communications', 'channels'
        ]
        
        for coll_name in link_collection_names:
            try:
                if hasattr(pa, coll_name):
                    collection = getattr(pa, coll_name)
                    count = len(collection) if hasattr(collection, '__len__') else 0
                    self.log_finding(category, f"pa.{coll_name}", "EXISTS", 
                                   f"Collection with {count} items")
                    
                    if hasattr(collection, 'create'):
                        try:
                            test_link = collection.create(name="Test_PhysicalLink_Gap")
                            self.log_finding(category, f"pa.{coll_name}.create()", "SUCCESS",
                                           f"Created link: {test_link.uuid}")
                            return True
                        except Exception as e:
                            self.log_finding(category, f"pa.{coll_name}.create()", "FAILED",
                                           f"Create failed: {e}")
                    else:
                        self.log_finding(category, f"pa.{coll_name}.create", "MISSING",
                                       "Collection exists but no create method")
                else:
                    self.log_finding(category, f"pa.{coll_name}", "MISSING", "Collection not found")
            except Exception as e:
                self.log_finding(category, f"pa.{coll_name}", "ERROR", f"Access error: {e}")
                
        # Strategy 2: Package-based exploration
        link_pkg_names = [
            'link_pkg', 'communication_pkg', 'connection_pkg', 'exchange_pkg'
        ]
        
        for pkg_name in link_pkg_names:
            try:
                if hasattr(pa, pkg_name):
                    pkg = getattr(pa, pkg_name)
                    self.log_finding(category, f"pa.{pkg_name}", "EXISTS", f"Package: {type(pkg)}")
                    
                    # Look for collections inside packages
                    for coll_name in ['links', 'connections', 'communications', 'exchanges']:
                        if hasattr(pkg, coll_name):
                            collection = getattr(pkg, coll_name)
                            count = len(collection) if hasattr(collection, '__len__') else 0
                            self.log_finding(category, f"{pkg_name}.{coll_name}", "EXISTS",
                                           f"Collection with {count} items")
                            
                            if hasattr(collection, 'create'):
                                try:
                                    test_link = collection.create(name="Test_PhysicalLink_Pkg")
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "SUCCESS", f"Created: {test_link.uuid}")
                                    return True
                                except Exception as e:
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "FAILED", f"Create failed: {e}")
                else:
                    self.log_finding(category, f"pa.{pkg_name}", "MISSING", "Package not found")
            except Exception as e:
                self.log_finding(category, f"pa.{pkg_name}", "ERROR", f"Access error: {e}")
                
        # Strategy 3: Port-based connections
        try:
            # Check if we can create links through component ports
            components = pa.component_pkg.components
            if len(components) >= 2:
                comp1, comp2 = components[0], components[1]
                self.log_finding(category, "component_ports", "INFO", 
                               f"Testing port connection between {comp1.name} and {comp2.name}")
                
                # Check for port collections
                for comp in [comp1, comp2]:
                    port_attrs = [attr for attr in dir(comp) if 'port' in attr.lower()]
                    if port_attrs:
                        self.log_finding(category, f"component_port_attrs", "EXISTS",
                                       f"Found port attributes: {port_attrs}")
                        
                        for port_attr in port_attrs:
                            try:
                                ports = getattr(comp, port_attr)
                                if hasattr(ports, '__len__'):
                                    count = len(ports)
                                    self.log_finding(category, f"component.{port_attr}", "EXISTS",
                                                   f"Ports collection with {count} items")
                            except Exception as e:
                                self.log_finding(category, f"component.{port_attr}", "ERROR", 
                                               f"Access error: {e}")
                    else:
                        self.log_finding(category, "component_ports", "MISSING", 
                                       f"No port attributes on {comp.name}")
            else:
                self.log_finding(category, "component_ports", "INSUFFICIENT", 
                               "Need at least 2 components to test port connections")
                
        except Exception as e:
            self.log_finding(category, "port_connections", "ERROR", f"Error: {e}")
            
        # Strategy 4: Physical paths as alternative
        try:
            path_attrs = [attr for attr in dir(pa) if 'path' in attr.lower()]
            if path_attrs:
                for path_attr in path_attrs:
                    try:
                        paths = getattr(pa, path_attr)
                        count = len(paths) if hasattr(paths, '__len__') else 0
                        self.log_finding(category, f"pa.{path_attr}", "EXISTS",
                                       f"Paths collection with {count} items")
                        
                        if hasattr(paths, 'create'):
                            try:
                                test_path = paths.create(name="Test_PhysicalPath_Gap")
                                self.log_finding(category, f"pa.{path_attr}.create()", "SUCCESS",
                                               f"Created path: {test_path.uuid}")
                                return True
                            except Exception as e:
                                self.log_finding(category, f"pa.{path_attr}.create()", "FAILED",
                                               f"Create failed: {e}")
                    except Exception as e:
                        self.log_finding(category, f"pa.{path_attr}", "ERROR", f"Access error: {e}")
            else:
                self.log_finding(category, "pa_paths", "MISSING", "No path-related attributes found")
                
        except Exception as e:
            self.log_finding(category, "physical_paths", "ERROR", f"Error: {e}")
            
        elapsed = time.time() - investigation_start  
        self.log_finding(category, "INVESTIGATION_COMPLETE", "SUMMARY",
                        f"No working PhysicalLink API found after {elapsed:.1f}s")
        return False
        
    def investigate_node_component(self):
        """
        NodeComponent Investigation (Time-boxed: 60 minutes)
        
        Search strategies:
        1. PhysicalComponent variants: is_node, nature, kind attributes
        2. Dedicated node collections: pa.nodes, pa.node_pkg
        3. Behavioral components: check component types/categories
        4. Deployment nodes: infrastructure, hosting elements
        5. Alternative names: hosts, devices, platforms, infrastructure
        """
        print("\n=== NODECOMPONENT INVESTIGATION ===")
        investigation_start = time.time()
        category = "NodeComponent"
        
        try:
            pa = self.model.pa
            components = pa.component_pkg.components
            self.log_finding(category, "pa_components", "EXISTS", 
                           f"Found {len(components)} physical components")
        except Exception as e:
            self.log_finding(category, "pa_components", "ERROR", f"Cannot access PA components: {e}")
            return False
            
        # Strategy 1: PhysicalComponent with attributes
        try:
            if len(components) > 0:
                test_comp = components[0]
                
                # Check for node-related attributes
                node_attrs = ['is_node', 'node', 'nature', 'kind', 'type', 'category']
                for attr_name in node_attrs:
                    if hasattr(test_comp, attr_name):
                        attr_value = getattr(test_comp, attr_name)
                        self.log_finding(category, f"PhysicalComponent.{attr_name}", "EXISTS",
                                       f"Attribute value: {attr_value}")
                        
                        # Test setting node attribute
                        if attr_name in ['is_node', 'node']:
                            try:
                                setattr(test_comp, attr_name, True)
                                self.log_finding(category, f"PhysicalComponent.{attr_name}=True", 
                                               "SUCCESS", "Set node attribute")
                                return True
                            except Exception as e:
                                self.log_finding(category, f"PhysicalComponent.{attr_name}=True",
                                               "FAILED", f"Cannot set: {e}")
                    else:
                        self.log_finding(category, f"PhysicalComponent.{attr_name}", "MISSING",
                                       "Attribute not found")
                        
                # Test creating PhysicalComponent with node parameters
                try:
                    test_node = components.create(name="Test_NodeComponent_Gap", is_node=True)
                    self.log_finding(category, "PhysicalComponent(is_node=True)", "SUCCESS",
                                   f"Created node component: {test_node.uuid}")
                    return True
                except Exception as e:
                    self.log_finding(category, "PhysicalComponent(is_node=True)", "FAILED",
                                   f"Create failed: {e}")
                    
                try:
                    test_node2 = components.create(name="Test_NodeComponent_Gap2", nature="node")
                    self.log_finding(category, "PhysicalComponent(nature='node')", "SUCCESS",
                                   f"Created nature=node component: {test_node2.uuid}")
                    return True
                except Exception as e:
                    self.log_finding(category, "PhysicalComponent(nature='node')", "FAILED",
                                   f"Create failed: {e}")
                    
        except Exception as e:
            self.log_finding(category, "PhysicalComponent_attrs", "ERROR", f"Error: {e}")
            
        # Strategy 2: Dedicated node collections
        node_collection_names = [
            'all_nodes', 'nodes', 'node_components', 'deployment_nodes',
            'hosts', 'devices', 'platforms', 'infrastructure'
        ]
        
        for coll_name in node_collection_names:
            try:
                if hasattr(pa, coll_name):
                    collection = getattr(pa, coll_name)
                    count = len(collection) if hasattr(collection, '__len__') else 0
                    self.log_finding(category, f"pa.{coll_name}", "EXISTS",
                                   f"Collection with {count} items")
                    
                    if hasattr(collection, 'create'):
                        try:
                            test_node = collection.create(name="Test_NodeComponent_Direct")
                            self.log_finding(category, f"pa.{coll_name}.create()", "SUCCESS",
                                           f"Created node: {test_node.uuid}")
                            return True
                        except Exception as e:
                            self.log_finding(category, f"pa.{coll_name}.create()", "FAILED",
                                           f"Create failed: {e}")
                else:
                    self.log_finding(category, f"pa.{coll_name}", "MISSING", "Collection not found")
            except Exception as e:
                self.log_finding(category, f"pa.{coll_name}", "ERROR", f"Access error: {e}")
                
        # Strategy 3: Node packages
        node_pkg_names = [
            'node_pkg', 'deployment_pkg', 'infrastructure_pkg', 'device_pkg'
        ]
        
        for pkg_name in node_pkg_names:
            try:
                if hasattr(pa, pkg_name):
                    pkg = getattr(pa, pkg_name)
                    self.log_finding(category, f"pa.{pkg_name}", "EXISTS", f"Package: {type(pkg)}")
                    
                    # Look for node collections in packages
                    for coll_name in ['nodes', 'components', 'devices', 'hosts']:
                        if hasattr(pkg, coll_name):
                            collection = getattr(pkg, coll_name)
                            count = len(collection) if hasattr(collection, '__len__') else 0
                            self.log_finding(category, f"{pkg_name}.{coll_name}", "EXISTS",
                                           f"Collection with {count} items")
                            
                            if hasattr(collection, 'create'):
                                try:
                                    test_node = collection.create(name="Test_NodeComponent_Pkg")
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "SUCCESS", f"Created: {test_node.uuid}")
                                    return True
                                except Exception as e:
                                    self.log_finding(category, f"{pkg_name}.{coll_name}.create()", 
                                                   "FAILED", f"Create failed: {e}")
                else:
                    self.log_finding(category, f"pa.{pkg_name}", "MISSING", "Package not found")
            except Exception as e:
                self.log_finding(category, f"pa.{pkg_name}", "ERROR", f"Access error: {e}")
                
        elapsed = time.time() - investigation_start
        self.log_finding(category, "INVESTIGATION_COMPLETE", "SUMMARY",
                        f"No working NodeComponent API found after {elapsed:.1f}s")
        return False
        
    def generate_report(self):
        """Generate comprehensive investigation report"""
        print("\n" + "="*60)
        print("GAP INVESTIGATION COMPLETE - SUMMARY REPORT")
        print("="*60)
        
        for category, subcategories in self.findings.items():
            print(f"\n{category.upper()}:")
            print("-" * (len(category) + 1))
            
            success_found = False
            for subcategory, finding in subcategories.items():
                status = finding['status']
                details = finding['details']
                
                if status == "SUCCESS":
                    success_found = True
                    print(f"  [SUCCESS] {subcategory}: {details}")
                elif status in ["EXISTS", "INFO"]:
                    print(f"  [INFO] {subcategory}: {details}")
                elif status in ["MISSING", "FAILED"]:
                    print(f"  [MISSING] {subcategory}: {details}")
                elif status == "ERROR":
                    print(f"  [ERROR] {subcategory}: {details}")
                elif status == "SUMMARY":
                    print(f"  [SUMMARY] {details}")
                    
            # Overall status for this gap
            if success_found:
                print(f"  [RESULT] {category} API FOUND!")
            else:
                print(f"  [RESULT] {category} API NOT FOUND")
                
        # Final recommendations
        print(f"\n{'RECOMMENDATIONS':^60}")
        print("="*60)
        
        success_count = sum(1 for cat, subcat in self.findings.items() 
                          for finding in subcat.values() 
                          if finding['status'] == 'SUCCESS')
        
        if success_count == 0:
            print("- No missing APIs discovered - document comprehensive workarounds")
            print("- SystemActor -> Use LogicalActor pattern in SA context")  
            print("- PhysicalLink -> Use manual port connection approach")
            print("- NodeComponent -> Use PhysicalComponent with naming convention")
            print("- Current capability remains at 84% (11/13 core elements)")
        elif success_count <= 2:
            print(f"- {success_count} missing API(s) discovered - implement immediately")
            print("- Document remaining gaps with workarounds")
            print(f"- Capability increased to ~{84 + (success_count * 5):.0f}%")
        else:
            print(f"- Excellent! All {success_count} missing APIs discovered")
            print("- Implement all found APIs")
            print("- Capability increased to ~94%+ (near-complete ARCADIA support)")


async def main():
    model_path = r"<local-path>"
    
    print("="*60)
    print("CAPELLA MCP SERVER - GAP INVESTIGATION")
    print("Phase 2B: Systematic API Discovery for Missing Elements")
    print("="*60)
    print()
    
    investigator = GapInvestigator(model_path)
    investigator.load_model()
    
    # Save model before investigation to avoid conflicts
    print("Saving model before investigation...")
    try:
        investigator.model.save()
        print("Model saved successfully")
    except Exception as e:
        print(f"Save warning: {e}")
    print()
    
    # Investigate each gap systematically
    gaps_resolved = 0
    
    # SystemActor investigation
    if investigator.investigate_system_actor():
        gaps_resolved += 1
        
    # PhysicalLink investigation  
    if investigator.investigate_physical_link():
        gaps_resolved += 1
        
    # NodeComponent investigation
    if investigator.investigate_node_component():
        gaps_resolved += 1
        
    # Generate final report
    investigator.generate_report()
    
    print(f"[COMPLETE] INVESTIGATION COMPLETE: {gaps_resolved}/3 gaps resolved")
    
    if gaps_resolved > 0:
        print("\nNext steps:")
        print("1. Implement discovered APIs in model_manager.py")
        print("2. Add query support for new element types")
        print("3. Update test suites to validate new APIs")
        print("4. Document findings in INVESTIGATION_LOG.md")
    else:
        print("\nNext steps:")
        print("1. Proceed with Phase 3A (master test suite)")
        print("2. Document comprehensive workarounds")
        print("3. Position current 84% capability as production-ready")


if __name__ == "__main__":
    asyncio.run(main())