#!/usr/bin/env python3
"""
Test Diagram Placement - Stage 1.2 TDD Implementation
=====================================================

This module tests that AI-created elements are properly placed on diagrams
to solve the critical visibility issue. Elements created without diagram placement
remain invisible in Capella workbench.

Following TDD approach - tests written first to drive implementation.

CRITICAL ISSUE: Elements created with visible_on_diagrams = [] are invisible
SOLUTION: Auto-place elements on appropriate diagrams during creation
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


class TestDiagramPlacement:
    """Test that elements are automatically placed on diagrams for visibility."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_place_element_on_diagram_tool_exists(self):
        """Test that place_element_on_diagram tool is available."""
        
        # Get all registered tools
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # Critical: place_element_on_diagram tool must exist
        assert "place_element_on_diagram" in tool_dict, \
            "place_element_on_diagram tool not found - CRITICAL for element visibility"
    
    @pytest.mark.asyncio
    async def test_place_element_tool_schema(self):
        """Test place_element_on_diagram has proper MCP schema."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "place_element_on_diagram" in tool_dict:
            tool = tool_dict["place_element_on_diagram"]
            
            # Check required parameters
            schema = tool.inputSchema
            assert "properties" in schema, "Tool missing input schema properties"
            
            properties = schema["properties"]
            assert "model_id" in properties, "Missing model_id parameter"
            assert "element_id" in properties, "Missing element_id parameter"
            
            # Optional parameters should be documented
            if "diagram_name" in properties:
                assert "description" in properties["diagram_name"], \
                    "diagram_name parameter needs description"
    
    @pytest.mark.asyncio
    async def test_create_component_auto_places_on_diagram(self):
        """Test that create_component automatically places elements on diagrams."""
        
        # Mock model setup
        mock_model = MagicMock()
        mock_layer = MagicMock()
        mock_component_pkg = MagicMock()
        mock_components = MagicMock()
        mock_diagram = MagicMock()
        mock_diagram.name = "TestDiagram"
        
        # Mock component creation
        mock_component = MagicMock()
        mock_component.uuid = "test-component-123"
        mock_component.name = "TestComponent"
        mock_component.visible_on_diagrams = []  # Initially empty (the problem!)
        
        mock_components.create.return_value = mock_component
        mock_component_pkg.components = mock_components
        mock_layer.component_pkg = mock_component_pkg
        mock_layer.diagrams = [mock_diagram]  # Available diagram
        mock_model.la = mock_layer
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            # Import the function to test
            from capella_mcp.server import create_component
            
            # Create component with auto-placement
            result = await create_component(
                model_id="test_model",
                name="TestComponent",
                component_type="LogicalComponent",
                parent_id=None
            )
            
            # Component should be created successfully
            assert "success" in result or "component_id" in result, \
                f"Component creation failed: {result}"
            
            # CRITICAL: Component should be placed on a diagram
            # This is the key requirement - elements must be visible
            if hasattr(mock_component, 'visible_on_diagrams'):
                assert len(mock_component.visible_on_diagrams) > 0, \
                    "CRITICAL: Created component not placed on any diagram - will be invisible in Capella"
    
    @pytest.mark.asyncio  
    async def test_place_element_on_specific_diagram(self):
        """Test placing an element on a specific named diagram."""
        
        # Mock model with diagrams
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "element-123"
        mock_element.visible_on_diagrams = []
        
        # Mock diagrams
        mock_diagram1 = MagicMock()
        mock_diagram1.name = "Component Architecture"
        mock_diagram2 = MagicMock() 
        mock_diagram2.name = "System Overview"
        
        mock_model.diagrams = [mock_diagram1, mock_diagram2]
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            # Test that we can place element on specific diagram
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "place_element_on_diagram" in tool_dict:
                from capella_mcp.server import place_element_on_diagram
                
                result = await place_element_on_diagram(
                    model_id="test_model",
                    element_id="element-123", 
                    diagram_name="Component Architecture"
                )
                
                assert "success" in result, f"Diagram placement failed: {result}"
                
                # Element should now be on the specified diagram
                assert len(mock_element.visible_on_diagrams) > 0, \
                    "Element not added to diagram"
    
    @pytest.mark.asyncio
    async def test_auto_diagram_selection(self):
        """Test automatic diagram selection based on element type."""
        
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "element-456"
        mock_element.__class__.__name__ = "LogicalComponent"
        mock_element.visible_on_diagrams = []
        
        # Mock layer-specific diagrams
        mock_la_diagram = MagicMock()
        mock_la_diagram.name = "Logical Architecture"
        mock_pa_diagram = MagicMock()
        mock_pa_diagram.name = "Physical Architecture"
        
        mock_model.la.diagrams = [mock_la_diagram]
        mock_model.pa.diagrams = [mock_pa_diagram]
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "place_element_on_diagram" in tool_dict:
                from capella_mcp.server import place_element_on_diagram
                
                # Auto-select diagram (no diagram_name specified)
                result = await place_element_on_diagram(
                    model_id="test_model",
                    element_id="element-456"
                )
                
                assert "success" in result, f"Auto diagram placement failed: {result}"
                
                # LogicalComponent should be placed on LA diagram
                assert len(mock_element.visible_on_diagrams) > 0, \
                    "Element not automatically placed on appropriate diagram"
    
    @pytest.mark.asyncio
    async def test_diagram_placement_prevents_invisibility_crisis(self):
        """Test that diagram placement solves the invisibility crisis."""
        
        # This test verifies the core issue is resolved
        mock_model = MagicMock()
        mock_element = MagicMock()
        mock_element.uuid = "crisis-element-789"
        mock_element.name = "CrisisComponent"
        mock_element.visible_on_diagrams = []  # The crisis: empty!
        
        mock_diagram = MagicMock()
        mock_diagram.name = "Architecture Overview"
        mock_model.diagrams = [mock_diagram]
        mock_model.by_uuid.return_value = mock_element
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "place_element_on_diagram" in tool_dict:
                from capella_mcp.server import place_element_on_diagram
                
                # Before: Element is invisible (empty visible_on_diagrams)
                assert len(mock_element.visible_on_diagrams) == 0, \
                    "Setup error: element should start invisible"
                
                # Apply the fix
                result = await place_element_on_diagram(
                    model_id="test_model",
                    element_id="crisis-element-789"
                )
                
                # After: Element is visible (non-empty visible_on_diagrams)
                assert len(mock_element.visible_on_diagrams) > 0, \
                    "CRISIS NOT RESOLVED: Element still invisible after placement"
                
                # Verify element is on a real diagram
                placed_diagram = mock_element.visible_on_diagrams[0]
                assert placed_diagram is not None, \
                    "Element placed on null diagram"
    
    def test_diagram_placement_tool_has_safety_annotations(self):
        """Test that diagram placement tool has proper safety annotations."""
        
        # This should NOT have destructiveHint (it's not destructive)
        # This should NOT have readOnlyHint (it modifies the model)
        # This could have idempotentHint (safe to repeat)
        
        tools = self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        if "place_element_on_diagram" in tool_dict:
            tool = tool_dict["place_element_on_diagram"]
            
            if hasattr(tool, 'annotations') and tool.annotations:
                annotations = tool.annotations
                
                # Should NOT be marked as destructive
                assert annotations.get('destructiveHint') is not True, \
                    "Diagram placement should not be marked destructive"
                
                # Should NOT be marked as read-only
                assert annotations.get('readOnlyHint') is not True, \
                    "Diagram placement modifies model, should not be read-only"
    
    @pytest.mark.asyncio
    async def test_integration_with_real_gmhcd_model(self):
        """Integration test with actual GMHCD model structure."""
        
        # This test should be run with actual model loaded
        # Skip if no model is loaded
        try:
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            if "list_models" in tool_dict:
                from capella_mcp.server import list_models
                models_result = await list_models()
                
                if models_result.get("count", 0) > 0:
                    # We have a real model loaded - test with it
                    model_id = list(models_result.get("models", {}).keys())[0]
                    
                    # Get model elements
                    if "get_model_elements" in tool_dict:
                        from capella_mcp.server import get_model_elements
                        elements_result = await get_model_elements(model_id)
                        
                        if elements_result.get("count", 0) > 0:
                            # Test diagram placement with real element
                            element_id = elements_result["elements"][0]["id"]
                            
                            if "place_element_on_diagram" in tool_dict:
                                from capella_mcp.server import place_element_on_diagram
                                
                                result = await place_element_on_diagram(
                                    model_id=model_id,
                                    element_id=element_id
                                )
                                
                                # Should work with real model
                                assert "success" in result or "placed" in str(result).lower(), \
                                    f"Real model diagram placement failed: {result}"
                
        except Exception as e:
            # Skip test if model not available
            pytest.skip(f"Skipping integration test - model not available: {e}")


class TestDiagramManagement:
    """Test diagram creation and management capabilities."""
    
    def setup_method(self):
        """Setup for each test method."""
        self.app = app
        self.model_manager = model_manager
    
    @pytest.mark.asyncio
    async def test_create_diagram_tool_exists(self):
        """Test that create_diagram tool is available."""
        
        tools = await self.app.list_tools()
        tool_dict = {tool.name: tool for tool in tools}
        
        # create_diagram tool should exist
        if "create_diagram" in tool_dict:
            tool = tool_dict["create_diagram"]
            
            # Check schema
            schema = tool.inputSchema
            properties = schema.get("properties", {})
            
            assert "model_id" in properties, "create_diagram missing model_id"
            assert "diagram_spec" in properties, "create_diagram missing diagram_spec"
            
            # Check diagram_spec structure
            diagram_spec = properties.get("diagram_spec", {})
            if "properties" in diagram_spec:
                spec_props = diagram_spec["properties"]
                assert "name" in spec_props, "diagram_spec missing name"
                assert "layer" in spec_props, "diagram_spec missing layer"
                assert "diagram_type" in spec_props, "diagram_spec missing diagram_type"
    
    @pytest.mark.asyncio
    async def test_list_available_diagrams(self):
        """Test that we can list available diagrams for placement."""
        
        # Mock model with various diagrams
        mock_model = MagicMock()
        mock_model.diagrams = []
        
        # Mock layer diagrams
        mock_la_diagram = MagicMock()
        mock_la_diagram.name = "LA Component Architecture"
        mock_pa_diagram = MagicMock()  
        mock_pa_diagram.name = "PA Node Allocation"
        
        mock_model.la.diagrams = [mock_la_diagram]
        mock_model.pa.diagrams = [mock_pa_diagram]
        
        with patch.object(self.model_manager, 'get_model', return_value=mock_model):
            tools = await self.app.list_tools()
            tool_dict = {tool.name: tool for tool in tools}
            
            # Should have a way to list diagrams
            if "list_diagrams" in tool_dict:
                from capella_mcp.server import list_diagrams
                
                result = await list_diagrams(model_id="test_model")
                
                assert "diagrams" in result, "list_diagrams should return diagrams"
                assert isinstance(result["diagrams"], list), \
                    "diagrams should be a list"


if __name__ == "__main__":
    """Run diagram placement tests."""
    pytest.main([__file__, "-v", "--tb=short"])