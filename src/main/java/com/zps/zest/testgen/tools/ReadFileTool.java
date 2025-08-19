package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tool for reading file contents from the project.
 * Supports various file types including configuration files, scripts, data files, etc.
 */
public class ReadFileTool {
    private final Project project;
    private final Map<String, String> readFiles;
    private static final int PREVIEW_LENGTH = 500;
    private static final int MAX_FILE_SIZE = 1024 * 1024; // 1MB limit

    public ReadFileTool(@NotNull Project project, @NotNull Map<String, String> readFiles) {
        this.project = project;
        this.readFiles = readFiles;
    }

    @Tool("""
        Read the complete content of any file in the project (configs, scripts, data files, etc.).
        This tool is essential for understanding configuration, resources, and non-Java files.
        
        Parameters:
        - filePath: The path to the file. Can be:
          * Absolute path: "/full/path/to/file.xml"
          * Relative path from project: "src/main/resources/application.properties"
          * Package-style resource path: "com.example.config.settings" (will try common extensions)
          * Path with ~ for home: "~/config/file.conf"
        
        Returns: The complete file content or a preview for large files, with the content 
                automatically stored for inclusion in test context.
        
        Supported file types:
        - Configuration: .properties, .xml, .json, .yml, .yaml, .conf
        - Scripts: .sh, .bat, .py, .js, .sql
        - Data: .csv, .txt, .md
        - Templates: .ftl, .vm, .html
        - Any other text-based file
        
        Notes:
        - Files larger than 1MB will show a preview with option to read in chunks
        - Binary files are not supported
        - File content is automatically captured for test generation context
        
        Example usage:
        - readFile("src/main/resources/application.properties")
        - readFile("config/database.yml")
        - readFile("com.example.templates.email")  // Will try .ftl, .vm, etc.
        - readFile("scripts/setup.sh")
        """)
    public String readFile(String filePath) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                VirtualFile virtualFile = findFileByPathOrFqn(filePath);
                if (virtualFile == null) {
                    // Enhanced error message with debugging info
                    StringBuilder suggestions = new StringBuilder();
                    suggestions.append("❌ File not found: ").append(filePath).append("\n\n");
                    suggestions.append("🔍 Paths tried:\n");
                    suggestions.append("- Direct path: ").append(filePath).append("\n");
                    suggestions.append("- Normalized: ").append(filePath.replace('\\', '/')).append("\n");
                    
                    String basePath = project.getBasePath();
                    if (basePath != null) {
                        suggestions.append("- Project relative: ").append(basePath).append("/").append(filePath).append("\n");
                    }
                    
                    suggestions.append("\n💡 Suggestions:\n");
                    suggestions.append("- Check the file path and spelling\n");
                    suggestions.append("- Try different path formats (absolute, relative, package-style)\n");
                    suggestions.append("- Use listFiles or findFiles to locate the file first\n");
                    suggestions.append("- For Java files, try: com/package/ClassName.java\n");
                    suggestions.append("- For Windows paths, try: C:/full/path/to/file.ext\n");
                    suggestions.append("- Ensure the file exists in the project");
                    
                    return suggestions.toString();
                }

                // Check if it's a binary file
                if (isBinaryFile(virtualFile)) {
                    return String.format("❌ Cannot read binary file: %s\n" +
                                       "This tool only supports text-based files.\n" +
                                       "File type: %s",
                                       virtualFile.getName(), virtualFile.getFileType().getName());
                }

                // Check file size
                long fileSize = virtualFile.getLength();
                if (fileSize > MAX_FILE_SIZE) {
                    return String.format("⚠️ File too large: %s\n" +
                                       "Size: %s (limit: 1MB)\n" +
                                       "Showing preview only. Consider reading specific sections.\n",
                                       virtualFile.getName(), formatFileSize(fileSize));
                }

                // Read file content
                String content = new String(virtualFile.contentsToByteArray(), 
                                           virtualFile.getCharset() != null ? 
                                           virtualFile.getCharset() : 
                                           StandardCharsets.UTF_8);

                // Store the file content with canonical path
                String canonicalPath = virtualFile.getPath();
                readFiles.put(canonicalPath, content);

