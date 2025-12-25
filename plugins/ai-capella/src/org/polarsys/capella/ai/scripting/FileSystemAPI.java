package org.polarsys.capella.ai.scripting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.polarsys.capella.ai.model.ActionResult;

/**
 * Filesystem API for on-demand documentation discovery.
 * Implements Anthropic's filesystem-based discovery pattern for code execution.
 *
 * <p>This API allows the LLM to explore API documentation stored in the plugin's
 * resources directory, enabling on-demand learning rather than loading all
 * documentation upfront.
 *
 * <p>Reference: <a href="https://www.anthropic.com/engineering/code-execution-with-mcp">
 * Anthropic's Code Execution with MCP</a>
 *
 * <p>Example usage:
 * <pre>{@code
 * // List available documentation
 * api.fs.list("")                    // ["api/", "metamodel/", "skills/", "SKILL.md"]
 * api.fs.list("api/")                // ["find.md", "create.md", "link.md", ...]
 * api.fs.list("skills/")             // ["state-machine/", "interface/", ...]
 *
 * // Read specific documentation
 * api.fs.read("SKILL.md")            // Root documentation
 * api.fs.read("api/create.md")       // Create method documentation
 * api.fs.read("skills/state-machine/SKILL.md")
 *
 * // Search for relevant documentation
 * api.fs.search("component")         // Find all docs mentioning "component"
 * api.fs.search("FunctionalExchange") // Find docs about functional exchanges
 * }</pre>
 */
public final class FileSystemAPI {

    private static final Logger LOG = Logger.getLogger(
        FileSystemAPI.class.getName()
    );

    /** Root directory for documentation resources */
    private static final String DOCS_ROOT = "resources/capella-api";

    /** Maximum number of search results to return */
    private static final int MAX_SEARCH_RESULTS = 10;

    /** Maximum file size to read (100KB) */
    private static final int MAX_FILE_SIZE = 100_000;

    /** User skills directory override (default: ~/.capella-ai/skills/) */
    private static final String USER_SKILLS_DIR_PROP = "capella.ai.skills.dir";

    private static final String USER_SKILLS_PREFIX = "user-skills/";

    /**
     * Safe workspace root for agent persistence.
     *
     * <p>Override for tests or custom installs via:
     * <ul>
     *   <li>capella.ai.workspace.dir</li>
     *   <li>capella.ai.workspace.maxFileBytes</li>
     *   <li>capella.ai.workspace.maxTotalBytes</li>
     * </ul>
     */
    private static final String WORKSPACE_DIR_PROP = "capella.ai.workspace.dir";
    private static final String WORKSPACE_MAX_FILE_BYTES_PROP =
        "capella.ai.workspace.maxFileBytes";
    private static final String WORKSPACE_MAX_TOTAL_BYTES_PROP =
        "capella.ai.workspace.maxTotalBytes";

    private static final String WORKSPACE_PREFIX = "workspace/";
    private static final String WORKSPACE_AUDIT_LOG = ".audit.log";
    private static final String WORKSPACE_QUOTA_FILE = ".quota.json";

    private static final List<String> WORKSPACE_ALLOWED_EXT = List.of(
        ".json",
        ".md",
        ".txt",
        ".csv"
    );

    /** Search detail levels */
    public enum DetailLevel {
        /** Return only file names */
        NAME_ONLY,
        /** Return names with first-line description */
        NAME_AND_DESCRIPTION,
        /** Return full file content */
        FULL,
    }

    /** Parsed metadata for a skill entry. */
    public static final class SkillMetadata {
        public final String name;
        public final String summary;
        public final List<String> keywords;
        public final List<String> layers;
        public final String whenToUse;
        public final String difficulty;
        public final String path;

        private SkillMetadata(
            String name,
            String summary,
            List<String> keywords,
            List<String> layers,
            String whenToUse,
            String difficulty,
            String path
        ) {
            this.name = name;
            this.summary = summary;
            this.keywords = keywords != null ? List.copyOf(keywords) : List.of();
            this.layers = layers != null ? List.copyOf(layers) : List.of();
            this.whenToUse = whenToUse;
            this.difficulty = difficulty;
            this.path = path;
        }
    }

    // Static directory index for OSGi bundle compatibility
    private static final java.util.Map<String, List<String>> DIRECTORY_INDEX =
        new java.util.HashMap<>();

