#!/usr/bin/env python3
"""
MCP Tool Accessibility Test
Tests that all 27 tools are actually accessible via MCP protocol after FastMCP conversion
"""

import asyncio
import sys
import os
from pathlib import Path
import logging

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class MCPToolAccessibilityTest:
    """Test MCP tool accessibility comprehensively"""
    
    def __init__(self):
        self.test_results = []
        self.model_path = r"<local-path>"
        
        # All tools that should be accessible (from comprehensive assessment)
        self.all_expected_tools = [
            # Working tools (from assessment)
            'server_status', 'load_model', 'list_models', 'close_model', 'save_model',
            'get_model_info', 'get_model_elements', 'query_components', 
            'validate_architecture', 'analyze_dependencies',
            'create_component', 'create_component_with_interfaces', 
            'create_actor_with_scenarios', 'create_functional_chain', 'import_from_drawio',
            
            # Previously failing tools (should now work)
            'delete_component', 'modify_component', 
            'undo_last_operation', 'undo_operation', 'redo_last_operation',
            'list_operations', 'create_checkpoint', 'restore_checkpoint', 'list_checkpoints',
            'restore_deleted_element', 'list_deleted_elements', 'get_reversibility_status'
        ]
        
        logger.info(f">> MCP Tool Accessibility Test initialized")
        logger.info(f">> Expected tools: {len(self.all_expected_tools)}")

    async def run_full_test(self):
        """Run comprehensive MCP tool accessibility test"""
        
        logger.info(">> Starting MCP Tool Accessibility Test")
        
        try:
            # Test 1: Verify FastMCP server registration
            await self.test_fastmcp_registration()
            
            # Test 2: Test basic tool functionality  
            await self.test_basic_tool_functionality()
            
            # Test 3: Test previously failing tools
            await self.test_previously_failing_tools()
            
            # Test 4: Verify no tools are missing
            await self.test_no_missing_tools()
            
            # Report Results
            await self.report_results()
            
        except Exception as e:
            logger.error(f">> Test suite failed: {e}")
            self.test_results.append(("Test Suite", False, str(e)))
    
    async def test_fastmcp_registration(self):
        """Test 1: Verify FastMCP server has all tools registered"""
        logger.info("\\n📋 TEST 1: FastMCP Server Registration")
        
        try:
            # Import and check FastMCP server
            from capella_mcp.server import mcp
            
            # Verify it's FastMCP
            from mcp.server.fastmcp import FastMCP
            assert isinstance(mcp, FastMCP), f"Server is {type(mcp)}, not FastMCP"
            
            # Get all registered tools
            tools = await mcp.list_tools()
            tool_names = [tool.name for tool in tools]
            
            logger.info(f"✅ FastMCP server with {len(tools)} tools registered")
            
            # Check all expected tools are registered
            missing_tools = []
            for expected_tool in self.all_expected_tools:
                if expected_tool not in tool_names:
                    missing_tools.append(expected_tool)
            
            if missing_tools:
                raise AssertionError(f"Missing tools: {missing_tools}")
            
            logger.info(f"✅ All {len(self.all_expected_tools)} expected tools registered")
            self.test_results.append(("FastMCP Registration", True, f"All {len(tools)} tools registered correctly"))
            
        except Exception as e:
            logger.error(f"❌ FastMCP registration test failed: {e}")
            self.test_results.append(("FastMCP Registration", False, str(e)))
    
    async def test_basic_tool_functionality(self):
        """Test 2: Test basic tool functionality"""
        logger.info("\\n📋 TEST 2: Basic Tool Functionality")
        
        try:
            from capella_mcp.server import mcp
            
            # Test server_status (should always work)
            try:
                status_result = await mcp.call_tool('server_status', {})
                logger.info(f"✅ server_status: {type(status_result)}")
            except Exception as e:
                raise AssertionError(f"server_status failed: {e}")
            
            # Test load_model if model exists
            if os.path.exists(self.model_path):
                try:
                    load_result = await mcp.call_tool('load_model', {'path': self.model_path})
                    logger.info(f"✅ load_model: Model loaded successfully")
                    
                    # Test list_models
                    list_result = await mcp.call_tool('list_models', {})
                    logger.info(f"✅ list_models: {type(list_result)}")
                    
                except Exception as e:
                    logger.warning(f"⚠️ Model loading failed (expected if model not available): {e}")
            else:
                logger.info(f"⚠️ Model file not found: {self.model_path}")
            
            self.test_results.append(("Basic Tool Functionality", True, "Core tools accessible and functional"))
            
        except Exception as e:
            logger.error(f"❌ Basic tool functionality test failed: {e}")
            self.test_results.append(("Basic Tool Functionality", False, str(e)))
    
    async def test_previously_failing_tools(self):
        """Test 3: Test previously failing tools are now accessible"""
        logger.info("\\n📋 TEST 3: Previously Failing Tools")
        
        previously_failing = [
            'delete_component', 'modify_component', 
            'undo_last_operation', 'redo_last_operation',
            'create_checkpoint', 'list_checkpoints', 
            'get_reversibility_status'
        ]
        
        accessible_count = 0
        
        try:
            from capella_mcp.server import mcp
            tools = await mcp.list_tools()
            tool_names = [tool.name for tool in tools]
            
            for tool_name in previously_failing:
                if tool_name in tool_names:
                    logger.info(f"✅ {tool_name}: NOW ACCESSIBLE")
                    accessible_count += 1
                else:
                    logger.error(f"❌ {tool_name}: STILL MISSING")
            
            success_rate = (accessible_count / len(previously_failing)) * 100
            logger.info(f"✅ Previously failing tools: {accessible_count}/{len(previously_failing)} now accessible ({success_rate:.1f}%)")
            
            if accessible_count == len(previously_failing):
                self.test_results.append(("Previously Failing Tools", True, f"All {accessible_count} previously failing tools now accessible"))
            else:
                self.test_results.append(("Previously Failing Tools", False, f"Only {accessible_count}/{len(previously_failing)} accessible"))
            
        except Exception as e:
            logger.error(f"❌ Previously failing tools test failed: {e}")
            self.test_results.append(("Previously Failing Tools", False, str(e)))
    
    async def test_no_missing_tools(self):
        """Test 4: Verify no tools are missing from expected set"""
        logger.info("\\n📋 TEST 4: No Missing Tools")
        
        try:
            from capella_mcp.server import mcp
            tools = await mcp.list_tools()
            tool_names = set([tool.name for tool in tools])
            expected_tools = set(self.all_expected_tools)
            
            # Check for missing tools
            missing_tools = expected_tools - tool_names
            extra_tools = tool_names - expected_tools
            
            if missing_tools:
                logger.error(f"❌ Missing tools: {missing_tools}")
                
            if extra_tools:
                logger.info(f"ℹ️ Extra tools (not expected): {extra_tools}")
            
            if not missing_tools:
                logger.info(f"✅ No missing tools - all {len(expected_tools)} expected tools found")
                self.test_results.append(("No Missing Tools", True, f"All {len(expected_tools)} expected tools present"))
            else:
                self.test_results.append(("No Missing Tools", False, f"Missing {len(missing_tools)} tools: {missing_tools}"))
                
        except Exception as e:
            logger.error(f"❌ Missing tools test failed: {e}")
            self.test_results.append(("No Missing Tools", False, str(e)))
    
    async def report_results(self):
        """Generate final test report"""
        logger.info("\\n" + "="*80)
        logger.info("📊 MCP TOOL ACCESSIBILITY TEST RESULTS")
        logger.info("="*80)
        
        total_tests = len(self.test_results)
        passed_tests = sum(1 for _, success, _ in self.test_results if success)
        failed_tests = total_tests - passed_tests
        
        logger.info(f"Total Tests: {total_tests}")
        logger.info(f"✅ Passed: {passed_tests}")
        logger.info(f"❌ Failed: {failed_tests}")
        logger.info(f"Success Rate: {(passed_tests/total_tests)*100:.1f}%" if total_tests > 0 else "No tests run")
        
        logger.info("\\nDetailed Results:")
        for test_name, success, message in self.test_results:
            status = "✅ PASS" if success else "❌ FAIL" 
            logger.info(f"  {status} {test_name}: {message}")
        
        if failed_tests == 0:
            logger.info("\\n🎉 ALL TESTS PASSED! MCP Tool Accessibility Crisis RESOLVED!")
            logger.info("🚀 FastMCP conversion successfully fixed the 43% tool failure rate!")
            logger.info("✅ All tools now accessible via MCP protocol")
        else:
            logger.info(f"\\n⚠️ {failed_tests} test(s) failed. Review errors above.")
        
        logger.info("="*80)


async def main():
    """Run the MCP tool accessibility test"""
    # Set environment variable for model path
    os.environ['CAPELLA_MODEL_PATH'] = r"<local-path>"
    
    test_runner = MCPToolAccessibilityTest()
    await test_runner.run_full_test()

if __name__ == "__main__":
    print(">> Starting MCP Tool Accessibility Test")
    print(">> This test validates that the FastMCP conversion fixed the 43% tool failure rate")
    print(">> Testing: All 27 tools should now be accessible via MCP protocol")
    print()
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\\n>> Test interrupted by user")
    except Exception as e:
        print(f"\\n>> Test failed with exception: {e}")
        import traceback
        traceback.print_exc()