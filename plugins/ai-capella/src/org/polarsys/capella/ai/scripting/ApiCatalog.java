package org.polarsys.capella.ai.scripting;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured API catalog for LLM discovery and validation.
 * Provides a JSON view of available methods for api modules.
 */
public final class ApiCatalog {

    private static final Set<String> OBJECT_METHODS = Set.of(
        "getClass",
        "hashCode",
        "wait",
        "notify",
        "notifyAll",
        "equals",
        "toString"
    );

    private static final Map<String, Class<?>> MODULES = Map.of(
        "api",
        CapellaAPI.class,
        "api.diagram",
        DiagramAPI.class,
        "api.fs",
        FileSystemAPI.class
    );

    private static final Map<String, List<String>> MODULE_NOTES =
        new LinkedHashMap<>();

    static {
        MODULE_NOTES.put(
            "api",
            List.of(
                "Use api.diagram.find(...) to locate diagrams by name; do NOT use api.find(\"DRepresentationDescriptor\", ...).",
                "Use api.help(\"diagram\") to inspect diagram methods before calling them."
            )
        );
        MODULE_NOTES.put(
            "api.diagram",
            List.of(
                "Valid methods include: create, createSafe, add, addSafe, addAll, addByUuids, refresh, layout, list, listAvailable, listAvailableFormatted.",
                "Do NOT use addElement() or show() (they do not exist).",
                "If add() fails, use addByUuids() with element UUIDs."
            )
        );
    }

    private ApiCatalog() {}

    public static String help() {
        return buildJson(null);
    }

    public static String help(String module) {
        return buildJson(module);
    }

    public static Set<String> methodNames(String module) {
        String normalized = normalizeModule(module);
        if (normalized == null) {
            return Set.of();
        }
        List<ApiMethod> methods = collectMethods(MODULES.get(normalized));
        return methods.stream().map(m -> m.name).collect(java.util.stream.Collectors.toSet());
    }

    public static String validateCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<String> errors = new ArrayList<>();

        String apiError = validateUnknownMethods(
            code,
            "api",
            "\\bapi\\s*\\.\\s*(\\w+)\\s*\\(",
            methodNames("api")
        );
        if (apiError != null) {
            errors.add(apiError);
        }

        String diagramError = validateUnknownMethods(
            code,
            "api.diagram",
            "\\bapi\\s*\\.\\s*diagram\\s*\\.\\s*(\\w+)\\s*\\(",
            methodNames("api.diagram")
        );
        if (diagramError != null) {
            errors.add(diagramError);
        }

        String fsError = validateUnknownMethods(
            code,
            "api.fs",
            "\\bapi\\s*\\.\\s*fs\\s*\\.\\s*(\\w+)\\s*\\(",
            methodNames("api.fs")
        );
        if (fsError != null) {
            errors.add(fsError);
        }

        String diagramFindMisuse = detectDiagramFindMisuse(code);
        if (diagramFindMisuse != null) {
            errors.add(diagramFindMisuse);
        }

