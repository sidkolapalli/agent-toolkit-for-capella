# CAPELLA MCP SERVER - USER GUIDE
## Complete ARCADIA Modeling with 100% Element Support

**Version**: 1.0  
**Date**: 2025-08-12  
**ARCADIA Coverage**: 100% (16/16 elements)  
**Status**: Production Ready  

---

## QUICK START

### Prerequisites
- Python 3.8+
- capellambse library
- Existing Capella model (.aird file)

### Installation
```bash
git clone <repository>
cd capella-mcp-server
uv venv
uv activate
uv sync
```

### Basic Usage
```python
from capella_mcp.model_manager import ModelManager
import asyncio

async def main():
    # Initialize manager
    manager = ModelManager()
    
    # Load your model
    model_id = await manager.load_model("path/to/your/model.aird")
    
    # Create elements
    entity_id = await manager.create_element(model_id, "OperationalEntity", name="Customer")
    
    # Query elements
    entities = await manager.query_elements(model_id, {"type": "OperationalEntity"})
    
    # Save model
    await manager.save_model(model_id)

asyncio.run(main())
```

---

## COMPLETE ARCADIA ELEMENT REFERENCE

### Operational Architecture (OA) Elements

#### OperationalEntity
**Purpose**: Represents external actors or entities in the operational context  
**API**: `oa.entity_pkg.entities.create()`  

```python
# Create an operational entity
entity_id = await manager.create_element(
    model_id, 
    "OperationalEntity", 
    name="Customer",
    description="End user of the system"
)

# Query operational entities
entities = await manager.query_elements(model_id, {"type": "OperationalEntity"})
```

#### OperationalActivity  
**Purpose**: High-level business processes and workflows  
**API**: `oa.function_pkg.activities.create()`  

```python
# Create an operational activity
activity_id = await manager.create_element(
    model_id, 
    "OperationalActivity", 
    name="Process Order",
    description="Handle customer order processing"
)

# Find activities by name
activities = await manager.query_elements(model_id, {
    "type": "OperationalActivity",
    "name_contains": "Order"
})
```

#### OperationalCapability
**Purpose**: Business capabilities required to achieve operational goals  
**API**: `oa.capability_pkg.capabilities.create()`  

```python
# Create an operational capability
capability_id = await manager.create_element(
    model_id, 
    "OperationalCapability", 
    name="Customer Management",
    description="Ability to manage customer relationships"
)
```

### System Architecture (SA) Elements

#### SystemFunction
**Purpose**: System-level functions that support operational activities  
**API**: `sa.function_pkg.functions.create()`  

```python
# Create a system function
function_id = await manager.create_element(
    model_id, 
    "SystemFunction", 
    name="Authenticate User",
    description="Verify user credentials"
)
```

#### SystemComponent
**Purpose**: Major system components at architectural level  
**API**: `sa.component_pkg.components.create()`  

```python
# Create a system component
component_id = await manager.create_element(
    model_id, 
    "SystemComponent", 
    name="Authentication Service",
    description="Handles user authentication"
)
```

#### SystemCapability
**Purpose**: System capabilities that deliver operational capabilities  
**API**: `sa.capability_pkg.capabilities.create()`  

```python
# Create a system capability
capability_id = await manager.create_element(
    model_id, 
    "SystemCapability", 
    name="User Authentication",
    description="System capability to authenticate users"
)
```

#### SystemActor ⭐ BREAKTHROUGH ELEMENT
**Purpose**: External actors that interact with the system  
**API**: `sa.component_pkg.components.create(is_actor=True)`  

```python
# Create a system actor (breakthrough implementation)
actor_id = await manager.create_element(
    model_id, 
    "SystemActor", 
    name="External Payment Gateway",
    description="Third-party payment processing service"
)

# Query system actors specifically
actors = await manager.query_elements(model_id, {"type": "SystemActor"})

# Verify actor property
for actor in actors:
    if hasattr(actor, 'is_actor') and actor.is_actor:
        print(f"Confirmed SystemActor: {actor.name}")
```

### Logical Architecture (LA) Elements

