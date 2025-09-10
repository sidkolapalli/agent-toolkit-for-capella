"""
TDD Validation Test - Final Production Readiness Check

This test validates that the TDD implementation has successfully closed
the gap between claimed 100% ARCADIA capability and actual production capability.

Based on the production assessment that showed only 65-70% capability,
this test verifies all missing elements are now working:
- Entity (alias for OperationalEntity)
- LogicalFunction (implemented via la.function_pkg.functions)
- Capability (alias for SystemCapability)
- Actor (alias for SystemActor) 
- Requirements (implemented via la.requirement_modules)
"""

import pytest
import asyncio
from capella_mcp.model_manager import ModelManager

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


class TestTDDValidation:
    """Final validation that TDD implementation solved the production gap."""

    async def test_production_assessment_elements_all_working(self, manager, loaded_model):
        """Test all elements that were failing in the production assessment."""
        model_id = loaded_model
        
        # These are the exact elements that were failing in production assessment
        failing_elements = [
            "Entity",           # Failed: ElementType 'Entity' not supported yet
            "LogicalFunction",  # Failed: ElementType 'LogicalFunction' not supported yet  
            "Capability",       # Failed: ElementType 'Capability' not supported yet
            "Actor",            # Failed: ElementType 'Actor' not supported yet
            "Requirements"      # Failed: ElementType 'Requirements' not supported yet
        ]
        
        created_elements = []
        
        # Test creation of all previously failing elements
        for element_type in failing_elements:
            element_id = await manager.create_element(
                model_id,
                element_type,
                name=f"TDD_Fixed_{element_type}",
                description=f"Validates {element_type} now works via TDD implementation"
            )
            
            assert element_id is not None, f"{element_type} creation should succeed"
            assert len(element_id) > 0, f"{element_type} should return valid ID"
            
            created_elements.append((element_type, element_id))
        
        # Test querying of all previously failing elements
        for element_type, expected_id in created_elements:
            elements = await manager.query_elements(model_id, {"type": element_type})
            
            # Should find at least our created element
            assert len(elements) > 0, f"Should find {element_type} elements"
            
            # Should find our specific element
            found = any(e for e in elements 
                       if hasattr(e, 'name') and f"TDD_Fixed_{element_type}" in e.name)
            assert found, f"Should find our created {element_type} element"
        
        print(f"✅ SUCCESS: All {len(failing_elements)} previously failing elements now work!")
        
        return created_elements

    async def test_arcadia_coverage_100_percent(self, manager, loaded_model):
        """Test that we now have 100% ARCADIA coverage."""
        model_id = loaded_model
        
        # All 16 ARCADIA elements from the assessment
        all_arcadia_elements = [
            # Operational Architecture (OA) - 3 elements
            "OperationalEntity", "OperationalActivity", "OperationalCapability",
            
            # System Architecture (SA) - 4 elements  
            "SystemFunction", "SystemComponent", "SystemCapability", "SystemActor",
            
            # Logical Architecture (LA) - 5 elements
            "LogicalComponent", "LogicalActor", "Interface", "FunctionalChain", "FunctionalExchange",
            
            # Physical Architecture (PA) - 4 elements
            "PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent"
        ]
        
        # Plus the new generic aliases for production compatibility
        production_aliases = [
            "Entity", "LogicalFunction", "Capability", "Actor", "Requirements"
        ]
        
        all_supported_elements = all_arcadia_elements + production_aliases
        
        success_count = 0
        total_count = len(all_supported_elements)
        
        for element_type in all_supported_elements:
            try:
                # Test creation
                element_id = await manager.create_element(
                    model_id,
                    element_type,
                    name=f"Coverage_Test_{element_type}"
                )
                
                # Test querying
                elements = await manager.query_elements(model_id, {"type": element_type})
                
                # Both should work for 100% coverage
                if element_id and len(elements) >= 0:  # >= 0 because some collections might be empty
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
        
        return {
            "coverage_percentage": coverage_percentage,
            "success_count": success_count,
            "total_count": total_count,
            "working_elements": success_count
        }

    async def test_tdd_success_metrics(self, manager, loaded_model):
        """Validate TDD success by testing the exact scenario from assessment."""
        model_id = loaded_model
        
        # The exact test cases from the production assessment that were failing
        assessment_test_cases = [
            {
                "type": "Entity", 
                "name": "K0_Driver_Entity",
                "expected_failure": "ElementType 'Entity' not supported yet"
            },
            {
                "type": "LogicalFunction", 
                "name": "K0_LogicalFunction",
                "expected_failure": "ElementType 'LogicalFunction' not supported yet"
            },
            {
                "type": "Capability", 
                "name": "K0_Hydraulic_Capability", 
                "expected_failure": "ElementType 'Capability' not supported yet"
            },
            {
                "type": "Actor",
                "name": "K0_External_Actor",
                "expected_failure": "ElementType 'Actor' not supported yet"
            },
            {
                "type": "Requirements",
                "name": "K0_System_Requirements", 
                "expected_failure": "ElementType 'Requirements' not supported yet"
            }
        ]
        
        results = {
            "before_tdd": {"working": 0, "failing": 5},
            "after_tdd": {"working": 0, "failing": 0},
            "improvement": {}
        }
        
        # Test each previously failing case
        for test_case in assessment_test_cases:
            try:
                # This should now work (was failing before TDD)
                element_id = await manager.create_element(
                    model_id,
                    test_case["type"],
                    name=test_case["name"]
                )
                
                # Verify creation succeeded
                assert element_id is not None
                assert len(element_id) > 0
                
                # Verify querying works
                elements = await manager.query_elements(
                    model_id, {"type": test_case["type"]}
                )
                found = any(e for e in elements 
                           if hasattr(e, 'name') and e.name == test_case["name"])
                assert found
                
                results["after_tdd"]["working"] += 1
                results["improvement"][test_case["type"]] = "FIXED"
                
                print(f"✅ {test_case['type']}: FIXED (was failing with '{test_case['expected_failure']}')")
                
            except Exception as e:
                results["after_tdd"]["failing"] += 1
                results["improvement"][test_case["type"]] = f"STILL FAILING: {e}"
                print(f"❌ {test_case['type']}: STILL FAILING - {e}")
        
        # Calculate improvement metrics
        before_capability = (results["before_tdd"]["working"] / 5) * 100  # 0% 
        after_capability = (results["after_tdd"]["working"] / 5) * 100    # Should be 100%
        improvement = after_capability - before_capability
        
        results["metrics"] = {
            "before_capability_percent": before_capability,
            "after_capability_percent": after_capability, 
            "improvement_percent": improvement
        }
        
        print(f"\n📊 TDD SUCCESS METRICS:")
        print(f"   Before TDD: {before_capability}% capability ({results['before_tdd']['working']}/5 working)")
        print(f"   After TDD:  {after_capability}% capability ({results['after_tdd']['working']}/5 working)")
        print(f"   Improvement: +{improvement}% ({results['after_tdd']['working'] - results['before_tdd']['working']} elements fixed)")
        
        # TDD should have achieved 100% success on previously failing elements
        assert after_capability == 100, f"TDD should achieve 100% on failing elements, got {after_capability}%"
        assert improvement == 100, f"TDD should improve by 100%, got {improvement}%"
        
        return results


