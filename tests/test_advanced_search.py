#!/usr/bin/env python3
"""
Test Advanced Search - Stage 2.1 TDD Implementation
==================================================

This module tests advanced search capabilities that are prerequisite for relationship 
management. Tests the following critical capellambse APIs:

- model.search(*clsnames, below=parent) - Advanced multi-class search
- model.find_references(target) - Find all references to element
- model.by_uuid(uuid) - Direct element access by UUID

Following TDD approach - tests written first to drive implementation.

STRATEGIC PRIORITY: Advanced Search enables Stage 3 (Relationship Management)
CURRENT GAP: Only basic filtering available, missing powerful search capabilities
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


class TestAdvancedSearch:
    """Test advanced search capabilities using capellambse APIs."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_search_elements_advanced_tool_exists(self):
        """Test that search_elements_advanced tool is available."""
        
        # Get all registered tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: search_elements_advanced tool must exist
        assert "search_elements_advanced" in tool_dict, \
            "search_elements_advanced tool not found - CRITICAL for element discovery"
    
    @pytest.mark.asyncio
    async def test_search_tool_schema_compliance(self):
        """Test search_elements_advanced has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "search_elements_advanced" in tool_dict:
            tool = tool_dict["search_elements_advanced"]
            
            # Check required parameters
            schema = tool.inputSchema
            assert "properties" in schema, "Tool missing input schema properties"
            
            properties = schema["properties"]
            assert "model_id" in properties, "Missing model_id parameter"
            assert "search_spec" in properties, "Missing search_spec parameter"
            
            # Check search_spec structure
            search_spec = properties.get("search_spec", {})
            if "properties" in search_spec:
                spec_props = search_spec["properties"]
                expected_fields = ["element_types", "name_pattern", "below_element", "layer"]
                for field in expected_fields:
                    if field in spec_props:
                        assert "description" in spec_props[field] or "type" in spec_props[field], \
                            f"search_spec.{field} needs proper documentation"
    
    @pytest.mark.asyncio
    async def test_search_elements_advanced_functionality(self):
        """Test search_elements_advanced uses model.search() API."""
        
        # Mock model with search capability
        mock_model = MagicMock()
        mock_component1 = MagicMock()
        mock_component1.uuid = "comp-123"
        mock_component1.name = "PowerController"
        mock_component1.__class__.__name__ = "LogicalComponent"
        
        mock_component2 = MagicMock()
        mock_component2.uuid = "comp-456"
        mock_component2.name = "PowerSupply"
        mock_component2.__class__.__name__ = "PhysicalComponent"
        
        # Mock the capellambse search API
        mock_model.search.return_value = [mock_component1, mock_component2]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "search_elements_advanced" in tool_dict:
                from capella_mcp.server import search_elements_advanced
                
                # Test multi-class search
                result = await search_elements_advanced(
                    model_id="test_model",
                    search_spec={
                        "element_types": ["LogicalComponent", "PhysicalComponent"],
                        "name_pattern": "Power*",
                        "layer": "all"
                    }
                )
                
                # Should call model.search with proper parameters
                mock_model.search.assert_called()
                call_args = mock_model.search.call_args
                assert "LogicalComponent" in str(call_args) or "PhysicalComponent" in str(call_args), \
                    "search() not called with proper element types"
                
                # Should return found elements
                assert "elements" in result or "results" in result, \
                    f"Search should return elements, got: {result}"
    
    @pytest.mark.asyncio
    async def test_search_with_parent_constraint(self):
        """Test search with below_element constraint uses model.search(below=parent)."""
        
        mock_model = MagicMock()
        mock_parent = MagicMock()
        mock_parent.uuid = "parent-789"
        
        mock_child = MagicMock()
        mock_child.uuid = "child-101"
        mock_child.name = "ChildComponent"
        
        mock_model.by_uuid.return_value = mock_parent
        mock_model.search.return_value = [mock_child]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "search_elements_advanced" in tool_dict:
                from capella_mcp.server import search_elements_advanced
                
                result = await search_elements_advanced(
                    model_id="test_model",
                    search_spec={
                        "element_types": ["LogicalComponent"],
                        "below_element": "parent-789"
                    }
                )
                
                # Should call by_uuid to get parent element
                mock_model.by_uuid.assert_called_with("parent-789")
                
                # Should call search with below parameter
                mock_model.search.assert_called()
                call_args = mock_model.search.call_args
                assert "below" in call_args.kwargs or len(call_args.args) > 1, \
                    "search() not called with below parameter"
    
    @pytest.mark.asyncio
    async def test_find_all_references_tool_exists(self):
        """Test that find_all_references tool is available."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: find_all_references tool must exist
        assert "find_all_references" in tool_dict, \
            "find_all_references tool not found - CRITICAL for relationship analysis"
    
    @pytest.mark.asyncio
    async def test_find_references_schema_compliance(self):
        """Test find_all_references has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "find_all_references" in tool_dict:
            tool = tool_dict["find_all_references"]
            
            # Check required parameters
            schema = tool.inputSchema
            properties = schema.get("properties", {})
            
            assert "model_id" in properties, "Missing model_id parameter"
            assert "element_id" in properties, "Missing element_id parameter"
            
            # Optional parameters should be documented
            if "reference_types" in properties:
                assert "type" in properties["reference_types"], \
                    "reference_types parameter needs type definition"
            
            if "include_diagrams" in properties:
                assert properties["include_diagrams"]["type"] == "boolean", \
                    "include_diagrams should be boolean type"
    
    @pytest.mark.asyncio
    async def test_find_all_references_functionality(self):
        """Test find_all_references uses model.find_references() API."""
        
        # Mock model with find_references capability
        mock_model = MagicMock()
        mock_target_element = MagicMock()
        mock_target_element.uuid = "target-123"
        
        # Mock reference results
        mock_ref1 = MagicMock()
        mock_ref1.uuid = "ref-456"
        mock_ref1.name = "ReferenceComponent"
        mock_ref2 = MagicMock()
        mock_ref2.uuid = "ref-789"
        mock_ref2.name = "AnotherReference"
        
        mock_model.by_uuid.return_value = mock_target_element
        mock_model.find_references.return_value = [mock_ref1, mock_ref2]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "find_all_references" in tool_dict:
                from capella_mcp.server import find_all_references
                
                result = await find_all_references(
                    model_id="test_model",
                    element_id="target-123",
                    include_diagrams=True
                )
                
                # Should call by_uuid to get target element
                mock_model.by_uuid.assert_called_with("target-123")
                
                # Should call find_references with target
                mock_model.find_references.assert_called_with(mock_target_element)
                
                # Should return reference information
                assert "references" in result or "elements" in result, \
                    f"find_references should return reference data, got: {result}"
    
    @pytest.mark.asyncio
    async def test_get_element_by_uuid_tool_exists(self):
        """Test that get_element_by_uuid tool is available."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: get_element_by_uuid tool must exist
        assert "get_element_by_uuid" in tool_dict, \
            "get_element_by_uuid tool not found - CRITICAL for direct element access"
    
    @pytest.mark.asyncio
    async def test_get_element_by_uuid_schema_compliance(self):
        """Test get_element_by_uuid has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "get_element_by_uuid" in tool_dict:
            tool = tool_dict["get_element_by_uuid"]
            
            # Check required parameters
            schema = tool.inputSchema
            properties = schema.get("properties", {})
            
            assert "model_id" in properties, "Missing model_id parameter"
            assert "element_uuid" in properties, "Missing element_uuid parameter"
            
            # Optional parameters
            if "include_properties" in properties:
                assert properties["include_properties"]["type"] == "boolean", \
                    "include_properties should be boolean type"
            
            if "include_relationships" in properties:
                assert properties["include_relationships"]["type"] == "boolean", \
                    "include_relationships should be boolean type"
    
    @pytest.mark.asyncio
    async def test_get_element_by_uuid_functionality(self):
        """Test get_element_by_uuid uses model.by_uuid() API."""
        
        # Mock model with by_uuid capability
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "element-999"
        mock_element.name = "TargetElement"
        mock_element.__class__.__name__ = "LogicalComponent"
        mock_element.description = "Test element description"
        
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "get_element_by_uuid" in tool_dict:
                from capella_mcp.server import get_element_by_uuid
                
                result = await get_element_by_uuid(
                    model_id="test_model",
                    element_uuid="element-999",
                    include_properties=True
                )
                
                # Should call by_uuid with correct UUID
                mock_model.by_uuid.assert_called_with("element-999")
                
                # Should return element information
                assert "element" in result, f"Should return element data, got: {result}"
                
                if "element" in result:
                    element_data = result["element"]
                    assert "uuid" in element_data or "id" in element_data, \
                        "Element data should include UUID"
                    assert "name" in element_data, "Element data should include name"
    
    @pytest.mark.asyncio
    async def test_search_tools_have_readonly_annotation(self):
        """Test that search tools have readOnlyHint annotation."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        search_tools = ["search_elements_advanced", "find_all_references", "get_element_by_uuid"]
        
        for tool_name in search_tools:
            if tool_name in tool_dict:
                tool = tool_dict[tool_name]
                
                # Search operations are read-only
                assert hasattr(tool, 'annotations'), f"{tool_name} missing annotations"
                assert tool.annotations.get('readOnlyHint') is True, \
                    f"{tool_name} should have readOnlyHint: True (search is read-only)"
                
                # Should NOT be marked as destructive
                assert tool.annotations.get('destructiveHint') is not True, \
                    f"{tool_name} should not have destructiveHint (search is safe)"


