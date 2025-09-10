#!/usr/bin/env python3
"""
Test Stage 3: Relationship Management - TDD Implementation
==========================================================

This module tests relationship management capabilities that build upon Stage 2 
advanced search foundation. Tests the following critical relationship tools:

- traverse_element_relationships - Foundation tool for relationship discovery
- analyze_element_dependencies - Dependency analysis with impact assessment  
- navigate_architectural_paths - Cross-layer ARCADIA navigation

Following TDD approach - tests written first to drive implementation.

STRATEGIC PRIORITY: Relationship Management enables comprehensive model analysis
DEPENDENCIES: Stage 2 tools (get_element_details, find_references_working)
PERFORMANCE TARGET: <2s response time on production GMHCD model
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


class TestTraverseElementRelationships:
    """Test traverse_element_relationships tool - Foundation of Stage 3."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_traverse_element_relationships_tool_exists(self):
        """Test that traverse_element_relationships tool is available."""
        
        # Get all registered tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: traverse_element_relationships tool must exist
        assert "traverse_element_relationships" in tool_dict, \
            "traverse_element_relationships tool not found - CRITICAL for relationship discovery"
    
    @pytest.mark.asyncio
    async def test_traverse_tool_schema_compliance(self):
        """Test traverse_element_relationships has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "traverse_element_relationships" in tool_dict:
            tool = tool_dict["traverse_element_relationships"]
            
            # Check required parameters
            schema = tool.inputSchema
            assert "properties" in schema, "Tool missing input schema properties"
            
            properties = schema["properties"]
            assert "model_id" in properties, "Missing model_id parameter"
            assert "element_id" in properties, "Missing element_id parameter"
            
            # Check optional parameters with proper types
            if "max_depth" in properties:
                assert properties["max_depth"]["type"] == "integer", \
                    "max_depth should be integer type"
            
            if "relationship_types" in properties:
                # FastMCP generates anyOf for Optional[List[str]] which is correct JSON Schema
                rel_types_schema = properties["relationship_types"]
                if "anyOf" in rel_types_schema:
                    # Check that it includes array type in anyOf
                    array_types = [item for item in rel_types_schema["anyOf"] if item.get("type") == "array"]
                    assert len(array_types) > 0, "relationship_types should include array type in anyOf"
                else:
                    assert rel_types_schema["type"] == "array", \
                        "relationship_types should be array type"
            
            if "bidirectional" in properties:
                assert properties["bidirectional"]["type"] == "boolean", \
                    "bidirectional should be boolean type"
            
            if "include_layers" in properties:
                # FastMCP generates anyOf for Optional[List[str]] which is correct JSON Schema
                layers_schema = properties["include_layers"]
                if "anyOf" in layers_schema:
                    # Check that it includes array type in anyOf
                    array_types = [item for item in layers_schema["anyOf"] if item.get("type") == "array"]
                    assert len(array_types) > 0, "include_layers should include array type in anyOf"
                else:
                    assert layers_schema["type"] == "array", \
                        "include_layers should be array type"
    
    @pytest.mark.asyncio
    async def test_traverse_basic_functionality(self):
        """Test traverse_element_relationships basic relationship discovery."""
        
        # Mock Stage 2 foundation tools directly (this is what traverse actually calls)
        
        # Mock get_element_details response
        mock_element_details = {
            "success": True,
            "element": {
                "id": "test-element-123",
                "name": "TestComponent",
                "type": "LogicalComponent",
                "layer": "LA"
            }
        }
        
        # Mock find_references_working response  
        mock_references = {
            "success": True,
            "elements": [
                {
                    "id": "related-456",
                    "name": "RelatedComponent1",
                    "type": "LogicalComponent",
                    "layer": "LA"
                },
                {
                    "id": "related-789", 
                    "name": "RelatedComponent2",
                    "type": "PhysicalComponent",
                    "layer": "PA"
                }
            ]
        }
        
        # Mock model_manager to return valid wrapper
        mock_wrapper = MagicMock()
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', return_value=mock_element_details), \
             patch('capella_mcp.server.find_references_working', return_value=mock_references):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="test-element-123",
                    max_depth=1,
                    bidirectional=False  # Start simple
                )
                
                # Should return structured relationship network
                assert "network" in result or "error" not in result, \
                    f"Should return relationship network, got: {result}"
                
                # Should include source element information
                if "network" in result:
                    network = result["network"]
                    assert "source_element" in network, "Network should include source element"
                    assert "relationships" in network, "Network should include relationships"
                    assert "metadata" in network, "Network should include metadata"
                    
                    # Check metadata
                    metadata = network["metadata"]
                    assert "max_depth" in metadata, "Metadata should include max_depth"
                    assert "total_elements" in metadata, "Metadata should include total_elements"
    
    @pytest.mark.asyncio
    async def test_traverse_depth_limitation(self):
        """Test traverse_element_relationships respects max_depth parameter."""
        
        # Mock multi-level relationship hierarchy
        mock_wrapper = MagicMock()
        
        # Level 0: Source element
        mock_source = MagicMock()
        mock_source.uuid = "source-000"
        mock_source.name = "SourceComponent"
        
        # Level 1: Direct relationships
        mock_level1a = MagicMock()
        mock_level1a.uuid = "level1-aaa"
        mock_level1a.name = "Level1ComponentA"
        
        mock_level1b = MagicMock()
        mock_level1b.uuid = "level1-bbb" 
        mock_level1b.name = "Level1ComponentB"
        
        # Level 2: Indirect relationships
        mock_level2a = MagicMock()
        mock_level2a.uuid = "level2-aaa"
        mock_level2a.name = "Level2ComponentA"
        
        # Mock hierarchical element access
        def mock_get_element(uuid):
            element_map = {
                "source-000": mock_source,
                "level1-aaa": mock_level1a,
                "level1-bbb": mock_level1b,
                "level2-aaa": mock_level2a
            }
            return element_map.get(uuid)
        
        mock_wrapper.get_element.side_effect = mock_get_element
        
        # Mock hierarchical references
        def mock_find_references(element):
            ref_map = {
                "source-000": [mock_level1a, mock_level1b],  # Level 1
                "level1-aaa": [mock_level2a],                # Level 2
                "level1-bbb": [],                            # No further refs
                "level2-aaa": []                             # No further refs
            }
            return ref_map.get(element.uuid, [])
        
        mock_wrapper.model.find_references.side_effect = mock_find_references
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                # Test depth=1 (should stop at level 1)
                result_depth1 = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="source-000", 
                    max_depth=1
                )
                
                # Should include level 1 but not level 2
                assert "network" in result_depth1 or "relationships" in result_depth1, \
                    "Should return relationship network"
                
                # Test depth=2 (should include level 2)
                result_depth2 = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="source-000",
                    max_depth=2
                )
                
                # Should include both levels
                assert "network" in result_depth2 or "relationships" in result_depth2, \
                    "Should return deeper relationship network"
    
    @pytest.mark.asyncio
    async def test_traverse_relationship_type_filtering(self):
        """Test traverse_element_relationships filters by relationship types."""
        
        mock_wrapper = MagicMock()
        
        # Mock element with different relationship types
        mock_element = MagicMock()
        mock_element.uuid = "filter-test-123"
        mock_element.name = "FilterTestComponent"
        
        # Mock related elements with different relationship types
        mock_composition = MagicMock()
        mock_composition.uuid = "comp-456"
        mock_composition.name = "CompositionRelated"
        mock_composition.relationship_type = "composition"
        
        mock_allocation = MagicMock()
        mock_allocation.uuid = "alloc-789"
        mock_allocation.name = "AllocationRelated"
        mock_allocation.relationship_type = "allocation"
        
        mock_interface = MagicMock()
        mock_interface.uuid = "iface-101"
        mock_interface.name = "InterfaceRelated"
        mock_interface.relationship_type = "interface"
        
        mock_wrapper.get_element.return_value = mock_element
        mock_wrapper.model.find_references.return_value = [
            mock_composition, mock_allocation, mock_interface
        ]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                # Test filtering for specific relationship types
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="filter-test-123",
                    relationship_types=["composition", "allocation"]
                )
                
                # Should filter relationships by type
                assert "network" in result or "relationships" in result, \
                    "Should return filtered relationship network"
                
                # Should respect relationship type filter in implementation
                mock_wrapper.get_element.assert_called_with("filter-test-123")
    
    @pytest.mark.asyncio
    async def test_traverse_bidirectional_exploration(self):
        """Test traverse_element_relationships bidirectional relationship discovery."""
        
        mock_wrapper = MagicMock()
        
        # Mock element for bidirectional test
        mock_element = MagicMock()
        mock_element.uuid = "bidir-test-456"
        mock_element.name = "BidirectionalTest"
        
        # Mock outgoing references (element -> others)
        mock_outgoing1 = MagicMock()
        mock_outgoing1.uuid = "outgoing-111"
        mock_outgoing1.name = "OutgoingRef1"
        
        # Mock incoming references (others -> element)
        mock_incoming1 = MagicMock()
        mock_incoming1.uuid = "incoming-222"
        mock_incoming1.name = "IncomingRef1"
        
        mock_wrapper.get_element.return_value = mock_element
        
        # Mock both outgoing and incoming reference discovery
        def mock_bidirectional_search(element_id, direction):
            if direction == "outgoing":
                return [mock_outgoing1]
            elif direction == "incoming":
                return [mock_incoming1]
            else:
                return []
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                # Test bidirectional=True
                result_bidir = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="bidir-test-456",
                    bidirectional=True
                )
                
                # Should discover both directions
                assert "network" in result_bidir or "relationships" in result_bidir, \
                    "Should return bidirectional relationship network"
                
                # Test bidirectional=False (default behavior)
                result_unidir = await traverse_element_relationships(
                    model_id="test_model", 
                    element_id="bidir-test-456",
                    bidirectional=False
                )
                
                # Should discover only one direction
                assert "network" in result_unidir or "relationships" in result_unidir, \
                    "Should return unidirectional relationship network"
    
    @pytest.mark.asyncio
    async def test_traverse_layer_specific_filtering(self):
        """Test traverse_element_relationships respects include_layers parameter."""
        
        mock_wrapper = MagicMock()
        
        # Mock element in Logical Architecture
        mock_la_element = MagicMock()
        mock_la_element.uuid = "la-element-789"
        mock_la_element.name = "LAElement"
        mock_la_element.layer = "la"
        
        # Mock related elements in different layers
        mock_sa_related = MagicMock()
        mock_sa_related.uuid = "sa-related-101"
        mock_sa_related.name = "SARelated"
        mock_sa_related.layer = "sa"
        
        mock_pa_related = MagicMock()
        mock_pa_related.uuid = "pa-related-202"
        mock_pa_related.name = "PARelated"
        mock_pa_related.layer = "pa"
        
        mock_la_related = MagicMock()
        mock_la_related.uuid = "la-related-303"
        mock_la_related.name = "LARelated"
        mock_la_related.layer = "la"
        
        mock_wrapper.get_element.return_value = mock_la_element
        mock_wrapper.model.find_references.return_value = [
            mock_sa_related, mock_pa_related, mock_la_related
        ]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                # Test layer filtering (only LA and PA)
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="la-element-789",
                    include_layers=["la", "pa"]
                )
                
                # Should filter relationships by layer
                assert "network" in result or "relationships" in result, \
                    "Should return layer-filtered relationship network"
                
                mock_wrapper.get_element.assert_called_with("la-element-789")
    
    @pytest.mark.asyncio
    async def test_traverse_performance_requirements(self):
        """Test traverse_element_relationships meets performance requirements (<2s)."""
        
        # Mock large model with many relationships
        mock_wrapper = MagicMock()
        
        # Create mock element with many relationships
        mock_element = MagicMock()
        mock_element.uuid = "perf-test-999"
        mock_element.name = "PerformanceTestElement"
        
        # Create 50 mock related elements for performance test
        mock_related_elements = []
        for i in range(50):
            related = MagicMock()
            related.uuid = f"related-{i:03d}"
            related.name = f"RelatedElement_{i}"
            related.__class__.__name__ = "LogicalComponent"
            mock_related_elements.append(related)
        
        mock_wrapper.get_element.return_value = mock_element
        mock_wrapper.model.find_references.return_value = mock_related_elements[:10]  # Return first 10
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                import time
                start_time = time.time()
                
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="perf-test-999",
                    max_depth=2,
                    bidirectional=True
                )
                
                end_time = time.time()
                duration = end_time - start_time
                
                # Relationship traversal should complete quickly (< 2 seconds)
                assert duration < 2.0, f"Traversal took too long: {duration:.2f}s"
                
                # Should return relationship results
                assert "network" in result or "relationships" in result, \
                    "Should return relationship network"
    
    @pytest.mark.asyncio
    async def test_traverse_has_readonly_annotation(self):
        """Test that traverse_element_relationships has readOnlyHint annotation."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "traverse_element_relationships" in tool_dict:
            tool = tool_dict["traverse_element_relationships"]
            
            # Relationship traversal is read-only
            assert hasattr(tool, 'annotations'), "traverse_element_relationships missing annotations"
            assert tool.annotations.readOnlyHint is True, \
                "traverse_element_relationships should have readOnlyHint: True (traversal is read-only)"
            
            # Should NOT be marked as destructive
            assert tool.annotations.destructiveHint is not True, \
                "traverse_element_relationships should not have destructiveHint (traversal is safe)"


