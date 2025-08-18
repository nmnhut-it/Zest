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
        List all files and subdirectories in a specified directory.
        This tool helps explore the project structure and discover relevant files.
        
        Parameters:
        - directoryPath: The path to the directory to list. Can be:
          * Absolute path: "/full/path/to/directory"
          * Relative path from project root: "src/main/java"
          * Package-style path: "com.example.package" (will be converted to directory path)
        
        Returns: A formatted list of files and subdirectories, or an error message if the directory doesn't exist.
        
        Note: The listing is non-recursive (only shows immediate children).
        Use this tool multiple times to explore deeper into the directory structure.
        
        Example usage:
        - listFiles("src/main/resources")
        - listFiles("com.example.config")
        - listFiles("/absolute/path/to/dir")
        """)
    public String listFiles(String directoryPath) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // First try using the CodeExplorationTool if available
                CodeExplorationTool listTool = toolRegistry.getTool("list_files");
                if (listTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("directoryPath", normalizeDirectoryPath(directoryPath));
                    params.addProperty("recursive", false);

                    CodeExplorationTool.ToolResult result = listTool.execute(params);
                    if (result.isSuccess()) {
                        return formatListingResult(directoryPath, result.getContent());
                    }
                }

                // Fallback to direct file system access
                return listFilesDirectly(directoryPath);
                
            } catch (Exception e) {
                return String.format("Error listing directory '%s': %s\n" +
                                   "Please check the path and ensure it exists within the project.",
                                   directoryPath, e.getMessage());
            }
        });
    }

    /**
     * Directly lists files using VirtualFile API as a fallback.
     */
    private String listFilesDirectly(String directoryPath) {
        VirtualFile directory = findDirectory(directoryPath);
        if (directory == null || !directory.isDirectory()) {
            return String.format("Directory not found or not a directory: %s\n" +
                               "Try using a different path format (absolute, relative, or package-style).",
                               directoryPath);
        }

        StringBuilder result = new StringBuilder();
        result.append("Contents of: ").append(directory.getPath()).append("\n");
        result.append("─".repeat(50)).append("\n");

        VirtualFile[] children = directory.getChildren();
        if (children.length == 0) {
            result.append("  (empty directory)\n");
        } else {
            // Separate directories and files
            for (VirtualFile child : children) {
                if (child.isDirectory()) {
                    result.append("  📁 ").append(child.getName()).append("/\n");
                }
            }
            for (VirtualFile child : children) {
                if (!child.isDirectory()) {
                    result.append("  📄 ").append(child.getName());
                    long size = child.getLength();
                    if (size > 0) {
                        result.append(" (").append(formatFileSize(size)).append(")");
                    }
                    result.append("\n");
                }
            }
        }

        result.append("\nTotal: ").append(children.length).append(" items");
        return result.toString();
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