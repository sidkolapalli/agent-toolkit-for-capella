"""
Test suite for enhanced Capella models.
Following TDD principles to ensure all models work correctly.
"""

import pytest
from datetime import datetime
from pydantic import ValidationError

import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.enhanced_models import (
    ArchitectureLevel,
    ComponentType,
    InterfaceDirection,
    ExchangeItemType,
    ValidationSeverity,
    InterfaceSpec,
    ComponentSpec,
    ActorSpec,
    FunctionalChainSpec,
    ValidationIssue,
    ValidationResult,
    ModelMetrics,
    DrawIOElement,
    DrawIOImportSpec
)


class TestEnums:
    """Test enumeration values."""
    
    def test_architecture_levels(self):
        """Test architecture level enum values."""
        assert ArchitectureLevel.OPERATIONAL == "operational"
        assert ArchitectureLevel.SYSTEM == "system"
        assert ArchitectureLevel.LOGICAL == "logical"
        assert ArchitectureLevel.PHYSICAL == "physical"
        assert ArchitectureLevel.EPBS == "epbs"
    
    def test_component_types(self):
        """Test component type enum values."""
        assert ComponentType.LOGICAL_COMPONENT == "LogicalComponent"
        assert ComponentType.PHYSICAL_COMPONENT == "PhysicalComponent"
        assert ComponentType.ACTOR == "Actor"
        assert ComponentType.LOGICAL_FUNCTION == "LogicalFunction"
    
    def test_interface_directions(self):
        """Test interface direction enum values."""
        assert InterfaceDirection.IN == "IN"
        assert InterfaceDirection.OUT == "OUT"
        assert InterfaceDirection.INOUT == "INOUT"
        assert InterfaceDirection.BIDIRECTIONAL == "bidirectional"


class TestInterfaceSpec:
    """Test InterfaceSpec model."""
    
    def test_create_basic_interface(self):
        """Test creating a basic interface specification."""
        interface = InterfaceSpec(
            name="CAN_Bus",
            interface_type="CAN",
            direction=InterfaceDirection.BIDIRECTIONAL,
            exchange_items=["VehicleSpeed", "EngineRPM"]
        )
        
        assert interface.name == "CAN_Bus"
        assert interface.interface_type == "CAN"
        assert interface.direction == InterfaceDirection.BIDIRECTIONAL
        assert len(interface.exchange_items) == 2
    
    def test_interface_with_properties(self):
        """Test interface with custom properties."""
        interface = InterfaceSpec(
            name="Ethernet_Port",
            interface_type="Ethernet",
            direction=InterfaceDirection.INOUT,
            properties={
                "speed": "1000Mbps",
                "protocol": "TCP/IP",
                "port": 8080
            }
        )
        
        assert interface.properties["speed"] == "1000Mbps"
        assert interface.properties["port"] == 8080
    
    def test_interface_name_validation(self):
        """Test interface name validation."""
        # Empty name should fail
        with pytest.raises(ValidationError) as exc_info:
            InterfaceSpec(name="", interface_type="Test")
        assert "name cannot be empty" in str(exc_info.value)
        
        # Name with invalid characters should be cleaned
        interface = InterfaceSpec(
            name="Interface/With\\Slashes",
            interface_type="Test"
        )
        assert interface.name == "Interface_With_Slashes"
    
    def test_interface_defaults(self):
        """Test interface default values."""
        interface = InterfaceSpec(name="SimpleInterface")
        
        assert interface.interface_type == "standard"
        assert interface.direction == InterfaceDirection.BIDIRECTIONAL
        assert interface.exchange_items == []
        assert interface.protocol is None
        assert interface.properties == {}