#### LogicalComponent
**Purpose**: Logical components that implement system functions  
**API**: `la.component_pkg.components.create()`  

```python
# Create a logical component
component_id = await manager.create_element(
    model_id, 
    "LogicalComponent", 
    name="User Manager",
    description="Manages user accounts and profiles"
)
```

#### LogicalActor
**Purpose**: External actors at the logical level  
**API**: `la.component_pkg.components.create(is_actor=True)`  

```python
# Create a logical actor
actor_id = await manager.create_element(
    model_id, 
    "LogicalActor", 
    name="Database Server",
    description="External database system"
)
```

#### Interface
**Purpose**: Defines contracts between logical components  
**API**: `la.interface_pkg.interfaces.create()`  

```python
# Create an interface
interface_id = await manager.create_element(
    model_id, 
    "Interface", 
    name="UserDataInterface",
    description="Interface for user data operations"
)
```

#### FunctionalChain
**Purpose**: Sequences of logical functions working together  
**API**: `capability.functional_chains.create()`  

```python
# Create a functional chain (requires capability context)
chain_id = await manager.create_element(
    model_id, 
    "FunctionalChain", 
    name="User Login Chain",
    description="Complete user login process"
)
```

#### FunctionalExchange
**Purpose**: Data/control exchanges between logical functions  
**API**: `la.function_pkg.exchanges.create()`  

```python
# Create a functional exchange
exchange_id = await manager.create_element(
    model_id, 
    "FunctionalExchange", 
    name="Login Credentials",
    description="User credentials exchange"
)
```

### Physical Architecture (PA) Elements

#### PhysicalComponent
**Purpose**: Physical hardware or software components  
**API**: `pa.component_pkg.components.create()`  

```python
# Create a physical component
component_id = await manager.create_element(
    model_id, 
    "PhysicalComponent", 
    name="Application Server",
    description="Server hosting the application"
)
```

#### PhysicalFunction
**Purpose**: Functions implemented by physical components  
**API**: `pa.function_pkg.functions.create()`  

```python
# Create a physical function
function_id = await manager.create_element(
    model_id, 
    "PhysicalFunction", 
    name="Process HTTP Request",
    description="Handle incoming HTTP requests"
)
```

#### PhysicalLink ⭐ BREAKTHROUGH ELEMENT
**Purpose**: Physical connections and communication paths  
**API**: `pa.component_exchange_categories.create()`  

```python
# Create a physical link (breakthrough implementation)
link_id = await manager.create_element(
    model_id, 
    "PhysicalLink", 
    name="Network Connection",
    description="TCP/IP network link between servers"
)

# Query physical links
links = await manager.query_elements(model_id, {"type": "PhysicalLink"})

# Physical links are implemented as ComponentExchangeCategory
print(f"Created {len(links)} physical links")
```

#### NodeComponent ⭐ BREAKTHROUGH ELEMENT
**Purpose**: Deployment nodes and infrastructure components  
**API**: `pa.component_pkg.components.create(nature="NODE")`  

```python
# Create a node component (breakthrough implementation)
node_id = await manager.create_element(
    model_id, 
    "NodeComponent", 
    name="Production Server",
    description="Physical server for production deployment"
)

# Query node components specifically
nodes = await manager.query_elements(model_id, {"type": "NodeComponent"})

# Verify nature property
for node in nodes:
    if hasattr(node, 'nature') and node.nature == "NODE":
        print(f"Confirmed NodeComponent: {node.name}")
```

---

## PRACTICAL WORKFLOWS

### Complete System Modeling Workflow

