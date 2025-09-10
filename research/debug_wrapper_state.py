#!/usr/bin/env python3
"""
Debug script to examine wrapper state in MCP context vs API discovery context
"""

import asyncio
import sys
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, 'src')

async def debug_wrapper_state():
    """Debug wrapper state in both contexts"""
    
    print("=== MCP WRAPPER STATE DEBUG ===")
    
    try:
        # Import MCP components
        from capella_mcp.server import (
            load_model, 
            ENHANCED_AVAILABLE,
            model_manager
        )
        
        print(f"Enhanced tools available: {ENHANCED_AVAILABLE}")
        print(f"Model manager available: {model_manager is not None}")
        
        # Load model through MCP system
        model_path = '<local-path>'
        load_result = await load_model(model_path)
        print(f"Load result: {load_result}")
        
        if isinstance(load_result, str) and "Successfully loaded" in load_result:
            # Extract model ID
            id_part = load_result.split("ID: ")[1].strip()
            model_id = id_part.split('\n')[0].strip()
            print(f"Model ID: {model_id}")
            
            # Get wrapper from model_manager
            wrapper = model_manager.get_model(model_id)
            print(f"Wrapper exists: {wrapper is not None}")
            print(f"Wrapper type: {type(wrapper)}")
            print(f"Wrapper attributes: {[attr for attr in dir(wrapper) if not attr.startswith('_')]}")
            
            # Check if wrapper has .model attribute
            if hasattr(wrapper, 'model'):
                print(f"wrapper.model exists: True")
                print(f"wrapper.model type: {type(wrapper.model)}")
                
                # Check if wrapper.model has by_uuid method
                if hasattr(wrapper.model, 'by_uuid'):
                    print(f"wrapper.model.by_uuid exists: True")
                    print(f"wrapper.model.by_uuid type: {type(wrapper.model.by_uuid)}")
                else:
                    print(f"wrapper.model.by_uuid exists: False")
                    print(f"wrapper.model methods with 'uuid': {[m for m in dir(wrapper.model) if 'uuid' in m.lower()]}")
            else:
                print(f"wrapper.model exists: False")
                print(f"Available wrapper methods: {[attr for attr in dir(wrapper) if not attr.startswith('_') and callable(getattr(wrapper, attr, None))]}")
                
            # Test wrapper.get_element() method (known to work)
            if hasattr(wrapper, 'get_element'):
                print(f"wrapper.get_element exists: True")
                try:
                    test_uuid = "a540dd0b-8280-4583-a694-f2454a05620a"
                    element = wrapper.get_element(test_uuid)
                    print(f"wrapper.get_element() works: {type(element)}")
                except Exception as e:
                    print(f"wrapper.get_element() failed: {e}")
            else:
                print(f"wrapper.get_element exists: False")
                
    except Exception as e:
        print(f"CRITICAL ERROR: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(debug_wrapper_state())