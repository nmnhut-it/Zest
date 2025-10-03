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
import com.zps.zest.explanation.tools.RipgrepCodeTool;

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
        Read the complete content of any text-based file in the project.
        Use this tool to examine source code, documentation, scripts, configuration, or any other files.
        
        Parameters:
        - filePath: The path to the file. Supports multiple formats:
          * Relative from project root: "src/main/java/MyClass.java"
          * Absolute path: "/full/path/to/file.txt"
          * Package-style path: "com.example.MyClass" (will try common extensions)
          * Home directory: "~/documents/notes.md"
        
        Returns: The complete file content, or a preview for very large files.
        
        Supported file types:
        - Source code: .java, .kt, .py, .js, .ts, .cpp, .c, .go, .rs, .rb, .php
        - Configuration: .properties, .xml, .json, .yml, .yaml, .conf, .toml
        - Documentation: .md, .txt, .rst, .adoc
        - Scripts: .sh, .bat, .ps1, .sql
        - Web: .html, .css, .scss, .jsx, .tsx, .vue
        - Data: .csv, .log, .env
        - Templates: .ftl, .vm, .mustache
        - Any other text-based file
        
        Example usage:
        - readFile("src/main/java/UserService.java") - Read Java source code
        - readFile("README.md") - Read project documentation  
        - readFile("package.json") - Read Node.js dependencies
        - readFile("src/test/java/UserTest.java") - Read test files
        - readFile("scripts/deploy.sh") - Read deployment scripts
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

                // Check for empty content
                if (content == null || content.trim().isEmpty()) {
                    return formatEmptyFileContent(virtualFile, content);
                }

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
     * Formats empty file content with appropriate messaging.
     */
    private String formatEmptyFileContent(VirtualFile file, String content) {
        StringBuilder result = new StringBuilder();
        
        // File header
        result.append("📄 File: ").append(file.getName()).append("\n");
        result.append("📁 Path: ").append(file.getPath()).append("\n");
        result.append("📏 Size: ").append(formatFileSize(file.getLength()));
        result.append(" | Lines: ").append(countLines(content != null ? content : "")).append("\n");
        result.append("─".repeat(70)).append("\n\n");

        // Empty file messaging
        if (file.getLength() == 0) {
            result.append("📋 File Status: Empty File (0 bytes)\n\n");
            result.append("This file contains no content. It may be:\n");
            result.append("- A newly created file\n");
            result.append("- A placeholder file\n");
            result.append("- A file that was cleared of content\n\n");
            result.append("✅ Empty file detected and stored for test generation context.");
        } else {
            result.append("📋 File Status: Content appears empty or contains only whitespace\n\n");
            result.append("This file has ").append(file.getLength()).append(" bytes but no visible content. It may contain:\n");
            result.append("- Only whitespace characters (spaces, tabs, newlines)\n");
            result.append("- Non-printable characters\n");
            result.append("- Content in an unsupported encoding\n\n");
            
            if (content != null && !content.isEmpty()) {
                result.append("Raw content preview (first 200 chars):\n");
                result.append("```\n");
                result.append(escapeControlChars(content.substring(0, Math.min(200, content.length()))));
                result.append("\n```\n\n");
            }
            
            result.append("✅ File content stored for test generation context (may need encoding review).");
        }
        
        return result.toString();
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
            // Show meaningful preview for large files
            String preview = getMeaningfulPreview(content, 1000);
            result.append("Content (preview - showing meaningful content):\n");
            result.append("```").append(getFileExtension(file)).append("\n");
            result.append(preview);
            if (content.length() > 1000) {
                result.append("\n...\n[Content truncated - ");
                result.append(content.length() - preview.length()).append(" more characters]");
            }
            result.append("\n```\n\n");
            result.append("✅ Full content has been stored.");
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
     * Gets a meaningful preview of content, skipping empty lines and comments at the start.
     */
    private String getMeaningfulPreview(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        String[] lines = content.split("\n");
        StringBuilder preview = new StringBuilder();
        boolean foundMeaningfulContent = false;
        int currentLength = 0;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // Skip completely empty lines at the start
            if (!foundMeaningfulContent && trimmedLine.isEmpty()) {
                continue;
            }
            
            // Skip comment-only lines at the start (common patterns)
            if (!foundMeaningfulContent && isCommentLine(trimmedLine)) {
                continue;
            }
            
            // We found meaningful content, include everything from here
            foundMeaningfulContent = true;
            
            // Check if adding this line would exceed max length
            int lineLength = line.length() + 1; // +1 for newline
            if (currentLength + lineLength > maxLength && preview.length() > 0) {
                break;
            }
            
            if (preview.length() > 0) {
                preview.append("\n");
                currentLength++;
            }
            preview.append(line);
            currentLength += line.length();
        }
        
        // Fallback: if no meaningful content found, just take the first maxLength chars
        if (!foundMeaningfulContent || preview.length() == 0) {
            return content.substring(0, Math.min(maxLength, content.length()));
        }
        
        return preview.toString();
    }
    
    /**
     * Checks if a line is primarily a comment line.
     */
    private boolean isCommentLine(String trimmedLine) {
        if (trimmedLine.isEmpty()) return false;
        
        // Common comment patterns
        return trimmedLine.startsWith("//") ||          // Java, C++, JS
               trimmedLine.startsWith("#") ||           // Python, Bash, etc.
               trimmedLine.startsWith("/*") ||          // Java, C++ block comment start
               trimmedLine.startsWith("*") ||           // Java, C++ block comment middle
               trimmedLine.startsWith("<!--") ||        // HTML, XML
               trimmedLine.startsWith("--") ||          // SQL, Haskell
               trimmedLine.startsWith("%") ||           // LaTeX, Matlab
               (trimmedLine.startsWith("\"\"\"") && trimmedLine.length() > 3); // Python docstring
    }

    /**
     * Escapes control characters for better display in text output.
     */
    private String escapeControlChars(String input) {
        if (input == null) return "";
        
        return input
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replaceAll("[\\p{Cntrl}&&[^\t\n\r]]", "?"); // Replace other control chars with ?
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

        // Last resort: Use ripgrep to find the file by pattern
        return findFileWithRipgrep(pathOrFqn);
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
     * Use ripgrep as last resort to find file by pattern.
     */
    @Nullable 
    private VirtualFile findFileWithRipgrep(String pathOrFqn) {
        try {
            // Create ripgrep instance for file finding
            RipgrepCodeTool ripgrep = new RipgrepCodeTool(project, new java.util.HashSet<>(), new java.util.ArrayList<>());
            
            // Convert path to glob pattern
            String pattern = pathOrFqn.contains("/") || pathOrFqn.contains("\\") ? 
                "**/" + pathOrFqn.replace('\\', '/') :  // Path pattern
                "**/*" + pathOrFqn + "*";              // Name pattern
            
            // Use ripgrep to find files matching pattern
            String ripgrepResult = ripgrep.findFiles( pattern);
            
            // Extract first matching file path
            return extractFirstFileFromRipgrepResult(ripgrepResult);
            
        } catch (Exception e) {
            // Ripgrep fallback failed
            return null;
        }
    }
    
    /**
     * Extract first VirtualFile from ripgrep search results.
     */
    @Nullable
    private VirtualFile extractFirstFileFromRipgrepResult(String ripgrepOutput) {
        if (ripgrepOutput.contains("No results found") || ripgrepOutput.contains("❌")) {
            return null;
        }
        
        String[] lines = ripgrepOutput.split("\n");
        for (String line : lines) {
            // Look for file path patterns in ripgrep output (format: "N. 📄 path:line")
            if (line.matches("\\d+\\. 📄 .*:\\d+")) {
                String filePart = line.substring(line.indexOf("📄 ") + 2);
                String filePath = filePart.substring(0, filePart.lastIndexOf(":"));
                
                // Convert to VirtualFile
                VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
                if (file != null && file.exists()) {
                    return file;
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