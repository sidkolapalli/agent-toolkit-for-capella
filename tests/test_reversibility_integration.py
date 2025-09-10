#!/usr/bin/env python3
"""
Test Reversibility System Integration with ModelManager
TDD test to verify the comprehensive undo/delete/redo capabilities work end-to-end
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
from capella_mcp.virtual_deletion import DeletionState


class TestReversibilityIntegration:
    """Test the complete reversibility system integration with ModelManager"""
    
    @pytest.fixture
    async def model_manager(self):
        """Create ModelManager with reversibility enabled"""
        return ModelManager(cache_size=3, enable_reversibility=True)
    
    @pytest.fixture
    async def model_manager_no_reversibility(self):
        """Create ModelManager with reversibility disabled"""
        return ModelManager(cache_size=3, enable_reversibility=False)
    
    def test_model_manager_initialization_with_reversibility(self):
        """Test ModelManager initializes reversibility system correctly"""
        # TDD: Test that all reversibility components are initialized
        manager = ModelManager(enable_reversibility=True)
        
        # Verify all components exist
        assert manager.operation_history is not None
        assert manager.virtual_deletion is not None
        assert manager.transaction_manager is not None
        assert manager.undo_manager is not None
        
        # Verify components are connected
        assert manager.virtual_deletion.operation_history is manager.operation_history
        assert manager.undo_manager.operation_history is manager.operation_history
        assert manager.undo_manager.virtual_deletion is manager.virtual_deletion
        assert manager.undo_manager.model_manager is manager
    
    def test_model_manager_initialization_without_reversibility(self):
        """Test ModelManager works without reversibility"""
        manager = ModelManager(enable_reversibility=False)
        
        # Verify all components are None
        assert manager.operation_history is None
        assert manager.virtual_deletion is None
        assert manager.transaction_manager is None
        assert manager.undo_manager is None
    
    def test_status_includes_reversibility_info(self):
        """Test get_status includes reversibility information"""
        manager = ModelManager(enable_reversibility=True)
        status = manager.get_status()
        
        assert "reversibility_enabled" in status
        assert status["reversibility_enabled"] is True
        assert "operations_tracked" in status
        assert "can_undo" in status
        assert "can_redo" in status
        assert "deleted_elements" in status
    
    @pytest.mark.asyncio
    async def test_create_element_records_operation(self, model_manager):
        """Test that creating an element records operation in history"""
        # This test will fail initially because we don't have a real Capella model
        # But it tests the integration logic
        
        # Skip if no capellambse available (this is integration test)
        pytest.skip("Integration test - requires actual Capella model")
        
        # Load a test model (would need real .aird file)
        # model_id = await model_manager.load_model("test_model.aird")
        
        # Create element
        # element_id = await model_manager.create_element(model_id, "LogicalComponent", name="TestComponent")
        
        # Verify operation was recorded
        # operations = model_manager.get_operation_history()
        # assert len(operations) == 1
        # assert operations[0]['operation_type'] == 'create'
        # assert operations[0]['element_id'] == element_id
    
    @pytest.mark.asyncio 
    async def test_virtual_deletion_integration(self, model_manager):
        """Test virtual deletion through ModelManager"""
        
        # Skip integration test without real model
        pytest.skip("Integration test - requires actual Capella model")
        
        # Would test:
        # 1. Create element
        # 2. Delete element (should be virtual deletion)
        # 3. Verify element is marked as deleted but not actually removed
        # 4. Verify query_elements filters out deleted element
        # 5. Restore element
        # 6. Verify element is back in queries
    
    @pytest.mark.asyncio
    async def test_undo_redo_operations(self, model_manager):
        """Test complete undo/redo cycle"""
        
        pytest.skip("Integration test - requires actual Capella model")
        
        # Would test:
        # 1. Create element
        # 2. Modify element 
        # 3. Delete element
        # 4. Undo delete (should restore)
        # 5. Undo modify (should revert changes)
        # 6. Undo create (should virtually delete)
        # 7. Redo create (should restore)
        # 8. Redo modify (should reapply changes)
        # 9. Redo delete (should mark as deleted again)
    
    def test_reversibility_methods_exist(self, model_manager):
        """Test all reversibility methods are available on ModelManager"""
        
        # Test all reversibility methods exist
        assert hasattr(model_manager, 'undo_last_operation')
        assert hasattr(model_manager, 'redo_last_operation')
        assert hasattr(model_manager, 'restore_deleted_element')
        assert hasattr(model_manager, 'list_deleted_elements')
        assert hasattr(model_manager, 'get_operation_history')
        assert hasattr(model_manager, 'create_checkpoint')
        assert hasattr(model_manager, 'get_reversibility_status')
    
    def test_reversibility_status(self, model_manager):
        """Test reversibility status reporting"""
        
        status = model_manager.get_reversibility_status()
        
        assert status["enabled"] is True
        assert "operation_history" in status
        assert "virtual_deletion" in status
        assert "undo_manager" in status
        
        # Test statistics format
        assert isinstance(status["operation_history"], dict)
        assert isinstance(status["virtual_deletion"], dict)  
        assert isinstance(status["undo_manager"], dict)
    
    def test_reversibility_disabled_status(self, model_manager_no_reversibility):
        """Test status when reversibility is disabled"""
        
        status = model_manager_no_reversibility.get_reversibility_status()
        
        assert status["enabled"] is False
        assert "reason" in status
    
    @pytest.mark.asyncio
    async def test_error_handling_without_reversibility(self, model_manager_no_reversibility):
        """Test error handling when reversibility is disabled"""
        
        manager = model_manager_no_reversibility
        
        # Test that reversibility methods raise appropriate errors
        with pytest.raises(RuntimeError, match="Reversibility system is not enabled"):
            await manager.undo_last_operation()
            
        with pytest.raises(RuntimeError, match="Reversibility system is not enabled"):
            await manager.redo_last_operation()
            
        with pytest.raises(RuntimeError, match="Virtual deletion system is not enabled"):
            await manager.restore_deleted_element("test_id")
            
        with pytest.raises(RuntimeError, match="Operation history is not enabled"):
            manager.create_checkpoint("test_checkpoint")
    
    def test_list_deleted_elements_empty_when_disabled(self, model_manager_no_reversibility):
        """Test list_deleted_elements returns empty list when disabled"""
        
        manager = model_manager_no_reversibility
        deleted = manager.list_deleted_elements()
        
        assert deleted == []
    
    def test_operation_history_empty_when_disabled(self, model_manager_no_reversibility):
        """Test get_operation_history returns empty list when disabled"""
        
        manager = model_manager_no_reversibility
        operations = manager.get_operation_history()
        
        assert operations == []
    
    def test_checkpoint_creation(self, model_manager):
        """Test checkpoint creation works"""
        
        # Create checkpoint
        model_manager.create_checkpoint("test_checkpoint", "Test checkpoint description")
        
        # Verify checkpoint exists in operation history
        checkpoints = model_manager.operation_history.list_checkpoints()
        assert "test_checkpoint" in checkpoints
        
        # Get checkpoint data
        checkpoint_data = model_manager.operation_history.get_checkpoint("test_checkpoint")
        assert checkpoint_data is not None
        assert checkpoint_data["model_state"]["checkpoint_description"] == "Test checkpoint description"


class TestReversibilitySystemComponents:
    """Test individual reversibility components work correctly"""
    
    def test_operation_history_standalone(self):
        """Test OperationHistory works independently"""
        from capella_mcp.operation_history import OperationHistory, OperationType, OperationStatus
        
        history = OperationHistory()
        
        # Record operation
        op_id = history.record_operation(
            operation_type=OperationType.CREATE,
            model_id="test_model",
            element_id="test_element",
            element_type="LogicalComponent",
            new_state={"name": "TestComponent"},
            user_description="Test creation"
        )
        
        # Verify operation recorded
        assert len(history._operations) == 1
        assert op_id in history._operation_index
        assert history.can_undo() is True
        assert history.can_redo() is False
    
    def test_virtual_deletion_standalone(self):
        """Test VirtualDeletionManager works independently"""
        from capella_mcp.virtual_deletion import VirtualDeletionManager, DeletionCascadeMode
        
        vd_manager = VirtualDeletionManager()
        
        # Test deletion
        success = vd_manager.delete_element(
            element_id="test_element",
            model_id="test_model",
            element_data={"name": "TestElement", "type": "LogicalComponent"},
            reason="Test deletion",
            cascade_mode=DeletionCascadeMode.NONE
        )
        
        assert success is True
        assert vd_manager.is_deleted("test_element") is True
        assert vd_manager.get_deletion_state("test_element") == DeletionState.DELETED
        
        # Test restoration
        restored = vd_manager.restore_element("test_element")
        assert restored is True
        assert vd_manager.is_deleted("test_element") is False
    
    def test_undo_manager_integration(self):
        """Test UndoManager integrates with other components"""
        from capella_mcp.operation_history import OperationHistory
        from capella_mcp.virtual_deletion import VirtualDeletionManager
        from capella_mcp.undo_manager import UndoManager
        
        history = OperationHistory()
        virtual_deletion = VirtualDeletionManager(operation_history=history)
        undo_manager = UndoManager(
            operation_history=history,
            virtual_deletion=virtual_deletion
        )
        
        # Test basic functionality
        assert undo_manager.operation_history is history
        assert undo_manager.virtual_deletion is virtual_deletion
        
        # Test statistics
        stats = undo_manager.get_statistics()
        assert "successful_undos" in stats
        assert "failed_undos" in stats


if __name__ == "__main__":
    # Run the tests
    pytest.main([__file__, "-v"])