    static {
        DIRECTORY_INDEX.put(
            "",
            List.of("SKILL.md", "api/", "metamodel/", "skills/", "workspace/")
        );
        DIRECTORY_INDEX.put(
            "api/",
            List.of(
                "SKILL.md",
                "aql.md",
                "create.md",
                "describe.md",
                "diagram.md",
                "diagram-layout.md",
                "find.md",
                "link.md",
                "modify.md",
                "ops.md",
                "style.md",
                "validate.md"
            )
        );
        DIRECTORY_INDEX.put(
            "metamodel/",
            List.of("SKILL.md", "la/", "oa/", "pa/", "sa/")
        );
        DIRECTORY_INDEX.put("metamodel/la/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("metamodel/oa/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("metamodel/pa/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("metamodel/sa/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put(
            "skills/",
            List.of(
                "SKILL.md",
                "arcadia-workflow/",
                "diagram/",
                "interface/",
                "requirements/",
                "state-machine/",
                "runtime-demo/"
            )
        );
        DIRECTORY_INDEX.put("skills/arcadia-workflow/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("skills/diagram/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("skills/interface/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("skills/requirements/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put("skills/state-machine/", List.of("SKILL.md"));
        DIRECTORY_INDEX.put(
            "skills/runtime-demo/",
            List.of("SKILL.md", "skill.groovy")
        );
    }

    /**
     * List contents of a directory in the documentation tree.
     *
     * @param path relative path within the documentation root (e.g., "api/", "skills/")
     * @return list of entries (files and directories, directories end with "/")
     */
    public List<String> list(String path) {
        String normalizedPath = normalizePath(path);

        // Ensure trailing slash for directories
        if (!normalizedPath.isEmpty() && !normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath + "/";
        }

        if (normalizedPath.equals(WORKSPACE_PREFIX)) {
            return listWorkspaceEntries("");
        }
        if (normalizedPath.startsWith(WORKSPACE_PREFIX)) {
            return listWorkspaceEntries(
                normalizedPath.substring(WORKSPACE_PREFIX.length())
            );
        }

        if (normalizedPath.equals(USER_SKILLS_PREFIX)) {
            return listUserSkillsPath("");
        }
        if (normalizedPath.startsWith(USER_SKILLS_PREFIX)) {
            return listUserSkillsPath(
                normalizedPath.substring(USER_SKILLS_PREFIX.length())
            );
        }

        List<String> entries = listFromResources(normalizedPath);
        if (!entries.isEmpty()) {
            return mergeWithUserSkills(normalizedPath, entries);
        }

        // Use static index for OSGi bundle compatibility
        entries = DIRECTORY_INDEX.get(normalizedPath);
        if (entries != null) {
            return mergeWithUserSkills(normalizedPath, new ArrayList<>(entries));
        }

        if (normalizedPath.isEmpty()) {
            return mergeWithUserSkills(normalizedPath, new ArrayList<>());
        }

        return new ArrayList<>();
    }

    /**
     * Read the content of a documentation file.
     *
     * @param path relative path to the file (e.g., "SKILL.md", "api/create.md")
     * @return the file content as a string, or error message if not found
     */
    public String read(String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath.startsWith(WORKSPACE_PREFIX)) {
            return readWorkspaceFile(
                normalizedPath.substring(WORKSPACE_PREFIX.length())
            );
        }
        if (normalizedPath.startsWith(USER_SKILLS_PREFIX)) {
            return readUserSkillFile(
                normalizedPath.substring(USER_SKILLS_PREFIX.length())
            );
        }
        String resourcePath = DOCS_ROOT + "/" + normalizedPath;

        try (
            InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(resourcePath)
        ) {
            if (is == null) {
                return (
                    "File not found: " +
                    path +
                    "\n\nUse api.fs.list() to see available files."
                );
            }

            // Read with size limit
            byte[] bytes = is.readNBytes(MAX_FILE_SIZE);
            String content = new String(bytes, StandardCharsets.UTF_8);

            if (bytes.length >= MAX_FILE_SIZE) {
                content +=
                    "\n\n[Content truncated - file exceeds " +
                    MAX_FILE_SIZE +
                    " bytes]";
            }

            return content;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading file: " + path, e);
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Search for documentation files containing a keyword.
     *
     * @param keyword the search term (case-insensitive)
     * @return list of matching file paths with context snippets
     */
    public List<String> search(String keyword) {
        List<String> results = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return results;
        }

        String searchTerm = keyword.toLowerCase();
        searchRecursive("", searchTerm, results);

        // Limit results
        if (results.size() > MAX_SEARCH_RESULTS) {
            results = new ArrayList<>(results.subList(0, MAX_SEARCH_RESULTS));
            results.add(
                "... and more. Use api.fs.list() to browse all documentation."
            );
        }

        return results;
    }

    /**
     * Check if a path exists.
     *
     * @param path relative path to check
     * @return true if the path exists as a file or directory
     */
    public boolean exists(String path) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath.startsWith(WORKSPACE_PREFIX)) {
            Path p = resolveWorkspacePath(
                normalizedPath.substring(WORKSPACE_PREFIX.length())
            );
            return p != null && Files.exists(p);
        }
        if (normalizedPath.startsWith(USER_SKILLS_PREFIX)) {
            Path p = resolveUserSkillsPath(
                normalizedPath.substring(USER_SKILLS_PREFIX.length())
            );
            return p != null && Files.exists(p);
        }
        String resourcePath = DOCS_ROOT + "/" + normalizedPath;
        return getClass().getClassLoader().getResource(resourcePath) != null;
    }

    /**
     * Write content into the safe workspace directory.
     *
     * <p>All writes are restricted to {@code ~/.capella-ai/workspace/} (or the
     * override path from {@code capella.ai.workspace.dir}). Only small text
     * files are permitted, and path traversal / hidden files are blocked.
     *
     * @param path relative path under the workspace (e.g., "state/run-1.json" or "workspace/state/run-1.json")
     * @param contents file contents (UTF-8)
     * @return ActionResult describing success or rejection reason
     */
    public ActionResult write(String path, String contents) {
        if (path == null || path.isBlank()) {
            return ActionResult.fail(
                ActionResult.ErrorCode.INVALID_INPUT,
                "Path is required",
                "Use a relative path like 'state/run-1.json' under workspace/"
            );
        }

        String normalized = normalizePath(path);
        if (normalized.startsWith(WORKSPACE_PREFIX)) {
            normalized = normalized.substring(WORKSPACE_PREFIX.length());
        }

        Path target = resolveWorkspacePath(normalized);
        if (target == null) {
            return ActionResult.fail(
                ActionResult.ErrorCode.INVALID_INPUT,
                "Invalid workspace path",
                "Avoid '..', absolute paths, and hidden files; write only under workspace/"
            );
        }

        String fileName = target.getFileName() != null
            ? target.getFileName().toString().toLowerCase()
            : "";
        if (!isAllowedWorkspaceExtension(fileName)) {
            return ActionResult.fail(
                ActionResult.ErrorCode.INVALID_INPUT,
                "Blocked file extension for workspace write: " + fileName,
                "Allowed extensions: " + String.join(", ", WORKSPACE_ALLOWED_EXT)
            );
        }

        byte[] bytes = (contents != null ? contents : "").getBytes(
            StandardCharsets.UTF_8
        );
        long maxFileBytes = maxWorkspaceFileBytes();
        if (bytes.length > maxFileBytes) {
            return ActionResult.fail(
                ActionResult.ErrorCode.INVALID_INPUT,
                "File too large (" +
                bytes.length +
                " bytes). Max is " +
                maxFileBytes +
                " bytes.",
                "Write a smaller file (e.g., summarize or split into multiple files)."
            );
        }

        try {
            Files.createDirectories(workspaceRoot());
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            long existingSize = Files.exists(target) ? Files.size(target) : 0L;
            long totalBytes = workspaceTotalBytes();
            long maxTotalBytes = maxWorkspaceTotalBytes();
            long projected = totalBytes - existingSize + bytes.length;
            if (projected > maxTotalBytes) {
                return ActionResult.fail(
                    ActionResult.ErrorCode.INVALID_INPUT,
                    "Workspace quota exceeded (" +
                    projected +
                    " bytes). Max is " +
                    maxTotalBytes +
                    " bytes.",
                    "Delete unused workspace files or reduce output size."
                );
            }

            Files.write(target, bytes);
            writeWorkspaceAudit("WRITE", normalized, bytes.length);
            writeWorkspaceQuota(projected);
            return ActionResult.ok(
                "Wrote workspace/" + normalized + " (" + bytes.length + " bytes)"
            );
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Workspace write failed: " + normalized, e);
            return ActionResult.fail(
                ActionResult.ErrorCode.INVALID_INPUT,
                "Workspace write failed: " + e.getMessage()
            );
        }
    }

    /**
     * Search with configurable detail level.
     *
     * <p>Detail levels:
     * <ul>
     *   <li>NAME_ONLY - just file paths</li>
     *   <li>NAME_AND_DESCRIPTION - paths with first line of content</li>
     *   <li>FULL - complete file contents (use sparingly)</li>
     * </ul>
     *
     * @param keyword the search term (case-insensitive)
     * @param detail the level of detail to return
     * @return list of results at the specified detail level
     */
    public List<String> search(String keyword, DetailLevel detail) {
        List<String> results = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return results;
        }

        String searchTerm = keyword.toLowerCase();
        searchRecursiveWithDetail("", searchTerm, results, detail);

        // Limit results
        if (results.size() > MAX_SEARCH_RESULTS) {
            results = new ArrayList<>(results.subList(0, MAX_SEARCH_RESULTS));
            results.add(
                "... and more. Refine your search or use api.fs.list() to browse."
            );
        }

        return results;
    }

    /**
     * Search with string detail level for easier Groovy usage.
     *
     * @param keyword the search term
     * @param detail "name", "description", or "full"
     * @return list of results
     */
    public List<String> search(String keyword, String detail) {
        DetailLevel level = switch (detail.toLowerCase()) {
            case "name", "name_only" -> DetailLevel.NAME_ONLY;
            case "full", "complete" -> DetailLevel.FULL;
            default -> DetailLevel.NAME_AND_DESCRIPTION;
        };
        return search(keyword, level);
    }

    // ========== Skills Persistence ==========

    /**
     * Save a new skill for future reuse.
     *
     * <p>Skills are saved as folders under {@code ~/.capella-ai/skills/<skill>/SKILL.md}
     * (Anthropic skill format) and can be loaded in future sessions.
     *
     * @param name the skill name (e.g., "create-state-machine")
     * @param content the skill content (Groovy code)
     * @return true if saved successfully
     */
    public boolean saveSkill(String name, String content) {
        if (name == null || name.isBlank() || content == null) {
            return false;
        }

        // Sanitize name
        String safeName = name
            .replaceAll("[^a-zA-Z0-9_-]", "-")
            .replaceAll("-+", "-")
            .toLowerCase();

        try {
            Path root = userSkillsRoot();
            Files.createDirectories(root);

            Path skillDir = root.resolve(safeName).normalize();
            Files.createDirectories(skillDir);

            Path skillMd = skillDir.resolve("SKILL.md");
            Files.writeString(
                skillMd,
                buildUserSkillSkillMd(safeName, content),
                StandardCharsets.UTF_8
            );

            LOG.info(
                "Saved skill: " +
                    safeName +
                    " to " +
                    skillMd.toAbsolutePath()
            );
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save skill: " + name, e);
            return false;
        }
    }

    /**
     * Load a user-saved skill.
     *
     * @param name the skill name
     * @return the skill content, or null if not found
     */
    public String loadSkill(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String safeName = name
            .replaceAll("[^a-zA-Z0-9_-]", "-")
            .replaceAll("-+", "-")
            .toLowerCase();

        // New format: ~/.capella-ai/skills/<skill>/SKILL.md
        Path md = userSkillsRoot().resolve(safeName).resolve("SKILL.md");
        if (Files.exists(md)) {
            try {
                String content = Files.readString(md, StandardCharsets.UTF_8);
                String code = GroovyExecutor.extractCode(content);
                return code.isBlank() ? content : code;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to load skill: " + name, e);
                return null;
            }
        }

        // Legacy fallback: ~/.capella-ai/skills/<skill>.groovy
        Path legacy = userSkillsRoot().resolve(safeName + ".groovy");
        if (Files.exists(legacy)) {
            try {
                String code = Files.readString(legacy, StandardCharsets.UTF_8);
                // Best-effort migration (non-destructive)
                tryMigrateLegacySkill(safeName, code);
                return code;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to load legacy skill: " + name, e);
            }
        }

        return null;
    }

    /**
     * List all user-saved skills.
     *
     * @return list of skill names
     */
    public List<String> listSkills() {
        List<String> skills = new ArrayList<>();
        Path root = userSkillsRoot();
        if (Files.exists(root) && Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path md = dir.resolve("SKILL.md");
                        if (Files.exists(md)) {
                            String name = dir.getFileName().toString();
                            if (!skills.contains(name)) {
                                skills.add(name);
                            }
                        }
                    });
            } catch (IOException e) {
                // ignore
            }

