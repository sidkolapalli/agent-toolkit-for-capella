"""
TDD Tests for Stage 4: Model Modification Tools

This module provides comprehensive test coverage for safe model modification
capabilities including element creation, relationship establishment, 
consistency validation, and transaction management with rollback support.

Stage 4 implements write operations with safety guarantees:
1. Safe element creation with automatic rollback on failure
2. Relationship establishment with validation 
3. Model consistency checking before/after modifications
4. Transaction management with undo/redo capabilities

Test Strategy:
- Follow TDD methodology (RED → GREEN → REFACTOR)
- Test safety mechanisms and rollback scenarios
- Validate transaction boundaries and consistency
- Ensure MCP v0.5.0+ compliance with safety annotations
- Performance target: <5s for complex modification operations
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, Mock, patch
from typing import Dict, List, Any, Optional
import uuid
import json

# Test imports - these will be implemented after TDD design
try:
    from capella_mcp.server import create_element_with_transaction
except ImportError:
    # TDD Phase: Tool not yet implemented
    create_element_with_transaction = None

try:
    from capella_mcp.server import establish_element_relationship
except ImportError:
    establish_element_relationship = None

try:
    from capella_mcp.server import validate_model_consistency
except ImportError:
    validate_model_consistency = None

try:
    from capella_mcp.server import manage_modification_transaction
except ImportError:
    manage_modification_transaction = None

try:
    from capella_mcp.server import rollback_failed_modification
except ImportError:
    rollback_failed_modification = None


# Module-level fixtures for Stage 4 testing
@pytest.fixture
def mock_model_id():
    """Standard test model ID for all Stage 4 tests."""
    return "test_model_stage4"

@pytest.fixture
def mock_transaction_id():
    """Standard transaction ID for transaction management tests."""
    return f"txn_{uuid.uuid4().hex[:8]}"

@pytest.fixture
def sample_element_spec():
    """Sample element specification for creation tests."""
    return {
        "name": "TestComponent",
        "element_type": "LogicalComponent",
        "layer": "la",
        "description": "Test component for Stage 4 validation",
        "properties": {
            "is_human": False,
            "is_actor": False
        },
        "parent_id": None
    }

@pytest.fixture
def sample_relationship_spec():
    """Sample relationship specification for establishment tests."""
    return {
        "source_element_id": "test_source_123",
        "target_element_id": "test_target_456", 
        "relationship_type": "ComponentExchange",
        "properties": {
            "name": "TestExchange",
            "description": "Test exchange for Stage 4"
        }
    }


class TestStage4ModelModification:
    """
    Comprehensive test suite for Stage 4 Model Modification tools.
    
    Tests cover all planned Stage 4 capabilities:
    - Safe element creation with rollback
    - Relationship establishment with validation
    - Model consistency checking
    - Transaction management with undo/redo
    """


class TestSafeElementCreation:
    """
    Test suite for safe element creation with rollback capabilities.
    
    Tool: create_element_with_transaction
    Purpose: Create elements with automatic rollback on failure
    Safety: All operations must be reversible
    """

    @pytest.mark.asyncio
    async def test_create_element_with_transaction_success(self, mock_model_id, sample_element_spec):
        """Test successful element creation with transaction safety."""
        if create_element_with_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model manager and wrapper
            mock_model_manager = Mock()
            mock_wrapper = Mock()
            mock_model = Mock()
            
            # Set up the mock chain
            mock_model._modified = False
            mock_model.la.logical_component_pkg = Mock()
            
            # Mock created element
            mock_created_element = Mock()
            mock_created_element.uuid = "created_element_123"
            mock_created_element.name = "TestComponent"
            mock_created_element.parent = Mock()
            
            # Mock the create method to return our mock element
            mock_model.la.logical_component_pkg.create.return_value = mock_created_element
            
            # Mock model.by_uuid for validation
            mock_model.by_uuid.return_value = mock_created_element
            
            # Set up wrapper to return model
            mock_wrapper.model = mock_model
            mock_model_manager.get_model.return_value = mock_wrapper
            
            # Mock the model_manager
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                result = await create_element_with_transaction(
                    model_id=mock_model_id,
                    element_spec=sample_element_spec,
                    validate_before_commit=True,
                    auto_rollback_on_failure=True
                )
                
                # Print result for debugging
                print(f"Test result: {result}")
                
                # Verify successful creation
                assert result["success"] is True
                assert "element_id" in result
                assert "transaction_id" in result
                assert result["operation"] == "CREATE"
                assert result["rollback_available"] is True

    @pytest.mark.asyncio
    async def test_create_element_with_transaction_rollback(self, mock_model_id, sample_element_spec):
        """Test automatic rollback on element creation failure."""
        if create_element_with_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock creation failure
        with patch('capella_mcp.server.get_element_details') as mock_get:
            mock_get.side_effect = Exception("Model validation failed")
            
            result = await create_element_with_transaction(
                model_id=mock_model_id,
                element_spec=sample_element_spec,
                validate_before_commit=True,
                auto_rollback_on_failure=True
            )
            
            # Verify rollback occurred
            assert result["success"] is False
            assert result["rollback_executed"] is True
            assert "error" in result
            assert result["model_state"] == "restored"

    @pytest.mark.asyncio
    async def test_create_element_validation_before_commit(self, mock_model_id, sample_element_spec):
        """Test pre-commit validation prevents invalid modifications."""
        if create_element_with_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Test with invalid element spec
        invalid_spec = sample_element_spec.copy()
        invalid_spec["element_type"] = "InvalidType"
        
        result = await create_element_with_transaction(
            model_id=mock_model_id,
            element_spec=invalid_spec,
            validate_before_commit=True,
            auto_rollback_on_failure=True
        )
        
        # Verify validation prevented commit
        assert result["success"] is False
        assert result["validation_failed"] is True
        assert "validation_errors" in result

    @pytest.mark.asyncio
    async def test_create_element_performance_target(self, mock_model_id, sample_element_spec):
        """Test element creation meets <5s performance target."""
        if create_element_with_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        import time
        start_time = time.time()
        
        with patch('capella_mcp.server.get_element_details') as mock_get:
            mock_get.return_value = {"success": True, "element": {"uuid": "perf_test_123"}}
            
            result = await create_element_with_transaction(
                model_id=mock_model_id,
                element_spec=sample_element_spec,
                validate_before_commit=True,
                auto_rollback_on_failure=True
            )
            
        execution_time = time.time() - start_time
        
        # Verify performance target
        assert execution_time < 5.0, f"Creation took {execution_time:.2f}s, target <5s"
        assert result["execution_time"] < 5.0

    @pytest.mark.asyncio
    async def test_create_element_mcp_compliance(self, mock_model_id, sample_element_spec):
        """Test MCP v0.5.0+ compliance for modification tools."""
        if create_element_with_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Verify tool has proper MCP annotations
        tool_annotations = getattr(create_element_with_transaction, '__annotations__', {})
        
        # Stage 4 tools should have destructiveHint: true (they modify model)
        # Unlike read-only tools in Stages 1-3
        expected_annotations = {
            "destructiveHint": True,  # Modifies model state
            "idempotentHint": False   # Each creation is unique
        }
        
        # This test validates the MCP decorator configuration
        # Actual validation will be done during implementation


class TestRelationshipEstablishment:
    """
    Test suite for relationship establishment with validation.
    
    Tool: establish_element_relationship
    Purpose: Create relationships between existing elements with validation
    Safety: Validate both elements exist before creating relationship
    """

    @pytest.mark.asyncio
    async def test_establish_relationship_success(self, mock_model_id, sample_relationship_spec):
        """Test successful relationship establishment between valid elements."""
        if establish_element_relationship is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock the enhanced components availability
        with patch('capella_mcp.server.ENHANCED_AVAILABLE', True):
            # Mock model manager and wrapper
            mock_model_manager = Mock()
            mock_wrapper = Mock()
            mock_model = Mock()
            
            # Mock source and target elements
            mock_source_element = Mock()
            mock_source_element.uuid = "test_source_123"
            mock_source_element.parent = Mock()
            
            mock_target_element = Mock()
            mock_target_element.uuid = "test_target_456"
            mock_target_element.parent = Mock()
            
            # Mock created relationship
            mock_relationship = Mock()
            mock_relationship.uuid = "created_relationship_123"
            mock_relationship.name = "TestExchange"
            
            # Set up model mocking
            mock_model.by_uuid.side_effect = [mock_source_element, mock_target_element]
            mock_model.la.component_exchanges.create.return_value = mock_relationship
            mock_model._modified = False
            
            # Set up wrapper to return model
            mock_wrapper.model = mock_model
            mock_model_manager.get_model.return_value = mock_wrapper
            
            # Mock the model_manager
            with patch('capella_mcp.server.model_manager', mock_model_manager):
                result = await establish_element_relationship(
                    model_id=mock_model_id,
                    relationship_spec=sample_relationship_spec,
                    validate_elements_exist=True,
                    validate_relationship_type=True
                )
                
                # Print result for debugging
                print(f"Relationship test result: {result}")
                
                # Verify successful relationship creation
                assert result["success"] is True
                assert "relationship_id" in result
                assert result["source_validated"] is True
                assert result["target_validated"] is True
                assert result["relationship_type"] == "ComponentExchange"

    @pytest.mark.asyncio
    async def test_establish_relationship_invalid_source(self, mock_model_id, sample_relationship_spec):
        """Test relationship establishment fails when source element doesn't exist."""
        if establish_element_relationship is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock invalid source element
        with patch('capella_mcp.server.get_element_details') as mock_get:
            mock_get.side_effect = [
                {"success": False, "error": "Element not found"},  # Source invalid
                {"success": True, "element": {"uuid": "test_target_456"}}   # Target valid
            ]
            
            result = await establish_element_relationship(
                model_id=mock_model_id,
                relationship_spec=sample_relationship_spec,
                validate_elements_exist=True,
                validate_relationship_type=True
            )
            
            # Verify validation failure
            assert result["success"] is False
            assert result["source_validated"] is False
            assert "validation_errors" in result

    @pytest.mark.asyncio
    async def test_establish_relationship_type_validation(self, mock_model_id, sample_relationship_spec):
        """Test relationship type validation prevents invalid relationships."""
        if establish_element_relationship is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Test invalid relationship type
        invalid_spec = sample_relationship_spec.copy()
        invalid_spec["relationship_type"] = "InvalidRelationshipType"
        
        with patch('capella_mcp.server.get_element_details') as mock_get:
            mock_get.side_effect = [
                {"success": True, "element": {"uuid": "test_source_123", "type": "LogicalComponent"}},
                {"success": True, "element": {"uuid": "test_target_456", "type": "LogicalComponent"}}
            ]
            
            result = await establish_element_relationship(
                model_id=mock_model_id,
                relationship_spec=invalid_spec,
                validate_elements_exist=True,
                validate_relationship_type=True
            )
            
            # Verify type validation failure
            assert result["success"] is False
            assert result["relationship_type_valid"] is False
            assert "invalid_relationship_type" in result["validation_errors"]