```python
async def create_complete_system():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # 1. Start with Operational Architecture
    customer = await manager.create_element(model_id, "OperationalEntity", 
                                          name="Customer")
    
    order_process = await manager.create_element(model_id, "OperationalActivity", 
                                               name="Place Order")
    
    # 2. Define System Architecture
    order_system = await manager.create_element(model_id, "SystemComponent", 
                                              name="Order Management System")
    
    payment_gateway = await manager.create_element(model_id, "SystemActor", 
                                                 name="Payment Gateway")
    
    # 3. Design Logical Architecture
    order_manager = await manager.create_element(model_id, "LogicalComponent", 
                                               name="Order Manager")
    
    payment_interface = await manager.create_element(model_id, "Interface", 
                                                   name="Payment Interface")
    
    # 4. Implement Physical Architecture
    app_server = await manager.create_element(model_id, "PhysicalComponent", 
                                            name="Application Server")
    
    db_server = await manager.create_element(model_id, "NodeComponent", 
                                           name="Database Server")
    
    network_link = await manager.create_element(model_id, "PhysicalLink", 
                                              name="Internal Network")
    
    # 5. Save the complete model
    await manager.save_model(model_id)
    
    return {
        "operational": [customer, order_process],
        "system": [order_system, payment_gateway],
        "logical": [order_manager, payment_interface],
        "physical": [app_server, db_server, network_link]
    }
```

### Querying and Analysis Workflow

```python
async def analyze_architecture():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Get overview of all elements
    all_elements = await manager.query_elements(model_id)
    print(f"Total elements in model: {len(all_elements)}")
    
    # Analyze by layer
    layers = {
        "OA": ["OperationalEntity", "OperationalActivity", "OperationalCapability"],
        "SA": ["SystemFunction", "SystemComponent", "SystemCapability", "SystemActor"],
        "LA": ["LogicalComponent", "LogicalActor", "Interface", "FunctionalChain", "FunctionalExchange"],
        "PA": ["PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent"]
    }
    
    for layer_name, element_types in layers.items():
        print(f"\n{layer_name} Layer Analysis:")
        for element_type in element_types:
            elements = await manager.query_elements(model_id, {"type": element_type})
            print(f"  {element_type}: {len(elements)} elements")
    
    # Find specific patterns
    actors = await manager.query_elements(model_id, {"type": "SystemActor"})
    nodes = await manager.query_elements(model_id, {"type": "NodeComponent"})
    links = await manager.query_elements(model_id, {"type": "PhysicalLink"})
    
    print(f"\nBreakthrough Elements:")
    print(f"  SystemActors: {len(actors)}")
    print(f"  NodeComponents: {len(nodes)}")
    print(f"  PhysicalLinks: {len(links)}")
```

### Model Validation Workflow

```python
async def validate_model_completeness():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Check for presence of all ARCADIA element types
    required_elements = [
        "OperationalEntity", "OperationalActivity", "OperationalCapability",
        "SystemFunction", "SystemComponent", "SystemCapability", "SystemActor",
        "LogicalComponent", "LogicalActor", "Interface", "FunctionalChain", "FunctionalExchange",
        "PhysicalComponent", "PhysicalFunction", "PhysicalLink", "NodeComponent"
    ]
    
    coverage_report = {}
    
    for element_type in required_elements:
        elements = await manager.query_elements(model_id, {"type": element_type})
        coverage_report[element_type] = {
            "count": len(elements),
            "present": len(elements) > 0
        }
    
    # Generate coverage statistics
    total_types = len(required_elements)
    covered_types = sum(1 for info in coverage_report.values() if info["present"])
    coverage_percentage = (covered_types / total_types) * 100
    
    print(f"ARCADIA Coverage: {coverage_percentage:.0f}% ({covered_types}/{total_types})")
    
    # Identify gaps
    missing_types = [t for t, info in coverage_report.items() if not info["present"]]
    if missing_types:
        print(f"Missing element types: {missing_types}")
    else:
        print("✅ Complete ARCADIA coverage achieved!")
    
    return coverage_report
```

---

## ADVANCED FEATURES

### Model Persistence and Versioning

```python
async def model_versioning_example():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Create backup before modifications
    backup_path = "my_model_backup.aird"
    await manager.save_model(model_id)  # Ensure current state is saved
    
    # Make modifications
    new_component = await manager.create_element(
        model_id, "SystemComponent", name="New Feature Component"
    )
    
    # Save with modifications
    await manager.save_model(model_id)
    
    # Validate persistence
    manager2 = ModelManager()
    model_id2 = await manager2.load_model("my_model.aird")
    
    # Verify new component exists
    components = await manager2.query_elements(model_id2, {"type": "SystemComponent"})
    new_comp_found = any(c for c in components if hasattr(c, 'name') and 'New Feature' in c.name)
    
    print(f"Persistence verified: {new_comp_found}")
```

