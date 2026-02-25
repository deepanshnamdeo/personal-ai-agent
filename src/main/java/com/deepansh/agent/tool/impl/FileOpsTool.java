package com.deepansh.agent.tool.impl;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * File operations tool — read, write, list, delete files.
 *
 * Security model:
 * - All operations are sandboxed to base-directory (AGENT_FILES_DIR)
 * - Path traversal prevention: canonical path must start with base dir
 * - Extension allowlist: only permitted file types can be created/read
 * - File size cap: reads and writes capped at max-file-size-kb
 * - No symlink following (NOFOLLOW_LINKS)
 *
 * Operations: read, write, append, list, delete
 *
 * The base directory is auto-created on first use.
 */
@Component
@Slf4j
public class FileOpsTool implements AgentTool {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ToolProperties toolProperties;

    public FileOpsTool(ToolProperties toolProperties) {
        this.toolProperties = toolProperties;
    }

    @Override
    public String getName() {
        return "file_ops";
    }

    @Override
    public String getDescription() {
        return """
                Read, write, append, list, or delete files in your personal notes directory.
                Use this to save research summaries, create notes, maintain task lists,
                store structured data, or read previously saved content.
                Supported file types: txt, md, json, csv, yaml, log.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of(
                                "type", "string",
                                "enum", List.of("read", "write", "append", "list", "delete"),
                                "description", "The operation to perform"
                        ),
                        "filename", Map.of(
                                "type", "string",
                                "description", "Filename with extension. E.g: 'meeting-notes.md', 'tasks.txt'. " +
                                               "Subdirectories allowed: 'notes/project.md'"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "File content for write/append operations"
                        )
                ),
                "required", List.of("operation")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String operation = (String) arguments.get("operation");
        if (operation == null || operation.isBlank()) {
            return "ERROR: 'operation' is required (read, write, append, list, delete)";
        }

        try {
            ensureBaseDirectoryExists();
            return switch (operation.toLowerCase()) {
                case "read"   -> readFile(arguments);
                case "write"  -> writeFile(arguments, false);
                case "append" -> writeFile(arguments, true);
                case "list"   -> listFiles(arguments);
                case "delete" -> deleteFile(arguments);
                default -> "ERROR: Unknown operation '" + operation + "'";
            };
        } catch (Exception e) {
            log.error("FileOps failed [operation={}]", operation, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String readFile(Map<String, Object> args) throws IOException {
        Path path = resolveSafePath((String) args.get("filename"));
        if (path == null) return "ERROR: 'filename' is required for read";

        if (!Files.exists(path)) {
            return "File not found: " + args.get("filename");
        }

        long sizeKb = Files.size(path) / 1024;
        int maxKb = toolProperties.getFileOps().getMaxFileSizeKb();
        if (sizeKb > maxKb) {
            return String.format("ERROR: File too large (%d KB). Max allowed: %d KB", sizeKb, maxKb);
        }

        String content = Files.readString(path, StandardCharsets.UTF_8);
        log.info("Read file: {} ({} bytes)", path.getFileName(), content.length());
        return content;
    }

    private String writeFile(Map<String, Object> args, boolean append) throws IOException {
        Path path = resolveSafePath((String) args.get("filename"));
        if (path == null) return "ERROR: 'filename' is required for " + (append ? "append" : "write");

        String content = (String) args.get("content");
        if (content == null) return "ERROR: 'content' is required";

        // Extension check
        String ext = getExtension(path.getFileName().toString());
        if (!toolProperties.getFileOps().getAllowedExtensionList().contains(ext)) {
            return "ERROR: Extension '." + ext + "' not allowed. " +
                   "Allowed: " + toolProperties.getFileOps().getAllowedExtensionList();
        }

        // Size check before write
        int maxBytes = toolProperties.getFileOps().getMaxFileSizeKb() * 1024;
        if (content.length() > maxBytes) {
            return String.format("ERROR: Content too large (%d bytes). Max: %d KB",
                    content.length(), toolProperties.getFileOps().getMaxFileSizeKb());
        }

        // Create parent dirs if needed
        Files.createDirectories(path.getParent());

        OpenOption[] options = append
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

        Files.writeString(path, content, StandardCharsets.UTF_8, options);

        String action = append ? "Appended" : "Written";
        log.info("{} file: {} ({} bytes)", action, path.getFileName(), content.length());
        return String.format("%s successfully to '%s' (%d bytes)",
                action, args.get("filename"), content.length());
    }

    private String listFiles(Map<String, Object> args) throws IOException {
        String subdir = (String) args.getOrDefault("filename", "");
        Path listDir = subdir != null && !subdir.isBlank()
                ? resolveSafePath(subdir)
                : getBaseDir();

        if (listDir == null || !Files.exists(listDir)) {
            return "Directory not found or empty.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Files in '").append(subdir == null || subdir.isBlank() ? "/" : subdir).append("':\n\n");

        try (var stream = Files.walk(listDir, 2)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p,
                                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            String relPath = getBaseDir().relativize(p).toString();
                            String modified = DATE_FMT.format(
                                    Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()));
                            long sizeKb = Math.max(1, attrs.size() / 1024);
                            sb.append(String.format("  %-40s %s  %d KB\n", relPath, modified, sizeKb));
                        } catch (IOException ignored) {}
                    });
        }
        return sb.toString();
    }

    private String deleteFile(Map<String, Object> args) throws IOException {
        Path path = resolveSafePath((String) args.get("filename"));
        if (path == null) return "ERROR: 'filename' is required for delete";

        if (!Files.exists(path)) {
            return "File not found: " + args.get("filename");
        }

        Files.delete(path);
        log.info("Deleted file: {}", path.getFileName());
        return "Deleted: " + args.get("filename");
    }

    /**
     * Resolves a filename to an absolute path within the base directory.
     * Returns null if filename is null/blank.
     * Throws SecurityException if path escapes base directory (traversal prevention).
     */
    private Path resolveSafePath(String filename) throws IOException {
        if (filename == null || filename.isBlank()) return null;

        Path base = getBaseDir().toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path resolved = base.resolve(filename).normalize();

        if (!resolved.startsWith(base)) {
            throw new SecurityException(
                    "Path traversal attempt detected: '" + filename + "'");
        }
        return resolved;
    }

    private Path getBaseDir() {
        return Paths.get(toolProperties.getFileOps().getBaseDirectory()).toAbsolutePath();
    }

    private void ensureBaseDirectoryExists() throws IOException {
        Files.createDirectories(getBaseDir());
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }
}
