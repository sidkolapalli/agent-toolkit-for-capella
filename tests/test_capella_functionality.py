"""
Comprehensive Capella-specific Functionality Test Suite

Tests the Capella model integration, query capabilities, and domain-specific features
of the Capella MCP Server.
"""

import pytest
import json
from unittest.mock import patch, mock_open

from capella_mcp.server import CapellaMCPServer, ServerConfig
from capella_mcp.model_manager import ModelManager
from capella_mcp.context_manager import ContextManager
from capella_mcp.tools import ToolRegistry
from capella_mcp.resources import ResourceManager
from capella_mcp.transaction_manager import TransactionManager


class TestCapellaModelIntegration:
    """Test Capella model loading and management"""

    @pytest.fixture
    async def model_manager(self):
        """Create a model manager instance"""
        manager = ModelManager()
        await manager.initialize()
        return manager

    @pytest.fixture
    def sample_capella_model(self):
        """Create a sample Capella model structure"""
        return {
            "id": "test-model",
            "name": "Test System Model",
            "version": "1.0.0",
            "architecture": {
                "operational": {
                    "entities": [
                        {
                            "id": "oe-1",
                            "name": "Operator",
                            "type": "OperationalEntity",
                            "activities": ["oa-1", "oa-2"],
                        }
                    ],
                    "activities": [
                        {
                            "id": "oa-1",
                            "name": "Monitor System",
                            "type": "OperationalActivity",
                        },
                        {
                            "id": "oa-2",
                            "name": "Control System",
                            "type": "OperationalActivity",
                        },
                    ],
                },
                "system": {
                    "components": [
                        {
                            "id": "sc-1",
                            "name": "Control Unit",
                            "type": "SystemComponent",
                            "functions": ["sf-1"],
                        }
                    ],
                    "functions": [
                        {
                            "id": "sf-1",
                            "name": "Process Data",
                            "type": "SystemFunction",
                            "inputs": ["data-raw"],
                            "outputs": ["data-processed"],
                        }
                    ],
                },
                "logical": {
                    "components": [
                        {
                            "id": "lc-1",
                            "name": "Data Processor",
                            "type": "LogicalComponent",
                            "interfaces": ["li-1"],
                        }
                    ],
                    "interfaces": [
                        {
                            "id": "li-1",
                            "name": "Data Interface",
                            "type": "LogicalInterface",
                            "protocol": "REST",
                        }
                    ],
                },
                "physical": {
                    "nodes": [
                        {
                            "id": "pn-1",
                            "name": "Server Node",
                            "type": "PhysicalNode",
                            "components": ["pc-1"],
                        }
                    ],
                    "components": [
                        {
                            "id": "pc-1",
                            "name": "Application Server",
                            "type": "PhysicalComponent",
                            "deployment": "Docker",
                        }
                    ],
                },
            },
        }

    @pytest.mark.asyncio
    async def test_load_capella_model(self, model_manager, sample_capella_model):
        """Test loading a Capella model file"""
        with patch(
            "builtins.open", mock_open(read_data=json.dumps(sample_capella_model))
        ):
            model = await model_manager.load_model("test.capella")

            assert model is not None
            assert model.id == "test-model"
            assert model.name == "Test System Model"
            assert hasattr(model, "architecture")

    @pytest.mark.asyncio
    async def test_model_validation(self, model_manager, sample_capella_model):
        """Test Capella model validation"""
        # Valid model should pass
        is_valid = model_manager.validate_model(sample_capella_model)
        assert is_valid is True

        # Invalid model should fail
        invalid_model = {"invalid": "structure"}
        is_valid = model_manager.validate_model(invalid_model)
        assert is_valid is False

    @pytest.mark.asyncio
    async def test_model_caching(self, model_manager):
        """Test model caching for performance"""
        with patch("builtins.open", mock_open(read_data="{}")):
            # First load
            model1 = await model_manager.load_model("test.capella")

            # Second load should use cache
            with patch("builtins.open") as mock_file:
                model2 = await model_manager.load_model("test.capella")
                # File should not be opened again
                mock_file.assert_not_called()

            assert model1 is model2  # Same instance

    @pytest.mark.asyncio
    async def test_multiple_model_management(self, model_manager):
        """Test managing multiple Capella models"""
        models = [
            {"id": "model1", "name": "Model 1"},
            {"id": "model2", "name": "Model 2"},
            {"id": "model3", "name": "Model 3"},
        ]

        for model_data in models:
            with patch("builtins.open", mock_open(read_data=json.dumps(model_data))):
                await model_manager.load_model(f"{model_data['id']}.capella")

        # All models should be loaded
        assert len(model_manager.list_models()) == 3

        # Should be able to retrieve specific model
        model = model_manager.get_model("model2")
        assert model is not None
        assert model.name == "Model 2"


