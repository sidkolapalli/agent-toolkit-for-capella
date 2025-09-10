#!/usr/bin/env python3
"""
Delete/Undo Capability Discovery Script
Systematically explores ALL delete, undo, and reversibility capabilities in capellambse
"""

import inspect
import sys
import json
from typing import Dict, List, Any, Set
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent / "src"))

try:
    import capellambse
    from capellambse import MelodyModel
    from capellambse.model import ModelElement
    from capellambse.metamodel import capellacommon, capellacore, la, oa, sa, pa, epbs
    print("Successfully imported capellambse and core modules")
except ImportError as e:
    print(f"Import error: {e}")
    sys.exit(1)

class DeleteUndoDiscovery:
    """Comprehensive delete/undo discovery for capellambse library"""
    
    def __init__(self):
        self.discovered_capabilities = {
            'delete_methods': {},
            'remove_methods': {},
            'undo_methods': {},
            'transaction_methods': {},
            'rollback_methods': {},
            'snapshot_methods': {},
            'cache_methods': {},
            'state_methods': {},
            'advanced_capabilities': {}
        }
        self.method_signatures = {}
        
    def discover_class_capabilities(self, cls, category: str) -> Dict[str, Any]:
        """Discover delete/undo capabilities for a given class"""
        capabilities = {}
        
        # Keywords to search for
        delete_keywords = ['delete', 'remove', 'destroy', 'discard', 'purge', 'erase', 'clear']
        undo_keywords = ['undo', 'revert', 'rollback', 'restore', 'reset', 'cancel']
        state_keywords = ['save', 'load', 'backup', 'checkpoint', 'snapshot']
        transaction_keywords = ['transaction', 'commit', 'abort', 'begin', 'end']
        cache_keywords = ['cache', 'flush', 'invalidate', 'refresh']
        
        all_keywords = delete_keywords + undo_keywords + state_keywords + transaction_keywords + cache_keywords
        
        # Search through all methods and properties
        for name in dir(cls):
            if name.startswith('_'):
                continue
                
            # Check if name contains any of our keywords
            name_lower = name.lower()
            matching_keywords = [kw for kw in all_keywords if kw in name_lower]
            
            if matching_keywords:
                try:
                    attr = getattr(cls, name)
                    if callable(attr):
                        # Method discovery
                        sig = None
                        try:
                            sig = inspect.signature(attr)
                            self.method_signatures[f"{cls.__name__}.{name}"] = str(sig)
                        except (ValueError, TypeError):
                            pass
                        
                        doc = inspect.getdoc(attr)
                        capabilities[name] = {
                            'type': 'method',
                            'signature': str(sig) if sig else 'unknown',
                            'doc': doc or 'No documentation',
                            'module': getattr(attr, '__module__', 'unknown'),
                            'keywords': matching_keywords
                        }
                    else:
                        # Property discovery
                        capabilities[name] = {
                            'type': 'property',
                            'value_type': type(attr).__name__,
                            'doc': str(attr) if hasattr(attr, '__doc__') else 'Property',
                            'keywords': matching_keywords
                        }
                except Exception as e:
                    capabilities[name] = {'type': 'error', 'error': str(e)}
        
        return capabilities
    
    def discover_model_capabilities(self):
        """Discover MelodyModel delete/undo capabilities"""
        print("Discovering MelodyModel delete/undo capabilities...")
        
        # Core model class
        model_caps = self.discover_class_capabilities(MelodyModel, 'model')
        
        # Categorize by capability type
        for name, info in model_caps.items():
            if 'keywords' in info:
                keywords = info['keywords']
                if any(kw in ['delete', 'remove', 'destroy', 'discard', 'purge', 'erase'] for kw in keywords):
                    self.discovered_capabilities['delete_methods'][f"MelodyModel.{name}"] = info
                if any(kw in ['undo', 'revert', 'rollback', 'restore', 'reset'] for kw in keywords):
                    self.discovered_capabilities['undo_methods'][f"MelodyModel.{name}"] = info
                if any(kw in ['transaction', 'commit', 'abort', 'begin', 'end'] for kw in keywords):
                    self.discovered_capabilities['transaction_methods'][f"MelodyModel.{name}"] = info
                if any(kw in ['save', 'load', 'backup', 'checkpoint', 'snapshot'] for kw in keywords):
                    self.discovered_capabilities['snapshot_methods'][f"MelodyModel.{name}"] = info
                if any(kw in ['cache', 'flush', 'invalidate', 'refresh'] for kw in keywords):
                    self.discovered_capabilities['cache_methods'][f"MelodyModel.{name}"] = info
                if 'clear' in keywords:
                    self.discovered_capabilities['state_methods'][f"MelodyModel.{name}"] = info
        
        print(f"   Found {len(model_caps)} potential methods in MelodyModel")
    
    def discover_element_capabilities(self):
        """Discover ModelElement delete/undo capabilities"""
        print("Discovering ModelElement delete/undo capabilities...")
        
        element_caps = self.discover_class_capabilities(ModelElement, 'element')
        
        # Categorize by capability type
        for name, info in element_caps.items():
            if 'keywords' in info:
                keywords = info['keywords']
                if any(kw in ['delete', 'remove', 'destroy', 'discard', 'purge', 'erase'] for kw in keywords):
                    self.discovered_capabilities['delete_methods'][f"ModelElement.{name}"] = info
                if any(kw in ['undo', 'revert', 'rollback', 'restore', 'reset'] for kw in keywords):
                    self.discovered_capabilities['undo_methods'][f"ModelElement.{name}"] = info
                if any(kw in ['transaction', 'commit', 'abort', 'begin', 'end'] for kw in keywords):
                    self.discovered_capabilities['transaction_methods'][f"ModelElement.{name}"] = info
                if any(kw in ['save', 'load', 'backup', 'checkpoint', 'snapshot'] for kw in keywords):
                    self.discovered_capabilities['snapshot_methods'][f"ModelElement.{name}"] = info
                if any(kw in ['cache', 'flush', 'invalidate', 'refresh'] for kw in keywords):
                    self.discovered_capabilities['cache_methods'][f"ModelElement.{name}"] = info
        
        print(f"   Found {len(element_caps)} potential methods in ModelElement")
    
    def discover_capellambse_capabilities(self):
        """Discover top-level capellambse module capabilities"""
        print("Discovering capellambse module delete/undo capabilities...")
        
        capabilities_found = {}
        
        # Search in main capellambse module
        for name in dir(capellambse):
            if not name.startswith('_'):
                name_lower = name.lower()
                delete_keywords = ['delete', 'remove', 'destroy', 'discard', 'purge', 'erase', 'clear']
                undo_keywords = ['undo', 'revert', 'rollback', 'restore', 'reset', 'cancel']
                
                matching_keywords = []
                for kw in delete_keywords + undo_keywords:
                    if kw in name_lower:
                        matching_keywords.append(kw)
                
                if matching_keywords:
                    try:
                        attr = getattr(capellambse, name)
                        if callable(attr):
                            sig = inspect.signature(attr)
                            capabilities_found[name] = {
                                'type': 'function',
                                'signature': str(sig),
                                'doc': inspect.getdoc(attr) or 'No documentation',
                                'module': 'capellambse',
                                'keywords': matching_keywords
                            }
                    except Exception as e:
                        capabilities_found[name] = {'error': str(e)}
        
        self.discovered_capabilities['advanced_capabilities'].update(capabilities_found)
        print(f"   Found {len(capabilities_found)} potential functions in capellambse")
    
    def test_element_manipulation(self):
        """Test if elements can be manipulated in ways that simulate deletion"""
        print("Testing element manipulation capabilities...")
        
        test_results = {
            'can_hide_elements': False,
            'can_deactivate_elements': False,
            'can_mark_inactive': False,
            'can_set_properties': False,
            'property_based_hiding': []
        }
        
        # Test common properties that might allow hiding/deactivation
        test_properties = [
            'active', 'inactive', 'enabled', 'disabled', 'visible', 'hidden',
            'status', 'state', 'valid', 'invalid', 'deleted', 'removed'
        ]
        
        try:
            # We can't test without a real model, but we can check if ModelElement
            # has properties that might support virtual deletion
            for prop in test_properties:
                if hasattr(ModelElement, prop):
                    test_results['property_based_hiding'].append(prop)
                    test_results['can_set_properties'] = True
        except Exception as e:
            test_results['error'] = str(e)
        
        self.discovered_capabilities['virtual_deletion_test'] = test_results
        print(f"   Found {len(test_results['property_based_hiding'])} potential hiding properties")
    
    def analyze_current_implementation(self):
        """Analyze our current implementation to see what's already there"""
        print("Analyzing current implementation...")
        
        current_implementation = {}
        
        try:
            # Load our current files to see what we're already using
            files_to_check = [
                Path(__file__).parent / "src" / "capella_mcp" / "model_manager.py",
                Path(__file__).parent / "src" / "capella_mcp" / "transaction_manager.py",
                Path(__file__).parent / "src" / "capella_mcp" / "tools.py"
            ]
            
            for file_path in files_to_check:
                if file_path.exists():
                    with open(file_path, 'r', encoding='utf-8') as f:
                        content = f.read()
                    
                    filename = file_path.name
                    current_implementation[filename] = {
                        'has_delete_methods': 'delete_element' in content,
                        'has_transaction_methods': 'transaction' in content,
                        'has_rollback_methods': 'rollback' in content,
                        'has_undo_methods': 'undo' in content,
                        'has_snapshot_methods': 'snapshot' in content,
                        'lines_of_code': len(content.split('\n'))
                    }
        except Exception as e:
            current_implementation['error'] = str(e)
        
        self.discovered_capabilities['current_implementation'] = current_implementation
    
    def generate_implementation_recommendations(self):
        """Generate specific recommendations based on discoveries"""
        print("Generating implementation recommendations...")
        
        recommendations = {
            'high_priority': [],
            'medium_priority': [],
            'workarounds_needed': [],
            'implementation_strategy': {}
        }
        
        # Analyze what we found
        delete_methods = len(self.discovered_capabilities['delete_methods'])
        undo_methods = len(self.discovered_capabilities['undo_methods'])
        transaction_methods = len(self.discovered_capabilities['transaction_methods'])
        snapshot_methods = len(self.discovered_capabilities['snapshot_methods'])
        
        # High priority recommendations
        if delete_methods == 0:
            recommendations['workarounds_needed'].append({
                'issue': 'No native delete methods found',
                'workaround': 'Implement virtual deletion with element marking'
            })
        
        if undo_methods == 0:
            recommendations['workarounds_needed'].append({
                'issue': 'No native undo methods found',
                'workaround': 'Implement operation history with state restoration'
            })
        
        if transaction_methods > 0:
            recommendations['high_priority'].append({
                'capability': 'Transaction support exists',
                'action': 'Integrate existing transaction methods',
                'methods': list(self.discovered_capabilities['transaction_methods'].keys())
            })
        
        if snapshot_methods > 0:
            recommendations['high_priority'].append({
                'capability': 'Snapshot support exists', 
                'action': 'Implement automatic snapshots',
                'methods': list(self.discovered_capabilities['snapshot_methods'].keys())
            })
        
        # Implementation strategy
        recommendations['implementation_strategy'] = {
            'approach': 'Multi-layer hybrid',
            'layers': [
                'Virtual deletion (element marking)',
                'Operation history tracking',
                'Automatic snapshots',
                'Transaction integration',
                'Undo command interface'
            ]
        }
        
        self.discovered_capabilities['recommendations'] = recommendations
    
    def run_discovery(self):
        """Run the complete delete/undo discovery process"""
        print("Starting Comprehensive Delete/Undo Discovery")
        print("=" * 60)
        
        try:
            self.discover_model_capabilities()
            self.discover_element_capabilities()
            self.discover_capellambse_capabilities()
            self.test_element_manipulation()
            self.analyze_current_implementation()
            self.generate_implementation_recommendations()
            
            # Generate summary statistics
            total_capabilities = sum(len(caps) for caps in self.discovered_capabilities.values() if isinstance(caps, dict))
            print(f"\nDiscovery Summary:")
            print(f"   Delete methods found: {len(self.discovered_capabilities['delete_methods'])}")
            print(f"   Undo methods found: {len(self.discovered_capabilities['undo_methods'])}")
            print(f"   Transaction methods found: {len(self.discovered_capabilities['transaction_methods'])}")
            print(f"   Snapshot methods found: {len(self.discovered_capabilities['snapshot_methods'])}")
            print(f"   Total capabilities discovered: {total_capabilities}")
            
            # Save results to JSON for analysis
            output_file = Path(__file__).parent / "delete_undo_discovery_results.json"
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(self.discovered_capabilities, f, indent=2, default=str)
            
            print(f"Results saved to: {output_file}")
            
            return self.discovered_capabilities
            
        except Exception as e:
            print(f"Discovery error: {e}")
            import traceback
            traceback.print_exc()
            return None

def main():
    """Run the delete/undo discovery"""
    discovery = DeleteUndoDiscovery()
    results = discovery.run_discovery()
    
    if results:
        print("\nCritical Findings:")
        recommendations = results.get('recommendations', {})
        
        # Show workarounds needed
        for workaround in recommendations.get('workarounds_needed', []):
            print(f"\n   ❌ {workaround['issue']}")
            print(f"   ✅ Solution: {workaround['workaround']}")
        
        # Show implementation strategy
        strategy = recommendations.get('implementation_strategy', {})
        print(f"\n   🏗️  Recommended Approach: {strategy.get('approach', 'Unknown')}")
        for i, layer in enumerate(strategy.get('layers', []), 1):
            print(f"      {i}. {layer}")
    
    return results

if __name__ == "__main__":
    main()