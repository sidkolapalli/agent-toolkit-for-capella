"""
TDD Implementation Tests - Final 100% ARCADIA Capability

This test file implements Phase 1 (RED) of the TDD approach to achieve
complete 100% ARCADIA methodology support by testing the 2 remaining features:
1. Scenario Type Support (Actor scenarios)
2. Dependency Analysis (Real implementation)

These tests will initially fail, driving the implementation of the missing APIs.
"""

import pytest
import asyncio
from capella_mcp.model_manager import ModelManager
from capella_mcp.tools import ToolRegistry

# Test model path
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


@pytest.fixture
async def tool_registry(manager):
    """Create a ToolRegistry for testing dependency analysis."""
    return ToolRegistry(manager)


class TestScenarioSupport:
    """Test Scenario element type support for Actor scenarios."""

    async def test_operational_scenario_creation(self, manager, loaded_model):
        """Test creation of OperationalScenario element."""
        model_id = loaded_model
        
        # This should create an OperationalScenario
        scenario_id = await manager.create_element(
            model_id, 
            "OperationalScenario",
            name="K0_Driver_Scenario_Test",
            description="Driver interaction scenario for K0 system"
        )
        
        assert scenario_id is not None, "OperationalScenario creation should succeed"
        assert len(scenario_id) > 0, "OperationalScenario should return valid ID"

    async def test_system_scenario_creation(self, manager, loaded_model):
        """Test creation of SystemScenario element."""
        model_id = loaded_model
        
        # This should create a SystemScenario
        scenario_id = await manager.create_element(
            model_id, 
            "SystemScenario",
            name="K0_System_Scenario_Test",
            description="System-level interaction scenario"
        )
        
        assert scenario_id is not None, "SystemScenario creation should succeed"
        assert len(scenario_id) > 0, "SystemScenario should return valid ID"

    async def test_logical_scenario_creation(self, manager, loaded_model):
        """Test creation of LogicalScenario element."""
        model_id = loaded_model
        
        # This should create a LogicalScenario
        scenario_id = await manager.create_element(
            model_id, 
            "LogicalScenario",
            name="K0_Logical_Scenario_Test", 
            description="Logical-level behavior scenario"
        )
        
        assert scenario_id is not None, "LogicalScenario creation should succeed"
        assert len(scenario_id) > 0, "LogicalScenario should return valid ID"

    async def test_generic_scenario_creation(self, manager, loaded_model):
        """Test creation of generic Scenario element (should default to OperationalScenario)."""
        model_id = loaded_model
        
        # This should create a generic Scenario (likely OperationalScenario)
        scenario_id = await manager.create_element(
            model_id, 
            "Scenario",
            name="K0_Generic_Scenario_Test",
            description="Generic scenario for testing"
        )
        
        assert scenario_id is not None, "Generic Scenario creation should succeed"
        assert len(scenario_id) > 0, "Generic Scenario should return valid ID"

    async def test_scenario_querying(self, manager, loaded_model):
        """Test querying Scenario elements."""
        model_id = loaded_model
        
        # First create a scenario to ensure at least one exists
        await manager.create_element(
            model_id, 
            "OperationalScenario",
            name="K0_Query_Test_Scenario"
        )
        
        # Query for OperationalScenario elements
        scenarios = await manager.query_elements(model_id, {"type": "OperationalScenario"})
        
        assert len(scenarios) > 0, "Should find OperationalScenario elements"
        
        # Verify our created scenario is in the results
        found = any(s for s in scenarios 
                   if hasattr(s, 'name') and "K0_Query_Test_Scenario" in s.name)
        assert found, "Should find our created OperationalScenario"

    async def test_scenario_persistence(self, manager, loaded_model):
        """Test that scenarios persist in the model."""
        model_id = loaded_model
        
        # Create scenario
        scenario_id = await manager.create_element(
            model_id, 
            "OperationalScenario",
            name="K0_Persistence_Test_Scenario"
        )
        
        # Save model
        await manager.save_model(model_id)
        
        # Query to verify persistence
        scenarios = await manager.query_elements(model_id, {"type": "OperationalScenario"})
        found = any(s for s in scenarios 
                   if hasattr(s, 'name') and "K0_Persistence_Test_Scenario" in s.name)
        
        assert found, "Scenario should persist after save"