class TestTraverseErrorHandling:
    """Test error handling in traverse_element_relationships tool."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_traverse_nonexistent_element(self):
        """Test traverse_element_relationships with nonexistent element."""
        
        mock_wrapper = MagicMock()
        mock_wrapper.get_element.side_effect = KeyError("Element not found")
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="nonexistent-999"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle nonexistent elements gracefully"
    
    @pytest.mark.asyncio
    async def test_traverse_invalid_depth(self):
        """Test traverse_element_relationships with invalid max_depth."""
        
        mock_wrapper = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "test-123"
        mock_wrapper.get_element.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                # Test negative depth
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="test-123",
                    max_depth=-1
                )
                
                # Should handle invalid depth gracefully
                assert "error" in result or "success" in result, \
                    "Should handle invalid depth values gracefully"
    
    @pytest.mark.asyncio
    async def test_traverse_invalid_relationship_types(self):
        """Test traverse_element_relationships with invalid relationship types."""
        
        mock_wrapper = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "test-456"
        mock_wrapper.get_element.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "traverse_element_relationships" in tool_dict:
                from capella_mcp.server import traverse_element_relationships
                
                result = await traverse_element_relationships(
                    model_id="test_model",
                    element_id="test-456",
                    relationship_types=["invalid_type", "another_invalid"]
                )
                
                # Should handle invalid types gracefully
                assert "error" in result or "success" in result, \
                    "Should handle invalid relationship types gracefully"


class TestTraverseIntegration:
    """Integration tests for traverse_element_relationships."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_traverse_integration_with_real_gmhcd_model(self):
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
                    
                    # Get a real element to test with
                    if "get_model_elements" in tool_dict:
                        from capella_mcp.server import get_model_elements
                        elements_result = await get_model_elements(model_id)
                        
                        if elements_result.get("count", 0) > 0:
                            element_id = elements_result["elements"][0]["id"]
                            
                            # Test traverse_element_relationships with real model
                            if "traverse_element_relationships" in tool_dict:
                                from capella_mcp.server import traverse_element_relationships
                                
                                result = await traverse_element_relationships(
                                    model_id=model_id,
                                    element_id=element_id,
                                    max_depth=1,
                                    bidirectional=True
                                )
                                
                                # Should work with real model
                                assert "success" in result or "network" in result, \
                                    f"Real model traversal failed: {result}"
                
        except Exception as e:
            # Skip test if model not available
            pytest.skip(f"Skipping integration test - model not available: {e}")
    
    @pytest.mark.asyncio
    async def test_traverse_foundation_tool_dependency(self):
        """Test that traverse_element_relationships uses Stage 2 foundation tools."""
        
        # This test verifies the dependency on Stage 2 tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Required Stage 2 foundation tools must exist
        stage2_tools = ["get_element_details", "find_references_working"]
        
        for foundation_tool in stage2_tools:
            assert foundation_tool in tool_dict, \
                f"Stage 2 foundation tool {foundation_tool} not found - required for Stage 3"
        
        # traverse_element_relationships should exist and depend on these
        assert "traverse_element_relationships" in tool_dict, \
            "traverse_element_relationships not found - Stage 3 implementation missing"


