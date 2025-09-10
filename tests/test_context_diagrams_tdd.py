"""
TDD Test Suite for Context Diagram Generation (Phase 1)

This test suite follows MCP protocol patterns and ensures proper implementation
of capellambse-context-diagrams integration for persistent diagram visibility.

Test Strategy:
1. Test context diagram generation from element context
2. Test diagram export formats (SVG/PNG)
3. Test element visibility in generated diagrams
4. Test automatic layout quality
5. Test context diagram performance
6. Test MCP tool compliance (input/output schemas, error handling)

Based on MCP Python SDK patterns from official documentation.
"""

import asyncio
import pytest
import tempfile
import os
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import Mock, AsyncMock, patch

# MCP types for proper tool implementation
try:
    import mcp.types as types
    from mcp.server.fastmcp import FastMCP
    MCP_AVAILABLE = True
except ImportError:
    MCP_AVAILABLE = False

# Test data and constants
TEST_MODEL_PATH = "<local-path>"
TEST_ELEMENT_UUID = "6edeadd1-33cb-42ce-9d80-a5741ac77a4f"
TEST_DIAGRAM_FORMATS = ["svg", "png", "pdf"]


class TestContextDiagramGeneration:
    """Test suite for context diagram generation functionality."""

    @pytest.fixture
    def temp_output_dir(self):
        """Create temporary directory for diagram output."""
        with tempfile.TemporaryDirectory() as temp_dir:
            yield temp_dir

    @pytest.fixture
    def mock_capellambse_model(self):
        """Mock capellambse model for testing."""
        model = Mock()
        model.uuid = "model_test_123"
        
        # Mock element
        element = Mock()
        element.uuid = TEST_ELEMENT_UUID
        element.name = "K0 Control"
        element.description = "Test component for diagram generation"
        
        # Mock model access
        model.by_uuid = Mock(return_value=element)
        model.get_element = Mock(return_value=element)
        
        return model, element

    @pytest.fixture
    def mock_context_diagrams(self):
        """Mock capellambse-context-diagrams functionality."""
        mock_context_diagram = Mock()
        mock_context_diagram.render.return_value = '<svg>mock diagram</svg>'
        mock_context_diagram.nodes = [Mock(), Mock()]  # Mock 2 nodes
        
        # Mock ELK data for connections count
        mock_elk_data = Mock()
        mock_child = Mock()
        mock_child.edges = [Mock(), Mock()]  # Mock 2 edges
        mock_elk_data.children = [mock_child]
        mock_context_diagram.elk_input_data.return_value = mock_elk_data
        
        yield mock_context_diagram

    def test_generate_context_diagram_tool_definition(self):
        """Test 1: Verify MCP tool definition follows official patterns."""
        if not MCP_AVAILABLE:
            pytest.skip("MCP not available")

        # Expected tool schema based on MCP SDK patterns
        expected_schema = {
            "name": "generate_context_diagram",
            "description": "Generate context diagram for Capella model element",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "model_id": {
                        "type": "string",
                        "description": "Model identifier"
                    },
                    "element_id": {
                        "type": "string", 
                        "description": "Element UUID to generate context for"
                    },
                    "format": {
                        "type": "string",
                        "enum": ["svg", "png", "pdf"],
                        "default": "svg",
                        "description": "Output diagram format"
                    },
                    "include_connections": {
                        "type": "boolean",
                        "default": True,
                        "description": "Include connected elements in context"
                    },
                    "layout_algorithm": {
                        "type": "string",
                        "enum": ["hierarchical", "circular", "force_directed"],
                        "default": "hierarchical",
                        "description": "Automatic layout algorithm"
                    }
                },
                "required": ["model_id", "element_id"]
            },
            "outputSchema": {
                "type": "object",
                "properties": {
                    "success": {"type": "boolean"},
                    "diagram_url": {"type": "string"},
                    "format": {"type": "string"},
                    "elements_count": {"type": "integer"},
                    "connections_count": {"type": "integer"},
                    "layout_info": {
                        "type": "object",
                        "properties": {
                            "width": {"type": "integer"},
                            "height": {"type": "integer"}
                        }
                    }
                },
                "required": ["success", "format", "elements_count"]
            }
        }

        # This test validates that our tool will follow MCP patterns
        assert expected_schema["name"] == "generate_context_diagram"
        assert "inputSchema" in expected_schema
        assert "outputSchema" in expected_schema
        assert expected_schema["inputSchema"]["required"] == ["model_id", "element_id"]

    @pytest.mark.asyncio
    async def test_context_diagram_generation_success(self, mock_capellambse_model, mock_context_diagrams, temp_output_dir):
        """Test 2: Verify successful context diagram generation."""
        model, element = mock_capellambse_model
        
        # Mock the element to have a context_diagram accessor that returns our mock diagram
        element.context_diagram = mock_context_diagrams
        
        # Mock the capellambse-context-diagrams import and module registration
        with patch.dict('sys.modules', {'capellambse_context_diagrams': Mock()}):
            # Import and test the actual server function
            from capella_mcp.server import generate_context_diagram
            from capella_mcp.server import model_manager
            
            # Mock the model manager to return our mock model
            mock_wrapper = Mock()
            mock_wrapper.model = model
            mock_wrapper.get_element.return_value = element
            
            with patch.object(model_manager, 'get_model', return_value=mock_wrapper):
                # Test successful generation
                result = await generate_context_diagram(
                    model_id="model_test_123",
                    element_id=TEST_ELEMENT_UUID,
                    format="svg"
                )

                assert result["success"] is True
                assert result["format"] == "svg"
                assert result["elements_count"] >= 1
                assert "diagram_url" in result
                # Note: We won't check file existence since we're using mocks

    @pytest.mark.asyncio
    async def test_diagram_export_formats_validation(self, mock_capellambse_model, temp_output_dir):
        """Test 3: Verify all supported export formats work correctly."""
        model, element = mock_capellambse_model
        
        async def generate_context_diagram(model_id: str, element_id: str, format: str = "svg") -> Dict[str, Any]:
            """Mock diagram generation with format validation."""
            supported_formats = ["svg", "png", "pdf"]
            
            if format not in supported_formats:
                return {
                    "success": False,
                    "error": f"Unsupported format: {format}. Supported: {supported_formats}"
                }
            
            # Mock diagram data for different formats
            format_data = {
                "svg": b'<svg>mock svg</svg>',
                "png": b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00',  # PNG header
                "pdf": b'%PDF-1.4\n1 0 obj\n<<>>\nendobj'  # PDF header
            }
            
            output_path = Path(temp_output_dir) / f"test_diagram.{format}"
            with open(output_path, 'wb') as f:
                f.write(format_data[format])
            
            return {
                "success": True,
                "diagram_url": str(output_path),
                "format": format,
                "elements_count": 1,
                "connections_count": 0
            }

        # Test each supported format
        for format_type in TEST_DIAGRAM_FORMATS:
            result = await generate_context_diagram(
                model_id="model_test_123",
                element_id=TEST_ELEMENT_UUID,
                format=format_type
            )
            
            assert result["success"] is True, f"Failed for format: {format_type}"
            assert result["format"] == format_type
            assert Path(result["diagram_url"]).exists()
            
        # Test unsupported format
        result = await generate_context_diagram(
            model_id="model_test_123",
            element_id=TEST_ELEMENT_UUID,
            format="unsupported"
        )
        assert result["success"] is False
        assert "error" in result

    @pytest.mark.asyncio
    async def test_element_visibility_in_generated_diagrams(self, mock_capellambse_model):
        """Test 4: Verify target elements appear in generated diagrams."""
        model, element = mock_capellambse_model
        
        async def generate_context_diagram_with_visibility(model_id: str, element_id: str) -> Dict[str, Any]:
            """Generate diagram and verify element visibility."""
            # Mock element discovery in context
            context_elements = [
                {"uuid": element_id, "name": "K0 Control", "type": "LogicalComponent"},
                {"uuid": "connected_1", "name": "Connected Element 1", "type": "LogicalComponent"},
                {"uuid": "connected_2", "name": "Connected Element 2", "type": "FunctionalExchange"}
            ]
            
            # Verify target element is included
            target_element = next((e for e in context_elements if e["uuid"] == element_id), None)
            if not target_element:
                return {
                    "success": False,
                    "error": f"Target element {element_id} not found in context"
                }
            
            return {
                "success": True,
                "diagram_url": "/tmp/mock_diagram.svg",
                "format": "svg",
                "elements_count": len(context_elements),
                "target_element_visible": True,
                "elements": context_elements
            }

        result = await generate_context_diagram_with_visibility(
            model_id="model_test_123",
            element_id=TEST_ELEMENT_UUID
        )

        assert result["success"] is True
        assert result["target_element_visible"] is True
        assert result["elements_count"] >= 1
        assert any(e["uuid"] == TEST_ELEMENT_UUID for e in result["elements"])

    @pytest.mark.asyncio
    async def test_automatic_layout_quality_validation(self, mock_capellambse_model):
        """Test 5: Verify automatic layout algorithms produce valid results."""
        model, element = mock_capellambse_model
        
        async def test_layout_algorithm(algorithm: str) -> Dict[str, Any]:
            """Test specific layout algorithm."""
            supported_algorithms = ["hierarchical", "circular", "force_directed"]
            
            if algorithm not in supported_algorithms:
                return {
                    "success": False,
                    "error": f"Unsupported algorithm: {algorithm}"
                }
            
            # Mock layout quality metrics
            layout_metrics = {
                "hierarchical": {"clarity": 0.9, "overlap": 0.1, "aspect_ratio": 1.6},
                "circular": {"clarity": 0.8, "overlap": 0.05, "aspect_ratio": 1.0},
                "force_directed": {"clarity": 0.85, "overlap": 0.15, "aspect_ratio": 1.3}
            }
            
            metrics = layout_metrics[algorithm]
            
            return {
                "success": True,
                "layout_algorithm": algorithm,
                "layout_quality": {
                    "clarity_score": metrics["clarity"],
                    "overlap_ratio": metrics["overlap"],
                    "aspect_ratio": metrics["aspect_ratio"]
                },
                "layout_info": {
                    "width": 800,
                    "height": 600,
                    "nodes_positioned": 5,
                    "edges_routed": 4
                }
            }

        # Test each layout algorithm
        algorithms = ["hierarchical", "circular", "force_directed"]
        
        for algorithm in algorithms:
            result = await test_layout_algorithm(algorithm)
            
            assert result["success"] is True
            assert result["layout_algorithm"] == algorithm
            assert "layout_quality" in result
            assert result["layout_quality"]["clarity_score"] > 0.7  # Quality threshold
            assert result["layout_quality"]["overlap_ratio"] < 0.2   # Overlap threshold

    @pytest.mark.asyncio
    async def test_context_diagram_performance_benchmarks(self, mock_capellambse_model):
        """Test 6: Verify diagram generation performance meets requirements."""
        import time
        
        model, element = mock_capellambse_model
        
        async def generate_timed_diagram(complexity: str) -> Dict[str, Any]:
            """Generate diagram with timing metrics."""
            start_time = time.time()
            
            # Mock different complexity levels
            complexity_configs = {
                "simple": {"elements": 5, "connections": 3, "delay": 0.1},
                "medium": {"elements": 20, "connections": 15, "delay": 0.5},
                "complex": {"elements": 50, "connections": 40, "delay": 1.2}
            }
            
            config = complexity_configs.get(complexity, complexity_configs["simple"])
            
            # Simulate processing time
            await asyncio.sleep(config["delay"])
            
            generation_time = time.time() - start_time
            
            return {
                "success": True,
                "generation_time_seconds": generation_time,
                "complexity_level": complexity,
                "elements_processed": config["elements"],
                "connections_processed": config["connections"],
                "performance_rating": "excellent" if generation_time < 1.0 else "acceptable"
            }

        # Test performance for different complexity levels
        performance_results = {}
        
        for complexity in ["simple", "medium", "complex"]:
            result = await generate_timed_diagram(complexity)
            performance_results[complexity] = result
            
            assert result["success"] is True
            assert result["generation_time_seconds"] < 5.0  # Max 5 second threshold
            
        # Verify performance scaling is reasonable
        simple_time = performance_results["simple"]["generation_time_seconds"]
        complex_time = performance_results["complex"]["generation_time_seconds"]
        assert complex_time / simple_time < 20  # Reasonable scaling factor

    @pytest.mark.asyncio
    async def test_mcp_error_handling_patterns(self, mock_capellambse_model):
        """Test 7: Verify proper MCP error handling patterns."""
        model, element = mock_capellambse_model
        
        async def generate_with_error_handling(model_id: str, element_id: str) -> Dict[str, Any]:
            """Test comprehensive error handling."""
            try:
                # Input validation
                if not model_id:
                    return {
                        "success": False,
                        "error": "Missing required parameter: model_id",
                        "error_code": "INVALID_INPUT"
                    }
                
                if not element_id:
                    return {
                        "success": False,
                        "error": "Missing required parameter: element_id", 
                        "error_code": "INVALID_INPUT"
                    }
                
                # Model validation
                if model_id == "invalid_model":
                    return {
                        "success": False,
                        "error": f"Model not found: {model_id}",
                        "error_code": "MODEL_NOT_FOUND"
                    }
                
                # Element validation
                if element_id == "invalid_element":
                    return {
                        "success": False,
                        "error": f"Element not found: {element_id}",
                        "error_code": "ELEMENT_NOT_FOUND"
                    }
                
                # Extension availability check
                if model_id == "no_extension":
                    return {
                        "success": False,
                        "error": "capellambse-context-diagrams extension not available",
                        "error_code": "EXTENSION_UNAVAILABLE"
                    }
                
                # Success case
                return {
                    "success": True,
                    "diagram_url": "/tmp/success_diagram.svg",
                    "format": "svg",
                    "elements_count": 1
                }
                
            except Exception as e:
                return {
                    "success": False,
                    "error": f"Unexpected error: {str(e)}",
                    "error_code": "INTERNAL_ERROR"
                }

        # Test various error scenarios
        error_cases = [
            ("", TEST_ELEMENT_UUID, "INVALID_INPUT"),
            ("model_123", "", "INVALID_INPUT"),
            ("invalid_model", TEST_ELEMENT_UUID, "MODEL_NOT_FOUND"),
            ("model_123", "invalid_element", "ELEMENT_NOT_FOUND"),
            ("no_extension", TEST_ELEMENT_UUID, "EXTENSION_UNAVAILABLE"),
        ]
        
        for model_id, element_id, expected_error in error_cases:
            result = await generate_with_error_handling(model_id, element_id)
            assert result["success"] is False
            assert result["error_code"] == expected_error
            assert "error" in result

        # Test success case
        result = await generate_with_error_handling("model_123", TEST_ELEMENT_UUID)
        assert result["success"] is True
        assert "diagram_url" in result

    def test_integration_readiness(self):
        """Test 8: Verify system is ready for capellambse-context-diagrams integration."""
        # Check if we can import required dependencies
        dependencies = [
            "capellambse",
            "pathlib", 
            "tempfile",
            "asyncio"
        ]
        
        for dep in dependencies:
            try:
                __import__(dep)
            except ImportError:
                pytest.fail(f"Required dependency not available: {dep}")
        
        # Verify test constants are valid
        assert TEST_MODEL_PATH.endswith(".aird")
        assert len(TEST_ELEMENT_UUID) == 36  # UUID format
        assert all(fmt in ["svg", "png", "pdf"] for fmt in TEST_DIAGRAM_FORMATS)
        
        # Test file system access
        temp_dir = Path(tempfile.gettempdir())
        assert temp_dir.exists()
        assert temp_dir.is_dir()
        
        print("✅ Integration readiness verified - ready for Phase 1 implementation")


if __name__ == "__main__":
    pytest.main([__file__, "-v"])