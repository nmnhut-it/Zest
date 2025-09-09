package com.zps.zest.testgen.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tool for finding files by name pattern within the project.
 * Supports wildcard patterns and various search strategies.
 */
public class FindFilesTool {
    private final Project project;
    private final CodeExplorationToolRegistry toolRegistry;

    public FindFilesTool(@NotNull Project project, @NotNull CodeExplorationToolRegistry toolRegistry) {
        this.project = project;
        this.toolRegistry = toolRegistry;
    }

    @Tool("""
        Find files by name pattern across the entire project.
        This tool searches for files matching the specified pattern and returns their locations.
        
        Parameters:
        - pattern: The file name pattern to search for. Supports:
          * Exact names: "application.properties"
          * Wildcards: "*.xml", "Test*.java", "*Config*"
          * Partial names: "User" (will find UserService.java, UserDao.java, etc.)
          * Extensions: ".properties" (finds all properties files)
        
        Returns: A list of matching files with their full paths, or a message if no files are found.
        
        Tips:
        - Use wildcards (*) to broaden your search
        - Search is case-insensitive by default
        - Returns up to 50 matches to avoid overwhelming output
        
        Example usage:
        - findFiles("*.properties")  // Find all properties files
        - findFiles("*Test.java")    // Find all test files
        - findFiles("application")   // Find files with "application" in the name
        - findFiles("pom.xml")       // Find specific file
        """)
    public String findFiles(String pattern) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // First try using the CodeExplorationTool if available
                CodeExplorationTool findTool = toolRegistry.getTool("find_file");
                if (findTool != null) {
                    JsonObject params = new JsonObject();
                    params.addProperty("pattern", normalizePattern(pattern));

                    CodeExplorationTool.ToolResult result = findTool.execute(params);
                    if (result.isSuccess()) {
                        return formatFindResult(pattern, result.getContent());
                    }
                }

