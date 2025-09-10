"""
Test suite for Resource Management with pagination and context optimization
Following TDD principles
"""

import pytest
import json
from unittest.mock import AsyncMock, MagicMock

from capella_mcp.resources import ResourceManager, ResourcePage
from mcp.types import Resource


class TestResourceManager:
    """Test resource management with smart context loading"""

    @pytest.fixture
    async def mock_model_manager(self):
        """Create mock model manager"""
        manager = AsyncMock()

        # Mock model listing
        mock_model = MagicMock()
        mock_model.id = "test-model"
        mock_model.name = "Test Model"
        mock_model.get_all_components = AsyncMock(
            return_value=[
                MagicMock(uuid="comp-1", name="Component1", type="PhysicalComponent"),
                MagicMock(uuid="comp-2", name="Component2", type="LogicalComponent"),
            ]
        )
        mock_model.get_all_functions = AsyncMock(
            return_value=[
                MagicMock(uuid="func-1", name="Function1", type="PhysicalFunction")
            ]
        )

        manager.list_models = AsyncMock(return_value=[mock_model])
        manager.get_model = AsyncMock(return_value=mock_model)
        manager.list_templates = AsyncMock(return_value=[])

        return manager

    @pytest.fixture
    async def mock_context_manager(self):
        """Create mock context manager"""
        manager = AsyncMock()
        manager.optimize_for_context = AsyncMock(
            side_effect=lambda data, max_tokens: data  # Return data unchanged
        )
        return manager

    @pytest.fixture
    async def resource_manager(self, mock_model_manager, mock_context_manager):
        """Create resource manager with mocks"""
        manager = ResourceManager(
            model_manager=mock_model_manager,
            context_manager=mock_context_manager,
            page_size=10,
        )
        await manager.initialize()
        return manager

    @pytest.mark.asyncio
    async def test_list_resources_basic(self, resource_manager):
        """Test basic resource listing without pagination"""
        # Act
        resources = await resource_manager.list_resources()

        # Assert
        assert len(resources) > 0

        # Check model resource
        model_resources = [r for r in resources if "/model" in r.uri]
        assert len(model_resources) == 1
        assert model_resources[0].uri == "capella://test-model/model"
        assert model_resources[0].name == "Test Model"
        assert model_resources[0].mimeType == "application/capella+json"

        # Check component resources
        component_resources = [r for r in resources if "/component/" in r.uri]
        assert len(component_resources) == 2
        assert component_resources[0].uri == "capella://test-model/component/comp-1"
        assert component_resources[0].name == "Component1"

    @pytest.mark.asyncio
    async def test_list_resources_with_pagination(self, resource_manager):
        """Test resource listing with pagination"""
        # Arrange
        resource_manager.page_size = 2

        # Act - Get first page
        first_page = await resource_manager.list_resources()

        # Assert
        assert len(first_page) == 2

        # Act - Get second page with cursor
        cursor = resource_manager.encode_cursor(2)
        second_page = await resource_manager.list_resources(cursor=cursor)

        # Assert
        assert len(second_page) == 2
        assert second_page[0].uri != first_page[0].uri  # Different resources

    @pytest.mark.asyncio
    async def test_read_model_resource(self, resource_manager, mock_model_manager):
        """Test reading entire model resource"""
        # Arrange
        mock_model = mock_model_manager.get_model.return_value
        mock_model.to_dict = AsyncMock(
            return_value={
                "id": "test-model",
                "name": "Test Model",
                "components": [],
                "functions": [],
            }
        )

        # Act
        content = await resource_manager.read_resource("capella://test-model/model")

        # Assert
        data = json.loads(content)
        assert data["id"] == "test-model"
        assert data["name"] == "Test Model"
        mock_model_manager.get_model.assert_called_once_with("test-model")

    @pytest.mark.asyncio
    async def test_read_large_model_with_context_optimization(
        self, resource_manager, mock_model_manager, mock_context_manager
    ):
        """Test that large models trigger context optimization"""
        # Arrange
        large_model_data = {"data": "x" * 200000}  # Large data
        mock_model = mock_model_manager.get_model.return_value
        mock_model.to_dict = AsyncMock(return_value=large_model_data)

        # Act
        await resource_manager.read_resource("capella://test-model/model")

        # Assert
        mock_context_manager.optimize_for_context.assert_called_once()
        call_args = mock_context_manager.optimize_for_context.call_args
        assert call_args[0][0] == large_model_data
        assert call_args[1]["max_tokens"] == 30000

    @pytest.mark.asyncio
    async def test_read_component_resource(self, resource_manager, mock_model_manager):
        """Test reading specific component resource"""
        # Arrange
        mock_component = MagicMock()
        mock_component.uuid = "comp-1"
        mock_component.name = "TestComponent"
        mock_component.to_dict = AsyncMock(
            return_value={
                "id": "comp-1",
                "name": "TestComponent",
                "type": "PhysicalComponent",
            }
        )

        mock_model = mock_model_manager.get_model.return_value
        mock_model.get_component = AsyncMock(return_value=mock_component)

        # Act
        content = await resource_manager.read_resource(
            "capella://test-model/component/comp-1"
        )

        # Assert
        data = json.loads(content)
        assert data["id"] == "comp-1"
        assert data["name"] == "TestComponent"
        mock_model.get_component.assert_called_once_with("comp-1")

    @pytest.mark.asyncio
    async def test_read_components_collection(
        self, resource_manager, mock_model_manager
    ):
        """Test reading all components as a collection"""
        # Act
        content = await resource_manager.read_resource(
            "capella://test-model/components"
        )

        # Assert
        data = json.loads(content)
        assert data["type"] == "components"
        assert data["count"] == 2
        assert len(data["components"]) == 2
        assert data["components"][0]["name"] == "Component1"

    @pytest.mark.asyncio
    async def test_invalid_uri_handling(self, resource_manager):
        """Test handling of invalid URIs"""
        # Act & Assert
        with pytest.raises(ValueError, match="Invalid URI"):
            await resource_manager.read_resource("invalid://uri")

        with pytest.raises(ValueError, match="Component ID required"):
            await resource_manager.read_resource("capella://test-model/component")

    @pytest.mark.asyncio
    async def test_cursor_encoding_decoding(self, resource_manager):
        """Test cursor encoding and decoding for pagination"""
        # Act
        cursor = resource_manager.encode_cursor(42)
        decoded = resource_manager.decode_cursor(cursor)

        # Assert
        assert decoded == 42

        # Test invalid cursor
        invalid_decoded = resource_manager.decode_cursor("invalid-cursor")
        assert invalid_decoded == 0

    @pytest.mark.asyncio
    async def test_token_estimation(self, resource_manager):
        """Test token count estimation for context management"""
        # Arrange
        data = {"text": "a" * 400}  # 400 characters

        # Act
        estimated_tokens = resource_manager._estimate_tokens(data)

        # Assert
        # Should be roughly 100 tokens (1 token per 4 characters)
        assert 95 <= estimated_tokens <= 110

    @pytest.mark.asyncio
    async def test_template_resource_listing(
        self, resource_manager, mock_model_manager
    ):
        """Test that templates are included in resource listing"""
        # Arrange
        mock_template = MagicMock()
        mock_template.id = "hybrid-p2"
        mock_template.name = "P2 Hybrid Template"
        mock_template.description = "P2 hybrid architecture template"
        mock_template.category = "architectures"

        mock_model_manager.list_templates = AsyncMock(return_value=[mock_template])

        # Act
        resources = await resource_manager.list_resources()

        # Assert
        template_resources = [r for r in resources if "template" in r.uri]
        assert len(template_resources) == 1
        assert template_resources[0].uri == "capella://template/architectures/hybrid-p2"
        assert template_resources[0].name == "P2 Hybrid Template"


class TestResourcePage:
    """Test resource pagination data structure"""

    def test_resource_page_creation(self):
        """Test ResourcePage dataclass creation"""
        # Arrange
        resources = [
            Resource(uri="test://1", name="Resource 1"),
            Resource(uri="test://2", name="Resource 2"),
        ]

        # Act
        page = ResourcePage(
            resources=resources, next_cursor="next-page-cursor", total_count=100
        )

        # Assert
        assert len(page.resources) == 2
        assert page.next_cursor == "next-page-cursor"
        assert page.total_count == 100
