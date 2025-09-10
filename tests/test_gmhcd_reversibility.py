#!/usr/bin/env python3
"""
End-to-End Reversibility Test with GMHCD Capella Model
Tests the complete reversibility system with the real GMHCD.aird model
"""

import asyncio
import sys
from pathlib import Path
import logging
import os

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent / "src"))

from capella_mcp.model_manager import ModelManager
from capella_mcp.operation_history import OperationType, OperationStatus
from capella_mcp.virtual_deletion import DeletionState, DeletionCascadeMode

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class GMHCDReversibilityTest:
    """End-to-end test of reversibility system with GMHCD model"""
    
    def __init__(self):
        self.model_manager = ModelManager(cache_size=5, enable_reversibility=True)
        self.model_id = None
        self.test_results = []
        
        # Set model path from user request
        self.model_path = r"<local-path>"
        
        logger.info(">> GMHCD Reversibility Test initialized")
        logger.info(f">> Model path: {self.model_path}")
    
    async def run_full_test(self):
        """Run the complete end-to-end reversibility test"""
        
        logger.info(">> Starting GMHCD Reversibility End-to-End Test")
        
        try:
            # Phase 1: Load Model
            await self.test_load_model()
            
            # Phase 2: Basic Operations
            await self.test_create_operations()
            await self.test_modify_operations() 
            await self.test_virtual_deletion()
            
            # Phase 3: Undo/Redo Operations
            await self.test_undo_redo_cycle()
            
            # Phase 4: Advanced Features
            await self.test_checkpoints()
            await self.test_batch_operations()
            
            # Phase 5: System Health
            await self.test_system_status()
            
            # Report Results
            await self.report_results()
            
        except Exception as e:
            logger.error(f">> Test suite failed: {e}")
            self.test_results.append(("Test Suite", False, str(e)))
        
        finally:
            await self.cleanup()
    
    async def test_load_model(self):
        """Test 1: Load the GMHCD model"""
        logger.info("\n>> TEST 1: Loading GMHCD Model")
        
        try:
            # Check if model file exists
            if not os.path.exists(self.model_path):
                raise FileNotFoundError(f"Model file not found: {self.model_path}")
            
            # Load model
            self.model_id = await self.model_manager.load_model(self.model_path)
            
            if self.model_id:
                logger.info(f">> Model loaded successfully: {self.model_id}")
                
                # Get model info
                loaded_models = self.model_manager.list_loaded_models()
                if loaded_models:
                    model_info = loaded_models[0]
                    logger.info(f">> Model info: {model_info.get('name', 'Unknown')} from {model_info.get('path', 'Unknown path')}")
                else:
                    logger.info(">> Model loaded but no model info available")
                
                self.test_results.append(("Load Model", True, f"Model {self.model_id} loaded"))
            else:
                raise RuntimeError("Model loading returned None")
                
        except Exception as e:
            logger.error(f">> Model loading failed: {e}")
            self.test_results.append(("Load Model", False, str(e)))
            raise  # Can't continue without model
    
    async def test_create_operations(self):
        """Test 2: Create elements and track operations"""
        logger.info("\n📋 TEST 2: Create Operations with History Tracking")
        
        try:
            # Create a logical component
            component_data = await self.model_manager.create_element(
                model_id=self.model_id,
                element_type="LogicalComponent", 
                name="TestReversibilityComponent",
                description="Test component for reversibility validation"
            )
            
            if component_data:
                component_id = component_data.get('id')
                logger.info(f"✅ Created LogicalComponent: {component_id}")
                
                # Verify operation was recorded
                operations = self.model_manager.get_operation_history()
                if operations and operations[0]['operation_type'] == 'create':
                    logger.info(f"✅ Create operation recorded in history")
                    self.test_results.append(("Create with History", True, f"Component {component_id} created and tracked"))
                else:
                    raise RuntimeError("Create operation not recorded in history")
            else:
                raise RuntimeError("Create operation failed")
                
        except Exception as e:
            logger.error(f"❌ Create operations failed: {e}")
            self.test_results.append(("Create with History", False, str(e)))
    
    async def test_modify_operations(self):
        """Test 3: Modify elements and track changes"""
        logger.info("\n📋 TEST 3: Modify Operations with State Tracking")
        
        try:
            # Get existing elements to modify
            elements = await self.model_manager.query_elements(
                model_id=self.model_id,
                element_type="LogicalComponent", 
                limit=5
            )
            
            if elements:
                element_to_modify = elements[0]
                element_id = element_to_modify['id']
                original_name = element_to_modify.get('name', 'Unknown')
                
                logger.info(f"📝 Modifying component: {element_id} ('{original_name}')")
                
                # Modify the element
                await self.model_manager.modify_element(
                    model_id=self.model_id,
                    element_id=element_id,
                    updates={
                        'name': f"{original_name}_MODIFIED_BY_REVERSIBILITY_TEST",
                        'description': "Modified for reversibility testing"
                    }
                )
                
                # Verify modification was tracked
                operations = self.model_manager.get_operation_history()
                modify_ops = [op for op in operations if op['operation_type'] == 'modify']
                
                if modify_ops:
                    logger.info(f"✅ Modify operation tracked with state changes")
                    self.test_results.append(("Modify with History", True, f"Element {element_id} modified and tracked"))
                else:
                    raise RuntimeError("Modify operation not recorded")
            else:
                raise RuntimeError("No elements found to modify")
                
        except Exception as e:
            logger.error(f"❌ Modify operations failed: {e}")
            self.test_results.append(("Modify with History", False, str(e)))
    
    async def test_virtual_deletion(self):
        """Test 4: Virtual deletion system"""
        logger.info("\n📋 TEST 4: Virtual Deletion System")
        
        try:
            # Get elements for deletion test
            elements = await self.model_manager.query_elements(
                model_id=self.model_id,
                element_type="LogicalComponent",
                limit=3
            )
            
            if elements:
                element_to_delete = elements[-1]  # Take last one to avoid interfering with other tests
                element_id = element_to_delete['id'] 
                element_name = element_to_delete.get('name', 'Unknown')
                
                logger.info(f"🗑️ Virtually deleting component: {element_id} ('{element_name}')")
                
                # Virtual delete the element
                delete_result = await self.model_manager.delete_element(
                    model_id=self.model_id,
                    element_id=element_id,
                    reason="Reversibility test - virtual deletion"
                )
                
                if delete_result:
                    # Verify element is marked as deleted
                    if self.model_manager.virtual_deletion.is_deleted(element_id):
                        logger.info(f"✅ Element virtually deleted and marked in system")
                        
                        # Verify it's filtered from queries
                        filtered_elements = await self.model_manager.query_elements(
                            model_id=self.model_id,
                            element_type="LogicalComponent"
                        )
                        
                        if not any(elem['id'] == element_id for elem in filtered_elements):
                            logger.info(f"✅ Deleted element correctly filtered from queries")
                            self.test_results.append(("Virtual Deletion", True, f"Element {element_id} virtually deleted and filtered"))
                        else:
                            raise RuntimeError("Deleted element still appears in queries")
                    else:
                        raise RuntimeError("Element not marked as deleted")
                else:
                    raise RuntimeError("Delete operation failed")
            else:
                raise RuntimeError("No elements found for deletion test")
                
        except Exception as e:
            logger.error(f"❌ Virtual deletion failed: {e}")
            self.test_results.append(("Virtual Deletion", False, str(e)))
    
    async def test_undo_redo_cycle(self):
        """Test 5: Complete undo/redo cycle"""
        logger.info("\n📋 TEST 5: Undo/Redo Cycle")
        
        try:
            # Check we can undo
            status = self.model_manager.get_reversibility_status()
            if not status['undo_manager']['can_undo']:
                raise RuntimeError("No operations available to undo")
            
            logger.info("🔄 Testing undo of last operation")
            
            # Undo last operation
            undo_result = await self.model_manager.undo_last_operation()
            
            if undo_result['success']:
                logger.info(f"✅ Undo successful: {undo_result.get('message', 'No message')}")
                
                # Test redo
                logger.info("🔄 Testing redo of undone operation")
                
                redo_result = await self.model_manager.redo_last_operation()
                
                if redo_result['success']:
                    logger.info(f"✅ Redo successful: {redo_result.get('message', 'No message')}")
                    self.test_results.append(("Undo/Redo Cycle", True, "Complete undo/redo cycle successful"))
                else:
                    raise RuntimeError(f"Redo failed: {redo_result.get('message', 'Unknown error')}")
            else:
                raise RuntimeError(f"Undo failed: {undo_result.get('message', 'Unknown error')}")
                
        except Exception as e:
            logger.error(f"❌ Undo/Redo cycle failed: {e}")
            self.test_results.append(("Undo/Redo Cycle", False, str(e)))
    
    async def test_checkpoints(self):
        """Test 6: Checkpoint system"""
        logger.info("\n📋 TEST 6: Checkpoint System")
        
        try:
            # Create checkpoint
            checkpoint_id = "GMHCD_REVERSIBILITY_TEST_CHECKPOINT"
            
            self.model_manager.create_checkpoint(
                checkpoint_id,
                f"Test checkpoint for GMHCD reversibility validation"
            )
            
            logger.info(f"📌 Created checkpoint: {checkpoint_id}")
            
            # Verify checkpoint exists
            checkpoints = self.model_manager.operation_history.list_checkpoints()
            
            if checkpoint_id in checkpoints:
                logger.info(f"✅ Checkpoint successfully created and listed")
                
                # Get checkpoint data
                checkpoint_data = self.model_manager.operation_history.get_checkpoint(checkpoint_id)
                
                if checkpoint_data:
                    logger.info(f"✅ Checkpoint data accessible: {checkpoint_data['model_state'].get('checkpoint_description', 'No description')}")
                    self.test_results.append(("Checkpoint System", True, f"Checkpoint {checkpoint_id} created and verified"))
                else:
                    raise RuntimeError("Checkpoint data not accessible")
            else:
                raise RuntimeError("Checkpoint not found in list")
                
        except Exception as e:
            logger.error(f"❌ Checkpoint system failed: {e}")
            self.test_results.append(("Checkpoint System", False, str(e)))
    
    async def test_batch_operations(self):
        """Test 7: Batch operations"""
        logger.info("\n📋 TEST 7: Batch Operations")
        
        try:
            # Start batch
            batch_id = self.model_manager.operation_history.start_batch("GMHCD Reversibility Test Batch")
            logger.info(f"📦 Started batch operation: {batch_id}")
            
            # Perform multiple operations in batch
            for i in range(3):
                try:
                    await self.model_manager.create_element(
                        model_id=self.model_id,
                        element_type="LogicalComponent",
                        name=f"BatchTestComponent_{i}",
                        description=f"Component {i} created in batch for reversibility testing"
                    )
                    logger.info(f"✅ Created batch component {i}")
                except Exception as e:
                    logger.warning(f"⚠️ Batch component {i} creation failed: {e}")
            
            # End batch
            final_batch_id = self.model_manager.operation_history.end_batch()
            
            if final_batch_id:
                logger.info(f"✅ Batch operations completed: {final_batch_id}")
                
                # Verify batch is in history
                operations = self.model_manager.get_operation_history()
                batch_ops = [op for op in operations if op['operation_type'] == 'batch']
                
                if batch_ops:
                    logger.info(f"✅ Batch operation recorded in history")
                    self.test_results.append(("Batch Operations", True, f"Batch {final_batch_id} completed successfully"))
                else:
                    raise RuntimeError("Batch operation not found in history")
            else:
                raise RuntimeError("Batch operation did not complete properly")
                
        except Exception as e:
            logger.error(f"❌ Batch operations failed: {e}")
            self.test_results.append(("Batch Operations", False, str(e)))
    
    async def test_system_status(self):
        """Test 8: System status and health"""
        logger.info("\n📋 TEST 8: System Status and Health")
        
        try:
            # Get comprehensive status
            general_status = self.model_manager.get_status()
            reversibility_status = self.model_manager.get_reversibility_status()
            
            logger.info("🩺 System Status Check:")
            logger.info(f"   - Models loaded: {general_status.get('models_loaded', 0)}")
            logger.info(f"   - Reversibility enabled: {reversibility_status.get('enabled', False)}")
            logger.info(f"   - Operations tracked: {general_status.get('operations_tracked', 0)}")
            logger.info(f"   - Can undo: {general_status.get('can_undo', False)}")
            logger.info(f"   - Can redo: {general_status.get('can_redo', False)}")
            logger.info(f"   - Deleted elements: {general_status.get('deleted_elements', 0)}")
            
            # Get operation history statistics
            ops_history = self.model_manager.get_operation_history()
            logger.info(f"   - Total operations in history: {len(ops_history)}")
            
            # Get deleted elements
            deleted_elements = self.model_manager.list_deleted_elements()
            logger.info(f"   - Currently deleted elements: {len(deleted_elements)}")
            
            if reversibility_status.get('enabled', False):
                logger.info("✅ Reversibility system fully operational")
                self.test_results.append(("System Health", True, "All systems operational"))
            else:
                raise RuntimeError("Reversibility system not enabled")
                
        except Exception as e:
            logger.error(f"❌ System status check failed: {e}")
            self.test_results.append(("System Health", False, str(e)))
    
    async def report_results(self):
        """Generate final test report"""
        logger.info("\n" + "="*80)
        logger.info("📊 GMHCD REVERSIBILITY TEST RESULTS")
        logger.info("="*80)
        
        total_tests = len(self.test_results)
        passed_tests = sum(1 for _, success, _ in self.test_results if success)
        failed_tests = total_tests - passed_tests
        
        logger.info(f"Total Tests: {total_tests}")
        logger.info(f"✅ Passed: {passed_tests}")
        logger.info(f"❌ Failed: {failed_tests}")
        logger.info(f"Success Rate: {(passed_tests/total_tests)*100:.1f}%" if total_tests > 0 else "No tests run")
        
        logger.info("\nDetailed Results:")
        for test_name, success, message in self.test_results:
            status = "✅ PASS" if success else "❌ FAIL" 
            logger.info(f"  {status} {test_name}: {message}")
        
        if failed_tests == 0:
            logger.info("\n🎉 ALL TESTS PASSED! Reversibility system fully validated with GMHCD model!")
        else:
            logger.info(f"\n⚠️ {failed_tests} test(s) failed. Review errors above.")
        
        logger.info("="*80)
    
    async def cleanup(self):
        """Clean up after tests"""
        try:
            if self.model_id and self.model_manager:
                # Don't close model - user might want to continue using it
                logger.info("🧹 Test cleanup completed (model left loaded for user)")
        except Exception as e:
            logger.warning(f"⚠️ Cleanup warning: {e}")


async def main():
    """Run the GMHCD reversibility test"""
    test_runner = GMHCDReversibilityTest()
    await test_runner.run_full_test()

if __name__ == "__main__":
    # Set environment variable for model path if needed
    os.environ['CAPELLA_MODEL_PATH'] = r"<local-path>"
    
    print(">> Starting GMHCD Reversibility End-to-End Test")
    print(">> This test validates the complete reversibility system with real Capella model data")
    print(">> Testing: create, modify, delete, undo, redo, checkpoints, batches")
    print()
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n>> Test interrupted by user")
    except Exception as e:
        print(f"\n>> Test failed with exception: {e}")
        import traceback
        traceback.print_exc()
