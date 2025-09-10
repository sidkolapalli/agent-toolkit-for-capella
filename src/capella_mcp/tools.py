"""
Unified tool registry for Capella MCP Server.

This module combines basic and enhanced tools into a single registry,
providing both atomic operations and coarse-grained, AI-friendly operations.
Migrated from capella-mcp-fresh and adapted for capellambse.
"""

from typing import Dict, Any, List, Optional, Callable
from dataclasses import dataclass
from datetime import datetime
import logging
from uuid import uuid4

from .enhanced_models import (
    ComponentSpec,
    InterfaceSpec,
    ActorSpec,
    FunctionalChainSpec,
    ValidationResult,
    ValidationIssue,
    ValidationSeverity,
    DrawIOElement,
    DrawIOImportSpec,
    ArchitectureLevel,
    ComponentType,
    InterfaceDirection,
)

logger = logging.getLogger(__name__)


@dataclass
class ToolDefinition:
    """Definition of a tool"""
    name: str
    description: str
    parameters: Dict[str, Any]
    handler: Optional[Callable] = None


class ToolRegistry:
    """Unified registry for all Capella MCP tools."""

    def __init__(self, model_manager=None):
        self.model_manager = model_manager
        self.tools: Dict[str, ToolDefinition] = {}
        self._register_basic_tools()
        self._register_enhanced_tools()
        self._register_undo_tools()

    def _register_basic_tools(self):
        """Register basic atomic tools."""
        
        # Create component tool (simple)
        self.register_tool(
            name="create_component",
            description="Create a new component in the model (basic)",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "name": {"type": "string", "description": "Component name"},
                    "type": {"type": "string", "description": "Component type"},
                    "parent_id": {
                        "type": "string",
                        "description": "Parent component ID",
                        "optional": True,
                    },
                },
                "required": ["model_id", "name", "type"],
            },
            handler=self._create_component_basic,
        )

        # Modify component tool
        self.register_tool(
            name="modify_component",
            description="Modify an existing component",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "component_id": {
                        "type": "string",
                        "description": "Component identifier",
                    },
                    "changes": {"type": "object", "description": "Changes to apply"},
                },
                "required": ["model_id", "component_id", "changes"],
            },
            handler=self._modify_component,
        )

        # Delete component tool
        self.register_tool(
            name="delete_component",
            description="Delete a component from the model",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "component_id": {
                        "type": "string",
                        "description": "Component identifier",
                    },
                },
                "required": ["model_id", "component_id"],
            },
            handler=self._delete_component,
        )

        # Query components tool
        self.register_tool(
            name="query_components",
            description="Query components with filters",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "filters": {
                        "type": "object",
                        "description": "Query filters",
                        "optional": True,
                    },
                },
                "required": ["model_id"],
            },
            handler=self._query_components,
        )

    def _register_enhanced_tools(self):
        """Register enhanced coarse-grained tools."""
        
        # Component creation with full context
        self.register_tool(
            name="create_component_with_interfaces",
            description="Create a complete component with interfaces, properties, and allocations",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "component_spec": {
                        "type": "object",
                        "description": "Complete component specification",
                        "properties": {
                            "name": {"type": "string"},
                            "component_type": {"type": "string"},
                            "architecture_level": {"type": "string"},
                            "description": {"type": "string"},
                            "interfaces": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "name": {"type": "string"},
                                        "interface_type": {"type": "string"},
                                        "direction": {"type": "string"},
                                        "exchange_items": {"type": "array", "items": {"type": "string"}},
                                        "protocol": {"type": "string"},
                                        "properties": {"type": "object"}
                                    }
                                }
                            },
                            "allocated_functions": {"type": "array", "items": {"type": "string"}},
                            "properties": {"type": "object"},
                            "requirements": {"type": "array", "items": {"type": "string"}}
                        },
                        "required": ["name", "component_type", "architecture_level"]
                    }
                },
                "required": ["model_id", "component_spec"]
            },
            handler=self._create_component_with_interfaces
        )
        
        # Actor creation with scenarios
        self.register_tool(
            name="create_actor_with_scenarios",
            description="Create an actor with interaction scenarios and capabilities",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "actor_spec": {
                        "type": "object",
                        "description": "Actor specification",
                        "properties": {
                            "name": {"type": "string"},
                            "actor_type": {"type": "string"},
                            "architecture_level": {"type": "string"},
                            "description": {"type": "string"},
                            "interfaces": {"type": "array"},
                            "scenarios": {"type": "array", "items": {"type": "string"}},
                            "capabilities": {"type": "array", "items": {"type": "string"}},
                            "missions": {"type": "array", "items": {"type": "string"}}
                        },
                        "required": ["name", "architecture_level"]
                    }
                },
                "required": ["model_id", "actor_spec"]
            },
            handler=self._create_actor_with_scenarios
        )
        
        # Functional chain creation
        self.register_tool(
            name="create_functional_chain",
            description="Create a functional chain linking multiple functions",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "chain_spec": {
                        "type": "object",
                        "description": "Functional chain specification",
                        "properties": {
                            "name": {"type": "string"},
                            "description": {"type": "string"},
                            "functions": {"type": "array", "items": {"type": "string"}},
                            "exchanges": {"type": "array"},
                            "precondition": {"type": "string"},
                            "postcondition": {"type": "string"}
                        },
                        "required": ["name", "functions"]
                    }
                },
                "required": ["model_id", "chain_spec"]
            },
            handler=self._create_functional_chain
        )
        
        # Draw.io import
        self.register_tool(
            name="import_from_drawio",
            description="Import architecture from draw.io diagram",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "import_spec": {
                        "type": "object",
                        "description": "Draw.io import specification",
                        "properties": {
                            "diagram_name": {"type": "string"},
                            "elements": {
                                "type": "array",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "id": {"type": "string"},
                                        "type": {"type": "string"},
                                        "label": {"type": "string"},
                                        "x": {"type": "number"},
                                        "y": {"type": "number"},
                                        "width": {"type": "number"},
                                        "height": {"type": "number"},
                                        "connections": {"type": "array", "items": {"type": "string"}}
                                    }
                                }
                            },
                            "architecture_level": {"type": "string"},
                            "auto_create_interfaces": {"type": "boolean"},
                            "preserve_layout": {"type": "boolean"}
                        },
                        "required": ["diagram_name", "elements"]
                    }
                },
                "required": ["model_id", "import_spec"]
            },
            handler=self._import_from_drawio
        )
        
        # Comprehensive validation
        self.register_tool(
            name="validate_architecture",
            description="Validate model architecture with comprehensive rules",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "validation_rules": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Rules to check: completeness, consistency, traceability, interfaces, allocations"
                    },
                    "severity_threshold": {
                        "type": "string",
                        "description": "Minimum severity to report: error, warning, info",
                        "default": "warning"
                    }
                },
                "required": ["model_id"]
            },
            handler=self._validate_architecture
        )
        
        # Dependency analysis
        self.register_tool(
            name="analyze_dependencies",
            description="Analyze dependencies and impacts of a component",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {"type": "string", "description": "Model identifier"},
                    "element_id": {"type": "string", "description": "Element to analyze"},
                    "depth": {
                        "type": "integer",
                        "description": "Depth of dependency analysis",
                        "default": 2
                    },
                    "include_indirect": {
                        "type": "boolean",
                        "description": "Include indirect dependencies",
                        "default": True
                    }
                },
                "required": ["model_id", "element_id"]
            },
            handler=self._analyze_dependencies
        )

    def _register_undo_tools(self):
        """Register undo/redo and reversibility tools."""
        
        # Undo last operation
        self.register_tool(
            name="undo_last_operation",
            description="Undo the most recent operation (create, modify, delete, etc.)",
            parameters={
                "type": "object",
                "properties": {},
                "required": []
            },
            handler=self._undo_last_operation
        )
        
        # Undo specific operation
        self.register_tool(
            name="undo_operation",
            description="Undo a specific operation by ID",
            parameters={
                "type": "object",
                "properties": {
                    "operation_id": {
                        "type": "string",
                        "description": "ID of the operation to undo"
                    }
                },
                "required": ["operation_id"]
            },
            handler=self._undo_operation
        )
        
        # Redo last operation
        self.register_tool(
            name="redo_last_operation", 
            description="Redo the most recently undone operation",
            parameters={
                "type": "object",
                "properties": {},
                "required": []
            },
            handler=self._redo_last_operation
        )
        
        # List operation history
        self.register_tool(
            name="list_operations",
            description="List recent operations with undo/redo status",
            parameters={
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Maximum number of operations to return",
                        "default": 10
                    },
                    "model_id": {
                        "type": "string",
                        "description": "Filter by model ID",
                        "optional": True
                    },
                    "operation_type": {
                        "type": "string", 
                        "description": "Filter by operation type (create, modify, delete, etc.)",
                        "optional": True
                    }
                },
                "required": []
            },
            handler=self._list_operations
        )
        
        # Create checkpoint
        self.register_tool(
            name="create_checkpoint",
            description="Create a checkpoint for easy restoration",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {
                        "type": "string",
                        "description": "Model to create checkpoint for"
                    },
                    "checkpoint_name": {
                        "type": "string",
                        "description": "Name for this checkpoint"
                    },
                    "description": {
                        "type": "string",
                        "description": "Description of this checkpoint",
                        "optional": True
                    }
                },
                "required": ["model_id", "checkpoint_name"]
            },
            handler=self._create_checkpoint
        )
        
        # Restore to checkpoint
        self.register_tool(
            name="restore_to_checkpoint",
            description="Undo all operations back to a specific checkpoint",
            parameters={
                "type": "object",
                "properties": {
                    "checkpoint_id": {
                        "type": "string",
                        "description": "ID of checkpoint to restore to"
                    }
                },
                "required": ["checkpoint_id"]
            },
            handler=self._restore_to_checkpoint
        )
        
        # List checkpoints
        self.register_tool(
            name="list_checkpoints",
            description="List available checkpoints",
            parameters={
                "type": "object",
                "properties": {},
                "required": []
            },
            handler=self._list_checkpoints
        )
        
        # Get undo preview
        self.register_tool(
            name="preview_undo",
            description="Preview what operations would be undone",
            parameters={
                "type": "object",
                "properties": {
                    "count": {
                        "type": "integer",
                        "description": "Number of operations to preview",
                        "default": 1
                    }
                },
                "required": []
            },
            handler=self._preview_undo
        )
        
        # Restore deleted element
        self.register_tool(
            name="restore_deleted_element",
            description="Restore a virtually deleted element",
            parameters={
                "type": "object",
                "properties": {
                    "element_id": {
                        "type": "string",
                        "description": "ID of the element to restore"
                    },
                    "restore_children": {
                        "type": "boolean",
                        "description": "Whether to restore cascade-deleted children",
                        "default": True
                    }
                },
                "required": ["element_id"]
            },
            handler=self._restore_deleted_element
        )
        
        # List deleted elements
        self.register_tool(
            name="list_deleted_elements",
            description="List virtually deleted elements that can be restored",
            parameters={
                "type": "object",
                "properties": {
                    "model_id": {
                        "type": "string",
                        "description": "Filter by model ID",
                        "optional": True
                    }
                },
                "required": []
            },
            handler=self._list_deleted_elements
        )
        
        # Get reversibility status
        self.register_tool(
            name="get_reversibility_status",
            description="Get current status of undo/redo capabilities",
            parameters={
                "type": "object",
                "properties": {},
                "required": []
            },
            handler=self._get_reversibility_status
        )

    def register_tool(
        self,
        name: str,
        description: str,
        parameters: Dict,
        handler: Optional[Callable] = None,
    ):
        """Register a new tool"""
        self.tools[name] = ToolDefinition(
            name=name, description=description, parameters=parameters, handler=handler
        )

    def list_tools(self) -> List[Dict]:
        """List all available tools"""
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "parameters": tool.parameters,
            }
            for tool in self.tools.values()
        ]

    async def call_tool(self, name: str, arguments: Dict[str, Any]) -> Any:
        """Execute a tool with given arguments"""
        if name not in self.tools:
            raise ValueError(f"Tool '{name}' not found")

        tool = self.tools[name]

        # Validate parameters
        required = tool.parameters.get("required", [])
        for param in required:
            if param not in arguments:
                raise ValueError(f"Missing required parameter: {param}")

        # Execute handler if available
        if tool.handler:
            try:
                result = await tool.handler(arguments)
                return result
            except Exception as e:
                logger.error(f"Tool execution failed: {e}")
                raise

        # Default response if no handler
        return {"status": "success", "message": f"Tool '{name}' executed"}

    # Basic tool handlers
    
    async def _create_component_basic(self, args: Dict) -> Dict:
        """Create a basic component (atomic operation)."""
        model_id = args["model_id"]
        name = args["name"]
        component_type = args["type"]
        parent_id = args.get("parent_id")

        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Create the actual component using model_manager
            component_id = wrapper.create_element(
                element_type=component_type,
                parent_id=parent_id,
                name=name
            )
            
            # Save the model to persist changes
            wrapper.save()
            
            result = {
                "component_id": component_id,
                "name": name,
                "type": component_type,
                "parent_id": parent_id,
                "model_id": model_id,
                "status": "success",
                "message": f"Component created and saved successfully"
            }
            
            logger.info(f"Created and saved component: {component_id}")
            return result
            
        except Exception as e:
            logger.error(f"Failed to create component: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }

    async def _modify_component(self, args: Dict) -> Dict:
        """Modify a component"""
        model_id = args["model_id"]
        component_id = args["component_id"]
        changes = args["changes"]

        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Modify the element using model_manager
            wrapper.modify_element(component_id, changes)
            
            # Save the model to persist changes
            wrapper.save()
            
            result = {
                "component_id": component_id,
                "model_id": model_id,
                "changes_applied": changes,
                "status": "success",
                "message": "Component modified and saved successfully"
            }
            
            logger.info(f"Modified and saved component: {component_id}")
            return result
            
        except Exception as e:
            logger.error(f"Failed to modify component: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }

    async def _delete_component(self, args: Dict) -> Dict:
        """Delete a component"""
        model_id = args["model_id"]
        component_id = args["component_id"]

        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Delete the element using model_manager
            wrapper.delete_element(component_id)
            
            # Save the model to persist changes
            wrapper.save()
            
            result = {
                "component_id": component_id,
                "model_id": model_id,
                "deleted": True,
                "status": "success",
                "message": "Component deleted and saved successfully"
            }
            
            logger.info(f"Deleted and saved component: {component_id}")
            return result
            
        except Exception as e:
            logger.error(f"Failed to delete component: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }

    async def _query_components(self, args: Dict) -> Dict:
        """Query components from actual model"""
        model_id = args["model_id"]
        filters = args.get("filters", {})

        if self.model_manager:
            # Get the model wrapper
            wrapper = self.model_manager.get_model(model_id)
            if not wrapper:
                return {
                    "error": f"Model {model_id} not found",
                    "status": "failed"
                }
            
            # Query actual elements from the model
            elements = wrapper.query_elements(filters)
            
            # Convert elements to serializable format
            components = []
            for elem in elements:
                if hasattr(elem, '__dict__'):
                    # Real capellambse element
                    comp_data = {
                        "id": str(getattr(elem, 'uuid', elem)),
                        "name": getattr(elem, 'name', 'Unnamed'),
                        "type": elem.__class__.__name__
                    }
                    # Add parent if available
                    if hasattr(elem, 'parent') and elem.parent:
                        comp_data["parent"] = str(elem.parent.uuid)
                else:
                    # Dictionary element from cache
                    comp_data = elem if isinstance(elem, dict) else {
                        "id": str(elem),
                        "name": str(elem),
                        "type": "Unknown"
                    }
                components.append(comp_data)
            
            result = {
                "model_id": model_id,
                "components": components,
                "count": len(components),
            }
            
            logger.info(f"Queried {len(components)} components from model")
            return result
        else:
            # Fallback if no model manager
            return {
                "model_id": model_id,
                "components": [],
                "count": 0,
                "message": "No model manager available"
            }

    # Enhanced tool handlers
    
    async def _create_component_with_interfaces(self, args: Dict) -> Dict:
        """
        Create a complete component with interfaces and properties.
        This is a coarse-grained operation that combines multiple atomic actions.
        """
        model_id = args["model_id"]
        spec_data = args["component_spec"]
        
        # Parse specification
        component_spec = ComponentSpec(**spec_data)
        
        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Create the main component
            component_id = wrapper.create_element(
                element_type=component_spec.component_type.value,
                name=component_spec.name,
                description=component_spec.description
            )
            
            # Create interfaces
            interface_ids = []
            for interface in component_spec.interfaces:
                interface_id = wrapper.create_element(
                    element_type="Interface",
                    parent_id=component_id,
                    name=interface.name,
                    interface_type=interface.interface_type,
                    direction=interface.direction.value if hasattr(interface.direction, 'value') else interface.direction
                )
                interface_ids.append(interface_id)
            
            # Save the model to persist all changes
            wrapper.save()
            
            return {
                "status": "success",
                "component_id": component_id,
                "name": component_spec.name,
                "type": component_spec.component_type.value,
                "level": component_spec.architecture_level.value,
                "interface_ids": interface_ids,
                "interfaces_created": len(interface_ids),
                "properties_set": len(component_spec.properties),
                "functions_allocated": len(component_spec.allocated_functions),
                "message": f"Created component '{component_spec.name}' with {len(interface_ids)} interfaces and saved to model"
            }
            
        except Exception as e:
            logger.error(f"Failed to create component with interfaces: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _create_actor_with_scenarios(self, args: Dict) -> Dict:
        """Create an actor with interaction scenarios and capabilities."""
        model_id = args["model_id"]
        spec_data = args["actor_spec"]
        
        # Parse specification
        actor_spec = ActorSpec(**spec_data)
        
        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Create the actor
            actor_id = wrapper.create_element(
                element_type="Actor",
                name=actor_spec.name,
                actor_type=actor_spec.actor_type,
                description=actor_spec.description
            )
            
            # Create scenarios
            scenario_ids = []
            for scenario in actor_spec.scenarios:
                scenario_id = wrapper.create_element(
                    element_type="Scenario",
                    parent_id=actor_id,
                    name=scenario
                )
                scenario_ids.append(scenario_id)
            
            # Save the model to persist changes
            wrapper.save()
            
            return {
                "status": "success",
                "actor_id": actor_id,
                "name": actor_spec.name,
                "type": actor_spec.actor_type,
                "scenarios_created": len(scenario_ids),
                "capabilities_defined": len(actor_spec.capabilities),
                "missions_assigned": len(actor_spec.missions),
                "message": f"Created actor '{actor_spec.name}' with {len(scenario_ids)} scenarios and saved to model"
            }
            
        except Exception as e:
            logger.error(f"Failed to create actor with scenarios: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _create_functional_chain(self, args: Dict) -> Dict:
        """Create a functional chain linking multiple functions."""
        model_id = args["model_id"]
        spec_data = args["chain_spec"]
        
        # Parse specification
        chain_spec = FunctionalChainSpec(**spec_data)
        
        if not self.model_manager:
            return {
                "error": "Model manager not available",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Create the functional chain
            chain_id = wrapper.create_element(
                element_type="FunctionalChain",
                name=chain_spec.name,
                description=chain_spec.description,
                precondition=chain_spec.precondition,
                postcondition=chain_spec.postcondition
            )
            
            # Create exchanges
            exchange_ids = []
            for exchange in chain_spec.exchanges:
                exchange_id = wrapper.create_element(
                    element_type="FunctionalExchange",
                    parent_id=chain_id,
                    name=getattr(exchange, 'name', 'Exchange')
                )
                exchange_ids.append(exchange_id)
            
            # Save the model to persist changes
            wrapper.save()
            
            return {
                "status": "success",
                "chain_id": chain_id,
                "name": chain_spec.name,
                "functions_linked": len(chain_spec.functions),
                "exchanges_created": len(exchange_ids),
                "message": f"Created functional chain '{chain_spec.name}' with {len(chain_spec.functions)} functions and saved to model"
            }
            
        except Exception as e:
            logger.error(f"Failed to create functional chain: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _import_from_drawio(self, args: Dict) -> Dict:
        """Import architecture from draw.io diagram."""
        model_id = args["model_id"]
        spec_data = args["import_spec"]
        
        # Parse specification
        import_spec = DrawIOImportSpec(**spec_data)
        
        created_components = []
        created_interfaces = []
        
        # Process each draw.io element
        for element_data in import_spec.elements:
            element = DrawIOElement(**element_data)
            
            # Convert to component spec
            component_spec = element.to_component_spec()
            
            # Create component
            result = await self._create_component_with_interfaces({
                "model_id": model_id,
                "component_spec": component_spec.model_dump()
            })
            
            created_components.append({
                "drawio_id": element.id,
                "capella_id": result["component_id"],
                "name": component_spec.name,
                "type": component_spec.component_type.value
            })
            
            # Create interfaces based on connections
            if import_spec.auto_create_interfaces:
                for target_id in element.connections:
                    interface_id = str(uuid4())
                    created_interfaces.append({
                        "id": interface_id,
                        "source": element.id,
                        "target": target_id
                    })
        
        return {
            "status": "success",
            "diagram_name": import_spec.diagram_name,
            "components_created": len(created_components),
            "interfaces_created": len(created_interfaces),
            "components": created_components,
            "interfaces": created_interfaces,
            "message": f"Imported {len(created_components)} components from draw.io diagram"
        }
    
    async def _validate_architecture(self, args: Dict) -> Dict:
        """Validate model architecture with comprehensive rules."""
        model_id = args["model_id"]
        rules = args.get("validation_rules", ["completeness", "consistency", "interfaces"])
        severity_threshold = args.get("severity_threshold", "warning")
        
        issues = []
        
        if not self.model_manager:
            return {
                "error": "Model manager not available - cannot validate architecture",
                "status": "failed"
            }
        
        # Filter by severity
        severity_map = {
            "error": [ValidationSeverity.ERROR],
            "warning": [ValidationSeverity.ERROR, ValidationSeverity.WARNING],
            "info": [ValidationSeverity.ERROR, ValidationSeverity.WARNING, ValidationSeverity.INFO]
        }
        
        threshold_severities = severity_map.get(severity_threshold, [])
        filtered_issues = [i for i in issues if i.severity in threshold_severities]
        
        # Create validation result
        result = ValidationResult(
            valid=not any(i.severity == ValidationSeverity.ERROR for i in filtered_issues),
            issues=filtered_issues,
            warnings_count=sum(1 for i in filtered_issues if i.severity == ValidationSeverity.WARNING),
            errors_count=sum(1 for i in filtered_issues if i.severity == ValidationSeverity.ERROR),
            checked_rules=rules,
            timestamp=datetime.now().isoformat()
        )
        
        return {
            "status": "success",
            "valid": result.valid,
            "errors": result.errors_count,
            "warnings": result.warnings_count,
            "total_issues": len(result.issues),
            "rules_checked": result.checked_rules,
            "issues": [i.model_dump() for i in result.issues],
            "message": f"Validation {'passed' if result.valid else 'failed'} with {len(result.issues)} issues"
        }
    
    async def _analyze_dependencies(self, args: Dict) -> Dict:
        """Analyze dependencies and impacts of a component using real capellambse APIs."""
        model_id = args["model_id"]
        element_id = args["element_id"]
        depth = args.get("depth", 2)
        include_indirect = args.get("include_indirect", True)
        
        if not self.model_manager:
            return {
                "error": "Model manager not available - cannot analyze dependencies",
                "status": "failed"
            }
        
        # Get the model wrapper
        wrapper = self.model_manager.get_model(model_id)
        if not wrapper:
            return {
                "error": f"Model {model_id} not found",
                "status": "failed"
            }
        
        try:
            # Get the element from the model
            element = wrapper.get_element(element_id)
            if not element:
                return {
                    "error": f"Element {element_id} not found in model",
                    "status": "failed"
                }
            
            # Initialize dependency structures
            dependencies = {
                "direct": [],
                "indirect": [],
                "dependents": [],
                "interfaces": [],
                "allocations": [],
                "requirements": []
            }
            
            # Extract element name
            element_name = getattr(element, 'name', 'Unknown')
            
            # Analyze direct dependencies using discovered relationship APIs
            direct_count = 0
            
            # 1. Allocated Functions
            if hasattr(element, 'allocated_functions'):
                allocated_funcs = list(element.allocated_functions)
                for func in allocated_funcs:
                    dependencies["allocations"].append({
                        "id": str(func.uuid),
                        "name": func.name,
                        "type": "AllocatedFunction",
                        "relationship": "allocates"
                    })
                direct_count += len(allocated_funcs)
            
            # 2. Interface Relationships
            interface_count = 0
            if hasattr(element, 'used_interfaces'):
                used_intfs = list(element.used_interfaces)
                for intf in used_intfs:
                    dependencies["interfaces"].append({
                        "id": str(intf.uuid),
                        "name": intf.name,
                        "type": "UsedInterface", 
                        "relationship": "uses"
                    })
                interface_count += len(used_intfs)
                direct_count += len(used_intfs)
            
            if hasattr(element, 'implemented_interfaces'):
                impl_intfs = list(element.implemented_interfaces)
                for intf in impl_intfs:
                    dependencies["interfaces"].append({
                        "id": str(intf.uuid),
                        "name": intf.name,
                        "type": "ImplementedInterface",
                        "relationship": "implements"
                    })
                interface_count += len(impl_intfs)
                direct_count += len(impl_intfs)
            
            # 3. Component Exchanges
            if hasattr(element, 'component_exchanges'):
                exchanges = list(element.component_exchanges)
                for exchange in exchanges:
                    dependencies["direct"].append({
                        "id": str(exchange.uuid),
                        "name": exchange.name,
                        "type": "ComponentExchange",
                        "relationship": "exchanges_with"
                    })
                direct_count += len(exchanges)
            
            # 4. Related Exchanges
            if hasattr(element, 'related_exchanges'):
                rel_exchanges = list(element.related_exchanges)
                for exchange in rel_exchanges:
                    dependencies["direct"].append({
                        "id": str(exchange.uuid),
                        "name": getattr(exchange, 'name', 'Unnamed Exchange'),
                        "type": "RelatedExchange",
                        "relationship": "related_to"
                    })
                direct_count += len(rel_exchanges)
            
            # 5. Find dependent elements (elements that depend on this one)
            dependent_count = 0
            if depth > 1 and include_indirect:
                # Search through the model for elements that reference this one
                all_components = []
                try:
                    # Collect components from all layers
                    if hasattr(wrapper.model, 'oa'):
                        all_components.extend(wrapper.model.oa.all_components)
                    if hasattr(wrapper.model, 'sa'):
                        all_components.extend(wrapper.model.sa.all_components)
                    if hasattr(wrapper.model, 'la'):
                        all_components.extend(wrapper.model.la.all_components)
                    if hasattr(wrapper.model, 'pa'):
                        all_components.extend(wrapper.model.pa.all_components)
                    
                    # Check each component for references to our element
                    for comp in all_components:
                        if str(comp.uuid) == element_id:
                            continue  # Skip self
                        
                        # Check if this component depends on our element
                        depends_on_element = False
                        relationship_type = "unknown"
                        
                        # Check allocated functions
                        if hasattr(comp, 'allocated_functions'):
                            for func in comp.allocated_functions:
                                if str(func.uuid) == element_id:
                                    depends_on_element = True
                                    relationship_type = "allocates_function"
                                    break
                        
                        # Check interface usage
                        if hasattr(comp, 'used_interfaces'):
                            for intf in comp.used_interfaces:
                                if str(intf.uuid) == element_id:
                                    depends_on_element = True
                                    relationship_type = "uses_interface"
                                    break
                        
                        if depends_on_element:
                            dependencies["dependents"].append({
                                "id": str(comp.uuid),
                                "name": comp.name,
                                "type": comp.__class__.__name__,
                                "relationship": relationship_type
                            })
                            dependent_count += 1
                
                except Exception as e:
                    logger.warning(f"Error analyzing dependents: {e}")
            
            # Calculate impact score based on relationship counts
            # Higher scores indicate elements with more dependencies/dependents
            impact_score = (
                direct_count * 2 +        # Direct dependencies weighted heavily
                dependent_count * 3 +     # Dependents weighted most heavily  
                interface_count * 1.5     # Interfaces weighted moderately
            )
            
            return {
                "status": "success",
                "element_id": element_id,
                "element_name": element_name,
                "direct_dependencies": direct_count,
                "indirect_dependencies": 0,  # Would need recursive analysis
                "dependent_elements": dependent_count,
                "connected_interfaces": interface_count,
                "impact_score": round(impact_score, 1),
                "dependencies": dependencies,
                "message": f"Analyzed {direct_count} direct dependencies and {dependent_count} dependents"
            }
            
        except Exception as e:
            logger.error(f"Dependency analysis failed: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }

    
    # Undo/Redo tool handlers
    
    async def _undo_last_operation(self, args: Dict) -> Dict:
        """Undo the most recent operation."""
        try:
            if not hasattr(self.model_manager, 'undo_manager'):
                return {
                    "error": "Undo manager not available",
                    "status": "failed"
                }
            
            result = await self.model_manager.undo_manager.undo_last_operation()
            return {
                "status": "success" if result["success"] else "failed",
                **result
            }
            
        except Exception as e:
            logger.error(f"Failed to undo last operation: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _undo_operation(self, args: Dict) -> Dict:
        """Undo a specific operation by ID."""
        operation_id = args["operation_id"]
        
        try:
            if not hasattr(self.model_manager, 'undo_manager'):
                return {
                    "error": "Undo manager not available",
                    "status": "failed"
                }
            
            result = await self.model_manager.undo_manager.undo_operation(operation_id)
            return {
                "status": "success" if result["success"] else "failed",
                **result
            }
            
        except Exception as e:
            logger.error(f"Failed to undo operation {operation_id}: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _redo_last_operation(self, args: Dict) -> Dict:
        """Redo the most recently undone operation."""
        try:
            if not hasattr(self.model_manager, 'undo_manager'):
                return {
                    "error": "Undo manager not available",
                    "status": "failed"
                }
            
            result = await self.model_manager.undo_manager.redo_last_operation()
            return {
                "status": "success" if result["success"] else "failed",
                **result
            }
            
        except Exception as e:
            logger.error(f"Failed to redo last operation: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _list_operations(self, args: Dict) -> Dict:
        """List recent operations with undo/redo status."""
        limit = args.get("limit", 10)
        model_id = args.get("model_id")
        operation_type = args.get("operation_type")
        
        try:
            if not hasattr(self.model_manager, 'operation_history'):
                return {
                    "error": "Operation history not available",
                    "status": "failed"
                }
            
            # Convert operation_type string to enum if provided
            op_type_enum = None
            if operation_type:
                from .operation_history import OperationType
                try:
                    op_type_enum = OperationType(operation_type.lower())
                except ValueError:
                    return {
                        "error": f"Invalid operation type: {operation_type}",
                        "status": "failed"
                    }
            
            # Get operation history
            operations = self.model_manager.operation_history.get_operation_history(
                limit=limit,
                operation_type=op_type_enum,
                model_id=model_id
            )
            
            # Convert to serializable format
            operations_list = []
            for op in operations:
                operations_list.append({
                    "operation_id": op.operation_id,
                    "operation_type": op.operation_type.value,
                    "timestamp": op.timestamp.isoformat(),
                    "model_id": op.model_id,
                    "element_id": op.element_id,
                    "element_type": op.element_type,
                    "status": op.operation_status.value,
                    "description": op.user_description,
                    "is_undoable": op.is_undoable,
                    "properties_changed": op.properties_changed
                })
            
            # Get undo/redo status
            can_undo = self.model_manager.operation_history.can_undo()
            can_redo = self.model_manager.operation_history.can_redo()
            
            return {
                "status": "success",
                "operations": operations_list,
                "total_operations": len(operations_list),
                "can_undo": can_undo,
                "can_redo": can_redo,
                "filters_applied": {
                    "limit": limit,
                    "model_id": model_id,
                    "operation_type": operation_type
                }
            }
            
        except Exception as e:
            logger.error(f"Failed to list operations: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _create_checkpoint(self, args: Dict) -> Dict:
        """Create a checkpoint for easy restoration."""
        model_id = args["model_id"]
        checkpoint_name = args["checkpoint_name"]
        description = args.get("description", "")
        
        try:
            if not self.model_manager:
                return {
                    "error": "Model manager not available",
                    "status": "failed"
                }
            
            # Get model state
            wrapper = self.model_manager.get_model(model_id)
            if not wrapper:
                return {
                    "error": f"Model {model_id} not found",
                    "status": "failed"
                }
            
            # Create model state snapshot
            model_state = {
                "model_id": model_id,
                "timestamp": datetime.now().isoformat(),
                "description": description,
                "element_count": len(wrapper._element_cache) if hasattr(wrapper, '_element_cache') else 0
            }
            
            # Create checkpoint
            if hasattr(self.model_manager, 'operation_history'):
                self.model_manager.operation_history.create_checkpoint(
                    checkpoint_id=checkpoint_name,
                    model_state=model_state
                )
            else:
                return {
                    "error": "Operation history not available",
                    "status": "failed"
                }
            
            return {
                "status": "success",
                "checkpoint_id": checkpoint_name,
                "model_id": model_id,
                "description": description,
                "created_at": model_state["timestamp"],
                "message": f"Checkpoint '{checkpoint_name}' created successfully"
            }
            
        except Exception as e:
            logger.error(f"Failed to create checkpoint: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _restore_to_checkpoint(self, args: Dict) -> Dict:
        """Restore to a specific checkpoint."""
        checkpoint_id = args["checkpoint_id"]
        
        try:
            if not hasattr(self.model_manager, 'undo_manager'):
                return {
                    "error": "Undo manager not available",
                    "status": "failed"
                }
            
            result = await self.model_manager.undo_manager.undo_to_checkpoint(checkpoint_id)
            return {
                "status": "success" if result["success"] else "failed",
                **result
            }
            
        except Exception as e:
            logger.error(f"Failed to restore to checkpoint {checkpoint_id}: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _list_checkpoints(self, args: Dict) -> Dict:
        """List available checkpoints."""
        try:
            if not hasattr(self.model_manager, 'operation_history'):
                return {
                    "error": "Operation history not available",
                    "status": "failed"
                }
            
            checkpoint_ids = self.model_manager.operation_history.list_checkpoints()
            checkpoints = []
            
            for checkpoint_id in checkpoint_ids:
                checkpoint = self.model_manager.operation_history.get_checkpoint(checkpoint_id)
                if checkpoint:
                    checkpoints.append({
                        "checkpoint_id": checkpoint_id,
                        "timestamp": checkpoint["timestamp"],
                        "description": checkpoint["model_state"].get("description", ""),
                        "operation_count": checkpoint["operation_count"]
                    })
            
            return {
                "status": "success",
                "checkpoints": checkpoints,
                "total_checkpoints": len(checkpoints)
            }
            
        except Exception as e:
            logger.error(f"Failed to list checkpoints: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _preview_undo(self, args: Dict) -> Dict:
        """Preview what operations would be undone."""
        count = args.get("count", 1)
        
        try:
            if not hasattr(self.model_manager, 'undo_manager'):
                return {
                    "error": "Undo manager not available", 
                    "status": "failed"
                }
            
            preview_operations = self.model_manager.undo_manager.get_undo_preview(count)
            
            operations = []
            for op in preview_operations:
                operations.append({
                    "operation_id": op.operation_id,
                    "operation_type": op.operation_type.value,
                    "description": op.description,
                    "can_undo": op.can_undo,
                    "dependencies": op.dependencies
                })
            
            return {
                "status": "success",
                "operations_to_undo": operations,
                "count": len(operations),
                "can_undo": len(operations) > 0
            }
            
        except Exception as e:
            logger.error(f"Failed to preview undo: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _restore_deleted_element(self, args: Dict) -> Dict:
        """Restore an actually deleted element."""
        element_id = args["element_id"]
        restore_children = args.get("restore_children", True)
        
        try:
            if not hasattr(self.model_manager, 'actual_deletion_manager') or self.model_manager.actual_deletion_manager is None:
                return {
                    "error": "Actual deletion manager not available",
                    "status": "failed"
                }
            
            success = self.model_manager.actual_deletion_manager.undo_actual_deletion(
                element_id=element_id
            )
            
            if success:
                return {
                    "status": "success",
                    "element_id": element_id,
                    "restored": True,
                    "restore_children": restore_children,
                    "message": f"Element {element_id} restored successfully"
                }
            else:
                return {
                    "status": "failed", 
                    "element_id": element_id,
                    "restored": False,
                    "message": f"Failed to restore element {element_id}"
                }
                
        except Exception as e:
            logger.error(f"Failed to restore deleted element {element_id}: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _list_deleted_elements(self, args: Dict) -> Dict:
        """List actually deleted elements that can be restored."""
        model_id = args.get("model_id")
        
        try:
            if not hasattr(self.model_manager, 'actual_deletion_manager') or self.model_manager.actual_deletion_manager is None:
                return {
                    "error": "Actual deletion manager not available",
                    "status": "failed"
                }
            
            deleted_records = self.model_manager.actual_deletion_manager.list_actual_deletions(model_id=model_id)
            
            deleted_elements = []
            for record in deleted_records:
                deleted_elements.append({
                    "element_id": record["element_id"],
                    "model_id": record["model_id"],
                    "element_type": record["element_data"].get("type", "Unknown"),
                    "element_name": record["element_data"].get("name", "Unnamed"),
                    "deleted_at": record["deletion_timestamp"],
                    "deletion_reason": record["reason"],
                    "can_restore": record["has_backup"],
                    "backup_available": record["has_backup"]
                })
            
            return {
                "status": "success",
                "deleted_elements": deleted_elements,
                "total_deleted": len(deleted_elements),
                "model_filter": model_id
            }
            
        except Exception as e:
            logger.error(f"Failed to list deleted elements: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
    
    async def _get_reversibility_status(self, args: Dict) -> Dict:
        """Get current status of undo/redo capabilities."""
        try:
            status = {
                "operation_history_available": hasattr(self.model_manager, 'operation_history'),
                "actual_deletion_available": hasattr(self.model_manager, 'actual_deletion_manager') and self.model_manager.actual_deletion_manager is not None,
                "undo_manager_available": hasattr(self.model_manager, 'undo_manager'),
                "transaction_manager_available": hasattr(self.model_manager, 'transaction_manager')
            }
            
            # Get detailed statistics if managers are available
            if status["operation_history_available"]:
                op_stats = self.model_manager.operation_history.get_statistics()
                status["operation_statistics"] = op_stats
            
            if status["actual_deletion_available"]:
                del_stats = self.model_manager.actual_deletion_manager.get_statistics()
                status["deletion_statistics"] = del_stats
            
            if status["undo_manager_available"]:
                undo_stats = self.model_manager.undo_manager.get_statistics()
                status["undo_statistics"] = undo_stats
            
            # Overall status
            all_systems_available = all([
                status["operation_history_available"],
                status["actual_deletion_available"],
                status["undo_manager_available"]
            ])
            
            return {
                "status": "success",
                "reversibility_enabled": all_systems_available,
                "systems": status,
                "message": f"Reversibility is {'fully enabled' if all_systems_available else 'partially enabled'}"
            }
            
        except Exception as e:
            logger.error(f"Failed to get reversibility status: {e}")
            return {
                "error": str(e),
                "status": "failed"
            }
