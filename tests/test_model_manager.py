"""
Test suite for Model Manager with capellambse integration
Following TDD principles
"""

import asyncio
import pytest
import yaml
from unittest.mock import AsyncMock, MagicMock, patch

from capella_mcp.model_manager import ModelManager, ModelCache, CapellaModelWrapper


class TestModelManager:
    """Test model management with caching and capellambse"""

    @pytest.fixture
    async def model_manager(self):
        """Create model manager instance"""
        manager = ModelManager(cache_enabled=True, cache_ttl=3600)
        await manager.initialize()
        return manager

    @pytest.fixture
    def mock_capellambse_model(self):
        """Create mock capellambse model"""
        with patch("capellambse.MelodyModel") as mock:
            model = MagicMock()
            model.name = "TestModel"
            model.uuid = "model-uuid-123"

            # Mock architectures
            model.pa = MagicMock()
            model.pa.all_components = [
                MagicMock(uuid="comp-1", name="Component1"),
                MagicMock(uuid="comp-2", name="Component2"),
            ]
            model.pa.all_functions = [MagicMock(uuid="func-1", name="Function1")]

            model.la = MagicMock()
            model.la.all_components = []
            model.la.all_functions = []

            mock.return_value = model
            return mock, model

    @pytest.mark.asyncio
    async def test_load_model(self, model_manager, mock_capellambse_model):
        """Test loading a Capella model"""
        # Arrange
        mock_class, mock_instance = mock_capellambse_model
        model_path = "test/model.capella"

        # Act
        with patch("capellambse.MelodyModel", mock_class):
            model = await model_manager.load_model(model_path)

        # Assert
        assert model is not None
        assert model.name == "TestModel"
        assert model_path in model_manager.loaded_models
        mock_class.assert_called_once_with(model_path)

    @pytest.mark.asyncio
    async def test_load_model_with_cache(self, model_manager, mock_capellambse_model):
        """Test that models are cached after loading"""
        # Arrange
        mock_class, mock_instance = mock_capellambse_model
        model_path = "test/model.capella"

        # Act
        with patch("capellambse.MelodyModel", mock_class):
            model1 = await model_manager.load_model(model_path, cache=True)
            model2 = await model_manager.load_model(model_path, cache=True)

        # Assert
        assert model1 is model2  # Same instance returned from cache
        mock_class.assert_called_once()  # Only loaded once

    @pytest.mark.asyncio
    async def test_get_model_by_id(self, model_manager, mock_capellambse_model):
        """Test retrieving model by ID"""
        # Arrange
        mock_class, mock_instance = mock_capellambse_model

        with patch("capellambse.MelodyModel", mock_class):
            loaded_model = await model_manager.load_model("test/model.capella")
            model_id = loaded_model.id

        # Act
        retrieved_model = await model_manager.get_model(model_id)

        # Assert
        assert retrieved_model is loaded_model

    @pytest.mark.asyncio
    async def test_get_nonexistent_model(self, model_manager):
        """Test retrieving non-existent model raises error"""
        # Act & Assert
        with pytest.raises(ValueError, match="Model not found"):
            await model_manager.get_model("non-existent-id")

    @pytest.mark.asyncio
    async def test_list_models(self, model_manager, mock_capellambse_model):
        """Test listing all loaded models"""
        # Arrange
        mock_class, mock_instance = mock_capellambse_model

        with patch("capellambse.MelodyModel", mock_class):
            await model_manager.load_model("model1.capella")
            await model_manager.load_model("model2.capella")

        # Act
        models = await model_manager.list_models()

        # Assert
        assert len(models) == 2

    @pytest.mark.asyncio
    async def test_load_registry(self, model_manager):
        """Test loading model registry from YAML"""
        # Arrange
        registry_content = """
models:
  - path: "models/model1.capella"
    name: "Model 1"
    auto_load: true
  - path: "models/model2.capella"
    name: "Model 2"
    auto_load: false
"""

        with patch("builtins.open", MagicMock()):
            with patch("yaml.safe_load", return_value=yaml.safe_load(registry_content)):
                with patch.object(
                    model_manager, "load_model", new_callable=AsyncMock
                ) as mock_load:
                    # Act
                    await model_manager.load_registry("config/registry.yaml")

        # Assert - Only auto_load=true models should be loaded
        assert mock_load.call_count == 1
        mock_load.assert_called_with("models/model1.capella")

    @pytest.mark.asyncio
    async def test_cleanup_cache(self, model_manager):
        """Test cache cleanup removes expired entries"""
        # Arrange
        model_manager.cache.ttl = 0.1  # 100ms TTL

        # Add model to cache
        await model_manager.load_model("test.capella", cache=True)

        # Wait for expiration
        await asyncio.sleep(0.2)

        # Act
        await model_manager.cleanup_cache()

        # Assert
        assert len(model_manager.cache.entries) == 0

    @pytest.mark.asyncio
    async def test_create_model_from_template(self, model_manager):
        """Test creating a new model from template"""
        # Arrange
        template = {
            "id": "hybrid-p2",
            "name": "P2 Hybrid Template",
            "components": [
                {"name": "Engine", "type": "PhysicalComponent"},
                {"name": "Motor", "type": "PhysicalComponent"},
            ],
        }

        # Act
        model = await model_manager.create_from_template(template, "NewModel")

        # Assert
        assert model.name == "NewModel"
        assert model.id in model_manager.loaded_models


