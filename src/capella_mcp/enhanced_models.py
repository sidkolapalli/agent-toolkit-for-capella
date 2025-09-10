"""
Enhanced data models for Capella MCP Server.

These models are migrated from capella-mcp-fresh and provide rich
validation and structure for Capella model operations.
"""

from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field, validator


class ArchitectureLevel(str, Enum):
    """Capella architecture levels."""
    OPERATIONAL = "operational"
    SYSTEM = "system"
    LOGICAL = "logical"
    PHYSICAL = "physical"
    EPBS = "epbs"


class ComponentType(str, Enum):
    """Types of components that can be created in Capella."""
    SYSTEM_COMPONENT = "SystemComponent"
    LOGICAL_COMPONENT = "LogicalComponent"
    PHYSICAL_COMPONENT = "PhysicalComponent"
    BEHAVIORAL_COMPONENT = "BehavioralComponent"
    NODE_COMPONENT = "NodeComponent"
    ACTOR = "Actor"
    SYSTEM_ACTOR = "SystemActor"
    LOGICAL_ACTOR = "LogicalActor"
    PHYSICAL_ACTOR = "PhysicalActor"
    FUNCTION = "SystemFunction"
    LOGICAL_FUNCTION = "LogicalFunction"
    PHYSICAL_FUNCTION = "PhysicalFunction"


class InterfaceDirection(str, Enum):
    """Interface direction for component ports."""
    IN = "IN"
    OUT = "OUT"
    INOUT = "INOUT"
    PROVIDED = "provided"
    REQUIRED = "required"
    BIDIRECTIONAL = "bidirectional"


class ExchangeItemType(str, Enum):
    """Types of exchange items in interfaces."""
    DATA = "data"
    EVENT = "event"
    FLOW = "flow"
    OPERATION = "operation"
    UNSET = "unset"


class ValidationSeverity(str, Enum):
    """Severity levels for validation issues."""
    ERROR = "error"
    WARNING = "warning"
    INFO = "info"
    HINT = "hint"


class InterfaceSpec(BaseModel):
    """Specification for component interfaces with rich metadata."""
    name: str = Field(description="Interface name")
    interface_type: str = Field(
        default="standard",
        description="Type of interface (CAN, Ethernet, SPI, UART, etc.)"
    )
    direction: InterfaceDirection = Field(
        default=InterfaceDirection.BIDIRECTIONAL,
        description="Interface direction"
    )
    exchange_items: List[str] = Field(
        default_factory=list,
        description="Exchange items/data flowing through interface"
    )
    protocol: Optional[str] = Field(
        None,
        description="Communication protocol if applicable"
    )
    properties: Dict[str, Any] = Field(
        default_factory=dict,
        description="Custom properties (baudrate, frequency, etc.)"
    )
    
    @validator('name')
    def validate_name(cls, v: str) -> str:
        """Ensure interface name is valid."""
        if not v or not v.strip():
            raise ValueError("Interface name cannot be empty")
        # Remove invalid characters for Capella
        cleaned = v.strip().replace('/', '_').replace('\\', '_')
        return cleaned


class ComponentSpec(BaseModel):
    """
    Specification for creating components with full context.
    This is a coarse-grained model that includes everything needed
    to create a complete component in one operation.
    """
    name: str = Field(description="Component name")
    component_type: ComponentType = Field(
        description="Type of component to create"
    )
    architecture_level: ArchitectureLevel = Field(
        description="Architecture level where component belongs"
    )
    description: Optional[str] = Field(
        None,
        description="Component description/documentation"
    )
    interfaces: List[InterfaceSpec] = Field(
        default_factory=list,
        description="Component interfaces/ports"
    )
    allocated_functions: List[str] = Field(
        default_factory=list,
        description="Functions allocated to this component"
    )
    properties: Dict[str, Any] = Field(
        default_factory=dict,
        description="Component properties (custom attributes)"
    )
    parent_id: Optional[str] = Field(
        None,
        description="Parent component ID for hierarchical structure"
    )
    sub_components: List[str] = Field(
        default_factory=list,
        description="IDs of sub-components"
    )
    requirements: List[str] = Field(
        default_factory=list,
        description="Requirements traced to this component"
    )
    
    @validator('name')
    def validate_name(cls, v: str) -> str:
        """Ensure component name is valid for Capella."""
        if not v or not v.strip():
            raise ValueError("Component name cannot be empty")
        # Clean name for Capella
        cleaned = v.strip()
        # Check for reserved names
        reserved = ['System', 'Root', 'Model']
        if cleaned in reserved:
            raise ValueError(f"'{cleaned}' is a reserved name")
        return cleaned
    
    @validator('component_type', 'architecture_level')
    def validate_compatibility(cls, v, values):
        """Ensure component type matches architecture level."""
        if 'architecture_level' in values and 'component_type' in values:
            level = values.get('architecture_level')
            comp_type = values.get('component_type')
            
            # Validate compatibility
            if level == ArchitectureLevel.LOGICAL:
                valid_types = [
                    ComponentType.LOGICAL_COMPONENT,
                    ComponentType.LOGICAL_ACTOR,
                    ComponentType.LOGICAL_FUNCTION
                ]
                if comp_type and comp_type not in valid_types:
                    raise ValueError(
                        f"{comp_type} not valid for {level} architecture"
                    )
            elif level == ArchitectureLevel.PHYSICAL:
                valid_types = [
                    ComponentType.PHYSICAL_COMPONENT,
                    ComponentType.NODE_COMPONENT,
                    ComponentType.PHYSICAL_ACTOR,
                    ComponentType.PHYSICAL_FUNCTION
                ]
                if comp_type and comp_type not in valid_types:
                    raise ValueError(
                        f"{comp_type} not valid for {level} architecture"
                    )
        return v


