package org.polarsys.capella.ai.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThreadLocal operation tracking for intelligent undo/redo label generation.
 *
 * <p>Tracks operations (create, delete, link, set, allocate) performed during a transaction and
 * generates human-readable labels for the undo/redo stack.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * OperationInferencer.begin();
 * try {
 *     // perform operations that call recordCreate(), recordDelete(), etc.
 *     api.create("SA", "SystemFunction", "Navigate");
 *     api.create("SA", "SystemFunction", "Control");
 *
 *     String label = OperationInferencer.endAndGenerateLabel();
 *     // Returns: "Create 2 SystemFunctions"
 * } finally {
 *     OperationInferencer.clear();
 * }
 * }</pre>
 *
 * <p>Thread-safe via ThreadLocal - each thread has its own tracker. Supports nested tracking with
 * depth counting.
 */
public final class OperationInferencer {

    private static final int MAX_LABEL_LENGTH = 60;
    private static final int MAX_TRACKED_OPERATIONS = 1000;
    private static final int MAX_TRACKING_DEPTH = 100;

    private static final ThreadLocal<OperationTracker> TRACKER =
            ThreadLocal.withInitial(OperationTracker::new);
    private static final ThreadLocal<String> LABEL_HINT = ThreadLocal.withInitial(() -> null);

    private OperationInferencer() {}

    /** Operation types that can be tracked. */
    public enum OpType {
        CREATE,
        DELETE,
        LINK,
        SET,
        ALLOCATE,
        DIAGRAM
    }

    /** Record of a single operation. */
    private record Operation(OpType type, String elementType, String elementName, String extra) {
        Operation(OpType type, String elementType, String elementName) {
            this(type, elementType, elementName, null);
        }
    }

    /** Tracker state for a single thread. */
    private static final class OperationTracker {
        private final List<Operation> operations = new ArrayList<>();
        private int depth = 0;

        void reset() {
            operations.clear();
            depth = 0;
        }
    }

    /**
     * Begin tracking operations.
     *
     * @return true if this is the outermost tracking context, false if nested
     */
    public static boolean begin() {
        OperationTracker tracker = TRACKER.get();

        // Prevent unbounded depth (protects against infinite recursion bugs)
        if (tracker.depth >= MAX_TRACKING_DEPTH) {
            System.err.println(
                    "OperationInferencer: Max tracking depth ("
                            + MAX_TRACKING_DEPTH
                            + ") reached. Possible infinite recursion or missing"
                            + " endAndGenerateLabel() calls.");
            return false;
        }

        tracker.depth++;
        if (tracker.depth == 1) {
            tracker.operations.clear();
            LABEL_HINT.set(null);
            return true;
        }
        return false;
    }

    /**
     * Check if currently tracking operations.
     *
     * @return true if tracking is active
     */
    public static boolean isTracking() {
        return TRACKER.get().depth > 0;
    }

