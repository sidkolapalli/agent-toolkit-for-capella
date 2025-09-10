"""
Test suite for enhanced tools following TDD principles.
"""

import pytest
from datetime import datetime
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.tools import ToolRegistry
from capella_mcp.enhanced_models import (
    ComponentSpec,
    InterfaceSpec,
    ActorSpec,
    FunctionalChainSpec,
    DrawIOElement,
    DrawIOImportSpec,
    ComponentType,
    ArchitectureLevel,
    InterfaceDirection,
    ValidationSeverity
)


class TestToolsRegistry:
    """Test unified tools registry."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    def test_registry_initialization(self, registry):
        """Test that registry initializes with all tools."""
        assert len(registry.tools) > 0
        
        # Check key tools are registered
        assert "create_component_with_interfaces" in registry.tools
        assert "create_actor_with_scenarios" in registry.tools
        assert "import_from_drawio" in registry.tools
        assert "validate_architecture" in registry.tools
        assert "analyze_dependencies" in registry.tools
    
    def test_tool_has_required_fields(self, registry):
        """Test that each tool has required fields."""
        for tool_name, tool in registry.tools.items():
            assert hasattr(tool, "name")
            assert hasattr(tool, "description")
            assert hasattr(tool, "parameters")
            assert hasattr(tool, "handler")
            assert tool.handler is not None


class TestCreateComponentWithInterfaces:
    """Test create_component_with_interfaces tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_create_simple_component(self, registry):
        """Test creating a simple component."""
        args = {
            "model_id": "test_model",
            "component_spec": {
                "name": "PowerManager",
                "component_type": "LogicalComponent",
                "architecture_level": "logical",
                "description": "Manages power distribution"
            }
        }
        
        result = await registry.call_tool("create_component_with_interfaces", args)
        
        assert result["status"] == "success"
        assert result["name"] == "PowerManager"
        assert result["type"] == "LogicalComponent"
        assert result["level"] == "logical"
        assert "component_id" in result
    
    @pytest.mark.asyncio
    async def test_create_component_with_interfaces(self, registry):
        """Test creating a component with interfaces."""
        args = {
            "model_id": "test_model",
            "component_spec": {
                "name": "Controller",
                "component_type": "LogicalComponent",
                "architecture_level": "logical",
                "interfaces": [
                    {
                        "name": "CAN_Input",
                        "interface_type": "CAN",
                        "direction": "IN",
                        "exchange_items": ["VehicleSpeed", "EngineRPM"]
                    },
                    {
                        "name": "Power_Output",
                        "interface_type": "Power",
                        "direction": "OUT",
                        "properties": {"voltage": "12V", "max_current": "10A"}
                    }
                ]
            }
        }
        
        result = await registry.call_tool("create_component_with_interfaces", args)
        
        assert result["status"] == "success"
        assert result["interfaces_created"] == 2
        assert len(result["interface_ids"]) == 2
        assert "Created component 'Controller' with" in result["message"]
    
    @pytest.mark.asyncio
    async def test_create_component_with_full_context(self, registry):
        """Test creating a component with all optional fields."""
        args = {
            "model_id": "test_model",
            "component_spec": {
                "name": "ComplexComponent",
                "component_type": "LogicalComponent",
                "architecture_level": "logical",
                "description": "A complex component with full context",
                "interfaces": [
                    {"name": "Interface1", "interface_type": "standard"},
                    {"name": "Interface2", "interface_type": "standard"}
                ],
                "allocated_functions": ["Function1", "Function2", "Function3"],
                "properties": {
                    "weight": 100,
                    "cost": 5000,
                    "criticality": "high"
                },
                "requirements": ["REQ-001", "REQ-002", "REQ-003"]
            }
        }
        
        result = await registry.call_tool("create_component_with_interfaces", args)
        
        assert result["status"] == "success"
        assert result["interfaces_created"] == 2
        assert result["properties_set"] == 3
        assert result["functions_allocated"] == 3
        assert "complete context" in result["message"]
    
    @pytest.mark.asyncio
    async def test_missing_required_parameters(self, registry):
        """Test that missing required parameters raise an error."""
        args = {
            "model_id": "test_model"
            # Missing component_spec
        }
        
        with pytest.raises(ValueError) as exc_info:
            await registry.call_tool("create_component_with_interfaces", args)
        
        assert "Missing required parameter: component_spec" in str(exc_info.value)