        return errors.isEmpty() ? null : String.join("\n", errors);
    }

    private static String validateUnknownMethods(
        String code,
        String module,
        String modulePattern,
        Set<String> allowed
    ) {
        List<String> unknown = findUnknownMethods(code, modulePattern, allowed);
        if (unknown.isEmpty()) {
            return null;
        }
        return buildUnknownMethodMessage(module, unknown, allowed);
    }

    private static String detectDiagramFindMisuse(String code) {
        Pattern pattern = Pattern.compile(
            "\\bapi\\s*\\.\\s*find(All)?\\s*\\(\\s*\"(DRepresentationDescriptor|DRepresentation)\""
        );
        Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return "Diagram lookup should use api.diagram.find(\"<diagram name>\") or api.diagram.findByUid(\"<uid>\"). " +
                "Do NOT use api.find(\"DRepresentationDescriptor\", ...).";
        }
        return null;
    }

    private static List<String> findUnknownMethods(
        String code,
        String modulePattern,
        Set<String> allowed
    ) {
        Pattern pattern = Pattern.compile(
            "\\b" + modulePattern + "\\s*\\.\\s*(\\w+)\\s*\\("
        );
        Matcher matcher = pattern.matcher(code);
        List<String> unknown = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    private static String buildUnknownMethodMessage(
        String module,
        List<String> unknown,
        Set<String> allowed
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Unknown methods for ").append(module).append(": ");
        sb.append(String.join(", ", unknown)).append("\n");
        sb.append("Valid methods: ").append(String.join(", ", allowed)).append("\n");
        sb.append("Use api.help(\"").append(module.replace("api.", "")).append("\") before calling methods.\n");
        return sb.toString();
    }

    private static String buildJson(String module) {
        String normalized = normalizeModule(module);
        Map<String, List<ApiMethod>> modules = new LinkedHashMap<>();

        if (normalized != null) {
            modules.put(normalized, collectMethods(MODULES.get(normalized)));
        } else {
            for (Map.Entry<String, Class<?>> entry : MODULES.entrySet()) {
                modules.put(entry.getKey(), collectMethods(entry.getValue()));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"modules\":[");
        boolean firstModule = true;
        for (Map.Entry<String, List<ApiMethod>> entry : modules.entrySet()) {
            if (!firstModule) {
                sb.append(",");
            }
            firstModule = false;
            sb.append("{");
            sb.append("\"name\":\"").append(escape(entry.getKey())).append("\",");
            sb.append("\"methods\":[");
            boolean firstMethod = true;
            for (ApiMethod method : entry.getValue()) {
                if (!firstMethod) {
                    sb.append(",");
                }
                firstMethod = false;
                sb.append("{");
                sb.append("\"name\":\"").append(escape(method.name)).append("\",");
                sb.append("\"params\":[");
                for (int i = 0; i < method.params.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(method.params.get(i))).append("\"");
                }
                sb.append("],");
                sb.append("\"returns\":\"").append(escape(method.returns)).append("\"");
                sb.append("}");
            }
            sb.append("]");

            List<String> notes = MODULE_NOTES.get(entry.getKey());
            if (notes != null && !notes.isEmpty()) {
                sb.append(",\"notes\":[");
                for (int i = 0; i < notes.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escape(notes.get(i))).append("\"");
                }
                sb.append("]");
            }

            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String normalizeModule(String module) {
        if (module == null || module.isBlank()) {
            return null;
        }
        String trimmed = module.trim();
        if (!trimmed.startsWith("api")) {
            trimmed = "api." + trimmed;
        }
        if (MODULES.containsKey(trimmed)) {
            return trimmed;
        }
        return null;
    }

    private static List<ApiMethod> collectMethods(Class<?> cls) {
        if (cls == null) {
            return List.of();
        }
        List<ApiMethod> methods = new ArrayList<>();
        for (Method method : cls.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (OBJECT_METHODS.contains(method.getName())) {
                continue;
            }
            methods.add(
                new ApiMethod(
                    method.getName(),
                    paramTypes(method),
                    simpleName(method.getReturnType())
                )
            );
        }
        methods.sort(
            Comparator.comparing((ApiMethod m) -> m.name.toLowerCase(Locale.ROOT))
                .thenComparing(m -> m.params.size())
        );
        return methods;
    }

    private static List<String> paramTypes(Method method) {
        Class<?>[] types = method.getParameterTypes();
        List<String> params = new ArrayList<>(types.length);
        for (Class<?> type : types) {
            params.add(simpleName(type));
        }
        return params;
    }

    private static String simpleName(Class<?> type) {
        if (type == null) {
            return "void";
        }
        if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
        }
        return type.getSimpleName();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private record ApiMethod(String name, List<String> params, String returns) {}
}
