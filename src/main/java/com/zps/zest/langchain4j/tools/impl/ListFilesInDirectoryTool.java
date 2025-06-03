package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for listing files in a directory.
 */
public class ListFilesInDirectoryTool extends BaseCodeExplorationTool {
    
    public ListFilesInDirectoryTool(@NotNull Project project) {
        super(project, "list_files", "List files in a directory");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject directory = new JsonObject();
        directory.addProperty("type", "string");
        directory.addProperty("description", "Directory path (relative to project root)");
        properties.add("directory", directory);
        
        JsonObject recursive = new JsonObject();
        recursive.addProperty("type", "boolean");
        recursive.addProperty("description", "List files recursively (default: false)");
        recursive.addProperty("default", false);
        properties.add("recursive", recursive);
        
        JsonObject pattern = new JsonObject();
        pattern.addProperty("type", "string");
        pattern.addProperty("description", "File name pattern to filter (e.g., '*.java')");
        properties.add("pattern", pattern);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"directory\"]");
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String directory = getRequiredString(parameters, "directory");
        boolean recursive = parameters.has("recursive") && parameters.get("recursive").getAsBoolean();
        String pattern = getOptionalString(parameters, "pattern", null);
        
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) {
                return ToolResult.error("Project base directory not found");
            }
            
            VirtualFile targetDir;
            if (directory.equals("/") || directory.isEmpty()) {
                targetDir = baseDir;
            } else {
                targetDir = baseDir.findFileByRelativePath(directory);
            }
            
            if (targetDir == null) {
                return ToolResult.error("Directory not found: " + directory);
            }
            
            if (!targetDir.isDirectory()) {
                return ToolResult.error("Path is not a directory: " + directory);
            }
            
            List<FileInfo> files = new ArrayList<>();
            collectFiles(targetDir, files, recursive, pattern, "");
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("directory", directory);
            metadata.addProperty("fileCount", files.size());
            metadata.addProperty("recursive", recursive);
            if (pattern != null) {
                metadata.addProperty("pattern", pattern);
            }
            
            content.append("Files in '").append(directory).append("'");
            if (pattern != null) {
                content.append(" matching '").append(pattern).append("'");
            }
            content.append(":\n\n");
            
            if (files.isEmpty()) {
                content.append("No files found.\n");
            } else {
                // Group by directory
                String currentDir = null;
                for (FileInfo file : files) {
                    if (!file.directory.equals(currentDir)) {
                        currentDir = file.directory;
                        if (!currentDir.isEmpty()) {
                            content.append("\n### ").append(currentDir).append("/\n");
                        }
                    }
                    content.append("- ").append(file.name);
                    if (file.isDirectory) {
                        content.append("/");
                    } else {
                        content.append(" (").append(formatFileSize(file.size)).append(")");
                    }
                    content.append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to list files: " + e.getMessage());
        }
    }
    
    private void collectFiles(VirtualFile dir, List<FileInfo> files, boolean recursive, 
                            String pattern, String relativePath) {
        VirtualFile[] children = dir.getChildren();
        
        for (VirtualFile child : children) {
            String childRelativePath = relativePath.isEmpty() ? "" : relativePath;
            
            if (child.isDirectory()) {
                files.add(new FileInfo(child.getName(), childRelativePath, 0, true));
                if (recursive) {
                    String newPath = relativePath.isEmpty() ? child.getName() : relativePath + "/" + child.getName();
                    collectFiles(child, files, true, pattern, newPath);
                }
            } else {
                if (pattern == null || matchesPattern(child.getName(), pattern)) {
                    files.add(new FileInfo(child.getName(), childRelativePath, child.getLength(), false));
                }
            }
        }
        
        // Sort files: directories first, then by name
        files.sort((a, b) -> {
            if (a.isDirectory != b.isDirectory) {
                return a.isDirectory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }
    
    private boolean matchesPattern(String fileName, String pattern) {
        // Simple pattern matching
        if (pattern.startsWith("*")) {
            String suffix = pattern.substring(1);
            return fileName.endsWith(suffix);
        } else if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return fileName.startsWith(prefix);
        } else if (pattern.contains("*")) {
            String[] parts = pattern.split("\\*");
            if (parts.length == 2) {
                return fileName.startsWith(parts[0]) && fileName.endsWith(parts[1]);
            }
        }
        return fileName.equals(pattern);
    }
    
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
    }
    
    private static class FileInfo {
        final String name;
        final String directory;
        final long size;
        final boolean isDirectory;
        
        FileInfo(String name, String directory, long size, boolean isDirectory) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.isDirectory = isDirectory;
        }
    }
}