class TestModelConsistencyValidation:
    """
    Test suite for model consistency checking before/after modifications.
    
    Tool: validate_model_consistency
    Purpose: Ensure model remains valid after modifications
    Safety: Comprehensive validation of model integrity
    """

    @pytest.mark.asyncio
    async def test_validate_model_consistency_clean_model(self, mock_model_id):
        """Test consistency validation on clean, valid model."""
        if validate_model_consistency is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock the loaded_models for mock mode fallback
        with patch('capella_mcp.server.loaded_models', {mock_model_id: {"path": "test_path", "name": "test_model"}}):
            result = await validate_model_consistency(
                model_id=mock_model_id,
                validation_scope="full",
                check_relationships=True,
                check_layer_compliance=True,
                check_naming_conventions=True
            )
        
            # Print result for debugging
            print(f"Validation test result: {result}")
            
            # Verify clean model validation
            assert result["success"] is True
            assert result["is_consistent"] is True
            assert result["validation_errors"] == []
            assert result["validation_warnings"] == []
            assert "validation_summary" in result

    @pytest.mark.asyncio
    async def test_validate_model_consistency_with_issues(self, mock_model_id):
        """Test consistency validation detects model issues."""
        if validate_model_consistency is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock model with consistency issues
        with patch('capella_mcp.server.get_model_elements') as mock_elements:
            mock_elements.return_value = {
                "elements": [
                    {"uuid": "broken_123", "name": "", "type": "LogicalComponent"},  # Empty name
                    {"uuid": "orphan_456", "parent": "nonexistent", "type": "LogicalFunction"}  # Orphaned
                ]
            }
            
            result = await validate_model_consistency(
                model_id=mock_model_id,
                validation_scope="full", 
                check_relationships=True,
                check_layer_compliance=True,
                check_naming_conventions=True
            )
            
            # Verify issues detected
            assert result["success"] is True  # Validation completed successfully
            assert result["is_consistent"] is False  # But model has issues
            assert len(result["validation_errors"]) > 0
            assert any("empty name" in error.lower() for error in result["validation_errors"])

    @pytest.mark.asyncio
    async def test_validate_model_consistency_arcadia_compliance(self, mock_model_id):
        """Test ARCADIA methodology compliance validation."""
        if validate_model_consistency is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        result = await validate_model_consistency(
            model_id=mock_model_id,
            validation_scope="arcadia",
            check_layer_compliance=True,
            check_operational_analysis=True,
            check_system_analysis=True,
            check_logical_architecture=True,
            check_physical_architecture=True
        )
        
        # Verify ARCADIA-specific validation
        assert result["success"] is True
        assert "arcadia_compliance" in result
        assert "layer_analysis" in result
        for layer in ["oa", "sa", "la", "pa"]:
            assert layer in result["layer_analysis"]