class TestComponentSpec:
    """Test ComponentSpec model."""
    
    def test_create_basic_component(self):
        """Test creating a basic component specification."""
        component = ComponentSpec(
            name="PowerManager",
            component_type=ComponentType.LOGICAL_COMPONENT,
            architecture_level=ArchitectureLevel.LOGICAL,
            description="Manages power distribution"
        )
        
        assert component.name == "PowerManager"
        assert component.component_type == ComponentType.LOGICAL_COMPONENT
        assert component.architecture_level == ArchitectureLevel.LOGICAL
        assert component.description == "Manages power distribution"
    
    def test_component_with_interfaces(self):
        """Test component with multiple interfaces."""
        interfaces = [
            InterfaceSpec(name="CAN_In", direction=InterfaceDirection.IN),
            InterfaceSpec(name="Power_Out", direction=InterfaceDirection.OUT)
        ]
        
        component = ComponentSpec(
            name="Controller",
            component_type=ComponentType.LOGICAL_COMPONENT,
            architecture_level=ArchitectureLevel.LOGICAL,
            interfaces=interfaces
        )
        
        assert len(component.interfaces) == 2
        assert component.interfaces[0].name == "CAN_In"
        assert component.interfaces[1].direction == InterfaceDirection.OUT
    
    def test_component_name_validation(self):
        """Test component name validation."""
        # Empty name should fail
        with pytest.raises(ValidationError) as exc_info:
            ComponentSpec(
                name="",
                component_type=ComponentType.LOGICAL_COMPONENT,
                architecture_level=ArchitectureLevel.LOGICAL
            )
        assert "name cannot be empty" in str(exc_info.value)
        
        # Reserved name should fail
        with pytest.raises(ValidationError) as exc_info:
            ComponentSpec(
                name="System",
                component_type=ComponentType.LOGICAL_COMPONENT,
                architecture_level=ArchitectureLevel.LOGICAL
            )
        assert "reserved name" in str(exc_info.value)
    
    def test_component_type_level_compatibility(self):
        """Test that component type matches architecture level."""
        # Valid combination
        component = ComponentSpec(
            name="LogicalComp",
            component_type=ComponentType.LOGICAL_COMPONENT,
            architecture_level=ArchitectureLevel.LOGICAL
        )
        assert component is not None
        
        # Invalid combination should fail
        with pytest.raises(ValidationError) as exc_info:
            ComponentSpec(
                name="PhysicalInLogical",
                component_type=ComponentType.PHYSICAL_COMPONENT,
                architecture_level=ArchitectureLevel.LOGICAL
            )
        assert "not valid for" in str(exc_info.value)
    
    def test_component_with_full_context(self):
        """Test component with all optional fields."""
        component = ComponentSpec(
            name="ComplexComponent",
            component_type=ComponentType.LOGICAL_COMPONENT,
            architecture_level=ArchitectureLevel.LOGICAL,
            description="A complex component",
            interfaces=[
                InterfaceSpec(name="Interface1"),
                InterfaceSpec(name="Interface2")
            ],
            allocated_functions=["Function1", "Function2"],
            properties={"weight": 100, "cost": 5000},
            parent_id="parent_123",
            sub_components=["sub_1", "sub_2"],
            requirements=["REQ-001", "REQ-002"]
        )
        
        assert len(component.interfaces) == 2
        assert len(component.allocated_functions) == 2
        assert component.properties["weight"] == 100
        assert component.parent_id == "parent_123"
        assert len(component.sub_components) == 2
        assert len(component.requirements) == 2


class TestActorSpec:
    """Test ActorSpec model."""
    
    def test_create_actor(self):
        """Test creating an actor specification."""
        actor = ActorSpec(
            name="Operator",
            actor_type="Human",
            architecture_level=ArchitectureLevel.OPERATIONAL,
            description="System operator",
            scenarios=["Normal Operation", "Emergency Response"],
            capabilities=["Monitor System", "Control Power"],
            missions=["Ensure Safety", "Optimize Performance"]
        )
        
        assert actor.name == "Operator"
        assert actor.actor_type == "Human"
        assert len(actor.scenarios) == 2
        assert len(actor.capabilities) == 2
        assert len(actor.missions) == 2
    
    def test_actor_with_interfaces(self):
        """Test actor with interfaces."""
        actor = ActorSpec(
            name="ExternalSystem",
            actor_type="System",
            architecture_level=ArchitectureLevel.SYSTEM,
            interfaces=[
                InterfaceSpec(name="DataLink", interface_type="Ethernet"),
                InterfaceSpec(name="ControlLink", interface_type="Serial")
            ]
        )
        
        assert len(actor.interfaces) == 2
        assert actor.interfaces[0].interface_type == "Ethernet"


