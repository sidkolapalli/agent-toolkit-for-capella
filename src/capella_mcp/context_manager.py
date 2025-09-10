"""
Context management for optimizing model data for AI consumption
"""

from typing import Any, Dict, List, Set
from dataclasses import dataclass, field
import logging
import json

logger = logging.getLogger(__name__)

# Token limits
MAX_TOKENS = 50000  # Maximum tokens for context
ELEMENT_AVG_TOKENS = 50  # Average tokens per element


@dataclass
class ContextWindow:
    """Represents a context window for AI consumption"""

    elements: List[Dict[str, Any]] = field(default_factory=list)
    token_count: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)

    def add_element(self, element: Dict[str, Any], tokens: int = ELEMENT_AVG_TOKENS):
        """Add an element to the context"""
        if self.token_count + tokens > MAX_TOKENS:
            return False

        self.elements.append(element)
        self.token_count += tokens
        return True

    def is_full(self) -> bool:
        """Check if context window is full"""
        return self.token_count >= MAX_TOKENS * 0.9  # 90% threshold

    def clear(self):
        """Clear the context window"""
        self.elements.clear()
        self.token_count = 0
        self.metadata.clear()


@dataclass
class ContextStrategy:
    """Strategy for loading context"""

    name: str
    priority_rules: List[str] = field(default_factory=list)
    filters: Dict[str, Any] = field(default_factory=dict)
    max_depth: int = 3

    def should_include(self, element: Dict[str, Any]) -> bool:
        """Check if element should be included based on strategy"""
        # Apply filters
        for key, value in self.filters.items():
            if element.get(key) != value:
                return False

        # Apply priority rules
        for rule in self.priority_rules:
            if rule == "has_requirements" and not element.get("requirements"):
                return False
            elif rule == "has_interfaces" and not element.get("interfaces"):
                return False
            elif rule == "is_critical" and not element.get("critical", False):
                return False

        return True

    def get_priority(self, element: Dict[str, Any]) -> int:
        """Get priority score for an element"""
        score = 0

        # Base score by type
        element_type = element.get("type", "")
        if "System" in element_type:
            score += 100
        elif "Component" in element_type:
            score += 50
        elif "Function" in element_type:
            score += 30

        # Bonus for critical elements
        if element.get("critical", False):
            score += 50

        # Bonus for elements with many connections
        connections = element.get("connections", [])
        score += min(len(connections) * 5, 50)

        return score