class ActorSpec(BaseModel):
    """Specification for creating actors with scenarios."""
    name: str = Field(description="Actor name")
    actor_type: str = Field(
        default="Human",
        description="Type of actor (Human, System, Organization)"
    )
    architecture_level: ArchitectureLevel = Field(
        description="Architecture level"
    )
    description: Optional[str] = Field(None, description="Actor description")
    interfaces: List[InterfaceSpec] = Field(
        default_factory=list,
        description="Actor interfaces"
    )
    scenarios: List[str] = Field(
        default_factory=list,
        description="Interaction scenarios"
    )
    capabilities: List[str] = Field(
        default_factory=list,
        description="Actor capabilities"
    )
    missions: List[str] = Field(
        default_factory=list,
        description="Actor missions/goals"
    )


class FunctionalChainSpec(BaseModel):
    """Specification for creating functional chains."""
    name: str = Field(description="Chain name")
    description: Optional[str] = Field(None, description="Chain description")
    functions: List[str] = Field(
        description="Ordered list of function IDs in the chain"
    )
    exchanges: List[Dict[str, str]] = Field(
        default_factory=list,
        description="Functional exchanges between functions"
    )
    precondition: Optional[str] = Field(
        None,
        description="Precondition for chain execution"
    )
    postcondition: Optional[str] = Field(
        None,
        description="Postcondition after chain execution"
    )


class ValidationIssue(BaseModel):
    """Represents a validation issue found in the model."""
    severity: ValidationSeverity = Field(description="Issue severity")
    element_id: Optional[str] = Field(None, description="Affected element ID")
    element_name: Optional[str] = Field(None, description="Affected element name")
    rule: str = Field(description="Validation rule that failed")
    message: str = Field(description="Human-readable error message")
    suggestion: Optional[str] = Field(
        None,
        description="Suggested fix for the issue"
    )


class ValidationResult(BaseModel):
    """Result of model validation."""
    valid: bool = Field(description="Whether model is valid")
    issues: List[ValidationIssue] = Field(
        default_factory=list,
        description="List of validation issues"
    )
    warnings_count: int = Field(default=0, description="Number of warnings")
    errors_count: int = Field(default=0, description="Number of errors")
    checked_rules: List[str] = Field(
        default_factory=list,
        description="Rules that were checked"
    )
    timestamp: str = Field(description="Validation timestamp")
    
    @validator('errors_count', 'warnings_count')
    def count_issues(cls, v, values):
        """Auto-calculate issue counts."""
        if 'issues' in values:
            issues = values['issues']
            errors = sum(1 for i in issues if i.severity == ValidationSeverity.ERROR)
            warnings = sum(1 for i in issues if i.severity == ValidationSeverity.WARNING)
            
            if 'errors_count' in values:
                return errors
            if 'warnings_count' in values:
                return warnings
        return v


class ModelMetrics(BaseModel):
    """Metrics and statistics about a Capella model."""
    model_id: str = Field(description="Model identifier")
    total_components: int = Field(default=0, description="Total component count")
    total_functions: int = Field(default=0, description="Total function count")
    total_interfaces: int = Field(default=0, description="Total interface count")
    total_actors: int = Field(default=0, description="Total actor count")
    architecture_levels: List[str] = Field(
        default_factory=list,
        description="Architecture levels present"
    )
    complexity_score: Optional[float] = Field(
        None,
        description="Model complexity score (0-100)"
    )
    completeness_score: Optional[float] = Field(
        None,
        description="Model completeness score (0-100)"
    )


class DrawIOElement(BaseModel):
    """Represents an element from a draw.io diagram for import."""
    id: str = Field(description="Element ID from draw.io")
    type: str = Field(description="Element type (rectangle, ellipse, arrow, etc.)")
    label: str = Field(description="Element label/text")
    x: float = Field(description="X coordinate")
    y: float = Field(description="Y coordinate")
    width: float = Field(description="Element width")
    height: float = Field(description="Element height")
    style: Dict[str, Any] = Field(
        default_factory=dict,
        description="Style properties"
    )
    connections: List[str] = Field(
        default_factory=list,
        description="IDs of connected elements"
    )
    
    def to_component_spec(self) -> ComponentSpec:
        """Convert draw.io element to component specification."""
        # Infer component type from visual style
        component_type = ComponentType.LOGICAL_COMPONENT
        if 'actor' in self.label.lower() or self.type == 'ellipse':
            component_type = ComponentType.LOGICAL_ACTOR
        elif 'function' in self.label.lower():
            component_type = ComponentType.LOGICAL_FUNCTION
        
        return ComponentSpec(
            name=self.label,
            component_type=component_type,
            architecture_level=ArchitectureLevel.LOGICAL,
            description=f"Imported from draw.io element {self.id}",
            properties={
                'visual_x': self.x,
                'visual_y': self.y,
                'visual_width': self.width,
                'visual_height': self.height
            }
        )


class DrawIOImportSpec(BaseModel):
    """Specification for importing draw.io diagrams."""
    diagram_name: str = Field(description="Name for imported diagram")
    elements: List[DrawIOElement] = Field(
        description="Elements from draw.io"
    )
    architecture_level: ArchitectureLevel = Field(
        default=ArchitectureLevel.LOGICAL,
        description="Target architecture level"
    )
    auto_create_interfaces: bool = Field(
        default=True,
        description="Automatically create interfaces from connections"
    )
    preserve_layout: bool = Field(
        default=True,
        description="Try to preserve visual layout"
    )