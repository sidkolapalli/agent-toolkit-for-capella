#!/usr/bin/env python3
"""Check if Stage 2 tools are registered in FastMCP"""

import asyncio
import sys
sys.path.insert(0, 'src')

async def check_tools():
    from capella_mcp.server import mcp
    
    tools = await mcp.list_tools()
    print(f'Total FastMCP tools: {len(tools)}')
    
    stage2_tools = [
        'search_elements_advanced',
        'find_all_references', 
        'get_element_by_uuid',
        'find_references_working',
        'get_element_details'
    ]
    
    print('\nStage 2 Tools Registration Status:')
    for name in stage2_tools:
        found = any(t.name == name for t in tools)
        print(f'  {name}: {"✓ FOUND" if found else "✗ NOT FOUND"}')
    
    # Show all tool names
    print(f'\nAll {len(tools)} registered tools:')
    for i, tool in enumerate(tools, 1):
        print(f'  {i}. {tool.name}')

if __name__ == "__main__":
    asyncio.run(check_tools())