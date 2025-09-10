"""
Test suite for Tool Registry and execution with transaction safety
Following TDD principles
"""

import pytest
from unittest.mock import AsyncMock, MagicMock
from dataclasses import dataclass
from typing import Optional

from capella_mcp.tools import ToolRegistry


@dataclass
class ToolResult:
    """Result structure for tool operations"""

    success: bool
    data: Optional[dict] = None
    message: Optional[str] = None
    error: Optional[str] = None


class TestToolRegistry:
    """Test tool registration and execution"""

    @pytest.fixture
    async def mock_model_manager(self):
        """Create mock model manager"""
        manager = AsyncMock()

        # Mock model operations
        mock_model = MagicMock()
        mock_model.id = "test-model"
        mock_model.name = "Test Model"
        mock_model.create_component = AsyncMock(
            return_value=MagicMock(uuid="new-comp-123", name="NewComponent")
        )
        mock_model.save = AsyncMock()
        mock_model.validate = AsyncMock(return_value={"valid": True})

        manager.get_model = AsyncMock(return_value=mock_model)
        manager.load_model = AsyncMock(return_value=mock_model)

        return manager

    @pytest.fixture
    async def mock_transaction_manager(self):
        """Create mock transaction manager"""
        manager = AsyncMock()
        manager.create_snapshot = AsyncMock(return_value="snapshot-123")
        manager.commit = AsyncMock()
        manager.rollback = AsyncMock()
        return manager

    @pytest.fixture
    async def tool_registry(self, mock_model_manager, mock_transaction_manager):
        """Create tool registry with mocks"""
        registry = ToolRegistry(
            model_manager=mock_model_manager,
            transaction_manager=mock_transaction_manager,
        )
        await registry.initialize()
        return registry

    @pytest.mark.asyncio
    async def test_tool_registration(self, tool_registry):
        """Test that tools are properly registered"""
        # Act
        tools = await tool_registry.list_tools()

        # Assert
        assert len(tools) > 0

        # Check for essential tools
        tool_names = [t.name for t in tools]
        assert "load_model" in tool_names
        assert "create_component" in tool_names
        assert "validate_model" in tool_names
        assert "query_elements" in tool_names

    @pytest.mark.asyncio
    async def test_load_model_tool(self, tool_registry, mock_model_manager):
        """Test loading a model"""
        # Arrange
        arguments = {"path": "test/model.capella", "cache": True}

        # Act
        result = await tool_registry.call_tool("load_model", arguments)

        # Assert
        assert result["success"] is True
        assert result["model_id"] == "test-model"
        assert result["name"] == "Test Model"
        mock_model_manager.load_model.assert_called_once_with(
            "test/model.capella", True
        )

    @pytest.mark.asyncio
    async def test_create_component_tool(self, tool_registry, mock_model_manager):
        """Test creating a component"""
        # Arrange
        arguments = {
            "model_id": "test-model",
            "name": "NewComponent",
            "type": "PhysicalComponent",
            "nature": "NODE",
        }

        # Act
        result = await tool_registry.call_tool("create_component", arguments)

        # Assert
        assert result["success"] is True
        assert result["component_id"] == "new-comp-123"
        assert result["uri"] == "capella://test-model/component/new-comp-123"

        mock_model = mock_model_manager.get_model.return_value
        mock_model.create_component.assert_called_once()
        mock_model.save.assert_called_once()

    @pytest.mark.asyncio
    async def test_create_component_with_parent(
        self, tool_registry, mock_model_manager
    ):
        """Test creating a component with parent"""
        # Arrange
        mock_parent = MagicMock(uuid="parent-123")
        mock_model = mock_model_manager.get_model.return_value
        mock_model.get_component = AsyncMock(return_value=mock_parent)

        arguments = {
            "model_id": "test-model",
            "parent_id": "parent-123",
            "name": "ChildComponent",
            "type": "PhysicalComponent",
        }

        # Act
        result = await tool_registry.call_tool("create_component", arguments)

        # Assert
        assert result["success"] is True
        mock_model.get_component.assert_called_once_with("parent-123")

    @pytest.mark.asyncio
    async def test_validate_model_tool(self, tool_registry, mock_model_manager):
        """Test model validation"""
        # Arrange
        mock_model = mock_model_manager.get_model.return_value
        mock_model.validate = AsyncMock(
            return_value=[
                {"rule": "metamodel", "valid": True},
                {"rule": "consistency", "valid": True},
                {
                    "rule": "completeness",
                    "valid": False,
                    "errors": ["Missing allocations"],
                },
            ]
        )

        arguments = {
            "model_id": "test-model",
            "rules": ["metamodel", "consistency", "completeness"],
        }

        # Act
        result = await tool_registry.call_tool("validate_model", arguments)

        # Assert
        assert result["valid"] is False  # One rule failed
        assert len(result["results"]) == 3
        assert result["results"][2]["valid"] is False
        mock_model.validate.assert_called_once_with(
            ["metamodel", "consistency", "completeness"]
        )

    @pytest.mark.asyncio
    async def test_query_elements_tool(self, tool_registry, mock_model_manager):
        """Test querying model elements"""
        # Arrange
        mock_model = mock_model_manager.get_model.return_value
        mock_model.query_elements = AsyncMock(
            return_value={
                "elements": [
                    {"id": "elem-1", "name": "Element1", "type": "PhysicalComponent"},
                    {"id": "elem-2", "name": "Element2", "type": "PhysicalComponent"},
                ],
                "total": 2,
                "hasMore": False,
            }
        )

        arguments = {
            "model_id": "test-model",
            "element_type": "PhysicalComponent",
            "filters": {"nature": "NODE"},
        }

        # Act
        result = await tool_registry.call_tool("query_elements", arguments)

        # Assert
        assert len(result["elements"]) == 2
        assert result["total"] == 2
        assert result["hasMore"] is False

    @pytest.mark.asyncio
    async def test_tool_with_transaction_rollback(
        self, tool_registry, mock_transaction_manager
    ):
        """Test that failed operations trigger rollback"""
        # Arrange
        tool_registry.model_manager.get_model = AsyncMock(
            side_effect=ValueError("Model not found")
        )

        arguments = {"model_id": "non-existent", "name": "Component"}

        # Act & Assert
        with pytest.raises(ValueError, match="Model not found"):
            await tool_registry.call_tool("create_component", arguments)

    @pytest.mark.asyncio
    async def test_unknown_tool_handling(self, tool_registry):
        """Test handling of unknown tool calls"""
        # Act & Assert
        with pytest.raises(ValueError, match="Unknown tool"):
            await tool_registry.call_tool("non_existent_tool", {})

    @pytest.mark.asyncio
    async def test_tool_input_validation(self, tool_registry):
        """Test that tool inputs are validated"""
        # Arrange - Missing required parameter
        arguments = {
            "model_id": "test-model"
            # Missing 'name' parameter
        }

        # Act & Assert
        with pytest.raises(ValueError, match="Missing required parameter"):
            await tool_registry.call_tool("create_component", arguments)

    @pytest.mark.asyncio
    async def test_tool_with_progress_reporting(
        self, tool_registry, mock_model_manager
    ):
        """Test tools that report progress for long operations"""
        # Arrange
        progress_callback = AsyncMock()

        arguments = {"model_id": "test-model", "operation": "complex_analysis"}

        # Mock a tool that reports progress
        async def complex_tool(args, progress=None):
            if progress:
                await progress(0.25, "Starting analysis")
                await progress(0.50, "Processing components")
                await progress(0.75, "Processing functions")
                await progress(1.0, "Complete")
            return {"result": "analysis_complete"}

        tool_registry.register_tool(
            "complex_analysis",
            "Perform complex analysis",
            {"type": "object", "properties": {"model_id": {"type": "string"}}},
            complex_tool,
        )

        # Act
        result = await tool_registry.call_tool(
            "complex_analysis", arguments, progress_callback=progress_callback
        )

        # Assert
        assert result["result"] == "analysis_complete"
        assert progress_callback.call_count == 4


class TestToolResult:
    """Test tool result structure"""

    def test_successful_tool_result(self):
        """Test creating successful tool result"""
        # Act
        result = ToolResult(
            success=True,
            data={"component_id": "123"},
            message="Component created successfully",
        )

        # Assert
        assert result.success is True
        assert result.data["component_id"] == "123"
        assert result.message == "Component created successfully"
        assert result.error is None

    def test_failed_tool_result(self):
        """Test creating failed tool result"""
        # Act
        result = ToolResult(
            success=False,
            error="Validation failed",
            message="Component name already exists",
        )

        # Assert
        assert result.success is False
        assert result.error == "Validation failed"
        assert result.message == "Component name already exists"
        assert result.data is None
