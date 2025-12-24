# Capella AI Assistant - API Reference

This document serves as the ground truth for fine-tuning data generation. It documents the exposed `api` object available in the Groovy sandbox.

## Core API (`api`)

The main entry point for manipulating the Capella model.

### Finding Elements

#### `api.find(String type, String name)`
Finds a single element by its type and name.
*   **Parameters:**
    *   `type`: Simple class name (e.g., "SystemFunction", "LogicalComponent").
    *   `name`: The exact name of the element.
*   **Returns:** `EObject` or `null`.
*   **Example:**
    ```groovy
    def func = api.find("SystemFunction", "Process Data")
    ```

#### `api.findAll(String type)`
Finds all elements of a specific type.
*   **Parameters:**
    *   `type`: Simple class name.
    *   **Returns:** `List<EObject>` (never null).
*   **Example:**
    ```groovy
    def allComps = api.findAll("LogicalComponent")
    ```

#### `api.findById(String id)`
Finds an element by its unique ID (UUID).
*   **Parameters:**
    *   `id`: The UUID string.
*   **Returns:** `EObject` or `null`.
*   **Example:**
    ```groovy
    def elem = api.findById("0000-0000-...")
    ```

#### `api.layer(String name)`
Gets the root element of an architecture layer.
*   **Parameters:**
    *   `name`: "OA", "SA", "LA", or "PA".
*   **Returns:** `EObject` (the layer root).
*   **Example:**
    ```groovy
    def sa = api.layer("SA")
    ```

### Creating Elements

#### `api.create(String layer, String type, String name)`
Creates an element in a specific layer (auto-locates the correct container).
*   **Parameters:**
    *   `layer`: "OA", "SA", "LA", "PA".
    *   `type`: Element type (e.g., "SystemFunction").
    *   `name`: Name for the new element.
*   **Returns:** `EObject` (the created element).
*   **Example:**
    ```groovy
    api.create("SA", "SystemFunction", "New Capability")
    ```

#### `api.create(String type, String name, EObject parent)`
Creates an element under a specific parent.
*   **Parameters:**
    *   `type`: Element type.
    *   `name`: Name.
    *   `parent`: The parent `EObject`.
*   **Returns:** `EObject`.
*   **Example:**
    ```groovy
    def pkg = api.container("SA", "function")
    api.create("SystemFunction", "Sub Function", pkg)
    ```

### modifying Elements

#### `api.set(EObject element, String property, Object value)`
Sets a property on an element.
*   **Parameters:**
    *   `element`: The target element.
    *   `property`: Property name (e.g., "description", "summary").
    *   `value`: New value.
*   **Returns:** `boolean` (success).
*   **Example:**
    ```groovy
    api.set(func, "description", "Handles raw sensor input")
    ```

#### `api.rename(EObject element, String newName)`
Renames an element.
*   **Parameters:**
    *   `element`: Target.
    *   `newName`: New name.
*   **Returns:** `boolean`.
*   **Example:**
    ```groovy
    api.rename(comp, "Flight Computer A")
    ```

#### `api.delete(EObject element)`
Deletes an element from the model.
*   **Parameters:**
    *   `element`: Target to delete.
*   **Returns:** `boolean`.
*   **Example:**
    ```groovy
    api.delete(oldFunc)
    ```

### Relationships

#### `api.link(EObject source, EObject target, String relationType)`
Creates a relationship (link) between two elements.
*   **Parameters:**
    *   `source`: Source element.
    *   `target`: Target element.
    *   `relationType`: "FunctionalExchange", "ComponentExchange", "realization", etc.
*   **Returns:** `EObject` (the created link).
*   **Example:**
    ```groovy
    api.link(funcA, funcB, "FunctionalExchange")
    ```

#### `api.allocate(EObject function, EObject component)`
Allocates a function to a component.
*   **Parameters:**
    *   `function`: The function/activity.
    *   `component`: The component/entity.
*   **Returns:** `boolean`.
*   **Example:**
    ```groovy
    api.allocate(processData, server)
    ```

### Transaction Management

#### `api.batch(String label, Closure closure)`
Groups multiple operations into a single undoable transaction.
*   **Parameters:**
    *   `label`: Name of the operation (for Undo menu).
    *   `closure`: Code block to execute.
*   **Returns:** Result of the closure.
*   **Example:**
    ```groovy
    api.batch("Create Subsystem") {
        def comp = api.create("SA", "SystemComponent", "Engine")
        def func = api.create("SA", "SystemFunction", "Thrust")
        api.allocate(func, comp)
    }
    ```

## Diagram API (`api.diagram`)

Operations for creating and modifying Sirius diagrams.

#### `api.diagram.create(String type, String name, EObject target)`
Creates a new diagram.
*   **Parameters:**
    *   `type`: Abbreviation ("SAB", "LAB", "OAB") or full name.
    *   `name`: Title of the diagram.
    *   `target`: Context element (usually layer root or package).
*   **Returns:** `DRepresentation`.
*   **Example:**
    ```groovy
    def sa = api.layer("SA")
    def diag = api.diagram.create("SAB", "System Context", sa)
    ```

#### `api.diagram.add(DRepresentation diagram, EObject element)`
Adds an element to an existing diagram.
*   **Parameters:**
    *   `diagram`: The target diagram.
    *   `element`: The semantic element to visualize.
*   **Returns:** `boolean`.
*   **Example:**
    ```groovy
    api.diagram.add(diag, serverComp)
    ```

#### `api.diagram.layout(DRepresentation diagram, String strategy)`
Applies an auto-layout algorithm.
*   **Parameters:**
    *   `diagram`: Target diagram.
    *   `strategy`: "auto", "flow", "radial", "tree".
*   **Returns:** `boolean`.
*   **Example:**
    ```groovy
    api.diagram.layout(diag, "flow")
    ```

## Filesystem API (`api.fs`)

Used for self-discovery and documentation reading.

#### `api.fs.list(String path)`
Lists files in the virtual documentation system.
*   **Parameters:**
    *   `path`: Directory path (e.g., "api/", "skills/").
*   **Returns:** `List<String>`.
*   **Example:**
    ```groovy
    def docs = api.fs.list("api/")
    ```

#### `api.fs.read(String path)`
Reads the content of a documentation file.
*   **Parameters:**
    *   `path`: File path (e.g., "api/create.md").
*   **Returns:** `String` (content).
*   **Example:**
    ```groovy
    def help = api.fs.read("api/create.md")
    ```

## Skills API (`api.skills`)

Access to reusable automation patterns.

#### `api.skills.run(String name, Map args)`
Executes a named skill.
*   **Parameters:**
    *   `name`: Skill ID (e.g., "create-crud-interface").
    *   `args`: Map of arguments required by the skill.
*   **Returns:** `ExecutionResult`.
*   **Example:**
    ```groovy
    api.skills.run("create-state-machine", [
        name: "FlightModes",
        states: ["Idle", "Taxi", "Flying"]
    ])
    ```
