package com.zps.zest.explanation.tools;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// JSON processing for ripgrep output
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Professional grep-based code search tool using native ripgrep for maximum performance.
 * 
 * Features:
 * - Native ripgrep performance (when available) with bundled binaries
 * - Full regex and glob pattern support
 * - Context lines support (-A, -B, -C)
 * - JSON output parsing for structured results
 * - Cross-platform Windows/macOS/Linux support
 */
public class RipgrepCodeTool {
    private final Project project;
    private final Set<String> relatedFiles;
    private final List<String> usagePatterns;
    private static final int MAX_RESULTS = 20;
    
    // Ripgrep binary detection cache
    private static String ripgrepPath = null;
    private static boolean ripgrepSearched = false;
    
    public RipgrepCodeTool(@NotNull Project project, @NotNull Set<String> relatedFiles, @NotNull List<String> usagePatterns) {
        this.project = project;
        this.relatedFiles = relatedFiles;
        this.usagePatterns = usagePatterns;
    }

    /**
     * Search for code patterns across the project using native ripgrep.
     */
    public String searchCode(String query, @Nullable String filePattern, @Nullable String excludePattern) {
        return searchWithRipgrep(query, filePattern, excludePattern, 0, 0, false);
    }
    
    /**
     * Find files matching a glob pattern (no content search, just file listing).
     */
    public String findFiles(String globPattern) {
        return findFilesWithRipgrep(globPattern);
    }
    
    /**
     * Search with context lines (like rg -C)
     */
    public String searchCodeWithContext(String query, @Nullable String filePattern, @Nullable String excludePattern, int contextLines) {
        return searchWithRipgrep(query, filePattern, excludePattern, contextLines, contextLines, false);
    }
    
    /**
     * Search with before context (like rg -B)
     */
    public String searchWithBeforeContext(String query, @Nullable String filePattern, @Nullable String excludePattern, int beforeLines) {
        return searchWithRipgrep(query, filePattern, excludePattern, beforeLines, 0, false);
    }
    
    /**
     * Search with after context (like rg -A)  
     */
    public String searchWithAfterContext(String query, @Nullable String filePattern, @Nullable String excludePattern, int afterLines) {
        return searchWithRipgrep(query, filePattern, excludePattern, 0, afterLines, false);
    }
    
    /**
     * Core ripgrep search implementation
     */
    private String searchWithRipgrep(String query, @Nullable String filePattern, @Nullable String excludePattern,
                                   int beforeLines, int afterLines, boolean caseSensitive) {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return "Error: Could not determine project path";
            }
            
            // Find ripgrep binary (bundled or system)
            String rgPath = findRipgrepBinary();
            if (rgPath == null) {
                return "❌ Ripgrep not available.\n\n" +
                       "📥 Ripgrep provides blazing fast search with full regex and glob support.\n" +
                       "Install options:\n" +
                       "• Windows: winget install BurntSushi.ripgrep.MSVC\n" +
                       "• Windows: choco install ripgrep\n" +
                       "• Manual: https://github.com/BurntSushi/ripgrep/releases\n\n" +
                       "Note: Zest will auto-bundle ripgrep in future releases.";
            }
            