class ContextManager:
    """Manages context optimization for AI consumption"""

    def __init__(self, max_tokens: int = MAX_TOKENS):
        self.max_tokens = max_tokens
        self.strategies: Dict[str, ContextStrategy] = self._init_strategies()
        self.current_window = ContextWindow()
        self._element_cache: Dict[str, int] = {}  # Cache token counts

    def _init_strategies(self) -> Dict[str, ContextStrategy]:
        """Initialize default context strategies"""
        return {
            "overview": ContextStrategy(
                name="overview", priority_rules=["is_critical"], max_depth=2
            ),
            "detailed": ContextStrategy(
                name="detailed",
                priority_rules=["has_requirements", "has_interfaces"],
                max_depth=4,
            ),
            "focused": ContextStrategy(
                name="focused", filters={"selected": True}, max_depth=3
            ),
            "hierarchical": ContextStrategy(name="hierarchical", max_depth=5),
        }

    def optimize_context(
        self, model_data: Any, strategy_name: str = "overview"
    ) -> Dict[str, Any]:
        """Optimize model data for AI context"""
        strategy = self.strategies.get(strategy_name, self.strategies["overview"])

        # Clear current window
        self.current_window.clear()

        # Extract and prioritize elements
        elements = self._extract_elements(model_data)
        prioritized = self._prioritize_elements(elements, strategy)

        # Build context window
        context_data = self._build_context(prioritized, strategy)

        # Add metadata
        context_data["metadata"] = {
            "strategy": strategy_name,
            "total_elements": len(elements),
            "included_elements": len(context_data.get("elements", [])),
            "token_estimate": self.current_window.token_count,
        }

        logger.info(
            f"Optimized context with strategy '{strategy_name}': "
            f"{context_data['metadata']['included_elements']}/{context_data['metadata']['total_elements']} elements"
        )

        return context_data

    def _extract_elements(self, model_data: Any) -> List[Dict[str, Any]]:
        """Extract elements from model data"""
        elements = []

        # Real extraction - parse actual capellambse model structure
        if hasattr(model_data, '__dict__'):
            # Real capellambse model
            try:
                # Add root element from actual model
                elements.append(
                    {
                        "id": str(getattr(model_data, 'uuid', 'root')),
                        "type": model_data.__class__.__name__,
                        "name": getattr(model_data, 'name', 'Model'),
                        "depth": 0,
                    }
                )
                
                # Extract actual child elements from model
                # This would need proper capellambse model traversal
                # For now, return minimal real structure
                
            except Exception as e:
                logger.warning(f"Failed to extract model structure: {e}")
        elif isinstance(model_data, dict):
            # Fallback for non-capellambse data
            elements.append(
                {
                    "id": model_data.get("id", "root"),
                    "type": model_data.get("type", "Model"),
                    "name": model_data.get("name", "Unknown"),
                    "depth": 0,
                }
            )

        return elements

    def _prioritize_elements(
        self, elements: List[Dict], strategy: ContextStrategy
    ) -> List[Dict]:
        """Prioritize elements based on strategy"""
        # Filter elements
        filtered = [e for e in elements if strategy.should_include(e)]

        # Sort by priority
        prioritized = sorted(
            filtered, key=lambda e: strategy.get_priority(e), reverse=True
        )

        return prioritized

    def _build_context(
        self, elements: List[Dict], strategy: ContextStrategy
    ) -> Dict[str, Any]:
        """Build context window from prioritized elements"""
        context = {"elements": [], "relationships": [], "hierarchy": {}}

        included_ids = set()

        for element in elements:
            # Check depth
            if element.get("depth", 0) > strategy.max_depth:
                continue

            # Estimate tokens
            tokens = self._estimate_tokens(element)

            # Try to add to window
            if self.current_window.add_element(element, tokens):
                context["elements"].append(element)
                included_ids.add(element["id"])
            else:
                # Window full
                break

        # Add relationships for included elements
        context["relationships"] = self._extract_relationships(
            context["elements"], included_ids
        )

        # Build hierarchy
        context["hierarchy"] = self._build_hierarchy(context["elements"])

        return context

    def _estimate_tokens(self, element: Dict) -> int:
        """Estimate token count for an element"""
        # Check cache
        element_id = element.get("id", "")
        if element_id in self._element_cache:
            return self._element_cache[element_id]

        # Estimate based on content
        json_str = json.dumps(element)
        # Rough estimate: 1 token per 4 characters
        tokens = len(json_str) // 4

        # Cache result
        if element_id:
            self._element_cache[element_id] = tokens

        return tokens

    def _extract_relationships(
        self, elements: List[Dict], included_ids: Set[str]
    ) -> List[Dict]:
        """Extract relationships between included elements"""
        relationships = []

        for element in elements:
            # Real relationships from capellambse model
            if "parent_id" in element and element["parent_id"] in included_ids:
                relationships.append(
                    {
                        "source": element["parent_id"],
                        "target": element["id"],
                        "type": "contains",
                    }
                )

        return relationships

    def _build_hierarchy(self, elements: List[Dict]) -> Dict:
        """Build hierarchical structure"""
        hierarchy = {}

        # Group by depth
        by_depth = {}
        for element in elements:
            depth = element.get("depth", 0)
            if depth not in by_depth:
                by_depth[depth] = []
            by_depth[depth].append(element["id"])

        # Build tree
        for depth in sorted(by_depth.keys()):
            hierarchy[f"level_{depth}"] = by_depth[depth]

        return hierarchy

    def get_summary(self, model_data: Any) -> Dict[str, Any]:
        """Get a summary of the model"""
        elements = self._extract_elements(model_data)

        # Count by type
        type_counts = {}
        for element in elements:
            element_type = element.get("type", "Unknown")
            type_counts[element_type] = type_counts.get(element_type, 0) + 1

        return {
            "total_elements": len(elements),
            "type_distribution": type_counts,
            "max_depth": max(e.get("depth", 0) for e in elements) if elements else 0,
        }

    def chunk_large_model(self, model_data: Any, chunk_size: int = 100) -> List[Dict]:
        """Chunk a large model into smaller pieces"""
        elements = self._extract_elements(model_data)
        chunks = []

        for i in range(0, len(elements), chunk_size):
            chunk = elements[i : i + chunk_size]
            chunks.append(
                {
                    "chunk_id": i // chunk_size,
                    "elements": chunk,
                    "start_index": i,
                    "end_index": min(i + chunk_size, len(elements)),
                }
            )

        return chunks
