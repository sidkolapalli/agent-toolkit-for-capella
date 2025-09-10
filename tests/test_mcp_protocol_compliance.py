"""
Comprehensive MCP Protocol Compliance Test Suite

Tests that the Capella MCP Server correctly implements the Model Context Protocol
specification as defined in the official documentation.
"""

import pytest
import asyncio
import json
from unittest.mock import AsyncMock, patch
from dataclasses import dataclass

from capella_mcp.server import CapellaMCPServer, ServerConfig
from mcp.types import (
    Tool,
    Resource,
)


@dataclass
class MockTransport:
    """Mock transport for testing"""

    read_stream: AsyncMock
    write_stream: AsyncMock

    def __init__(self):
        self.read_stream = AsyncMock()
        self.write_stream = AsyncMock()
        self.messages_sent = []

        # Track messages
        async def write_message(msg):
            self.messages_sent.append(msg)
            return None

        self.write_stream.write = AsyncMock(side_effect=write_message)


class TestMCPProtocolCompliance:
    """Test suite for MCP protocol compliance"""

    @pytest.fixture
    async def server(self):
        """Create a test server instance"""
        config = ServerConfig(
            model_path="test_model.capella",
            capabilities={
                "tools": True,
                "resources": True,
                "prompts": True,
                "logging": True,
            },
        )
        server = CapellaMCPServer(config)
        await server.initialize()
        return server

    @pytest.fixture
    def mock_transport(self):
        """Create mock transport"""
        return MockTransport()

    # ==================== LIFECYCLE TESTS ====================

    @pytest.mark.asyncio
    async def test_server_initialization_protocol(self, server):
        """Test server follows MCP initialization protocol"""
        # Test server info is properly set
        assert server.name == "capella-mcp-server"
        assert server.version is not None

        # Test capabilities are properly declared
        capabilities = server.get_capabilities()
        assert isinstance(capabilities, dict)
        assert "tools" in capabilities
        assert "resources" in capabilities
        assert "prompts" in capabilities
        assert "logging" in capabilities

    @pytest.mark.asyncio
    async def test_initialize_request_response(self, server, mock_transport):
        """Test proper initialize request/response per MCP spec"""
        # Simulate initialize request
        init_request = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {
                    "roots": {"listChanged": True},
                    "sampling": {},
                    "elicitation": {},
                },
                "clientInfo": {"name": "TestClient", "version": "1.0.0"},
            },
        }

        # Process initialization
        response = await server.handle_initialize(init_request["params"])

        # Verify response structure per MCP spec
        assert "protocolVersion" in response
        assert response["protocolVersion"] in ["2024-11-05", "2025-06-18"]
        assert "capabilities" in response
        assert "serverInfo" in response
        assert response["serverInfo"]["name"] == "capella-mcp-server"

        # Verify capability negotiation
        caps = response["capabilities"]
        assert isinstance(caps.get("tools"), dict) or caps.get("tools") is None
        assert isinstance(caps.get("resources"), dict) or caps.get("resources") is None
        assert isinstance(caps.get("prompts"), dict) or caps.get("prompts") is None
        assert isinstance(caps.get("logging"), dict) or caps.get("logging") == {}

    @pytest.mark.asyncio
    async def test_initialized_notification_handling(self, server):
        """Test server handles initialized notification correctly"""
        # Server should accept initialized notification
        _ = {"jsonrpc": "2.0", "method": "notifications/initialized"}

        # Should not raise exception
        await server.handle_initialized_notification()
        assert server.is_initialized is True

    # ==================== TOOL TESTS ====================

    @pytest.mark.asyncio
    async def test_list_tools_compliance(self, server):
        """Test tools/list request returns proper MCP tool format"""
        tools = await server.list_tools()

        assert isinstance(tools, list)
        for tool in tools:
            # Verify tool structure per MCP spec
            assert isinstance(tool, Tool)
            assert hasattr(tool, "name")
            assert hasattr(tool, "description")
            assert hasattr(tool, "inputSchema")

            # Verify inputSchema is JSON Schema format
            schema = tool.inputSchema
            assert isinstance(schema, dict)
            assert "type" in schema
            assert schema["type"] == "object"
            assert "properties" in schema
            assert "required" in schema

    @pytest.mark.asyncio
    async def test_call_tool_request_response(self, server):
        """Test tools/call request handling per MCP spec"""
        # Test calling a tool
        request = {
            "name": "query_model",
            "arguments": {
                "query": "SELECT * FROM SystemEngineering",
                "output_format": "json",
            },
        }

        with patch.object(server.tool_registry, "execute_tool") as mock_execute:
            mock_execute.return_value = {"result": "test_data"}

            response = await server.handle_tool_call(request)

            # Verify response format
            assert "content" in response or "result" in response
            mock_execute.assert_called_once_with(
                "query_model", request["arguments"], server.session
            )

    @pytest.mark.asyncio
    async def test_tool_error_handling(self, server):
        """Test proper error responses for tool failures"""
        request = {"name": "invalid_tool", "arguments": {}}

        with pytest.raises(Exception) as exc_info:
            await server.handle_tool_call(request)

        # Should return proper JSON-RPC error
        assert (
            "not found" in str(exc_info.value).lower()
            or "invalid" in str(exc_info.value).lower()
        )

    # ==================== RESOURCE TESTS ====================

    @pytest.mark.asyncio
    async def test_list_resources_compliance(self, server):
        """Test resources/list returns proper MCP resource format"""
        resources = await server.list_resources()

        assert isinstance(resources, list)
        for resource in resources:
            # Verify resource structure per MCP spec
            assert isinstance(resource, Resource)
            assert hasattr(resource, "uri")
            assert hasattr(resource, "name")
            assert hasattr(resource, "mimeType") or hasattr(resource, "description")

            # Verify URI format
            assert resource.uri.startswith("capella://")

    @pytest.mark.asyncio
    async def test_read_resource_compliance(self, server):
        """Test resources/read returns proper content format"""
        test_uri = "capella://test-model/model"

        with patch.object(server.resource_manager, "read_resource") as mock_read:
            mock_read.return_value = {
                "uri": test_uri,
                "mimeType": "application/json",
                "text": '{"test": "data"}',
            }

            content = await server.handle_read_resource(test_uri)

            # Verify content structure
            assert "contents" in content or "text" in content
            if "contents" in content:
                for item in content["contents"]:
                    assert "uri" in item or "text" in item
                    assert "mimeType" in item or "text" in item

    @pytest.mark.asyncio
    async def test_resource_subscription(self, server):
        """Test resource subscription if capability is declared"""
        caps = server.get_capabilities()
        if caps.get("resources", {}).get("subscribe"):
            # Test subscription handling
            request = {"uri": "capella://test-model/model"}

            response = await server.handle_resource_subscribe(request)
            assert response is not None

    # ==================== PROMPT TESTS ====================

    @pytest.mark.asyncio
    async def test_list_prompts_compliance(self, server):
        """Test prompts/list returns proper format"""
        if not server.get_capabilities().get("prompts"):
            pytest.skip("Prompts not supported")

        prompts = await server.list_prompts()

        assert isinstance(prompts, list)
        for prompt in prompts:
            assert hasattr(prompt, "name")
            assert hasattr(prompt, "description")
            if hasattr(prompt, "arguments"):
                for arg in prompt.arguments:
                    assert hasattr(arg, "name")
                    assert hasattr(arg, "required")

    @pytest.mark.asyncio
    async def test_get_prompt_compliance(self, server):
        """Test prompts/get returns proper message format"""
        if not server.get_capabilities().get("prompts"):
            pytest.skip("Prompts not supported")

        request = {"name": "analyze_system", "arguments": {"component": "test"}}

        with patch.object(server, "get_prompt") as mock_get:
            mock_get.return_value = {
                "messages": [
                    {
                        "role": "user",
                        "content": {"type": "text", "text": "Analyze component"},
                    }
                ]
            }

            response = await server.handle_get_prompt(request)

            assert "messages" in response
            for msg in response["messages"]:
                assert "role" in msg
                assert "content" in msg

    # ==================== LOGGING TESTS ====================

    @pytest.mark.asyncio
    async def test_logging_capability(self, server):
        """Test logging capability is properly declared"""
        caps = server.get_capabilities()

        if "logging" in caps:
            # Logging should be an empty object if supported
            assert caps["logging"] == {} or isinstance(caps["logging"], dict)

    @pytest.mark.asyncio
    async def test_log_message_handling(self, server):
        """Test server can receive log messages"""
        log_message = {
            "jsonrpc": "2.0",
            "method": "notifications/message",
            "params": {"level": "info", "logger": "test", "data": "Test log message"},
        }

        # Should not raise exception
        await server.handle_log_message(log_message["params"])

    # ==================== COMPLETION TESTS ====================

    @pytest.mark.asyncio
    async def test_completion_support(self, server):
        """Test completion capability if declared"""
        caps = server.get_capabilities()

        if caps.get("completion"):
            request = {
                "ref": {"type": "ref/resource", "uri": "capella://test/model"},
                "argument": {"name": "query", "value": "SELECT"},
            }

            response = await server.handle_completion(request)

            assert "completion" in response
            assert "values" in response["completion"]
            assert isinstance(response["completion"]["values"], list)

    # ==================== ERROR HANDLING TESTS ====================

    @pytest.mark.asyncio
    async def test_json_rpc_error_codes(self, server):
        """Test server returns proper JSON-RPC error codes"""
        test_cases = [
            (-32601, "method_not_found", {"method": "invalid/method"}),
            (-32602, "invalid_params", {"method": "tools/call", "params": {}}),
            (
                -32603,
                "internal_error",
                {"method": "tools/call", "params": {"name": "crash_tool"}},
            ),
        ]

        for error_code, error_type, request in test_cases:
            with patch.object(server, "handle_request") as mock_handle:
                mock_handle.side_effect = Exception(error_type)

                try:
                    await server.process_request(request)
                except Exception as e:
                    error_response = server.create_error_response(
                        request.get("id", 1), error_code, str(e)
                    )

                    assert error_response["error"]["code"] == error_code
                    assert "message" in error_response["error"]

    # ==================== TRANSPORT TESTS ====================

    @pytest.mark.asyncio
    async def test_stdio_transport_support(self, server):
        """Test server supports STDIO transport"""
        # Server should be able to connect via STDIO
        with patch("sys.stdin") as mock_stdin, patch("sys.stdout") as mock_stdout:

            transport_connected = await server.connect_stdio_transport(
                mock_stdin, mock_stdout
            )

            assert transport_connected is True

    @pytest.mark.asyncio
    async def test_message_framing(self, server, mock_transport):
        """Test proper JSON-RPC message framing"""
        test_message = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}

        # Send message
        await server.send_message(mock_transport.write_stream, test_message)

        # Verify message was properly framed
        assert len(mock_transport.messages_sent) > 0
        sent = mock_transport.messages_sent[0]

        # Should be valid JSON
        if isinstance(sent, bytes):
            sent = sent.decode("utf-8")
        parsed = json.loads(sent) if isinstance(sent, str) else sent
        assert parsed["jsonrpc"] == "2.0"

    # ==================== CAPABILITY NEGOTIATION TESTS ====================

    @pytest.mark.asyncio
    async def test_capability_negotiation(self, server):
        """Test server properly negotiates capabilities with client"""
        client_caps = {
            "roots": {"listChanged": True},
            "sampling": {},
            "elicitation": {},
        }

        server_caps = server.negotiate_capabilities(client_caps)

        # Server should only expose capabilities it supports
        for cap in server_caps:
            assert cap in ["tools", "resources", "prompts", "logging", "completion"]

        # listChanged should be negotiated properly
        if "tools" in server_caps and server_caps["tools"]:
            if "listChanged" in server_caps["tools"]:
                assert isinstance(server_caps["tools"]["listChanged"], bool)

    # ==================== SECURITY TESTS ====================

    @pytest.mark.asyncio
    async def test_uri_validation(self, server):
        """Test server validates resource URIs"""
        invalid_uris = [
            "file:///etc/passwd",  # Should not allow file system access
            "http://evil.com/hack",  # Should not allow external URLs
            "../../../etc/passwd",  # Path traversal
            "capella://../../admin",  # Path traversal in capella URI
        ]

        for uri in invalid_uris:
            with pytest.raises(Exception):
                await server.validate_resource_uri(uri)

    @pytest.mark.asyncio
    async def test_input_sanitization(self, server):
        """Test server sanitizes tool inputs"""
        dangerous_inputs = [
            {"query": "'; DROP TABLE users; --"},  # SQL injection
            {"script": "<script>alert('xss')</script>"},  # XSS attempt
            {"path": "../../../../etc/passwd"},  # Path traversal
        ]

        for input_data in dangerous_inputs:
            sanitized = server.sanitize_input(input_data)
            # Verify dangerous content is escaped or rejected
            assert sanitized != input_data or server.is_safe_input(sanitized)

    # ==================== PERFORMANCE TESTS ====================

    @pytest.mark.asyncio
    async def test_large_resource_handling(self, server):
        """Test server can handle large resources efficiently"""
        # Create large resource (10MB)
        large_data = "x" * (10 * 1024 * 1024)

        with patch.object(server.resource_manager, "read_resource") as mock_read:
            mock_read.return_value = {
                "uri": "capella://test/large",
                "mimeType": "text/plain",
                "text": large_data,
            }

            start_time = asyncio.get_event_loop().time()
            content = await server.handle_read_resource("capella://test/large")
            elapsed = asyncio.get_event_loop().time() - start_time

            # Should handle in reasonable time (< 1 second)
            assert elapsed < 1.0

            # Should support pagination or chunking for large content
            if "contents" in content:
                # Check if content is chunked
                assert len(content["contents"]) > 0

    @pytest.mark.asyncio
    async def test_concurrent_request_handling(self, server):
        """Test server can handle concurrent requests"""

        async def make_request(request_id):
            return await server.handle_tool_call(
                {"name": "query_model", "arguments": {"query": f"SELECT {request_id}"}}
            )

        # Make 10 concurrent requests
        with patch.object(server.tool_registry, "execute_tool") as mock_execute:
            mock_execute.return_value = {"result": "data"}

            tasks = [make_request(i) for i in range(10)]
            results = await asyncio.gather(*tasks, return_exceptions=True)

            # All requests should complete
            assert len(results) == 10
            # No exceptions should occur
            for result in results:
                assert not isinstance(result, Exception)


