package com.zps.zest.testgen.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

/**
 * Tool for listing files in a directory within the project.
 * Provides file exploration capabilities for understanding project structure.
 */
public class ListFilesTool {
    private final Project project;
    private final CodeExplorationToolRegistry toolRegistry;

    public ListFilesTool(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
        this.project = project;
        this.toolRegistry = toolRegistry;
    }

    @Tool("""
        List files and subdirectories in a directory with controlled recursion depth.
        
        Parameters:
        - directoryPath: The directory to list (e.g., "config/", "src/main/resources")
        - recursiveLevel: How deep to explore:
          * 0 = current directory only (non-recursive) - use for large dirs
          * 1 = current + immediate subdirectories - use for most exploration  
          * 2 = two levels deep - use for config/template structures
          * 3+ = deeper exploration - use only when absolutely necessary
        
        Examples:
        - listFiles("config/", 0) → lists only files directly in config/
        - listFiles("config/", 2) → explores config/ and 2 levels deep
        - listFiles("src/main/resources", 1) → resources/ and immediate subdirs
        - listFiles("scripts/", 1) → scripts/ and immediate subdirs
        
        Returns: A formatted tree of files and directories up to the specified depth.
        
        Strategy:
        - Use level 0 for large directories to avoid overwhelming output
        - Use level 1-2 for targeted exploration of config/resource directories
        - Always specify depth explicitly based on exploration needs
        """)
    public String listFiles(String directoryPath, int recursiveLevel) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // First try using the CodeExplorationTool if available
                CodeExplorationTool listTool = toolRegistry.getTool("list_files");
                if (listTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("directory", normalizeDirectoryPath(directoryPath));
                    params.addProperty("recursive", recursiveLevel > 0);
                    params.addProperty("maxDepth", recursiveLevel);

                    CodeExplorationTool.ToolResult result = listTool.execute(params);
                    if (result.isSuccess()) {
                        return formatListingResult(directoryPath, result.getContent());
                    }
                }

                // Fallback to direct file system access with depth control
                return listFilesDirectly(directoryPath, recursiveLevel);
                
            } catch (Exception e) {
                return String.format("Error listing directory '%s' (level %d): %s\n" +
                                   "Please check the path and ensure it exists within the project.",
                                   directoryPath, recursiveLevel, e.getMessage());
            }
        });
    }

    /**
     * Directly lists files using VirtualFile API as a fallback with recursive depth control.
     */
    private String listFilesDirectly(String directoryPath, int recursiveLevel) {
        VirtualFile directory = findDirectory(directoryPath);
        if (directory == null || !directory.isDirectory()) {
            return String.format("Directory not found or not a directory: %s\n" +
                               "Try using a different path format (absolute, relative, or package-style).",
                               directoryPath);
        }

        StringBuilder result = new StringBuilder();
        result.append("Contents of: ").append(directory.getPath());
        result.append(" (depth: ").append(recursiveLevel).append(")\n");
        result.append("─".repeat(60)).append("\n");

        listFilesRecursively(directory, result, 0, recursiveLevel, "");

        return result.toString();
    }

    /**
     * Recursively lists files up to specified depth.
     */
    private void listFilesRecursively(VirtualFile directory, StringBuilder result, int currentDepth, int maxDepth, String indent) {
        if (currentDepth > maxDepth) return;
        
        VirtualFile[] children = directory.getChildren();
        if (children.length == 0 && currentDepth == 0) {
            result.append(indent).append("  (empty directory)\n");
            return;
        }

        // Sort: directories first, then files, both alphabetically
        java.util.Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        // List directories first
        for (VirtualFile child : children) {
            if (child.isDirectory()) {
                result.append(indent).append("  📁 ").append(child.getName()).append("/\n");
                
                // Recurse into subdirectory if within depth limit
                if (currentDepth < maxDepth) {
                    listFilesRecursively(child, result, currentDepth + 1, maxDepth, indent + "  ");
                }
            }
        }
        
        // Then list files
        for (VirtualFile child : children) {
            if (!child.isDirectory()) {
                result.append(indent).append("  📄 ").append(child.getName());
                long size = child.getLength();
                if (size > 0) {
                    result.append(" (").append(formatFileSize(size)).append(")");
                }
                result.append("\n");
            }
        }
        
        // Show summary for root level
        if (currentDepth == 0) {
            long totalFiles = countFiles(directory, 0, maxDepth);
            long totalDirs = countDirectories(directory, 0, maxDepth);
            result.append("\nSummary: ").append(totalFiles).append(" files, ");
            result.append(totalDirs).append(" directories (depth: ").append(maxDepth).append(")");
        }
    }

    private long countFiles(VirtualFile directory, int currentDepth, int maxDepth) {
        if (currentDepth > maxDepth) return 0;
        
        long count = 0;
        for (VirtualFile child : directory.getChildren()) {
            if (child.isDirectory() && currentDepth < maxDepth) {
                count += countFiles(child, currentDepth + 1, maxDepth);
            } else if (!child.isDirectory()) {
                count++;
            }
        }
        return count;
    }

    private long countDirectories(VirtualFile directory, int currentDepth, int maxDepth) {
        if (currentDepth > maxDepth) return 0;
        
        long count = 0;
        for (VirtualFile child : directory.getChildren()) {
            if (child.isDirectory()) {
                count++;
                if (currentDepth < maxDepth) {
                    count += countDirectories(child, currentDepth + 1, maxDepth);
                }
            }
        }
        return count;
    }

    /**
     * Finds a directory by trying various path resolution strategies.
     */
    private VirtualFile findDirectory(String directoryPath) {
        // Normalize the path
        String normalizedPath = normalizeDirectoryPath(directoryPath);

        // Try as absolute path
        VirtualFile dir = LocalFileSystem.getInstance().findFileByPath(normalizedPath);
        if (dir != null && dir.exists()) {
            return dir;
        }

        // Try relative to project base
        String basePath = project.getBasePath();
        if (basePath != null) {
            dir = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalizedPath);
            if (dir != null && dir.exists()) {
                return dir;
            }
        }

        return null;
    }

    /**
     * Normalizes directory path, converting package notation to file path.
     */
    private String normalizeDirectoryPath(String path) {
        // Convert package notation to path (com.example -> com/example)
        String normalized = path.replace('.', '/');
        
        // Handle Windows paths
        normalized = normalized.replace('\\', '/');
        
        // Remove trailing slashes
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        
        return normalized;
    }

    /**
     * Formats the listing result for better readability.
     */
    private String formatListingResult(String requestedPath, String content) {
        return String.format("Directory listing for: %s\n%s", requestedPath, content);
    }

    /**
     * Formats file size in human-readable format.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}