class TestTransactionManagement:
    """
    Test suite for transaction management with undo/redo capabilities.
    
    Tool: manage_modification_transaction
    Purpose: Provide safe transaction boundaries for complex modifications
    Safety: Atomic operations with full rollback support
    """

    @pytest.mark.asyncio
    async def test_transaction_commit_success(self, mock_model_id, mock_transaction_id):
        """Test successful transaction commit with multiple operations."""
        if manage_modification_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock successful transaction with multiple operations
        transaction_ops = [
            {"operation": "CREATE", "element_id": "new_component_123"},
            {"operation": "CREATE_RELATIONSHIP", "relationship_id": "new_exchange_456"},
            {"operation": "MODIFY", "element_id": "existing_789", "changes": {"name": "Updated"}}
        ]
        
        result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=mock_transaction_id,
            operation="COMMIT",
            transaction_operations=transaction_ops,
            validate_before_commit=True
        )
        
        # Verify successful commit
        assert result["success"] is True
        assert result["transaction_status"] == "COMMITTED"
        assert result["operations_applied"] == len(transaction_ops)
        assert result["rollback_available"] is True

    @pytest.mark.asyncio
    async def test_transaction_rollback_on_failure(self, mock_model_id, mock_transaction_id):
        """Test automatic transaction rollback when operation fails."""
        if manage_modification_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock transaction with failing operation
        transaction_ops = [
            {"operation": "CREATE", "element_id": "new_component_123"},
            {"operation": "CREATE_RELATIONSHIP", "relationship_id": "invalid_exchange", "error": "Invalid type"}
        ]
        
        result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=mock_transaction_id,
            operation="COMMIT",
            transaction_operations=transaction_ops,
            validate_before_commit=True,
            auto_rollback_on_failure=True
        )
        
        # Verify rollback occurred
        assert result["success"] is False
        assert result["transaction_status"] == "ROLLED_BACK"
        assert result["rollback_executed"] is True
        assert "failed_operation" in result

    @pytest.mark.asyncio
    async def test_transaction_undo_redo_capabilities(self, mock_model_id, mock_transaction_id):
        """Test undo/redo functionality for committed transactions."""
        if manage_modification_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Test UNDO operation
        undo_result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=mock_transaction_id,
            operation="UNDO",
            preserve_transaction_history=True
        )
        
        assert undo_result["success"] is True
        assert undo_result["operation"] == "UNDO"
        assert undo_result["redo_available"] is True
        
        # Test REDO operation
        redo_result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=mock_transaction_id,
            operation="REDO",
            preserve_transaction_history=True
        )
        
        assert redo_result["success"] is True
        assert redo_result["operation"] == "REDO"
        assert redo_result["model_state"] == "restored"

    @pytest.mark.asyncio
    async def test_transaction_boundary_isolation(self, mock_model_id):
        """Test transaction isolation prevents interference between concurrent modifications."""
        if manage_modification_transaction is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Create two separate transactions
        txn1_id = f"txn1_{uuid.uuid4().hex[:8]}"
        txn2_id = f"txn2_{uuid.uuid4().hex[:8]}"
        
        # Start both transactions
        txn1_result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=txn1_id,
            operation="BEGIN",
            isolation_level="READ_COMMITTED"
        )
        
        txn2_result = await manage_modification_transaction(
            model_id=mock_model_id,
            transaction_id=txn2_id,
            operation="BEGIN",
            isolation_level="READ_COMMITTED"
        )
        
        # Verify both transactions can coexist
        assert txn1_result["success"] is True
        assert txn2_result["success"] is True
        assert txn1_result["transaction_id"] != txn2_result["transaction_id"]
        assert txn1_result["isolation_level"] == "READ_COMMITTED"