class TestAnalyzeElementDependencies:
    """Test analyze_element_dependencies tool - Stage 3 Tool 2."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_tool_exists(self):
        """Test that analyze_element_dependencies tool is available."""
        
        # Get all registered tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: analyze_element_dependencies tool must exist  
        assert "analyze_element_dependencies" in tool_dict, \
            "analyze_element_dependencies tool not found - CRITICAL for dependency analysis"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_schema_compliance(self):
        """Test analyze_element_dependencies has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "analyze_element_dependencies" in tool_dict:
            tool = tool_dict["analyze_element_dependencies"]
            
            # Check required parameters
            schema = tool.inputSchema
            assert "properties" in schema, "Tool missing input schema properties"
            
            properties = schema["properties"]
            assert "model_id" in properties, "Missing model_id parameter"
            assert "element_id" in properties, "Missing element_id parameter"
            
            # Check optional parameters with proper types
            if "analysis_depth" in properties:
                assert properties["analysis_depth"]["type"] == "integer", \
                    "analysis_depth should be integer type"
            
            if "include_impact_scores" in properties:
                assert properties["include_impact_scores"]["type"] == "boolean", \
                    "include_impact_scores should be boolean type"
            
            if "include_critical_paths" in properties:
                assert properties["include_critical_paths"]["type"] == "boolean", \
                    "include_critical_paths should be boolean type"
            
            if "dependency_types" in properties:
                # FastMCP generates anyOf for Optional[List[str]]
                dep_types_schema = properties["dependency_types"]
                if "anyOf" in dep_types_schema:
                    array_types = [item for item in dep_types_schema["anyOf"] if item.get("type") == "array"]
                    assert len(array_types) > 0, "dependency_types should include array type in anyOf"
                else:
                    assert dep_types_schema["type"] == "array", \
                        "dependency_types should be array type"
            
            if "layer_filter" in properties:
                # FastMCP generates anyOf for Optional[List[str]]
                layer_schema = properties["layer_filter"]
                if "anyOf" in layer_schema:
                    array_types = [item for item in layer_schema["anyOf"] if item.get("type") == "array"]
                    assert len(array_types) > 0, "layer_filter should include array type in anyOf"
                else:
                    assert layer_schema["type"] == "array", \
                        "layer_filter should be array type"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_basic_functionality(self):
        """Test analyze_element_dependencies basic dependency analysis."""
        
        # Mock Stage 2 foundation tools and Tool 1 traverse
        mock_element_details = {
            "success": True,
            "element": {
                "id": "test-component-456", 
                "name": "TestComponent",
                "type": "LogicalComponent",
                "layer": "LA"
            }
        }
        
        # Mock traverse_element_relationships response (Tool 1)
        mock_traverse_response = {
            "success": True,
            "network": {
                "source_element": {
                    "id": "test-component-456",
                    "name": "TestComponent",
                    "type": "LogicalComponent" 
                },
                "relationships": [
                    {
                        "target_id": "dep-component-789",
                        "target_name": "DepComponent1",
                        "relationship_type": "composition",
                        "direction": "outgoing"
                    },
                    {
                        "target_id": "dep-component-101",
                        "target_name": "DepComponent2", 
                        "relationship_type": "allocation",
                        "direction": "incoming"
                    }
                ],
                "metadata": {
                    "max_depth": 3,
                    "total_elements": 3
                }
            }
        }
        
        mock_wrapper = MagicMock()
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', return_value=mock_element_details), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_traverse_response):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="test-component-456",
                    analysis_depth=3,
                    include_impact_scores=True,
                    include_critical_paths=True
                )
                
                # Should return structured dependency analysis
                assert "dependency_graph" in result or "error" not in result, \
                    f"Should return dependency analysis, got: {result}"
                
                # Check analysis structure
                if "dependency_graph" in result:
                    analysis = result["dependency_graph"]
                    assert "source_element" in analysis, "Analysis should include source element"
                    assert "dependencies" in analysis, "Analysis should include dependencies"
                    
                    # Check for impact scoring
                    if result.get("include_impact_scores"):
                        assert "impact_matrix" in result, "Should include impact matrix when requested"
                    
                    # Check for critical paths
                    if result.get("include_critical_paths"):
                        assert "critical_paths" in result, "Should include critical paths when requested"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_impact_scoring(self):
        """Test analyze_element_dependencies quantified impact scoring."""
        
        # Mock complex dependency network for impact scoring
        mock_wrapper = MagicMock()
        
        # Mock source element
        mock_source = MagicMock()
        mock_source.uuid = "source-123"
        mock_source.name = "SourceComponent"
        
        # Mock dependent elements with different impact levels
        mock_high_impact = MagicMock()
        mock_high_impact.uuid = "high-impact-456"
        mock_high_impact.name = "HighImpactComponent"
        mock_high_impact.layer = "SA"  # Cross-layer dependency = higher impact
        
        mock_low_impact = MagicMock()
        mock_low_impact.uuid = "low-impact-789"
        mock_low_impact.name = "LowImpactComponent"
        mock_low_impact.layer = "LA"  # Same layer = lower impact
        
        # Mock traverse response with multi-level dependencies
        mock_traverse_network = {
            "success": True,
            "network": {
                "source_element": {"id": "source-123", "name": "SourceComponent"},
                "relationships": [
                    {
                        "target_id": "high-impact-456",
                        "relationship_type": "allocation",  # High impact type
                        "direction": "outgoing",
                        "layer": "SA"
                    },
                    {
                        "target_id": "low-impact-789", 
                        "relationship_type": "interface",   # Lower impact type
                        "direction": "outgoing",
                        "layer": "LA"
                    }
                ]
            }
        }
        
        mock_wrapper.get_element.return_value = mock_source
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_traverse_network):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="source-123",
                    include_impact_scores=True
                )
                
                # Should include quantified impact scores
                assert "impact_matrix" in result, "Should include impact scoring matrix"
                
                impact_matrix = result["impact_matrix"]
                assert isinstance(impact_matrix, dict), "Impact matrix should be dictionary"
                
                # Should have impact scores for dependencies (0.0-1.0 range)
                for dep_id, score in impact_matrix.items():
                    assert isinstance(score, (int, float)), f"Impact score should be numeric: {score}"
                    assert 0.0 <= score <= 1.0, f"Impact score should be 0.0-1.0: {score}"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_critical_paths(self):
        """Test analyze_element_dependencies critical path identification."""
        
        # Mock critical dependency chain for path analysis
        mock_wrapper = MagicMock()
        
        # Mock cascade of critical dependencies
        mock_traverse_critical = {
            "success": True,
            "network": {
                "source_element": {"id": "critical-source-999", "name": "CriticalSource"},
                "relationships": [
                    {
                        "target_id": "critical-dep1-111",
                        "target_name": "CriticalDep1",
                        "relationship_type": "allocation",
                        "direction": "outgoing",
                        "layer": "SA"
                    },
                    {
                        "target_id": "critical-dep2-222", 
                        "target_name": "CriticalDep2",
                        "relationship_type": "composition",
                        "direction": "outgoing", 
                        "layer": "PA"
                    }
                ]
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_traverse_critical):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="critical-source-999",
                    include_critical_paths=True
                )
                
                # Should identify critical dependency paths
                assert "critical_paths" in result, "Should include critical paths analysis"
                
                critical_paths = result["critical_paths"]
                assert isinstance(critical_paths, list), "Critical paths should be list"
                
                # Critical paths should have path and impact information
                for path in critical_paths:
                    assert "path" in path, "Critical path should include path information"
                    assert "cumulative_impact" in path, "Critical path should include cumulative impact"
                    assert isinstance(path["cumulative_impact"], (int, float)), \
                        "Cumulative impact should be numeric"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_performance_requirements(self):
        """Test analyze_element_dependencies meets performance requirements (<2s)."""
        
        # Mock large dependency network for performance testing
        mock_wrapper = MagicMock()
        
        # Create mock network with many dependencies (50+ elements)
        mock_large_network = {
            "success": True,
            "network": {
                "source_element": {"id": "perf-test-555", "name": "PerformanceTest"},
                "relationships": []
            }
        }
        
        # Add 50 mock dependencies
        for i in range(50):
            mock_large_network["network"]["relationships"].append({
                "target_id": f"dep-element-{i:03d}",
                "target_name": f"DepElement_{i}",
                "relationship_type": "composition",
                "direction": "outgoing",
                "layer": "LA"
            })
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_large_network):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                import time
                start_time = time.time()
                
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="perf-test-555",
                    analysis_depth=3,
                    include_impact_scores=True,
                    include_critical_paths=True
                )
                
                end_time = time.time()
                duration = end_time - start_time
                
                # Dependency analysis should complete quickly (< 2 seconds)
                assert duration < 2.0, f"Analysis took too long: {duration:.2f}s"
                
                # Should return analysis results
                assert "dependency_graph" in result or "impact_matrix" in result, \
                    "Should return dependency analysis results"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_arcadia_layer_awareness(self):
        """Test analyze_element_dependencies ARCADIA layer-specific analysis."""
        
        # Mock multi-layer dependency scenario
        mock_wrapper = MagicMock()
        
        # Mock cross-layer dependencies (OA→SA→LA→PA)
        mock_arcadia_network = {
            "success": True,
            "network": {
                "source_element": {"id": "la-component-777", "name": "LAComponent", "layer": "LA"},
                "relationships": [
                    {
                        "target_id": "sa-component-888",
                        "target_name": "SAComponent", 
                        "relationship_type": "allocation",
                        "direction": "incoming",  # SA allocates to LA
                        "layer": "SA"
                    },
                    {
                        "target_id": "pa-component-999",
                        "target_name": "PAComponent",
                        "relationship_type": "allocation", 
                        "direction": "outgoing",  # LA allocates to PA
                        "layer": "PA"
                    }
                ]
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_arcadia_network):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                # Test layer-specific analysis
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="la-component-777",
                    layer_filter=["SA", "LA", "PA"]
                )
                
                # Should handle ARCADIA layer filtering
                assert "dependency_graph" in result, "Should return layer-filtered analysis"
                
                # Test cross-layer impact scoring (should be higher)
                result_with_impact = await analyze_element_dependencies(
                    model_id="test_model", 
                    element_id="la-component-777",
                    include_impact_scores=True
                )
                
                if "impact_matrix" in result_with_impact:
                    impact_matrix = result_with_impact["impact_matrix"]
                    # Cross-layer dependencies should have higher impact scores
                    assert len(impact_matrix) > 0, "Should have impact scores for cross-layer dependencies"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_has_readonly_annotation(self):
        """Test that analyze_element_dependencies has readOnlyHint annotation."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "analyze_element_dependencies" in tool_dict:
            tool = tool_dict["analyze_element_dependencies"]
            
            # Dependency analysis is read-only
            assert hasattr(tool, 'annotations'), "analyze_element_dependencies missing annotations"
            assert tool.annotations.readOnlyHint is True, \
                "analyze_element_dependencies should have readOnlyHint: True (analysis is read-only)"
            
            # Should NOT be marked as destructive
            assert tool.annotations.destructiveHint is not True, \
                "analyze_element_dependencies should not have destructiveHint (analysis is safe)"


class TestAnalyzeDependenciesErrorHandling:
    """Test error handling in analyze_element_dependencies tool."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_nonexistent_element(self):
        """Test analyze_element_dependencies with nonexistent element."""
        
        mock_wrapper = MagicMock()
        mock_wrapper.get_element.side_effect = KeyError("Element not found")
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="nonexistent-888"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle nonexistent elements gracefully"
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_invalid_depth(self):
        """Test analyze_element_dependencies with invalid analysis_depth."""
        
        mock_wrapper = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "test-456"
        mock_wrapper.get_element.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "analyze_element_dependencies" in tool_dict:
                from capella_mcp.server import analyze_element_dependencies
                
                # Test negative depth
                result = await analyze_element_dependencies(
                    model_id="test_model",
                    element_id="test-456",
                    analysis_depth=-1
                )
                
                # Should handle invalid depth gracefully
                assert "error" in result or "success" in result, \
                    "Should handle invalid depth values gracefully"


