package com.zps.zest.git;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.utils.GitCommandExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper class for GitService to reduce code duplication and improve maintainability.
 */
public class GitServiceHelper {
    private static final Logger LOG = Logger.getInstance(GitServiceHelper.class);
    private static final Gson gson = new Gson();
    
    // Cache configuration
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Map<String, CachedDiff> diffCache = new ConcurrentHashMap<>();
    
    /**
     * Creates a success response with optional data.
     */
    public static JsonObject createSuccessResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        return response;
    }
    
    /**
     * Creates a success response with a message.
     */
    public static JsonObject createSuccessResponse(String message) {
        JsonObject response = createSuccessResponse();
        response.addProperty("message", message);
        return response;
    }
    
    /**
     * Creates an error response.
     */
    public static JsonObject createErrorResponse(String error) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", error);
        return response;
    }
    
    /**
     * Creates an error response from an exception.
     */
    public static JsonObject createErrorResponse(Exception e) {
        LOG.error("Error in GitService", e);
        return createErrorResponse("Error: " + e.getMessage());
    }
    
    /**
     * Converts a JsonObject to JSON string.
     */
    public static String toJson(JsonObject obj) {
        return gson.toJson(obj);
    }
    
    /**
     * Executes a git command and returns the output.
     */
    public static String executeGitCommand(String workingDir, String command) throws Exception {
        return GitCommandExecutor.executeWithGenericException(workingDir, command);
    }

    /**
     * Executes a git command and returns the output.
     * @param expectNonZeroExit Whether non-zero exit codes are expected (e.g., for git check-ignore)
     */
    public static String executeGitCommand(String workingDir, String command, boolean expectNonZeroExit) throws Exception {
        return GitCommandExecutor.executeWithGenericException(workingDir, command, expectNonZeroExit);
    }
    
    /**
     * Gets project path from a Project instance.
     */
    public static String getProjectPath(Project project) {
        if (project == null || project.getBasePath() == null) {
            throw new IllegalStateException("Project or project base path is null");
        }
        return project.getBasePath();
    }
    
    /**
     * Checks if a file is truly new (untracked).
     */
    public static boolean isNewFile(String projectPath, String filePath) {
        try {
            String result = executeGitCommand(projectPath,
                "git ls-files -- " + GitCommandExecutor.escapeFilePath(filePath));
            return result.trim().isEmpty();
        } catch (Exception e) {
            LOG.warn("Error checking if file is new: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a file is ignored by .gitignore using git check-ignore command.
     *
     * @param projectPath The root path of the git repository
     * @param filePath The file path to check (relative to project root)
     * @return true if the file is ignored, false otherwise
     */
    public static boolean isFileIgnored(String projectPath, String filePath) {
        try {
            // Use git check-ignore to test if file is ignored
            // Exit code 0 means the file is ignored
            // Exit code 1 means the file is not ignored (this is expected, not an error)
            String result = executeGitCommand(projectPath,
                "git check-ignore -- " + GitCommandExecutor.escapeFilePath(filePath), true);

            // If command succeeds and returns the file path, it's ignored
            return result != null && result.trim().equals(filePath.trim());

        } catch (Exception e) {
            // git check-ignore returns exit code 1 (exception) for non-ignored files
            // This is the normal case for non-ignored files
            String message = e.getMessage();
            if (message != null && message.contains("exit code 1")) {
                // Exit code 1 means file is not ignored - this is expected
                return false;
            }

            // For other errors, log warning but assume not ignored to be safe
            LOG.debug("Error checking if file is ignored (assuming not ignored): " + e.getMessage());
            return false;
        }
    }

    /**
     * Filters out ignored files from a list of file paths.
     *
     * @param projectPath The root path of the git repository
     * @param filePaths List of file paths to filter
     * @return List of file paths that are not ignored
     */
    public static java.util.List<String> filterIgnoredFiles(String projectPath, java.util.List<String> filePaths) {
        return filePaths.stream()
            .filter(filePath -> !isFileIgnored(projectPath, filePath))
            .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Gets diff from cache if available and not expired.
     */
    public static CachedDiff getCachedDiff(String cacheKey) {
        CachedDiff cached = diffCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LOG.info("Using cached diff for: " + cacheKey);
            return cached;
        }
        if (cached != null) {
            diffCache.remove(cacheKey);
        }
        return null;
    }
    
    /**
     * Caches a diff result.
     */
    public static void cacheDiff(String cacheKey, String diff) {
        diffCache.put(cacheKey, new CachedDiff(diff, Instant.now()));
        LOG.info("Cached diff for: " + cacheKey);
    }
    
    /**
     * Clears the diff cache.
     */
    public static void clearDiffCache() {
        int size = diffCache.size();
        diffCache.clear();
        LOG.info("Cleared diff cache (" + size + " entries)");
    }
    
    /**
     * Cleans file path by removing project name prefix if present.
     */
    public static String cleanFilePath(String filePath, String projectName) {
        if (filePath == null || filePath.isEmpty()) return "";

        // Remove project name prefix if present
        if (projectName != null && !projectName.isEmpty()) {
            String prefix = projectName + "/";
            if (filePath.startsWith(prefix)) {
                return filePath.substring(prefix.length());
            }
        }
        
        // Handle duplicated project name in path
        String[] parts = filePath.split("/");
        if (parts.length > 1 && parts[0].equals(parts[1])) {
            return String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        }
        
        // Remove project name from beginning
        if (projectName != null && parts.length > 0 && parts[0].equals(projectName)) {
            return String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        }
        
        return filePath;
    }
    
    /**
     * Creates a cache key for diff operations.
     */
    public static String createDiffCacheKey(String projectPath, String filePath, String status) {
        return projectPath + ":" + filePath + ":" + status;
    }
    
    /**
     * Cached diff data structure.
     */
    public static class CachedDiff {
        public final String diff;
        public final Instant timestamp;
        
        public CachedDiff(String diff, Instant timestamp) {
            this.diff = diff;
            this.timestamp = timestamp;
        }
        
        public boolean isExpired() {
            return Duration.between(timestamp, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
    
    /**
     * Escapes strings for JavaScript string literals.
     */
    public static String escapeJsString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
    }
    
    /**
     * Escapes strings for shell commands.
     */
    public static String escapeForShell(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}