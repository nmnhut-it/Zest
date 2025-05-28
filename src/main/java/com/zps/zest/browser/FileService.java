package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.ReplaceInFileTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for handling file operations from JavaScript bridge.
 * This includes file replacement operations and batch processing.
 * PRESERVES ORIGINAL IMPLEMENTATIONS from JavaScriptBridge.
 */
public class FileService {
    private static final Logger LOG = Logger.getInstance(FileService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    public FileService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Handles replace in file requests.
     */
    public String replaceInFile(JsonObject data) {
        try {
            String filePath = data.get("filePath").getAsString();
            String searchText = data.get("search").getAsString();
            String replaceText = data.get("replace").getAsString();
            boolean useRegex = data.has("regex") && data.get("regex").getAsBoolean();
            boolean caseSensitive = !data.has("caseSensitive") || data.get("caseSensitive").getAsBoolean();
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleReplaceInFileInternal(filePath, searchText, replaceText, useRegex, caseSensitive);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling replace in file", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles batch replace in file requests.
     */
    public String batchReplaceInFile(JsonObject data) {
        try {
            String batchFilePath = data.get("filePath").getAsString();
            JsonArray replacements = data.getAsJsonArray("replacements");
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleBatchReplaceInFileInternal(batchFilePath, replacements);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles replace in file requests from JavaScript by delegating to the ReplaceInFileTool
     * This method is now asynchronous and doesn't block the UI.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private boolean handleReplaceInFileInternal(String filePath, String searchText, String replaceText, boolean useRegex, boolean caseSensitive) {
        LOG.info("Handling replace in file request for: " + filePath);

        try {
            // Create a JSON object with the parameters for the ReplaceInFileTool
            JsonObject params = new JsonObject();
            params.addProperty("filePath", filePath);
            params.addProperty("search", searchText);
            params.addProperty("replace", replaceText);
            params.addProperty("regex", useRegex);
            params.addProperty("caseSensitive", caseSensitive);

            AgentTool tool = new ReplaceInFileTool(project);
            if (tool == null) {
                LOG.error("Could not find replace_in_file tool");
                return false;
            }

            // Execute the tool
            String result = tool.execute(params);

            // Show result asynchronously
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showInfoMessage("Replace in file result: " + result, "Replace in File Result");
            });

            // Log the result
            LOG.info("Replace in file result: " + result);

            // Return success based on whether the result indicates success
            return !result.startsWith("Error:") && !result.contains("Changes were not applied") && !result.contains("No matches found");
        } catch (Exception e) {
            LOG.error("Error handling replace in file request", e);
            return false;
        }
    }
    
    /**
     * Handles batch replace in file requests from JavaScript by delegating to the ReplaceInFileTool multiple times
     * This method is now fully asynchronous and doesn't block the UI.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private boolean handleBatchReplaceInFileInternal(String filePath, JsonArray replacements) {
        LOG.info("Handling batch replace in file request for: " + filePath + " with " + replacements.size() + " replacements");

        // Always run on background thread
        try {
            // 1. Resolve file
            File targetFile = new File(filePath);
            if (!targetFile.exists()) {
                String basePath = project.getBasePath();
                if (basePath != null)
                    targetFile = new File(basePath, filePath);
            }
            if (!targetFile.exists() || !targetFile.isFile()) {
                LOG.error("File not found: " + filePath);
                return false;
            }
            Path inputPath = targetFile.toPath();

            // 2. Start with original lines/content
            List<String> currentLines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
            String originalContent = String.join("\n", currentLines);

            int totalReplacementCount = 0;

            for (int i = 0; i < replacements.size(); i++) {
                JsonObject replacement = replacements.get(i).getAsJsonObject();
                String searchText = replacement.get("search").getAsString();
                String replaceText = replacement.get("replace").getAsString();
                boolean caseSensitive = !replacement.has("caseSensitive") || replacement.get("caseSensitive").getAsBoolean();
                boolean ignoreWhitespace = replacement.has("ignoreWhitespace") && replacement.get("ignoreWhitespace").getAsBoolean();
                boolean useRegex = replacement.has("regex") && replacement.get("regex").getAsBoolean();

                if (useRegex) {
                    LOG.warn("Regex mode is not supported in batchReplaceInFile when using performSearchAndReplace. Skipping this replacement.");
                    continue;
                }

                // Write currentLines to a temp file for replace
                Path tempInput = Files.createTempFile("batch_replace_", ".tmp");
                Files.write(tempInput, currentLines, StandardCharsets.UTF_8);

                // Call performSearchAndReplace
                ReplaceInFileTool.ReplaceResult result =
                        ReplaceInFileTool.performSearchAndReplace(
                                tempInput,
                                searchText,
                                replaceText,
                                caseSensitive,
                                ignoreWhitespace,
                                new EmptyProgressIndicator()
                        );
                totalReplacementCount += result.replacementCount;
                currentLines = Arrays.asList(result.modifiedContent.split("\n", -1));

                // Clean up temp file
                Files.deleteIfExists(tempInput);
            }

            String modifiedContent = String.join("\n", currentLines);

            if (originalContent.equals(modifiedContent)) {
                LOG.info("No changes made to file content after applying all replacements");
                return true;
            }

            // Show diff and handle user interaction on EDT
            final File finalTargetFile = targetFile;
            final String finalModifiedContent = modifiedContent;
            final int finalTotalReplacementCount = totalReplacementCount;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                    DocumentContent leftContent = diffFactory.create(originalContent);
                    DocumentContent rightContent = diffFactory.create(finalModifiedContent);

                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            "Batch Changes to " + finalTargetFile.getName() + " (" + finalTotalReplacementCount + " replacements)",
                            leftContent,
                            rightContent,
                            "Original",
                            "After Replacements"
                    );

                    DiffManager.getInstance().showDiff(project, diffRequest, DiffDialogHints.MODAL);

                    int option = Messages.showYesNoDialog(
                            project,
                            "Apply " + finalTotalReplacementCount + " replacements to " + filePath + "?",
                            "Confirm Batch Changes",
                            "Apply",
                            "Cancel",
                            Messages.getQuestionIcon()
                    );

                    if (option == Messages.YES) {
                        // Perform file update in write action
                        VirtualFile vFile =
                                LocalFileSystem.getInstance().findFileByPath(finalTargetFile.getPath());
                        if (vFile == null) {
                            LOG.error("Could not find file in virtual file system: " + filePath);
                            return;
                        }

                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            try {
                                vFile.refresh(false, false);
                                vFile.setBinaryContent(finalModifiedContent.getBytes(StandardCharsets.UTF_8));
                                FileEditorManager.getInstance(project).openFile(vFile, true);
                            } catch (Exception e) {
                                LOG.error("Error updating file: " + filePath, e);
                            }
                        });
                    } else {
                        LOG.info("Batch changes were not applied - discarded by user");
                    }
                } catch (Exception e) {
                    LOG.error("Error showing diff dialog", e);
                }
            });

            return true; // Return immediately, don't wait for user interaction
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file request", e);
            return false;
        }
    }
    
    /**
     * Lists files in a directory recursively for the Research Agent
     */
    public String listFiles(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String path = data.has("path") ? data.get("path").getAsString() : "/";
                JsonArray excludePatterns = data.has("excludePatterns") ? 
                    data.getAsJsonArray("excludePatterns") : new JsonArray();
                JsonArray extensions = data.has("extensions") ? 
                    data.getAsJsonArray("extensions") : null;
                int maxDepth = data.has("maxDepth") ? data.get("maxDepth").getAsInt() : 5;
                
                VirtualFile baseDir = getProjectFile(path);
                if (baseDir == null || !baseDir.isDirectory()) {
                    return createErrorResponse("Directory not found: " + path);
                }
                
                Set<String> excludeSet = new HashSet<>();
                excludePatterns.forEach(e -> excludeSet.add(e.getAsString()));
                
                Set<String> extensionSet;
                if (extensions != null) {
                    extensionSet = new HashSet<>();
                    extensions.forEach(e -> extensionSet.add(e.getAsString()));
                } else {
                    extensionSet = null;
                }

                JsonArray files = new JsonArray();
                collectFiles(baseDir, project.getBasePath(), files, excludeSet, extensionSet, 0, maxDepth);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.add("files", files);
                return gson.toJson(response);
                
            } catch (Exception e) {
                LOG.error("Error listing files", e);
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    /**
     * Reads a file's content
     */
    public String readFile(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String path = data.get("path").getAsString();
                String encoding = data.has("encoding") ? data.get("encoding").getAsString() : "UTF-8";
                
                VirtualFile file = getProjectFile(path);
                if (file == null || file.isDirectory()) {
                    return createErrorResponse("File not found: " + path);
                }
                
                // Check file size
                if (file.getLength() > MAX_FILE_SIZE) {
                    return createErrorResponse("File too large: " + file.getLength() + " bytes");
                }
                
                String content = new String(file.contentsToByteArray(), encoding);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("content", content);
                response.addProperty("path", path);
                return gson.toJson(response);
                
            } catch (Exception e) {
                LOG.error("Error reading file", e);
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    /**
     * Searches for text in files
     */
    public String searchInFiles(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String searchText = data.get("searchText").getAsString();
                String path = data.has("path") ? data.get("path").getAsString() : "/";
                boolean caseSensitive = data.has("caseSensitive") && data.get("caseSensitive").getAsBoolean();
                boolean wholeWord = data.has("wholeWord") && data.get("wholeWord").getAsBoolean();
                boolean regex = data.has("regex") && data.get("regex").getAsBoolean();
                int contextLines = data.has("contextLines") ? data.get("contextLines").getAsInt() : 2;
                int maxResults = data.has("maxResults") ? data.get("maxResults").getAsInt() : 1000;
                
                JsonArray excludePatterns = data.has("excludePatterns") ? 
                    data.getAsJsonArray("excludePatterns") : new JsonArray();
                JsonArray extensions = data.has("extensions") ? 
                    data.getAsJsonArray("extensions") : null;
                
                VirtualFile baseDir = getProjectFile(path);
                if (baseDir == null || !baseDir.isDirectory()) {
                    return createErrorResponse("Directory not found: " + path);
                }
                
                Set<String> excludeSet = new HashSet<>();
                excludePatterns.forEach(e -> excludeSet.add(e.getAsString()));
                
                Set<String> extensionSet;
                if (extensions != null) {
                    extensionSet = new HashSet<>();
                    extensions.forEach(e -> extensionSet.add(e.getAsString()));
                } else {
                    extensionSet = null;
                }

                // Build search pattern
                Pattern searchPattern;
                if (regex) {
                    searchPattern = Pattern.compile(searchText, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                } else {
                    String escapedText = Pattern.quote(searchText);
                    if (wholeWord) {
                        escapedText = "\\b" + escapedText + "\\b";
                    }
                    searchPattern = Pattern.compile(escapedText, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                }
                
                JsonArray results = new JsonArray();
                int[] totalMatches = {0};
                searchInDirectory(baseDir, project.getBasePath(), searchPattern, results, 
                    excludeSet, extensionSet, contextLines, maxResults, totalMatches);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("totalMatches", totalMatches[0]);
                response.add("results", results);
                return gson.toJson(response);
                
            } catch (Exception e) {
                LOG.error("Error searching in files", e);
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    /**
     * Finds JavaScript functions in files
     */
    public String findFunctions(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String functionName = data.has("functionName") ? data.get("functionName").getAsString() : null;
                String path = data.has("path") ? data.get("path").getAsString() : "/";
                
                VirtualFile baseDir = getProjectFile(path);
                if (baseDir == null || !baseDir.isDirectory()) {
                    return createErrorResponse("Directory not found: " + path);
                }
                
                JsonArray excludePatterns = data.has("excludePatterns") ? 
                    data.getAsJsonArray("excludePatterns") : new JsonArray();
                Set<String> excludeSet = new HashSet<>();
                excludePatterns.forEach(e -> excludeSet.add(e.getAsString()));
                
                // JavaScript file extensions
                Set<String> jsExtensions = Set.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");
                
                JsonArray results = new JsonArray();
                findFunctionsInDirectory(baseDir, project.getBasePath(), functionName, 
                    results, excludeSet, jsExtensions);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.add("results", results);
                return gson.toJson(response);
                
            } catch (Exception e) {
                LOG.error("Error finding functions", e);
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    /**
     * Gets directory tree structure
     */
    public String getDirectoryTree(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String path = data.has("path") ? data.get("path").getAsString() : "/";
                int maxDepth = data.has("maxDepth") ? data.get("maxDepth").getAsInt() : 3;
                
                JsonArray excludePatterns = data.has("excludePatterns") ? 
                    data.getAsJsonArray("excludePatterns") : new JsonArray();
                JsonArray extensions = data.has("extensions") ? 
                    data.getAsJsonArray("extensions") : null;
                
                VirtualFile baseDir = getProjectFile(path);
                if (baseDir == null) {
                    return createErrorResponse("Directory not found: " + path);
                }
                
                Set<String> excludeSet = new HashSet<>();
                excludePatterns.forEach(e -> excludeSet.add(e.getAsString()));
                
                Set<String> extensionSet;
                if (extensions != null) {
                    extensionSet = new HashSet<>();
                    extensions.forEach(e -> extensionSet.add(e.getAsString()));
                } else {
                    extensionSet = null;
                }

                JsonObject tree = buildDirectoryTree(baseDir, excludeSet, extensionSet, 0, maxDepth);
                
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.add("tree", tree);
                return gson.toJson(response);
                
            } catch (Exception e) {
                LOG.error("Error getting directory tree", e);
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    // Helper methods
    
    private VirtualFile getProjectFile(String path) {
        if (path.equals("/") || path.isEmpty()) {
            return project.getBaseDir();
        }
        
        VirtualFile projectBase = project.getBaseDir();
        if (projectBase == null) return null;
        
        // Remove leading slash if present
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        
        return projectBase.findFileByRelativePath(path);
    }
    
    private void collectFiles(VirtualFile dir, String projectBase, JsonArray results, 
                            Set<String> excludePatterns, Set<String> extensions, 
                            int currentDepth, int maxDepth) {
        if (currentDepth > maxDepth) return;
        
        for (VirtualFile child : dir.getChildren()) {
            String relativePath = getRelativePath(child, projectBase);
            
            // Check exclusions
            if (shouldExclude(relativePath, excludePatterns)) continue;
            
            JsonObject fileInfo = new JsonObject();
            fileInfo.addProperty("path", relativePath);
            fileInfo.addProperty("name", child.getName());
            fileInfo.addProperty("type", child.isDirectory() ? "directory" : "file");
            
            if (child.isDirectory()) {
                results.add(fileInfo);
                collectFiles(child, projectBase, results, excludePatterns, extensions, 
                    currentDepth + 1, maxDepth);
            } else if (extensions == null || hasValidExtension(child.getName(), extensions)) {
                results.add(fileInfo);
            }
        }
    }
    
    private void searchInDirectory(VirtualFile dir, String projectBase, Pattern searchPattern,
                                 JsonArray results, Set<String> excludePatterns, 
                                 Set<String> extensions, int contextLines, int maxResults,
                                 int[] totalMatches) {
        if (totalMatches[0] >= maxResults) return;
        
        for (VirtualFile child : dir.getChildren()) {
            if (totalMatches[0] >= maxResults) return;
            
            String relativePath = getRelativePath(child, projectBase);
            if (shouldExclude(relativePath, excludePatterns)) continue;
            
            if (child.isDirectory()) {
                searchInDirectory(child, projectBase, searchPattern, results, excludePatterns,
                    extensions, contextLines, maxResults, totalMatches);
            } else if (extensions == null || hasValidExtension(child.getName(), extensions)) {
                searchInFile(child, relativePath, searchPattern, results, contextLines, 
                    maxResults, totalMatches);
            }
        }
    }
    
    private void searchInFile(VirtualFile file, String relativePath, Pattern searchPattern,
                            JsonArray results, int contextLines, int maxResults, int[] totalMatches) {
        try {
            if (file.getLength() > MAX_FILE_SIZE) return;
            
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            JsonArray matches = new JsonArray();
            
            for (int i = 0; i < lines.length; i++) {
                if (totalMatches[0] >= maxResults) break;
                
                Matcher matcher = searchPattern.matcher(lines[i]);
                if (matcher.find()) {
                    totalMatches[0]++;
                    
                    JsonObject match = new JsonObject();
                    match.addProperty("line", i + 1);
                    match.addProperty("column", matcher.start() + 1);
                    match.addProperty("text", lines[i].trim());
                    
                    // Add context
                    JsonObject context = new JsonObject();
                    JsonArray before = new JsonArray();
                    JsonArray after = new JsonArray();
                    
                    for (int j = Math.max(0, i - contextLines); j < i; j++) {
                        before.add(lines[j]);
                    }
                    for (int j = i + 1; j < Math.min(lines.length, i + contextLines + 1); j++) {
                        after.add(lines[j]);
                    }
                    
                    context.add("before", before);
                    context.addProperty("current", lines[i]);
                    context.add("after", after);
                    match.add("context", context);
                    
                    matches.add(match);
                }
            }
            
            if (matches.size() > 0) {
                JsonObject fileResult = new JsonObject();
                fileResult.addProperty("file", relativePath);
                fileResult.add("matches", matches);
                results.add(fileResult);
            }
            
        } catch (Exception e) {
            LOG.warn("Error searching in file: " + relativePath, e);
        }
    }
    
    private void findFunctionsInDirectory(VirtualFile dir, String projectBase, String functionName,
                                        JsonArray results, Set<String> excludePatterns,
                                        Set<String> extensions) {
        for (VirtualFile child : dir.getChildren()) {
            String relativePath = getRelativePath(child, projectBase);
            if (shouldExclude(relativePath, excludePatterns)) continue;
            
            if (child.isDirectory()) {
                findFunctionsInDirectory(child, projectBase, functionName, results, 
                    excludePatterns, extensions);
            } else if (hasValidExtension(child.getName(), extensions)) {
                findFunctionsInFile(child, relativePath, functionName, results);
            }
        }
    }
    
    private void findFunctionsInFile(VirtualFile file, String relativePath, String targetName,
                                   JsonArray results) {
        try {
            if (file.getLength() > MAX_FILE_SIZE) return;
            
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            
            // Comprehensive function patterns for JavaScript/TypeScript
            List<Pattern> patterns = Arrays.asList(
                // Traditional function declarations
                Pattern.compile("function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
                Pattern.compile("async\\s+function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
                Pattern.compile("function\\s*\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // generator
                Pattern.compile("async\\s+function\\s*\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // async generator
                
                // Variable assignments with function expressions
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function\\s*\\([^)]*\\)"),
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s+function\\s*\\([^)]*\\)"),
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function\\s*\\*\\s*\\([^)]*\\)"),
                
                // Arrow functions
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*[a-zA-Z_$][a-zA-Z0-9_$]*\\s*=>"), // single param
                Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s+[a-zA-Z_$][a-zA-Z0-9_$]*\\s*=>"),
                
                // Object methods (ES6 shorthand)
                Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
                Pattern.compile("async\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
                Pattern.compile("\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"), // generator method
                
                // Object property functions
                Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)"),
                Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*async\\s+function\\s*\\([^)]*\\)"),
                Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*async\\s*\\([^)]*\\)\\s*=>"),
                
                // Class methods
                Pattern.compile("(?:static\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
                Pattern.compile("(?:static\\s+)?async\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
                Pattern.compile("(?:static\\s+)?\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
                Pattern.compile("(?:get|set)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // getters/setters
                
                // Constructor
                Pattern.compile("(constructor)\\s*\\([^)]*\\)\\s*\\{"),
                
                // Exports
                Pattern.compile("export\\s+(?:default\\s+)?function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
                Pattern.compile("export\\s+(?:default\\s+)?async\\s+function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
                Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
                
                // Module exports
                Pattern.compile("module\\.exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
                Pattern.compile("module\\.exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                Pattern.compile("exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
                Pattern.compile("exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                
                // IIFE (Immediately Invoked Function Expression) - special handling
                Pattern.compile("\\(function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
                
                // Object.defineProperty functions
                Pattern.compile("(?:value|get|set)\\s*:\\s*function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
                
                // React/Vue component methods
                Pattern.compile("(?:componentDidMount|componentWillUnmount|render|mounted|created|beforeDestroy)\\s*\\([^)]*\\)"),
                
                // TypeScript specific
                Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?(?:async\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
                Pattern.compile("(?:readonly\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
                
                // jQuery style
                Pattern.compile("\\$\\.fn\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
                Pattern.compile("jQuery\\.fn\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function")
            );
            
            JsonArray functions = new JsonArray();
            Set<String> foundFunctions = new HashSet<>();
            String[] lines = content.split("\n");
            
            // First, look for Cocos2d-x class definitions and extract their methods
            findCocosClassMethods(content, functions, foundFunctions, targetName);
            
            // Then look for regular function patterns
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    String funcName = matcher.group(1);
                    
                    // Skip if already found
                    String uniqueKey = funcName + "_" + matcher.start();
                    if (foundFunctions.contains(uniqueKey)) continue;
                    foundFunctions.add(uniqueKey);
                    
                    if (targetName == null || funcName.equals(targetName)) {
                        int position = matcher.start();
                        int lineNumber = getLineNumber(content, position);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", funcName);
                        func.addProperty("line", lineNumber);
                        func.addProperty("signature", extractSignature(content, position));
                        func.addProperty("type", detectFunctionType(matcher.group(0)));
                        functions.add(func);
                    }
                }
            }
            
            // Also look for anonymous functions if no specific name is requested
            if (targetName == null) {
                findAnonymousFunctions(content, functions, foundFunctions);
            }
            
            if (functions.size() > 0) {
                JsonObject fileResult = new JsonObject();
                fileResult.addProperty("file", relativePath);
                fileResult.add("functions", functions);
                results.add(fileResult);
            }
            
        } catch (Exception e) {
            LOG.warn("Error finding functions in file: " + relativePath, e);
        }
    }
    
    private void findAnonymousFunctions(String content, JsonArray functions, Set<String> foundFunctions) {
        // Pattern for anonymous functions that might be interesting
        List<Pattern> anonymousPatterns = Arrays.asList(
            // Callbacks
            Pattern.compile("\\b(?:then|catch|finally|map|filter|reduce|forEach|find|some|every)\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            Pattern.compile("\\b(?:setTimeout|setInterval|requestAnimationFrame)\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            Pattern.compile("\\b(?:addEventListener|on[A-Z][a-zA-Z]*)\\s*\\(\\s*['\"][^'\"]+['\"]\\s*,\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Promise executors
            Pattern.compile("new\\s+Promise\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Array methods with anonymous functions
            Pattern.compile("\\.(?:map|filter|reduce|forEach|find|some|every)\\s*\\(\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>"),
            
            // Event handlers
            Pattern.compile("\\bon[A-Z][a-zA-Z]*\\s*=\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Module patterns
            Pattern.compile("\\(\\s*(?:async\\s*)?function\\s*\\([^)]*\\)\\s*\\{[^}]+\\}\\s*\\)\\s*\\("), // IIFE
            
            // React hooks
            Pattern.compile("use(?:Effect|Callback|Memo|LayoutEffect)\\s*\\(\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>")
        );
        
        int anonymousCount = 1;
        for (Pattern pattern : anonymousPatterns) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                int position = matcher.start();
                String uniqueKey = "anonymous_" + position;
                
                if (!foundFunctions.contains(uniqueKey)) {
                    foundFunctions.add(uniqueKey);
                    
                    JsonObject func = new JsonObject();
                    func.addProperty("name", "<anonymous#" + anonymousCount++ + ">");
                    func.addProperty("line", getLineNumber(content, position));
                    func.addProperty("context", extractContext(content, position, 50));
                    func.addProperty("type", "anonymous");
                    functions.add(func);
                }
            }
        }
    }
    
    private String detectFunctionType(String match) {
        if (match.contains("=>")) return "arrow";
        if (match.contains("async")) return "async";
        if (match.contains("function*") || match.contains("*")) return "generator";
        if (match.contains("constructor")) return "constructor";
        if (match.contains("static")) return "static";
        if (match.contains("get ")) return "getter";
        if (match.contains("set ")) return "setter";
        if (match.contains("export")) return "exported";
        if (match.startsWith("(")) return "anonymous";
        if (match.contains(":")) return "method";
        return "function";
    }
    
    private String extractContext(String content, int position, int contextLength) {
        int start = Math.max(0, position - contextLength);
        int end = Math.min(content.length(), position + contextLength);
        String context = content.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) context = "..." + context;
        if (end < content.length()) context = context + "...";
        return context;
    }
    
    private void findCocosClassMethods(String content, JsonArray functions, Set<String> foundFunctions, String targetName) {
        // Pattern to find Cocos2d-x class definitions
        // Examples:
        // var MyClass = cc.Class.extend({
        // cc.Class.extend({
        // ClassName.extend({
        Pattern classPattern = Pattern.compile("(?:var\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*)?(?:cc\\.Class|cc\\.Layer|cc\\.Scene|cc\\.Sprite|cc\\.Node|[a-zA-Z_$][a-zA-Z0-9_$]*)\\.extend\\s*\\(\\s*\\{");
        
        Matcher classMatcher = classPattern.matcher(content);
        while (classMatcher.find()) {
            int classStart = classMatcher.end() - 1; // Position of the opening brace
            String className = classMatcher.group(1); // May be null for anonymous classes
            
            // Find the matching closing brace for the class
            int classEnd = findMatchingBrace(content, classStart);
            if (classEnd == -1) continue;
            
            String classBody = content.substring(classStart + 1, classEnd);
            
            // Find all methods within this class body
            // Method patterns in Cocos2d-x classes:
            // methodName: function() {}
            // methodName: function(param1, param2) {}
            // methodName:function() {} (no space)
            // ctor: function() {} (constructor)
            // onEnter: function() {} (lifecycle methods)
            Pattern methodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)");
            
            Matcher methodMatcher = methodPattern.matcher(classBody);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                
                if (targetName == null || methodName.equals(targetName)) {
                    int methodPosition = classStart + 1 + methodMatcher.start();
                    String uniqueKey = methodName + "_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", getLineNumber(content, methodPosition));
                        func.addProperty("signature", methodName + methodMatcher.group(0).substring(methodName.length()));
                        func.addProperty("type", "cocos-method");
                        if (className != null) {
                            func.addProperty("className", className);
                        }
                        
                        // Add special type annotations for common Cocos2d-x methods
                        if (methodName.equals("ctor")) {
                            func.addProperty("type", "cocos-constructor");
                        } else if (methodName.matches("^(onEnter|onExit|onEnterTransitionDidFinish|onExitTransitionDidStart|init|update|draw|visit)$")) {
                            func.addProperty("type", "cocos-lifecycle");
                        }
                        
                        functions.add(func);
                    }
                }
            }
            
            // Also look for ES6-style methods in Cocos classes (some modern Cocos2d-x code)
            // methodName() {}
            // methodName(param) {}
            Pattern es6MethodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{");
            Matcher es6Matcher = es6MethodPattern.matcher(classBody);
            
            while (es6Matcher.find()) {
                String methodName = es6Matcher.group(1);
                
                // Skip if it's actually "function("
                if (methodName.equals("function")) continue;
                
                if (targetName == null || methodName.equals(targetName)) {
                    int methodPosition = classStart + 1 + es6Matcher.start();
                    String uniqueKey = methodName + "_es6_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", getLineNumber(content, methodPosition));
                        func.addProperty("signature", es6Matcher.group(0));
                        func.addProperty("type", "cocos-method-es6");
                        if (className != null) {
                            func.addProperty("className", className);
                        }
                        functions.add(func);
                    }
                }
            }
        }
        
        // Also look for Cocos Creator style classes
        // cc.Class({
        //     extends: cc.Component,
        //     properties: { ... },
        //     methodName: function() {}
        // })
        Pattern creatorPattern = Pattern.compile("cc\\.Class\\s*\\(\\s*\\{");
        Matcher creatorMatcher = creatorPattern.matcher(content);
        
        while (creatorMatcher.find()) {
            int classStart = creatorMatcher.end() - 1;
            int classEnd = findMatchingBrace(content, classStart);
            if (classEnd == -1) continue;
            
            String classBody = content.substring(classStart + 1, classEnd);
            
            // Find methods in Creator-style classes
            Pattern methodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)");
            Matcher methodMatcher = methodPattern.matcher(classBody);
            
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                
                // Skip property definitions
                if (methodName.equals("properties") || methodName.equals("extends")) continue;
                
                if (targetName == null || methodName.equals(targetName)) {
                    int methodPosition = classStart + 1 + methodMatcher.start();
                    String uniqueKey = "creator_" + methodName + "_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", getLineNumber(content, methodPosition));
                        func.addProperty("signature", methodName + methodMatcher.group(0).substring(methodName.length()));
                        func.addProperty("type", "cocos-creator-method");
                        functions.add(func);
                    }
                }
            }
        }
    }
    
    private int findMatchingBrace(String content, int openBracePos) {
        int braceCount = 1;
        boolean inString = false;
        boolean inComment = false;
        boolean inMultiLineComment = false;
        char stringChar = 0;
        
        for (int i = openBracePos + 1; i < content.length(); i++) {
            char current = content.charAt(i);
            char prev = i > 0 ? content.charAt(i - 1) : 0;
            char next = i < content.length() - 1 ? content.charAt(i + 1) : 0;
            
            // Handle multi-line comments
            if (!inString && !inComment && current == '/' && next == '*') {
                inMultiLineComment = true;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment && current == '*' && next == '/') {
                inMultiLineComment = false;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment) continue;
            
            // Handle single-line comments
            if (!inString && current == '/' && next == '/') {
                inComment = true;
                continue;
            }
            if (inComment && current == '\n') {
                inComment = false;
                continue;
            }
            if (inComment) continue;
            
            // Handle strings
            if (!inString && (current == '"' || current == '\'' || current == '`')) {
                inString = true;
                stringChar = current;
                continue;
            }
            if (inString && current == stringChar && prev != '\\') {
                inString = false;
                continue;
            }
            if (inString) continue;
            
            // Count braces
            if (current == '{') {
                braceCount++;
            } else if (current == '}') {
                braceCount--;
                if (braceCount == 0) {
                    return i;
                }
            }
        }
        
        return -1; // No matching brace found
    }
    
    private JsonObject buildDirectoryTree(VirtualFile file, Set<String> excludePatterns,
                                        Set<String> extensions, int currentDepth, int maxDepth) {
        JsonObject node = new JsonObject();
        node.addProperty("name", file.getName());
        node.addProperty("type", file.isDirectory() ? "directory" : "file");
        node.addProperty("path", getRelativePath(file, project.getBasePath()));
        
        if (file.isDirectory() && currentDepth < maxDepth) {
            JsonArray children = new JsonArray();
            
            for (VirtualFile child : file.getChildren()) {
                String relativePath = getRelativePath(child, project.getBasePath());
                if (shouldExclude(relativePath, excludePatterns)) continue;
                
                if (child.isDirectory() || extensions == null || 
                    hasValidExtension(child.getName(), extensions)) {
                    children.add(buildDirectoryTree(child, excludePatterns, extensions, 
                        currentDepth + 1, maxDepth));
                }
            }
            
            node.add("children", children);
        }
        
        return node;
    }
    
    private String getRelativePath(VirtualFile file, String basePath) {
        String fullPath = file.getPath();
        if (basePath != null && fullPath.startsWith(basePath)) {
            String relative = fullPath.substring(basePath.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return file.getName();
    }
    
    private boolean shouldExclude(String path, Set<String> excludePatterns) {
        // Check default excludes
        for (String exclude : DEFAULT_EXCLUDE_DIRS) {
            if (path.contains("/" + exclude + "/") || path.endsWith("/" + exclude)) {
                return true;
            }
        }
        
        // Check custom excludes
        for (String pattern : excludePatterns) {
            if (matchesPattern(path, pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean matchesPattern(String path, String pattern) {
        // Simple glob pattern matching
        String regex = pattern.replace("**", ".*").replace("*", "[^/]*");
        return path.matches(regex);
    }
    
    private boolean hasValidExtension(String filename, Set<String> extensions) {
        String ext = "";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            ext = filename.substring(lastDot);
        }
        return extensions.contains(ext);
    }
    
    private int getLineNumber(String content, int position) {
        int line = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
    
    private String extractSignature(String content, int startPos) {
        int endPos = startPos;
        int braceCount = 0;
        boolean foundFirstBrace = false;
        
        for (int i = startPos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (!foundFirstBrace) {
                    foundFirstBrace = true;
                    endPos = i;
                }
                braceCount++;
            } else if (c == '}' && foundFirstBrace) {
                braceCount--;
                if (braceCount == 0) {
                    break;
                }
            } else if (c == '\n' && !foundFirstBrace) {
                endPos = i;
                break;
            }
        }
        
        return content.substring(startPos, endPos).trim();
    }
    
    private String createErrorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", message);
        return gson.toJson(response);
    }
    
    // Common exclude patterns
    private static final Set<String> DEFAULT_EXCLUDE_DIRS = Set.of(
        ".git", ".idea", ".vscode", "node_modules", "dist", "build", 
        "target", "out", ".gradle", "__pycache__", ".pytest_cache"
    );
    
    // File size limit (10MB)
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
}