                // Format the response
                return formatFileContent(virtualFile, content);
                
            } catch (Exception e) {
                return String.format("❌ Error reading file '%s': %s\n" +
                                   "Please check file permissions and encoding.",
                                   filePath, e.getMessage());
            }
        });
    }

    /**
     * Formats file content for display.
     */
    private String formatFileContent(VirtualFile file, String content) {
        StringBuilder result = new StringBuilder();
        
        // File header
        result.append("📄 File: ").append(file.getName()).append("\n");
        result.append("📁 Path: ").append(file.getPath()).append("\n");
        result.append("📏 Size: ").append(formatFileSize(file.getLength()));
        result.append(" | Lines: ").append(countLines(content)).append("\n");
        result.append("─".repeat(70)).append("\n\n");

        // Content or preview
        if (content.length() > 2000) {
            // Show preview for large files
            result.append("Content (preview - first 1000 chars):\n");
            result.append("```").append(getFileExtension(file)).append("\n");
            result.append(content.substring(0, Math.min(1000, content.length())));
            if (content.length() > 1000) {
                result.append("\n...\n[Content truncated - ");
                result.append(content.length() - 1000).append(" more characters]");
            }
            result.append("\n```\n\n");
            result.append("✅ Full content has been stored for test generation context.");
        } else {
            // Show full content for smaller files
            result.append("Content:\n");
            result.append("```").append(getFileExtension(file)).append("\n");
            result.append(content);
            result.append("\n```\n\n");
            result.append("✅ File content stored for test generation context.");
        }

        return result.toString();
    }

    /**
     * Finds a file by path or FQN, trying multiple resolution strategies.
     */
    @Nullable
    private VirtualFile findFileByPathOrFqn(String pathOrFqn) {
        // First normalize path separators for Windows/Unix compatibility
        String normalizedPath = pathOrFqn.replace('\\', '/');
        
        // Try direct absolute path
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(normalizedPath);
        if (file != null && file.exists()) {
            return file;
        }

        // Try relative to project base path
        String basePath = project.getBasePath();
        if (basePath != null) {
            basePath = basePath.replace('\\', '/');
            
            // Try as relative path from project root
            file = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalizedPath);
            if (file != null && file.exists()) {
                return file;
            }

            // Try without leading slash if present
            if (normalizedPath.startsWith("/")) {
                file = LocalFileSystem.getInstance().findFileByPath(basePath + normalizedPath);
                if (file != null && file.exists()) {
                    return file;
                }
            }
            
            // Try with explicit normalization for Windows paths like C:/...
            if (normalizedPath.matches("^[A-Za-z]:/.*")) {
                file = LocalFileSystem.getInstance().findFileByPath(normalizedPath);
                if (file != null && file.exists()) {
                    return file;
                }
            }
        }

        // Handle package-style paths or FQN (e.g., com.example.resources.config or com/zps.redis/RedisConfig.java)
        if (normalizedPath.contains(".") && !normalizedPath.startsWith("/") && !normalizedPath.matches("^[A-Za-z]:/.*")) {
            // Check if it already has a file extension
            boolean hasExtension = normalizedPath.matches(".*\\.[a-zA-Z0-9]{1,4}$");
            
            if (hasExtension) {
                // It's already a path with extension, just try to find it in project
                file = findInProjectSources(normalizedPath);
                if (file != null) {
                    return file;
                }
            } else {
                // Convert dot notation to path and try various extensions
                String resourcePath = normalizedPath.replace('.', '/');

                // Try common file extensions including Java source files
                String[] extensions = {".java", ".kt", ".properties", ".xml", ".json", ".yml", ".yaml", 
                                      ".conf", ".txt", ".csv", ".sql", ".ftl", ".vm", ".html", ".js", ".ts"};
                for (String ext : extensions) {
                    file = findInProjectSources(resourcePath + ext);
                    if (file != null) {
                        return file;
                    }
                }

                // Try without extension
                file = findInProjectSources(resourcePath);
                if (file != null) {
                    return file;
                }
            }
        }
        
        // Try to find the file in project sources (handles mixed path styles)
        file = findInProjectSources(normalizedPath);
        if (file != null) {
            return file;
        }


        // Try to expand ~ for home directory
        if (pathOrFqn.startsWith("~")) {
            String homeDir = System.getProperty("user.home");
            String expandedPath = pathOrFqn.replaceFirst("~", homeDir);
            file = LocalFileSystem.getInstance().findFileByPath(expandedPath);
            if (file != null && file.exists()) {
                return file;
            }
        }

        return null;
    }

    /**
     * Enhanced method to find files in project sources, including all source and resource directories.
     */
    @Nullable
    private VirtualFile findInProjectSources(String filePath) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

            // Check all source roots (includes src/main/java, src/test/java, etc.)
            for (VirtualFile sourceRoot : rootManager.getSourceRoots()) {
                VirtualFile file = sourceRoot.findFileByRelativePath(filePath);
                if (file != null && file.exists()) {
                    return file;
                }
            }

            // Check content roots and their subdirectories
            for (VirtualFile contentRoot : rootManager.getContentRoots()) {
                // Try direct path from content root
                VirtualFile file = contentRoot.findFileByRelativePath(filePath);
                if (file != null && file.exists()) {
                    return file;
                }
                
                // Try common source directories
                String[] sourceDirs = {"src/main/java", "src/test/java", "src/main/kotlin", "src/test/kotlin",
                                      "src/main/resources", "src/test/resources", "src", "main", "test"};
                for (String srcDir : sourceDirs) {
                    VirtualFile sourceDir = contentRoot.findFileByRelativePath(srcDir);
                    if (sourceDir != null) {
                        file = sourceDir.findFileByRelativePath(filePath);
                        if (file != null && file.exists()) {
                            return file;
                        }
                    }
                }
            }
        }

        return null;
    }
    
    /**
     * Finds a resource file in the project's resource directories.
     */
    @Nullable
    private VirtualFile findResourceFile(String resourcePath) {
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

            // Check source roots
            for (VirtualFile sourceRoot : rootManager.getSourceRoots()) {
                VirtualFile file = sourceRoot.findFileByRelativePath(resourcePath);
                if (file != null && file.exists()) {
                    return file;
                }
            }

            // Check common resource directories
            for (VirtualFile contentRoot : rootManager.getContentRoots()) {
                String[] resourceDirs = {"src/main/resources", "src/test/resources", 
                                        "resources", "res", "config", "conf"};
                for (String resDir : resourceDirs) {
                    VirtualFile resourceRoot = contentRoot.findFileByRelativePath(resDir);
                    if (resourceRoot != null) {
                        VirtualFile file = resourceRoot.findFileByRelativePath(resourcePath);
                        if (file != null && file.exists()) {
                            return file;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks if a file is likely binary based on its extension.
     */
    private boolean isBinaryFile(VirtualFile file) {
        String name = file.getName().toLowerCase();
        String[] binaryExtensions = {".class", ".jar", ".zip", ".exe", ".dll", 
                                    ".so", ".dylib", ".png", ".jpg", ".jpeg", 
                                    ".gif", ".pdf", ".doc", ".docx", ".xls", ".xlsx"};
        
        for (String ext : binaryExtensions) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Gets file extension for syntax highlighting.
     */
    private String getFileExtension(VirtualFile file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && lastDot < name.length() - 1) {
            String ext = name.substring(lastDot + 1).toLowerCase();
            // Map to common language identifiers for markdown
            switch (ext) {
                case "yml":
                case "yaml":
                    return "yaml";
                case "json":
                    return "json";
                case "xml":
                    return "xml";
                case "properties":
                case "conf":
                    return "properties";
                case "sql":
                    return "sql";
                case "sh":
                    return "bash";
                case "bat":
                case "cmd":
                    return "batch";
                case "py":
                    return "python";
                case "js":
                    return "javascript";
                case "html":
                case "htm":
                    return "html";
                case "css":
                    return "css";
                default:
                    return ext;
            }
        }
        return "";
    }

    /**
     * Counts lines in content.
     */
    private int countLines(String content) {
        if (content.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
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