            // Best-effort migration: legacy .groovy -> folder/SKILL.md (non-destructive)
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(p ->
                        Files.isRegularFile(p) &&
                        p.getFileName().toString().endsWith(".groovy")
                    )
                    .forEach(p -> {
                        String file = p.getFileName().toString();
                        String base = file.substring(0, file.length() - 7);
                        Path md = root.resolve(base).resolve("SKILL.md");
                        if (Files.exists(md)) {
                            return;
                        }
                        try {
                            String code = Files.readString(p, StandardCharsets.UTF_8);
                            if (tryMigrateLegacySkill(base, code)) {
                                if (!skills.contains(base)) {
                                    skills.add(base);
                                }
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    });
            } catch (IOException e) {
                // ignore
            }
        }

        Collections.sort(skills);
        return skills;
    }

    /**
     * Delete a user-saved skill.
     *
     * @param name the skill name
     * @return true if deleted
     */
    public boolean deleteSkill(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        String safeName = name
            .replaceAll("[^a-zA-Z0-9_-]", "-")
            .replaceAll("-+", "-")
            .toLowerCase();

        boolean deletedAny = false;

        Path root = userSkillsRoot();
        Path skillDir = root.resolve(safeName);
        if (Files.exists(skillDir) && Files.isDirectory(skillDir)) {
            try (Stream<Path> stream = Files.walk(skillDir)) {
                stream
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
                deletedAny = true;
            } catch (IOException e) {
                // ignore
            }
        }

        Path legacyGroovy = root.resolve(safeName + ".groovy");
        Path legacyMd = root.resolve(safeName + ".md");
        try {
            deletedAny |= Files.deleteIfExists(legacyGroovy);
            deletedAny |= Files.deleteIfExists(legacyMd);
        } catch (IOException e) {
            // ignore
        }

        return deletedAny;
    }

    /**
     * List metadata for all built-in skills (metadata only).
     *
     * @return list of skill metadata entries
     */
    public List<SkillMetadata> listSkillMetadata() {
        List<SkillMetadata> results = new ArrayList<>();
        results.addAll(listSkillMetadataFromDir("skills/"));
        results.addAll(listUserSkillMetadata());
        return results;
    }

    // ========== Private Helpers ==========

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static Path workspaceRoot() {
        String override = System.getProperty(WORKSPACE_DIR_PROP);
        String base = (override != null && !override.isBlank())
            ? override
            : System.getProperty("user.home") +
            File.separator +
            ".capella-ai" +
            File.separator +
            "workspace";
        return Paths.get(base).toAbsolutePath().normalize();
    }

    private static Path userSkillsRoot() {
        String override = System.getProperty(USER_SKILLS_DIR_PROP);
        String base = (override != null && !override.isBlank())
            ? override
            : System.getProperty("user.home") +
            File.separator +
            ".capella-ai" +
            File.separator +
            "skills";
        return Paths.get(base).toAbsolutePath().normalize();
    }

    private static long maxWorkspaceFileBytes() {
        return Long.getLong(WORKSPACE_MAX_FILE_BYTES_PROP, 256L * 1024L);
    }

    private static long maxWorkspaceTotalBytes() {
        return Long.getLong(WORKSPACE_MAX_TOTAL_BYTES_PROP, 10L * 1024L * 1024L);
    }

    private static boolean isAllowedWorkspaceExtension(String fileNameLower) {
        if (fileNameLower == null || fileNameLower.isBlank()) {
            return false;
        }
        for (String ext : WORKSPACE_ALLOWED_EXT) {
            if (fileNameLower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveWorkspacePath(String relative) {
        if (relative == null) {
            return null;
        }
        String trimmed = relative.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Reject absolute paths early.
        Path rel;
        try {
            rel = Paths.get(trimmed);
        } catch (Exception e) {
            return null;
        }
        if (rel.isAbsolute()) {
            return null;
        }

        // Reject hidden segments.
        for (Path part : rel) {
            String s = part.toString();
            if (s.startsWith(".")) {
                return null;
            }
        }

        Path root = workspaceRoot();
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    private static Path resolveUserSkillsPath(String relative) {
        if (relative == null) {
            return null;
        }
        String trimmed = relative.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Path rel;
        try {
            rel = Paths.get(trimmed);
        } catch (Exception e) {
            return null;
        }
        if (rel.isAbsolute()) {
            return null;
        }

        for (Path part : rel) {
            String s = part.toString();
            if (s.startsWith(".")) {
                return null;
            }
        }

        Path root = userSkillsRoot();
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    private String readUserSkillFile(String relative) {
        Path p = resolveUserSkillsPath(relative);
        if (p == null) {
            return (
                "Invalid user skill path: " +
                relative +
                "\n\nUse api.fs.list('user-skills/') to browse skills."
            );
        }
        if (!Files.exists(p) || Files.isDirectory(p)) {
            return (
                "User skill file not found: user-skills/" +
                relative +
                "\n\nUse api.fs.list('user-skills/') to browse skills."
            );
        }
        try {
            long size = Files.size(p);
            if (size > MAX_FILE_SIZE) {
                return (
                    "User skill file too large to read safely: user-skills/" +
                    relative +
                    " (" +
                    size +
                    " bytes, max " +
                    MAX_FILE_SIZE +
                    ")\n"
                );
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading user skill file: " + relative, e);
            return "Error reading user skill file: " + e.getMessage();
        }
    }

    private static List<String> listUserSkillsPath(String relativeDir) {
        boolean isRoot = relativeDir == null || relativeDir.isBlank();
        Path dir = (relativeDir == null || relativeDir.isBlank())
            ? userSkillsRoot()
            : resolveUserSkillsPath(relativeDir);
        if (dir == null) {
            return List.of();
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName() != null ? p.getFileName().toString() : "";
                    if (name.isBlank() || name.startsWith(".")) return false;
                    if (isRoot && !Files.isDirectory(p)) return false;
                    return true;
                })
                .sorted()
                .map(p -> {
                    String name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private String readWorkspaceFile(String relative) {
        Path p = resolveWorkspacePath(relative);
        if (p == null) {
            return (
                "Invalid workspace path: " +
                relative +
                "\n\nUse api.fs.read('workspace/<path>') with a safe relative path."
            );
        }
        if (!Files.exists(p) || Files.isDirectory(p)) {
            return (
                "Workspace file not found: workspace/" +
                relative +
                "\n\nUse api.fs.list('workspace/') to browse saved files."
            );
        }

        try {
            long size = Files.size(p);
            long max = maxWorkspaceFileBytes();
            if (size > max) {
                return (
                    "Workspace file too large to read safely: workspace/" +
                    relative +
                    " (" +
                    size +
                    " bytes, max " +
                    max +
                    ")\n"
                );
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading workspace file: " + relative, e);
            return "Error reading workspace file: " + e.getMessage();
        }
    }

    private static List<String> listWorkspaceEntries(String relativeDir) {
        Path dir = (relativeDir == null || relativeDir.isBlank())
            ? workspaceRoot()
            : resolveWorkspacePath(relativeDir);
        if (dir == null) {
            return List.of();
        }
        if (!Files.exists(dir)) {
            return List.of();
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(p -> {
                    String name = p.getFileName() != null ? p.getFileName().toString() : "";
                    if (name.isBlank()) return false;
                    if (name.startsWith(".")) return false;
                    return true;
                })
                .sorted()
                .map(p -> {
                    String name = p.getFileName().toString();
                    return Files.isDirectory(p) ? name + "/" : name;
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static long workspaceTotalBytes() throws IOException {
        Path root = workspaceRoot();
        if (!Files.exists(root)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName() != null ? p.getFileName().toString() : "";
                    if (name.equals(WORKSPACE_AUDIT_LOG) || name.equals(WORKSPACE_QUOTA_FILE)) {
                        return false;
                    }
                    return true;
                })
                .mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                })
                .sum();
        }
    }

    private static void writeWorkspaceAudit(String op, String relative, long bytes) {
        try {
            Path logFile = workspaceRoot().resolve(WORKSPACE_AUDIT_LOG);
            String line = java.time.Instant.now() +
                " " +
                op +
                " workspace/" +
                relative +
                " bytes=" +
                bytes +
                "\n";
            Files.writeString(
                logFile,
                line,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            // Best effort only.
        }
    }

    private static void writeWorkspaceQuota(long totalBytes) {
        try {
            Path quotaFile = workspaceRoot().resolve(WORKSPACE_QUOTA_FILE);
            String json = "{\n" +
                "  \"totalBytes\": " + totalBytes + ",\n" +
                "  \"maxTotalBytes\": " + maxWorkspaceTotalBytes() + ",\n" +
                "  \"maxFileBytes\": " + maxWorkspaceFileBytes() + "\n" +
                "}\n";
            Files.writeString(quotaFile, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Best effort only.
        }
    }

    private List<String> listFromResources(String normalizedPath) {
        String basePath = normalizedPath;
        String resourcePath = DOCS_ROOT + (basePath.isEmpty() ? "" : "/" + basePath);
        try {
            URL url = getClass().getClassLoader().getResource(resourcePath);
            if (url == null) {
                return new ArrayList<>();
            }
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                Path dir = Paths.get(url.toURI());
                return listFromFileSystem(dir, basePath);
            }
            if ("jar".equalsIgnoreCase(url.getProtocol())) {
                return listFromJar(url.toURI(), basePath);
            }
            return listFromClasspathResources(basePath);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error listing resources: " + normalizedPath, e);
            return new ArrayList<>();
        }
    }

    private List<String> mergeWithUserSkills(String normalizedPath, List<String> entries) {
        if (!normalizedPath.isEmpty()) {
            return entries;
        }
        List<String> result = new ArrayList<>(entries);
        List<String> userSkills = listUserSkillsEntries();
        if (!userSkills.isEmpty() && !result.contains("user-skills/")) {
            result.add("user-skills/");
        }
        return result;
    }

    private List<String> listUserSkillsEntries() {
        List<String> result = new ArrayList<>();
        List<String> skills = listSkills();
        for (String name : skills) {
            result.add(name + "/");
        }
        return result;
    }

    private List<SkillMetadata> listSkillMetadataFromDir(String baseDir) {
        List<String> entries = list(baseDir);
        if (entries.isEmpty()) {
            return List.of();
        }

        List<SkillMetadata> results = new ArrayList<>();
        for (String entry : entries) {
            if ("SKILL.md".equals(entry)) {
                continue;
            }
            if (entry.endsWith("/")) {
                String path = baseDir + entry + "SKILL.md";
                SkillMetadata meta = parseSkillMetadata(path);
                if (meta != null) {
                    results.add(meta);
                }
            } else if (entry.endsWith(".md")) {
                String path = baseDir + entry;
                SkillMetadata meta = parseSkillMetadata(path);
                if (meta != null) {
                    results.add(meta);
                }
            }
        }

        return results;
    }

    private List<SkillMetadata> listUserSkillMetadata() {
        // Ensure legacy skills are migrated so metadata discovery matches Anthropic folder format.
        listSkills();

        Path skillsDir = userSkillsRoot();
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return List.of();
        }

        List<SkillMetadata> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path md = dir.resolve("SKILL.md");
                if (!Files.exists(md)) {
                    continue;
                }
            try {
                String content = Files.readString(md, StandardCharsets.UTF_8);
                String path =
                    USER_SKILLS_PREFIX +
                    dir.getFileName().toString() +
                    "/SKILL.md";
                SkillMetadata meta = parseSkillMetadata(path, content);
                if (meta != null) {
                    results.add(meta);
                }
            } catch (IOException e) {
                LOG.log(
                    Level.WARNING,
                    "Failed to read user skill metadata: " + md,
                    e
                );
            }
        }
        } catch (IOException e) {
            return List.of();
        }

        return results;
    }

    private SkillMetadata parseSkillMetadata(String path) {
        String content = read(path);
        return parseSkillMetadata(path, content);
    }

    private SkillMetadata parseSkillMetadata(String path, String content) {
        if (content == null || content.isBlank() || content.contains("File not found")) {
            return null;
        }

        Frontmatter fm = parseFrontmatter(content);
        String name = fm.get("name");
        String summary = fm.get("summary");
        if (summary == null || summary.isBlank()) {
            summary = fm.get("description");
        }
        List<String> keywords = fm.getList("keywords");
        List<String> layers = fm.getList("layer");
        String whenToUse = fm.get("when_to_use");
        String difficulty = fm.get("difficulty");

        if (name == null || name.isBlank()) {
            name = path;
        }
        if (summary == null || summary.isBlank()) {
            summary = extractFirstLine(content);
        }

        return new SkillMetadata(
            name.trim(),
            summary != null ? summary.trim() : "",
            keywords,
            layers,
            whenToUse,
            difficulty,
            path
        );
    }

    private static final class Frontmatter {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();

        String get(String key) {
            return values.get(key);
        }

        List<String> getList(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return parseList(value);
        }
    }

    private Frontmatter parseFrontmatter(String content) {
        Frontmatter fm = new Frontmatter();
        if (content == null || !content.startsWith("---")) {
            return fm;
        }

        String[] lines = content.split("\n");
        int i = 1;
        String pendingListKey = null;
        for (; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                break;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") && pendingListKey != null) {
                String item = stripQuotes(line.substring(2).trim());
                String existing = fm.values.getOrDefault(pendingListKey, "");
                String merged = existing.isBlank() ? item : existing + ", " + item;
                fm.values.put(pendingListKey, merged);
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if (value.isEmpty()) {
                pendingListKey = key;
                fm.values.put(key, "");
                continue;
            }
            pendingListKey = null;
            fm.values.put(key, stripQuotes(value));
        }

        return fm;
    }

    private static String stripQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<String> parseList(String value) {
        String trimmed = value.trim();
        String listBody = trimmed;
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            listBody = trimmed.substring(1, trimmed.length() - 1);
        }
        if (listBody.isBlank()) {
            return List.of();
        }
        String[] parts = listBody.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private static String extractFirstLine(String content) {
        if (content == null) {
            return "";
        }
        return content.lines()
            .filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("---"))
            .findFirst()
            .orElse("")
            .trim();
    }

    private List<String> listFromJar(URI jarUri, String basePath)
        throws IOException {
        List<String> results = new ArrayList<>();
        String jarPath = jarUri.toString().split("!")[0];

        try (
            FileSystem fs = FileSystems.newFileSystem(
                URI.create(jarPath),
                Collections.emptyMap()
            )
        ) {
            String searchPath = DOCS_ROOT + "/" + basePath;
            if (searchPath.endsWith("/")) {
                searchPath = searchPath.substring(0, searchPath.length() - 1);
            }

            Path dir = fs.getPath(searchPath);
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                try (Stream<Path> entries = Files.list(dir)) {
                    results = entries
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return Files.isDirectory(p) ? name + "/" : name;
                        })
                        .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            // JAR filesystem may already be open, try alternative approach
            results = listFromClasspathResources(basePath);
        }

        return results;
    }

    private List<String> listFromClasspathResources(String basePath) {
        List<String> results = new ArrayList<>();
        String resourcePath = DOCS_ROOT + "/" + basePath;

        try {
            Enumeration<URL> resources = getClass()
                .getClassLoader()
                .getResources(resourcePath);

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (
                    InputStream is = url.openStream();
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8)
                    )
                ) {
                    // For directory listing from classpath
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            results.add(line);
                        }
                    }
                } catch (IOException e) {
                    // Might not be a directory, try as file
                }
            }
        } catch (IOException e) {
            LOG.log(
                Level.FINE,
                "Error listing classpath resources: " + basePath,
                e
            );
        }

        return results;
    }

    private List<String> listFromFileSystem(Path dir, String basePath)
        throws IOException {
        List<String> results = new ArrayList<>();

        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (Stream<Path> entries = Files.list(dir)) {
                results = entries
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .collect(Collectors.toList());
            }
        }

        return results;
    }

    private void searchRecursive(
        String currentPath,
        String keyword,
        List<String> results
    ) {
        if (results.size() >= MAX_SEARCH_RESULTS) {
            return;
        }

        List<String> entries = list(currentPath);
        for (String entry : entries) {
            if (results.size() >= MAX_SEARCH_RESULTS) {
                break;
            }

            String fullPath = currentPath.isEmpty()
                ? entry
                : currentPath + entry;

            if (entry.endsWith("/")) {
                // Recurse into directory
                searchRecursive(fullPath, keyword, results);
            } else if (entry.endsWith(".md")) {
                // Search in markdown file
                String content = read(fullPath);
                if (content.toLowerCase().contains(keyword)) {
                    // Extract snippet around keyword
                    String snippet = extractSnippet(content, keyword);
                    results.add(fullPath + ": " + snippet);
                }
            }
        }
    }

    private String extractSnippet(String content, String keyword) {
        String lower = content.toLowerCase();
        int index = lower.indexOf(keyword.toLowerCase());
        if (index < 0) {
            return "";
        }

        // Get context around the match
        int start = Math.max(0, index - 40);
        int end = Math.min(content.length(), index + keyword.length() + 40);

        String snippet = content
            .substring(start, end)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim();

        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < content.length()) {
            snippet = snippet + "...";
        }

        return snippet;
    }

    private void searchRecursiveWithDetail(
        String currentPath,
        String keyword,
        List<String> results,
        DetailLevel detail
    ) {
        if (results.size() >= MAX_SEARCH_RESULTS) {
            return;
        }

        List<String> entries = list(currentPath);
        for (String entry : entries) {
            if (results.size() >= MAX_SEARCH_RESULTS) {
                break;
            }

            String fullPath = currentPath.isEmpty()
                ? entry
                : currentPath + entry;

            if (entry.endsWith("/")) {
                // Recurse into directory
                searchRecursiveWithDetail(fullPath, keyword, results, detail);
            } else if (entry.endsWith(".md")) {
                // Search in markdown file
                String content = read(fullPath);
                if (content.toLowerCase().contains(keyword)) {
                    String result = switch (detail) {
                        case NAME_ONLY -> fullPath;
                        case NAME_AND_DESCRIPTION -> {
                            String firstLine = content
                                .lines()
                                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                                .findFirst()
                                .orElse("");
                            yield fullPath + ": " + truncate(firstLine, 60);
                        }
                        case FULL -> fullPath + "\n" + content;
                    };
                    results.add(result);
                }
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private boolean tryMigrateLegacySkill(String safeName, String code) {
        if (safeName == null || safeName.isBlank() || code == null) {
            return false;
        }
        Path root = userSkillsRoot();
        Path md = root.resolve(safeName).resolve("SKILL.md");
        if (Files.exists(md)) {
            return true;
        }
        try {
            Files.createDirectories(md.getParent());
            Files.writeString(
                md,
                buildUserSkillSkillMd(safeName, code),
                StandardCharsets.UTF_8
            );
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String buildUserSkillSkillMd(String name, String code) {
        StringBuilder doc = new StringBuilder();
        String description = extractOneLineDescription(code);
        if (description == null || description.isBlank()) {
            description = "User-saved skill.";
        }
        String fmName = name != null ? name.trim() : "user-skill";
        String fmDesc = description.replaceAll("\\R", " ").trim();

        doc.append("---\n");
        doc.append("name: ").append(fmName).append("\n");
        doc.append("description: ").append(fmDesc).append("\n");
        doc.append("---\n\n");

        doc.append("# ").append(fmName).append("\n\n");
        doc.append(description).append("\n\n");

        // Extract Javadoc or leading comments
        String javadoc = extractJavadocBlock(code);
        if (javadoc != null && !javadoc.isBlank()) {
            doc.append("## Notes\n\n").append(javadoc).append("\n\n");
        }

        doc.append("## Code\n\n```groovy\n").append(code).append("\n```\n");
        return doc.toString();
    }

    private static String extractJavadocBlock(String code) {
        if (code == null) {
            return null;
        }
        int start = code.indexOf("/**");
        if (start < 0) {
            return null;
        }
        int end = code.indexOf("*/", start);
        if (end <= start) {
            return null;
        }
        return code
            .substring(start + 3, end)
            .lines()
            .map(l -> l.replaceFirst("^\\s*\\*\\s?", ""))
            .collect(Collectors.joining("\n"))
            .trim();
    }

    private static String extractOneLineDescription(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String javadoc = extractJavadocBlock(code);
        if (javadoc != null && !javadoc.isBlank()) {
            for (String line : javadoc.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        for (String line : code.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) {
                String msg = trimmed.replaceFirst("^//\\s*", "").trim();
                if (!msg.isEmpty()) {
                    return msg;
                }
            }
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                break;
            }
        }
        return null;
    }
}
