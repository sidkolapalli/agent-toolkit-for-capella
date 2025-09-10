"""
Resource management for Capella models with smart context loading and pagination
"""

import json
import base64
from typing import List, Any, Optional
from dataclasses import dataclass
from mcp.types import Resource

from .logger import setup_logger

logger = setup_logger(__name__)


@dataclass
class ResourcePage:
    """Paginated resource response"""

    resources: List[Resource]
    next_cursor: Optional[str] = None
    total_count: int = 0


class ResourceManager:
    """
    Manages MCP resources for Capella models with:
    - Hierarchical URI templates
    - Smart context-aware loading
    - Cursor-based pagination
    - Caching for performance
    """

    def __init__(self, model_manager, context_manager, page_size: int = 100):
        self.model_manager = model_manager
        self.context_manager = context_manager
        self.page_size = page_size

        # URI templates for hierarchical access
        self.uri_templates = {
            "model": "capella://{project_id}/model",
            "element": "capella://{project_id}/element/{element_type}/{element_id}",
            "diagram": "capella://{project_id}/diagram/{diagram_id}",
            "requirement": "capella://{project_id}/requirement/{req_id}",
            "architecture": "capella://{project_id}/{architecture_type}",
            "component": "capella://{project_id}/{architecture}/component/{component_id}",
            "function": "capella://{project_id}/{architecture}/function/{function_id}",
            "interface": "capella://{project_id}/interface/{interface_id}",
            "state_machine": "capella://{project_id}/state_machine/{sm_id}",
            "template": "capella://template/{template_type}/{template_id}",
        }

    async def initialize(self):
        """Initialize resource manager"""
        logger.info("Initializing resource manager")

    async def list_resources(self, cursor: Optional[str] = None) -> List[Resource]:
        """
        List available resources with pagination

        Returns resources in pages of max 100 items to avoid overwhelming
        the context window.
        """
        resources = []

        # Decode cursor if provided
        start_index = 0
        if cursor:
            try:
                cursor_data = json.loads(base64.b64decode(cursor).decode())
                start_index = cursor_data.get("index", 0)
            except Exception as e:
                logger.warning(f"Invalid cursor: {e}")

        # Get all available models
        models = self.model_manager.list_loaded_models()

        for model in models:
            # Add model resource
            resources.append(
                Resource(
                    uri=f"capella://{model['id']}/model",
                    name=f"Model {model['id']}",
                    description="Capella model",
                    mimeType="application/capella+json",
                )
            )

        # Templates not yet implemented
        templates = []
        for template in templates:
            resources.append(
                Resource(
                    uri=f"capella://template/{template.category}/{template.id}",
                    name=template.name,
                    description=template.description,
                    mimeType="application/capella-template+yaml",
                )
            )

        # Apply pagination
        end_index = min(start_index + self.page_size, len(resources))
        page = resources[start_index:end_index]

        # Log resource count
        logger.info(f"Returning {len(page)} resources (total: {len(resources)})")

        return page

    async def read_resource(self, uri: str) -> str:
        """
        Read a specific resource with smart context loading

        For large models, automatically summarizes or filters content
        to stay within context window limits.
        """
        logger.info(f"Reading resource: {uri}")

        # Parse URI
        parts = uri.replace("capella://", "").split("/")

        if not parts:
            raise ValueError(f"Invalid URI: {uri}")

        resource_type = parts[0]

        # Handle template resources
        if resource_type == "template":
            template_category = parts[1] if len(parts) > 1 else None
            template_id = parts[2] if len(parts) > 2 else None
            template = await self.model_manager.get_template(
                template_category, template_id
            )
            return json.dumps(template.to_dict(), indent=2)

        # Handle model resources
        project_id = resource_type
        resource_subtype = parts[1] if len(parts) > 1 else "model"

        # Load model
        model = await self.model_manager.get_model(project_id)

        if resource_subtype == "model":
            # Return entire model with smart context management
            model_data = await model.to_dict()

            # Apply context optimization if model is large
            if self._estimate_tokens(model_data) > 30000:
                logger.info("Model too large, applying context optimization")
                model_data = await self.context_manager.optimize_for_context(
                    model_data, max_tokens=30000
                )

            return json.dumps(model_data, indent=2)

        elif resource_subtype == "components":
            # Return all components
            components = await model.get_all_components()
            component_data = [
                {
                    "id": c.uuid,
                    "name": c.name,
                    "type": c.type,
                    "nature": getattr(c, "nature", None),
                    "parent": getattr(c.parent, "uuid", None) if c.parent else None,
                }
                for c in components
            ]
            return json.dumps(
                {
                    "project": project_id,
                    "type": "components",
                    "count": len(component_data),
                    "components": component_data,
                },
                indent=2,
            )

        elif resource_subtype == "component":
            # Return specific component
            component_id = parts[2] if len(parts) > 2 else None
            if not component_id:
                raise ValueError("Component ID required")

            component = await model.get_component(component_id)
            component_data = await component.to_dict(include_relations=True)

            return json.dumps(component_data, indent=2)

        elif resource_subtype == "functions":
            # Return all functions
            functions = await model.get_all_functions()
            function_data = [
                {
                    "id": f.uuid,
                    "name": f.name,
                    "type": f.type,
                    "allocated_to": [c.uuid for c in f.allocating_components],
                }
                for f in functions
            ]
            return json.dumps(
                {
                    "project": project_id,
                    "type": "functions",
                    "count": len(function_data),
                    "functions": function_data,
                },
                indent=2,
            )

        elif resource_subtype == "function":
            # Return specific function
            function_id = parts[2] if len(parts) > 2 else None
            if not function_id:
                raise ValueError("Function ID required")

            function = await model.get_function(function_id)
            function_data = await function.to_dict(include_ports=True)

            return json.dumps(function_data, indent=2)

        elif resource_subtype in ["oa", "sa", "la", "pa", "epbs"]:
            # Return specific architecture
            architecture = getattr(model, resource_subtype, None)
            if not architecture:
                raise ValueError(f"Architecture {resource_subtype} not found")

            arch_data = await architecture.to_dict()

            # Optimize if too large
            if self._estimate_tokens(arch_data) > 20000:
                arch_data = await self.context_manager.optimize_for_context(
                    arch_data, max_tokens=20000
                )

            return json.dumps(arch_data, indent=2)

        else:
            raise ValueError(f"Unknown resource type: {resource_subtype}")

    def _estimate_tokens(self, data: Any) -> int:
        """Estimate token count for data"""
        # Rough estimation: 1 token per 4 characters
        json_str = json.dumps(data)
        return len(json_str) // 4

    def encode_cursor(self, index: int) -> str:
        """Encode pagination cursor"""
        cursor_data = {"index": index}
        return base64.b64encode(json.dumps(cursor_data).encode()).decode()

    def decode_cursor(self, cursor: str) -> int:
        """Decode pagination cursor"""
        try:
            cursor_data = json.loads(base64.b64decode(cursor).decode())
            return cursor_data.get("index", 0)
        except Exception:
            return 0