class TestRollbackFailedModification:
    """
    Test suite for rollback capabilities for failed modifications.
    
    Tool: rollback_failed_modification
    Purpose: Recover from failed modifications by restoring previous state
    Safety: Comprehensive rollback with state verification
    """

    @pytest.mark.asyncio
    async def test_rollback_failed_creation(self, mock_model_id):
        """Test rollback of failed element creation."""
        if rollback_failed_modification is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock failed creation scenario
        failure_context = {
            "operation": "CREATE_ELEMENT",
            "element_spec": {"name": "FailedComponent", "type": "LogicalComponent"},
            "failure_point": "validation",
            "error": "Invalid element type",
            "partial_state": {"element_id": "temp_123"}
        }
        
        result = await rollback_failed_modification(
            model_id=mock_model_id,
            failure_context=failure_context,
            verify_state_restored=True,
            cleanup_partial_changes=True
        )
        
        # Verify successful rollback
        assert result["success"] is True
        assert result["rollback_completed"] is True
        assert result["state_verified"] is True
        assert result["partial_changes_cleaned"] is True

    @pytest.mark.asyncio
    async def test_rollback_complex_modification_chain(self, mock_model_id):
        """Test rollback of complex modification chain with multiple operations."""
        if rollback_failed_modification is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        # Mock complex failed operation chain
        failure_context = {
            "operation": "COMPLEX_MODIFICATION",
            "operation_chain": [
                {"step": 1, "operation": "CREATE_ELEMENT", "status": "SUCCESS", "element_id": "elem_123"},
                {"step": 2, "operation": "CREATE_RELATIONSHIP", "status": "SUCCESS", "relationship_id": "rel_456"},
                {"step": 3, "operation": "MODIFY_ELEMENT", "status": "FAILED", "error": "Constraint violation"}
            ],
            "failure_point": "step_3",
            "rollback_required": ["step_2", "step_1"]
        }
        
        result = await rollback_failed_modification(
            model_id=mock_model_id,
            failure_context=failure_context,
            verify_state_restored=True,
            cleanup_partial_changes=True
        )
        
        # Verify complex rollback
        assert result["success"] is True
        assert result["operations_rolled_back"] == 2
        assert result["rollback_sequence"] == ["step_2", "step_1"]
        assert result["final_state"] == "clean"

    @pytest.mark.asyncio
    async def test_rollback_performance_target(self, mock_model_id):
        """Test rollback operation meets <5s performance target."""
        if rollback_failed_modification is None:
            pytest.skip("Tool not yet implemented - TDD Phase")
            
        import time
        start_time = time.time()
        
        # Mock rollback scenario
        failure_context = {
            "operation": "BULK_MODIFICATION",
            "elements_affected": 50,  # Large operation to test performance
            "failure_point": "element_25",
            "rollback_required": list(range(1, 25))
        }
        
        result = await rollback_failed_modification(
            model_id=mock_model_id,
            failure_context=failure_context,
            verify_state_restored=True,
            cleanup_partial_changes=True
        )
        
        execution_time = time.time() - start_time
        
        # Verify performance target
        assert execution_time < 5.0, f"Rollback took {execution_time:.2f}s, target <5s"
        assert result["rollback_time"] < 5.0


