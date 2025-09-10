#!/usr/bin/env python3
"""
Debug script to test traverse_element_relationships with simple mocking
"""

import asyncio
import sys
from pathlib import Path
from unittest.mock import patch, MagicMock

# Add src to path for imports
sys.path.insert(0, 'src')

async def debug_traverse_test():
    """Debug the traverse_element_relationships with basic test"""
    
    try:
        from capella_mcp.server import traverse_element_relationships, model_manager
        
        # Mock the model_manager to return a mock wrapper
        mock_wrapper = MagicMock()
        
        with patch.object(model_manager, 'get_model', return_value=mock_wrapper):
            
            result = await traverse_element_relationships(
                model_id="test_model",
                element_id="test-element-123",
                max_depth=1,
                bidirectional=False  # Start simple
            )
            
            print("=== TRAVERSE RESULT ===")
            print(f"Result keys: {result.keys()}")
            print(f"Status: {result.get('success', result.get('error'))}")
            
            if result.get("error"):
                print(f"Error: {result['error']}")
            elif result.get("network"):
                network = result["network"]
                print(f"Network keys: {network.keys()}")
                print(f"Metadata: {network.get('metadata', {})}")
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(debug_traverse_test())