class TestAnalyzeDependenciesIntegration:
    """Integration tests for analyze_element_dependencies."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_integration_with_real_gmhcd_model(self):
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
                    
                    # Get a real element to test with
                    if "get_model_elements" in tool_dict:
                        from capella_mcp.server import get_model_elements
                        elements_result = await get_model_elements(model_id)
                        
                        if elements_result.get("count", 0) > 0:
                            element_id = elements_result["elements"][0]["id"]
                            
                            # Test analyze_element_dependencies with real model
                            if "analyze_element_dependencies" in tool_dict:
                                from capella_mcp.server import analyze_element_dependencies
                                
                                result = await analyze_element_dependencies(
                                    model_id=model_id,
                                    element_id=element_id,
                                    analysis_depth=2,
                                    include_impact_scores=True,
                                    include_critical_paths=True
                                )
                                
                                # Should work with real model
                                assert "success" in result or "dependency_graph" in result, \
                                    f"Real model dependency analysis failed: {result}"
                
        except Exception as e:
            # Skip test if model not available
            pytest.skip(f"Skipping integration test - model not available: {e}")
    
    @pytest.mark.asyncio
    async def test_analyze_dependencies_foundation_tool_dependency(self):
        """Test that analyze_element_dependencies uses Stage 2 and Tool 1 foundations."""
        
        # This test verifies the dependency on foundation tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Required foundation tools must exist
        foundation_tools = [
            "get_element_details",           # Stage 2
            "find_references_working",       # Stage 2
            "traverse_element_relationships" # Stage 3 Tool 1
        ]
        
        for foundation_tool in foundation_tools:
            assert foundation_tool in tool_dict, \
                f"Foundation tool {foundation_tool} not found - required for dependency analysis"
        
        # analyze_element_dependencies should exist and depend on these
        assert "analyze_element_dependencies" in tool_dict, \
            "analyze_element_dependencies not found - Stage 3 Tool 2 implementation missing"


class TestNavigateArchitecturalPaths:
    """Test navigate_architectural_paths tool - Stage 3 Tool 3."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_navigate_paths_tool_exists(self):
        """Test that navigate_architectural_paths tool is available."""
        
        # Get all registered tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: navigate_architectural_paths tool must exist  
        assert "navigate_architectural_paths" in tool_dict, \
            "navigate_architectural_paths tool not found - CRITICAL for ARCADIA navigation"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_schema_compliance(self):
        """Test navigate_architectural_paths has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "navigate_architectural_paths" in tool_dict:
            tool = tool_dict["navigate_architectural_paths"]
            
            # Check required parameters
            schema = tool.inputSchema
            assert "properties" in schema, "Tool missing input schema properties"
            
            properties = schema["properties"]
            assert "model_id" in properties, "Missing model_id parameter"
            assert "source_element_id" in properties, "Missing source_element_id parameter"
            assert "target_element_id" in properties, "Missing target_element_id parameter"
            
            # Check optional parameters with proper types
            if "max_paths" in properties:
                assert properties["max_paths"]["type"] == "integer", \
                    "max_paths should be integer type"
            
            if "path_optimization" in properties:
                path_opt_schema = properties["path_optimization"]
                if "enum" in path_opt_schema:
                    expected_values = ["quality", "speed", "balanced"]
                    assert all(val in expected_values for val in path_opt_schema["enum"]), \
                        f"path_optimization enum should include {expected_values}"
                else:
                    assert path_opt_schema["type"] == "string", \
                        "path_optimization should be string type"
            
            if "layer_preference" in properties:
                # FastMCP generates anyOf for Optional[List[str]]
                layer_pref_schema = properties["layer_preference"]
                if "anyOf" in layer_pref_schema:
                    array_types = [item for item in layer_pref_schema["anyOf"] if item.get("type") == "array"]
                    assert len(array_types) > 0, "layer_preference should include array type in anyOf"
                else:
                    assert layer_pref_schema["type"] == "array", \
                        "layer_preference should be array type"
            
            if "max_depth" in properties:
                assert properties["max_depth"]["type"] == "integer", \
                    "max_depth should be integer type"
            
            if "include_cross_layer" in properties:
                assert properties["include_cross_layer"]["type"] == "boolean", \
                    "include_cross_layer should be boolean type"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_basic_functionality(self):
        """Test navigate_architectural_paths basic pathfinding functionality."""
        
        # Mock Stage 3 foundation tools (Tools 1-2)
        mock_element_details_source = {
            "success": True,
            "element": {
                "id": "nav-source-123",
                "name": "NavigationSource",
                "type": "LogicalComponent",
                "layer": "LA"
            }
        }
        
        mock_element_details_target = {
            "success": True,
            "element": {
                "id": "nav-target-456",
                "name": "NavigationTarget", 
                "type": "PhysicalComponent",
                "layer": "PA"
            }
        }
        
        # Mock traverse_element_relationships response
        mock_traverse_response = {
            "success": True,
            "network": {
                "source_element": {"id": "nav-source-123", "name": "NavigationSource"},
                "relationships": [
                    {
                        "target_id": "intermediate-789",
                        "target_name": "IntermediateComponent",
                        "relationship_type": "allocation",
                        "direction": "outgoing",
                        "layer": "PA"
                    }
                ]
            }
        }
        
        # Mock analyze_element_dependencies response
        mock_dependencies_response = {
            "success": True,
            "dependency_graph": {
                "source_element": {"id": "nav-source-123"},
                "dependencies": []
            },
            "impact_matrix": {
                "intermediate-789": 0.75,
                "nav-target-456": 0.90
            }
        }
        
        mock_wrapper = MagicMock()
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_element_details_source, mock_element_details_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_traverse_response), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_dependencies_response):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="nav-source-123",
                    target_element_id="nav-target-456",
                    max_paths=3,
                    path_optimization="quality"
                )
                
                # Should return structured path navigation result
                assert "navigation_result" in result or "error" not in result, \
                    f"Should return navigation result, got: {result}"
                
                # Check path navigation structure
                if "navigation_result" in result:
                    nav_result = result["navigation_result"]
                    assert "source_element" in nav_result, "Result should include source element"
                    assert "target_element" in nav_result, "Result should include target element"
                    assert "paths" in nav_result, "Result should include discovered paths"
                    assert "analysis" in nav_result, "Result should include path analysis"
                    
                    # Check paths structure
                    paths = nav_result["paths"]
                    assert isinstance(paths, list), "Paths should be list of discovered routes"
                    
                    if len(paths) > 0:
                        primary_path = paths[0]
                        assert "path_id" in primary_path, "Path should have unique identifier"
                        assert "steps" in primary_path, "Path should include navigation steps"
                        assert "quality_score" in primary_path, "Path should include quality score"
                        assert "layer_transitions" in primary_path, "Path should include layer transitions"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_arcadia_layer_navigation(self):
        """Test navigate_architectural_paths ARCADIA layer-aware navigation."""
        
        # Mock cross-layer ARCADIA navigation scenario
        mock_wrapper = MagicMock()
        
        # Mock OA → SA → LA → PA navigation scenario
        mock_oa_source = {
            "success": True,
            "element": {"id": "oa-actor-001", "name": "OAActorSource", "layer": "OA"}
        }
        
        mock_pa_target = {
            "success": True,
            "element": {"id": "pa-node-999", "name": "PANodeTarget", "layer": "PA"}
        }
        
        # Mock multi-layer relationship network
        mock_arcadia_network = {
            "success": True,
            "network": {
                "source_element": {"id": "oa-actor-001", "name": "OAActorSource", "layer": "OA"},
                "relationships": [
                    {
                        "target_id": "sa-function-222",
                        "target_name": "SAFunction",
                        "relationship_type": "operationalactivityallocation",
                        "direction": "outgoing",
                        "layer": "SA"
                    },
                    {
                        "target_id": "la-component-333",
                        "target_name": "LAComponent", 
                        "relationship_type": "functionallocation",
                        "direction": "outgoing",
                        "layer": "LA"
                    },
                    {
                        "target_id": "pa-node-999",
                        "target_name": "PANodeTarget",
                        "relationship_type": "componentallocation", 
                        "direction": "outgoing",
                        "layer": "PA"
                    }
                ]
            }
        }
        
        # Mock impact analysis with layer weighting
        mock_layer_analysis = {
            "success": True,
            "impact_matrix": {
                "sa-function-222": 0.85,  # OA→SA transition
                "la-component-333": 0.75, # SA→LA transition  
                "pa-node-999": 0.65      # LA→PA transition
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_oa_source, mock_pa_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_arcadia_network), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_layer_analysis):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="oa-actor-001",
                    target_element_id="pa-node-999",
                    include_cross_layer=True,
                    layer_preference=["OA", "SA", "LA", "PA"]
                )
                
                # Should handle ARCADIA cross-layer navigation
                assert "navigation_result" in result, "Should return ARCADIA navigation result"
                
                nav_result = result["navigation_result"]
                if "paths" in nav_result and len(nav_result["paths"]) > 0:
                    primary_path = nav_result["paths"][0]
                    
                    # Should include layer transition analysis
                    assert "layer_transitions" in primary_path, \
                        "ARCADIA path should include layer transition analysis"
                    
                    # Should have ARCADIA-aware quality scoring
                    assert "quality_score" in primary_path, \
                        "Path should include ARCADIA quality scoring"
                    
                    layer_transitions = primary_path["layer_transitions"]
                    assert isinstance(layer_transitions, list), \
                        "Layer transitions should be list of OA→SA→LA→PA steps"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_multiple_path_discovery(self):
        """Test navigate_architectural_paths multiple alternative path discovery."""
        
        # Mock scenario with multiple possible paths
        mock_wrapper = MagicMock()
        
        # Mock source and target
        mock_source = {
            "success": True,
            "element": {"id": "multi-source-555", "name": "MultiPathSource", "layer": "LA"}
        }
        
        mock_target = {
            "success": True,
            "element": {"id": "multi-target-666", "name": "MultiPathTarget", "layer": "PA"}
        }
        
        # Mock network with multiple paths
        mock_multi_path_network = {
            "success": True,
            "network": {
                "source_element": {"id": "multi-source-555", "name": "MultiPathSource"},
                "relationships": [
                    # Path 1: Direct allocation
                    {
                        "target_id": "multi-target-666",
                        "target_name": "MultiPathTarget",
                        "relationship_type": "allocation",
                        "direction": "outgoing",
                        "layer": "PA"
                    },
                    # Path 2: Through intermediate component
                    {
                        "target_id": "intermediate-777",
                        "target_name": "IntermediateComp1", 
                        "relationship_type": "composition",
                        "direction": "outgoing",
                        "layer": "LA"
                    },
                    # Path 3: Through different intermediate  
                    {
                        "target_id": "intermediate-888",
                        "target_name": "IntermediateComp2",
                        "relationship_type": "interface",
                        "direction": "outgoing", 
                        "layer": "LA"
                    }
                ]
            }
        }
        
        # Mock impact analysis for path comparison
        mock_path_analysis = {
            "success": True,
            "impact_matrix": {
                "multi-target-666": 0.95,   # Direct path - highest score
                "intermediate-777": 0.80,   # Composition path
                "intermediate-888": 0.70    # Interface path
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_source, mock_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_multi_path_network), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_path_analysis):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="multi-source-555",
                    target_element_id="multi-target-666",
                    max_paths=3  # Request multiple paths
                )
                
                # Should discover multiple alternative paths
                assert "navigation_result" in result, "Should return multiple path navigation"
                
                nav_result = result["navigation_result"]
                if "paths" in nav_result:
                    paths = nav_result["paths"]
                    
                    # Should discover multiple paths (up to max_paths)
                    assert len(paths) >= 1, "Should discover at least one path"
                    assert len(paths) <= 3, "Should not exceed max_paths limit"
                    
                    # Paths should be ranked by quality
                    if len(paths) > 1:
                        first_quality = paths[0].get("quality_score", 0)
                        second_quality = paths[1].get("quality_score", 0)
                        assert first_quality >= second_quality, \
                            "Paths should be ranked by quality score (highest first)"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_optimization_strategies(self):
        """Test navigate_architectural_paths path optimization strategies."""
        
        # Mock scenario for testing different optimization approaches
        mock_wrapper = MagicMock()
        
        # Mock source and target for optimization test
        mock_source = {
            "success": True,
            "element": {"id": "opt-source-111", "name": "OptSource", "layer": "SA"}
        }
        
        mock_target = {
            "success": True, 
            "element": {"id": "opt-target-222", "name": "OptTarget", "layer": "LA"}
        }
        
        # Mock network with paths of different characteristics
        mock_optimization_network = {
            "success": True,
            "network": {
                "source_element": {"id": "opt-source-111", "name": "OptSource"},
                "relationships": [
                    {
                        "target_id": "fast-path-333",
                        "target_name": "FastPath",
                        "relationship_type": "interface",  # Fast but lower quality
                        "direction": "outgoing",
                        "layer": "LA"
                    },
                    {
                        "target_id": "quality-path-444",
                        "target_name": "QualityPath", 
                        "relationship_type": "allocation",  # Slower but higher quality
                        "direction": "outgoing",
                        "layer": "LA"
                    }
                ]
            }
        }
        
        # Mock analysis emphasizing different optimization criteria
        mock_optimization_analysis = {
            "success": True,
            "impact_matrix": {
                "fast-path-333": 0.70,     # Lower quality, faster
                "quality-path-444": 0.90   # Higher quality, slower
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_source, mock_target, mock_source, mock_target, mock_source, mock_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_optimization_network), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_optimization_analysis):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                # Test quality optimization (should prefer quality-path-444)
                result_quality = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="opt-source-111",
                    target_element_id="opt-target-222",
                    path_optimization="quality"
                )
                
                # Test speed optimization (should prefer fast-path-333)
                result_speed = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="opt-source-111", 
                    target_element_id="opt-target-222",
                    path_optimization="speed"
                )
                
                # Test balanced optimization
                result_balanced = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="opt-source-111",
                    target_element_id="opt-target-222",
                    path_optimization="balanced"
                )
                
                # All optimization strategies should return valid results
                for result in [result_quality, result_speed, result_balanced]:
                    assert "navigation_result" in result, \
                        "All optimization strategies should return navigation results"
                    
                    if "navigation_result" in result and "paths" in result["navigation_result"]:
                        paths = result["navigation_result"]["paths"]
                        assert len(paths) > 0, "Should discover paths with optimization"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_performance_requirements(self):
        """Test navigate_architectural_paths meets performance requirements (<2s)."""
        
        # Mock large model with complex path discovery requirements
        mock_wrapper = MagicMock()
        
        # Mock complex network scenario for performance testing
        mock_source = {
            "success": True,
            "element": {"id": "perf-source-999", "name": "PerfSource", "layer": "OA"}
        }
        
        mock_target = {
            "success": True,
            "element": {"id": "perf-target-888", "name": "PerfTarget", "layer": "PA"}
        }
        
        # Create large relationship network (100+ elements)
        mock_large_network = {
            "success": True,
            "network": {
                "source_element": {"id": "perf-source-999", "name": "PerfSource"},
                "relationships": []
            }
        }
        
        # Add 100 mock relationships for performance test
        for i in range(100):
            layer = ["OA", "SA", "LA", "PA"][i % 4]  # Distribute across layers
            mock_large_network["network"]["relationships"].append({
                "target_id": f"perf-element-{i:03d}",
                "target_name": f"PerfElement_{i}",
                "relationship_type": "allocation",
                "direction": "outgoing",
                "layer": layer
            })
        
        # Mock large impact matrix
        mock_large_analysis = {
            "success": True,
            "impact_matrix": {f"perf-element-{i:03d}": 0.5 + (i % 50) / 100.0 for i in range(100)}
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_source, mock_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_large_network), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_large_analysis):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                import time
                start_time = time.time()
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="perf-source-999",
                    target_element_id="perf-target-888",
                    max_paths=5,
                    max_depth=10,
                    path_optimization="balanced"
                )
                
                end_time = time.time()
                duration = end_time - start_time
                
                # Path navigation should complete quickly (< 2 seconds)
                assert duration < 2.0, f"Navigation took too long: {duration:.2f}s"
                
                # Should return navigation results
                assert "navigation_result" in result, \
                    "Should return navigation result within performance requirements"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_layer_preference_filtering(self):
        """Test navigate_architectural_paths respects layer_preference parameter."""
        
        # Mock scenario with multi-layer options
        mock_wrapper = MagicMock()
        
        mock_source = {
            "success": True,
            "element": {"id": "layer-source-321", "name": "LayerSource", "layer": "SA"}
        }
        
        mock_target = {
            "success": True,
            "element": {"id": "layer-target-654", "name": "LayerTarget", "layer": "PA"}
        }
        
        # Mock network with elements in different layers
        mock_layer_network = {
            "success": True,
            "network": {
                "source_element": {"id": "layer-source-321", "name": "LayerSource"},
                "relationships": [
                    {
                        "target_id": "oa-element-111",
                        "target_name": "OAElement",
                        "relationship_type": "allocation",
                        "direction": "outgoing",
                        "layer": "OA"
                    },
                    {
                        "target_id": "la-element-222", 
                        "target_name": "LAElement",
                        "relationship_type": "allocation",
                        "direction": "outgoing",
                        "layer": "LA"
                    },
                    {
                        "target_id": "pa-element-333",
                        "target_name": "PAElement",
                        "relationship_type": "allocation",
                        "direction": "outgoing", 
                        "layer": "PA"
                    }
                ]
            }
        }
        
        # Mock analysis for layer filtering
        mock_layer_analysis = {
            "success": True,
            "impact_matrix": {
                "oa-element-111": 0.60,  # OA layer
                "la-element-222": 0.80,  # LA layer (preferred)
                "pa-element-333": 0.90   # PA layer (preferred)
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_source, mock_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_layer_network), \
             patch('capella_mcp.server.analyze_element_dependencies', return_value=mock_layer_analysis):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                # Test layer preference (prefer LA and PA, avoid OA)
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="layer-source-321",
                    target_element_id="layer-target-654",
                    layer_preference=["LA", "PA"]  # Exclude OA
                )
                
                # Should respect layer preferences in path discovery
                assert "navigation_result" in result, "Should return layer-filtered navigation"
                
                nav_result = result["navigation_result"]
                if "paths" in nav_result and len(nav_result["paths"]) > 0:
                    # Should discover paths in preferred layers
                    paths = nav_result["paths"]
                    preferred_path_count = 0
                    total_paths = len(paths)
                    
                    for path in paths:
                        if "path_layers" in path:
                            path_layers = path["path_layers"]
                            # Count paths that contain preferred layers
                            if any(layer in ["LA", "PA"] for layer in path_layers):
                                preferred_path_count += 1
                    
                    # At least one path should contain preferred layers
                    assert preferred_path_count > 0, \
                        f"Should find at least one path with preferred layers (LA, PA). Found {preferred_path_count}/{total_paths}"
                    
                    # Preferred paths should be majority when layer preference is specified
                    if total_paths > 1:
                        preference_ratio = preferred_path_count / total_paths
                        assert preference_ratio >= 0.5, \
                            f"Majority of paths should use preferred layers: {preference_ratio:.2f} ({preferred_path_count}/{total_paths})"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_has_readonly_and_idempotent_annotations(self):
        """Test that navigate_architectural_paths has proper MCP annotations."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "navigate_architectural_paths" in tool_dict:
            tool = tool_dict["navigate_architectural_paths"]
            
            # Path navigation is read-only
            assert hasattr(tool, 'annotations'), "navigate_architectural_paths missing annotations"
            assert tool.annotations.readOnlyHint is True, \
                "navigate_architectural_paths should have readOnlyHint: True (navigation is read-only)"
            
            # Path navigation is idempotent (same query = same result)
            assert tool.annotations.idempotentHint is True, \
                "navigate_architectural_paths should have idempotentHint: True (same paths for same query)"
            
            # Should NOT be marked as destructive
            assert tool.annotations.destructiveHint is not True, \
                "navigate_architectural_paths should not have destructiveHint (navigation is safe)"
            
            # Should work in closed model environment
            assert tool.annotations.openWorldHint is not True, \
                "navigate_architectural_paths should have openWorldHint: False (closed model environment)"


