#!/usr/bin/env python3
"""
Debug script to check the actual schema generated for traverse_element_relationships
"""

import asyncio
import sys
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, 'src')

async def debug_schema():
    """Debug the actual schema for traverse_element_relationships"""
    
    try:
        from capella_mcp.server import mcp
        
        # Get all tools
        tools = await mcp.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "traverse_element_relationships" in tool_dict:
            tool = tool_dict["traverse_element_relationships"]
            
            print("=== TRAVERSE_ELEMENT_RELATIONSHIPS SCHEMA ===")
            print(f"Tool name: {tool.name}")
            print(f"Schema: {tool.inputSchema}")
            
            if "properties" in tool.inputSchema:
                properties = tool.inputSchema["properties"]
                print("\n=== PROPERTIES ===")
                for prop_name, prop_schema in properties.items():
                    print(f"{prop_name}: {prop_schema}")
            
            print("\n=== ANNOTATIONS ===")
            print(f"Annotations: {tool.annotations}")
            
        else:
            print("traverse_element_relationships tool not found!")
            print("Available tools:", [t.name for t in tools if "traverse" in t.name.lower()])
            
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(debug_schema())