            // Build and execute ripgrep command
            List<String> command = buildRipgrepCommand(rgPath, query, projectPath, filePattern, excludePattern, 
                                                     beforeLines, afterLines, caseSensitive);
            return executeRipgrepCommand(command, query);
            
        } catch (Exception e) {
            return String.format("Error executing ripgrep: %s", e.getMessage());
        }
    }
    
    /**
     * Find ripgrep binary - check bundled first, then system
     */
    private String findRipgrepBinary() {
        if (ripgrepSearched) {
            return ripgrepPath;
        }
        
        ripgrepSearched = true;
        
        // Try bundled binary first
        String bundledPath = extractBundledRipgrep();
        if (bundledPath != null) {
            ripgrepPath = bundledPath;
            return ripgrepPath;
        }
        
        // Fallback to system ripgrep
        String systemPath = findSystemRipgrep();
        if (systemPath != null) {
            ripgrepPath = systemPath;
            return ripgrepPath;
        }
        
        return null;
    }
    
    /**
     * Extract bundled ripgrep binary for current platform
     */
    private String extractBundledRipgrep() {
        try {
            String platform = detectPlatform();
            String resourcePath = "/bin/rg-" + platform + (platform.contains("windows") ? ".exe" : "");
            
            // Check if bundled binary exists
            InputStream bundledBinary = getClass().getResourceAsStream(resourcePath);
            if (bundledBinary == null) {
                return null; // No bundled binary
            }
            
            // Create persistent directory in AppData instead of temp
            Path appDataDir = getAppDataDirectory();
            String binaryName = platform.contains("windows") ? "rg-windows-64.exe" : "rg";
            Path extractedPath = appDataDir.resolve(binaryName);
            
            // Check if already extracted and valid
            if (Files.exists(extractedPath) && Files.isExecutable(extractedPath)) {
                ripgrepPath = extractedPath.toString();
                return ripgrepPath;
            }
            
            // Extract binary
            Files.copy(bundledBinary, extractedPath, StandardCopyOption.REPLACE_EXISTING);
            bundledBinary.close();
            
            // Make executable on Unix
            if (!platform.contains("windows")) {
                Files.setPosixFilePermissions(extractedPath, 
                    Set.of(PosixFilePermission.OWNER_READ,
                           PosixFilePermission.OWNER_WRITE,
                           PosixFilePermission.OWNER_EXECUTE));
            }
            
            // Verify works
            if (isCommandAvailable(extractedPath.toString())) {
                return extractedPath.toString();
            }
            
        } catch (Exception e) {
            // Extraction failed
        }
        
        return null;
    }
    
    /**
     * Get persistent AppData directory for storing ripgrep binary.
     */
    private Path getAppDataDirectory() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        Path appDataPath;
        
        if (os.contains("windows")) {
            // Windows: %APPDATA%\Zest\tools
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                appDataPath = Paths.get(appData, "Zest", "tools");
            } else {
                // Fallback to user home
                appDataPath = Paths.get(System.getProperty("user.home"), ".zest", "tools");
            }
        } else if (os.contains("mac")) {
            // macOS: ~/Library/Application Support/Zest/tools
            appDataPath = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Zest", "tools");
        } else {
            // Linux: ~/.local/share/zest/tools
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null) {
                appDataPath = Paths.get(xdgDataHome, "zest", "tools");
            } else {
                appDataPath = Paths.get(System.getProperty("user.home"), ".local", "share", "zest", "tools");
            }
        }
        
        // Create directories if they don't exist
        Files.createDirectories(appDataPath);
        return appDataPath;
    }
    
    /**
     * Detect platform for binary selection
     */
    private String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        
        if (os.contains("windows")) {
            return "windows-x64"; // Most common
        } else if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") ? "macos-arm64" : "macos-x64";
        } else if (os.contains("linux")) {
            return "linux-x64";
        }
        
        return "unknown";
    }
    
    /**
     * Find system-installed ripgrep
     */
    private String findSystemRipgrep() {
        String[] possibleNames = {"rg", "rg-windows-64.exe"};
        
        // Check PATH
        for (String name : possibleNames) {
            if (isCommandAvailable(name)) {
                return name;
            }
        }
        
        return null;
    }
    
    /**
     * Check if command is available
     */
    private boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Build ripgrep command with flags
     */
    private List<String> buildRipgrepCommand(String rgPath, String query, String projectPath, 
                                           @Nullable String filePattern, @Nullable String excludePattern,
                                           int beforeLines, int afterLines, boolean caseSensitive) {
        List<String> command = new ArrayList<>();
        command.add(rgPath);
        
        // Output options
        command.add("--json");        // JSON output for parsing
        command.add("--line-number"); // Include line numbers
        command.add("--no-heading");  // No file headers
        
        // Context options
        if (beforeLines > 0) {
            command.add("--before-context");
            command.add(String.valueOf(beforeLines));
        }
        if (afterLines > 0) {
            command.add("--after-context");
            command.add(String.valueOf(afterLines));
        }
        
        // Case sensitivity
        if (!caseSensitive) {
            command.add("--ignore-case");
        }
        
        // File patterns
        if (filePattern != null && !filePattern.isEmpty()) {
            command.add("--glob");
            command.add(filePattern);
        }
        
        // Exclude patterns  
        if (excludePattern != null && !excludePattern.isEmpty()) {
            String[] excludes = excludePattern.split("[,;]");
            for (String exclude : excludes) {
                exclude = exclude.trim();
                if (!exclude.isEmpty()) {
                    command.add("--glob");
                    command.add("!" + exclude);
                }
            }
        }
        
        // Performance options
        command.add("--max-count");
        command.add(String.valueOf(MAX_RESULTS));
        
        // Query and path
        command.add(query);
        command.add(projectPath);
        
        return command;
    }
    
    /**
     * Execute ripgrep and parse results
     */
    private String executeRipgrepCommand(List<String> command, String query) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        StringBuilder jsonOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonOutput.append(line).append("\n");
            }
        }
        
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "Error: Ripgrep search timed out";
        }
        
        if (process.exitValue() == 1) {
            return String.format("No results found for: '%s'", query);
        }
        
        if (process.exitValue() != 0) {
            return "Error: Ripgrep failed with exit code: " + process.exitValue();
        }
        
        return parseRipgrepOutput(jsonOutput.toString(), query);
    }
    
    /**
     * Parse ripgrep JSON output
     */
    private String parseRipgrepOutput(String jsonOutput, String query) {
        List<RipgrepMatch> matches = new ArrayList<>();
        Gson gson = new Gson();
        
        String[] lines = jsonOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            try {
                JsonObject json = gson.fromJson(line, JsonObject.class);
                if (json.has("type") && "match".equals(json.get("type").getAsString())) {
                    RipgrepMatch match = parseMatch(json);
                    if (match != null) {
                        matches.add(match);
                        relatedFiles.add(match.filePath);
                        recordUsagePattern(match.lineText, query);
                    }
                }
            } catch (Exception e) {
                continue; // Skip malformed JSON
            }
        }
        
        return formatResults(query, matches);
    }
    
    /**
     * Parse single match from JSON
     */
    private RipgrepMatch parseMatch(JsonObject json) {
        try {
            JsonObject data = json.getAsJsonObject("data");
            String filePath = data.getAsJsonObject("path").get("text").getAsString();
            
            JsonArray lines = data.getAsJsonArray("lines");
            JsonObject line = lines.get(0).getAsJsonObject();
            int lineNumber = line.get("line_number").getAsInt();
            String lineText = line.get("text").getAsString();
            
            return new RipgrepMatch(filePath, lineNumber, lineText);
            
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Format results for display
     */
    private String formatResults(String query, List<RipgrepMatch> matches) {
        if (matches.isEmpty()) {
            return String.format("No results found for: '%s'", query);
        }
        
        StringBuilder output = new StringBuilder();
        output.append(String.format("🔍 Found %d result(s) for: '%s' (ripgrep)\n", matches.size(), query));
        output.append("═".repeat(60)).append("\n\n");
        
        int count = 0;
        for (RipgrepMatch match : matches) {
            if (count >= MAX_RESULTS) break;
            
            String relativePath = getRelativePath(match.filePath);
            output.append(String.format("%d. 📄 %s:%d\n", count + 1, relativePath, match.lineNumber));
            output.append("   ").append(highlightMatch(match.lineText, query)).append("\n\n");
            
            count++;
        }
        
        return output.toString();
    }
    
    /**
     * Highlight query in line text
     */
    private String highlightMatch(String line, String query) {
        try {
            return line.replaceAll("(?i)" + java.util.regex.Pattern.quote(query), ">>>" + query + "<<<");
        } catch (Exception e) {
            return line;
        }
    }
    
    /**
     * Get relative path for display
     */
    private String getRelativePath(String filePath) {
        String projectPath = project.getBasePath();
        if (projectPath != null && filePath.startsWith(projectPath)) {
            String relative = filePath.substring(projectPath.length());
            return relative.startsWith("/") || relative.startsWith("\\") ? 
                relative.substring(1).replace('\\', '/') : 
                relative.replace('\\', '/');
        }
        return filePath.replace('\\', '/');
    }
    
    /**
     * Record usage patterns
     */
    private void recordUsagePattern(String line, String query) {
        String lowerLine = line.toLowerCase();
        String lowerQuery = query.toLowerCase();
        
        if (lowerLine.contains("import") && lowerLine.contains(lowerQuery)) {
            usagePatterns.add("Import: " + line.trim());
        } else if (lowerLine.contains("@") && lowerLine.contains(lowerQuery)) {
            usagePatterns.add("Annotation: " + line.trim());
        } else if (lowerLine.contains(lowerQuery + "(")) {
            usagePatterns.add("Method call: " + line.trim());
        }
    }
    
    /**
     * Find files using ripgrep's --files flag with glob patterns.
     */
    private String findFilesWithRipgrep(String globPattern) {
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return "Error: Could not determine project path";
            }
            
            // Find ripgrep binary
            String rgPath = findRipgrepBinary();
            if (rgPath == null) {
                return "❌ Ripgrep not available for file finding.\nFalling back to basic search...";
            }
            
            // Build ripgrep --files command (no content search, just file listing)
            List<String> command = new ArrayList<>();
            command.add(rgPath);
            command.add("--files");           // List files, don't search content
            command.add("--glob");
            command.add(globPattern);
            command.add(projectPath);
            
            // Execute command
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Ripgrep file listing timed out";
            }
            
            if (process.exitValue() != 0) {
                return String.format("No files found matching glob pattern: '%s'", globPattern);
            }
            
            return formatFileListResults(output.toString(), globPattern);
            
        } catch (Exception e) {
            return String.format("Error using ripgrep for file finding: %s", e.getMessage());
        }
    }
    
    /**
     * Format file listing results from ripgrep --files.
     */
    private String formatFileListResults(String output, String pattern) {
        String[] filePaths = output.trim().split("\n");
        
        if (filePaths.length == 0 || (filePaths.length == 1 && filePaths[0].trim().isEmpty())) {
            return String.format("No files found matching pattern: '%s'", pattern);
        }
        
        StringBuilder result = new StringBuilder();
        result.append(String.format("🔍 Found %d file(s) matching pattern: '%s' (ripgrep --files)\n", 
                                   filePaths.length, pattern));
        result.append("═".repeat(60)).append("\n\n");
        
        for (String filePath : filePaths) {
            if (!filePath.trim().isEmpty()) {
                String relativePath = getRelativePath(filePath);
                result.append("📄 ").append(relativePath).append("\n");
                result.append("   Full path: ").append(filePath).append("\n\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * Ripgrep match result
     */
    private static class RipgrepMatch {
        final String filePath;
        final int lineNumber;
        final String lineText;
        
        RipgrepMatch(String filePath, int lineNumber, String lineText) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.lineText = lineText;
        }
    }
}