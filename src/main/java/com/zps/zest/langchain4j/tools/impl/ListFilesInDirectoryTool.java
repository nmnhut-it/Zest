package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Tool for listing files in a directory.
 */
public class ListFilesInDirectoryTool extends BaseCodeExplorationTool {
    
    // Common folders to exclude
    private static final Set<String> EXCLUDED_FOLDERS = new HashSet<>(Arrays.asList(
        ".git", ".svn", ".hg", ".bzr", // Version control
        "node_modules", "bower_components", // JavaScript
        "build", "dist", "out", "target", "bin", // Build outputs
        ".gradle", ".idea", ".vscode", ".eclipse", // IDE
        "__pycache__", ".pytest_cache", "venv", "env", // Python
        "vendor", "packages", // Package managers
        ".mvn", ".m2", // Maven
        "coverage", ".nyc_output", // Test coverage
        "logs", "temp", "tmp", "cache", // Temporary files
        ".DS_Store", "Thumbs.db" // OS files
    ));
    
    // Common code file extensions
    private static final Set<String> CODE_EXTENSIONS = new HashSet<>(Arrays.asList(
        // Java/JVM
        "java", "kt", "kts", "groovy", "scala", "clj",
        // Web
        "js", "jsx", "ts", "tsx", "vue", "html", "css", "scss", "sass", "less",
        // Python
        "py", "pyw", "pyx", "pyi",
        // C/C++
        "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
        // C#/.NET
        "cs", "vb", "fs", "fsx",
        // Go
        "go",
        // Rust
        "rs",
        // Ruby
        "rb", "erb",
        // PHP
        "php", "phtml",
        // Lua
        "lua",
        // Shell
        "sh", "bash", "zsh", "fish", "ps1", "psm1",
        // Config/Data
        "json", "xml", "yaml", "yml", "toml", "ini", "properties", "conf",
        // Documentation
        "md", "rst", "txt",
        // SQL
        "sql",
        // Swift
        "swift",
        // Kotlin
        "kt", "kts",
        // R
        "r", "R", "rmd", "Rmd"
    ));
    
