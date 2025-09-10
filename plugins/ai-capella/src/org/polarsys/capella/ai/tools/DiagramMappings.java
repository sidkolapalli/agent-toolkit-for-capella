package org.polarsys.capella.ai.tools;

import java.util.HashMap;
import java.util.Map;

/** Static mapping from (layer,type) to Sirius diagram description names. */
public final class DiagramMappings {
    private DiagramMappings() {}

    private static final Map<String, String> MAP = new HashMap<>();
    static {
        // Operational
        MAP.put("OA_OAB", "Operational Architecture Blank");
        MAP.put("OA_OES", "Operational Entity Scenario");
        MAP.put("OA_OPD", "Operational Process Description");
        MAP.put("OA_OCB", "Operational Capabilities Blank");
        MAP.put("OA_OEB", "Operational Entity Breakdown");
        MAP.put("OA_ORB", "Operational Role Breakdown");
        // System
        MAP.put("SA_SAB", "System Architecture Blank");
        MAP.put("SA_ES", "Exchange Scenario");
        MAP.put("SA_FS", "Functional Scenario");
        MAP.put("SA_EAB", "EPBS Architecture Blank");
        MAP.put("SA_CSA", "Component Scenario");
        MAP.put("SA_SDFB", "System Data Flow Blank");
        // Logical
        MAP.put("LA_LAB", "Logical Architecture Blank");
        MAP.put("LA_LDFB", "Logical Data Flow Blank");
        MAP.put("LA_LFBD", "Logical Function Breakdown");
        MAP.put("LA_ES", "Exchange Scenario");
        MAP.put("LA_FS", "Functional Scenario");
        MAP.put("LA_EAB", "EPBS Architecture Blank");
        // Physical
        MAP.put("PA_PAB", "Physical Architecture Blank");
        MAP.put("PA_PDFB", "Physical Data Flow Blank");
        MAP.put("PA_PFBD", "Physical Function Breakdown");
        MAP.put("PA_ES", "Exchange Scenario");
        MAP.put("PA_FS", "Functional Scenario");
        MAP.put("PA_EAB", "EPBS Architecture Blank");
        // Common
        MAP.put("CDB", "Class Diagram Blank");
        MAP.put("IS", "Interface Scenario");
    }

    public static String descriptionFor(String layer, String type) {
        if (layer == null || type == null) return type;
        String key = layer.toUpperCase() + "_" + type.toUpperCase();
        return MAP.getOrDefault(key, type);
    }
}