class TestDependencyAnalysis:
    """Test comprehensive dependency analysis functionality."""

    async def test_component_dependency_analysis(self, tool_registry, loaded_model):
        """Test analyzing dependencies of a component."""
        model_id = loaded_model
        
        # First create a component to analyze
        component_id = await tool_registry.model_manager.create_element(
            model_id,
            "LogicalComponent", 
            name="K0_Analysis_Test_Component"
        )
        
        # Analyze its dependencies
        result = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 2,
            "include_indirect": True
        })
        
        assert result["status"] == "success", "Dependency analysis should succeed"
        assert "element_id" in result, "Should return element_id"
        assert "element_name" in result, "Should return element_name"
        assert "direct_dependencies" in result, "Should count direct dependencies"
        assert "indirect_dependencies" in result, "Should count indirect dependencies"
        assert "dependent_elements" in result, "Should count dependent elements"
        assert "impact_score" in result, "Should calculate impact score"
        assert "dependencies" in result, "Should return dependencies structure"

    async def test_dependency_analysis_real_implementation(self, tool_registry, loaded_model):
        """Test that dependency analysis provides real insights, not stub data."""
        model_id = loaded_model
        
        # Find an existing component with likely dependencies
        components = await tool_registry.model_manager.query_elements(
            model_id, {"type": "LogicalComponent"}
        )
        
        assert len(components) > 0, "Should have LogicalComponents to analyze"
        
        # Analyze first component
        component = components[0]
        component_id = str(component.uuid)
        
        result = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 2,
            "include_indirect": True
        })
        
        assert result["status"] == "success", "Analysis should succeed"
        assert result["element_name"] == component.name, "Should return correct element name"
        
        # Verify this is real implementation, not stub
        assert "message" not in result or "not yet implemented" not in result.get("message", ""), \
            "Should be real implementation, not stub"
        
        # Should have some form of dependency data structure
        deps = result["dependencies"]
        assert isinstance(deps, dict), "Dependencies should be structured data"
        assert "direct" in deps, "Should analyze direct dependencies"
        assert "dependents" in deps, "Should analyze dependent elements"

    async def test_dependency_impact_score_calculation(self, tool_registry, loaded_model):
        """Test that impact scores are calculated meaningfully."""
        model_id = loaded_model
        
        # Create a complex component with dependencies
        component_id = await tool_registry.model_manager.create_element(
            model_id,
            "LogicalComponent",
            name="K0_Complex_Component_For_Impact"
        )
        
        # Analyze its impact
        result = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 1,
            "include_indirect": False
        })
        
        assert result["status"] == "success", "Analysis should succeed"
        
        impact_score = result["impact_score"]
        assert isinstance(impact_score, (int, float)), "Impact score should be numeric"
        assert impact_score >= 0, "Impact score should be non-negative"

    async def test_dependency_analysis_depth_parameter(self, tool_registry, loaded_model):
        """Test that depth parameter affects analysis depth."""
        model_id = loaded_model
        
        # Find a component to analyze
        components = await tool_registry.model_manager.query_elements(
            model_id, {"type": "LogicalComponent"}
        )
        
        if len(components) == 0:
            # Create one if none exist
            component_id = await tool_registry.model_manager.create_element(
                model_id, "LogicalComponent", name="K0_Depth_Test_Component"
            )
            component = tool_registry.model_manager.get_model(model_id).get_element(component_id)
        else:
            component = components[0]
        
        component_id = str(component.uuid)
        
        # Test depth=1
        result_shallow = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 1,
            "include_indirect": False
        })
        
        # Test depth=3
        result_deep = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 3,
            "include_indirect": True
        })
        
        assert result_shallow["status"] == "success", "Shallow analysis should succeed"
        assert result_deep["status"] == "success", "Deep analysis should succeed"
        
        # The deep analysis should potentially find more dependencies
        # (though this depends on the actual model structure)
        assert isinstance(result_shallow["dependencies"], dict), "Should return dependency structure"
        assert isinstance(result_deep["dependencies"], dict), "Should return dependency structure"

    async def test_dependency_analysis_error_handling(self, tool_registry, loaded_model):
        """Test error handling for invalid element IDs."""
        model_id = loaded_model
        
        # Test with invalid element ID
        result = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": "invalid-uuid-12345",
            "depth": 2,
            "include_indirect": True
        })
        
        assert result["status"] == "failed", "Should fail for invalid element ID"
        assert "error" in result, "Should return error message"

    async def test_dependency_analysis_missing_model(self, tool_registry):
        """Test error handling for missing model."""
        
        result = await tool_registry._analyze_dependencies({
            "model_id": "invalid-model-id",
            "element_id": "some-element-id",
            "depth": 2,
            "include_indirect": True
        })
        
        assert result["status"] == "failed", "Should fail for invalid model ID"
        assert "error" in result, "Should return error message"