class TestStage4Integration:
    """
    Integration tests for Stage 4 tools working together.
    
    Tests the complete workflow of safe model modification with
    transaction management, validation, and rollback capabilities.
    """

    @pytest.mark.asyncio
    async def test_complete_modification_workflow(self, mock_model_id):
        """Test complete workflow: create element → establish relationship → validate → commit."""
        # Skip if tools not implemented
        if not all([create_element_with_transaction, establish_element_relationship, 
                   validate_model_consistency, manage_modification_transaction]):
            pytest.skip("Stage 4 tools not yet implemented - TDD Phase")
            
        # This test will validate the complete Stage 4 workflow
        # Implementation will be added during GREEN phase
        pass

    @pytest.mark.asyncio
    async def test_modification_workflow_with_rollback(self, mock_model_id):
        """Test workflow with failure and automatic rollback."""
        # Skip if tools not implemented  
        if not all([create_element_with_transaction, establish_element_relationship,
                   rollback_failed_modification]):
            pytest.skip("Stage 4 tools not yet implemented - TDD Phase")
            
        # This test will validate failure handling and rollback
        # Implementation will be added during GREEN phase
        pass

    @pytest.mark.asyncio
    async def test_stage4_performance_integration(self, mock_model_id):
        """Test Stage 4 tools meet performance targets in integrated scenarios."""
        # Skip if tools not implemented
        if not all([create_element_with_transaction, establish_element_relationship,
                   validate_model_consistency, manage_modification_transaction]):
            pytest.skip("Stage 4 tools not yet implemented - TDD Phase")
            
        # This test will validate performance targets for complex operations
        # Target: <5s for complex modification workflows
        pass


if __name__ == "__main__":
    # Run TDD tests during development
    pytest.main([__file__, "-v", "--tb=short"])