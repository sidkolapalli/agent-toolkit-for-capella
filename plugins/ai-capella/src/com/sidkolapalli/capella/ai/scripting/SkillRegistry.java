package com.sidkolapalli.capella.ai.scripting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skill registry for built-in and user-defined skills.
 *
 * <p>Built-in skills live under {@code resources/capella-api/skills/<skill>/}. User skills live
 * under {@code ~/.capella-ai/skills/<skill>/SKILL.md} and are discoverable via {@code
 * api.fs.list("user-skills/")}.
 */
public final class SkillRegistry {

    public enum Trust {
        BUILT_IN,
        USER
    }

    public record SkillInfo(
            String name,
            String description,
            String version,
            Trust trust,
            List<String> requiredInputs,
            String skillMdPath,
            String codePath) {}

    private SkillRegistry() {}

    public static List<SkillInfo> listAll() {
        FileSystemAPI fs = new FileSystemAPI();
        List<SkillInfo> out = new ArrayList<>();

        // Built-in skills
        for (String entry : fs.list("skills/")) {
            if (!entry.endsWith("/")) {
                continue;
            }
            String name = entry.substring(0, entry.length() - 1);
            SkillInfo info = loadBuiltIn(fs, name);
            if (info != null) {
                out.add(info);
            }
        }

        // User skills (folders)
        for (String entry : fs.list("user-skills/")) {
            if (!entry.endsWith("/")) {
                continue;
            }
            String name = entry.substring(0, entry.length() - 1);
            SkillInfo info = loadUser(fs, name);
            if (info != null) {
                out.add(info);
            }
        }

        out.sort(
                (a, b) ->
                        a.name()
                                .toLowerCase(Locale.ROOT)
                                .compareTo(b.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    public static SkillInfo find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (SkillInfo s : listAll()) {
            if (s.name() != null && s.name().trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return s;
            }
        }
        return null;
    }

    public static String loadCode(SkillInfo skill) {
        if (skill == null) {
            return null;
        }
        FileSystemAPI fs = new FileSystemAPI();
        String codePath = skill.codePath();
        if (codePath != null && !codePath.isBlank()) {
            String content = fs.read(codePath);
            if (content != null && !content.contains("File not found")) {
                return content;
            }
        }
        // Fallback: extract code block from SKILL.md
        String md = fs.read(skill.skillMdPath());
        if (md == null) {
            return null;
        }
        String extracted = GroovyExecutor.extractCode(md);
        return extracted.isBlank() ? null : extracted;
    }

    private static SkillInfo loadBuiltIn(FileSystemAPI fs, String name) {
        String mdPath = "skills/" + name + "/SKILL.md";
        String md = fs.read(mdPath);
        if (md == null || md.contains("File not found")) {
            return null;
        }
        Map<String, String> fm = parseFrontmatter(md);
        String desc = firstNonBlank(fm.get("description"), fm.get("summary"));
        String version = fm.getOrDefault("version", "0.0.0");
        List<String> required = parseCsvList(fm.get("required_inputs"));
        String codePath = "skills/" + name + "/skill.groovy";
        return new SkillInfo(
                fm.getOrDefault("name", name),
                desc != null ? desc : "",
                version,
                Trust.BUILT_IN,
                required,
                mdPath,
                codePath);
    }

    private static SkillInfo loadUser(FileSystemAPI fs, String name) {
        String mdPath = "user-skills/" + name + "/SKILL.md";
        String md = fs.read(mdPath);
        if (md == null || md.contains("File not found")) {
            return null;
        }
        Map<String, String> fm = parseFrontmatter(md);
        String desc = firstNonBlank(fm.get("description"), fm.get("summary"));
        String version = fm.getOrDefault("version", "0.0.0");
        List<String> required = parseCsvList(fm.get("required_inputs"));
        return new SkillInfo(
                fm.getOrDefault("name", name),
                desc != null ? desc : "",
                version,
                Trust.USER,
                required,
                mdPath,
                null);
    }

    private static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) {
            return values;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            end = content.indexOf("\r\n---", 3);
        }
        if (end < 0) {
            return values;
        }
        String block = content.substring(3, end);
        for (String line : block.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static List<String> parseCsvList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
