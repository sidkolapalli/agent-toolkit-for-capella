"""
Transaction management for safe model operations
"""

from typing import Any, Optional, Dict, List, Callable
from dataclasses import dataclass, field
from datetime import datetime
from contextlib import asynccontextmanager
import logging
import copy
from uuid import uuid4

logger = logging.getLogger(__name__)


@dataclass
class Snapshot:
    """Model snapshot for rollback"""

    id: str
    timestamp: datetime
    data: Any
    metadata: Dict = field(default_factory=dict)


@dataclass
class Transaction:
    """Represents a model transaction"""

    id: str
    start_time: datetime
    operations: List[Dict] = field(default_factory=list)
    snapshot: Optional[Snapshot] = None
    status: str = "pending"  # pending, committed, rolled_back
    end_time: Optional[datetime] = None

    def add_operation(self, operation: Dict):
        """Add an operation to the transaction"""
        self.operations.append({**operation, "timestamp": datetime.now().isoformat()})

    def commit(self):
        """Commit the transaction"""
        self.status = "committed"
        self.end_time = datetime.now()

    def rollback(self):
        """Rollback the transaction"""
        self.status = "rolled_back"
        self.end_time = datetime.now()


class TransactionManager:
    """Manages transactions for safe model operations"""

    def __init__(self):
        self.transactions: Dict[str, Transaction] = {}
        self.active_transaction: Optional[Transaction] = None
        self.snapshots: Dict[str, Snapshot] = {}
        self.rollback_handlers: List[Callable] = []

    async def begin_transaction(self, model_data: Any = None) -> Transaction:
        """Begin a new transaction"""
        if self.active_transaction:
            raise RuntimeError("Transaction already in progress")

        # Create transaction
        transaction_id = str(uuid4())
        transaction = Transaction(id=transaction_id, start_time=datetime.now())

        # Create snapshot if model data provided
        if model_data is not None:
            snapshot = await self.create_snapshot(model_data)
            transaction.snapshot = snapshot
            self.snapshots[snapshot.id] = snapshot

        # Set as active
        self.active_transaction = transaction
        self.transactions[transaction_id] = transaction

        logger.info(f"Transaction {transaction_id} started")
        return transaction

    async def commit_transaction(self) -> Transaction:
        """Commit the current transaction"""
        if not self.active_transaction:
            raise RuntimeError("No active transaction to commit")

        transaction = self.active_transaction

        try:
            # Execute any pre-commit hooks
            await self._execute_pre_commit_hooks(transaction)

            # Mark as committed
            transaction.commit()

            # Clean up snapshot if no longer needed
            if transaction.snapshot:
                # Keep snapshot for audit trail
                pass

            logger.info(f"Transaction {transaction.id} committed")

        finally:
            self.active_transaction = None

        return transaction

    async def rollback_transaction(self) -> Transaction:
        """Rollback the current transaction"""
        if not self.active_transaction:
            raise RuntimeError("No active transaction to rollback")

        transaction = self.active_transaction

        try:
            # Execute rollback handlers
            for handler in self.rollback_handlers:
                try:
                    await handler(transaction)
                except Exception as e:
                    logger.error(f"Rollback handler failed: {e}")

            # Mark as rolled back
            transaction.rollback()

            logger.info(f"Transaction {transaction.id} rolled back")

        finally:
            self.active_transaction = None

        return transaction

    async def create_snapshot(self, data: Any) -> Snapshot:
        """Create a snapshot of the model data"""
        snapshot_id = str(uuid4())

        # Deep copy the data
        snapshot_data = copy.deepcopy(data)

        snapshot = Snapshot(
            id=snapshot_id,
            timestamp=datetime.now(),
            data=snapshot_data,
            metadata={"size": len(str(snapshot_data)) if snapshot_data else 0},
        )

        logger.debug(f"Created snapshot {snapshot_id}")
        return snapshot

    async def restore_snapshot(self, snapshot_id: str) -> Any:
        """Restore from a snapshot"""
        if snapshot_id not in self.snapshots:
            raise ValueError(f"Snapshot {snapshot_id} not found")

        snapshot = self.snapshots[snapshot_id]

        # Deep copy to avoid mutations
        restored_data = copy.deepcopy(snapshot.data)

        logger.info(f"Restored from snapshot {snapshot_id}")
        return restored_data

    def add_rollback_handler(self, handler: Callable):
        """Add a rollback handler"""
        self.rollback_handlers.append(handler)

    def get_transaction(self, transaction_id: str) -> Optional[Transaction]:
        """Get a transaction by ID"""
        return self.transactions.get(transaction_id)

    def get_active_transaction(self) -> Optional[Transaction]:
        """Get the active transaction"""
        return self.active_transaction

    @asynccontextmanager
    async def transaction(self, model_data: Any = None):
        """Context manager for transactions"""
        transaction = await self.begin_transaction(model_data)

        try:
            yield transaction
            await self.commit_transaction()
        except Exception as e:
            logger.error(f"Transaction failed: {e}")
            await self.rollback_transaction()
            raise

    async def _execute_pre_commit_hooks(self, transaction: Transaction):
        """Execute pre-commit hooks"""
        # Validate operations
        for operation in transaction.operations:
            if not self._validate_operation(operation):
                raise ValueError(f"Invalid operation: {operation}")

    def _validate_operation(self, operation: Dict) -> bool:
        """Validate an operation"""
        # Basic validation
        if "type" not in operation:
            return False

        # Check operation type
        valid_types = ["create", "modify", "delete", "query"]
        if operation["type"] not in valid_types:
            return False

        return True

    def clear_transactions(self):
        """Clear all transactions (for testing)"""
        self.transactions.clear()
        self.active_transaction = None
        self.snapshots.clear()
        logger.debug("Cleared all transactions")
