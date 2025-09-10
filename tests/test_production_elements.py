"""
Production Element Tests - TDD Phase 1 (RED)
Tests for elements expected by production assessment but currently missing.

This test file implements the RED phase of TDD - writing failing tests first
to define the expected behavior for:
- Entity (generic, not OperationalEntity)
- LogicalFunction (missing from LA layer)
- Capability (generic, not SystemCapability)
- Actor (generic, not SystemActor)
- Requirements (not implemented)
"""

import pytest
import asyncio
from capella_mcp.model_manager import ModelManager

# Test model path - using the main GMHCD model for testing
import os
TEST_MODEL_PATH = os.getenv("CAPELLA_MODEL_PATH", "<local-path>")


@pytest.fixture
async def manager():
    """Create a ModelManager instance for testing."""
    return ModelManager()


@pytest.fixture
async def loaded_model(manager):
    """Load a test model and return the model_id."""
    try:
        model_id = await manager.load_model(TEST_MODEL_PATH)
        yield model_id
    finally:
        # Cleanup
        try:
            await manager.unload_model(model_id)
        except:
            pass


class TestProductionElements:
    """Test suite for production-expected elements that are currently missing."""

    async def test_entity_creation(self, manager, loaded_model):
        """Test creation of generic 'Entity' element (not OperationalEntity)."""
        model_id = loaded_model
        
        # This should work according to production assessment
        entity_id = await manager.create_element(
            model_id, 
            "Entity",  # Generic name, not "OperationalEntity"
            name="Production_Entity_Test",
            description="Generic entity for production testing"
        )
        
        assert entity_id is not None
        assert len(entity_id) > 0
        
        # Verify it can be queried
        entities = await manager.query_elements(model_id, {"type": "Entity"})
        assert len(entities) > 0
        
        # Find our specific entity
        found = any(e for e in entities 
                   if hasattr(e, 'name') and e.name == "Production_Entity_Test")
        assert found, "Created entity should be findable by query"

    async def test_logical_function_creation(self, manager, loaded_model):
        """Test creation of LogicalFunction in LA layer."""
        model_id = loaded_model
        
        # This element is completely missing from our implementation
        function_id = await manager.create_element(
            model_id,
            "LogicalFunction",
            name="Production_LogicalFunction_Test",
            description="Logical function for production testing"
        )
        
        assert function_id is not None
        assert len(function_id) > 0
        
        # Verify it can be queried
        functions = await manager.query_elements(model_id, {"type": "LogicalFunction"})
        assert len(functions) > 0
        
        # Find our specific function
        found = any(f for f in functions 
                   if hasattr(f, 'name') and f.name == "Production_LogicalFunction_Test")
        assert found, "Created logical function should be findable by query"

    async def test_capability_creation(self, manager, loaded_model):
        """Test creation of generic 'Capability' element (not SystemCapability)."""
        model_id = loaded_model
        
        # Generic capability, not layer-specific
        capability_id = await manager.create_element(
            model_id,
            "Capability",  # Generic name, not "SystemCapability"
            name="Production_Capability_Test",
            description="Generic capability for production testing"
        )
        
        assert capability_id is not None
        assert len(capability_id) > 0
        
        # Verify it can be queried
        capabilities = await manager.query_elements(model_id, {"type": "Capability"})
        assert len(capabilities) > 0
        
        # Find our specific capability
        found = any(c for c in capabilities 
                   if hasattr(c, 'name') and c.name == "Production_Capability_Test")
        assert found, "Created capability should be findable by query"

    async def test_actor_creation(self, manager, loaded_model):
        """Test creation of generic 'Actor' element (not SystemActor)."""
        model_id = loaded_model
        
        # Generic actor, not layer-specific
        actor_id = await manager.create_element(
            model_id,
            "Actor",  # Generic name, not "SystemActor"
            name="Production_Actor_Test",
            description="Generic actor for production testing"
        )
        
        assert actor_id is not None
        assert len(actor_id) > 0
        
        # Verify it can be queried
        actors = await manager.query_elements(model_id, {"type": "Actor"})
        assert len(actors) > 0
        
        # Find our specific actor
        found = any(a for a in actors 
                   if hasattr(a, 'name') and a.name == "Production_Actor_Test")
        assert found, "Created actor should be findable by query"

    async def test_requirements_creation(self, manager, loaded_model):
        """Test creation of Requirements element."""
        model_id = loaded_model
        
        # Requirements are not implemented at all
        requirement_id = await manager.create_element(
            model_id,
            "Requirements",
            name="Production_Requirements_Test",
            description="Requirements for production testing"
        )
        
        assert requirement_id is not None
        assert len(requirement_id) > 0
        
        # Verify it can be queried
        requirements = await manager.query_elements(model_id, {"type": "Requirements"})
        assert len(requirements) > 0
        
        # Find our specific requirement
        found = any(r for r in requirements 
                   if hasattr(r, 'name') and r.name == "Production_Requirements_Test")
        assert found, "Created requirement should be findable by query"

    async def test_production_element_persistence(self, manager, loaded_model):
        """Test that production elements persist correctly."""
        model_id = loaded_model
        
        # Create one of each missing element type
        elements_to_create = [
            ("Entity", "Persistence_Entity"),
            ("LogicalFunction", "Persistence_LogicalFunction"),
            ("Capability", "Persistence_Capability"),
            ("Actor", "Persistence_Actor"),
            ("Requirements", "Persistence_Requirements")
        ]
        
        created_ids = []
        for element_type, name in elements_to_create:
            element_id = await manager.create_element(
                model_id, element_type, name=name
            )
            created_ids.append((element_type, element_id, name))
        
        # Save the model
        await manager.save_model(model_id)
        
        # Reload the model
        await manager.unload_model(model_id)
        model_id2 = await manager.load_model(TEST_MODEL_PATH)
        
        # Verify all elements still exist
        for element_type, original_id, name in created_ids:
            elements = await manager.query_elements(model_id2, {"type": element_type})
            found = any(e for e in elements 
                       if hasattr(e, 'name') and e.name == name)
            assert found, f"Element {name} of type {element_type} should persist after reload"
        
        # Cleanup
        await manager.unload_model(model_id2)

    async def test_production_assessment_scenario(self, manager, loaded_model):
        """Test the exact scenario from the production assessment."""
        model_id = loaded_model
        
        # These are the exact failing elements from the assessment
        test_cases = [
            {"type": "Entity", "name": "K0_Driver_Entity"},
            {"type": "LogicalFunction", "name": "K0_LogicalFunction"},
            {"type": "Capability", "name": "K0_Hydraulic_Capability"}
        ]
        
        created_elements = []
        
        for case in test_cases:
            element_id = await manager.create_element(
                model_id,
                case["type"],
                name=case["name"]
            )
            created_elements.append((case["type"], element_id, case["name"]))
        
        # Verify all were created successfully
        assert len(created_elements) == len(test_cases)
        
        # Verify they can all be queried
        for element_type, element_id, name in created_elements:
            elements = await manager.query_elements(model_id, {"type": element_type})
            found = any(e for e in elements 
                       if hasattr(e, 'name') and e.name == name)
            assert found, f"Production assessment element {name} should be queryable"


