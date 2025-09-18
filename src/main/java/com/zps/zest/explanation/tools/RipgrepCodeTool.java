package com.zps.zest.explanation.tools;

import com.intellij.openapi.project.Project;
import dev.langchain4j.agent.tool.Tool;
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
     * Search for patterns INSIDE file contents across the project using native ripgrep.
     * This searches the text content of files, not the file names themselves.
     */
    @Tool("""
        Search for patterns INSIDE file contents across the entire project using high-performance ripgrep.
        This searches the text content of files, not file names. Use findFiles() to search file names.
        Perfect for finding function calls, class definitions, imports, or any text patterns.

        Parameters:
        - query: The search pattern (supports regex) to find in file contents
        - filePattern: Optional glob pattern to filter files (e.g., "*.java", "**/*.kt")
        - excludePattern: Optional pattern to exclude files (e.g., "test", "*.min.js")
        - beforeLines: Number of lines to show before each match (0-10, default: 0)
        - afterLines: Number of lines to show after each match (0-10, default: 0)

        Examples:
        - searchCode("getUserById", "*.java", null, 0, 0) - Find getUserById text in Java files
        - searchCode("import.*React", "*.tsx", null, 2, 2) - Find React imports with 2 lines context
        - searchCode("@Override", null, "test", 1, 3) - Find @Override with 1 line before, 3 after
        """)
    public String searchCode(String query, @Nullable String filePattern, @Nullable String excludePattern,
                           int beforeLines, int afterLines) {
        if (query == null || query.trim().isEmpty()) {
            return "Error: Search query cannot be empty";
        }
        // Validate context lines are within reasonable bounds
        beforeLines = Math.max(0, Math.min(10, beforeLines));
        afterLines = Math.max(0, Math.min(10, afterLines));

        return searchWithRipgrep(query, filePattern, excludePattern, beforeLines, afterLines, false);
    }

    /**
     * Backward compatibility overload - searches without context lines
     */
    public String searchCode(String query, @Nullable String filePattern, @Nullable String excludePattern) {
        return searchCode(query, filePattern, excludePattern, 0, 0);
    }
    
    /**
     * Find files by NAME/PATH matching a glob pattern (searches file names, not contents).
     * This is different from searchCode() which searches inside file contents.
     */
    @Tool("""
        Find files by NAME/PATH that match a specific glob pattern.
        This searches for file names/paths, NOT file contents. Use searchCode() to search inside files.
        Perfect for exploring project structure or finding specific file types.

        Parameters:
        - globPattern: Glob pattern to match file names/paths (e.g., "*.java", "**/*.ts", "src/**/*.properties")

        Examples:
        - findFiles("*.java") - Find all files with .java extension in project root
        - findFiles("**/*.kt") - Find all Kotlin files recursively by name
        - findFiles("src/**/application*.yml") - Find files named application*.yml in src
        - findFiles("*Test*") - Find all files with "Test" in their name
        """)
    public String findFiles(String globPattern) {
        if (globPattern == null || globPattern.trim().isEmpty()) {
            globPattern = "*"; // Default to all files if no pattern provided
        }
        return findFilesWithRipgrep(globPattern);
    }
    
    // Removed searchCodeWithContext, searchWithBeforeContext, and searchWithAfterContext
    // These are now handled by the unified searchCode method with beforeLines/afterLines parameters
    
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

        System.out.println("[RipgrepCodeTool] Starting ripgrep binary search...");

        // Try bundled binary first
        String bundledPath = extractBundledRipgrep();
        if (bundledPath != null) {
            System.out.println("[RipgrepCodeTool] Found bundled ripgrep: " + bundledPath);
            ripgrepPath = bundledPath;
            return ripgrepPath;
        }

        // Fallback to system ripgrep
        String systemPath = findSystemRipgrep();
        if (systemPath != null) {
            System.out.println("[RipgrepCodeTool] Found system ripgrep: " + systemPath);
            ripgrepPath = systemPath;
            return ripgrepPath;
        }

        System.out.println("[RipgrepCodeTool] No ripgrep binary found!");
        return null;
    }
    
    /**
     * Extract bundled ripgrep binary for current platform
     */
    private String extractBundledRipgrep() {
        try {
            String platform = detectPlatform();
            String resourcePath = "/bin/rg-" + platform + (platform.contains("windows") ? ".exe" : "");

            System.out.println("[RipgrepCodeTool] Looking for bundled binary at: " + resourcePath);

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
                System.out.println("[RipgrepCodeTool] Using existing extracted binary: " + extractedPath);
                ripgrepPath = extractedPath.toString();
                return ripgrepPath;
            }

            // Extract binary
            System.out.println("[RipgrepCodeTool] Extracting bundled binary to: " + extractedPath);
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
        // Log the command for debugging
        System.out.println("[RipgrepCodeTool] Executing command: " + String.join(" ", command));

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
        
        // Handle ripgrep exit codes:
        // 0 = match found
        // 1 = no match found (not an error)
        // 2+ = error occurred
        int exitCode = process.exitValue();

        if (exitCode == 1) {
            // No matches found - this is not an error
            return String.format("No results found for: '%s'", query);
        }

        if (exitCode == 2) {
            // Parse error output for better error message
            String output = jsonOutput.toString().trim();
            if (output.contains("regex parse error")) {
                return String.format("Error: Invalid regex pattern: '%s'\nDetails: %s", query, output);
            }
            return String.format("Error: Ripgrep failed with pattern '%s'\nDetails: %s", query, output);
        }

        if (exitCode != 0) {
            return String.format("Error: Ripgrep failed with exit code %d for query: '%s'", exitCode, query);
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

            // Fix: lines is an object, not an array
            JsonObject lines = data.getAsJsonObject("lines");
            // Fix: line_number is at data level, not in lines
            int lineNumber = data.get("line_number").getAsInt();
            String lineText = lines.get("text").getAsString();

            return new RipgrepMatch(filePath, lineNumber, lineText);

        } catch (Exception e) {
            System.err.println("[RipgrepCodeTool] Error parsing match: " + e.getMessage());
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