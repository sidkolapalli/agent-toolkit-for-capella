"""
Test suite for Transaction Manager - ensuring data safety
Following TDD principles
"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock
from datetime import datetime

from capella_mcp.transaction_manager import TransactionManager, Transaction, Snapshot


class TestTransactionManager:
    """Test transaction management for safe model operations"""

    @pytest.fixture
    async def transaction_manager(self):
        """Create transaction manager instance"""
        manager = TransactionManager(timeout=5)
        return manager

    @pytest.fixture
    async def mock_model(self):
        """Create mock model for testing"""
        model = MagicMock()
        model.id = "test-model"
        model.create_snapshot = AsyncMock(
            return_value={
                "id": "snapshot-123",
                "timestamp": datetime.now().isoformat(),
                "data": {"components": [], "functions": []},
            }
        )
        model.restore_snapshot = AsyncMock()
        model.save = AsyncMock()
        model.validate = AsyncMock(return_value={"valid": True})
        return model

    @pytest.mark.asyncio
    async def test_transaction_creation(self, transaction_manager):
        """Test creating a new transaction"""
        # Act
        transaction = await transaction_manager.begin_transaction(
            "session-1", "test-model"
        )

        # Assert
        assert transaction.id is not None
        assert transaction.session_id == "session-1"
        assert transaction.model_id == "test-model"
        assert transaction.status == "pending"
        assert transaction.snapshot_id is not None

    @pytest.mark.asyncio
    async def test_transaction_commit(self, transaction_manager, mock_model):
        """Test successful transaction commit"""
        # Arrange
        transaction = await transaction_manager.begin_transaction(
            "session-1", "test-model"
        )
        transaction_manager.models["test-model"] = mock_model

        # Act
        await transaction_manager.commit(transaction.id)

        # Assert
        assert transaction.status == "committed"
        mock_model.save.assert_called_once()
        assert transaction.id not in transaction_manager.active_transactions

    @pytest.mark.asyncio
    async def test_transaction_rollback(self, transaction_manager, mock_model):
        """Test transaction rollback on error"""
        # Arrange
        transaction = await transaction_manager.begin_transaction(
            "session-1", "test-model"
        )
        transaction_manager.models["test-model"] = mock_model
        transaction.snapshot_id = "snapshot-123"

        # Act
        await transaction_manager.rollback(transaction.id)

        # Assert
        assert transaction.status == "rolled_back"
        mock_model.restore_snapshot.assert_called_once_with("snapshot-123")
        assert transaction.id not in transaction_manager.active_transactions

    @pytest.mark.asyncio
    async def test_transaction_context_manager(self, transaction_manager, mock_model):
        """Test using transaction as context manager"""
        # Arrange
        transaction_manager.models["test-model"] = mock_model
        operation_executed = False

        # Act - Successful operation
        async with transaction_manager.transaction("session-1", "test-model") as txn:
            operation_executed = True
            assert txn.status == "pending"

        # Assert
        assert operation_executed
        assert txn.status == "committed"
        mock_model.save.assert_called_once()

    @pytest.mark.asyncio
    async def test_transaction_context_manager_with_error(
        self, transaction_manager, mock_model
    ):
        """Test transaction rollback on exception in context manager"""
        # Arrange
        transaction_manager.models["test-model"] = mock_model

        # Act & Assert
        with pytest.raises(ValueError, match="Test error"):
            async with transaction_manager.transaction(
                "session-1", "test-model"
            ) as txn:
                raise ValueError("Test error")

        # Transaction should be rolled back
        assert txn.status == "rolled_back"
        mock_model.restore_snapshot.assert_called_once()
        mock_model.save.assert_not_called()

    @pytest.mark.asyncio
    async def test_transaction_timeout(self, transaction_manager):
        """Test transaction timeout handling"""
        # Arrange
        transaction_manager.timeout = 0.1  # 100ms timeout

        # Act
        transaction = await transaction_manager.begin_transaction(
            "session-1", "test-model"
        )

        # Wait for timeout
        await asyncio.sleep(0.2)

        # Try to commit after timeout
        with pytest.raises(TimeoutError, match="Transaction timed out"):
            await transaction_manager.commit(transaction.id)

    @pytest.mark.asyncio
    async def test_nested_transactions_prevention(self, transaction_manager):
        """Test that nested transactions are prevented"""
        # Arrange
        await transaction_manager.begin_transaction("session-1", "test-model")

        # Act & Assert - Should not allow nested transaction
        with pytest.raises(RuntimeError, match="Transaction already active"):
            await transaction_manager.begin_transaction("session-1", "test-model")

    @pytest.mark.asyncio
    async def test_concurrent_transactions_different_models(self, transaction_manager):
        """Test concurrent transactions on different models"""
        # Act
        transaction1 = await transaction_manager.begin_transaction(
            "session-1", "model-1"
        )
        transaction2 = await transaction_manager.begin_transaction(
            "session-2", "model-2"
        )

        # Assert
        assert transaction1.id != transaction2.id
        assert transaction1.model_id == "model-1"
        assert transaction2.model_id == "model-2"
        assert len(transaction_manager.active_transactions) == 2

    @pytest.mark.asyncio
    async def test_transaction_isolation(self, transaction_manager):
        """Test that transactions are isolated from each other"""
        # Arrange
        model1 = MagicMock()
        model2 = MagicMock()
        transaction_manager.models["model-1"] = model1
        transaction_manager.models["model-2"] = model2

        # Act
        async with transaction_manager.transaction("session-1", "model-1"):
            # This should not affect model-2
            pass

        # Assert - Only model1 should be saved
        model1.save.assert_called_once()
        model2.save.assert_not_called()

    @pytest.mark.asyncio
    async def test_cleanup_session_transactions(self, transaction_manager):
        """Test cleaning up all transactions for a session"""
        # Arrange
        txn1 = await transaction_manager.begin_transaction("session-1", "model-1")
        txn2 = await transaction_manager.begin_transaction("session-1", "model-2")
        txn3 = await transaction_manager.begin_transaction("session-2", "model-3")

        # Act
        await transaction_manager.cleanup_session("session-1")

        # Assert
        assert txn1.id not in transaction_manager.active_transactions
        assert txn2.id not in transaction_manager.active_transactions
        assert txn3.id in transaction_manager.active_transactions  # Different session


class TestTransaction:
    """Test Transaction data structure"""

    def test_transaction_creation(self):
        """Test Transaction object creation"""
        # Act
        transaction = Transaction(session_id="session-123", model_id="model-456")

        # Assert
        assert transaction.id is not None
        assert transaction.session_id == "session-123"
        assert transaction.model_id == "model-456"
        assert transaction.status == "pending"
        assert transaction.snapshot_id is None
        assert transaction.started_at is not None

    def test_transaction_state_transitions(self):
        """Test valid state transitions"""
        # Arrange
        transaction = Transaction("session-1", "model-1")

        # Act & Assert
        assert transaction.status == "pending"

        transaction.commit()
        assert transaction.status == "committed"

        transaction.commit()
        assert transaction.status == "committed"


class TestSnapshot:
    """Test Snapshot functionality"""

    def test_snapshot_creation(self):
        """Test creating a model snapshot"""
        # Arrange
        model_data = {"components": ["comp1", "comp2"], "functions": ["func1"]}

        # Act
        snapshot = Snapshot(model_id="test-model", data=model_data)

        # Assert
        assert snapshot.id is not None
        assert snapshot.model_id == "test-model"
        assert snapshot.data == model_data
        assert snapshot.created_at is not None

    def test_snapshot_size_calculation(self):
        """Test calculating snapshot size"""
        # Arrange
        snapshot = Snapshot(model_id="test-model", data={"large_data": "x" * 1000})

        # Act
        size = snapshot.get_size()

        # Assert
        assert size > 1000  # Should be larger than the data itself
