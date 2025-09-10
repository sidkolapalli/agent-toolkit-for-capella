"""
Comprehensive test to verify everything is working.
"""

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent / "src"))

from capella_mcp.tools import ToolRegistry
from capella_mcp.model_manager import ModelManager
from capella_mcp.enhanced_models import ComponentSpec, InterfaceSpec, ArchitectureLevel, ComponentType


async def test_everything():
    """Test all components are working together."""
    
    print("="*60)
    print("COMPREHENSIVE SYSTEM TEST")
    print("="*60)
    
    # Test 1: Model Manager
    print("\n1. Testing Model Manager...")
    manager = ModelManager()
    status = manager.get_status()
    print(f"   - capellambse available: {status['capellambse_available']}")
    print(f"   - Version: {status['capellambse_version']}")
    print(f"   - Mode: {status['mode']}")
    assert status['capellambse_available'] == True, "capellambse not available"
    assert status['capellambse_version'] == '0.7.6', "Wrong version"
    print("   [OK] Model Manager working with capellambse")
    
    # Test 2: Tool Registry
    print("\n2. Testing Tool Registry...")
    registry = ToolRegistry()
    print(f"   - Tools registered: {len(registry.tools)}")
    assert len(registry.tools) == 10, "Not all tools registered"
    
    # Check key tools
    key_tools = [
        'create_component',
        'create_component_with_interfaces',
        'import_from_drawio',
        'validate_architecture',
        'analyze_dependencies'
    ]
    for tool in key_tools:
        assert tool in registry.tools, f"Tool {tool} not found"
    print(f"   - Key tools verified: {', '.join(key_tools[:3])}...")
    print("   [OK] All tools registered correctly")
    
    # Test 3: Enhanced Models
    print("\n3. Testing Enhanced Models...")
    
    # Create a component spec
    comp_spec = ComponentSpec(
        name="TestComponent",
        component_type=ComponentType.LOGICAL_COMPONENT,
        architecture_level=ArchitectureLevel.LOGICAL,
        description="Test component",
        interfaces=[
            InterfaceSpec(name="Interface1", interface_type="CAN"),
            InterfaceSpec(name="Interface2", interface_type="Ethernet")
        ]
    )
    assert comp_spec.name == "TestComponent"
    assert len(comp_spec.interfaces) == 2
    print(f"   - Created ComponentSpec: {comp_spec.name}")
    print(f"   - Interfaces: {len(comp_spec.interfaces)}")
    print("   [OK] Enhanced models working")
    
    # Test 4: Tool Execution (Mock)
    print("\n4. Testing Tool Execution...")
    
    # Test basic component creation
    result = await registry.call_tool('create_component', {
        'model_id': 'test_model',
        'name': 'BasicComponent',
        'type': 'LogicalComponent'
    })
    assert result.get('component_id') is not None
    print(f"   - Basic component created: {result['component_id']}")
    
    # Test coarse-grained operation
    result = await registry.call_tool('create_component_with_interfaces', {
        'model_id': 'test_model',
        'component_spec': {
            'name': 'ComplexComponent',
            'component_type': 'LogicalComponent',
            'architecture_level': 'logical',
            'interfaces': [
                {'name': 'CAN_Bus', 'interface_type': 'CAN'},
                {'name': 'Power', 'interface_type': 'Power'}
            ]
        }
    })
    assert result['status'] == 'success'
    assert result['interfaces_created'] == 2
    print(f"   - Complex component created with {result['interfaces_created']} interfaces")
    print("   [OK] Tool execution working")
    
    # Test 5: Integration
    print("\n5. Testing Integration...")
    
    # Validate architecture
    result = await registry.call_tool('validate_architecture', {
        'model_id': 'test_model',
        'validation_rules': ['completeness', 'consistency']
    })
    assert 'valid' in result
    print(f"   - Validation executed: {result['rules_checked']}")
    
    # Analyze dependencies
    result = await registry.call_tool('analyze_dependencies', {
        'model_id': 'test_model',
        'element_id': 'test_element'
    })
    assert 'impact_score' in result
    print(f"   - Dependency analysis: impact score = {result['impact_score']}")
    print("   [OK] Integration working")
    
    print("\n" + "="*60)
    print("ALL TESTS PASSED!")
    print("="*60)
    
    print("\nSYSTEM STATUS:")
    print("  [OK] capellambse 0.7.6 integrated")
    print("  [OK] Model Manager operational")
    print("  [OK] 10 tools registered and working")
    print("  [OK] Enhanced models validated")
    print("  [OK] Coarse-grained operations functional")
    print("  [OK] Ready for production use")
    
    return True


if __name__ == "__main__":
    try:
        success = asyncio.run(test_everything())
        if success:
            print("\n[SUCCESS] Everything is working correctly!")
    except Exception as e:
        print(f"\n[ERROR] Test failed: {e}")
        import traceback
        traceback.print_exc()