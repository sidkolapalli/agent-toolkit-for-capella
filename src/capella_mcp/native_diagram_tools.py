"""
Native Diagram Creation Tools for Capella MCP Server
These tools integrate with the Java diagram service for native .aird persistence
"""

from typing import Dict, Any, List, Optional
import logging
import os

logger = logging.getLogger(__name__)


def create_native_diagram_tools(mcp):
    """
    Create and register native diagram creation tools with the MCP server
    
    Args:
        mcp: FastMCP server instance
    """
    
    # Import java_client here to avoid circular imports
    from .java_client import get_java_client
    
    @mcp.tool()
    async def create_native_diagram(
        model_path: str,
        diagram_name: str,
        diagram_type: str,
        layer: str,
        description: Optional[str] = None,
        initial_elements: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """
        Create a native Capella diagram that persists in .aird files.
        
        This tool uses the Java diagram service to create diagrams that are:
        - Viewable in Capella IDE
        - Editable in Capella IDE
        - Persistent in .aird format
        - Compatible with all Capella tools
        
        Args:
            model_path: Path to the .aird model file
            diagram_name: Name for the new diagram
            diagram_type: Type of diagram (e.g., "LAB", "SAB", "OAB", "PAB", "CDB", "IS")
            layer: ARCADIA layer ("OA", "SA", "LA", "PA")
            description: Optional description for the diagram
            initial_elements: Optional list of element UUIDs to include in the diagram
        
        Returns:
            Dict containing:
            - success: bool - Whether creation succeeded
            - diagram_id: str - UUID of created diagram
            - name: str - Diagram name
            - type: str - Diagram type
            - layer: str - ARCADIA layer
            - message: str - Success or error message
        
        Valid diagram types per layer:
        - OA: OAB, OES, OEB, ORB, OCB, OPD, IS, CDB
        - SA: SAB, ES, FS, EAB, CSA, IS, CDB, SDFB
        - LA: LAB, LDFB, CDB, IS, EAB, FS, ES, LFBD
        - PA: PAB, PDFB, CDB, IS, EAB, FS, ES, PFBD
        """
        try:
            # Get or create Java service client
            client = get_java_client()
            
            # Check if service is available
            if not client.is_available():
                logger.warning("Java diagram service not available, falling back to Python-only creation")
                return {
                    "success": False,
                    "message": "Java diagram service not available. Native diagram creation requires the Java service to be running.",
                    "fallback": "Use existing Python-only diagram tools for non-native diagrams"
                }
            
            # Validate model path exists
            if not os.path.exists(model_path):
                return {
                    "success": False,
                    "message": f"Model file not found: {model_path}"
                }
            
            # Create diagram via Java service
            result = client.create_diagram(
                name=diagram_name,
                diagram_type=diagram_type,
                layer=layer,
                model_path=model_path,
                description=description,
                initial_elements=initial_elements
            )
            
            return {
                "success": True,
                "diagram_id": result.get("diagramId"),
                "name": result.get("name"),
                "type": result.get("type"),
                "layer": result.get("layer"),
                "element_count": result.get("elementCount", 0),
                "message": f"Successfully created native {diagram_type} diagram in {layer} layer",
                "native": True,
                "editable_in_capella": True
            }
            
        except ValueError as e:
            return {
                "success": False,
                "message": f"Invalid request: {str(e)}"
            }
        except ConnectionError as e:
            return {
                "success": False,
                "message": f"Connection error: {str(e)}"
            }
        except Exception as e:
            logger.error(f"Failed to create native diagram: {e}")
            return {
                "success": False,
                "message": f"Failed to create native diagram: {str(e)}"
            }
    
    @mcp.tool()
    async def update_native_diagram(
        diagram_id: str,
        add_elements: Optional[List[str]] = None,
        remove_elements: Optional[List[str]] = None
    ) -> Dict[str, Any]:
        """
        Update elements in a native Capella diagram.
        
        Args:
            diagram_id: UUID of the diagram to update
            add_elements: Optional list of element UUIDs to add to the diagram
            remove_elements: Optional list of element UUIDs to remove from the diagram
        
        Returns:
            Dict containing:
            - success: bool - Whether update succeeded
            - diagram_id: str - UUID of updated diagram
            - element_count: int - Total number of elements after update
            - added: int - Number of elements added
            - removed: int - Number of elements removed
            - message: str - Success or error message
        """
        try:
            # Get or create Java service client
            client = get_java_client()
            
            # Check if service is available
            if not client.is_available():
                return {
                    "success": False,
                    "message": "Java diagram service not available"
                }
            
            # Update diagram elements
            result = client.update_diagram_elements(
                diagram_id=diagram_id,
                add_elements=add_elements,
                remove_elements=remove_elements
            )
            
            added_count = len(add_elements) if add_elements else 0
            removed_count = len(remove_elements) if remove_elements else 0
            
            return {
                "success": True,
                "diagram_id": diagram_id,
                "element_count": result.get("elementCount", 0),
                "added": added_count,
                "removed": removed_count,
                "message": f"Successfully updated diagram: added {added_count}, removed {removed_count} elements"
            }
            
        except ValueError as e:
            return {
                "success": False,
                "message": f"Diagram not found: {str(e)}"
            }
        except Exception as e:
            logger.error(f"Failed to update native diagram: {e}")
            return {
                "success": False,
                "message": f"Failed to update diagram: {str(e)}"
            }
    
    @mcp.tool()
    async def list_native_diagrams(
        layer: Optional[str] = None,
        diagram_type: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        List all native diagrams created by the Java service.
        
        Args:
            layer: Optional filter by ARCADIA layer ("OA", "SA", "LA", "PA")
            diagram_type: Optional filter by diagram type (e.g., "LAB", "SAB")
        
        Returns:
            Dict containing:
            - success: bool - Whether listing succeeded
            - diagrams: List[Dict] - List of diagram summaries
            - total: int - Total number of diagrams
            - message: str - Success or error message
        """
        try:
            # Get or create Java service client
            client = get_java_client()
            
            # Check if service is available
            if not client.is_available():
                return {
                    "success": False,
                    "message": "Java diagram service not available",
                    "diagrams": [],
                    "total": 0
                }
            
            # List diagrams with optional filters
            diagrams = client.list_diagrams(
                layer=layer,
                diagram_type=diagram_type
            )
            
            # Format filters for message
            filters = []
            if layer:
                filters.append(f"layer={layer}")
            if diagram_type:
                filters.append(f"type={diagram_type}")
            filter_msg = f" (filtered by {', '.join(filters)})" if filters else ""
            
            return {
                "success": True,
                "diagrams": diagrams,
                "total": len(diagrams),
                "message": f"Found {len(diagrams)} native diagrams{filter_msg}"
            }
            
        except Exception as e:
            logger.error(f"Failed to list native diagrams: {e}")
            return {
                "success": False,
                "message": f"Failed to list diagrams: {str(e)}",
                "diagrams": [],
                "total": 0
            }
    
    @mcp.tool()
    async def delete_native_diagram(diagram_id: str) -> Dict[str, Any]:
        """
        Delete a native Capella diagram.
        
        Args:
            diagram_id: UUID of the diagram to delete
        
        Returns:
            Dict containing:
            - success: bool - Whether deletion succeeded
            - message: str - Success or error message
        """
        try:
            # Get or create Java service client
            client = get_java_client()
            
            # Check if service is available
            if not client.is_available():
                return {
                    "success": False,
                    "message": "Java diagram service not available"
                }
            
            # Delete diagram
            client.delete_diagram(diagram_id)
            
            return {
                "success": True,
                "message": f"Successfully deleted diagram {diagram_id}"
            }
            
        except ValueError as e:
            return {
                "success": False,
                "message": f"Diagram not found: {str(e)}"
            }
        except Exception as e:
            logger.error(f"Failed to delete native diagram: {e}")
            return {
                "success": False,
                "message": f"Failed to delete diagram: {str(e)}"
            }
    
    @mcp.tool()
    async def check_java_service_health() -> Dict[str, Any]:
        """
        Check the health status of the Java diagram service.
        
        Returns:
            Dict containing:
            - available: bool - Whether service is available
            - service: str - Service name
            - version: str - Service version
            - phase: str - Current phase (e.g., "GREEN", "REFACTOR")
            - message: str - Health status message
        """
        try:
            # Get or create Java service client
            client = get_java_client()
            
            if client.is_available():
                # Get detailed health info
                import requests
                response = requests.get(f"{client.api_base}/health", timeout=5)
                if response.status_code == 200:
                    health_data = response.json()
                    return {
                        "available": True,
                        "service": health_data.get("service", "Unknown"),
                        "version": health_data.get("version", "Unknown"),
                        "phase": health_data.get("phase", "Unknown"),
                        "status": health_data.get("status", "Unknown"),
                        "message": "Java diagram service is healthy and ready"
                    }
            
            return {
                "available": False,
                "message": "Java diagram service is not available. Please ensure it is running on port 8080."
            }
            
        except Exception as e:
            logger.error(f"Failed to check Java service health: {e}")
            return {
                "available": False,
                "message": f"Failed to check service health: {str(e)}"
            }