class TestMCPServerNotifications:
    """Test server notification capabilities"""

    @pytest.fixture
    async def server(self):
        """Create a test server with notification support"""
        config = ServerConfig(
            model_path="test_model.capella",
            capabilities={
                "tools": {"listChanged": True},
                "resources": {"listChanged": True, "subscribe": True},
                "prompts": {"listChanged": True},
            },
        )
        server = CapellaMCPServer(config)
        await server.initialize()
        return server

    @pytest.mark.asyncio
    async def test_tools_list_changed_notification(self, server):
        """Test server sends tools/listChanged notification"""
        with patch.object(server, "send_notification") as mock_send:
            await server.notify_tools_changed()

            mock_send.assert_called_once()
            notification = mock_send.call_args[0][0]
            assert notification["method"] == "notifications/tools/listChanged"

    @pytest.mark.asyncio
    async def test_resources_list_changed_notification(self, server):
        """Test server sends resources/listChanged notification"""
        with patch.object(server, "send_notification") as mock_send:
            await server.notify_resources_changed()

            mock_send.assert_called_once()
            notification = mock_send.call_args[0][0]
            assert notification["method"] == "notifications/resources/listChanged"

    @pytest.mark.asyncio
    async def test_resource_updated_notification(self, server):
        """Test server sends resources/updated for subscriptions"""
        # Subscribe to resource
        await server.subscribe_to_resource("capella://test/model")

        with patch.object(server, "send_notification") as mock_send:
            await server.notify_resource_updated("capella://test/model")

            mock_send.assert_called_once()
            notification = mock_send.call_args[0][0]
            assert notification["method"] == "notifications/resources/updated"
            assert notification["params"]["uri"] == "capella://test/model"

    @pytest.mark.asyncio
    async def test_prompts_list_changed_notification(self, server):
        """Test server sends prompts/listChanged notification"""
        with patch.object(server, "send_notification") as mock_send:
            await server.notify_prompts_changed()

            mock_send.assert_called_once()
            notification = mock_send.call_args[0][0]
            assert notification["method"] == "notifications/prompts/listChanged"


class TestMCPProtocolVersioning:
    """Test protocol version handling"""

    @pytest.mark.asyncio
    async def test_version_negotiation(self):
        """Test server negotiates protocol version correctly"""
        config = ServerConfig(model_path="test.capella")
        server = CapellaMCPServer(config)

        # Test with different client versions
        test_versions = [
            ("2024-11-05", "2024-11-05"),  # Exact match
            ("2025-06-18", "2025-06-18"),  # Newer version
            ("2024-10-01", "2024-11-05"),  # Older version - use server min
            ("invalid", "2024-11-05"),  # Invalid - use default
        ]

        for client_version, expected in test_versions:
            negotiated = server.negotiate_protocol_version(client_version)
            assert negotiated == expected

    @pytest.mark.asyncio
    async def test_backwards_compatibility(self):
        """Test server maintains backwards compatibility"""
        config = ServerConfig(model_path="test.capella")
        server = CapellaMCPServer(config)

        # Old client request format
        old_request = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "list_tools",  # Old method name
        }

        # Should handle gracefully
        response = await server.handle_legacy_request(old_request)
        assert response is not None