### Element Relationships and Dependencies

```python
async def analyze_relationships():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Create related elements
    system_comp = await manager.create_element(
        model_id, "SystemComponent", name="Core System"
    )
    
    logical_comp = await manager.create_element(
        model_id, "LogicalComponent", name="Core Logic"
    )
    
    physical_comp = await manager.create_element(
        model_id, "PhysicalComponent", name="Core Server"
    )
    
    # Create interfaces and links
    interface = await manager.create_element(
        model_id, "Interface", name="Core Interface"
    )
    
    link = await manager.create_element(
        model_id, "PhysicalLink", name="Core Network"
    )
    
    # Query for analysis
    all_core_elements = await manager.query_elements(model_id, {
        "name_contains": "Core"
    })
    
    print(f"Found {len(all_core_elements)} core-related elements")
    for element in all_core_elements:
        print(f"  - {element.name} ({type(element).__name__})")
```

### Performance Optimization

```python
async def optimized_bulk_creation():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Batch create multiple elements efficiently
    element_specs = [
        ("OperationalEntity", "Customer_1"),
        ("OperationalEntity", "Customer_2"),
        ("SystemComponent", "Service_1"),
        ("SystemComponent", "Service_2"),
        ("LogicalComponent", "Handler_1"),
        ("LogicalComponent", "Handler_2")
    ]
    
    created_elements = []
    for element_type, name in element_specs:
        element_id = await manager.create_element(model_id, element_type, name=name)
        created_elements.append((element_type, element_id))
    
    # Single save operation for all changes
    await manager.save_model(model_id)
    
    return created_elements
```

---

## ERROR HANDLING AND TROUBLESHOOTING

### Common Error Patterns

```python
async def robust_element_creation():
    manager = ModelManager()
    
    try:
        model_id = await manager.load_model("my_model.aird")
    except FileNotFoundError:
        print("Model file not found - check path")
        return
    except Exception as e:
        print(f"Model loading failed: {e}")
        return
    
    # Robust element creation with error handling
    for element_type in ["OperationalEntity", "SystemComponent", "LogicalComponent"]:
        try:
            element_id = await manager.create_element(
                model_id, element_type, name=f"Test_{element_type}"
            )
            print(f"✅ Created {element_type}: {element_id}")
            
        except ValueError as e:
            print(f"❌ Invalid element type {element_type}: {e}")
            
        except Exception as e:
            print(f"⚠️ Creation failed for {element_type}: {e}")
    
    # Safe save operation
    try:
        await manager.save_model(model_id)
        print("✅ Model saved successfully")
    except Exception as e:
        print(f"❌ Save failed: {e}")
```

### Debugging and Validation

```python
async def debug_model_state():
    manager = ModelManager()
    model_id = await manager.load_model("my_model.aird")
    
    # Check manager status
    status = manager.get_status()
    print("Manager Status:")
    for key, value in status.items():
        print(f"  {key}: {value}")
    
    # Validate model access
    model_wrapper = manager.get_model(model_id)
    if model_wrapper:
        info = model_wrapper.get_model_info()
        print(f"\nModel Info:")
        for key, value in info.items():
            print(f"  {key}: {value}")
    
    # Test each element type
    test_results = {}
    element_types = [
        "OperationalEntity", "SystemComponent", "LogicalComponent", "PhysicalComponent",
        "SystemActor", "PhysicalLink", "NodeComponent"
    ]
    
    for element_type in element_types:
        try:
            test_id = await manager.create_element(
                model_id, element_type, name=f"Debug_{element_type}"
            )
            test_results[element_type] = "SUCCESS"
        except Exception as e:
            test_results[element_type] = f"FAILED: {e}"
    
    print(f"\nElement Creation Test Results:")
    for element_type, result in test_results.items():
        print(f"  {element_type}: {result}")
```

