"""
Test suite for Stage 7: Advanced Relationship Management tools.

This module tests 8 advanced relationship management tools for managing
exchanges, processes, allocations, and patterns across ARCADIA layers.

Stage 7 Tools:
1. manage_port_connections - Foundation port management
2. manage_function_exchanges - Functional layer connectivity  
3. manage_physical_exchanges - Physical layer connectivity
4. create_allocation_links - Function-component allocation
5. manage_operational_processes - Operational workflows
6. manage_capability_exploitations - Capability-mission links
7. create_exchange_chains - End-to-end traceability
8. analyze_relationship_patterns - Pattern analysis and validation
"""

import pytest
from unittest.mock import Mock, patch, AsyncMock
from typing import List, Dict, Any, Optional
import asyncio

# Test imports
from capella_mcp.server import (
    manage_port_connections,
    manage_function_exchanges,
    manage_physical_exchanges,
    create_allocation_links,
    manage_operational_processes,
    manage_capability_exploitations,
    create_exchange_chains,
    analyze_relationship_patterns
)


class TestStage7RelationshipManagement:
    """Comprehensive test suite for Stage 7 Advanced Relationship Management tools."""

    @pytest.mark.asyncio
    async def test_manage_port_connections_functionality(self):
        """
        Test manage_port_connections tool functionality.
        
        This is the foundation tool for relationship management as ports
        are required for all exchanges. Tests CRUD operations for component
        ports with proper validation and ARCADIA compliance.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for port connection testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock component with ports
            mock_component = Mock()
            mock_component.uuid = "comp-123"
            mock_component.name = "TestComponent"
            mock_component.ports = []
            
            # Mock port objects
            mock_port1 = Mock()
            mock_port1.uuid = "port-456"
            mock_port1.name = "InputPort"
            mock_port1.direction = "IN"
            mock_port1.exchanges = []
            
            mock_port2 = Mock()
            mock_port2.uuid = "port-789"
            mock_port2.name = "OutputPort"
            mock_port2.direction = "OUT"
            mock_port2.exchanges = []
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_component]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "comp-123": mock_component,
                "port-456": mock_port1,
                "port-789": mock_port2
            }.get(uuid)
            
            # Mock port creation
            mock_model.create_port = Mock(return_value=mock_port1)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test port creation
                result = await manage_port_connections(
                    model_id="test_model_stage7",
                    operation="create",
                    port_spec={
                        "component_id": "comp-123",
                        "name": "TestInputPort",
                        "direction": "IN",
                        "port_type": "ComponentPort",
                        "exchange_items": ["data", "signal"]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "port_id" in result
                assert result["component_id"] == "comp-123"
                assert result["port_created"] is True
                
                # Test port connection
                result = await manage_port_connections(
                    model_id="test_model_stage7",
                    operation="connect",
                    connection_spec={
                        "source_port_id": "port-456",
                        "target_port_id": "port-789",
                        "exchange_type": "ComponentExchange",
                        "name": "TestConnection"
                    }
                )
                
                # Validate connection creation
                assert result["success"] is True
                assert result["operation"] == "connect"
                assert "exchange_id" in result
                assert result["ports_connected"] is True
                
                # Test port listing
                result = await manage_port_connections(
                    model_id="test_model_stage7",
                    operation="list",
                    filter_spec={
                        "component_id": "comp-123",
                        "direction": "IN"
                    }
                )
                
                # Validate port listing
                assert result["success"] is True
                assert result["operation"] == "list"
                assert "ports" in result
                assert result["total_count"] >= 0

    @pytest.mark.asyncio
    async def test_manage_function_exchanges_functionality(self):
        """
        Test manage_function_exchanges tool functionality.
        
        Tests CRUD operations for functional exchanges between functions
        with proper ARCADIA compliance and port validation.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for function exchange testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock functions with ports
            mock_function1 = Mock()
            mock_function1.uuid = "func-123"
            mock_function1.name = "SourceFunction"
            mock_function1.outputs = []
            
            mock_function2 = Mock()
            mock_function2.uuid = "func-456"
            mock_function2.name = "TargetFunction"
            mock_function2.inputs = []
            
            # Mock functional exchange
            mock_exchange = Mock()
            mock_exchange.uuid = "exchange-789"
            mock_exchange.name = "DataExchange"
            mock_exchange.source = mock_function1
            mock_exchange.target = mock_function2
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_function1, mock_function2]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "func-123": mock_function1,
                "func-456": mock_function2,
                "exchange-789": mock_exchange
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test functional exchange creation
                result = await manage_function_exchanges(
                    model_id="test_model_stage7",
                    operation="create",
                    exchange_spec={
                        "name": "TestFunctionalExchange",
                        "source_function_id": "func-123",
                        "target_function_id": "func-456",
                        "exchange_items": ["data", "signal"],
                        "description": "Test exchange between functions"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "exchange_id" in result
                assert result["source_function_id"] == "func-123"
                assert result["target_function_id"] == "func-456"
                assert result["exchange_created"] is True

    @pytest.mark.asyncio
    async def test_manage_physical_exchanges_functionality(self):
        """
        Test manage_physical_exchanges tool functionality.
        
        Tests CRUD operations for physical exchanges (PhysicalLink objects)
        between physical components with port validation.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for physical exchange testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock physical components
            mock_component1 = Mock()
            mock_component1.uuid = "phys-123"
            mock_component1.name = "PhysicalComponent1"
            mock_component1.physical_ports = []
            
            mock_component2 = Mock()
            mock_component2.uuid = "phys-456"
            mock_component2.name = "PhysicalComponent2"
            mock_component2.physical_ports = []
            
            # Mock physical link
            mock_physical_link = Mock()
            mock_physical_link.uuid = "link-789"
            mock_physical_link.name = "PhysicalDataLink"
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_component1, mock_component2]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "phys-123": mock_component1,
                "phys-456": mock_component2,
                "link-789": mock_physical_link
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test physical exchange creation
                result = await manage_physical_exchanges(
                    model_id="test_model_stage7",
                    operation="create",
                    exchange_spec={
                        "name": "TestPhysicalLink",
                        "source_component_id": "phys-123",
                        "target_component_id": "phys-456",
                        "link_type": "DataBus",
                        "protocol": "CAN",
                        "properties": {"bandwidth": "1Mbps"}
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "link_id" in result
                assert result["source_component_id"] == "phys-123"
                assert result["target_component_id"] == "phys-456"
                assert result["link_created"] is True

    @pytest.mark.asyncio
    async def test_create_allocation_links_functionality(self):
        """
        Test create_allocation_links tool functionality.
        
        Tests function-to-component allocation with ARCADIA rule validation
        (leaf functions only). Critical for maintaining model consistency.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for allocation testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock leaf function (no sub-functions)
            mock_function = Mock()
            mock_function.uuid = "func-123"
            mock_function.name = "LeafFunction"
            mock_function.functions = []  # Empty = leaf function
            mock_function.allocated_to = None
            
            # Mock component
            mock_component = Mock()
            mock_component.uuid = "comp-456"
            mock_component.name = "TargetComponent"
            mock_component.allocated_functions = []
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_function, mock_component]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "func-123": mock_function,
                "comp-456": mock_component
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test allocation creation
                result = await create_allocation_links(
                    model_id="test_model_stage7",
                    operation="allocate",
                    allocation_spec={
                        "function_id": "func-123",
                        "component_id": "comp-456",
                        "allocation_type": "realizes",
                        "validate_leaf": True
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "allocate"
                assert result["function_id"] == "func-123"
                assert result["component_id"] == "comp-456"
                assert result["allocation_created"] is True
                assert result["leaf_function_validated"] is True

    @pytest.mark.asyncio
    async def test_manage_operational_processes_functionality(self):
        """
        Test manage_operational_processes tool functionality.
        
        Tests CRUD operations for operational processes in OA layer with
        capability realization and activity sequencing.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for operational process testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock operational process
            mock_process = Mock()
            mock_process.uuid = "process-123"
            mock_process.name = "TestOperationalProcess"
            mock_process.activities = []
            
            # Mock operational activities
            mock_activity1 = Mock()
            mock_activity1.uuid = "activity-456"
            mock_activity1.name = "StartActivity"
            
            mock_activity2 = Mock()
            mock_activity2.uuid = "activity-789"
            mock_activity2.name = "EndActivity"
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_process, mock_activity1, mock_activity2]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "process-123": mock_process,
                "activity-456": mock_activity1,
                "activity-789": mock_activity2
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test operational process creation
                result = await manage_operational_processes(
                    model_id="test_model_stage7",
                    operation="create",
                    process_spec={
                        "name": "TestProcess",
                        "description": "Test operational process",
                        "activities": ["activity-456", "activity-789"],
                        "sequence": [
                            {"from": "activity-456", "to": "activity-789"}
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "process_id" in result
                assert result["activities_linked"] is True
                assert result["process_created"] is True

    @pytest.mark.asyncio
    async def test_manage_capability_exploitations_functionality(self):
        """
        Test manage_capability_exploitations tool functionality.
        
        Tests capability-mission linking in OA layer with hierarchy validation
        and capability realization patterns.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for capability exploitation testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock operational capability
            mock_capability = Mock()
            mock_capability.uuid = "cap-123"
            mock_capability.name = "TestCapability"
            mock_capability.exploiting_missions = []
            
            # Mock mission
            mock_mission = Mock()
            mock_mission.uuid = "mission-456"
            mock_mission.name = "TestMission"
            mock_mission.exploited_capabilities = []
            
            # Mock actor
            mock_actor = Mock()
            mock_actor.uuid = "actor-789"
            mock_actor.name = "TestActor"
            mock_actor.capabilities = []
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_capability, mock_mission, mock_actor]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "cap-123": mock_capability,
                "mission-456": mock_mission,
                "actor-789": mock_actor
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test capability exploitation creation
                result = await manage_capability_exploitations(
                    model_id="test_model_stage7",
                    operation="link",
                    exploitation_spec={
                        "capability_id": "cap-123",
                        "mission_id": "mission-456",
                        "actor_id": "actor-789",
                        "exploitation_type": "realizes"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "link"
                assert result["capability_id"] == "cap-123"
                assert result["mission_id"] == "mission-456"
                assert result["actor_id"] == "actor-789"
                assert result["exploitation_created"] is True

    @pytest.mark.asyncio
    async def test_create_exchange_chains_functionality(self):
        """
        Test create_exchange_chains tool functionality.
        
        Tests end-to-end exchange traceability across ARCADIA layers with
        layer transition validation and bidirectional traceability.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for exchange chain testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock exchanges at different layers
            mock_oa_exchange = Mock()
            mock_oa_exchange.uuid = "oa-exchange-123"
            mock_oa_exchange.layer = "OA"
            
            mock_sa_exchange = Mock()
            mock_sa_exchange.uuid = "sa-exchange-456"
            mock_sa_exchange.layer = "SA"
            
            mock_la_exchange = Mock()
            mock_la_exchange.uuid = "la-exchange-789"
            mock_la_exchange.layer = "LA"
            
            mock_pa_exchange = Mock()
            mock_pa_exchange.uuid = "pa-exchange-012"
            mock_pa_exchange.layer = "PA"
            
            # Mock capellambse collections
            mock_model.search.return_value = [mock_oa_exchange, mock_sa_exchange, mock_la_exchange, mock_pa_exchange]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "oa-exchange-123": mock_oa_exchange,
                "sa-exchange-456": mock_sa_exchange,
                "la-exchange-789": mock_la_exchange,
                "pa-exchange-012": mock_pa_exchange
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test exchange chain creation
                result = await create_exchange_chains(
                    model_id="test_model_stage7",
                    operation="create",
                    chain_spec={
                        "name": "TestExchangeChain",
                        "exchanges": [
                            "oa-exchange-123",
                            "sa-exchange-456", 
                            "la-exchange-789",
                            "pa-exchange-012"
                        ],
                        "validate_layers": True,
                        "bidirectional": True
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "chain_id" in result
                assert result["exchanges_linked"] is True
                assert result["layer_transitions_valid"] is True
                assert result["bidirectional_traceability"] is True

    @pytest.mark.asyncio
    async def test_analyze_relationship_patterns_functionality(self):
        """
        Test analyze_relationship_patterns tool functionality.
        
        Tests pattern analysis and validation including ARCADIA compliance,
        orphaned elements detection, and anti-pattern identification.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for pattern analysis
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock various model elements for pattern analysis
            mock_functions = []
            mock_components = []
            mock_exchanges = []
            
            # Create functions first
            for i in range(5):
                mock_func = Mock()
                mock_func.uuid = f"func-{i}"
                mock_func.name = f"Function{i}"
                mock_func.allocated_to = None if i == 4 else f"comp-{i}"  # One orphaned
                mock_functions.append(mock_func)
                
                mock_comp = Mock()
                mock_comp.uuid = f"comp-{i}"
                mock_comp.name = f"Component{i}"
                mock_comp.allocated_functions = [] if i == 4 else [mock_func]  # One orphaned
                mock_components.append(mock_comp)
            
            # Create exchanges after functions are built
            for i in range(4):  # 4 exchanges
                mock_exchange = Mock()
                mock_exchange.uuid = f"exchange-{i}"
                mock_exchange.name = f"Exchange{i}"
                mock_exchange.source = mock_functions[i]
                mock_exchange.target = mock_functions[(i+1) % 4]  # Loop back to start
                mock_exchanges.append(mock_exchange)
            
            # Mock capellambse search results
            mock_model.search.side_effect = lambda element_type: {
                "Function": mock_functions,
                "Component": mock_components,
                "Exchange": mock_exchanges
            }.get(element_type, [])
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test pattern analysis
                result = await analyze_relationship_patterns(
                    model_id="test_model_stage7",
                    analysis_scope="comprehensive",
                    pattern_types=[
                        "orphaned_elements",
                        "allocation_compliance", 
                        "exchange_patterns",
                        "arcadia_compliance"
                    ],
                    include_recommendations=True
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["analysis_scope"] == "comprehensive"
                assert "patterns_detected" in result
                assert "orphaned_elements" in result
                assert "allocation_compliance" in result
                assert "arcadia_violations" in result
                assert "recommendations" in result
                assert result["analysis_complete"] is True

    @pytest.mark.asyncio
    async def test_stage7_error_handling(self):
        """Test comprehensive error handling for all Stage 7 tools."""
        # Test with non-existent model
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model_manager.get_model.side_effect = KeyError("Model not found")
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                result = await manage_port_connections(
                    model_id="nonexistent_model",
                    operation="create",
                    port_spec={"name": "test"}
                )
                
                assert result["success"] is False
                assert "error" in result
                assert "Model not found" in result["error"]

    @pytest.mark.asyncio 
    async def test_stage7_enhanced_disabled(self):
        """Test graceful degradation when enhanced tools are disabled."""
        # Test with ENHANCED_AVAILABLE = False
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', False):
            result = await manage_port_connections(
                model_id="test_model",
                operation="create",
                port_spec={"name": "test"}
            )
            
            assert result["success"] is False
            assert "enhanced relationship management tools not available" in result["error"].lower()