class TestCapellaModelWrapper:
    """Test wrapper around capellambse model"""

    @pytest.fixture
    def mock_capella_model(self):
        """Create mock capellambse model"""
        model = MagicMock()
        model.name = "TestModel"
        model.uuid = "model-123"

        # Mock PA architecture
        model.pa = MagicMock()
        model.pa.all_components = [
            MagicMock(uuid="comp-1", name="Component1", type="PhysicalComponent")
        ]
        model.pa.all_functions = [
            MagicMock(uuid="func-1", name="Function1", type="PhysicalFunction")
        ]

        return model

    @pytest.fixture
    def model_wrapper(self, mock_capella_model):
        """Create model wrapper"""
        return CapellaModelWrapper(mock_capella_model)

    @pytest.mark.asyncio
    async def test_get_all_components(self, model_wrapper):
        """Test getting all components from model"""
        # Act
        components = await model_wrapper.get_all_components()

        # Assert
        assert len(components) == 1
        assert components[0].name == "Component1"

    @pytest.mark.asyncio
    async def test_get_component_by_id(self, model_wrapper):
        """Test getting specific component by ID"""
        # Act
        component = await model_wrapper.get_component("comp-1")

        # Assert
        assert component.name == "Component1"

    @pytest.mark.asyncio
    async def test_get_nonexistent_component(self, model_wrapper):
        """Test getting non-existent component raises error"""
        # Act & Assert
        with pytest.raises(ValueError, match="Component not found"):
            await model_wrapper.get_component("non-existent")

    @pytest.mark.asyncio
    async def test_create_component(self, model_wrapper):
        """Test creating a new component"""
        # Arrange
        model_wrapper.model.pa.create_component = MagicMock(
            return_value=MagicMock(uuid="new-comp", name="NewComponent")
        )

        # Act
        component = await model_wrapper.create_component(
            name="NewComponent", type="PhysicalComponent", nature="NODE"
        )

        # Assert
        assert component.uuid == "new-comp"
        assert component.name == "NewComponent"
        model_wrapper.model.pa.create_component.assert_called_once()

    @pytest.mark.asyncio
    async def test_model_to_dict(self, model_wrapper):
        """Test converting model to dictionary"""
        # Act
        model_dict = await model_wrapper.to_dict()

        # Assert
        assert model_dict["name"] == "TestModel"
        assert model_dict["id"] is not None
        assert "architectures" in model_dict
        assert "physical" in model_dict["architectures"]

    @pytest.mark.asyncio
    async def test_validate_model(self, model_wrapper):
        """Test model validation"""
        # Arrange
        model_wrapper.model.validate = MagicMock(return_value=True)

        # Act
        results = await model_wrapper.validate(["metamodel", "consistency"])

        # Assert
        assert len(results) == 2
        model_wrapper.model.validate.assert_called()

    @pytest.mark.asyncio
    async def test_save_model(self, model_wrapper):
        """Test saving model"""
        # Arrange
        model_wrapper.model.save = MagicMock()

        # Act
        await model_wrapper.save()

        # Assert
        model_wrapper.model.save.assert_called_once()


class TestModelCache:
    """Test model caching functionality"""

    def test_cache_creation(self):
        """Test cache initialization"""
        # Act
        cache = ModelCache(max_size=10, ttl=3600)

        # Assert
        assert cache.max_size == 10
        assert cache.ttl == 3600
        assert len(cache.entries) == 0

    def test_cache_add_and_get(self):
        """Test adding and retrieving from cache"""
        # Arrange
        cache = ModelCache()
        model = MagicMock()

        # Act
        cache.add("model-1", model)
        retrieved = cache.get("model-1")

        # Assert
        assert retrieved is model

    def test_cache_expiration(self):
        """Test cache entry expiration"""
        # Arrange
        cache = ModelCache(ttl=0.1)  # 100ms TTL
        cache.add("model-1", MagicMock())

        # Act
        import time

        time.sleep(0.2)
        retrieved = cache.get("model-1")

        # Assert
        assert retrieved is None  # Should be expired

    def test_cache_max_size(self):
        """Test cache respects max size"""
        # Arrange
        cache = ModelCache(max_size=2)

        # Act
        cache.add("model-1", MagicMock())
        cache.add("model-2", MagicMock())
        cache.add("model-3", MagicMock())  # Should evict model-1

        # Assert
        assert len(cache.entries) == 2
        assert cache.get("model-1") is None  # Evicted
        assert cache.get("model-2") is not None
        assert cache.get("model-3") is not None

    def test_cache_clear(self):
        """Test clearing cache"""
        # Arrange
        cache = ModelCache()
        cache.add("model-1", MagicMock())
        cache.add("model-2", MagicMock())

        # Act
        cache.clear()

        # Assert
        assert len(cache.entries) == 0
