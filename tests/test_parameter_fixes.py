#!/usr/bin/env python3
"""
Test Parameter Fixes for MCP Tools
Tests that the 3 previously broken tools now work with correct parameter mapping
"""

import asyncio
import sys
import os
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent / "src"))

async def test_parameter_fixes():
    """Test the 3 fixed MCP tools"""
    
    print(">> Testing Parameter Fixes for MCP Tools")
    
    try:
        from capella_mcp.server import mcp, load_model
        
        # Load the model first
        model_path = r"<local-path>"
        load_result = await load_model(model_path)
        print(f"Model loaded: {load_result}")
        
        # Extract model_id from load result
        if "model_" in load_result:
            model_id = load_result.split("ID: ")[1].split("\n")[0] if "ID: " in load_result else "model_1"
        else:
            model_id = "model_1"
        print(f"Using model_id: {model_id}")
        
        # Test 1: create_checkpoint (FIXED - now includes model_id parameter)
        print("\n=== Test 1: create_checkpoint ===")
        try:
            checkpoint_result = await mcp.call_tool('create_checkpoint', {
                'model_id': model_id,
                'checkpoint_id': 'TEST_PARAMETER_FIX',
                'description': 'Test checkpoint to verify parameter fix'
            })
            
            # FastMCP call_tool returns a CallToolResult object with .data property
            if hasattr(checkpoint_result, 'data'):
                result_data = checkpoint_result.data
                if isinstance(result_data, dict) and result_data.get('status') == 'success':
                    print("PASS: create_checkpoint - Parameter fix successful!")
                    print(f"  Checkpoint created: {result_data.get('checkpoint_id')}")
                else:
                    print(f"FAIL: create_checkpoint - {result_data}")
            else:
                print(f"UNEXPECTED: create_checkpoint returned: {type(checkpoint_result)} - {checkpoint_result}")
        except Exception as e:
            print(f"FAIL: create_checkpoint failed: {e}")
        
        # Test 2: Get components for delete/modify tests
        print("\n=== Getting Test Components ===")
        try:
            elements = await mcp.call_tool('get_model_elements', {
                'model_id': model_id,
                'element_type': 'LogicalComponent'
            })
            
            if elements and 'elements' in elements and elements['elements']:
                test_components = elements['elements'][:2]  # Get first 2 components
                print(f"Found {len(test_components)} test components")
                
                # Test 3: delete_component (FIXED - element_id -> component_id mapping)
                print("\n=== Test 2: delete_component ===")
                test_component_id = test_components[0]['id']
                try:
                    delete_result = await mcp.call_tool('delete_component', {
                        'model_id': model_id,
                        'element_id': test_component_id,  # MCP interface uses element_id
                        'reason': 'Testing parameter fix'
                    })
                    
                    if delete_result.get('status') == 'success':
                        print("PASS: delete_component - Parameter fix successful!")
                        print(f"  Component deleted: {delete_result.get('component_id')}")
                    else:
                        print(f"FAIL: delete_component - {delete_result}")
                except Exception as e:
                    print(f"FAIL: delete_component failed: {e}")
                
                # Test 4: modify_component (FIXED - element_id/updates -> component_id/changes mapping)
                if len(test_components) > 1:
                    print("\n=== Test 3: modify_component ===")
                    modify_component_id = test_components[1]['id']
                    original_name = test_components[1].get('name', 'Component')
                    try:
                        modify_result = await mcp.call_tool('modify_component', {
                            'model_id': model_id,
                            'element_id': modify_component_id,  # MCP interface uses element_id
                            'updates': {  # MCP interface uses updates
                                'name': f'{original_name}_PARAM_FIX_TEST',
                                'description': 'Modified to test parameter fix'
                            }
                        })
                        
                        if modify_result.get('status') == 'success':
                            print("PASS: modify_component - Parameter fix successful!")
                            print(f"  Component modified: {modify_result.get('component_id')}")
                        else:
                            print(f"FAIL: modify_component - {modify_result}")
                    except Exception as e:
                        print(f"FAIL: modify_component failed: {e}")
                        
            else:
                print("WARNING: No LogicalComponents found for delete/modify tests")
                
        except Exception as e:
            print(f"FAIL: Could not get test components: {e}")
        
        print("\n" + "="*60)
        print("PARAMETER FIX TESTING COMPLETE")
        print("All 3 tools should now work with proper parameter mapping:")
        print("- delete_component: element_id -> component_id")  
        print("- modify_component: element_id/updates -> component_id/changes")
        print("- create_checkpoint: Added model_id, checkpoint_id -> checkpoint_name")
        print("="*60)
            
    except Exception as e:
        print(f"Test setup failed: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    # Set environment variable for model path
    os.environ['CAPELLA_MODEL_PATH'] = r"<local-path>"
    
    print("Starting Parameter Fix Tests")
    print("Testing the 3 previously broken MCP tools with corrected parameter mapping")
    print()
    
    try:
        asyncio.run(test_parameter_fixes())
    except KeyboardInterrupt:
        print("\nTest interrupted by user")
    except Exception as e:
        print(f"\nTest failed with exception: {e}")
        import traceback
        traceback.print_exc()