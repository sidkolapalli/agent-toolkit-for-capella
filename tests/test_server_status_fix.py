#!/usr/bin/env python3
"""
TDD Test: Verify server_status() returns FastMCP tools (fixes tool visibility crisis)

This test validates that server_status() now returns all 32 FastMCP registered tools
instead of just the 21 ToolRegistry tools.
"""

import asyncio
import sys
from pathlib import Path

# Add src to path for imports  
sys.path.insert(0, 'src')

async def test_server_status_fix():
    """TDD Test: server_status() should return FastMCP tools (32) not ToolRegistry tools (21)"""
    
    print("=== TDD TEST: Server Status Tool Visibility Fix ===")
    
    try:
        from capella_mcp.server import server_status, mcp
        
        # Test 1: Get FastMCP tools directly (expected behavior)
        fastmcp_tools = await mcp.list_tools()
        fastmcp_count = len(fastmcp_tools)
        fastmcp_names = [t.name for t in fastmcp_tools]
        
        print(f"PASS FastMCP direct access: {fastmcp_count} tools")
        
        # Test 2: Get tools via server_status() (should now match FastMCP)
        status = server_status()
        server_status_count = status.get("tools_available", 0)
        server_status_names = status.get("tools", [])
        
        print(f"{'PASS' if server_status_count == fastmcp_count else 'FAIL'} server_status() reports: {server_status_count} tools")
        
        # Test 3: Verify critical Stage 2 tools are visible
        stage2_tools = ['search_elements_advanced', 'find_references_working', 'get_element_details']
        stage2_visibility = {}
        
        for tool in stage2_tools:
            visible_in_fastmcp = tool in fastmcp_names
            visible_in_status = tool in server_status_names
            stage2_visibility[tool] = {
                'fastmcp': visible_in_fastmcp,
                'server_status': visible_in_status,
                'fixed': visible_in_fastmcp and visible_in_status
            }
        
        print(f"\n=== Stage 2 Tools Visibility ===")
        for tool, visibility in stage2_visibility.items():
            status_icon = "PASS" if visibility['fixed'] else "FAIL"
            print(f"{status_icon} {tool}: FastMCP={visibility['fastmcp']}, Status={visibility['server_status']}")
        
        # Test Summary
        all_stage2_visible = all(v['fixed'] for v in stage2_visibility.values())
        tools_count_match = server_status_count == fastmcp_count
        
        if tools_count_match and all_stage2_visible:
            print(f"\nSUCCESS: Tool visibility crisis FIXED!")
            print(f"   - Total tools: {fastmcp_count} (FastMCP) = {server_status_count} (server_status)")
            print(f"   - Stage 2 tools: All visible")
            print(f"   - MCP client should now see all tools")
            return True
        else:
            print(f"\nFAILED: Tool visibility crisis NOT FIXED")
            print(f"   - Tools count match: {tools_count_match}")
            print(f"   - Stage 2 tools visible: {all_stage2_visible}")
            return False
            
    except Exception as e:
        print(f"TEST ERROR: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    success = asyncio.run(test_server_status_fix())
    sys.exit(0 if success else 1)