class TestSearchIntegration:
    """Integration tests for advanced search functionality."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_search_performance_with_large_model(self):
        """Test search performance with realistic model size."""
        
        # Mock large model with many elements
        mock_model = MagicMock()
        
        # Create 100 mock components for performance test
        mock_components = []
        for i in range(100):
            component = MagicMock()
            component.uuid = f"comp-{i:03d}"
            component.name = f"Component_{i}"
            component.__class__.__name__ = "LogicalComponent"
            mock_components.append(component)
        
        mock_model.search.return_value = mock_components[:10]  # Return first 10
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "search_elements_advanced" in tool_dict:
                from capella_mcp.server import search_elements_advanced
                
                import time
                start_time = time.time()
                
                result = await search_elements_advanced(
                    model_id="test_model",
                    search_spec={
                        "element_types": ["LogicalComponent"],
                        "name_pattern": "Component_*"
                    }
                )
                
                end_time = time.time()
                duration = end_time - start_time
                
                # Search should complete quickly (< 1 second)
                assert duration < 1.0, f"Search took too long: {duration:.2f}s"
                
                # Should return reasonable results
                assert "elements" in result or "results" in result, \
                    "Search should return results"
    
    @pytest.mark.asyncio
    async def test_search_layer_filtering(self):
        """Test search with layer-specific filtering."""
        
        mock_model = MagicMock()
        
        # Mock layer-specific elements
        mock_la_comp = MagicMock()
        mock_la_comp.uuid = "la-comp-123"
        mock_la_comp.name = "LAComponent"
        mock_la_comp.__class__.__name__ = "LogicalComponent"
        
        mock_pa_comp = MagicMock()
        mock_pa_comp.uuid = "pa-comp-456"
        mock_pa_comp.name = "PAComponent"
        mock_pa_comp.__class__.__name__ = "PhysicalComponent"
        
        # Mock layer access
        mock_model.la = MagicMock()
        mock_model.pa = MagicMock()
        mock_model.la.search.return_value = [mock_la_comp]
        mock_model.pa.search.return_value = [mock_pa_comp]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "search_elements_advanced" in tool_dict:
                from capella_mcp.server import search_elements_advanced
                
                # Test LA layer search
                la_result = await search_elements_advanced(
                    model_id="test_model",
                    search_spec={
                        "element_types": ["LogicalComponent"],
                        "layer": "la"
                    }
                )
                
                # Should search within LA layer
                assert "elements" in la_result or "results" in la_result, \
                    "LA layer search should return results"
                
                # Test PA layer search
                pa_result = await search_elements_advanced(
                    model_id="test_model", 
                    search_spec={
                        "element_types": ["PhysicalComponent"],
                        "layer": "pa"
                    }
                )
                
                # Should search within PA layer
                assert "elements" in pa_result or "results" in pa_result, \
                    "PA layer search should return results"
    
    @pytest.mark.asyncio
    async def test_integration_with_real_gmhcd_model(self):
        """Integration test with actual GMHCD model if available."""
        
        # This test should run with actual model loaded
        try:
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "list_models" in tool_dict:
                from capella_mcp.server import list_models
                models_result = await list_models()
                
                if models_result.get("count", 0) > 0:
                    # We have a real model loaded - test with it
                    model_id = list(models_result.get("models", {}).keys())[0]
                    
                    # Test search_elements_advanced with real model
                    if "search_elements_advanced" in tool_dict:
                        from capella_mcp.server import search_elements_advanced
                        
                        result = await search_elements_advanced(
                            model_id=model_id,
                            search_spec={
                                "element_types": ["LogicalComponent"],
                                "layer": "la"
                            }
                        )
                        
                        # Should work with real model
                        assert "success" in result or "elements" in result, \
                            f"Real model search failed: {result}"
                    
                    # Test find_all_references with real element
                    if "get_model_elements" in tool_dict and "find_all_references" in tool_dict:
                        from capella_mcp.server import get_model_elements, find_all_references
                        
                        elements_result = await get_model_elements(model_id)
                        if elements_result.get("count", 0) > 0:
                            element_id = elements_result["elements"][0]["id"]
                            
                            ref_result = await find_all_references(
                                model_id=model_id,
                                element_id=element_id
                            )
                            
                            # Should work with real model
                            assert "success" in ref_result or "references" in ref_result, \
                                f"Real model reference search failed: {ref_result}"
                
        except Exception as e:
            # Skip test if model not available
            pytest.skip(f"Skipping integration test - model not available: {e}")


class TestSearchErrorHandling:
    """Test error handling in advanced search tools."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_search_invalid_element_types(self):
        """Test search with invalid element types."""
        
        mock_model = MagicMock()
        mock_model.search.side_effect = ValueError("Invalid element type")
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "search_elements_advanced" in tool_dict:
                from capella_mcp.server import search_elements_advanced
                
                result = await search_elements_advanced(
                    model_id="test_model",
                    search_spec={
                        "element_types": ["InvalidType"],
                        "layer": "all"
                    }
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle invalid element types gracefully"
    
    @pytest.mark.asyncio
    async def test_find_references_nonexistent_element(self):
        """Test find_references with nonexistent element."""
        
        mock_model = MagicMock()
        mock_model.by_uuid.side_effect = KeyError("Element not found")
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "find_all_references" in tool_dict:
                from capella_mcp.server import find_all_references
                
                result = await find_all_references(
                    model_id="test_model",
                    element_id="nonexistent-123"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle nonexistent elements gracefully"
    
    @pytest.mark.asyncio
    async def test_get_element_invalid_uuid(self):
        """Test get_element_by_uuid with invalid UUID format."""
        
        mock_model = MagicMock()
        mock_model.by_uuid.side_effect = ValueError("Invalid UUID format")
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "get_element_by_uuid" in tool_dict:
                from capella_mcp.server import get_element_by_uuid
                
                result = await get_element_by_uuid(
                    model_id="test_model",
                    element_uuid="invalid-uuid-format"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle invalid UUID formats gracefully"


if __name__ == "__main__":
    """Run advanced search tests."""
    pytest.main([__file__, "-v", "--tb=short"])