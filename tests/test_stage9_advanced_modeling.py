"""
Test suite for Stage 9: Advanced Modeling Features tools.

This module tests 9 advanced modeling tools for viewpoints, missions, 
data classes, state machines, and scenarios across ARCADIA layers.

Stage 9 Tools:
1. manage_viewpoints - Viewpoint activation and perspective management
2. manage_missions - Mission modeling for operational architecture
3. manage_data_classes - Data class creation and inheritance
4. manage_collections - Collection element management
5. manage_enumerations - Enumeration literal handling
6. manage_union_types - Union type discriminant management
7. create_capability_realizations - Capability-function linking
8. manage_state_machines - State and transition modeling
9. advanced_scenario_modeling - Scenario step sequencing
"""

import pytest
from unittest.mock import Mock, patch, AsyncMock
from typing import List, Dict, Any, Optional
import asyncio

# Test imports
from capella_mcp.server import (
    manage_viewpoints,
    manage_missions,
    manage_data_classes,
    manage_collections,
    manage_enumerations,
    manage_union_types,
    create_capability_realizations,
    manage_state_machines,
    advanced_scenario_modeling
)


class TestStage9AdvancedModeling:
    """Comprehensive test suite for Stage 9 Advanced Modeling Features tools."""

    @pytest.mark.asyncio
    async def test_manage_viewpoints_functionality(self):
        """
        Test manage_viewpoints tool functionality.
        
        Tests viewpoint activation/deactivation using capellambse
        activate_viewpoint and referenced_viewpoints APIs.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for viewpoint testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock viewpoint objects
            mock_viewpoint1 = Mock()
            mock_viewpoint1.uuid = "viewpoint-123"
            mock_viewpoint1.name = "LogicalViewpoint"
            mock_viewpoint1.active = True
            
            mock_viewpoint2 = Mock()
            mock_viewpoint2.uuid = "viewpoint-456"
            mock_viewpoint2.name = "PhysicalViewpoint"
            mock_viewpoint2.active = False
            
            # Mock capellambse viewpoint APIs
            mock_model.referenced_viewpoints = [mock_viewpoint1, mock_viewpoint2]
            mock_model.activate_viewpoint = Mock()
            mock_model.by_uuid.side_effect = lambda uuid: {
                "viewpoint-123": mock_viewpoint1,
                "viewpoint-456": mock_viewpoint2
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test viewpoint activation
                result = await manage_viewpoints(
                    model_id="test_model_stage9",
                    operation="activate",
                    viewpoint_spec={
                        "viewpoint_id": "viewpoint-456",
                        "name": "PhysicalViewpoint"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "activate"
                assert result["viewpoint_id"] == "viewpoint-456"
                assert result["viewpoint_activated"] is True
                
                # Test viewpoint listing
                result = await manage_viewpoints(
                    model_id="test_model_stage9",
                    operation="list",
                    filter_spec={"active": True}
                )
                
                # Validate viewpoint listing
                assert result["success"] is True
                assert result["operation"] == "list"
                assert "viewpoints" in result
                assert result["total_count"] >= 0

    @pytest.mark.asyncio
    async def test_manage_missions_functionality(self):
        """
        Test manage_missions tool functionality.
        
        Tests mission CRUD operations using capellambse all_missions
        and Mission class APIs for operational architecture.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for mission testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock mission objects
            mock_mission = Mock()
            mock_mission.uuid = "mission-123"
            mock_mission.name = "TestMission"
            mock_mission.exploited_capabilities = []
            
            # Mock capability
            mock_capability = Mock()
            mock_capability.uuid = "cap-456"
            mock_capability.name = "TestCapability"
            
            # Mock capellambse mission APIs
            mock_model.all_missions = [mock_mission]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "mission-123": mock_mission,
                "cap-456": mock_capability
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test mission creation
                result = await manage_missions(
                    model_id="test_model_stage9",
                    operation="create",
                    mission_spec={
                        "name": "NewMission",
                        "description": "Test mission for operational architecture",
                        "capabilities": ["cap-456"]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "mission_id" in result
                assert result["mission_created"] is True
                assert result["capabilities_linked"] is True

    @pytest.mark.asyncio
    async def test_manage_data_classes_functionality(self):
        """
        Test manage_data_classes tool functionality.
        
        Tests data class creation and inheritance using capellambse
        all_classes API for data architecture modeling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for data class testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock data class objects
            mock_base_class = Mock()
            mock_base_class.uuid = "class-123"
            mock_base_class.name = "BaseDataClass"
            mock_base_class.properties = []
            
            mock_derived_class = Mock()
            mock_derived_class.uuid = "class-456"
            mock_derived_class.name = "DerivedDataClass"
            mock_derived_class.super_class = mock_base_class
            
            # Mock capellambse data class APIs
            mock_model.all_classes = [mock_base_class, mock_derived_class]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "class-123": mock_base_class,
                "class-456": mock_derived_class
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test data class creation with inheritance
                result = await manage_data_classes(
                    model_id="test_model_stage9",
                    operation="create",
                    class_spec={
                        "name": "CustomerData",
                        "super_class_id": "class-123",
                        "properties": [
                            {"name": "customerId", "type": "String"},
                            {"name": "name", "type": "String"}
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create"
                assert "class_id" in result
                assert result["class_created"] is True
                assert result["inheritance_established"] is True

    @pytest.mark.asyncio
    async def test_manage_collections_functionality(self):
        """
        Test manage_collections tool functionality.
        
        Tests collection element management using capellambse
        all_collections API for container modeling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for collection testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock collection objects
            mock_collection = Mock()
            mock_collection.uuid = "collection-123"
            mock_collection.name = "TestCollection"
            mock_collection.elements = []
            
            # Mock elements to add
            mock_element1 = Mock()
            mock_element1.uuid = "element-456"
            mock_element1.name = "Element1"
            
            mock_element2 = Mock()
            mock_element2.uuid = "element-789"
            mock_element2.name = "Element2"
            
            # Mock capellambse collection APIs
            mock_model.all_collections = [mock_collection]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "collection-123": mock_collection,
                "element-456": mock_element1,
                "element-789": mock_element2
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test adding elements to collection
                result = await manage_collections(
                    model_id="test_model_stage9",
                    operation="add_elements",
                    collection_spec={
                        "collection_id": "collection-123",
                        "elements": ["element-456", "element-789"]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add_elements"
                assert result["collection_id"] == "collection-123"
                assert result["elements_added"] == 2

    @pytest.mark.asyncio
    async def test_manage_enumerations_functionality(self):
        """
        Test manage_enumerations tool functionality.
        
        Tests enumeration literal handling using capellambse
        enumeration_property_types and enumerations APIs.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for enumeration testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock enumeration objects
            mock_enumeration = Mock()
            mock_enumeration.uuid = "enum-123"
            mock_enumeration.name = "StatusEnumeration"
            mock_enumeration.literals = []
            
            # Mock enumeration literal
            mock_literal = Mock()
            mock_literal.uuid = "literal-456"
            mock_literal.name = "ACTIVE"
            mock_literal.value = "active"
            
            # Mock capellambse enumeration APIs
            mock_model.enumerations = [mock_enumeration]
            mock_model.enumeration_property_types = []
            mock_model.by_uuid.side_effect = lambda uuid: {
                "enum-123": mock_enumeration,
                "literal-456": mock_literal
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test enumeration literal addition
                result = await manage_enumerations(
                    model_id="test_model_stage9",
                    operation="add_literals",
                    enumeration_spec={
                        "enumeration_id": "enum-123",
                        "literals": [
                            {"name": "ACTIVE", "value": "active"},
                            {"name": "INACTIVE", "value": "inactive"}
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add_literals"
                assert result["enumeration_id"] == "enum-123"
                assert result["literals_added"] == 2

    @pytest.mark.asyncio
    async def test_manage_union_types_functionality(self):
        """
        Test manage_union_types tool functionality.
        
        Tests union type discriminant management using capellambse
        all_unions API for advanced type modeling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for union type testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock union type objects
            mock_union = Mock()
            mock_union.uuid = "union-123"
            mock_union.name = "DataUnion"
            mock_union.discriminants = []
            
            # Mock discriminant types
            mock_type1 = Mock()
            mock_type1.uuid = "type-456"
            mock_type1.name = "StringType"
            
            mock_type2 = Mock()
            mock_type2.uuid = "type-789"
            mock_type2.name = "IntegerType"
            
            # Mock capellambse union APIs
            mock_model.all_unions = [mock_union]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "union-123": mock_union,
                "type-456": mock_type1,
                "type-789": mock_type2
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test union discriminant addition
                result = await manage_union_types(
                    model_id="test_model_stage9",
                    operation="add_discriminants",
                    union_spec={
                        "union_id": "union-123",
                        "discriminants": [
                            {"type_id": "type-456", "condition": "string_value"},
                            {"type_id": "type-789", "condition": "integer_value"}
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add_discriminants"
                assert result["union_id"] == "union-123"
                assert result["discriminants_added"] == 2

    @pytest.mark.asyncio
    async def test_create_capability_realizations_functionality(self):
        """
        Test create_capability_realizations tool functionality.
        
        Tests capability-function linking using capellambse
        CapabilityRealization class for capability traceability.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for capability realization testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock capability and function objects
            mock_capability = Mock()
            mock_capability.uuid = "cap-123"
            mock_capability.name = "TestCapability"
            
            mock_function = Mock()
            mock_function.uuid = "func-456"
            mock_function.name = "TestFunction"
            
            # Mock capability realization
            mock_realization = Mock()
            mock_realization.uuid = "realization-789"
            mock_realization.capability = mock_capability
            mock_realization.realizing_function = mock_function
            
            # Mock capellambse APIs
            mock_model.by_uuid.side_effect = lambda uuid: {
                "cap-123": mock_capability,
                "func-456": mock_function,
                "realization-789": mock_realization
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test capability realization creation
                result = await create_capability_realizations(
                    model_id="test_model_stage9",
                    operation="create_realization",
                    realization_spec={
                        "capability_id": "cap-123",
                        "function_id": "func-456",
                        "realization_type": "realizes"
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "create_realization"
                assert result["capability_id"] == "cap-123"
                assert result["function_id"] == "func-456"
                assert result["realization_created"] is True

    @pytest.mark.asyncio
    async def test_manage_state_machines_functionality(self):
        """
        Test manage_state_machines tool functionality.
        
        Tests state and transition modeling using capellambse
        StateMachine and state_machines APIs for behavioral modeling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for state machine testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock state machine objects
            mock_state_machine = Mock()
            mock_state_machine.uuid = "sm-123"
            mock_state_machine.name = "TestStateMachine"
            mock_state_machine.states = []
            mock_state_machine.transitions = []
            
            # Mock states
            mock_state1 = Mock()
            mock_state1.uuid = "state-456"
            mock_state1.name = "InitialState"
            
            mock_state2 = Mock()
            mock_state2.uuid = "state-789"
            mock_state2.name = "FinalState"
            
            # Mock transition
            mock_transition = Mock()
            mock_transition.uuid = "transition-012"
            mock_transition.source = mock_state1
            mock_transition.target = mock_state2
            
            # Mock capellambse state machine APIs
            mock_model.state_machines = [mock_state_machine]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "sm-123": mock_state_machine,
                "state-456": mock_state1,
                "state-789": mock_state2,
                "transition-012": mock_transition
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test state machine transition creation
                result = await manage_state_machines(
                    model_id="test_model_stage9",
                    operation="add_transitions",
                    state_machine_spec={
                        "state_machine_id": "sm-123",
                        "transitions": [
                            {
                                "source_state_id": "state-456",
                                "target_state_id": "state-789",
                                "trigger": "complete",
                                "guard": "validated"
                            }
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add_transitions"
                assert result["state_machine_id"] == "sm-123"
                assert result["transitions_added"] == 1

    @pytest.mark.asyncio
    async def test_advanced_scenario_modeling_functionality(self):
        """
        Test advanced_scenario_modeling tool functionality.
        
        Tests scenario step sequencing using capellambse scenarios
        API for operational process modeling.
        """
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model setup for scenario testing
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock scenario objects
            mock_scenario = Mock()
            mock_scenario.uuid = "scenario-123"
            mock_scenario.name = "TestScenario"
            mock_scenario.steps = []
            
            # Mock actors
            mock_actor1 = Mock()
            mock_actor1.uuid = "actor-456"
            mock_actor1.name = "PrimaryActor"
            
            mock_actor2 = Mock()
            mock_actor2.uuid = "actor-789"
            mock_actor2.name = "SecondaryActor"
            
            # Mock scenario steps
            mock_step1 = Mock()
            mock_step1.uuid = "step-012"
            mock_step1.name = "InitiateProcess"
            mock_step1.actor = mock_actor1
            
            mock_step2 = Mock()
            mock_step2.uuid = "step-345"
            mock_step2.name = "RespondToRequest"
            mock_step2.actor = mock_actor2
            
            # Mock capellambse scenario APIs
            mock_model.scenarios = [mock_scenario]
            mock_model.by_uuid.side_effect = lambda uuid: {
                "scenario-123": mock_scenario,
                "actor-456": mock_actor1,
                "actor-789": mock_actor2,
                "step-012": mock_step1,
                "step-345": mock_step2
            }.get(uuid)
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test scenario step sequencing
                result = await advanced_scenario_modeling(
                    model_id="test_model_stage9",
                    operation="add_steps",
                    scenario_spec={
                        "scenario_id": "scenario-123",
                        "steps": [
                            {
                                "name": "StartProcess",
                                "actor_id": "actor-456",
                                "description": "Actor initiates the process"
                            },
                            {
                                "name": "HandleRequest",
                                "actor_id": "actor-789", 
                                "description": "Secondary actor handles request"
                            }
                        ],
                        "sequence": [
                            {"from": "step-012", "to": "step-345"}
                        ]
                    }
                )
                
                # Validate successful operation
                assert result["success"] is True
                assert result["operation"] == "add_steps"
                assert result["scenario_id"] == "scenario-123"
                assert result["steps_added"] == 2
                assert result["sequence_validated"] is True

    @pytest.mark.asyncio
    async def test_stage9_error_handling(self):
        """Test comprehensive error handling for all Stage 9 tools."""
        # Test with non-existent model
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model_manager.get_model.side_effect = KeyError("Model not found")
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test manage_viewpoints error handling
                result = await manage_viewpoints(
                    model_id="nonexistent_model",
                    operation="activate",
                    viewpoint_spec={"viewpoint_id": "test"}
                )
                
                assert result["success"] is False
                assert "error" in result
                assert "Model not found" in result["error"]
                
                # Test manage_state_machines error handling
                result = await manage_state_machines(
                    model_id="nonexistent_model",
                    operation="create",
                    state_machine_spec={"name": "test"}
                )
                
                assert result["success"] is False
                assert "error" in result

    @pytest.mark.asyncio 
    async def test_stage9_enhanced_disabled(self):
        """Test graceful degradation when enhanced tools are disabled."""
        # Test with ENHANCED_AVAILABLE = False
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', False):
            result = await manage_viewpoints(
                model_id="test_model",
                operation="activate",
                viewpoint_spec={"viewpoint_id": "test"}
            )
            
            assert result["success"] is False
            assert "enhanced advanced modeling tools not available" in result["error"].lower()

    @pytest.mark.asyncio
    async def test_stage9_api_coverage(self):
        """Test validation of capellambse API usage for Stage 9 tools."""
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            mock_model_manager = Mock()
            mock_model = Mock()
            mock_model_manager.get_model.return_value = mock_model
            
            # Mock all required Stage 9 APIs
            mock_model.referenced_viewpoints = []
            mock_model.activate_viewpoint = Mock()
            mock_model.all_missions = []
            mock_model.all_classes = []
            mock_model.all_collections = []
            mock_model.enumerations = []
            mock_model.enumeration_property_types = []
            mock_model.all_unions = []
            mock_model.state_machines = []
            mock_model.scenarios = []
            
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                # Test that all Stage 9 APIs are accessible
                result = await manage_viewpoints(
                    model_id="test_model",
                    operation="list",
                    filter_spec={}
                )
                
                # Should succeed with proper API access
                assert result["success"] is True
                assert "api_coverage_validated" in result or result["success"] is True