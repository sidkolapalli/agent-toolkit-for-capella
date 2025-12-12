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
    private static final String DOCS_ROOT = "capella-api";

    /** Maximum number of search results to return */
    private static final int MAX_SEARCH_RESULTS = 10;

    /** Maximum file size to read (100KB) */
    private static final int MAX_FILE_SIZE = 100_000;

    /** User skills directory (writable) */
    private static final String USER_SKILLS_DIR =
        System.getProperty("user.home") +
        File.separator +
        ".capella-ai" +
        File.separator +
        "skills";

    /** Search detail levels */
    public enum DetailLevel {
        /** Return only file names */
        NAME_ONLY,
        /** Return names with first-line description */
        NAME_AND_DESCRIPTION,
        /** Return full file content */
        FULL,
    }

    /**
     * List contents of a directory in the documentation tree.
     *
     * @param path relative path within the documentation root (e.g., "api/", "skills/")
     * @return list of entries (files and directories, directories end with "/")
     */
    public List<String> list(String path) {
        List<String> results = new ArrayList<>();
        String normalizedPath = normalizePath(path);
        String resourcePath = DOCS_ROOT + "/" + normalizedPath;

        try {
            URL resourceUrl = getClass()
                .getClassLoader()
                .getResource(resourcePath);
            if (resourceUrl == null) {
                // Try without trailing slash
                if (resourcePath.endsWith("/")) {
                    resourcePath = resourcePath.substring(
                        0,
                        resourcePath.length() - 1
                    );
                    resourceUrl = getClass()
                        .getClassLoader()
                        .getResource(resourcePath);
                }
            }

            if (resourceUrl != null) {
                URI uri = resourceUrl.toURI();
                if ("jar".equals(uri.getScheme())) {
                    results = listFromJar(uri, normalizedPath);
                } else {
                    results = listFromFileSystem(
                        Paths.get(uri),
                        normalizedPath
                    );
                }
            }
        } catch (URISyntaxException | IOException e) {
            LOG.log(Level.FINE, "Error listing path: " + path, e);
        }

        Collections.sort(results);
        return results;
    }

    /**
     * Read the content of a documentation file.
     *
     * @param path relative path to the file (e.g., "SKILL.md", "api/create.md")
     * @return the file content as a string, or error message if not found
     */
    public String read(String path) {
        String normalizedPath = normalizePath(path);
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
        String resourcePath = DOCS_ROOT + "/" + normalizedPath;
        return getClass().getClassLoader().getResource(resourcePath) != null;
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
     * <p>Skills are saved to ~/.capella-ai/skills/ and can be loaded in future sessions.
     * Each skill should have a descriptive name and include documentation.
     *
     * @param name the skill name (will be used as filename, e.g., "create-state-machine")
     * @param content the skill content (Groovy code with comments)
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
            // Ensure directory exists
            File skillsDir = new File(USER_SKILLS_DIR);
            if (!skillsDir.exists()) {
                skillsDir.mkdirs();
            }

            // Write skill file
            File skillFile = new File(skillsDir, safeName + ".groovy");
            try (
                FileWriter writer = new FileWriter(
                    skillFile,
                    StandardCharsets.UTF_8
                )
            ) {
                writer.write(content);
            }

            // Also create a SKILL.md if content has documentation
            if (content.contains("/**") || content.contains("//")) {
                String doc = extractDocumentation(content, safeName);
                File docFile = new File(skillsDir, safeName + ".md");
                try (
                    FileWriter writer = new FileWriter(
                        docFile,
                        StandardCharsets.UTF_8
                    )
                ) {
                    writer.write(doc);
                }
            }

            LOG.info(
                "Saved skill: " +
                    safeName +
                    " to " +
                    skillFile.getAbsolutePath()
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

        File skillFile = new File(USER_SKILLS_DIR, safeName + ".groovy");
        if (!skillFile.exists()) {
            // Try without extension
            skillFile = new File(USER_SKILLS_DIR, safeName);
        }

        if (skillFile.exists()) {
            try {
                return Files.readString(
                    skillFile.toPath(),
                    StandardCharsets.UTF_8
                );
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to load skill: " + name, e);
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
        File skillsDir = new File(USER_SKILLS_DIR);

        if (skillsDir.exists() && skillsDir.isDirectory()) {
            File[] files = skillsDir.listFiles((dir, name) ->
                name.endsWith(".groovy")
            );
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    skills.add(name.substring(0, name.length() - 7)); // Remove .groovy
                }
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

        File skillFile = new File(USER_SKILLS_DIR, safeName + ".groovy");
        File docFile = new File(USER_SKILLS_DIR, safeName + ".md");

        boolean deleted = false;
        if (skillFile.exists()) {
            deleted = skillFile.delete();
        }
        if (docFile.exists()) {
            docFile.delete();
        }

        return deleted;
    }

    // ========== Private Helpers ==========

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        // Remove leading slash
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        // Prevent path traversal
        normalized = normalized.replace("..", "");
        return normalized;
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

    private String extractDocumentation(String code, String name) {
        StringBuilder doc = new StringBuilder();
        doc.append("# ").append(name).append("\n\n");
        doc.append("User-saved skill.\n\n");

        // Extract Javadoc or leading comments
        if (code.contains("/**")) {
            int start = code.indexOf("/**");
            int end = code.indexOf("*/", start);
            if (end > start) {
                String javadoc = code
                    .substring(start + 3, end)
                    .lines()
                    .map(l -> l.replaceFirst("^\\s*\\*\\s?", ""))
                    .collect(Collectors.joining("\n"));
                doc.append("## Description\n\n").append(javadoc).append("\n\n");
            }
        }

        doc.append("## Code\n\n```groovy\n").append(code).append("\n```\n");
        return doc.toString();
    }
}
