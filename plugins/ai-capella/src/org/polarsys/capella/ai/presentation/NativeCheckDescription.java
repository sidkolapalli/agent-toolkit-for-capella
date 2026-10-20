package org.polarsys.capella.ai.presentation;

import java.util.List;
import java.util.Map;

/** Readable review copy for immutable, data-only native relationship checks. */
public final class NativeCheckDescription {
    private NativeCheckDescription() {}

    public static String describe(Object value) {
        if (!(value instanceof Map<?, ?> check)) return "Native relationship check is unavailable";
        String kind = String.valueOf(check.get("kind"));
        return switch (kind) {
            case "reference_equals" -> selector(check.get("target")) + ": " + plain(check.get("feature"))
                + " points to " + (check.get("expected") == null ? "no element" : selector(check.get("expected")));
            case "contained_by" -> selector(check.get("target")) + " is owned by " + selector(check.get("container"));
            case "reference_sequence_equals" -> selector(check.get("target")) + ": " + plain(check.get("feature"))
                + " contains exactly " + sequence(check.get("expected")) + " in this order";
            case "relation_exists" -> "Exactly one " + plain(check.get("type")) + ": " + predicates(check.get("predicates"))
                + (check.containsKey("owner") ? "; owned by " + selector(check.get("owner")) : "");
            default -> "Native relationship check is unavailable";
        };
    }

    private static String predicates(Object value) {
        if (!(value instanceof Map<?, ?> map)) return "reference details unavailable";
        return map.entrySet().stream().map(entry -> plain(entry.getKey()) + " points to " + selector(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("; "));
    }
    private static String sequence(Object value) {
        if (!(value instanceof List<?> list)) return "an unavailable sequence";
        if (list.isEmpty()) return "zero elements";
        return list.stream().map(NativeCheckDescription::selector).collect(java.util.stream.Collectors.joining(" → "));
    }
    private static String selector(Object value) {
        if (!(value instanceof Map<?, ?> selector)) return "an unavailable element";
        if (selector.containsKey("id")) return "element " + plain(selector.get("id"));
        return plain(selector.get("type")) + " “" + plain(selector.get("name")) + "”"
            + (selector.containsKey("layer") ? " in " + plain(selector.get("layer")) : "")
            + (selector.containsKey("container") ? " within " + selector(selector.get("container")) : "");
    }
    private static String plain(Object value) {
        return String.valueOf(value).replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").replace("\\", "\\\\")
            .replace("`", "\\`").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[")
            .replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One readable line per declared check, shown to the reviewer before anything runs. */
    public static String describeAll(List<org.polarsys.capella.ai.agent.ArcadiaAgent.Postcondition> conditions) {
        return conditions.stream().map(condition -> {
            Map<String, Object> args = condition.arguments();
            if ("native_structure".equals(condition.kind())) {
                return "- " + describe(args.get("check"));
            }
            String target = args.containsKey("id") ? "Element " + args.get("id")
                : args.get("type") + " “" + args.get("name") + "”" + (args.containsKey("layer") ? " in " + args.get("layer") : "");
            return "- " + target + switch (condition.kind()) {
                case "element_absent" -> " is absent";
                case "attribute_equals" -> ": " + args.get("attribute") + " equals " + args.get("expected");
                default -> " exists";
            };
        }).collect(java.util.stream.Collectors.joining("\n"));
    }
}
