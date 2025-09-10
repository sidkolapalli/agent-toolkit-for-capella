"""
Actual Deletion Manager
Handles actual deletion from Capella model files by manipulating capellambse parent/child relationships
"""

import logging
import json
import shutil
from datetime import datetime
from typing import Any, Dict, List, Optional, Union
from pathlib import Path

from .operation_history import OperationType, OperationStatus

logger = logging.getLogger(__name__)

class ActualDeletionManager:
    """
    Manages actual deletion of elements from Capella model files
    Works with capellambse's parent/child relationship system
    """
    
    def __init__(self, model_manager):
        self.model_manager = model_manager
        self.deletion_manifest = {}  # Track deletions for undo: element_id -> manifest data
        self.backup_directory = Path("capella_backups")
        self.backup_directory.mkdir(exist_ok=True)
        
        # Statistics
        self._stats = {
            'actual_deletions': 0,
            'failed_deletions': 0,
            'restored_elements': 0,
            'failed_restorations': 0
        }
        
        logger.info("ActualDeletionManager initialized")
    
    def create_backup(self, model_id: str, reason: str = "deletion") -> str:
        """Create a backup of the model before deletion"""
        try:
            wrapper = self.model_manager.get_model(model_id)
            if not wrapper:
                raise ValueError(f"Model {model_id} not found")
            
            # Get model path from cache metadata
            model_path = None
            for mid, metadata in self.model_manager.cache.metadata.items():
                if mid == model_id:
                    model_path = metadata.path
                    break
            
            if not model_path:
                raise ValueError(f"Could not find path for model {model_id}")
            
            # Create timestamped backup
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            backup_filename = f"{model_id}_{reason}_{timestamp}.aird"
            backup_path = self.backup_directory / backup_filename
            
            # Copy the model file
            shutil.copy2(model_path, backup_path)
            
            logger.info(f"Created backup: {backup_path}")
            return str(backup_path)
            
        except Exception as e:
            logger.error(f"Failed to create backup for model {model_id}: {e}")
            raise
    
    def find_element_by_uuid(self, model, element_id: str):
        """Find element in model by UUID"""
        try:
            # Use capellambse search to find element
            for element in model.search():
                if hasattr(element, 'uuid') and str(element.uuid) == element_id:
                    return element
            return None
        except Exception as e:
            logger.warning(f"Error finding element {element_id}: {e}")
            return None
    
    def find_parent_collection(self, element):
        """Find which collection in the parent contains this element"""
        if not hasattr(element, 'parent') or element.parent is None:
            return None, None
        
        parent = element.parent
        
        # Comprehensive list of capellambse collection attributes
        collection_attrs = [
            # Common owned collections
            'owned_functions',
            'owned_components', 
            'owned_interfaces',
            'owned_actors',
            'owned_entities',
            'owned_activities',
            'owned_scenarios',
            'owned_capabilities',
            'owned_missions',
            'owned_operational_activities',
            'owned_operational_processes',
            'owned_system_functions',
            'owned_logical_functions',
            'owned_physical_functions',
            'owned_logical_components',
            'owned_physical_components',
            'owned_system_components',
            # Sub-element collections
            'sub_components',
            'children',
            'elements',
            # Generic collections that might exist
            'components',
            'functions',
            'interfaces',
            'actors',
            'scenarios'
        ]
        
        # First, try known collection attributes
        for attr_name in collection_attrs:
            if hasattr(parent, attr_name):
                collection = getattr(parent, attr_name)
                try:
                    # Check if element is in this collection
                    if hasattr(collection, '__contains__') and element in collection:
                        return parent, attr_name
                    elif hasattr(collection, '__iter__'):
                        # Fallback: iterate through collection
                        for item in collection:
                            if item is element:
                                return parent, attr_name
                except Exception as e:
                    logger.debug(f"Error checking collection {attr_name}: {e}")
                    continue
        
        # If not found, examine all attributes of parent that could be collections
        logger.debug(f"Element not found in known collections. Examining all parent attributes...")
        for attr_name in dir(parent):
            if attr_name.startswith('_') or attr_name in collection_attrs:
                continue  # Skip private attributes and already checked attributes
                
            try:
                attr = getattr(parent, attr_name)
                # Check if this attribute could be a collection
                if hasattr(attr, '__iter__') and not isinstance(attr, str):
                    try:
                        # Check if element is in this collection
                        for item in attr:
                            if item is element:
                                logger.info(f"Found element in collection '{attr_name}'")
                                return parent, attr_name
                    except Exception as e:
                        logger.debug(f"Error iterating {attr_name}: {e}")
                        continue
            except Exception as e:
                logger.debug(f"Error accessing attribute {attr_name}: {e}")
                continue
        
        # Last resort: log available attributes for debugging
        logger.warning(f"Could not find element {getattr(element, 'uuid', 'unknown')} in parent {getattr(parent, 'uuid', 'unknown')}")
        logger.debug(f"Parent type: {type(parent).__name__}")
        
        # List collections that exist in parent for debugging
        existing_collections = []
        for attr_name in collection_attrs:
            if hasattr(parent, attr_name):
                collection = getattr(parent, attr_name)
                try:
                    collection_size = len(collection) if hasattr(collection, '__len__') else 'unknown'
                    existing_collections.append(f"{attr_name}({collection_size})")
                except:
                    existing_collections.append(attr_name)
        
        logger.debug(f"Available collections in parent: {', '.join(existing_collections)}")
        
        return None, None
    
    def extract_element_data(self, element) -> Dict[str, Any]:
        """Extract element data for undo capability"""
        if not element:
            return {}
        
        try:
            data = {
                'uuid': str(getattr(element, 'uuid', '')),
                'name': getattr(element, 'name', ''),
                'type': type(element).__name__,
                'parent_uuid': str(getattr(element.parent, 'uuid', '')) if hasattr(element, 'parent') and element.parent else None
            }
            
            # Extract common properties
            common_props = ['description', 'summary', 'kind']
            for prop in common_props:
                if hasattr(element, prop):
                    value = getattr(element, prop)
                    if value is not None:
                        data[prop] = str(value)
            
            return data
            
        except Exception as e:
            logger.warning(f"Could not extract data for element: {e}")
            return {'uuid': str(getattr(element, 'uuid', 'unknown'))}
    
    def delete_element_actual(self, model_id: str, element_id: str, create_backup: bool = True) -> bool:
        """
        Actually delete element from the model file
        
        Args:
            model_id: ID of model containing element
            element_id: UUID of element to delete
            create_backup: Whether to create backup before deletion
            
        Returns:
            bool: True if deletion successful
        """
        try:
            # Create backup first
            backup_path = None
            if create_backup:
                backup_path = self.create_backup(model_id, f"before_delete_{element_id}")
            
            # Get model wrapper
            wrapper = self.model_manager.get_model(model_id)
            if not wrapper:
                raise ValueError(f"Model {model_id} not found")
            
            # Get the capellambse model object
            model = wrapper.model
            
            # Find the element to delete
            element = self.find_element_by_uuid(model, element_id)
            if not element:
                raise ValueError(f"Element {element_id} not found in model")
            
            # Extract element data for undo
            element_data = self.extract_element_data(element)
            
            # Find parent and collection
            parent, collection_name = self.find_parent_collection(element)
            if not parent or not collection_name:
                raise ValueError(f"Could not find parent collection for element {element_id}")
            
            # Store deletion manifest for undo
            self.deletion_manifest[element_id] = {
                'model_id': model_id,
                'element_data': element_data,
                'parent_uuid': str(parent.uuid) if hasattr(parent, 'uuid') else None,
                'collection_name': collection_name,
                'backup_path': backup_path,
                'deletion_timestamp': datetime.now().isoformat(),
                'reason': f"Actual deletion of {element_data.get('type', 'unknown')} '{element_data.get('name', 'unnamed')}'"
            }
            
            # Remove element from parent's collection
            collection = getattr(parent, collection_name)
            
            # Different collection types need different removal methods
            if hasattr(collection, 'remove'):
                collection.remove(element)
                logger.info(f"Removed element {element_id} from {collection_name} using remove()")
            elif hasattr(collection, '__delitem__'):
                # Find index and delete
                for i, item in enumerate(collection):
                    if item is element:
                        del collection[i]
                        logger.info(f"Removed element {element_id} from {collection_name} using del[]")
                        break
                else:
                    raise ValueError(f"Element {element_id} not found in collection {collection_name}")
            else:
                raise ValueError(f"Collection {collection_name} does not support removal")
            
            # Save the model to persist changes
            model.save()
            logger.info(f"Saved model after deleting element {element_id}")
            
            # Record in operation history
            if self.model_manager.operation_history:
                self.model_manager.operation_history.record_operation(
                    operation_type=OperationType.DELETE,  # Use existing DELETE type
                    model_id=model_id,
                    element_id=element_id,
                    old_state=element_data,
                    new_state={'deleted': True, 'actual_deletion': True},
                    user_description=f"Actually deleted {element_data.get('type', 'element')} '{element_data.get('name', element_id)}'",
                    operation_status=OperationStatus.COMPLETED,
                    undo_data=self.deletion_manifest[element_id]
                )
            
            # Update statistics
            self._stats['actual_deletions'] += 1
            
            logger.info(f"Successfully deleted element {element_id} from model file")
            return True
            
        except Exception as e:
            logger.error(f"Failed to actually delete element {element_id}: {e}")
            self._stats['failed_deletions'] += 1
            
            # Try to restore from backup if we created one
            if create_backup and backup_path and Path(backup_path).exists():
                try:
                    self.restore_from_backup(model_id, backup_path)
                    logger.info(f"Restored model from backup after failed deletion")
                except Exception as restore_error:
                    logger.error(f"Failed to restore from backup: {restore_error}")
            
            return False
    
    def undo_actual_deletion(self, element_id: str) -> bool:
        """
        Undo actual deletion by restoring from backup
        
        Args:
            element_id: UUID of element to restore
            
        Returns:
            bool: True if restoration successful
        """
        try:
            if element_id not in self.deletion_manifest:
                logger.error(f"No deletion manifest found for element {element_id}")
                return False
            
            manifest = self.deletion_manifest[element_id]
            model_id = manifest['model_id']
            backup_path = manifest.get('backup_path')
            
            if not backup_path or not Path(backup_path).exists():
                logger.error(f"No backup found for element {element_id}")
                return False
            
            # Restore from backup
            success = self.restore_from_backup(model_id, backup_path)
            
            if success:
                # Record undo in operation history
                if self.model_manager.operation_history:
                    self.model_manager.operation_history.record_operation(
                        operation_type=OperationType.RESTORE,
                        model_id=model_id,
                        element_id=element_id,
                        old_state={'deleted': True, 'actual_deletion': True},
                        new_state=manifest['element_data'],
                        user_description=f"Restored actually deleted element {element_id}",
                        operation_status=OperationStatus.COMPLETED
                    )
                
                # Remove from deletion manifest
                del self.deletion_manifest[element_id]
                
                # Update statistics
                self._stats['restored_elements'] += 1
                
                logger.info(f"Successfully restored element {element_id} from backup")
                return True
            else:
                self._stats['failed_restorations'] += 1
                return False
                
        except Exception as e:
            logger.error(f"Failed to undo deletion for element {element_id}: {e}")
            self._stats['failed_restorations'] += 1
            return False
    
    def restore_from_backup(self, model_id: str, backup_path: str) -> bool:
        """Restore model from backup file"""
        try:
            if not Path(backup_path).exists():
                raise FileNotFoundError(f"Backup file not found: {backup_path}")
            
            # Get original model path
            model_path = None
            for mid, metadata in self.model_manager.cache.metadata.items():
                if mid == model_id:
                    model_path = metadata.path
                    break
            
            if not model_path:
                raise ValueError(f"Could not find original path for model {model_id}")
            
            # Copy backup over original
            shutil.copy2(backup_path, model_path)
            
            # Reload the model in ModelManager
            wrapper = self.model_manager.get_model(model_id)
            if wrapper:
                # Force reload by recreating the model object
                from capellambse import MelodyModel
                wrapper.model = MelodyModel(model_path)
                wrapper.modified = True  # Mark as modified to ensure proper tracking
                
            logger.info(f"Successfully restored model {model_id} from backup {backup_path}")
            return True
            
        except Exception as e:
            logger.error(f"Failed to restore model from backup {backup_path}: {e}")
            return False
    
    def list_actual_deletions(self, model_id: Optional[str] = None) -> List[Dict[str, Any]]:
        """List elements that were actually deleted"""
        deletions = []
        for element_id, manifest in self.deletion_manifest.items():
            if model_id is None or manifest['model_id'] == model_id:
                deletions.append({
                    'element_id': element_id,
                    'model_id': manifest['model_id'],
                    'element_data': manifest['element_data'],
                    'deletion_timestamp': manifest['deletion_timestamp'],
                    'reason': manifest['reason'],
                    'has_backup': Path(manifest.get('backup_path', '')).exists() if manifest.get('backup_path') else False
                })
        
        # Sort by deletion timestamp (newest first)
        deletions.sort(key=lambda x: x['deletion_timestamp'], reverse=True)
        return deletions
    
    def cleanup_old_backups(self, days_to_keep: int = 30) -> int:
        """Clean up old backup files"""
        try:
            if not self.backup_directory.exists():
                return 0
            
            cutoff_time = datetime.now().timestamp() - (days_to_keep * 24 * 60 * 60)
            cleaned_count = 0
            
            for backup_file in self.backup_directory.glob("*.aird"):
                if backup_file.stat().st_mtime < cutoff_time:
                    backup_file.unlink()
                    cleaned_count += 1
                    logger.debug(f"Cleaned up old backup: {backup_file}")
            
            logger.info(f"Cleaned up {cleaned_count} old backup files")
            return cleaned_count
            
        except Exception as e:
            logger.error(f"Failed to cleanup old backups: {e}")
            return 0
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get deletion statistics"""
        stats = self._stats.copy()
        stats.update({
            'pending_deletions': len(self.deletion_manifest),
            'backup_directory': str(self.backup_directory),
            'backup_count': len(list(self.backup_directory.glob("*.aird"))) if self.backup_directory.exists() else 0
        })
        return stats