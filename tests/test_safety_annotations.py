#!/usr/bin/env python3
"""
Test Safety Annotations - Stage 1.1 TDD Implementation
======================================================

This module tests MCP tool safety annotations to prevent accidental operations.
Following MCP best practices, tools must be properly annotated:

- destructiveHint: True for operations that modify/delete data
- readOnlyHint: True for operations that only query data  
- idempotentHint: True for operations safe to repeat

Tests written first (TDD) to ensure proper safety implementation.
"""

import pytest
import asyncio
from unittest.mock import patch, MagicMock
from mcp.server.fastmcp import FastMCP

# Import the server components
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.server import mcp as app, model_manager


class TestSafetyAnnotations:
    """Test MCP tool safety annotations for accident prevention."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
async def test_destructive_operations_marked(self):
    """Test that destructive operations have destructiveHint: True."""
    
    # Get all registered tools (await the coroutine)
    tools = await self.app.list_tools()
    tool_dict = {tool.name: tool for tool in tools}
    
    # Critical: delete_component must have destructiveHint
    assert "delete_component" in tool_dict, "delete_component tool not found"
    delete_tool = tool_dict["delete_component"]
    
    # This should pass after implementation
    assert hasattr(delete_tool, 'annotations'), "delete_component missing annotations"
    assert delete_tool.annotations.get('destructiveHint') is True, \
        "delete_component missing destructiveHint: True (SAFETY CRITICAL)"
    
    # Also check modify_component (modifies data)
    if "modify_component" in tool_dict:
        modify_tool = tool_dict["modify_component"]
        assert hasattr(modify_tool, 'annotations'), "modify_component missing annotations"
        assert modify_tool.annotations.get('destructiveHint') is True, \
            "modify_component should have destructiveHint: True"
    
    @pytest.mark.asyncio
async def test_readonly_operations_marked(self):
    """Test that read-only operations have readOnlyHint: True."""
    
    # Get all registered tools (await the coroutine)
    tools = await self.app.list_tools()
    tool_dict = {tool.name: tool for tool in tools}
    
    # Define expected read-only tools
    readonly_tools = [
        "list_models",
        "query_components", 
        "get_model_elements",
        "get_model_info",
        "list_operations",
        "list_checkpoints", 
        "list_deleted_elements",
        "get_reversibility_status"
    ]
    
    for tool_name in readonly_tools:
        if tool_name in tool_dict:
            tool = tool_dict[tool_name]
            assert hasattr(tool, 'annotations'), f"{tool_name} missing annotations"
            assert tool.annotations.get('readOnlyHint') is True, \
                f"{tool_name} should have readOnlyHint: True"
    
    def test_idempotent_operations_marked(self):
        """Test that idempotent operations have idempotentHint: True."""
        
        # Get all registered tools
        tools = self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # save_model is idempotent (safe to call multiple times)
        if "save_model" in tool_dict:
            save_tool = tool_dict["save_model"]
            assert hasattr(save_tool, 'annotations'), "save_model missing annotations"
            assert save_tool.annotations.get('idempotentHint') is True, \
                "save_model should have idempotentHint: True"
    
    def test_annotations_in_tool_schema(self):
        """Test that annotations appear in MCP tool schema."""
        
        # Get tool schema for delete_component
        tools = self.app.list_tools()
        delete_tools = [t for t in tools if t.name == "delete_component"]
        
        assert len(delete_tools) > 0, "delete_component tool not found"
        delete_tool = delete_tools[0]
        
        # Check that the tool has annotations attribute
        assert hasattr(delete_tool, 'annotations'), \
            "Tool schema missing annotations attribute"
        
        # Check that destructiveHint is properly exposed
        annotations = delete_tool.annotations
        assert isinstance(annotations, dict), "Annotations should be a dictionary"
        assert 'destructiveHint' in annotations, \
            "destructiveHint not in tool annotations"
    
    @pytest.mark.asyncio
    async def test_delete_component_safety_warning(self):
        """Test that delete_component includes safety warning in description."""
        
        # Mock a model for testing
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "test-element-123"
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            with patch.object(self.model_manager, 'models', {"test_model": mock_model}):
                
                # Get the delete tool
                tools = self.app.list_tools()
                delete_tools = [t for t in tools if t.name == "delete_component"]
                
                assert len(delete_tools) > 0, "delete_component tool not found"
                delete_tool = delete_tools[0]
                
                # Check description includes safety warning
                description = delete_tool.description.lower()
                safety_keywords = [
                    "destructive", "permanently", "cannot be recovered", 
                    "caution", "backup", "checkpoint"
                ]
                
                found_safety_warning = any(keyword in description for keyword in safety_keywords)
                assert found_safety_warning, \
                    f"delete_component description should include safety warning. " \
                    f"Current description: {delete_tool.description}"
    
    def test_all_tools_have_appropriate_annotations(self):
        """Test that all tools have at least one appropriate annotation."""
        
        # Get all registered tools
        tools = self.app.list_tools()
        
        # Define tool categories and expected annotations
        tool_categories = {
            # Destructive operations
            "destructive": {
                "tools": ["delete_component", "modify_component"],
                "required_annotations": ["destructiveHint"]
            },
            # Read-only operations  
            "readonly": {
                "tools": [
                    "list_models", "query_components", "get_model_elements",
                    "get_model_info", "list_operations", "list_checkpoints",
                    "list_deleted_elements", "get_reversibility_status"
                ],
                "required_annotations": ["readOnlyHint"]
            },
            # Idempotent operations
            "idempotent": {
                "tools": ["save_model", "load_model"],
                "required_annotations": ["idempotentHint"]
            }
        }
        
        tool_dict = {tool.name: tool for tool in tools}
        
        for category, config in tool_categories.items():
            for tool_name in config["tools"]:
                if tool_name in tool_dict:
                    tool = tool_dict[tool_name]
                    
                    # Tool must have annotations
                    assert hasattr(tool, 'annotations'), \
                        f"{tool_name} ({category}) missing annotations attribute"
                    
                    annotations = tool.annotations or {}
                    
                    # Check for at least one required annotation
                    has_required_annotation = any(
                        annotations.get(ann_name) is True 
                        for ann_name in config["required_annotations"]
                    )
                    
                    assert has_required_annotation, \
                        f"{tool_name} ({category}) missing required annotations: " \
                        f"{config['required_annotations']}"
    
    def test_annotation_types(self):
        """Test that annotations are properly typed boolean values."""
        
        tools = self.app.list_tools()
        
        for tool in tools:
            if hasattr(tool, 'annotations') and tool.annotations:
                annotations = tool.annotations
                
                # All annotation values should be boolean
                for key, value in annotations.items():
                    if key.endswith('Hint'):  # MCP annotation keys end with 'Hint'
                        assert isinstance(value, bool), \
                            f"Tool {tool.name} annotation {key} should be boolean, got {type(value)}"
    
    def test_fastmcp_annotation_support(self):
        """Test that FastMCP properly supports tool annotations."""
        
        # This test verifies that our MCP framework can handle annotations
        tools = self.app.list_tools()
        
        # Find a tool that should have annotations
        annotated_tools = [t for t in tools if hasattr(t, 'annotations') and t.annotations]
        
        # After implementation, we should have at least some annotated tools
        assert len(annotated_tools) > 0, \
            "No tools found with annotations - FastMCP may not support annotations"
        
        # Check that annotations are properly structured
        for tool in annotated_tools:
            annotations = tool.annotations
            assert isinstance(annotations, dict), \
                f"Tool {tool.name} annotations should be dict, got {type(annotations)}"


class TestToolSafetyIntegration:
    """Integration tests for tool safety in real usage scenarios."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio  
    async def test_delete_requires_confirmation_pattern(self):
        """Test that delete operations follow confirmation best practices."""
        
        # Mock model setup
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "test-element-456"
        mock_element.name = "TestComponent"
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            with patch.object(self.model_manager, 'models', {"test_model": mock_model}):
                
                # Get delete tool function
                from capella_mcp.server import delete_component
                
                # Test that function validates element exists before deletion
                result = await delete_component(
                    model_id="test_model",
                    element_id="test-element-456",
                    reason="Test deletion"
                )
                
                # Should succeed and include confirmation details
                assert "success" in result
                # Should include safety information in response
                assert "element_id" in result or "deleted" in str(result).lower()
    
    def test_query_tools_are_safe(self):
        """Test that query tools have no side effects."""
        
        # Get all tools marked as read-only
        tools = self.app.list_tools()
        readonly_tools = [
            t for t in tools 
            if hasattr(t, 'annotations') and 
               t.annotations and 
               t.annotations.get('readOnlyHint') is True
        ]
        
        # Should have several read-only tools
        assert len(readonly_tools) >= 3, \
            f"Expected at least 3 read-only tools, found {len(readonly_tools)}"
        
        # Read-only tools should not have destructive hints
        for tool in readonly_tools:
            annotations = tool.annotations
            assert annotations.get('destructiveHint') is not True, \
                f"Read-only tool {tool.name} should not have destructiveHint: True"


if __name__ == "__main__":
    """Run safety annotation tests."""
    pytest.main([__file__, "-v", "--tb=short"])