                // Fallback to ripgrep-based search for better path pattern support
                return findFilesWithRipgrep(pattern);
                
            } catch (Exception e) {
                return String.format("Error searching for files with pattern '%s': %s\n" +
                                   "Try a different pattern or check if the project is properly indexed.",
                                   pattern, e.getMessage());
            }
        });
    }

    /**
     * Directly searches for files using IntelliJ's FilenameIndex.
     */
    private String findFilesDirectly(String pattern) {
        Collection<VirtualFile> matchingFiles;
        
        // Convert pattern to regex for matching
        String searchPattern = convertToSearchPattern(pattern);
        
        if (pattern.contains("*") || pattern.contains("?")) {
            // Wildcard search - get all files and filter
            matchingFiles = FilenameIndex.getAllFilesByExt(project, extractExtension(pattern))
                    .stream()
                    .filter(file -> matchesPattern(file.getName(), searchPattern))
                    .limit(50)
                    .collect(Collectors.toList());
        } else {
            // Exact or partial name search
            matchingFiles = FilenameIndex.getVirtualFilesByName(
                    pattern, 
                    GlobalSearchScope.projectScope(project)
            );
            
            // If no exact match, try partial match
            if (matchingFiles.isEmpty()) {
                @NotNull String[] allFilenames = FilenameIndex.getAllFilenames(project);
                matchingFiles = Arrays.stream(allFilenames)
                        .filter(name -> name.toLowerCase().contains(pattern.toLowerCase()))
                        .flatMap(name -> FilenameIndex.getVirtualFilesByName(
                                name, 
                                GlobalSearchScope.projectScope(project)
                        ).stream())
                        .limit(50)
                        .collect(Collectors.toList());
            }
        }

        return formatSearchResults(pattern, matchingFiles);
    }

    /**
     * Normalize pattern for better search results.
     */
    private String normalizePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return "*"; // Default to all files
        }
        
        String normalized = pattern.trim();
        
        // Fix common malformed patterns
        if (normalized.equals("**.yml*")) {
            normalized = "**/*.yml"; // Fix malformed suggestion
        }
        
        // Convert simple extensions to proper glob patterns
        if (normalized.startsWith(".") && !normalized.contains("*")) {
            normalized = "*" + normalized; // .yml → *.yml
        }
        
        // Ensure recursive patterns are properly formatted
        if (normalized.startsWith("**/") || normalized.contains("**/")) {
            return normalized; // Already a proper glob pattern
        }
        
        // For patterns with path separators (like "config/file.lua"), make them recursive
        if (normalized.contains("/") || normalized.contains("\\")) {
            // If it's a specific file path, make it findable anywhere in project
            if (normalized.contains(".") && !normalized.startsWith("**/")) {
                return "**/" + normalized; // config/leaderboard.lua → **/config/leaderboard.lua
            }
        }
        
        // For simple patterns without path separators, make them recursive
        if (!normalized.contains("/") && !normalized.contains("\\")) {
            if (normalized.contains(".")) {
                // File with extension - make it findable anywhere
                return "**/" + normalized;
            }
        }
        
        return normalized;
    }

    /**
     * Converts a wildcard pattern to a regex pattern.
     */
    private String convertToSearchPattern(String pattern) {
        // Escape special regex characters except * and ?
        String regex = pattern.replaceAll("([.\\[\\]{}()+^$|\\\\])", "\\\\$1");
        // Convert wildcards to regex
        regex = regex.replace("*", ".*").replace("?", ".");
        return "(?i)" + regex; // Case insensitive
    }

    /**
     * Checks if a filename matches the given pattern.
     */
    private boolean matchesPattern(String filename, String pattern) {
        return filename.matches(pattern);
    }

    /**
     * Extracts file extension from a pattern.
     */
    private String extractExtension(String pattern) {
        int lastDot = pattern.lastIndexOf('.');
        if (lastDot > 0 && lastDot < pattern.length() - 1) {
            String ext = pattern.substring(lastDot + 1);
            // Remove wildcards from extension
            ext = ext.replace("*", "").replace("?", "");
            if (!ext.isEmpty()) {
                return ext;
            }
        }
        return "";
    }

    /**
     * Formats the search results for display.
     */
    private String formatSearchResults(String pattern, Collection<VirtualFile> files) {
        if (files.isEmpty()) {
            return String.format("No files found matching pattern: '%s'\n" +
                               "Suggestions:\n" +
                               "- For extensions: **/*.yml (recursive) or *.yml (current dir)\n" +
                               "- For partial names: **/*%s* (recursive search)\n" +
                               "- For exact names: %s or **/%s\n" +
                               "- Check if the file exists in the project\n" +
                               "- Ensure the project is properly indexed\n" +
                               "\nNote: Pattern was normalized to: '%s'",
                               pattern, 
                               pattern.startsWith(".") ? pattern : pattern,
                               pattern, pattern, 
                               normalizePattern(pattern));
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d file(s) matching pattern: '%s'\n", 
                                   files.size(), pattern));
        result.append("─".repeat(60)).append("\n");

        // Group files by directory for better organization
        files.stream()
                .collect(Collectors.groupingBy(file -> {
                    VirtualFile parent = file.getParent();
                    return parent != null ? getRelativePath(parent) : "root";
                }))
                .forEach((dir, dirFiles) -> {
                    result.append("\n📁 ").append(dir).append("\n");
                    dirFiles.forEach(file -> {
                        result.append("   📄 ").append(file.getName());
                        long size = file.getLength();
                        if (size > 0) {
                            result.append(" (").append(formatFileSize(size)).append(")");
                        }
                        result.append("\n");
                        result.append("      Full path: ").append(file.getPath()).append("\n");
                    });
                });

        if (files.size() >= 50) {
            result.append("\n⚠️ Results limited to 50 files. Use a more specific pattern to narrow the search.");
        }

        return result.toString();
    }
    
    /**
     * Use ripgrep to find files by path pattern - much better at glob patterns than FilenameIndex.
     */
    private String findFilesWithRipgrep(String pattern) {
        try {
            // Create RipgrepCodeTool instance and use its file finding capability
            RipgrepCodeTool ripgrep = new RipgrepCodeTool(project, new java.util.HashSet<>(), new java.util.ArrayList<>());
            String fileGlob = normalizePattern(pattern);
            
            // Use ripgrep's file listing method
            return ripgrep.findFiles(fileGlob);
            
        } catch (Exception e) {
            // Fallback to FilenameIndex approach
            return findFilesDirectly(pattern);
        }
    }
    
    /**
     * Parse ripgrep search results to extract file list.
     */
    private String parseRipgrepToFileList(String ripgrepOutput, String originalPattern) {
        if (ripgrepOutput.contains("No results found") || ripgrepOutput.contains("❌")) {
            return String.format("No files found matching pattern: '%s'\n" +
                               "Pattern was normalized to: '%s'\n" +
                               "Note: Ripgrep search completed but no files matched.",
                               originalPattern, normalizePattern(originalPattern));
        }
        
        // Extract unique file paths from ripgrep output
        Set<String> filePaths = new java.util.LinkedHashSet<>();
        String[] lines = ripgrepOutput.split("\n");
        
        for (String line : lines) {
            // Look for file path patterns in ripgrep output (format: "N. 📄 path:line")
            if (line.matches("\\d+\\. 📄 .*:\\d+")) {
                String filePart = line.substring(line.indexOf("📄 ") + 2);
                String filePath = filePart.substring(0, filePart.lastIndexOf(":"));
                filePaths.add(filePath);
            }
        }
        
        if (filePaths.isEmpty()) {
            return String.format("No files found matching pattern: '%s'\n" +
                               "Pattern was normalized to: '%s'",
                               originalPattern, normalizePattern(originalPattern));
        }
        
        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d file(s) matching pattern: '%s' (via ripgrep)\n", 
                                   filePaths.size(), originalPattern));
        result.append("─".repeat(60)).append("\n");
        
        filePaths.forEach(path -> {
            result.append("📄 ").append(path).append("\n");
        });
        
        return result.toString();
    }

    /**
     * Gets the relative path of a file from the project base.
     */
    private String getRelativePath(VirtualFile file) {
        String basePath = project.getBasePath();
        if (basePath != null) {
            String filePath = file.getPath();
            if (filePath.startsWith(basePath)) {
                return filePath.substring(basePath.length() + 1);
            }
        }
        return file.getPath();
    }

    /**
     * Formats the result from CodeExplorationTool.
     */
    private String formatFindResult(String pattern, String content) {
        return String.format("Search results for pattern: '%s'\n%s", pattern, content);
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