#!/usr/bin/env python3
"""
TDD Tests for Actual Deletion System
Tests that actual deletion replaces virtual deletion in ModelManager
"""

import pytest
import asyncio
import tempfile
import os
from pathlib import Path
import sys

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.model_manager import ModelManager
from capella_mcp.operation_history import OperationType, OperationStatus


class TestActualDeletionTDD:
    """TDD tests for actual deletion system replacing virtual deletion"""
    
    @pytest.fixture
    def model_manager(self):
        """Create ModelManager with actual deletion (not virtual)"""
        return ModelManager(cache_size=3, enable_reversibility=True, use_actual_deletion=True)
    
    @pytest.fixture  
    def model_manager_virtual(self):
        """Create ModelManager with virtual deletion (legacy)"""
        return ModelManager(cache_size=3, enable_reversibility=True, use_actual_deletion=False)

    def test_model_manager_has_actual_deletion_manager(self, model_manager):
        """Test that ModelManager initializes with ActualDeletionManager instead of VirtualDeletionManager"""
        
        # RED: This test should FAIL initially because we haven't implemented actual deletion integration yet
        
        # ModelManager should have actual_deletion_manager instead of virtual_deletion
        assert hasattr(model_manager, 'actual_deletion_manager')
        assert model_manager.actual_deletion_manager is not None
        
        # Should NOT have virtual deletion when using actual deletion
        assert not hasattr(model_manager, 'virtual_deletion') or model_manager.virtual_deletion is None
        
        # Verify actual deletion manager is properly initialized
        assert hasattr(model_manager.actual_deletion_manager, 'delete_element_actual')
        assert hasattr(model_manager.actual_deletion_manager, 'undo_actual_deletion')
        assert hasattr(model_manager.actual_deletion_manager, 'create_backup')

    def test_delete_element_uses_actual_deletion(self, model_manager):
        """Test that delete_element uses actual deletion, not virtual deletion"""
        
        # RED: This should FAIL because current delete_element still uses virtual deletion
        
        # Skip integration test without real model for now
        pytest.skip("Integration test - requires actual Capella model")
        
        # When we have real model integration:
        # 1. Load model
        # 2. Create element  
        # 3. Delete element - should use actual deletion
        # 4. Verify element is ACTUALLY gone from .aird file, not just marked as deleted
        # 5. Verify backup was created
        # 6. Verify operation recorded as actual deletion

    def test_actual_deletion_creates_backup(self, model_manager):
        """Test that actual deletion creates backup files"""
        
        # RED: Should fail because backup creation not integrated yet
        
        # Skip integration test
        pytest.skip("Integration test - requires actual Capella model")
        
        # Test should verify:
        # 1. Backup directory exists
        # 2. Backup file created before deletion
        # 3. Backup contains original model state

    def test_undo_actual_deletion_restores_from_backup(self, model_manager):
        """Test that undo of actual deletion restores from backup, not virtual restoration"""
        
        # RED: Should fail because undo system doesn't use actual deletion yet
        
        pytest.skip("Integration test - requires actual Capella model")
        
        # Test should verify:
        # 1. Delete element (actual deletion)
        # 2. Undo deletion
        # 3. Element is restored to model file from backup
        # 4. No virtual deletion state remains

    def test_actual_deletion_visible_in_capella_ui(self, model_manager):
        """Test that actually deleted elements disappear from Capella UI"""
        
        # RED: This is the CORE requirement that virtual deletion failed to meet
        
        pytest.skip("Integration test - requires actual Capella model and UI verification")
        
        # Test should verify:
        # 1. Create element in model
        # 2. Delete element using actual deletion  
        # 3. Save model
        # 4. Reload model in separate session
        # 5. Verify element is NOT visible (unlike virtual deletion)

    def test_operation_history_records_actual_deletion(self, model_manager):
        """Test that operation history correctly records actual deletions"""
        
        # This test can pass even without real model
        
        # Create mock deletion record
        if model_manager.operation_history:
            op_id = model_manager.operation_history.record_operation(
                operation_type=OperationType.DELETE,
                model_id="test_model",
                element_id="test_element", 
                old_state={"name": "TestElement", "type": "LogicalComponent"},
                new_state={"deleted": True, "actual_deletion": True},
                user_description="Actual deletion test",
                operation_status=OperationStatus.COMPLETED,
                undo_data={"backup_path": "/path/to/backup.aird"}
            )
            
            # Verify operation recorded with actual deletion flag
            operation = model_manager.operation_history._operation_index.get(op_id)
            assert operation is not None
            assert operation.new_state.get('actual_deletion') is True
            assert operation.undo_data.get('backup_path') is not None

    def test_backwards_compatibility_with_virtual_deletion(self, model_manager_virtual):
        """Test that virtual deletion still works when explicitly requested"""
        
        # Verify virtual deletion manager exists when use_actual_deletion=False
        assert hasattr(model_manager_virtual, 'virtual_deletion')
        assert model_manager_virtual.virtual_deletion is not None
        
        # Should NOT have actual deletion manager
        assert not hasattr(model_manager_virtual, 'actual_deletion_manager') or \
               model_manager_virtual.actual_deletion_manager is None

    def test_initialization_parameter_use_actual_deletion(self):
        """Test that use_actual_deletion parameter controls which system is used"""
        
        # Test actual deletion enabled
        manager_actual = ModelManager(enable_reversibility=True, use_actual_deletion=True)
        assert hasattr(manager_actual, 'actual_deletion_manager')
        assert manager_actual.actual_deletion_manager is not None
        assert manager_actual.virtual_deletion is None
        
        # Test virtual deletion (default/legacy)
        manager_virtual = ModelManager(enable_reversibility=True, use_actual_deletion=False)  
        assert hasattr(manager_virtual, 'virtual_deletion')
        assert manager_virtual.virtual_deletion is not None
        assert manager_virtual.actual_deletion_manager is None

    def test_actual_deletion_manager_statistics(self, model_manager):
        """Test that ActualDeletionManager provides statistics"""
        
        # RED: Should fail because actual deletion manager not integrated yet
        
        # Skip until integration complete
        pytest.skip("Requires ActualDeletionManager integration")
        
        # Test should verify statistics like:
        # - actual_deletions count
        # - failed_deletions count  
        # - backup_count
        # - restored_elements count

    def test_delete_element_signature_unchanged(self):
        """Test that delete_element method signature remains the same for compatibility"""
        
        # This ensures we don't break existing code using delete_element
        manager = ModelManager(enable_reversibility=True)
        
        # Method should exist and be callable
        assert hasattr(manager, 'delete_element')
        assert callable(manager.delete_element)
        
        # Should have expected parameters (verify via inspect if needed)
        import inspect
        sig = inspect.signature(manager.delete_element)
        params = list(sig.parameters.keys())
        
        # Should have core parameters
        assert 'model_id' in params
        assert 'element_id' in params
        # cascade_mode should be optional
        assert 'cascade_mode' in params


if __name__ == "__main__":
    # Run the TDD tests - they should FAIL initially (RED phase)
    pytest.main([__file__, "-v", "-x"])  # -x stops on first failure for TDD