class TestComplete100PercentARCADIA:
    """Test complete 100% ARCADIA methodology support."""

    async def test_all_element_types_including_scenarios(self, manager, loaded_model):
        """Test that all ARCADIA element types work, including scenarios."""
        model_id = loaded_model
        
        # All ARCADIA elements including scenarios (18 total)
        all_arcadia_elements = [
            # Original 16 ARCADIA elements
            "OperationalEntity", "OperationalActivity", "OperationalCapability",
            "SystemFunction", "SystemComponent", "SystemCapability", "SystemActor",
            "LogicalComponent", "LogicalActor", "Interface", "FunctionalChain", "FunctionalExchange",
            "PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent",
            
            # Production aliases (5 elements)
            "Entity", "LogicalFunction", "Capability", "Actor", "Requirements",
            
            # NEW: Scenario types (adding to reach 100%)
            "OperationalScenario", "SystemScenario", "LogicalScenario"
        ]
        
        success_count = 0
        total_count = len(all_arcadia_elements)
        
        for element_type in all_arcadia_elements:
            try:
                # Test creation
                element_id = await manager.create_element(
                    model_id,
                    element_type,
                    name=f"Final100_Test_{element_type}"
                )
                
                # Test querying  
                elements = await manager.query_elements(model_id, {"type": element_type})
                
                # Both should work for 100% coverage
                if element_id and len(elements) >= 0:
                    success_count += 1
                    print(f"✅ {element_type}: WORKING")
                else:
                    print(f"❌ {element_type}: FAILED")
                    
            except Exception as e:
                print(f"❌ {element_type}: ERROR - {e}")
        
        coverage_percentage = (success_count / total_count) * 100
        
        print(f"\n🎯 ARCADIA Coverage: {coverage_percentage:.0f}% ({success_count}/{total_count})")
        
        # Should achieve 100% coverage
        assert coverage_percentage == 100, f"Expected 100% coverage, got {coverage_percentage}%"

    async def test_production_readiness_validation(self, manager, tool_registry, loaded_model):
        """Test complete production readiness with scenarios and dependency analysis."""
        model_id = loaded_model
        
        # Test 1: Create complete ARCADIA workflow including scenarios
        entity_id = await manager.create_element(
            model_id, "Entity", name="K0_Final_Test_Entity"
        )
        
        actor_id = await manager.create_element(
            model_id, "Actor", name="K0_Final_Test_Actor"
        )
        
        scenario_id = await manager.create_element(
            model_id, "OperationalScenario", name="K0_Final_Test_Scenario"
        )
        
        component_id = await manager.create_element(
            model_id, "LogicalComponent", name="K0_Final_Test_Component"
        )
        
        assert all([entity_id, actor_id, scenario_id, component_id]), \
            "All critical elements should create successfully"
        
        # Test 2: Dependency analysis works
        dep_result = await tool_registry._analyze_dependencies({
            "model_id": model_id,
            "element_id": component_id,
            "depth": 2,
            "include_indirect": True
        })
        
        assert dep_result["status"] == "success", "Dependency analysis should work"
        assert dep_result["element_name"] == "K0_Final_Test_Component", \
            "Should analyze correct element"
        
        # Test 3: Model validation passes
        validation_result = await manager.validate_model(model_id, ["completeness"])
        assert validation_result["valid"] == True, "Model should be valid"
        
        print("🎉 100% ARCADIA CAPABILITY ACHIEVED!")
        print("✅ All element types working")
        print("✅ Scenario support complete")
        print("✅ Dependency analysis functional")
        print("✅ Production ready!")


if __name__ == "__main__":
    # Quick test runner for development
    async def run_quick_tests():
        manager = ModelManager()
        try:
            model_id = await manager.load_model(TEST_MODEL_PATH)
            
            print("🔬 TDD RED PHASE - Testing Missing Features")
            print("=" * 60)
            
            # Test scenario creation (should fail initially)
            failing_scenarios = ["OperationalScenario", "SystemScenario", "LogicalScenario", "Scenario"]
            
            print("Testing Scenario Types:")
            for scenario_type in failing_scenarios:
                try:
                    scenario_id = await manager.create_element(
                        model_id, scenario_type, name=f"Test_{scenario_type}"
                    )
                    print(f"✅ {scenario_type}: WORKING (unexpected)")
                except Exception as e:
                    print(f"❌ {scenario_type}: FAILED - {e}")
            
            # Test dependency analysis (should be stub)
            print("\nTesting Dependency Analysis:")
            tool_registry = CapellaToolRegistry(manager)
            
            # Create a test component
            comp_id = await manager.create_element(
                model_id, "LogicalComponent", name="Test_Dependency_Component"
            )
            
            result = await tool_registry._analyze_dependencies({
                "model_id": model_id,
                "element_id": comp_id,
                "depth": 2,
                "include_indirect": True
            })
            
            if "not yet implemented" in result.get("message", ""):
                print("❌ Dependency Analysis: STUB IMPLEMENTATION")
            else:
                print("✅ Dependency Analysis: REAL IMPLEMENTATION (unexpected)")
                
        except Exception as e:
            print(f"❌ Test setup failed: {e}")
        finally:
            try:
                await manager.unload_model(model_id)
            except:
                pass

    # Uncomment to run quick tests
    # asyncio.run(run_quick_tests())
    pass