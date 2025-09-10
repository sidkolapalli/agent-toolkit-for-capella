#!/usr/bin/env python3
"""
Test that actual deletion is now the default behavior
"""

import sys
from pathlib import Path

# Add src to path for imports
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from capella_mcp.model_manager import ModelManager


def test_default_behavior_uses_actual_deletion():
    """Test that ModelManager uses actual deletion by default"""
    
    # Create ModelManager with default parameters
    manager = ModelManager()
    
    # Should have actual deletion manager by default
    assert hasattr(manager, 'actual_deletion_manager')
    assert manager.actual_deletion_manager is not None
    
    # Should NOT have virtual deletion when using default
    assert manager.virtual_deletion is None


def test_explicit_actual_deletion_true():
    """Test that explicitly setting use_actual_deletion=True works"""
    
    manager = ModelManager(use_actual_deletion=True)
    
    assert manager.actual_deletion_manager is not None
    assert manager.virtual_deletion is None


def test_explicit_virtual_deletion_false():
    """Test that explicitly setting use_actual_deletion=False uses virtual deletion"""
    
    manager = ModelManager(use_actual_deletion=False)
    
    assert manager.virtual_deletion is not None
    assert manager.actual_deletion_manager is None


def test_reversibility_disabled_no_deletion_systems():
    """Test that disabling reversibility disables both deletion systems"""
    
    manager = ModelManager(enable_reversibility=False)
    
    assert manager.actual_deletion_manager is None
    assert manager.virtual_deletion is None
    assert manager.operation_history is None
    assert manager.undo_manager is None


if __name__ == "__main__":
    test_default_behavior_uses_actual_deletion()
    test_explicit_actual_deletion_true()
    test_explicit_virtual_deletion_false()
    test_reversibility_disabled_no_deletion_systems()
    print("✅ All default behavior tests passed!")