class TestCapellaQueryEngine:
    """Test Capella model querying capabilities"""

    @pytest.fixture
    def query_engine(self, sample_capella_model):
        """Create a query engine with sample model"""
        # Mock query engine since CapellaQueryEngine is not implemented yet
        from unittest.mock import MagicMock

        engine = MagicMock()
        engine.load_model(sample_capella_model)
        return engine

    @pytest.mark.asyncio
    async def test_basic_element_query(self, query_engine):
        """Test basic element querying"""
        # Query all components
        query = "SELECT * FROM SystemComponent"
        results = await query_engine.execute_query(query)

        assert len(results) == 1
        assert results[0]["name"] == "Control Unit"
        assert results[0]["type"] == "SystemComponent"

    @pytest.mark.asyncio
    async def test_filtered_query(self, query_engine):
        """Test querying with filters"""
        # Query specific element by ID
        query = "SELECT * FROM OperationalActivity WHERE id = 'oa-1'"
        results = await query_engine.execute_query(query)

        assert len(results) == 1
        assert results[0]["name"] == "Monitor System"

    @pytest.mark.asyncio
    async def test_cross_layer_query(self, query_engine):
        """Test querying across architecture layers"""
        # Query elements from multiple layers
        query = """
        SELECT 
            system.name as system_component,
            logical.name as logical_component
        FROM SystemComponent system
        JOIN LogicalComponent logical ON system.realizes = logical.id
        """

        results = await query_engine.execute_query(query)
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_relationship_query(self, query_engine):
        """Test querying relationships between elements"""
        query = """
        SELECT 
            activity.name as activity,
            entity.name as performer
        FROM OperationalActivity activity
        JOIN OperationalEntity entity ON activity.performer = entity.id
        """

        results = await query_engine.execute_query(query)
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_aggregation_query(self, query_engine):
        """Test aggregation queries"""
        queries = [
            "SELECT COUNT(*) as total FROM SystemComponent",
            "SELECT layer, COUNT(*) as count FROM * GROUP BY layer",
            "SELECT MAX(complexity) as max_complexity FROM SystemFunction",
        ]

        for query in queries:
            results = await query_engine.execute_query(query)
            assert results is not None

    @pytest.mark.asyncio
    async def test_invalid_query_handling(self, query_engine):
        """Test handling of invalid queries"""
        invalid_queries = [
            "INVALID SYNTAX",
            "SELECT * FROM NonExistentElement",
            "SELECT FROM",  # Missing fields
            "DROP TABLE SystemComponent",  # Dangerous operation
        ]

        for query in invalid_queries:
            with pytest.raises(Exception):
                await query_engine.execute_query(query)


class TestCapellaTools:
    """Test Capella-specific tools"""

    @pytest.fixture
    def tool_registry(self):
        """Create tool registry with Capella tools"""
        registry = ToolRegistry()
        registry.initialize_capella_tools()
        return registry

    @pytest.mark.asyncio
    async def test_analyze_architecture_tool(self, tool_registry):
        """Test architecture analysis tool"""
        tool = tool_registry.get_tool("analyze_architecture")
        assert tool is not None

        result = await tool.execute(
            {"model_id": "test-model", "analysis_type": "complexity"}
        )

        assert "complexity_score" in result
        assert "recommendations" in result

    @pytest.mark.asyncio
    async def test_validate_model_tool(self, tool_registry):
        """Test model validation tool"""
        tool = tool_registry.get_tool("validate_model")
        assert tool is not None

        result = await tool.execute(
            {
                "model_id": "test-model",
                "validation_rules": ["completeness", "consistency"],
            }
        )

        assert "is_valid" in result
        assert "violations" in result

    @pytest.mark.asyncio
    async def test_generate_diagram_tool(self, tool_registry):
        """Test diagram generation tool"""
        tool = tool_registry.get_tool("generate_diagram")
        assert tool is not None

        result = await tool.execute(
            {
                "model_id": "test-model",
                "diagram_type": "operational_architecture",
                "format": "svg",
            }
        )

        assert "diagram_data" in result or "diagram_url" in result

    @pytest.mark.asyncio
    async def test_impact_analysis_tool(self, tool_registry):
        """Test impact analysis tool"""
        tool = tool_registry.get_tool("analyze_impact")
        assert tool is not None

        result = await tool.execute(
            {"model_id": "test-model", "element_id": "sc-1", "change_type": "remove"}
        )

        assert "impacted_elements" in result
        assert "severity" in result

    @pytest.mark.asyncio
    async def test_export_tool(self, tool_registry):
        """Test model export tool"""
        tool = tool_registry.get_tool("export_model")
        assert tool is not None

        formats = ["json", "xml", "yaml", "csv"]
        for format_type in formats:
            result = await tool.execute(
                {
                    "model_id": "test-model",
                    "format": format_type,
                    "include_diagrams": False,
                }
            )

            assert "exported_data" in result or "export_path" in result


