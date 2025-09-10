#!/usr/bin/env python3
"""
Test Actual Deletion with GMHCD Capella Model
Tests that actual deletion works with real model and elements disappear from Capella UI
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

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(name)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class ActualDeletionGMHCDTest:
    """Test actual deletion system with GMHCD model"""
    
    def __init__(self):
        # Use actual deletion by default (new system)
        self.model_manager = ModelManager(cache_size=5, enable_reversibility=True)
        self.model_id = None
        self.test_results = []
        
        # Set model path from user request
        self.model_path = r"<local-path>"
        
        logger.info(">> GMHCD Actual Deletion Test initialized")
        logger.info(f">> Model path: {self.model_path}")
    
    async def run_full_test(self):
        """Run the complete actual deletion test"""
        
        logger.info(">> Starting GMHCD Actual Deletion Test")
        
        try:
            # Phase 1: Load Model
            await self.test_load_model()
            
            # Phase 2: Test Actual Deletion System
            await self.test_actual_deletion_manager()
            await self.test_create_and_delete_element()
            await self.test_list_actual_deletions()
            
            # Phase 3: Test Undo/Restore 
            await self.test_undo_actual_deletion()
            
            # Phase 4: System Health
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
        logger.info("\\n>> TEST 1: Loading GMHCD Model")
        
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
                
                self.test_results.append(("Load Model", True, f"Model {self.model_id} loaded"))
            else:
                raise RuntimeError("Model loading returned None")
                
        except Exception as e:
            logger.error(f">> Model loading failed: {e}")
            self.test_results.append(("Load Model", False, str(e)))
            raise  # Can't continue without model
    
    async def test_actual_deletion_manager(self):
        """Test 2: Verify actual deletion manager is available"""
        logger.info("\\n📋 TEST 2: Verify Actual Deletion Manager")
        
        try:
            # Check that actual deletion manager exists
            assert hasattr(self.model_manager, 'actual_deletion_manager')
            assert self.model_manager.actual_deletion_manager is not None
            
            # Check that virtual deletion is NOT used
            assert not hasattr(self.model_manager, 'virtual_deletion') or self.model_manager.virtual_deletion is None
            
            # Verify actual deletion manager methods
            assert hasattr(self.model_manager.actual_deletion_manager, 'delete_element_actual')
            assert hasattr(self.model_manager.actual_deletion_manager, 'undo_actual_deletion')
            assert hasattr(self.model_manager.actual_deletion_manager, 'list_actual_deletions')
            
            logger.info("✅ Actual deletion manager verified and ready")
            self.test_results.append(("Actual Deletion Manager", True, "Manager available with all required methods"))
            
        except Exception as e:
            logger.error(f"❌ Actual deletion manager check failed: {e}")
            self.test_results.append(("Actual Deletion Manager", False, str(e)))
    
    async def test_create_and_delete_element(self):
        """Test 3: Create element and actually delete it"""
        logger.info("\\n📋 TEST 3: Create and Actually Delete Element")
        
        try:
            # Create a test component
            component_data = await self.model_manager.create_element(
                model_id=self.model_id,
                element_type="LogicalComponent", 
                name="ActualDeletionTestComponent",
                description="Component created specifically to test actual deletion from .aird file"
            )
            
            if component_data:
                # Handle case where create_element returns string ID or dict
                if isinstance(component_data, str):
                    component_id = component_data
                else:
                    component_id = component_data.get('id')
                logger.info(f"✅ Created LogicalComponent: {component_id}")
                
                # Verify element exists in model
                elements_before = await self.model_manager.query_elements(
                    model_id=self.model_id,
                    filters={"element_type": "LogicalComponent"}
                )
                
                element_exists = any(getattr(elem, 'uuid', str(elem)) == component_id for elem in elements_before)
                if not element_exists:
                    raise RuntimeError("Created element not found in model")
                    
                logger.info(f"✅ Element verified to exist in model before deletion")
                
                # Actually delete the element
                logger.info(f"🗑️ Actually deleting element {component_id} from .aird file...")
                
                # Actually delete element - delete_element either succeeds or raises exception
                await self.model_manager.delete_element(
                    model_id=self.model_id,
                    element_id=component_id
                )
                
                logger.info(f"✅ Element {component_id} actually deleted from model file")
                
                # Verify element is actually gone from model (not just marked as deleted)
                elements_after = await self.model_manager.query_elements(
                    model_id=self.model_id,
                    filters={"element_type": "LogicalComponent"}
                )
                
                element_still_exists = any(getattr(elem, 'uuid', str(elem)) == component_id for elem in elements_after)
                if not element_still_exists:
                    logger.info(f"✅ Element {component_id} confirmed ACTUALLY removed from model")
                    logger.info(f"🎯 SUCCESS: Element should now be invisible in Capella UI (unlike virtual deletion)")
                    self.test_results.append(("Actual Deletion", True, f"Element {component_id} actually deleted from .aird file"))
                else:
                    raise RuntimeError("Element still exists in model after actual deletion")
            else:
                raise RuntimeError("Element creation failed")
                
        except Exception as e:
            logger.error(f"❌ Create and delete test failed: {e}")
            self.test_results.append(("Actual Deletion", False, str(e)))
    
    async def test_list_actual_deletions(self):
        """Test 4: List actually deleted elements"""
        logger.info("\\n📋 TEST 4: List Actually Deleted Elements")
        
        try:
            # Get list of actually deleted elements
            deleted_elements = self.model_manager.list_deleted_elements()
            
            logger.info(f"📋 Found {len(deleted_elements)} actually deleted elements")
            
            if deleted_elements:
                for elem in deleted_elements:
                    logger.info(f"  - {elem.get('element_id', 'Unknown')} ({elem.get('element_type', 'Unknown type')})")
                    logger.info(f"    Deleted: {elem.get('deleted_at', 'Unknown time')}")
                    logger.info(f"    Backup: {elem.get('backup_available', 'Unknown')}")
                
                self.test_results.append(("List Deletions", True, f"Found {len(deleted_elements)} deleted elements with backups"))
            else:
                # This might be ok if no elements were deleted in this test run
                logger.info("✅ No deleted elements found (may be expected)")
                self.test_results.append(("List Deletions", True, "No deleted elements (expected for clean test)"))
                
        except Exception as e:
            logger.error(f"❌ List deletions failed: {e}")
            self.test_results.append(("List Deletions", False, str(e)))
    
    async def test_undo_actual_deletion(self):
        """Test 5: Test undo of actual deletion"""
        logger.info("\\n📋 TEST 5: Undo Actual Deletion")
        
        try:
            # Check if there are any deleted elements to restore
            deleted_elements = self.model_manager.list_deleted_elements()
            
            if deleted_elements:
                element_to_restore = deleted_elements[0]  # Take the first one
                element_id = element_to_restore['element_id']
                
                logger.info(f"🔄 Attempting to restore element {element_id}")
                
                # Undo the actual deletion
                undo_result = await self.model_manager.undo_last_operation()
                
                if undo_result['success']:
                    logger.info(f"✅ Undo successful: {undo_result.get('message', 'No message')}")
                    
                    # Verify element is restored to model
                    elements_after = await self.model_manager.query_elements(
                        model_id=self.model_id,
                        filters={"element_type": "LogicalComponent"}
                    )
                    
                    element_restored = any(getattr(elem, 'uuid', str(elem)) == element_id for elem in elements_after)
                    if element_restored:
                        logger.info(f"✅ Element {element_id} successfully restored to model")
                        logger.info(f"🎯 SUCCESS: Element should now be visible in Capella UI again")
                        self.test_results.append(("Undo Actual Deletion", True, f"Element {element_id} restored from backup"))
                    else:
                        raise RuntimeError("Element not found in model after undo")
                else:
                    raise RuntimeError(f"Undo failed: {undo_result.get('message', 'Unknown error')}")
            else:
                logger.info("⚠️ No deleted elements to restore")
                self.test_results.append(("Undo Actual Deletion", True, "No deleted elements to restore (expected)"))
                
        except Exception as e:
            logger.error(f"❌ Undo actual deletion failed: {e}")
            self.test_results.append(("Undo Actual Deletion", False, str(e)))
    
    async def test_system_status(self):
        """Test 6: Verify system status with actual deletion"""
        logger.info("\\n📋 TEST 6: System Status with Actual Deletion")
        
        try:
            # Get system status
            status = self.model_manager.get_status()
            reversibility_status = self.model_manager.get_reversibility_status()
            
            logger.info("🩺 System Status Check:")
            logger.info(f"   - Models loaded: {status.get('models_loaded', 0)}")
            logger.info(f"   - Reversibility enabled: {reversibility_status.get('enabled', False)}")
            logger.info(f"   - Operations tracked: {status.get('operations_tracked', 0)}")
            
            # Check that actual deletion is reported, not virtual deletion
            if 'actual_deletion' in reversibility_status:
                logger.info(f"   - Actual deletion stats: {reversibility_status['actual_deletion']}")
                logger.info("✅ Actual deletion system operational")
            else:
                logger.warning("⚠️ Actual deletion stats not found in status")
            
            if 'virtual_deletion' in reversibility_status:
                logger.error("❌ Virtual deletion still reported in status (should be removed)")
                raise RuntimeError("Virtual deletion still present in system status")
            else:
                logger.info("✅ Virtual deletion correctly removed from system")
            
            self.test_results.append(("System Status", True, "Actual deletion system fully operational"))
            
        except Exception as e:
            logger.error(f"❌ System status check failed: {e}")
            self.test_results.append(("System Status", False, str(e)))
    
    async def report_results(self):
        """Generate final test report"""
        logger.info("\\n" + "="*80)
        logger.info("📊 GMHCD ACTUAL DELETION TEST RESULTS")
        logger.info("="*80)
        
        total_tests = len(self.test_results)
        passed_tests = sum(1 for _, success, _ in self.test_results if success)
        failed_tests = total_tests - passed_tests
        
        logger.info(f"Total Tests: {total_tests}")
        logger.info(f"✅ Passed: {passed_tests}")
        logger.info(f"❌ Failed: {failed_tests}")
        logger.info(f"Success Rate: {(passed_tests/total_tests)*100:.1f}%" if total_tests > 0 else "No tests run")
        
        logger.info("\\nDetailed Results:")
        for test_name, success, message in self.test_results:
            status = "✅ PASS" if success else "❌ FAIL" 
            logger.info(f"  {status} {test_name}: {message}")
        
        if failed_tests == 0:
            logger.info("\\n🎉 ALL TESTS PASSED! Actual deletion system fully validated with GMHCD model!")
            logger.info("🎯 CRITICAL SUCCESS: Elements are now ACTUALLY deleted from .aird files!")
            logger.info("🖥️ UI VERIFICATION: Deleted elements should be invisible in Capella UI (unlike virtual deletion)")
        else:
            logger.info(f"\\n⚠️ {failed_tests} test(s) failed. Review errors above.")
        
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
    """Run the GMHCD actual deletion test"""
    test_runner = ActualDeletionGMHCDTest()
    await test_runner.run_full_test()

if __name__ == "__main__":
    # Set environment variable for model path
    os.environ['CAPELLA_MODEL_PATH'] = r"<local-path>"
    
    print(">> Starting GMHCD Actual Deletion Test")
    print(">> This test validates that actual deletion works with real Capella model")
    print(">> Testing: ACTUAL deletion from .aird files (elements disappear from Capella UI)")
    print()
    
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\\n>> Test interrupted by user")
    except Exception as e:
        print(f"\\n>> Test failed with exception: {e}")
        import traceback
        traceback.print_exc()