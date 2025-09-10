"""
Test suite for Capella MCP Server core functionality
Following TDD - tests written before implementation
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, patch

from capella_mcp.server import CapellaMCPServer, ServerConfig, Session
from mcp.types import Resource, Tool


class TestCapellaMCPServer:
    """Test the main MCP server functionality"""

    @pytest.fixture
    async def server_config(self):
        """Create test server configuration"""
        return ServerConfig(
            name="test-capella-server",
            version="0.1.0-test",
            max_context_tokens=1000,
            cache_enabled=True,
            page_size=10,
        )

    @pytest.fixture
    async def server(self, server_config):
        """Create test server instance"""
        server = CapellaMCPServer(config=server_config)
        await server.initialize()
        return server

    @pytest.mark.asyncio
    async def test_server_initialization(self, server_config):
        """Test that server initializes correctly with configuration"""
        # Arrange & Act
        server = CapellaMCPServer(config=server_config)

        # Assert
        assert server.config.name == "test-capella-server"
        assert server.config.version == "0.1.0-test"
        assert server.config.max_context_tokens == 1000
        assert server.sessions == {}

    @pytest.mark.asyncio
    async def test_server_capabilities(self, server):
        """Test that server declares correct MCP capabilities"""
        # Act
        init_options = await server.initialize()

        # Assert
        assert init_options.server_name == "test-capella-server"
        assert init_options.server_version == "0.1.0-test"
        assert init_options.capabilities.resources is True
        assert init_options.capabilities.tools is True
        assert init_options.capabilities.prompts is True

    @pytest.mark.asyncio
    async def test_session_management(self, server):
        """Test session creation and management"""
        # Act
        session1 = server._get_current_session()
        session2 = server._get_current_session()

        # Assert
        assert session1.id == session2.id  # Same session returned
        assert "default" in server.sessions
        assert isinstance(session1, Session)
        assert session1.context == {}

    @pytest.mark.asyncio
    async def test_session_cleanup(self, server):
        """Test that inactive sessions are cleaned up"""
        # Arrange
        old_session = Session(id="old-session")
        old_session.last_activity = (
            asyncio.get_event_loop().time() - 7200
        )  # 2 hours ago
        server.sessions["old-session"] = old_session

        active_session = Session(id="active-session")
        server.sessions["active-session"] = active_session

        # Act
        await server.cleanup_sessions()

        # Assert
        assert "old-session" not in server.sessions
        assert "active-session" in server.sessions

    @pytest.mark.asyncio
    async def test_list_resources(self, server):
        """Test resource listing functionality"""
        # Arrange
        mock_resources = [
            Resource(
                uri="capella://test-model/model",
                name="Test Model",
                description="Test Capella model",
                mimeType="application/capella+json",
            )
        ]
        server.resource_manager.list_resources = AsyncMock(return_value=mock_resources)

        # Act
        resources = await server.server.list_resources()

        # Assert
        assert len(resources) == 1
        assert resources[0].uri == "capella://test-model/model"
        assert resources[0].name == "Test Model"

    @pytest.mark.asyncio
    async def test_read_resource(self, server):
        """Test resource reading with context management"""
        # Arrange
        test_uri = "capella://test-model/model"
        expected_content = '{"name": "Test Model", "components": []}'
        server.resource_manager.read_resource = AsyncMock(return_value=expected_content)

        # Act
        content = await server.server.read_resource(test_uri)

        # Assert
        assert content == expected_content
        server.resource_manager.read_resource.assert_called_once_with(test_uri)

    @pytest.mark.asyncio
    async def test_list_tools(self, server):
        """Test tool listing functionality"""
        # Arrange
        mock_tools = [
            Tool(
                name="load_model",
                description="Load a Capella model",
                inputSchema={
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                },
            )
        ]
        server.tool_registry.list_tools = AsyncMock(return_value=mock_tools)

        # Act
        tools = await server.server.list_tools()

        # Assert
        assert len(tools) == 1
        assert tools[0].name == "load_model"

    @pytest.mark.asyncio
    async def test_call_tool_with_transaction(self, server):
        """Test that tool calls are wrapped in transactions"""
        # Arrange
        tool_name = "create_component"
        arguments = {"name": "TestComponent", "type": "PhysicalComponent"}
        expected_result = {"success": True, "component_id": "uuid-123"}

        server.tool_registry.call_tool = AsyncMock(return_value=expected_result)

        # Act
        with patch.object(
            server.transaction_manager, "transaction"
        ) as mock_transaction:
            mock_transaction.return_value.__aenter__ = AsyncMock()
            mock_transaction.return_value.__aexit__ = AsyncMock()

            result = await server.server.call_tool(tool_name, arguments)

        # Assert
        assert result == expected_result
        mock_transaction.assert_called_once()
        server.tool_registry.call_tool.assert_called_once()

    @pytest.mark.asyncio
    async def test_error_handling_in_tool_call(self, server):
        """Test that errors in tool calls trigger rollback"""
        # Arrange
        tool_name = "create_component"
        arguments = {"name": "BadComponent"}

        server.tool_registry.call_tool = AsyncMock(
            side_effect=ValueError("Invalid component type")
        )

        # Act & Assert
        with patch.object(
            server.transaction_manager, "transaction"
        ) as mock_transaction:
            mock_transaction.return_value.__aenter__ = AsyncMock()
            mock_transaction.return_value.__aexit__ = AsyncMock()

            with pytest.raises(ValueError, match="Invalid component type"):
                await server.server.call_tool(tool_name, arguments)

            # Verify transaction was properly exited (rollback)
            mock_transaction.return_value.__aexit__.assert_called_once()


class TestServerConfig:
    """Test server configuration"""

    def test_default_config(self):
        """Test default configuration values"""
        # Act
        config = ServerConfig()

        # Assert
        assert config.name == "capella-mcp-server"
        assert config.version == "0.1.0"
        assert config.max_context_tokens == 50000
        assert config.cache_enabled is True
        assert config.page_size == 100

    def test_custom_config(self):
        """Test custom configuration values"""
        # Act
        config = ServerConfig(
            name="custom-server", max_context_tokens=10000, cache_enabled=False
        )

        # Assert
        assert config.name == "custom-server"
        assert config.max_context_tokens == 10000
        assert config.cache_enabled is False


class TestSession:
    """Test session management"""

    def test_session_creation(self):
        """Test that sessions are created with unique IDs"""
        # Act
        session1 = Session()
        session2 = Session()

        # Assert
        assert session1.id != session2.id
        assert session1.context == {}
        assert session1.capabilities == {}
        assert session1.created_at <= session1.last_activity

    def test_session_with_client_id(self):
        """Test session creation with client ID"""
        # Act
        session = Session(client_id="test-client-123")

        # Assert
        assert session.client_id == "test-client-123"
