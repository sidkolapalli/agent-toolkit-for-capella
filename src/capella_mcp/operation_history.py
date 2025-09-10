"""
Operation History System
Tracks every model operation with sufficient detail to enable undo/redo functionality
"""

import asyncio
import json
import logging
from dataclasses import dataclass, asdict
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Union
from pathlib import Path
import copy

logger = logging.getLogger(__name__)

class OperationType(Enum):
    """Types of operations that can be tracked"""
    CREATE = "create"
    MODIFY = "modify"
    DELETE = "delete"
    VIRTUAL_DELETE = "virtual_delete"
    RESTORE = "restore"
    BATCH = "batch"
    TRANSACTION = "transaction"

class OperationStatus(Enum):
    """Status of an operation"""
    PENDING = "pending"
    COMPLETED = "completed"
    FAILED = "failed"
    UNDONE = "undone"
    REDONE = "redone"

@dataclass
class OperationRecord:
    """Complete record of a model operation"""
    
    # Core identification
    operation_id: str
    operation_type: OperationType
    timestamp: datetime
    
    # Element information
    model_id: str
    element_id: Optional[str] = None
    element_type: Optional[str] = None
    parent_id: Optional[str] = None
    
    # State information
    old_state: Optional[Dict[str, Any]] = None
    new_state: Optional[Dict[str, Any]] = None
    properties_changed: Optional[List[str]] = None
    
    # Operation metadata
    operation_status: OperationStatus = OperationStatus.PENDING
    user_description: Optional[str] = None
    batch_id: Optional[str] = None
    transaction_id: Optional[str] = None
    
    # Undo/redo information
    is_undoable: bool = True
    undo_data: Optional[Dict[str, Any]] = None
    redo_count: int = 0
    
    # Dependencies and relationships
    depends_on: Optional[List[str]] = None  # Operation IDs this depends on
    affects: Optional[List[str]] = None     # Element IDs this affects
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization"""
        data = asdict(self)
        # Convert datetime and enums to serializable formats
        data['timestamp'] = self.timestamp.isoformat()
        data['operation_type'] = self.operation_type.value
        data['operation_status'] = self.operation_status.value
        return data
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'OperationRecord':
        """Create from dictionary"""
        # Convert back from serialized formats
        data = data.copy()
        data['timestamp'] = datetime.fromisoformat(data['timestamp'])
        data['operation_type'] = OperationType(data['operation_type'])
        data['operation_status'] = OperationStatus(data['operation_status'])
        return cls(**data)

class OperationHistory:
    """
    Comprehensive operation history manager
    Tracks all operations with full undo/redo capability
    """
    
    def __init__(self, max_operations: int = 1000, enable_persistence: bool = True):
        self.max_operations = max_operations
        self.enable_persistence = enable_persistence
        
        # Operation storage
        self._operations: List[OperationRecord] = []
        self._operation_index: Dict[str, OperationRecord] = {}
        
        # Undo/redo stacks
        self._undo_stack: List[str] = []  # Operation IDs
        self._redo_stack: List[str] = []  # Operation IDs
        
        # Checkpoints for major state saves
        self._checkpoints: Dict[str, Dict[str, Any]] = {}
        
        # Batch operations
        self._current_batch: Optional[str] = None
        self._batch_operations: List[str] = []
        
        # Statistics
        self._stats = {
            'total_operations': 0,
            'undone_operations': 0,
            'redone_operations': 0,
            'failed_operations': 0
        }
        
        logger.info(f"OperationHistory initialized with max_operations={max_operations}")
    
    def record_operation(self, 
                        operation_type: OperationType,
                        model_id: str,
                        element_id: Optional[str] = None,
                        element_type: Optional[str] = None,
                        old_state: Optional[Dict[str, Any]] = None,
                        new_state: Optional[Dict[str, Any]] = None,
                        user_description: Optional[str] = None,
                        **kwargs) -> str:
        """
        Record a new operation in the history
        
        Returns:
            operation_id: Unique identifier for this operation
        """
        
        operation_id = f"{operation_type.value}_{model_id}_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}"
        
        # Create operation record
        record = OperationRecord(
            operation_id=operation_id,
            operation_type=operation_type,
            timestamp=datetime.now(),
            model_id=model_id,
            element_id=element_id,
            element_type=element_type,
            old_state=copy.deepcopy(old_state) if old_state else None,
            new_state=copy.deepcopy(new_state) if new_state else None,
            user_description=user_description,
            batch_id=self._current_batch,
            **kwargs
        )
        
        # Determine what properties changed
        if old_state and new_state:
            record.properties_changed = [
                key for key in new_state.keys() 
                if key not in old_state or old_state[key] != new_state[key]
            ]
        
        # Add to storage
        self._operations.append(record)
        self._operation_index[operation_id] = record
        
        # Add to current batch if active
        if self._current_batch:
            self._batch_operations.append(operation_id)
        
        # Add to undo stack (clear redo stack since new operation)
        self._undo_stack.append(operation_id)
        self._redo_stack.clear()
        
        # Manage size limits
        self._cleanup_old_operations()
        
        # Update statistics
        self._stats['total_operations'] += 1
        
        logger.debug(f"Recorded operation: {operation_id} ({operation_type.value})")
        return operation_id
    
    def start_batch(self, batch_description: Optional[str] = None) -> str:
        """Start a batch operation (multiple operations as one undoable unit)"""
        if self._current_batch:
            logger.warning("Starting new batch while another is active")
            
        batch_id = f"batch_{datetime.now().strftime('%Y%m%d_%H%M%S_%f')}"
        self._current_batch = batch_id
        self._batch_operations = []
        
        logger.info(f"Started batch operation: {batch_id}")
        return batch_id
    
    def end_batch(self) -> Optional[str]:
        """End current batch operation"""
        if not self._current_batch:
            logger.warning("No active batch to end")
            return None
            
        batch_id = self._current_batch
        batch_ops = self._batch_operations.copy()
        
        # Record batch operation
        batch_record = OperationRecord(
            operation_id=f"batch_{batch_id}",
            operation_type=OperationType.BATCH,
            timestamp=datetime.now(),
            model_id="batch",
            user_description=f"Batch of {len(batch_ops)} operations",
            undo_data={'operation_ids': batch_ops}
        )
        
        self._operations.append(batch_record)
        self._operation_index[batch_record.operation_id] = batch_record
        
        # Clear batch state
        self._current_batch = None
        self._batch_operations = []
        
        logger.info(f"Ended batch operation: {batch_id} with {len(batch_ops)} operations")
        return batch_id
    
    def can_undo(self) -> bool:
        """Check if undo is possible"""
        return len(self._undo_stack) > 0
    
    def can_redo(self) -> bool:
        """Check if redo is possible"""
        return len(self._redo_stack) > 0
    
    def get_last_operation(self) -> Optional[OperationRecord]:
        """Get the most recent operation"""
        if not self._undo_stack:
            return None
        return self._operation_index.get(self._undo_stack[-1])
    
    def get_next_redo_operation(self) -> Optional[OperationRecord]:
        """Get the next operation that would be redone"""
        if not self._redo_stack:
            return None
        return self._operation_index.get(self._redo_stack[-1])
    
    def prepare_undo(self, operation_id: Optional[str] = None) -> Optional[OperationRecord]:
        """
        Prepare undo operation (returns operation to undo, doesn't execute)
        
        Args:
            operation_id: Specific operation to undo, or None for last operation
        """
        if operation_id:
            if operation_id not in self._operation_index:
                logger.error(f"Operation {operation_id} not found")
                return None
            record = self._operation_index[operation_id]
        else:
            if not self.can_undo():
                logger.warning("No operations available to undo")
                return None
            record = self.get_last_operation()
        
        if not record.is_undoable:
            logger.error(f"Operation {record.operation_id} is not undoable")
            return None
        
        return record
    
    def mark_operation_undone(self, operation_id: str) -> bool:
        """Mark an operation as undone (called after successful undo execution)"""
        if operation_id not in self._operation_index:
            return False
            
        record = self._operation_index[operation_id]
        record.operation_status = OperationStatus.UNDONE
        
        # Move from undo to redo stack
        if operation_id in self._undo_stack:
            self._undo_stack.remove(operation_id)
        self._redo_stack.append(operation_id)
        
        self._stats['undone_operations'] += 1
        logger.info(f"Marked operation as undone: {operation_id}")
        return True
    
    def mark_operation_redone(self, operation_id: str) -> bool:
        """Mark an operation as redone (called after successful redo execution)"""
        if operation_id not in self._operation_index:
            return False
            
        record = self._operation_index[operation_id]
        record.operation_status = OperationStatus.REDONE
        record.redo_count += 1
        
        # Move from redo to undo stack
        if operation_id in self._redo_stack:
            self._redo_stack.remove(operation_id)
        self._undo_stack.append(operation_id)
        
        self._stats['redone_operations'] += 1
        logger.info(f"Marked operation as redone: {operation_id}")
        return True
    
    def create_checkpoint(self, checkpoint_id: str, model_state: Dict[str, Any]) -> None:
        """Create a checkpoint for major state restoration"""
        self._checkpoints[checkpoint_id] = {
            'timestamp': datetime.now().isoformat(),
            'model_state': copy.deepcopy(model_state),
            'operation_count': len(self._operations)
        }
        
        logger.info(f"Created checkpoint: {checkpoint_id}")
    
    def get_checkpoint(self, checkpoint_id: str) -> Optional[Dict[str, Any]]:
        """Retrieve a checkpoint"""
        return self._checkpoints.get(checkpoint_id)
    
    def list_checkpoints(self) -> List[str]:
        """List available checkpoints"""
        return list(self._checkpoints.keys())
    
    def get_operation_history(self, 
                             limit: Optional[int] = None,
                             operation_type: Optional[OperationType] = None,
                             model_id: Optional[str] = None) -> List[OperationRecord]:
        """
        Get operation history with optional filtering
        """
        operations = self._operations
        
        # Filter by type
        if operation_type:
            operations = [op for op in operations if op.operation_type == operation_type]
        
        # Filter by model
        if model_id:
            operations = [op for op in operations if op.model_id == model_id]
        
        # Sort by timestamp (newest first)
        operations = sorted(operations, key=lambda x: x.timestamp, reverse=True)
        
        # Apply limit
        if limit:
            operations = operations[:limit]
            
        return operations
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get operation history statistics"""
        stats = self._stats.copy()
        stats.update({
            'current_operations': len(self._operations),
            'undo_stack_size': len(self._undo_stack),
            'redo_stack_size': len(self._redo_stack),
            'checkpoints': len(self._checkpoints),
            'can_undo': self.can_undo(),
            'can_redo': self.can_redo()
        })
        return stats
    
    def clear_history(self, confirm: bool = False) -> None:
        """Clear all operation history (dangerous operation)"""
        if not confirm:
            raise ValueError("Must set confirm=True to clear history")
            
        self._operations.clear()
        self._operation_index.clear()
        self._undo_stack.clear()
        self._redo_stack.clear()
        self._checkpoints.clear()
        
        self._stats = {
            'total_operations': 0,
            'undone_operations': 0,
            'redone_operations': 0,
            'failed_operations': 0
        }
        
        logger.warning("Operation history cleared")
    
    def _cleanup_old_operations(self) -> None:
        """Remove old operations when exceeding max limit"""
        if len(self._operations) <= self.max_operations:
            return
            
        # Calculate how many to remove
        excess = len(self._operations) - self.max_operations
        operations_to_remove = self._operations[:excess]
        
        # Remove from main storage
        for op in operations_to_remove:
            if op.operation_id in self._operation_index:
                del self._operation_index[op.operation_id]
            
            # Remove from undo/redo stacks
            if op.operation_id in self._undo_stack:
                self._undo_stack.remove(op.operation_id)
            if op.operation_id in self._redo_stack:
                self._redo_stack.remove(op.operation_id)
        
        # Update operations list
        self._operations = self._operations[excess:]
        
        logger.info(f"Cleaned up {excess} old operations")
    
    def save_to_file(self, file_path: Union[str, Path]) -> None:
        """Save operation history to file"""
        if not self.enable_persistence:
            logger.warning("Persistence is disabled")
            return
            
        data = {
            'operations': [op.to_dict() for op in self._operations],
            'checkpoints': self._checkpoints,
            'statistics': self.get_statistics(),
            'saved_at': datetime.now().isoformat()
        }
        
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
            
        logger.info(f"Operation history saved to {file_path}")
    
    def load_from_file(self, file_path: Union[str, Path]) -> None:
        """Load operation history from file"""
        if not self.enable_persistence:
            logger.warning("Persistence is disabled")
            return
            
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            # Restore operations
            self._operations = [OperationRecord.from_dict(op_data) for op_data in data['operations']]
            self._operation_index = {op.operation_id: op for op in self._operations}
            
            # Restore checkpoints
            self._checkpoints = data.get('checkpoints', {})
            
            # Rebuild undo/redo stacks based on operation status
            self._undo_stack = [
                op.operation_id for op in self._operations 
                if op.operation_status in [OperationStatus.COMPLETED, OperationStatus.REDONE]
            ]
            self._redo_stack = [
                op.operation_id for op in self._operations 
                if op.operation_status == OperationStatus.UNDONE
            ]
            
            logger.info(f"Operation history loaded from {file_path}")
            
        except Exception as e:
            logger.error(f"Failed to load operation history: {e}")
            raise

# Global instance (optional - can also be created per model)
_global_history: Optional[OperationHistory] = None

def get_global_history() -> OperationHistory:
    """Get or create global operation history instance"""
    global _global_history
    if _global_history is None:
        _global_history = OperationHistory()
    return _global_history

def set_global_history(history: OperationHistory) -> None:
    """Set global operation history instance"""
    global _global_history
    _global_history = history