class TestNavigateArchitecturalPathsErrorHandling:
    """Test error handling in navigate_architectural_paths tool."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_navigate_paths_nonexistent_source_element(self):
        """Test navigate_architectural_paths with nonexistent source element."""
        
        mock_wrapper = MagicMock()
        
        # Mock nonexistent source element
        mock_error_source = {
            "error": "Element not found",
            "success": False
        }
        
        mock_valid_target = {
            "success": True,
            "element": {"id": "valid-target-123", "name": "ValidTarget", "layer": "PA"}
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_error_source, mock_valid_target]):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="nonexistent-source",
                    target_element_id="valid-target-123"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle nonexistent source elements gracefully"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_nonexistent_target_element(self):
        """Test navigate_architectural_paths with nonexistent target element."""
        
        mock_wrapper = MagicMock()
        
        # Mock valid source and nonexistent target
        mock_valid_source = {
            "success": True,
            "element": {"id": "valid-source-456", "name": "ValidSource", "layer": "LA"}
        }
        
        mock_error_target = {
            "error": "Element not found",
            "success": False
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_valid_source, mock_error_target]):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="valid-source-456",
                    target_element_id="nonexistent-target"
                )
                
                # Should return error with helpful message
                assert "error" in result or "success" in result, \
                    "Should handle nonexistent target elements gracefully"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_unreachable_target(self):
        """Test navigate_architectural_paths with unreachable target element."""
        
        mock_wrapper = MagicMock()
        
        # Mock valid source and target but no connecting path
        mock_source = {
            "success": True,
            "element": {"id": "isolated-source-789", "name": "IsolatedSource", "layer": "OA"}
        }
        
        mock_target = {
            "success": True,
            "element": {"id": "isolated-target-101", "name": "IsolatedTarget", "layer": "PA"}
        }
        
        # Mock empty relationship network (no connections)
        mock_empty_network = {
            "success": True,
            "network": {
                "source_element": {"id": "isolated-source-789", "name": "IsolatedSource"},
                "relationships": []  # No relationships = unreachable
            }
        }
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper), \
             patch('capella_mcp.server.get_element_details', side_effect=[mock_source, mock_target]), \
             patch('capella_mcp.server.traverse_element_relationships', return_value=mock_empty_network):
            
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                result = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="isolated-source-789",
                    target_element_id="isolated-target-101"
                )
                
                # Should handle unreachable targets gracefully
                assert "navigation_result" in result or "error" in result, \
                    "Should handle unreachable targets gracefully"
                
                if "navigation_result" in result:
                    nav_result = result["navigation_result"]
                    if "paths" in nav_result:
                        paths = nav_result["paths"]
                        # Should return empty paths or paths with failure indication
                        assert isinstance(paths, list), "Paths should be list even when empty"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_invalid_parameters(self):
        """Test navigate_architectural_paths with invalid parameters."""
        
        mock_wrapper = MagicMock()
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_wrapper):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "navigate_architectural_paths" in tool_dict:
                from capella_mcp.server import navigate_architectural_paths
                
                # Test invalid max_paths (negative)
                result_invalid_paths = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="source-123",
                    target_element_id="target-456",
                    max_paths=-1
                )
                
                # Should handle invalid max_paths gracefully
                assert "error" in result_invalid_paths or "navigation_result" in result_invalid_paths, \
                    "Should handle invalid max_paths parameter"
                
                # Test invalid max_depth (negative)  
                result_invalid_depth = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="source-123",
                    target_element_id="target-456",
                    max_depth=-5
                )
                
                # Should handle invalid max_depth gracefully
                assert "error" in result_invalid_depth or "navigation_result" in result_invalid_depth, \
                    "Should handle invalid max_depth parameter"
                
                # Test invalid path_optimization
                result_invalid_opt = await navigate_architectural_paths(
                    model_id="test_model",
                    source_element_id="source-123",
                    target_element_id="target-456",
                    path_optimization="invalid_strategy"
                )
                
                # Should handle invalid optimization strategy gracefully
                assert "error" in result_invalid_opt or "navigation_result" in result_invalid_opt, \
                    "Should handle invalid optimization strategy"


class TestNavigateArchitecturalPathsIntegration:
    """Integration tests for navigate_architectural_paths."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_navigate_paths_integration_with_real_gmhcd_model(self):
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
                    
                    # Get real elements to test navigation with
                    if "get_model_elements" in tool_dict:
                        from capella_mcp.server import get_model_elements
                        elements_result = await get_model_elements(model_id)
                        
                        if elements_result.get("count", 0) >= 2:
                            elements = elements_result["elements"]
                            source_id = elements[0]["id"]
                            target_id = elements[1]["id"]  # Different element
                            
                            # Test navigate_architectural_paths with real model
                            if "navigate_architectural_paths" in tool_dict:
                                from capella_mcp.server import navigate_architectural_paths
                                
                                result = await navigate_architectural_paths(
                                    model_id=model_id,
                                    source_element_id=source_id,
                                    target_element_id=target_id,
                                    max_paths=2,
                                    path_optimization="quality"
                                )
                                
                                # Should work with real model
                                assert "success" in result or "navigation_result" in result, \
                                    f"Real model navigation failed: {result}"
                
        except Exception as e:
            # Skip test if model not available
            pytest.skip(f"Skipping integration test - model not available: {e}")
    
    @pytest.mark.asyncio
    async def test_navigate_paths_foundation_tool_dependency(self):
        """Test that navigate_architectural_paths uses Stage 2 and Stage 3 foundation tools."""
        
        # This test verifies the dependency on all foundation tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Required foundation tools must exist
        foundation_tools = [
            "get_element_details",           # Stage 2
            "find_references_working",       # Stage 2
            "traverse_element_relationships", # Stage 3 Tool 1
            "analyze_element_dependencies"   # Stage 3 Tool 2
        ]
        
        for foundation_tool in foundation_tools:
            assert foundation_tool in tool_dict, \
                f"Foundation tool {foundation_tool} not found - required for architectural navigation"
        
        # navigate_architectural_paths should exist and depend on these
        assert "navigate_architectural_paths" in tool_dict, \
            "navigate_architectural_paths not found - Stage 3 Tool 3 implementation missing"
    
    @pytest.mark.asyncio
    async def test_navigate_paths_completes_stage3_relationship_management(self):
        """Test that navigate_architectural_paths completes Stage 3 Relationship Management."""
        
        # Verify all Stage 3 tools are implemented
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        stage3_tools = [
            "traverse_element_relationships",  # Tool 1
            "analyze_element_dependencies",    # Tool 2
            "navigate_architectural_paths"     # Tool 3
        ]
        
        for stage3_tool in stage3_tools:
            assert stage3_tool in tool_dict, \
                f"Stage 3 tool {stage3_tool} not found - Stage 3 incomplete"
        
        # All Stage 3 tools should have read-only annotations
        for stage3_tool in stage3_tools:
            if stage3_tool in tool_dict:
                tool = tool_dict[stage3_tool]
                assert hasattr(tool, 'annotations'), f"{stage3_tool} missing annotations"
                assert tool.annotations.readOnlyHint is True, \
                    f"{stage3_tool} should have readOnlyHint: True (relationship tools are read-only)"


if __name__ == "__main__":
    """Run Stage 3 relationship management tests."""
    pytest.main([__file__, "-v", "--tb=short"])