    /**
     * Record a create operation.
     *
     * @param type the element type (e.g., "SystemFunction", "LogicalComponent")
     * @param name the element name
     */
    public static void recordCreate(String type, String name) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            // Defensive null handling - never let tracking break operations
            String safeType = type != null ? type : "Unknown";
            String safeName = name != null ? name : "unnamed";
            TRACKER.get().operations.add(new Operation(OpType.CREATE, safeType, safeName));
        } catch (Exception e) {
            // Never let tracking failures break the actual operation
            System.err.println(
                    "OperationInferencer.recordCreate failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Record a delete operation.
     *
     * @param type the element type
     * @param name the element name
     */
    public static void recordDelete(String type, String name) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            String safeType = type != null ? type : "Unknown";
            String safeName = name != null ? name : "unnamed";
            TRACKER.get().operations.add(new Operation(OpType.DELETE, safeType, safeName));
        } catch (Exception e) {
            System.err.println(
                    "OperationInferencer.recordDelete failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Record a link/relationship creation operation.
     *
     * @param sourceType the source element type
     * @param targetType the target element type
     * @param relationType the relationship type (e.g., "FunctionalExchange")
     */
    public static void recordLink(String sourceType, String targetType, String relationType) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            String safeSource = sourceType != null ? sourceType : "Unknown";
            String safeTarget = targetType != null ? targetType : "Unknown";
            String safeRelation = relationType != null ? relationType : "relationship";
            TRACKER.get()
                    .operations
                    .add(
                            new Operation(
                                    OpType.LINK, safeRelation, safeSource + " -> " + safeTarget));
        } catch (Exception e) {
            System.err.println(
                    "OperationInferencer.recordLink failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Record a property set operation.
     *
     * @param type the element type
     * @param property the property name
     */
    public static void recordSet(String type, String property) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            String safeType = type != null ? type : "Unknown";
            String safeProperty = property != null ? property : "property";
            TRACKER.get().operations.add(new Operation(OpType.SET, safeType, safeProperty));
        } catch (Exception e) {
            System.err.println(
                    "OperationInferencer.recordSet failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Record an allocation operation.
     *
     * @param functionType the function type
     * @param componentType the component type
     */
    public static void recordAllocate(String functionType, String componentType) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            String safeFunction = functionType != null ? functionType : "function";
            String safeComponent = componentType != null ? componentType : "component";
            TRACKER.get()
                    .operations
                    .add(new Operation(OpType.ALLOCATE, safeFunction, safeComponent));
        } catch (Exception e) {
            System.err.println(
                    "OperationInferencer.recordAllocate failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Record a diagram creation operation.
     *
     * @param diagramType the diagram type (e.g., "SAB", "LAB")
     * @param diagramName the diagram name
     */
    public static void recordDiagram(String diagramType, String diagramName) {
        try {
            if (!tryAddOperation()) {
                return;
            }
            String safeType = diagramType != null ? diagramType : "diagram";
            String safeName = diagramName != null ? diagramName : "unnamed";
            TRACKER.get().operations.add(new Operation(OpType.DIAGRAM, safeType, safeName));
        } catch (Exception e) {
            System.err.println(
                    "OperationInferencer.recordDiagram failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Check if we should add an operation to the tracker. Returns false if not tracking or if we've
     * hit the size limit.
     */
    private static boolean tryAddOperation() {
        if (!isTracking()) {
            return false;
        }

        OperationTracker tracker = TRACKER.get();
        int currentSize = tracker.operations.size();

        // Prevent unbounded growth
        if (currentSize >= MAX_TRACKED_OPERATIONS) {
            // Add overflow marker once when we hit the limit
            if (currentSize == MAX_TRACKED_OPERATIONS) {
                tracker.operations.add(
                        new Operation(
                                OpType.CREATE,
                                "OVERFLOW",
                                "(" + (MAX_TRACKED_OPERATIONS - 1) + "+ operations)"));
                System.err.println(
                        "OperationInferencer: Tracking limit reached ("
                                + MAX_TRACKED_OPERATIONS
                                + "). Further operations not individually tracked.");
            }
            return false;
        }

        return true;
    }

    /**
     * End tracking and generate a descriptive label.
     *
     * @return the generated label, or null if this was a nested context or no operations were
     *     recorded
     */
    public static String endAndGenerateLabel() {
        OperationTracker tracker = TRACKER.get();
        if (tracker.depth <= 0) {
            return null;
        }

        tracker.depth--;

        // Only generate label when exiting outermost context
        if (tracker.depth > 0) {
            return null;
        }

        String hint = LABEL_HINT.get();
        LABEL_HINT.set(null);

        if (tracker.operations.isEmpty()) {
            return null;
        }

        if (hint != null && !hint.isBlank()) {
            tracker.operations.clear();
            return truncate(hint);
        }

        String label = generateLabel(tracker.operations);
        tracker.operations.clear();
        return label;
    }

    /** Clear tracking state (for error recovery). */
    public static void clear() {
        TRACKER.get().reset();
        LABEL_HINT.set(null);
    }

    /**
     * Clear ThreadLocal state to avoid leaks when the plugin stops. This should be called from the
     * plugin's Activator.stop() method.
     */
    public static void cleanup() {
        TRACKER.remove();
        LABEL_HINT.remove();
    }

    /**
     * Provide a label hint for the current tracking context. Useful when a caller wants to enforce
     * a specific undo label.
     */
    public static void hintLabel(String label) {
        if (label == null || label.isBlank()) {
            return;
        }
        LABEL_HINT.set(label);
    }

    /**
     * Generate a human-readable label from tracked operations.
     *
     * <p>Rules:
     *
     * <ul>
     *   <li>Single operation: "Create SystemFunction 'Process Data'"
     *   <li>Multiple same type: "Create 3 SystemFunctions"
     *   <li>Mixed operations: "Create 2 elements, link 1 exchange"
     *   <li>Max length: 60 characters, truncate with "..."
     * </ul>
     */
    private static String generateLabel(List<Operation> ops) {
        if (ops.isEmpty()) {
            return null;
        }

        // Single operation - detailed label
        if (ops.size() == 1) {
            return formatSingleOperation(ops.get(0));
        }

        // Group by operation type
        Map<OpType, List<Operation>> byType = new HashMap<>();
        for (Operation op : ops) {
            byType.computeIfAbsent(op.type(), k -> new ArrayList<>()).add(op);
        }

        // Single type - group label
        if (byType.size() == 1) {
            OpType type = byType.keySet().iterator().next();
            List<Operation> typeOps = byType.get(type);
            return formatGroupedOperations(type, typeOps);
        }

        // Mixed types - summary label
        return formatMixedOperations(byType);
    }

    private static String formatSingleOperation(Operation op) {
        String label =
                switch (op.type()) {
                    case CREATE -> "Create " + op.elementType() + " '" + op.elementName() + "'";
                    case DELETE -> "Delete " + op.elementType() + " '" + op.elementName() + "'";
                    case LINK -> "Create " + op.elementType();
                    case SET -> "Set " + op.elementName() + " on " + op.elementType();
                    case ALLOCATE -> "Allocate " + op.elementType() + " to " + op.elementName();
                    case DIAGRAM ->
                            "Create " + op.elementType() + " diagram '" + op.elementName() + "'";
                };
        return truncate(label);
    }

    private static String formatGroupedOperations(OpType type, List<Operation> ops) {
        int count = ops.size();

        // Check if all same element type
        String firstElementType = ops.get(0).elementType();
        boolean allSameType =
                ops.stream().allMatch(op -> firstElementType.equals(op.elementType()));

        String label;
        if (allSameType) {
            String plural = count > 1 ? pluralize(firstElementType) : firstElementType;
            label =
                    switch (type) {
                        case CREATE -> "Create " + count + " " + plural;
                        case DELETE -> "Delete " + count + " " + plural;
                        case LINK -> "Create " + count + " " + plural;
                        case SET -> "Set " + count + " properties";
                        case ALLOCATE -> "Allocate " + count + " functions";
                        case DIAGRAM -> "Create " + count + " diagrams";
                    };
        } else {
            label =
                    switch (type) {
                        case CREATE -> "Create " + count + " elements";
                        case DELETE -> "Delete " + count + " elements";
                        case LINK -> "Create " + count + " relationships";
                        case SET -> "Set " + count + " properties";
                        case ALLOCATE -> "Allocate " + count + " functions";
                        case DIAGRAM -> "Create " + count + " diagrams";
                    };
        }
        return truncate(label);
    }

    private static String formatMixedOperations(Map<OpType, List<Operation>> byType) {
        StringBuilder sb = new StringBuilder();

        // Order: CREATE, DELETE, LINK, ALLOCATE, SET, DIAGRAM
        OpType[] order = {
            OpType.CREATE, OpType.DELETE, OpType.LINK, OpType.ALLOCATE, OpType.SET, OpType.DIAGRAM
        };

        int partsAdded = 0;
        for (OpType type : order) {
            if (byType.containsKey(type) && partsAdded < 3) {
                if (partsAdded > 0) {
                    sb.append(", ");
                }
                int count = byType.get(type).size();
                sb.append(
                        switch (type) {
                            case CREATE ->
                                    count == 1
                                            ? "create 1 element"
                                            : "create " + count + " elements";
                            case DELETE ->
                                    count == 1
                                            ? "delete 1 element"
                                            : "delete " + count + " elements";
                            case LINK ->
                                    count == 1
                                            ? "link 1 relationship"
                                            : "link " + count + " relationships";
                            case SET ->
                                    count == 1 ? "set 1 property" : "set " + count + " properties";
                            case ALLOCATE ->
                                    count == 1
                                            ? "allocate 1 function"
                                            : "allocate " + count + " functions";
                            case DIAGRAM ->
                                    count == 1
                                            ? "create 1 diagram"
                                            : "create " + count + " diagrams";
                        });
                partsAdded++;
            }
        }

        // Capitalize first letter
        if (!sb.isEmpty()) {
            sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        }

        return truncate(sb.toString());
    }

    private static String pluralize(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        if (word.endsWith("y")) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.endsWith("s")
                || word.endsWith("x")
                || word.endsWith("ch")
                || word.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    private static String truncate(String label) {
        if (label == null || label.length() <= MAX_LABEL_LENGTH) {
            return label;
        }
        return label.substring(0, MAX_LABEL_LENGTH - 3) + "...";
    }
}
