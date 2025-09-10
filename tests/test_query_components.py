"""
Test suite for query_components functionality - TDD approach.
"""

import pytest
from unittest.mock import MagicMock, patch
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.model_manager import CapellaModelWrapper


class TestQueryElements:
    """Test that query_elements returns actual model data."""
    
    def test_query_elements_all_architectures(self):
        """Test that query_elements queries all architecture levels."""
        # Create mock model with all architecture levels
        mock_model = MagicMock()
        
        # Mock physical architecture components
        pa_comp1 = MagicMock()
        pa_comp1.uuid = "pa-uuid-1"
        pa_comp1.name = "K0"
        pa_comp1.__class__.__name__ = "PhysicalComponent"
        pa_comp1.parent = None
        
        pa_comp2 = MagicMock()
        pa_comp2.uuid = "pa-uuid-2"
        pa_comp2.name = "MCP"
        pa_comp2.__class__.__name__ = "PhysicalComponent"
        pa_comp2.parent = None
        
        mock_model.pa.all_components = [pa_comp1, pa_comp2]
        
        # Mock logical architecture components
        la_comp1 = MagicMock()
        la_comp1.uuid = "la-uuid-1"
        la_comp1.name = "LogicalController"
        la_comp1.__class__.__name__ = "LogicalComponent"
        la_comp1.parent = None
        
        mock_model.la.all_components = [la_comp1]
        
        # Mock system architecture components
        sa_comp1 = MagicMock()
        sa_comp1.uuid = "sa-uuid-1"
        sa_comp1.name = "SystemControl"
        sa_comp1.__class__.__name__ = "SystemComponent"
        sa_comp1.parent = None
        
        mock_model.sa.all_components = [sa_comp1]
        
        # Create wrapper with mock model
        wrapper = CapellaModelWrapper(mock_model, "test-model-id", use_capellambse=True)
        
        # Query all elements (no filter)
        elements = wrapper.query_elements()
        
        # Should return components from all architectures
        assert len(elements) == 4  # 2 PA + 1 LA + 1 SA
        
        # Verify we have components from each architecture
        element_names = [getattr(e, 'name', '') for e in elements]
        assert "K0" in element_names  # Physical
        assert "MCP" in element_names  # Physical
        assert "LogicalController" in element_names  # Logical
        assert "SystemControl" in element_names  # System
    
    def test_query_elements_with_type_filter(self):
        """Test that type filter works correctly."""
        mock_model = MagicMock()
        
        # Setup components
        pa_comp = MagicMock()
        pa_comp.uuid = "pa-uuid"
        pa_comp.name = "K0"
        pa_comp.__class__.__name__ = "PhysicalComponent"
        mock_model.pa.all_components = [pa_comp]
        
        la_comp = MagicMock()
        la_comp.uuid = "la-uuid"
        la_comp.name = "Logic1"
        la_comp.__class__.__name__ = "LogicalComponent"
        mock_model.la.all_components = [la_comp]
        
        mock_model.sa.all_components = []
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        
        # Filter for PhysicalComponent only
        elements = wrapper.query_elements({"type": "PhysicalComponent"})
        
        assert len(elements) == 1
        assert elements[0].name == "K0"
        
        # Filter for LogicalComponent only
        elements = wrapper.query_elements({"type": "LogicalComponent"})
        
        assert len(elements) == 1
        assert elements[0].name == "Logic1"
    
    def test_query_elements_with_name_filter(self):
        """Test that name filtering works."""
        mock_model = MagicMock()
        
        # Setup components with different names
        comp1 = MagicMock()
        comp1.uuid = "uuid-1"
        comp1.name = "K0_Controller"
        comp1.__class__.__name__ = "PhysicalComponent"
        
        comp2 = MagicMock()
        comp2.uuid = "uuid-2"
        comp2.name = "MCP_Module"
        comp2.__class__.__name__ = "PhysicalComponent"
        
        comp3 = MagicMock()
        comp3.uuid = "uuid-3"
        comp3.name = "K0_Sensor"
        comp3.__class__.__name__ = "PhysicalComponent"
        
        mock_model.pa.all_components = [comp1, comp2, comp3]
        mock_model.la.all_components = []
        mock_model.sa.all_components = []
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        
        # Filter for names containing "K0"
        elements = wrapper.query_elements({"name_contains": "K0"})
        
        assert len(elements) == 2
        element_names = [e.name for e in elements]
        assert "K0_Controller" in element_names
        assert "K0_Sensor" in element_names
        assert "MCP_Module" not in element_names
    
    def test_query_elements_handles_missing_architectures(self):
        """Test graceful handling when architecture levels are missing."""
        mock_model = MagicMock()
        
        # Only physical architecture exists
        pa_comp = MagicMock()
        pa_comp.uuid = "pa-uuid"
        pa_comp.name = "PhysicalComp"
        pa_comp.__class__.__name__ = "PhysicalComponent"
        mock_model.pa.all_components = [pa_comp]
        
        # Logical architecture doesn't exist
        mock_model.la = None
        
        # System architecture exists but empty
        mock_model.sa.all_components = []
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        
        # Should not crash, should return what exists
        elements = wrapper.query_elements()
        
        assert len(elements) == 1
        assert elements[0].name == "PhysicalComp"
    
    def test_query_elements_returns_empty_for_no_matches(self):
        """Test that empty list is returned when no elements match filter."""
        mock_model = MagicMock()
        
        comp = MagicMock()
        comp.uuid = "uuid"
        comp.name = "TestComponent"
        comp.__class__.__name__ = "PhysicalComponent"
        mock_model.pa.all_components = [comp]
        mock_model.la.all_components = []
        mock_model.sa.all_components = []
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=True)
        
        # Filter for non-existent name
        elements = wrapper.query_elements({"name_contains": "NonExistent"})
        
        assert len(elements) == 0
    
    def test_query_elements_mock_mode(self):
        """Test query_elements works in mock mode when capellambse unavailable."""
        # Use dict as mock model
        mock_model = {"type": "mock", "loaded": True}
        
        wrapper = CapellaModelWrapper(mock_model, "test-id", use_capellambse=False)
        
        # Add some elements to cache
        wrapper._element_cache["elem1"] = {
            "id": "elem1",
            "name": "Element1",
            "type": "PhysicalComponent"
        }
        wrapper._element_cache["elem2"] = {
            "id": "elem2",
            "name": "Element2",
            "type": "LogicalComponent"
        }
        
        # Query all
        elements = wrapper.query_elements()
        assert len(elements) == 2
        
        # Query with filter
        elements = wrapper.query_elements({"type": "PhysicalComponent"})
        assert len(elements) == 1
        assert elements[0]["name"] == "Element1"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])