---

## INTEGRATION EXAMPLES

### MCP Server Integration

```python
# Example MCP server endpoint implementation
from mcp import Server
from capella_mcp.model_manager import ModelManager

class CapellaMCPServer:
    def __init__(self):
        self.manager = ModelManager()
        self.server = Server("capella-server")
    
    async def load_model_endpoint(self, path: str):
        """MCP endpoint for loading models"""
        try:
            model_id = await self.manager.load_model(path)
            return {"success": True, "model_id": model_id}
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def create_element_endpoint(self, model_id: str, element_type: str, **kwargs):
        """MCP endpoint for creating elements"""
        try:
            element_id = await self.manager.create_element(model_id, element_type, **kwargs)
            return {"success": True, "element_id": element_id}
        except Exception as e:
            return {"success": False, "error": str(e)}
```

### CI/CD Pipeline Integration

```yaml
# Example GitHub Actions workflow
name: Capella Model Validation
on: [push, pull_request]

jobs:
  validate-model:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    - name: Setup Python
      uses: actions/setup-python@v2
      with:
        python-version: '3.9'
    
    - name: Install dependencies
      run: |
        pip install capellambse
        pip install -e .
    
    - name: Validate ARCADIA Coverage
      run: |
        python -c "
        import asyncio
        from capella_mcp.model_manager import ModelManager
        
        async def validate():
            manager = ModelManager()
            model_id = await manager.load_model('models/system.aird')
            
            # Test all 16 ARCADIA elements
            element_types = [
                'OperationalEntity', 'OperationalActivity', 'OperationalCapability',
                'SystemFunction', 'SystemComponent', 'SystemCapability', 'SystemActor',
                'LogicalComponent', 'LogicalActor', 'Interface', 'FunctionalChain', 'FunctionalExchange',
                'PhysicalComponent', 'PhysicalFunction', 'PhysicalLink', 'NodeComponent'
            ]
            
            success_count = 0
            for element_type in element_types:
                try:
                    await manager.create_element(model_id, element_type, name=f'Test_{element_type}')
                    success_count += 1
                except:
                    pass
            
            coverage = (success_count / len(element_types)) * 100
            print(f'ARCADIA Coverage: {coverage}%')
            
            if coverage < 100:
                exit(1)
        
        asyncio.run(validate())
        "
```

---

## BEST PRACTICES

### Model Organization
1. **Layer-based Structure**: Organize elements by ARCADIA layer
2. **Naming Conventions**: Use consistent naming patterns
3. **Documentation**: Include descriptions for all elements
4. **Validation**: Regular completeness checks
5. **Version Control**: Track model changes systematically

### Performance Optimization
1. **Batch Operations**: Create multiple elements before saving
2. **Query Efficiency**: Use specific type filters
3. **Memory Management**: Unload unused models
4. **Caching**: Leverage built-in model caching
5. **Error Handling**: Implement robust exception management

### Development Workflow
1. **Testing**: Always test element creation and queries
2. **Validation**: Verify model persistence
3. **Documentation**: Maintain up-to-date element documentation
4. **Monitoring**: Track performance and errors
5. **Maintenance**: Regular model health checks

---

## NEXT STEPS

### Getting Started Checklist
- [ ] Install capella-mcp-server and dependencies
- [ ] Load your first Capella model
- [ ] Test basic element creation
- [ ] Verify query functionality
- [ ] Implement model persistence
- [ ] Create your first complete ARCADIA workflow

### Advanced Topics
- [ ] Explore relationship management
- [ ] Implement custom validation rules
- [ ] Set up automated model testing
- [ ] Integrate with CI/CD pipelines
- [ ] Deploy MCP server for team use

### Community and Support
- **Documentation**: Complete technical reference available
- **Examples**: Sample implementations and patterns
- **Issues**: Report bugs and feature requests
- **Contributions**: Community development welcome

---

**User Guide Version**: 1.0  
**Last Updated**: 2025-08-12  
**ARCADIA Coverage**: ✅ 100% (16/16 elements)  
**Status**: Production Ready