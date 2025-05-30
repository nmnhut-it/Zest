package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
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
import com.intellij.ide.highlighter.JavaFileType;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.ReplaceInFileTool;
import com.zps.zest.browser.utils.StringMatchingUtils;
import com.zps.zest.browser.utils.CodeExtractionUtils;
import com.zps.zest.browser.utils.FunctionExtractionUtils;
import com.zps.zest.browser.utils.JavaMethodUtils;
import com.zps.zest.browser.utils.JavaScriptPatterns;
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
public class FileService implements Disposable {
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
     * Searches for text in files with support for case-insensitive matching across naming conventions
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

                // Build search pattern with support for naming conventions
                Pattern searchPattern;
                if (regex) {
                    searchPattern = Pattern.compile(searchText, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                } else if (!caseSensitive) {
                    // Use cross-naming pattern for case-insensitive search
                    searchPattern = StringMatchingUtils.createCrossNamingPattern(searchText, false);
                } else {
                    String escapedText = Pattern.quote(searchText);
                    if (wholeWord) {
                        escapedText = "\\b" + escapedText + "\\b";
                    }
                    searchPattern = Pattern.compile(escapedText);
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
     * Finds JavaScript functions in files with support for case-insensitive matching across naming conventions
     */
    public String findFunctions(JsonObject data) {
        return ReadAction.compute(() -> {
            try {
                String functionName = data.has("functionName") ? data.get("functionName").getAsString() : null;
                String path = data.has("path") ? data.get("path").getAsString() : "/";
                boolean caseSensitive = data.has("caseSensitive") && data.get("caseSensitive").getAsBoolean();

                VirtualFile baseDir = getProjectFile(path);
                if (baseDir == null || !baseDir.isDirectory()) {
                    return createErrorResponse("Directory not found: " + path);
                }

                JsonArray excludePatterns = data.has("excludePatterns") ?
                        data.getAsJsonArray("excludePatterns") : new JsonArray();
                Set<String> excludeSet = new HashSet<>();
                excludePatterns.forEach(e -> excludeSet.add(e.getAsString()));

                // JavaScript file extensions
                Set<String> jsExtensions = Set.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs", ".java");

                JsonArray results = new JsonArray();
                findFunctionsInDirectory(baseDir, project.getBasePath(), functionName,
                        results, excludeSet, jsExtensions, caseSensitive);

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

            // Determine if this is a code file that might benefit from more context
            boolean isCodeFile = relativePath.endsWith(".java") || relativePath.endsWith(".js") ||
                    relativePath.endsWith(".ts") || relativePath.endsWith(".jsx") ||
                    relativePath.endsWith(".tsx") || relativePath.endsWith(".py") ||
                    relativePath.endsWith(".cpp") || relativePath.endsWith(".c") ||
                    relativePath.endsWith(".cs") || relativePath.endsWith(".go");

            // Use more context lines for code files
            int effectiveContextLines = isCodeFile ? Math.max(contextLines, 5) : contextLines;

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

                    for (int j = Math.max(0, i - effectiveContextLines); j < i; j++) {
                        before.add(lines[j]);
                    }
                    for (int j = i + 1; j < Math.min(lines.length, i + effectiveContextLines + 1); j++) {
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
                                          Set<String> extensions, boolean caseSensitive) {
        for (VirtualFile child : dir.getChildren()) {
            String relativePath = getRelativePath(child, projectBase);
            if (shouldExclude(relativePath, excludePatterns)) continue;

            if (child.isDirectory()) {
                findFunctionsInDirectory(child, projectBase, functionName, results,
                        excludePatterns, extensions, caseSensitive);
            } else if (hasValidExtension(child.getName(), extensions)) {
                findFunctionsInFile(child, relativePath, functionName, results, caseSensitive);
            }
        }
    }

    private void findFunctionsInFile(VirtualFile file, String relativePath, String targetName,
                                     JsonArray results, boolean caseSensitive) {
        try {
            if (file.getLength() > MAX_FILE_SIZE) return;

            // Check if it's a Java file and use PSI if available
            if (file.getName().endsWith(".java")) {
                JavaMethodUtils.findFunctionsInJavaFile(project, file, relativePath, targetName, results, caseSensitive);
                return;
            }

            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);

            // Comprehensive function patterns for JavaScript/TypeScript
            List<Pattern> patterns = JavaScriptPatterns.getPatterns();

            JsonArray functions = new JsonArray();
            Set<String> foundFunctions = new HashSet<>();

            // First, look for Cocos2d-x class definitions and extract their methods
            FunctionExtractionUtils.findCocosClassMethods(content, functions, foundFunctions, targetName, caseSensitive);

            // Then look for regular function patterns
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    String funcName = matcher.group(1);

                    // Skip if already found
                    String uniqueKey = funcName + "_" + matcher.start();
                    if (foundFunctions.contains(uniqueKey)) continue;
                    foundFunctions.add(uniqueKey);

                    // Check if name matches using cross-naming convention support
                    if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(funcName, targetName, caseSensitive)) {
                        int position = matcher.start();
                        int lineNumber = CodeExtractionUtils.getLineNumber(content, position);

                        JsonObject func = new JsonObject();
                        func.addProperty("name", funcName);
                        func.addProperty("line", lineNumber);

                        // Extract full function implementation
                        String implementation = FunctionExtractionUtils.extractFullFunctionImplementation(content, position);
                        func.addProperty("implementation", implementation);
                        func.addProperty("signature", FunctionExtractionUtils.extractSignature(content, position));
                        func.addProperty("type", CodeExtractionUtils.detectFunctionType(matcher.group(0)));
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
        List<Pattern> anonymousPatterns = JavaScriptPatterns.getAnonymousPatterns();

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
                    func.addProperty("line", CodeExtractionUtils.getLineNumber(content, position));
                    func.addProperty("context", CodeExtractionUtils.extractContext(content, position, 50));

                    // Extract full anonymous function implementation
                    String implementation = extractAnonymousFunctionImplementation(content, position);
                    func.addProperty("implementation", implementation);
                    func.addProperty("type", "anonymous");
                    functions.add(func);
                }
            }
        }
    }

    /**
     * Extracts the full implementation of an anonymous function.
     */
    private String extractAnonymousFunctionImplementation(String content, int startPos) {
        // Find where the function actually starts
        int funcStart = startPos;

        // Look for function keyword or arrow
        int searchEnd = Math.min(startPos + 200, content.length());
        boolean isArrowFunction = false;
        int bodyStart = -1;

        for (int i = startPos; i < searchEnd; i++) {
            if (content.substring(i).startsWith("=>")) {
                isArrowFunction = true;
                bodyStart = i + 2;
                break;
            } else if (content.charAt(i) == '{') {
                bodyStart = i;
                break;
            }
        }

        if (bodyStart == -1) {
            return CodeExtractionUtils.extractContext(content, startPos, 100);
        }

        if (isArrowFunction) {
            // Skip whitespace after arrow
            while (bodyStart < content.length() && Character.isWhitespace(content.charAt(bodyStart))) {
                bodyStart++;
            }

            if (bodyStart < content.length() && content.charAt(bodyStart) == '{') {
                // Arrow function with block body
                int endBrace = CodeExtractionUtils.findMatchingBrace(content, bodyStart);
                if (endBrace != -1) {
                    return content.substring(funcStart, endBrace + 1);
                }
            } else {
                // Arrow function with expression body
                return CodeExtractionUtils.extractArrowFunctionExpression(content, bodyStart);
            }
        } else {
            // Regular function with braces
            int endBrace = CodeExtractionUtils.findMatchingBrace(content, bodyStart);
            if (endBrace != -1) {
                return content.substring(funcStart, endBrace + 1);
            }
        }

        return CodeExtractionUtils.extractContext(content, startPos, 200);
    }

    @Override
    public void dispose() {

    }

    // Add these constants at the class level
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final Set<String> DEFAULT_EXCLUDE_DIRS = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build",
            "target", "out", ".gradle", "__pycache__", ".pytest_cache"
    );

    // Add these methods to the FileService class
    private String createErrorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", message);
        return gson.toJson(response);
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

}