class TestCreateActorWithScenarios:
    """Test create_actor_with_scenarios tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_create_actor(self, registry):
        """Test creating an actor with scenarios."""
        args = {
            "model_id": "test_model",
            "actor_spec": {
                "name": "SystemOperator",
                "actor_type": "Human",
                "architecture_level": "operational",
                "description": "System operator responsible for monitoring",
                "scenarios": ["Normal Operation", "Emergency Response", "Maintenance"],
                "capabilities": ["Monitor System", "Control Power", "Execute Emergency Shutdown"],
                "missions": ["Ensure Safety", "Optimize Performance"]
            }
        }
        
        result = await registry.call_tool("create_actor_with_scenarios", args)
        
        assert result["status"] == "success"
        assert result["name"] == "SystemOperator"
        assert result["scenarios_created"] == 3
        assert result["capabilities_defined"] == 3
        assert result["missions_assigned"] == 2


class TestImportFromDrawIO:
    """Test import_from_drawio tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_import_drawio_diagram(self, registry):
        """Test importing a draw.io diagram."""
        args = {
            "model_id": "test_model",
            "import_spec": {
                "diagram_name": "System Architecture",
                "elements": [
                    {
                        "id": "elem1",
                        "type": "rectangle",
                        "label": "PowerController",
                        "x": 100,
                        "y": 200,
                        "width": 150,
                        "height": 80,
                        "connections": ["elem2", "elem3"]
                    },
                    {
                        "id": "elem2",
                        "type": "rectangle",
                        "label": "BatteryManager",
                        "x": 300,
                        "y": 200,
                        "width": 150,
                        "height": 80,
                        "connections": ["elem1"]
                    },
                    {
                        "id": "elem3",
                        "type": "ellipse",
                        "label": "External System",
                        "x": 200,
                        "y": 350,
                        "width": 100,
                        "height": 100,
                        "connections": []
                    }
                ],
                "architecture_level": "logical",
                "auto_create_interfaces": True,
                "preserve_layout": True
            }
        }
        
        result = await registry.call_tool("import_from_drawio", args)
        
        assert result["status"] == "success"
        assert result["diagram_name"] == "System Architecture"
        assert result["components_created"] == 3
        assert len(result["components"]) == 3
        
        # Check component types
        components = result["components"]
        assert any(c["name"] == "PowerController" for c in components)
        assert any(c["name"] == "BatteryManager" for c in components)
        assert any(c["type"] == "LogicalActor" for c in components)  # Ellipse becomes actor


class TestValidateArchitecture:
    """Test validate_architecture tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_validate_with_rules(self, registry):
        """Test validation with specific rules."""
        args = {
            "model_id": "test_model",
            "validation_rules": ["completeness", "consistency", "interfaces"],
            "severity_threshold": "warning"
        }
        
        result = await registry.call_tool("validate_architecture", args)
        
        assert result["status"] == "success"
        assert "valid" in result
        assert "errors" in result
        assert "warnings" in result
        assert "rules_checked" in result
        assert result["rules_checked"] == ["completeness", "consistency", "interfaces"]
    
    @pytest.mark.asyncio
    async def test_validate_with_default_rules(self, registry):
        """Test validation with default rules."""
        args = {
            "model_id": "test_model"
        }
        
        result = await registry.call_tool("validate_architecture", args)
        
        assert result["status"] == "success"
        assert "issues" in result
        assert isinstance(result["issues"], list)


class TestAnalyzeDependencies:
    """Test analyze_dependencies tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_analyze_element_dependencies(self, registry):
        """Test analyzing dependencies of an element."""
        args = {
            "model_id": "test_model",
            "element_id": "comp_123",
            "depth": 2,
            "include_indirect": True
        }
        
        result = await registry.call_tool("analyze_dependencies", args)
        
        assert result["status"] == "success"
        assert result["element_id"] == "comp_123"
        assert "direct_dependencies" in result
        assert "dependent_elements" in result
        assert "impact_score" in result
        assert "dependencies" in result
        
        deps = result["dependencies"]
        assert "direct" in deps
        assert "indirect" in deps
        assert "dependents" in deps
        assert "interfaces" in deps
    
    @pytest.mark.asyncio
    async def test_analyze_without_indirect(self, registry):
        """Test analyzing only direct dependencies."""
        args = {
            "model_id": "test_model",
            "element_id": "comp_456",
            "include_indirect": False
        }
        
        result = await registry.call_tool("analyze_dependencies", args)
        
        assert result["status"] == "success"
        assert result["indirect_dependencies"] == 0


