"""
Undo Manager
Central coordinator for all undo/redo operations across the reversibility system
"""

import asyncio
import logging
from typing import Any, Dict, List, Optional, Union, Callable
from datetime import datetime
from dataclasses import dataclass
from enum import Enum

from .operation_history import OperationHistory, OperationType, OperationRecord, OperationStatus
# Virtual deletion system removed - using actual deletion only
from .transaction_manager import TransactionManager

logger = logging.getLogger(__name__)

class UndoResult(Enum):
    """Results of undo operations"""
    SUCCESS = "success"
    FAILED = "failed"
    NOT_UNDOABLE = "not_undoable"
    DEPENDENCY_ERROR = "dependency_error"
    MODEL_ERROR = "model_error"

@dataclass
class UndoOperation:
    """Details of an undo operation"""
    operation_id: str
    operation_type: OperationType
    description: str
    can_undo: bool
    undo_method: Optional[Callable] = None
    dependencies: List[str] = None

class UndoManager:
    """
    Central manager for all undo/redo operations
    Coordinates between operation history, actual deletion, and model operations
    """
    
    def __init__(self,
                 operation_history: OperationHistory,
                 transaction_manager: Optional[TransactionManager] = None,
                 model_manager = None):
        
        self.operation_history = operation_history
        self.transaction_manager = transaction_manager
        self.model_manager = model_manager
        
        # Undo handlers by operation type
        self._undo_handlers = {
            OperationType.CREATE: self._undo_create,
            OperationType.MODIFY: self._undo_modify,
            OperationType.DELETE: self._undo_delete,
            OperationType.RESTORE: self._undo_restore,
            OperationType.BATCH: self._undo_batch,
            OperationType.TRANSACTION: self._undo_transaction
        }
        
        # Statistics
        self._stats = {
            'successful_undos': 0,
            'failed_undos': 0,
            'successful_redos': 0,
            'failed_redos': 0
        }
        
        logger.info("UndoManager initialized")
    
    async def undo_last_operation(self) -> Dict[str, Any]:
        """
        Undo the last operation
        
        Returns:
            Result dictionary with status, message, and operation details
        """
        
        if not self.operation_history.can_undo():
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': 'No operations available to undo'
            }
        
        last_operation = self.operation_history.get_last_operation()
        if not last_operation:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': 'No last operation found'
            }
        
        return await self.undo_operation(last_operation.operation_id)
    
    async def undo_operation(self, operation_id: str) -> Dict[str, Any]:
        """
        Undo a specific operation
        
        Args:
            operation_id: ID of operation to undo
            
        Returns:
            Result dictionary with status, message, and operation details
        """
        
        # Prepare the undo
        operation = self.operation_history.prepare_undo(operation_id)
        if not operation:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': f'Operation {operation_id} cannot be undone'
            }
        
        try:
            # Start transaction if available
            if self.transaction_manager:
                await self.transaction_manager.begin_transaction()
            
            # Execute the undo
            result = await self._execute_undo(operation)
            
            if result['success']:
                # Mark operation as undone
                self.operation_history.mark_operation_undone(operation_id)
                
                # Commit transaction
                if self.transaction_manager:
                    await self.transaction_manager.commit_transaction()
                
                self._stats['successful_undos'] += 1
                logger.info(f"Successfully undid operation: {operation_id}")
                
            else:
                # Rollback transaction
                if self.transaction_manager:
                    await self.transaction_manager.rollback_transaction()
                
                self._stats['failed_undos'] += 1
                logger.error(f"Failed to undo operation: {operation_id}")
            
            return result
            
        except Exception as e:
            # Rollback on exception
            if self.transaction_manager:
                await self.transaction_manager.rollback_transaction()
                
            self._stats['failed_undos'] += 1
            logger.error(f"Exception during undo of {operation_id}: {e}")
            
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Exception during undo: {str(e)}',
                'operation_id': operation_id,
                'operation_type': operation.operation_type.value
            }
    
    async def redo_last_operation(self) -> Dict[str, Any]:
        """
        Redo the last undone operation
        
        Returns:
            Result dictionary with status, message, and operation details
        """
        
        if not self.operation_history.can_redo():
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': 'No operations available to redo'
            }
        
        next_operation = self.operation_history.get_next_redo_operation()
        if not next_operation:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': 'No next redo operation found'
            }
        
        return await self.redo_operation(next_operation.operation_id)
    
    async def redo_operation(self, operation_id: str) -> Dict[str, Any]:
        """
        Redo a specific operation
        
        Args:
            operation_id: ID of operation to redo
            
        Returns:
            Result dictionary with status, message, and operation details
        """
        
        # Get the operation
        operation = self.operation_history._operation_index.get(operation_id)
        if not operation:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': f'Operation {operation_id} not found'
            }
        
        if operation.operation_status != OperationStatus.UNDONE:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': f'Operation {operation_id} is not undone, cannot redo'
            }
        
        try:
            # Start transaction if available
            if self.transaction_manager:
                await self.transaction_manager.begin_transaction()
            
            # Execute the redo (which is essentially re-executing the original operation)
            result = await self._execute_redo(operation)
            
            if result['success']:
                # Mark operation as redone
                self.operation_history.mark_operation_redone(operation_id)
                
                # Commit transaction
                if self.transaction_manager:
                    await self.transaction_manager.commit_transaction()
                
                self._stats['successful_redos'] += 1
                logger.info(f"Successfully redid operation: {operation_id}")
                
            else:
                # Rollback transaction
                if self.transaction_manager:
                    await self.transaction_manager.rollback_transaction()
                
                self._stats['failed_redos'] += 1
                logger.error(f"Failed to redo operation: {operation_id}")
            
            return result
            
        except Exception as e:
            # Rollback on exception
            if self.transaction_manager:
                await self.transaction_manager.rollback_transaction()
                
            self._stats['failed_redos'] += 1
            logger.error(f"Exception during redo of {operation_id}: {e}")
            
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Exception during redo: {str(e)}',
                'operation_id': operation_id,
                'operation_type': operation.operation_type.value
            }
    
    async def undo_to_checkpoint(self, checkpoint_id: str) -> Dict[str, Any]:
        """
        Undo all operations back to a specific checkpoint
        
        Args:
            checkpoint_id: ID of checkpoint to restore to
            
        Returns:
            Result dictionary with status and operations undone
        """
        
        checkpoint = self.operation_history.get_checkpoint(checkpoint_id)
        if not checkpoint:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': f'Checkpoint {checkpoint_id} not found'
            }
        
        # Find operations to undo (all operations after checkpoint)
        checkpoint_time = datetime.fromisoformat(checkpoint['timestamp'])
        operations_to_undo = [
            op for op in self.operation_history.get_operation_history()
            if op.timestamp > checkpoint_time and op.operation_status == OperationStatus.COMPLETED
        ]
        
        # Sort by timestamp (newest first for undo)
        operations_to_undo.sort(key=lambda x: x.timestamp, reverse=True)
        
        undone_operations = []
        failed_operations = []
        
        try:
            # Start batch transaction
            if self.transaction_manager:
                await self.transaction_manager.begin_transaction()
            
            # Undo each operation
            for operation in operations_to_undo:
                result = await self._execute_undo(operation)
                if result['success']:
                    self.operation_history.mark_operation_undone(operation.operation_id)
                    undone_operations.append(operation.operation_id)
                else:
                    failed_operations.append({
                        'operation_id': operation.operation_id,
                        'error': result.get('message', 'Unknown error')
                    })
            
            if failed_operations:
                # Rollback if any failures
                if self.transaction_manager:
                    await self.transaction_manager.rollback_transaction()
                
                return {
                    'success': False,
                    'result': UndoResult.FAILED,
                    'message': f'Failed to undo {len(failed_operations)} operations',
                    'undone_operations': undone_operations,
                    'failed_operations': failed_operations
                }
            else:
                # Commit if all successful
                if self.transaction_manager:
                    await self.transaction_manager.commit_transaction()
                
                return {
                    'success': True,
                    'result': UndoResult.SUCCESS,
                    'message': f'Undid {len(undone_operations)} operations to checkpoint {checkpoint_id}',
                    'undone_operations': undone_operations,
                    'checkpoint_id': checkpoint_id
                }
        
        except Exception as e:
            if self.transaction_manager:
                await self.transaction_manager.rollback_transaction()
                
            logger.error(f"Exception during checkpoint undo: {e}")
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Exception during checkpoint undo: {str(e)}'
            }
    
    async def _execute_undo(self, operation: OperationRecord) -> Dict[str, Any]:
        """Execute the actual undo logic for an operation"""
        
        handler = self._undo_handlers.get(operation.operation_type)
        if not handler:
            return {
                'success': False,
                'result': UndoResult.NOT_UNDOABLE,
                'message': f'No undo handler for operation type: {operation.operation_type.value}'
            }
        
        try:
            result = await handler(operation)
            result['operation_id'] = operation.operation_id
            result['operation_type'] = operation.operation_type.value
            return result
            
        except Exception as e:
            logger.error(f"Exception in undo handler for {operation.operation_type}: {e}")
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Handler exception: {str(e)}',
                'operation_id': operation.operation_id,
                'operation_type': operation.operation_type.value
            }
    
    async def _execute_redo(self, operation: OperationRecord) -> Dict[str, Any]:
        """Execute the actual redo logic for an operation"""
        
        # For most operations, redo means re-applying the new_state
        try:
            if operation.operation_type == OperationType.CREATE:
                return await self._redo_create(operation)
            elif operation.operation_type == OperationType.MODIFY:
                return await self._redo_modify(operation)
            elif operation.operation_type == OperationType.DELETE:
                return await self._redo_delete(operation)
            elif operation.operation_type == OperationType.RESTORE:
                return await self._redo_restore(operation)
            else:
                return {
                    'success': False,
                    'result': UndoResult.NOT_UNDOABLE,
                    'message': f'Redo not implemented for {operation.operation_type.value}'
                }
        
        except Exception as e:
            logger.error(f"Exception in redo for {operation.operation_type}: {e}")
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Redo exception: {str(e)}'
            }
    
    # Undo handlers for each operation type
    
    async def _undo_create(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a create operation by actually deleting the created element"""
        
        if not operation.element_id:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No element_id for create operation'
            }
        
        # Actually delete the created element using actual deletion manager
        if (self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            success = self.model_manager.actual_deletion_manager.delete_element_actual(
                model_id=operation.model_id,
                element_id=operation.element_id,
                create_backup=True
            )
            
            return {
                'success': success,
                'result': UndoResult.SUCCESS if success else UndoResult.FAILED,
                'message': f'{"Successfully" if success else "Failed to"} undo create operation'
            }
        
        # No deletion system available
        return {
            'success': False,
            'result': UndoResult.NOT_UNDOABLE,
            'message': 'No actual deletion system available to undo create operation'
        }
    
    async def _undo_modify(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a modify operation by restoring old state"""
        
        if not operation.element_id or not operation.old_state:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'Missing element_id or old_state for modify operation'
            }
        
        if not self.model_manager:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No model_manager available for modify undo'
            }
        
        try:
            # Restore the old state
            await self.model_manager.modify_element(
                model_id=operation.model_id,
                element_id=operation.element_id,
                updates=operation.old_state
            )
            
            return {
                'success': True,
                'result': UndoResult.SUCCESS,
                'message': 'Successfully undid modify operation'
            }
            
        except Exception as e:
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Failed to restore old state: {str(e)}'
            }
    
    async def _undo_delete(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a delete operation - handles both actual and virtual deletion"""
        
        if not operation.element_id:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No element_id for delete operation'
            }
        
        # Check if this was an actual deletion (has backup_path in undo_data)
        if (operation.undo_data and 
            operation.undo_data.get('backup_path') and 
            self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            # Use actual deletion manager's undo capability
            success = self.model_manager.actual_deletion_manager.undo_actual_deletion(operation.element_id)
            
            return {
                'success': success,
                'result': UndoResult.SUCCESS if success else UndoResult.FAILED,
                'message': f'{"Successfully" if success else "Failed to"} undo actual deletion'
            }
        
        # No fallback needed - actual deletion is the only system
        
        # No deletion system available
        return {
            'success': False,
            'result': UndoResult.NOT_UNDOABLE,
            'message': 'No deletion system available to undo deletion'
        }
    
# _undo_virtual_delete removed - virtual deletion system no longer used
    
    async def _undo_restore(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a restore operation by re-deleting the element using actual deletion"""
        
        if not operation.element_id:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No element_id for restore operation'
            }
        
        # Re-delete the restored element using actual deletion
        if (self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            success = self.model_manager.actual_deletion_manager.delete_element_actual(
                model_id=operation.model_id,
                element_id=operation.element_id,
                create_backup=True
            )
            
            return {
                'success': success,
                'result': UndoResult.SUCCESS if success else UndoResult.FAILED,
                'message': f'{"Successfully" if success else "Failed to"} undo restore operation'
            }
        
        # No deletion system available
        return {
            'success': False,
            'result': UndoResult.NOT_UNDOABLE,
            'message': 'No actual deletion system available to undo restore operation'
        }
    
    async def _undo_batch(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a batch operation by undoing all operations in reverse order"""
        
        if not operation.undo_data or 'operation_ids' not in operation.undo_data:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No operation_ids in batch undo_data'
            }
        
        operation_ids = operation.undo_data['operation_ids']
        undone_ops = []
        failed_ops = []
        
        # Undo in reverse order
        for op_id in reversed(operation_ids):
            result = await self.undo_operation(op_id)
            if result['success']:
                undone_ops.append(op_id)
            else:
                failed_ops.append({'operation_id': op_id, 'error': result.get('message', 'Unknown error')})
        
        success = len(failed_ops) == 0
        return {
            'success': success,
            'result': UndoResult.SUCCESS if success else UndoResult.FAILED,
            'message': f'Batch undo: {len(undone_ops)} succeeded, {len(failed_ops)} failed',
            'undone_operations': undone_ops,
            'failed_operations': failed_ops
        }
    
    async def _undo_transaction(self, operation: OperationRecord) -> Dict[str, Any]:
        """Undo a transaction operation"""
        
        if not self.transaction_manager:
            return {
                'success': False,
                'result': UndoResult.MODEL_ERROR,
                'message': 'No transaction manager available'
            }
        
        try:
            await self.transaction_manager.rollback_transaction()
            return {
                'success': True,
                'result': UndoResult.SUCCESS,
                'message': 'Successfully rolled back transaction'
            }
        except Exception as e:
            return {
                'success': False,
                'result': UndoResult.FAILED,
                'message': f'Failed to rollback transaction: {str(e)}'
            }
    
    # Redo handlers
    
    async def _redo_create(self, operation: OperationRecord) -> Dict[str, Any]:
        """Redo create by restoring element if actually deleted"""
        if (operation.element_id and 
            self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            # Check if element is in deletion manifest (actually deleted)
            if operation.element_id in self.model_manager.actual_deletion_manager.deletion_manifest:
                success = self.model_manager.actual_deletion_manager.undo_actual_deletion(operation.element_id)
                return {
                    'success': success,
                    'result': UndoResult.SUCCESS if success else UndoResult.FAILED,
                    'message': f'{"Successfully" if success else "Failed to"} redo create'
                }
        
        return {'success': False, 'result': UndoResult.NOT_UNDOABLE, 'message': 'Element not actually deleted'}
    
    async def _redo_modify(self, operation: OperationRecord) -> Dict[str, Any]:
        """Redo modify by re-applying new state"""
        if not self.model_manager or not operation.element_id or not operation.new_state:
            return {'success': False, 'result': UndoResult.MODEL_ERROR, 'message': 'Missing data for modify redo'}
        
        try:
            await self.model_manager.modify_element(
                model_id=operation.model_id,
                element_id=operation.element_id,
                updates=operation.new_state
            )
            return {'success': True, 'result': UndoResult.SUCCESS, 'message': 'Successfully redid modify'}
        except Exception as e:
            return {'success': False, 'result': UndoResult.FAILED, 'message': f'Redo modify failed: {str(e)}'}
    
    async def _redo_delete(self, operation: OperationRecord) -> Dict[str, Any]:
        """Redo actual delete"""
        if not operation.element_id:
            return {'success': False, 'result': UndoResult.MODEL_ERROR, 'message': 'No element_id'}
        
        # Redo actual deletion
        if (self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            success = self.model_manager.actual_deletion_manager.delete_element_actual(
                model_id=operation.model_id,
                element_id=operation.element_id,
                create_backup=True
            )
            
            return {'success': success, 'result': UndoResult.SUCCESS if success else UndoResult.FAILED}
        
        return {'success': False, 'result': UndoResult.NOT_UNDOABLE, 'message': 'No actual deletion system available'}
    
    async def _redo_restore(self, operation: OperationRecord) -> Dict[str, Any]:
        """Redo restore using actual deletion undo"""
        if not operation.element_id:
            return {'success': False, 'result': UndoResult.MODEL_ERROR, 'message': 'No element_id'}
        
        # Redo restore by using actual deletion undo capability
        if (self.model_manager and 
            hasattr(self.model_manager, 'actual_deletion_manager') and
            self.model_manager.actual_deletion_manager):
            
            success = self.model_manager.actual_deletion_manager.undo_actual_deletion(operation.element_id)
            return {'success': success, 'result': UndoResult.SUCCESS if success else UndoResult.FAILED}
        
        return {'success': False, 'result': UndoResult.NOT_UNDOABLE, 'message': 'No actual deletion system available'}
    
    def get_undo_preview(self, count: int = 1) -> List[UndoOperation]:
        """Get preview of operations that would be undone"""
        
        operations = []
        history = self.operation_history.get_operation_history(limit=count)
        
        for op in history:
            if op.operation_status == OperationStatus.COMPLETED:
                operations.append(UndoOperation(
                    operation_id=op.operation_id,
                    operation_type=op.operation_type,
                    description=op.user_description or f"{op.operation_type.value} on {op.element_id}",
                    can_undo=op.is_undoable,
                    dependencies=op.depends_on or []
                ))
        
        return operations
    
    def get_redo_preview(self, count: int = 1) -> List[UndoOperation]:
        """Get preview of operations that would be redone"""
        
        operations = []
        history = self.operation_history.get_operation_history()
        
        # Filter for undone operations
        undone_ops = [op for op in history if op.operation_status == OperationStatus.UNDONE]
        undone_ops.sort(key=lambda x: x.timestamp)  # Oldest first for redo
        
        for op in undone_ops[:count]:
            operations.append(UndoOperation(
                operation_id=op.operation_id,
                operation_type=op.operation_type,
                description=op.user_description or f"{op.operation_type.value} on {op.element_id}",
                can_undo=True,  # Undone operations can be redone
                dependencies=op.depends_on or []
            ))
        
        return operations
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get undo manager statistics"""
        stats = self._stats.copy()
        stats.update({
            'can_undo': self.operation_history.can_undo(),
            'can_redo': self.operation_history.can_redo(),
            'operations_available': len(self.operation_history._undo_stack),
            'redo_available': len(self.operation_history._redo_stack),
            'checkpoints_available': len(self.operation_history.list_checkpoints())
        })
        return stats