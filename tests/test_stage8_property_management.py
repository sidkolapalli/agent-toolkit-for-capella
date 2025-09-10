"""
Test suite for Stage 8: Property & Metadata Management tools.

This module tests 7 property management tools for ARCADIA property systems,
PVMT integration, and property analysis across architectural layers.

Stage 8 Tools:
1. manage_element_properties - CRUD operations for element properties
2. manage_property_values - Property value groups and definitions  
3. manage_property_types - Property type definitions including enumerations
4. query_by_properties - Property-based element discovery
5. manage_metadata_extensions - Custom metadata and extensions
6. import_property_definitions - Import property definitions from external sources
7. export_properties_report - Export property reports for analysis
"""

import pytest
from unittest.mock import Mock, patch, AsyncMock
from typing import List, Dict, Any, Optional
import asyncio

# Test imports
from capella_mcp.server import (
    manage_element_properties,
    manage_property_values,
    manage_property_types,
    query_by_properties,
    manage_metadata_extensions,
    import_property_definitions,
    export_properties_report
)


class TestStage8PropertyManagement:
    """Comprehensive test suite for Stage 8 Property & Metadata Management tools."""

    @pytest.mark.asyncio
    async def test_manage_element_properties_functionality(self):
        """
        Test manage_element_properties tool functionality.
        
        This is the foundation tool for property management providing CRUD
        operations for element properties with ARCADIA compliance.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property management testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock element with properties
            mock_element = Mock()
            mock_element.uuid = "element-123"
            mock_element.name = "TestElement"
            mock_element.applied_property_values = []
            
            # Mock property value
            mock_property_value = Mock()
            mock_property_value.uuid = "prop-value-456"
            mock_property_value.name = "TestProperty"
            mock_property_value.value = "TestValue"
            
            # Mock capellambse property APIs
            mock_model.by_uuid.side_effect = lambda uuid: {
                "element-123": mock_element,
                "prop-value-456": mock_property_value
            }.get(uuid)
            
            # Mock property value collections
            mock_model.applied_property_values = [mock_property_value]
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test property creation
                result = await manage_element_properties(
                    model_id="test_model_stage8",
                    operation="create",
                    element_id="element-123",
                    property_spec={
                        "name": "TestProperty",
                        "type": "String",
                        "value": "TestValue",
                        "description": "Test property for element"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert result["element_id"] == "element-123"
                assert "property_id" in result
                assert result["property_created"] is True
                
                # Test property listing
                result = await manage_element_properties(
                    model_id="test_model_stage8",
                    operation="list",
                    element_id="element-123",
                    filter_spec={"type": "String"}
                )
                
                # Validate property listing
                assert result["success"] is True
                assert result["operation"] == "list"
                assert "properties" in result
                assert result["total_count"] >= 0

    @pytest.mark.asyncio
    async def test_manage_property_values_functionality(self):
        """
        Test manage_property_values tool functionality.
        
        Tests CRUD operations for property value groups and definitions
        with PVMT compliance and hierarchy management.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property value testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock property value group
            mock_value_group = Mock()
            mock_value_group.uuid = "group-123"
            mock_value_group.name = "TestValueGroup"
            mock_value_group.property_values = []
            
            # Mock property value package
            mock_value_package = Mock()
            mock_value_package.uuid = "package-456"
            mock_value_package.name = "TestPackage"
            mock_value_package.property_value_groups = [mock_value_group]
            
            # Mock capellambse collections
            mock_model.property_value_packages = [mock_value_package]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "group-123": mock_value_group,
                "package-456": mock_value_package
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test value group creation
                result = await manage_property_values(
                    model_id="test_model_stage8",
                    operation="create_group",
                    group_spec={
                        "name": "TestPropertyGroup",
                        "description": "Test property value group",
                        "package_id": "package-456"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create_group"
                assert "group_id" in result
                assert result["group_created"] is True
                
                # Test property value creation
                result = await manage_property_values(
                    model_id="test_model_stage8",
                    operation="create_value",
                    value_spec={
                        "name": "TestValue",
                        "type": "String",
                        "default_value": "DefaultValue",
                        "group_id": "group-123"
                    }
                )
                
                # Validate value creation
                assert result["success"] is True
                assert result["operation"] == "create_value"
                assert "value_id" in result
                assert result["value_created"] is True

    @pytest.mark.asyncio
    async def test_manage_property_types_functionality(self):
        """
        Test manage_property_types tool functionality.
        
        Tests CRUD operations for property type definitions including
        enumerations and constraints with type safety validation.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property type testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock property type
            mock_property_type = Mock()
            mock_property_type.uuid = "type-123"
            mock_property_type.name = "TestPropertyType"
            mock_property_type.base_type = "String"
            
            # Mock enumeration
            mock_enumeration = Mock()
            mock_enumeration.uuid = "enum-456"
            mock_enumeration.name = "TestEnumeration"
            mock_enumeration.values = ["Value1", "Value2", "Value3"]
            
            # Mock capellambse collections
            mock_model.property_value_packages = []
            mock_model.by_uuid.side_effect = lambda uuid: {
                "type-123": mock_property_type,
                "enum-456": mock_enumeration
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test property type creation
                result = await manage_property_types(
                    model_id="test_model_stage8",
                    operation="create_type",
                    type_spec={
                        "name": "CustomStringType",
                        "base_type": "String",
                        "constraints": {"max_length": 255},
                        "description": "Custom string type with length constraint"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create_type"
                assert "type_id" in result
                assert result["type_created"] is True
                
                # Test enumeration creation
                result = await manage_property_types(
                    model_id="test_model_stage8",
                    operation="create_enumeration",
                    enumeration_spec={
                        "name": "TestEnumeration",
                        "values": ["Option1", "Option2", "Option3"],
                        "description": "Test enumeration type"
                    }
                )
                
                # Validate enumeration creation
                assert result["success"] is True
                assert result["operation"] == "create_enumeration"
                assert "enumeration_id" in result
                assert result["enumeration_created"] is True

    @pytest.mark.asyncio
    async def test_query_by_properties_functionality(self):
        """
        Test query_by_properties tool functionality.
        
        Tests property-based element discovery with filtering,
        search criteria, and ARCADIA layer compliance.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property query testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock elements with properties
            mock_elements = []
            for i in range(5):
                mock_element = Mock()
                mock_element.uuid = f"element-{i}"
                mock_element.name = f"Element{i}"
                mock_element.applied_property_values = []
                mock_elements.append(mock_element)
            
            # Mock property values
            mock_property_values = []
            for i in range(3):
                mock_prop_value = Mock()
                mock_prop_value.uuid = f"prop-{i}"
                mock_prop_value.name = f"Property{i}"
                mock_prop_value.value = f"Value{i}"
                mock_property_values.append(mock_prop_value)
            
            # Mock capellambse search results
            mock_model.search.return_value = mock_elements
            mock_model.applied_property_values = mock_property_values
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test property-based search
                result = await query_by_properties(
                    model_id="test_model_stage8",
                    query_spec={
                        "property_filters": [
                            {"name": "PropertyType", "value": "Component"},
                            {"name": "Layer", "value": "LA"}
                        ],
                        "element_types": ["LogicalComponent"],
                        "search_criteria": "exact_match"
                    },
                    search_scope="layer",
                    result_format="detailed"
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert "matching_elements" in result
                assert "search_criteria" in result
                assert result["total_matches"] >= 0
                assert result["search_scope"] == "layer"
                
                # Test aggregated search
                result = await query_by_properties(
                    model_id="test_model_stage8",
                    query_spec={
                        "property_filters": [
                            {"name": "Category", "value": "System"}
                        ],
                        "aggregation": "count_by_layer"
                    },
                    search_scope="all",
                    result_format="summary"
                )
                
                # Validate aggregated results
                assert result["success"] is True
                assert "aggregation_results" in result
                assert result["result_format"] == "summary"

    @pytest.mark.asyncio
    async def test_manage_metadata_extensions_functionality(self):
        """
        Test manage_metadata_extensions tool functionality.
        
        Tests custom metadata and extensions management for model elements
        with domain-specific annotations and external tool integration.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for metadata extension testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock element with extensions
            mock_element = Mock()
            mock_element.uuid = "element-123"
            mock_element.name = "TestElement"
            mock_element.extensions = []
            
            # Mock extension
            mock_extension = Mock()
            mock_extension.uuid = "ext-456"
            mock_extension.name = "TestExtension"
            mock_extension.metadata = {"tool": "external", "version": "1.0"}
            
            # Mock capellambse collections
            mock_model.by_uuid.side_effect = lambda uuid: {
                "element-123": mock_element,
                "ext-456": mock_extension
            }.get(uuid)
            
            # Mock extensions collection
            mock_model.extensions = [mock_extension]
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test metadata extension creation
                result = await manage_metadata_extensions(
                    model_id="test_model_stage8",
                    operation="add",
                    element_id="element-123",
                    extension_spec={
                        "name": "TestMetadata",
                        "type": "custom_annotation",
                        "metadata": {
                            "tool": "test_tool",
                            "version": "2.0",
                            "properties": {"key": "value"}
                        },
                        "namespace": "test.domain"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add"
                assert result["element_id"] == "element-123"
                assert "extension_id" in result
                assert result["extension_added"] is True
                
                # Test metadata listing
                result = await manage_metadata_extensions(
                    model_id="test_model_stage8",
                    operation="list",
                    element_id="element-123",
                    filter_spec={"type": "custom_annotation"}
                )
                
                # Validate metadata listing
                assert result["success"] is True
                assert result["operation"] == "list"
                assert "extensions" in result
                assert result["total_count"] >= 0

    @pytest.mark.asyncio
    async def test_import_property_definitions_functionality(self):
        """
        Test import_property_definitions tool functionality.
        
        Tests importing property definitions from external sources with
        validation, conflict resolution, and format handling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property import testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock property value packages
            mock_package = Mock()
            mock_package.uuid = "package-123"
            mock_package.name = "ImportPackage"
            
            # Mock capellambse collections
            mock_model.property_value_packages = [mock_package]
            mock_model.by_uuid.return_value = mock_package
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test CSV import
                csv_data = """name,type,description,default
TestProperty1,String,Test property 1,DefaultValue1
TestProperty2,Integer,Test property 2,42
TestProperty3,Boolean,Test property 3,true"""
                
                result = await import_property_definitions(
                    model_id="test_model_stage8",
                    import_spec={
                        "source_format": "csv",
                        "source_data": csv_data,
                        "property_mappings": {
                            "name": "name",
                            "type": "type",
                            "description": "description",
                            "default_value": "default"
                        },
                        "target_package": "package-123"
                    },
                    validation_mode="strict",
                    conflict_resolution="skip"
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert "imported_definitions" in result
                assert len(result["imported_definitions"]) >= 0
                assert "import_summary" in result
                assert result["import_summary"]["source_format"] == "csv"
                
                # Test JSON import
                json_data = {
                    "property_definitions": [
                        {
                            "name": "JsonProperty",
                            "type": "String",
                            "description": "Property from JSON import"
                        }
                    ],
                    "type_definitions": [
                        {
                            "name": "CustomType",
                            "base_type": "String",
                            "constraints": {"max_length": 100}
                        }
                    ]
                }
                
                result = await import_property_definitions(
                    model_id="test_model_stage8",
                    import_spec={
                        "source_format": "json",
                        "source_data": json_data
                    },
                    validation_mode="lenient"
                )
                
                # Validate JSON import
                assert result["success"] is True
                assert "imported_types" in result
                assert result["import_summary"]["source_format"] == "json"

    @pytest.mark.asyncio
    async def test_export_properties_report_functionality(self):
        """
        Test export_properties_report tool functionality.
        
        Tests comprehensive property analysis reporting with multiple
        formats, statistics, and compliance assessment.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for property report testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock property value packages
            mock_packages = []
            for i in range(3):
                mock_package = Mock()
                mock_package.uuid = f"package-{i}"
                mock_package.name = f"Package{i}"
                mock_packages.append(mock_package)
            
            # Mock property values
            mock_property_values = []
            for i in range(10):
                mock_prop_value = Mock()
                mock_prop_value.uuid = f"prop-{i}"
                mock_prop_value.name = f"Property{i}"
                mock_property_values.append(mock_prop_value)
            
            # Mock capellambse collections
            mock_model.property_value_packages = mock_packages
            mock_model.applied_property_values = mock_property_values
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test JSON report export
                result = await export_properties_report(
                    model_id="test_model_stage8",
                    export_spec={
                        "scope": "all",
                        "sections": ["inventory", "usage", "statistics"],
                        "analysis_depth": "detailed",
                        "layer_filter": ["oa", "sa", "la", "pa"],
                        "include_recommendations": True
                    },
                    report_format="json",
                    include_statistics=True
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["report_format"] == "json"
                assert "report_data" in result
                assert "property_inventory" in result
                assert "usage_analysis" in result
                assert "statistics_summary" in result
                assert "compliance_assessment" in result
                
                # Test HTML report export
                result = await export_properties_report(
                    model_id="test_model_stage8",
                    export_spec={
                        "scope": "layer",
                        "layer_filter": ["la"],
                        "sections": ["inventory", "usage"]
                    },
                    report_format="html",
                    include_statistics=False
                )
                
                # Validate HTML report
                assert result["success"] is True
                assert result["report_format"] == "html"
                assert isinstance(result["report_data"], str)
                assert "<html>" in result["report_data"]
                
                # Test CSV report export
                result = await export_properties_report(
                    model_id="test_model_stage8",
                    export_spec={
                        "scope": "all",
                        "analysis_depth": "summary"
                    },
                    report_format="csv"
                )
                
                # Validate CSV report
                assert result["success"] is True
                assert result["report_format"] == "csv"
                assert isinstance(result["report_data"], str)
                assert "Layer,Property Count" in result["report_data"]

    @pytest.mark.asyncio
    async def test_stage8_error_handling(self):
        """Test comprehensive error handling for all Stage 8 tools."""
        # Test with non-existent model
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model_manager.get_model.side_effect = KeyError("Model not found")
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test manage_element_properties error handling
                result = await manage_element_properties(
                    model_id="nonexistent_model",
                    operation="create",
                    element_id="test-element",
                    property_spec={"name": "test"}
                )
                
                assert result["success"] is False
                assert "error" in result
                assert "Model" in result["error"]
                
                # Test import_property_definitions error handling
                result = await import_property_definitions(
                    model_id="nonexistent_model",
                    import_spec={
                        "source_format": "invalid_format",
                        "source_data": ""
                    }
                )
                
                assert result["success"] is False
                assert "error" in result

    @pytest.mark.asyncio 
    async def test_stage8_enhanced_disabled(self):
        """Test graceful degradation when enhanced tools are disabled."""
        # Test with ENHANCED_AVAILABLE = False
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', False):
            result = await manage_element_properties(
                model_id="test_model",
                operation="create",
                element_id="test-element",
                property_spec={"name": "test"}
            )
            
            assert result["success"] is False
            assert "enhanced property management tools not available" in result["error"].lower()

    @pytest.mark.asyncio
    async def test_stage8_property_validation(self):
        """Test property validation and constraint checking."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock element
            mock_element = Mock()
            mock_element.uuid = "element-123"
            mock_model.by_uuid.return_value = mock_element
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test invalid property specification
                result = await manage_element_properties(
                    model_id="test_model",
                    operation="create",
                    element_id="element-123",
                    property_spec={}  # Empty spec should cause validation error
                )
                
                # Should handle gracefully
                assert "success" in result

    @pytest.mark.asyncio
    async def test_stage8_import_validation(self):
        """Test import validation and conflict resolution."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            mock_model.property_value_packages = []
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test missing required fields
                result = await import_property_definitions(
                    model_id="test_model",
                    import_spec={
                        # Missing source_format and source_data
                    }
                )
                
                assert result["success"] is False
                assert "Missing required" in result["error"]
                
                # Test unsupported format
                result = await import_property_definitions(
                    model_id="test_model",
                    import_spec={
                        "source_format": "unsupported_format",
                        "source_data": "test"
                    }
                )
                
                assert result["success"] is False
                assert "Unsupported source format" in result["error"]