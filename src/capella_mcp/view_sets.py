"""
Standard view set tools for Capella MCP Server

These tools orchestrate creation of common diagram sets across ARCADIA layers
by leveraging the Java diagram service for native .aird persistence.
"""

from typing import Dict, Any, List, Optional
import logging

logger = logging.getLogger(__name__)

try:
    # Expose get_java_client at module level for test monkeypatching
    from .java_client import get_java_client as get_java_client  # type: ignore
except Exception:
    get_java_client = None  # type: ignore

def create_view_set_tools(mcp):
    """Register view set creation tools with the MCP server."""

    # Use module-level get_java_client (allow monkeypatch in tests)

    DEFAULT_VIEW_SET = {
        "OA": ["OAB", "OCB", "OES"],
        "SA": ["SAB", "ES", "FS"],
        "LA": ["LAB", "LDFB", "LFBD"],
        "PA": ["PAB", "PDFB", "PFBD"],
    }

    @mcp.tool()
    async def create_standard_view_set(
        model_path: str,
        layers: Optional[List[str]] = None,
        include_common: bool = True
    ) -> Dict[str, Any]:
        """
        Create a standard set of native Capella diagrams across ARCADIA layers.

        Args:
            model_path: Path to the .aird model file
            layers: Optional list of layer codes to include (OA/SA/LA/PA)
            include_common: Whether to include common diagrams like CDB and IS

        Returns:
            Summary of created diagrams by layer
        """
        client = get_java_client()
        if not client.is_available():
            return {
                "success": False,
                "message": "Java diagram service not available",
                "created": {}
            }

        selected_layers = [l.upper() for l in (layers or ["OA", "SA", "LA", "PA"]) if l.upper() in DEFAULT_VIEW_SET]
        if not selected_layers:
            selected_layers = ["OA", "SA", "LA", "PA"]

        created: Dict[str, List[Dict[str, Any]]] = {l: [] for l in selected_layers}

        def _make_name(layer: str, dtype: str) -> str:
            return f"[{layer}] {dtype}"

        try:
            # Create diagrams per layer
            for layer in selected_layers:
                for dtype in DEFAULT_VIEW_SET[layer]:
                    try:
                        res = client.create_diagram(
                            name=_make_name(layer, dtype),
                            diagram_type=dtype,
                            layer=layer,
                            model_path=model_path,
                        )
                        created[layer].append(res)
                    except Exception as e:
                        logger.warning(f"Failed to create {layer}/{dtype}: {e}")

            # Optionally add a couple of common diagrams
            common_created: List[Dict[str, Any]] = []
            if include_common:
                for layer in selected_layers:
                    for dtype in ("CDB", "IS"):
                        try:
                            res = client.create_diagram(
                                name=_make_name(layer, dtype),
                                diagram_type=dtype,
                                layer=layer,
                                model_path=model_path,
                            )
                            common_created.append(res)
                            created[layer].append(res)
                        except Exception as e:
                            logger.info(f"Common diagram {layer}/{dtype} skipped: {e}")

            total = sum(len(v) for v in created.values())
            return {
                "success": True,
                "message": f"Created {total} native diagrams across {len(selected_layers)} layer(s)",
                "created": created,
                "layers": selected_layers
            }
        except Exception as e:
            logger.error(f"Failed to create view set: {e}")
            return {
                "success": False,
                "message": f"Failed to create view set: {e}",
                "created": created
            }
