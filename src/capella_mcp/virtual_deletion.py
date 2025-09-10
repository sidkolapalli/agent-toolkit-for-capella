"""
Virtual Deletion System
Since capellambse doesn't support native deletion, this system provides virtual deletion
by marking elements as deleted and filtering them from queries
"""

import asyncio
import json
import logging
from dataclasses import dataclass, asdict
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Union
from pathlib import Path
import copy

from .operation_history import OperationHistory, OperationType, OperationRecord

logger = logging.getLogger(__name__)

class DeletionState(Enum):
    """States for virtual deletion"""
    ACTIVE = "active"
    DELETED = "deleted"
    HIDDEN = "hidden"
    ARCHIVED = "archived"

class DeletionCascadeMode(Enum):
    """How to handle cascading deletions"""
    NONE = "none"          # Don't cascade
    WARN = "warn"          # Warn but don't cascade
    CASCADE = "cascade"    # Automatically cascade
    PROMPT = "prompt"      # Ask user for cascade decision

@dataclass
class VirtualDeletionRecord:
    """Record of a virtually deleted element"""
    
    element_id: str
    model_id: str
    deletion_state: DeletionState
    deletion_timestamp: datetime
    
    # Element information (cached for restoration)
    element_type: Optional[str] = None
    element_name: Optional[str] = None
    parent_id: Optional[str] = None
    original_properties: Optional[Dict[str, Any]] = None
    
    # Deletion metadata
    reason: Optional[str] = None
    deleted_by_operation: Optional[str] = None
    cascade_children: List[str] = None  # Child elements also deleted
    
    # Restoration information
    can_restore: bool = True
    restoration_dependencies: Optional[List[str]] = None
    
    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for serialization"""
        data = asdict(self)
        data['deletion_timestamp'] = self.deletion_timestamp.isoformat()
        data['deletion_state'] = self.deletion_state.value
        return data
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'VirtualDeletionRecord':
        """Create from dictionary"""
        data = data.copy()
        data['deletion_timestamp'] = datetime.fromisoformat(data['deletion_timestamp'])
        data['deletion_state'] = DeletionState(data['deletion_state'])
        return cls(**data)

class VirtualDeletionManager:
    """
    Manages virtual deletion of model elements
    Provides full deletion/restoration capabilities when native deletion isn't available
    """
    
    def __init__(self, operation_history: Optional[OperationHistory] = None):
        self.operation_history = operation_history
        
        # Deletion registry
        self._deleted_elements: Dict[str, VirtualDeletionRecord] = {}  # element_id -> record
        
        # State tracking by model
        self._model_deletions: Dict[str, Set[str]] = {}  # model_id -> set of deleted element_ids
        
        # Cascade relationship cache
        self._parent_child_map: Dict[str, List[str]] = {}  # parent_id -> [child_ids]
        self._child_parent_map: Dict[str, str] = {}       # child_id -> parent_id
        
        # Deletion statistics
        self._stats = {
            'total_deletions': 0,
            'total_restorations': 0,
            'cascade_deletions': 0,
            'failed_deletions': 0
        }
        
        logger.info("VirtualDeletionManager initialized")
    
    def delete_element(self,
                      element_id: str,
                      model_id: str,
                      element_data: Optional[Dict[str, Any]] = None,
                      reason: Optional[str] = None,
                      cascade_mode: DeletionCascadeMode = DeletionCascadeMode.WARN,
                      operation_id: Optional[str] = None) -> bool:
        """
        Virtually delete an element
        
        Args:
            element_id: ID of element to delete
            model_id: ID of model containing element
            element_data: Current element data (for restoration)
            reason: Reason for deletion
            cascade_mode: How to handle child elements
            operation_id: Associated operation ID
        
        Returns:
            bool: True if deletion successful
        """
        
        if self.is_deleted(element_id):
            logger.warning(f"Element {element_id} is already deleted")
            return False
        
        try:
            # Create deletion record
            deletion_record = VirtualDeletionRecord(
                element_id=element_id,
                model_id=model_id,
                deletion_state=DeletionState.DELETED,
                deletion_timestamp=datetime.now(),
                element_type=element_data.get('type') if element_data else None,
                element_name=element_data.get('name') if element_data else None,
                parent_id=element_data.get('parent_id') if element_data else None,
                original_properties=copy.deepcopy(element_data) if element_data else None,
                reason=reason,
                deleted_by_operation=operation_id,
                cascade_children=[]
            )
            
            # Handle cascade deletion
            cascade_deleted = []
            if cascade_mode != DeletionCascadeMode.NONE:
                cascade_deleted = self._handle_cascade_deletion(
                    element_id, model_id, cascade_mode, operation_id
                )
                deletion_record.cascade_children = cascade_deleted
            
            # Register deletion
            self._deleted_elements[element_id] = deletion_record
            
            # Update model tracking
            if model_id not in self._model_deletions:
                self._model_deletions[model_id] = set()
            self._model_deletions[model_id].add(element_id)
            
            # Record in operation history
            if self.operation_history:
                self.operation_history.record_operation(
                    operation_type=OperationType.VIRTUAL_DELETE,
                    model_id=model_id,
                    element_id=element_id,
                    old_state=element_data,
                    new_state={'deletion_state': 'deleted'},
                    user_description=f"Virtual deletion: {reason or 'No reason provided'}",
                    undo_data={
                        'deletion_record': deletion_record.to_dict(),
                        'cascade_deleted': cascade_deleted
                    }
                )
            
            # Update statistics
            self._stats['total_deletions'] += 1
            if cascade_deleted:
                self._stats['cascade_deletions'] += len(cascade_deleted)
            
            logger.info(f"Virtually deleted element {element_id} with {len(cascade_deleted)} cascaded deletions")
            return True
            
        except Exception as e:
            logger.error(f"Failed to delete element {element_id}: {e}")
            self._stats['failed_deletions'] += 1
            return False
    
    def restore_element(self, 
                       element_id: str,
                       restore_cascade: bool = True) -> bool:
        """
        Restore a virtually deleted element
        
        Args:
            element_id: ID of element to restore
            restore_cascade: Whether to restore cascade-deleted children
        
        Returns:
            bool: True if restoration successful
        """
        
        if not self.is_deleted(element_id):
            logger.warning(f"Element {element_id} is not deleted")
            return False
        
        try:
            deletion_record = self._deleted_elements[element_id]
            
            if not deletion_record.can_restore:
                logger.error(f"Element {element_id} cannot be restored")
                return False
            
            # Check restoration dependencies
            if deletion_record.restoration_dependencies:
                for dep_id in deletion_record.restoration_dependencies:
                    if self.is_deleted(dep_id):
                        logger.warning(f"Cannot restore {element_id}: dependency {dep_id} is still deleted")
                        return False
            
            # Restore cascade children first
            restored_children = []
            if restore_cascade and deletion_record.cascade_children:
                for child_id in deletion_record.cascade_children:
                    if self.is_deleted(child_id):
                        if self.restore_element(child_id, restore_cascade=True):
                            restored_children.append(child_id)
            
            # Remove from deletion registry
            del self._deleted_elements[element_id]
            
            # Update model tracking
            if deletion_record.model_id in self._model_deletions:
                self._model_deletions[deletion_record.model_id].discard(element_id)
            
            # Record in operation history
            if self.operation_history:
                self.operation_history.record_operation(
                    operation_type=OperationType.RESTORE,
                    model_id=deletion_record.model_id,
                    element_id=element_id,
                    old_state={'deletion_state': 'deleted'},
                    new_state=deletion_record.original_properties,
                    user_description=f"Restored virtually deleted element",
                    undo_data={
                        'was_deleted': True,
                        'deletion_record': deletion_record.to_dict(),
                        'restored_children': restored_children
                    }
                )
            
            # Update statistics
            self._stats['total_restorations'] += 1
            
            logger.info(f"Restored element {element_id} with {len(restored_children)} children")
            return True
            
        except Exception as e:
            logger.error(f"Failed to restore element {element_id}: {e}")
            return False
    
    def is_deleted(self, element_id: str) -> bool:
        """Check if an element is virtually deleted"""
        return element_id in self._deleted_elements
    
    def get_deletion_state(self, element_id: str) -> Optional[DeletionState]:
        """Get deletion state of an element"""
        if element_id in self._deleted_elements:
            return self._deleted_elements[element_id].deletion_state
        return DeletionState.ACTIVE
    
    def get_deletion_record(self, element_id: str) -> Optional[VirtualDeletionRecord]:
        """Get deletion record for an element"""
        return self._deleted_elements.get(element_id)
    
    def filter_active_elements(self, element_ids: List[str]) -> List[str]:
        """Filter list to only include non-deleted elements"""
        return [eid for eid in element_ids if not self.is_deleted(eid)]
    
    def filter_deleted_elements(self, element_ids: List[str]) -> List[str]:
        """Filter list to only include deleted elements"""
        return [eid for eid in element_ids if self.is_deleted(eid)]
    
    def get_deleted_elements(self, 
                            model_id: Optional[str] = None,
                            deletion_state: Optional[DeletionState] = None) -> List[VirtualDeletionRecord]:
        """
        Get list of deleted elements with optional filtering
        """
        records = list(self._deleted_elements.values())
        
        if model_id:
            records = [r for r in records if r.model_id == model_id]
        
        if deletion_state:
            records = [r for r in records if r.deletion_state == deletion_state]
        
        # Sort by deletion timestamp (newest first)
        records.sort(key=lambda x: x.deletion_timestamp, reverse=True)
        
        return records
    
    def get_model_deleted_count(self, model_id: str) -> int:
        """Get count of deleted elements in a model"""
        return len(self._model_deletions.get(model_id, set()))
    
    def update_relationship_cache(self, 
                                 parent_id: str,
                                 child_ids: List[str]) -> None:
        """Update parent-child relationship cache for cascade operations"""
        self._parent_child_map[parent_id] = child_ids
        for child_id in child_ids:
            self._child_parent_map[child_id] = parent_id
    
    def get_children(self, parent_id: str) -> List[str]:
        """Get child elements of a parent"""
        return self._parent_child_map.get(parent_id, [])
    
    def get_parent(self, child_id: str) -> Optional[str]:
        """Get parent element of a child"""
        return self._child_parent_map.get(child_id)
    
    def _handle_cascade_deletion(self,
                                parent_id: str,
                                model_id: str,
                                cascade_mode: DeletionCascadeMode,
                                operation_id: Optional[str]) -> List[str]:
        """Handle cascade deletion of child elements"""
        
        if cascade_mode == DeletionCascadeMode.NONE:
            return []
        
        children = self.get_children(parent_id)
        if not children:
            return []
        
        cascade_deleted = []
        
        if cascade_mode == DeletionCascadeMode.WARN:
            logger.warning(f"Element {parent_id} has {len(children)} children that will become orphaned")
            return []
        
        elif cascade_mode == DeletionCascadeMode.CASCADE:
            for child_id in children:
                if not self.is_deleted(child_id):
                    # Recursively delete children
                    child_cascade = self._handle_cascade_deletion(
                        child_id, model_id, cascade_mode, operation_id
                    )
                    
                    # Create deletion record for child
                    child_record = VirtualDeletionRecord(
                        element_id=child_id,
                        model_id=model_id,
                        deletion_state=DeletionState.DELETED,
                        deletion_timestamp=datetime.now(),
                        parent_id=parent_id,
                        reason=f"Cascade deletion from parent {parent_id}",
                        deleted_by_operation=operation_id,
                        cascade_children=child_cascade
                    )
                    
                    self._deleted_elements[child_id] = child_record
                    cascade_deleted.append(child_id)
                    cascade_deleted.extend(child_cascade)
        
        elif cascade_mode == DeletionCascadeMode.PROMPT:
            # For now, treat as warn - could be extended to use callbacks
            logger.warning(f"Cascade deletion prompt needed for {len(children)} children of {parent_id}")
            return []
        
        return cascade_deleted
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get deletion statistics"""
        stats = self._stats.copy()
        stats.update({
            'currently_deleted': len(self._deleted_elements),
            'models_with_deletions': len(self._model_deletions),
            'relationship_mappings': len(self._parent_child_map)
        })
        return stats
    
    def cleanup_model_deletions(self, model_id: str) -> int:
        """Clean up all deletions for a model (when model is unloaded)"""
        if model_id not in self._model_deletions:
            return 0
        
        deleted_ids = self._model_deletions[model_id].copy()
        count = 0
        
        for element_id in deleted_ids:
            if element_id in self._deleted_elements:
                del self._deleted_elements[element_id]
                count += 1
        
        del self._model_deletions[model_id]
        
        logger.info(f"Cleaned up {count} deletions for model {model_id}")
        return count
    
    def save_to_file(self, file_path: Union[str, Path]) -> None:
        """Save virtual deletions to file"""
        data = {
            'deleted_elements': {eid: record.to_dict() for eid, record in self._deleted_elements.items()},
            'model_deletions': {mid: list(eids) for mid, eids in self._model_deletions.items()},
            'statistics': self.get_statistics(),
            'saved_at': datetime.now().isoformat()
        }
        
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
            
        logger.info(f"Virtual deletions saved to {file_path}")
    
    def load_from_file(self, file_path: Union[str, Path]) -> None:
        """Load virtual deletions from file"""
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            # Restore deleted elements
            self._deleted_elements = {
                eid: VirtualDeletionRecord.from_dict(record_data)
                for eid, record_data in data['deleted_elements'].items()
            }
            
            # Restore model tracking
            self._model_deletions = {
                mid: set(eids) for mid, eids in data['model_deletions'].items()
            }
            
            logger.info(f"Virtual deletions loaded from {file_path}")
            
        except Exception as e:
            logger.error(f"Failed to load virtual deletions: {e}")
            raise

# Utility functions for integration with model operations

def should_filter_element(element_id: str, deletion_manager: VirtualDeletionManager) -> bool:
    """Check if an element should be filtered out due to virtual deletion"""
    return deletion_manager.is_deleted(element_id)

def filter_query_results(results: List[Dict[str, Any]], 
                        deletion_manager: VirtualDeletionManager,
                        id_field: str = 'id') -> List[Dict[str, Any]]:
    """Filter query results to exclude virtually deleted elements"""
    return [
        result for result in results 
        if not deletion_manager.is_deleted(result.get(id_field, ''))
    ]

def add_deletion_metadata(results: List[Dict[str, Any]], 
                         deletion_manager: VirtualDeletionManager,
                         id_field: str = 'id') -> List[Dict[str, Any]]:
    """Add deletion state metadata to query results"""
    for result in results:
        element_id = result.get(id_field, '')
        if element_id:
            result['deletion_state'] = deletion_manager.get_deletion_state(element_id).value
            if deletion_manager.is_deleted(element_id):
                record = deletion_manager.get_deletion_record(element_id)
                result['deleted_at'] = record.deletion_timestamp.isoformat()
                result['deletion_reason'] = record.reason
    return results