    public ListFilesInDirectoryTool(@NotNull Project project) {
        super(project, "list_files", 
            "List files in a directory. BY DEFAULT: Only shows code files (excludes build artifacts, node_modules, etc). " +
            "Examples: " +
            "- list_files({\"directory\": \"/\"}) - list code files in project root " +
            "- list_files({\"directory\": \"src\", \"recursive\": true}) - all code files under src/ " +
            "- list_files({\"directory\": \"config\", \"includeAll\": true}) - ALL files including .properties, .xml " +
            "- list_files({\"directory\": \"src\", \"pattern\": \"*Test.java\"}) - only test files " +
            "Params: directory (string, required), recursive (bool), pattern (string), includeAll (bool - set true for non-code files)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject directory = new JsonObject();
        directory.addProperty("type", "string");
        directory.addProperty("description", "Directory path relative to project root (use '/' for project root)");
        properties.add("directory", directory);
        
        JsonObject recursive = new JsonObject();
        recursive.addProperty("type", "boolean");
        recursive.addProperty("description", "List files recursively in subdirectories");
        recursive.addProperty("default", false);
        properties.add("recursive", recursive);
        
        JsonObject pattern = new JsonObject();
        pattern.addProperty("type", "string");
        pattern.addProperty("description", "File name pattern (e.g., '*.java', 'Test*', '*Service.java')");
        properties.add("pattern", pattern);
        
        JsonObject includeAll = new JsonObject();
        includeAll.addProperty("type", "boolean");
        includeAll.addProperty("description", "Include all files, not just code files (bypasses exclusion filters)");
        includeAll.addProperty("default", false);
        properties.add("includeAll", includeAll);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("directory");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        String directory = getRequiredString(parameters, "directory");
        boolean recursive = parameters.has("recursive") && parameters.get("recursive").getAsBoolean();
        String pattern = getOptionalString(parameters, "pattern", null);
        boolean includeAll = parameters.has("includeAll") && parameters.get("includeAll").getAsBoolean();
        
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
            collectFiles(targetDir, files, recursive, pattern, "", includeAll);
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("directory", directory);
            metadata.addProperty("fileCount", files.size());
            metadata.addProperty("recursive", recursive);
            metadata.addProperty("includeAll", includeAll);
            if (pattern != null) {
                metadata.addProperty("pattern", pattern);
            }
            
            content.append("Code files in '").append(directory).append("'");
            if (pattern != null) {
                content.append(" matching '").append(pattern).append("'");
            }
            content.append(":\n\n");
            
            if (files.isEmpty()) {
                content.append("No code files found.\n");
                if (!includeAll) {
                    content.append("(Only showing code files. Use includeAll=true to see all files)\n");
                }
            } else {
                // Group by directory
                Map<String, List<FileInfo>> filesByDir = groupFilesByDirectory(files);
                
                // Sort directories
                List<String> sortedDirs = new ArrayList<>(filesByDir.keySet());
                sortedDirs.sort(String::compareTo);
                
                for (String dir : sortedDirs) {
                    String absoluteDirPath = targetDir.getPath();
                    if (!dir.isEmpty()) {
                        absoluteDirPath = absoluteDirPath + java.io.File.separator + dir;
                        content.append("\n### ").append(absoluteDirPath).append("\n");
                    } else {
                        content.append("\n### ").append(absoluteDirPath).append("\n");
                    }
                    
                    List<FileInfo> dirFiles = filesByDir.get(dir);
                    dirFiles.sort((a, b) -> {
                        // Sort directories first, then by name
                        if (a.isDirectory != b.isDirectory) {
                            return a.isDirectory ? -1 : 1;
                        }
                        return a.name.compareToIgnoreCase(b.name);
                    });
                    
                    for (FileInfo file : dirFiles) {
                        // Build absolute path for each file
                        String fileAbsolutePath = absoluteDirPath + java.io.File.separator + file.name;
                        content.append("- ").append(fileAbsolutePath);
                        if (file.isDirectory) {
                            content.append("/");
                            if (!recursive && hasCodeFiles(targetDir.findFileByRelativePath(
                                    (dir.isEmpty() ? "" : dir + "/") + file.name))) {
                                content.append(" (contains code files)");
                            }
                        } else {
                            content.append(" (").append(formatFileSize(file.size)).append(")");
                        }
                        content.append("\n");
                    }
                }
                
                // Add summary
                content.append("\n---\n");
                content.append("Total: ").append(files.size()).append(" items");
                int fileCount = (int) files.stream().filter(f -> !f.isDirectory).count();
                int dirCount = files.size() - fileCount;
                if (dirCount > 0) {
                    content.append(" (").append(fileCount).append(" files, ")
                           .append(dirCount).append(" directories)");
                }
                content.append("\n");
                
                if (!includeAll) {
                    content.append("Excluded folders: ").append(String.join(", ", EXCLUDED_FOLDERS)).append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to list files: " + e.getMessage());
        }
    }
    
    private void collectFiles(VirtualFile dir, List<FileInfo> files, boolean recursive, 
                            String pattern, String relativePath, boolean includeAll) {
        VirtualFile[] children = dir.getChildren();
        
        for (VirtualFile child : children) {
            String childName = child.getName();
            
            // Skip excluded folders unless includeAll is true
            if (!includeAll && child.isDirectory() && EXCLUDED_FOLDERS.contains(childName.toLowerCase())) {
                continue;
            }
            
            String childRelativePath = relativePath.isEmpty() ? "" : relativePath;
            
            if (child.isDirectory()) {
                // Add directory only if it contains code files (or includeAll is true)
                if (includeAll || (recursive && containsCodeFiles(child))) {
                    files.add(new FileInfo(childName, childRelativePath, 0, true));
                }
                
                if (recursive) {
                    String newPath = relativePath.isEmpty() ? childName : relativePath + "/" + childName;
                    collectFiles(child, files, true, pattern, newPath, includeAll);
                }
            } else {
                // Check if it's a code file or includeAll is true
                if (includeAll || isCodeFile(childName)) {
                    if (pattern == null || matchesPattern(childName, pattern)) {
                        files.add(new FileInfo(childName, childRelativePath, child.getLength(), false));
                    }
                }
            }
        }
    }
    
    /**
     * Checks if a directory contains any code files.
     */
    private boolean containsCodeFiles(VirtualFile dir) {
        VirtualFile[] children = dir.getChildren();
        
        for (VirtualFile child : children) {
            if (!child.isDirectory() && isCodeFile(child.getName())) {
                return true;
            }
            if (child.isDirectory() && !EXCLUDED_FOLDERS.contains(child.getName().toLowerCase())) {
                if (containsCodeFiles(child)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Quick check if a directory has code files (non-recursive).
     */
    private boolean hasCodeFiles(VirtualFile dir) {
        if (dir == null) return false;
        
        VirtualFile[] children = dir.getChildren();
        for (VirtualFile child : children) {
            if (!child.isDirectory() && isCodeFile(child.getName())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if a file is a code file based on its extension.
     */
    private boolean isCodeFile(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            // No extension or ends with dot
            return false;
        }
        
        String extension = fileName.substring(lastDot + 1).toLowerCase();
        return CODE_EXTENSIONS.contains(extension);
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
    
    private Map<String, List<FileInfo>> groupFilesByDirectory(List<FileInfo> files) {
        Map<String, List<FileInfo>> grouped = new HashMap<>();
        
        for (FileInfo file : files) {
            grouped.computeIfAbsent(file.directory, k -> new ArrayList<>()).add(file);
        }
        
        return grouped;
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
