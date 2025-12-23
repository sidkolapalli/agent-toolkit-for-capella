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

    /**
     * Module -> class name mapping.
     *
     * <p>Use class names (not class literals) so ApiCatalog can still load even
     * when optional dependencies (e.g., Groovy) are not present in the runtime
     * classpath.
     */
    private static final Map<String, String> MODULES = Map.of(
        "api",
        "org.polarsys.capella.ai.scripting.CapellaAPI",
        "api.diagram",
        "org.polarsys.capella.ai.scripting.DiagramAPI",
        "api.fs",
        "org.polarsys.capella.ai.scripting.FileSystemAPI"
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
        Class<?> cls = loadModuleClass(normalized);
        List<ApiMethod> methods = collectMethods(cls);
        return methods
            .stream()
            .map(m -> m.name)
            .collect(java.util.stream.Collectors.toSet());
    }

    public static String validateCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<String> errors = new ArrayList<>();

        String blocked = detectBlockedSandboxOperations(code);
        if (blocked != null) {
            errors.add(blocked);
        }

        String apiError = validateUnknownMethods(
            code,
            "api",
            "api",
            methodNames("api")
        );
        if (apiError != null) {
            errors.add(apiError);
        }

        String diagramError = validateUnknownMethods(
            code,
            "api.diagram",
            "api\\s*\\.\\s*diagram",
            methodNames("api.diagram")
        );
        if (diagramError != null) {
            errors.add(diagramError);
        }

        String fsError = validateUnknownMethods(
            code,
            "api.fs",
            "api\\s*\\.\\s*fs",
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

    private static String detectBlockedSandboxOperations(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String lower = code.toLowerCase(Locale.ROOT);

        // ClassLoader usage is blocked by the Groovy sandbox.
        if (lower.contains(".classloader")) {
            return "Sandbox blocks ClassLoader usage. Avoid `api.class.classLoader.loadClass(...)`. " +
                "Use pre-bound factories (CapellacoreFactory, OaFactory, CtxFactory, LaFactory, PaFactory, FaFactory, CsFactory, InformationFactory) " +
                "or use OSGi bundle loading (FrameworkUtil.getBundle(api.class).bundleContext.getBundle(...).loadClass(...)) when needed.";
        }

        // Java reflection is blocked / unreliable (Method/Field/Constructor receivers are blocked).
        if (lower.contains("java.lang.reflect.") ||
            lower.contains(".getmethod(") ||
            lower.contains(".getdeclaredmethod(") ||
            lower.contains(".getconstructor(") ||
            lower.contains(".getdeclaredconstructor(") ||
            lower.contains(".invoke(")) {
            return "Sandbox blocks Java reflection (Method/Constructor/Field). " +
                "Avoid getMethod()/invoke()/constructors via reflection; use CapellaAPI methods or pre-bound factories instead.";
        }

        return null;
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
        // Guardrail: diagrams are not semantic elements, so they should be located via api.diagram.*,
        // not via the generic semantic search methods.
        Pattern pattern = Pattern.compile(
            "\\bapi\\s*\\.\\s*find(All)?\\s*\\(\\s*(['\"])\\s*(DRepresentationDescriptor|DRepresentation)\\s*\\2"
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
            modules.put(normalized, collectMethods(loadModuleClass(normalized)));
        } else {
            for (Map.Entry<String, String> entry : MODULES.entrySet()) {
                modules.put(
                    entry.getKey(),
                    collectMethods(loadModuleClass(entry.getKey()))
                );
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

    private static Class<?> loadModuleClass(String moduleKey) {
        String className = MODULES.get(moduleKey);
        if (className == null || className.isBlank()) {
            return null;
        }
        try {
            return Class.forName(
                className,
                false,
                ApiCatalog.class.getClassLoader()
            );
        } catch (Throwable t) {
            return null;
        }
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
        Method[] all;
        try {
            all = cls.getMethods();
        } catch (Throwable t) {
            // Optional dependency missing (e.g., signature types not resolvable).
            return List.of();
        }
        for (Method method : all) {
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
            try {
                methods.add(
                    new ApiMethod(
                        method.getName(),
                        paramTypes(method),
                        simpleName(method.getReturnType())
                    )
                );
            } catch (Throwable t) {
                // Skip methods whose signatures are not resolvable in this runtime.
            }
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