class TestElementCompatibility:
    """Test that existing layer-specific elements still work alongside generic ones."""

    async def test_both_entity_types_work(self, manager, loaded_model):
        """Test that both 'Entity' and 'OperationalEntity' work."""
        model_id = loaded_model
        
        # Create generic Entity
        generic_id = await manager.create_element(
            model_id, "Entity", name="Generic_Entity"
        )
        
        # Create OperationalEntity
        operational_id = await manager.create_element(
            model_id, "OperationalEntity", name="Operational_Entity"
        )
        
        # Both should be created
        assert generic_id is not None
        assert operational_id is not None
        
        # Generic query should find generic Entity
        generic_entities = await manager.query_elements(model_id, {"type": "Entity"})
        assert any(e for e in generic_entities 
                  if hasattr(e, 'name') and e.name == "Generic_Entity")
        
        # Specific query should find OperationalEntity
        operational_entities = await manager.query_elements(model_id, {"type": "OperationalEntity"})
        assert any(e for e in operational_entities 
                  if hasattr(e, 'name') and e.name == "Operational_Entity")

    async def test_both_capability_types_work(self, manager, loaded_model):
        """Test that both 'Capability' and 'SystemCapability' work."""
        model_id = loaded_model
        
        # Create generic Capability
        generic_id = await manager.create_element(
            model_id, "Capability", name="Generic_Capability"
        )
        
        # Create SystemCapability
        system_id = await manager.create_element(
            model_id, "SystemCapability", name="System_Capability"
        )
        
        # Both should be created
        assert generic_id is not None
        assert system_id is not None

    async def test_both_actor_types_work(self, manager, loaded_model):
        """Test that both 'Actor' and 'SystemActor' work."""
        model_id = loaded_model
        
        # Create generic Actor
        generic_id = await manager.create_element(
            model_id, "Actor", name="Generic_Actor"
        )
        
        # Create SystemActor
        system_id = await manager.create_element(
            model_id, "SystemActor", name="System_Actor"
        )
        
        # Both should be created
        assert generic_id is not None
        assert system_id is not None


if __name__ == "__main__":
    # Quick test runner for development
    async def run_quick_test():
        manager = ModelManager()
        # This will fail until we implement the missing elements
        try:
            model_id = await manager.load_model(TEST_MODEL_PATH)
            await manager.create_element(model_id, "Entity", name="Quick_Test")
            print("SUCCESS: Entity creation worked!")
        except Exception as e:
            print(f"EXPECTED FAILURE: {e}")
        
    # Uncomment to run quick test
    # asyncio.run(run_quick_test())
    pass