class TestCapellaResources:
    """Test Capella resource management"""

    @pytest.fixture
    def resource_manager(self):
        """Create resource manager"""
        manager = ResourceManager()
        manager.initialize()
        return manager

    @pytest.mark.asyncio
    async def test_model_resource_exposure(self, resource_manager):
        """Test exposing Capella models as resources"""
        resources = await resource_manager.list_resources()

        # Should expose model resources
        model_resources = [r for r in resources if r.uri.startswith("capella://")]
        assert len(model_resources) > 0

        for resource in model_resources:
            assert resource.mimeType in [
                "application/capella+json",
                "application/capella+xml",
            ]

    @pytest.mark.asyncio
    async def test_architecture_layer_resources(self, resource_manager):
        """Test architecture layer resources"""
        layer_uris = [
            "capella://model/operational",
            "capella://model/system",
            "capella://model/logical",
            "capella://model/physical",
        ]

        for uri in layer_uris:
            content = await resource_manager.read_resource(uri)
            assert content is not None
            assert "elements" in content or "components" in content

    @pytest.mark.asyncio
    async def test_element_resource_access(self, resource_manager):
        """Test individual element resource access"""
        element_uri = "capella://model/element/sc-1"

        content = await resource_manager.read_resource(element_uri)
        assert content is not None
        assert content.get("id") == "sc-1"
        assert content.get("type") == "SystemComponent"

    @pytest.mark.asyncio
    async def test_diagram_resources(self, resource_manager):
        """Test diagram resources"""
        diagram_uri = "capella://model/diagrams/operational_architecture"

        content = await resource_manager.read_resource(diagram_uri)
        assert content is not None
        assert "diagram_type" in content
        assert "elements" in content or "svg_data" in content


class TestCapellaTransactions:
    """Test transaction support for Capella model modifications"""

    @pytest.fixture
    def transaction_manager(self):
        """Create transaction manager"""
        manager = TransactionManager()
        return manager

    @pytest.mark.asyncio
    async def test_model_modification_transaction(self, transaction_manager):
        """Test transactional model modifications"""
        session_id = "test-session"
        model_id = "test-model"

        # Begin transaction
        tx = await transaction_manager.begin_transaction(session_id, model_id)
        assert tx is not None

        # Make modifications
        modifications = [
            {"action": "add", "element": {"id": "new-1", "type": "SystemComponent"}},
            {
                "action": "update",
                "element_id": "sc-1",
                "properties": {"name": "Updated"},
            },
            {"action": "delete", "element_id": "oa-2"},
        ]

        for mod in modifications:
            await transaction_manager.apply_modification(tx.id, mod)

        # Commit transaction
        await transaction_manager.commit_transaction(tx.id)

        # Verify modifications were applied
        assert transaction_manager.get_transaction(tx.id).status == "committed"

    @pytest.mark.asyncio
    async def test_transaction_rollback(self, transaction_manager):
        """Test transaction rollback on error"""
        session_id = "test-session"
        model_id = "test-model"

        tx = await transaction_manager.begin_transaction(session_id, model_id)

        # Apply some modifications
        await transaction_manager.apply_modification(
            tx.id, {"action": "add", "element": {"id": "new-1"}}
        )

        # Rollback
        await transaction_manager.rollback_transaction(tx.id)

        # Verify rollback
        assert transaction_manager.get_transaction(tx.id).status == "rolled_back"

    @pytest.mark.asyncio
    async def test_concurrent_transaction_isolation(self, transaction_manager):
        """Test isolation between concurrent transactions"""
        model_id = "test-model"

        # Start two transactions
        tx1 = await transaction_manager.begin_transaction("session-1", model_id)
        tx2 = await transaction_manager.begin_transaction("session-2", model_id)

        # Modifications in tx1 shouldn't affect tx2
        await transaction_manager.apply_modification(
            tx1.id,
            {"action": "update", "element_id": "sc-1", "properties": {"name": "TX1"}},
        )

        await transaction_manager.apply_modification(
            tx2.id,
            {"action": "update", "element_id": "sc-1", "properties": {"name": "TX2"}},
        )

        # Commit tx1
        await transaction_manager.commit_transaction(tx1.id)

        # tx2 should detect conflict
        with pytest.raises(Exception) as exc_info:
            await transaction_manager.commit_transaction(tx2.id)

        assert "conflict" in str(exc_info.value).lower()