class TestCreateArchitectureFromSpec:
    """Test create_architecture_from_spec tool."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_create_complete_architecture(self, registry):
        """Test creating a complete architecture from specification."""
        args = {
            "model_id": "test_model",
            "architecture_spec": {
                "name": "Vehicle Control System",
                "level": "logical",
                "components": [
                    {
                        "name": "ECU",
                        "component_type": "LogicalComponent",
                        "architecture_level": "logical"
                    },
                    {
                        "name": "SensorUnit",
                        "component_type": "LogicalComponent",
                        "architecture_level": "logical"
                    }
                ],
                "actors": [
                    {
                        "name": "Driver",
                        "architecture_level": "logical",
                        "actor_type": "Human"
                    }
                ],
                "connections": [
                    {
                        "source": "ECU",
                        "target": "SensorUnit",
                        "type": "interface"
                    }
                ]
            }
        }
        
        result = await registry.call_tool("create_architecture_from_spec", args)
        
        assert result["status"] == "success"
        assert result["architecture_name"] == "Vehicle Control System"
        assert result["level"] == "logical"
        assert result["components_created"] == 2
        assert result["actors_created"] == 1
        assert result["connections_created"] == 1
        assert result["total_elements"] == 4


class TestToolErrorHandling:
    """Test error handling in tools."""
    
    @pytest.fixture
    def registry(self):
        """Create a registry instance."""
        return ToolRegistry()
    
    @pytest.mark.asyncio
    async def test_invalid_tool_name(self, registry):
        """Test calling a non-existent tool."""
        with pytest.raises(ValueError) as exc_info:
            await registry.call_tool("non_existent_tool", {})
        
        assert "Tool 'non_existent_tool' not found" in str(exc_info.value)
    
    @pytest.mark.asyncio
    async def test_invalid_component_spec(self, registry):
        """Test creating component with invalid specification."""
        args = {
            "model_id": "test_model",
            "component_spec": {
                "name": "",  # Invalid empty name
                "component_type": "LogicalComponent",
                "architecture_level": "logical"
            }
        }
        
        with pytest.raises(Exception):
            await registry.call_tool("create_component_with_interfaces", args)
    
    @pytest.mark.asyncio
    async def test_incompatible_type_and_level(self, registry):
        """Test creating component with incompatible type and level."""
        args = {
            "model_id": "test_model",
            "component_spec": {
                "name": "InvalidComponent",
                "component_type": "PhysicalComponent",  # Physical component
                "architecture_level": "logical"  # But logical level
            }
        }
        
        with pytest.raises(Exception):
            await registry.call_tool("create_component_with_interfaces", args)


def test_all_tools_async():
    """Test that all tool handlers are async."""
    registry = ToolRegistry()
    
    for tool_name, tool in registry.tools.items():
        handler = tool.handler
        assert handler is not None, f"Tool '{tool_name}' has no handler"
        assert asyncio.iscoroutinefunction(handler), f"Tool '{tool_name}' handler is not async"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])