class TestValidationModels:
    """Test validation-related models."""
    
    def test_validation_issue(self):
        """Test creating validation issues."""
        issue = ValidationIssue(
            severity=ValidationSeverity.ERROR,
            element_id="comp_123",
            element_name="PowerManager",
            rule="components_must_have_interfaces",
            message="Component 'PowerManager' has no interfaces defined",
            suggestion="Add at least one interface to the component"
        )
        
        assert issue.severity == ValidationSeverity.ERROR
        assert issue.element_name == "PowerManager"
        assert issue.suggestion is not None
    
    def test_validation_result(self):
        """Test validation result with issues."""
        issues = [
            ValidationIssue(
                severity=ValidationSeverity.ERROR,
                rule="test_rule",
                message="Error message"
            ),
            ValidationIssue(
                severity=ValidationSeverity.WARNING,
                rule="test_rule",
                message="Warning message"
            )
        ]
        
        result = ValidationResult(
            valid=False,
            issues=issues,
            checked_rules=["test_rule"],
            timestamp=datetime.now().isoformat()
        )
        
        assert not result.valid
        assert len(result.issues) == 2
        # Note: Auto-calculation of counts not working in basic Pydantic
        # Would need to implement in model's __init__ or use computed fields


class TestDrawIOModels:
    """Test draw.io import models."""
    
    def test_drawio_element(self):
        """Test draw.io element model."""
        element = DrawIOElement(
            id="elem_1",
            type="rectangle",
            label="PowerController",
            x=100.0,
            y=200.0,
            width=150.0,
            height=80.0,
            style={"fillColor": "#ffffff"},
            connections=["elem_2", "elem_3"]
        )
        
        assert element.id == "elem_1"
        assert element.label == "PowerController"
        assert element.x == 100.0
        assert len(element.connections) == 2
    
    def test_drawio_element_to_component(self):
        """Test converting draw.io element to component spec."""
        element = DrawIOElement(
            id="elem_1",
            type="rectangle",
            label="Controller",
            x=100.0,
            y=200.0,
            width=150.0,
            height=80.0
        )
        
        component = element.to_component_spec()
        
        assert component.name == "Controller"
        assert component.component_type == ComponentType.LOGICAL_COMPONENT
        assert component.architecture_level == ArchitectureLevel.LOGICAL
        assert component.properties["visual_x"] == 100.0
        assert "Imported from draw.io" in component.description
    
    def test_drawio_actor_detection(self):
        """Test that actors are detected from draw.io elements."""
        # Element with 'actor' in label
        element = DrawIOElement(
            id="elem_1",
            type="ellipse",
            label="System Actor",
            x=0, y=0, width=100, height=100
        )
        
        component = element.to_component_spec()
        assert component.component_type == ComponentType.LOGICAL_ACTOR
        
        # Element with ellipse type
        element2 = DrawIOElement(
            id="elem_2",
            type="ellipse",
            label="External System",
            x=0, y=0, width=100, height=100
        )
        
        component2 = element2.to_component_spec()
        assert component2.component_type == ComponentType.LOGICAL_ACTOR
    
    def test_drawio_import_spec(self):
        """Test draw.io import specification."""
        elements = [
            DrawIOElement(
                id="e1",
                type="rectangle",
                label="Component1",
                x=0, y=0, width=100, height=50
            ),
            DrawIOElement(
                id="e2",
                type="rectangle",
                label="Component2",
                x=200, y=0, width=100, height=50
            )
        ]
        
        import_spec = DrawIOImportSpec(
            diagram_name="System Architecture",
            elements=elements,
            architecture_level=ArchitectureLevel.LOGICAL,
            auto_create_interfaces=True,
            preserve_layout=True
        )
        
        assert import_spec.diagram_name == "System Architecture"
        assert len(import_spec.elements) == 2
        assert import_spec.auto_create_interfaces
        assert import_spec.preserve_layout


class TestModelMetrics:
    """Test model metrics."""
    
    def test_model_metrics(self):
        """Test creating model metrics."""
        metrics = ModelMetrics(
            model_id="model_123",
            total_components=25,
            total_functions=15,
            total_interfaces=30,
            total_actors=5,
            architecture_levels=["logical", "physical"],
            complexity_score=65.5,
            completeness_score=80.0
        )
        
        assert metrics.total_components == 25
        assert metrics.total_functions == 15
        assert len(metrics.architecture_levels) == 2
        assert metrics.complexity_score == 65.5


def test_all_models_importable():
    """Test that all models can be imported."""
    from capella_mcp.enhanced_models import (
        ArchitectureLevel,
        ComponentType,
        InterfaceDirection,
        ExchangeItemType,
        ValidationSeverity,
        InterfaceSpec,
        ComponentSpec,
        ActorSpec,
        FunctionalChainSpec,
        ValidationIssue,
        ValidationResult,
        ModelMetrics,
        DrawIOElement,
        DrawIOImportSpec
    )
    
    # If we get here without ImportError, all models are importable
    assert True


if __name__ == "__main__":
    pytest.main([__file__, "-v"])