class TestCapellaContextOptimization:
    """Test context optimization for AI interactions"""

    @pytest.fixture
    def context_manager(self):
        """Create context manager"""
        manager = ContextManager()
        return manager

    @pytest.mark.asyncio
    async def test_context_reduction(self, context_manager, sample_capella_model):
        """Test reducing model context for AI consumption"""
        # Large model should be reduced
        context = await context_manager.optimize_context(
            sample_capella_model, max_tokens=1000
        )

        assert context is not None
        assert len(json.dumps(context)) < len(json.dumps(sample_capella_model))

        # Should preserve essential information
        assert "architecture" in context
        assert any(
            layer in context["architecture"]
            for layer in ["operational", "system", "logical", "physical"]
        )

    @pytest.mark.asyncio
    async def test_focused_context(self, context_manager, sample_capella_model):
        """Test creating focused context for specific queries"""
        # Focus on operational layer
        context = await context_manager.create_focused_context(
            sample_capella_model, focus="operational", max_tokens=500
        )

        assert "operational" in context.get("architecture", {})
        assert len(
            context.get("architecture", {}).get("system", {})
        ) == 0 or "system" not in context.get("architecture", {})

    @pytest.mark.asyncio
    async def test_hierarchical_context(self, context_manager, sample_capella_model):
        """Test hierarchical context generation"""
        hierarchy = await context_manager.create_hierarchical_context(
            sample_capella_model
        )

        assert "summary" in hierarchy
        assert "layers" in hierarchy
        assert len(hierarchy["layers"]) == 4  # 4 architecture layers

        for layer in hierarchy["layers"]:
            assert "name" in layer
            assert "element_count" in layer
            assert "key_elements" in layer


class TestCapellaIntegration:
    """Integration tests for complete Capella MCP server"""

    @pytest.fixture
    async def server(self):
        """Create fully configured server"""
        config = ServerConfig(
            model_path="test.capella",
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

    @pytest.mark.asyncio
    async def test_end_to_end_query_workflow(self, server):
        """Test complete query workflow"""
        # List available tools
        tools = await server.list_tools()
        query_tool = next((t for t in tools if t.name == "query_model"), None)
        assert query_tool is not None

        # Execute query
        result = await server.call_tool(
            "query_model",
            {"query": "SELECT * FROM SystemComponent", "output_format": "json"},
        )

        assert result is not None
        assert "results" in result or "data" in result

    @pytest.mark.asyncio
    async def test_model_analysis_workflow(self, server):
        """Test model analysis workflow"""
        # Get model resource
        resources = await server.list_resources()
        model_resource = next((r for r in resources if "model" in r.uri), None)
        assert model_resource is not None

        # Read model
        model_content = await server.read_resource(model_resource.uri)
        assert model_content is not None

        # Analyze model
        analysis_result = await server.call_tool(
            "analyze_architecture",
            {"model_id": "test-model", "analysis_type": "comprehensive"},
        )

        assert analysis_result is not None
        assert "analysis" in analysis_result or "results" in analysis_result

    @pytest.mark.asyncio
    async def test_model_modification_workflow(self, server):
        """Test model modification workflow"""
        # Begin transaction
        tx_result = await server.call_tool(
            "begin_transaction", {"model_id": "test-model"}
        )

        tx_id = tx_result.get("transaction_id")
        assert tx_id is not None

        # Add element
        add_result = await server.call_tool(
            "add_element",
            {
                "transaction_id": tx_id,
                "element": {
                    "type": "SystemComponent",
                    "name": "New Component",
                    "properties": {},
                },
            },
        )

        assert add_result.get("success") is True

        # Commit transaction
        commit_result = await server.call_tool(
            "commit_transaction", {"transaction_id": tx_id}
        )

        assert commit_result.get("success") is True

    @pytest.mark.asyncio
    async def test_prompt_based_interaction(self, server):
        """Test prompt-based model interaction"""
        prompts = await server.list_prompts()

        # Should have Capella-specific prompts
        capella_prompts = [
            "analyze_system",
            "explain_architecture",
            "suggest_improvements",
        ]

        prompt_names = [p.name for p in prompts]
        for expected in capella_prompts:
            assert expected in prompt_names

        # Get a prompt
        prompt_result = await server.get_prompt(
            "analyze_system", {"focus": "operational"}
        )

        assert "messages" in prompt_result
        assert len(prompt_result["messages"]) > 0
