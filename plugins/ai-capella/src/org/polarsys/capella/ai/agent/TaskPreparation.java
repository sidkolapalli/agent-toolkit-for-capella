package org.polarsys.capella.ai.agent;

import java.util.Map;

/** Bounded caller-supplied compilation diagnostics, without execution or model facts. */
public final class TaskPreparation {
    public static final int MAX_DIAGNOSTIC_CHARS = 8192;
    public static final String PREFIX = "AUTHORITATIVE CAPELLA PREPARATION RESULT";

    private TaskPreparation() {}

    public static String compilationFailure(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank() || diagnostic.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A bounded compilation diagnostic is required");
        }
        int end = Math.min(diagnostic.length(), MAX_DIAGNOSTIC_CHARS);
        if (end < diagnostic.length() && Character.isHighSurrogate(diagnostic.charAt(end - 1)))
            end--;
        return AgentJson.write(
                Map.of(
                        "compiled",
                        false,
                        "executed",
                        false,
                        "applied",
                        false,
                        "modelChanged",
                        false,
                        "undoCreated",
                        false,
                        "saveRequested",
                        false,
                        "diagnostic",
                        diagnostic.substring(0, end),
                        "diagnosticTruncated",
                        end < diagnostic.length()));
    }

    public static final String REPAIR_INSTRUCTION =
            "COMPILATION REPAIR TASK: Fix only the proposed source preparation failure. No script"
                + " ran, no model change occurred, and no Undo or save was created. Keep the"
                + " original user task and every current plan step, including established evidence"
                + " and remaining scope. Return a corrected exact-source proposal with the same"
                + " pending proposal stepIds for fresh review. Do not claim execution, saving,"
                + " successful model checks or task completion. This is not permission to apply"
                + " code.";
    public static final String CONTINUATION_INSTRUCTION =
            "READ-ONLY CONTINUATION TASK: Your complete answer left declared plan steps pending or"
                + " unverified. Use the remaining cumulative read-tool budget to gather missing"
                + " evidence for the same original task and current plan. Keep every step's id,"
                + " title and type; do not erase remaining work or repeat committed writes. An edit"
                + " remains a fresh review proposal, never execution authorization. If a"
                + " prerequisite or user decision is missing, say blocked or needs_input. Declared"
                + " checks do not establish engineering completeness or compliance.";
}