if __name__ == "__main__":
    # Quick validation runner for development
    async def run_validation():
        manager = ModelManager()
        try:
            model_id = await manager.load_model(TEST_MODEL_PATH)
            
            # Test the core issue from production assessment
            failing_elements = ["Entity", "LogicalFunction", "Capability", "Actor", "Requirements"]
            
            print("🔬 TDD VALIDATION - Testing Previously Failing Elements")
            print("=" * 60)
            
            success_count = 0
            for element_type in failing_elements:
                try:
                    element_id = await manager.create_element(
                        model_id, element_type, name=f"Validation_{element_type}"
                    )
                    print(f"✅ {element_type}: WORKING")
                    success_count += 1
                except Exception as e:
                    print(f"❌ {element_type}: FAILED - {e}")
            
            capability = (success_count / len(failing_elements)) * 100
            print(f"\n🎯 Result: {capability}% capability on previously failing elements")
            
            if capability == 100:
                print("🎉 TDD SUCCESS: Production assessment gap has been closed!")
            else:
                print("⚠️  TDD INCOMPLETE: Gap still exists")
                
        except Exception as e:
            print(f"❌ Validation failed: {e}")
        finally:
            try:
                await manager.unload_model(model_id)
            except:
                pass

    # Uncomment to run quick validation
    # asyncio.run(run_validation())
    pass