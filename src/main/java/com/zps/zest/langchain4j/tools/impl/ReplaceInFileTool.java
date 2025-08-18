package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zps.zest.completion.diff.FileDiffDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ultra-simplified tool for searching and replacing text in files.
 * Uses a direct line-by-line approach with simple string operations for better performance.
 */
public class ReplaceInFileTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(ReplaceInFileTool.class);

    public ReplaceInFileTool(@NotNull Project project) {
        super(project, "replace_in_file",
                "Searches and replaces text in files. No regex. Java files: auto-ignores whitespace.\n" +
                "JSON: Use \\n not \\\\n, \\\" not \\\\\\\", ? not \\?.\n" +
                "Params: filePath, search, replace (required); caseSensitive, ignoreWhitespace (optional)");
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Path to the file (relative to project root or absolute)");
        properties.add("filePath", filePath);

        JsonObject search = new JsonObject();
        search.addProperty("type", "string");
        search.addProperty("description", "Text to search for. Use \\n for newlines in JSON (not \\\\n). Don't escape ? or other special chars unless required by JSON.");
        properties.add("search", search);

        JsonObject replace = new JsonObject();
        replace.addProperty("type", "string");
        replace.addProperty("description", "Replacement text. Use \\n for newlines in JSON (not \\\\n).");
        properties.add("replace", replace);

        JsonObject caseSensitive = new JsonObject();
        caseSensitive.addProperty("type", "boolean");
        caseSensitive.addProperty("description", "Whether the search is case-sensitive (default: true)");
        caseSensitive.addProperty("default", true);
        properties.add("caseSensitive", caseSensitive);

        JsonObject ignoreWhitespace = new JsonObject();
        ignoreWhitespace.addProperty("type", "boolean");
        ignoreWhitespace.addProperty("description", "Whether to ignore whitespace variations (auto-enabled for Java files)");
        ignoreWhitespace.addProperty("default", false);
        properties.add("ignoreWhitespace", ignoreWhitespace);

        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("filePath");
        required.add("search");
        required.add("replace");
        schema.add("required", required);

        return schema;
    }

    @Override
    protected boolean requiresReadAction() {
        return false; // File operations don't require read action
    }

    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String filePath = getRequiredString(parameters, "filePath");
        String searchText = getRequiredString(parameters, "search");
        String replaceText = getOptionalString(parameters, "replace", "");
        boolean caseSensitive = getOptionalBoolean(parameters, "caseSensitive", true);
        boolean ignoreWhitespace = getOptionalBoolean(parameters, "ignoreWhitespace", false);

        // Auto-enable whitespace ignoring for Java files
        if (filePath.toLowerCase().endsWith(".java")) {
            ignoreWhitespace = true;
        }

        // Run in background with proper error handling
        return replaceInFileInBackground(filePath, searchText, replaceText, caseSensitive, ignoreWhitespace);
    }

    /**
     * Run the search and replace operation in a background thread to avoid blocking UI.
     */
    private ToolResult replaceInFileInBackground(String filePath, String searchText, String replaceText,
                                                 boolean caseSensitive, boolean ignoreWhitespace) {
        // Validate inputs first
        Path fullPath = resolvePath(filePath);
        if (fullPath == null) {
            return ToolResult.error("Error: No base directory found for the project.");
        }

        File targetFile = fullPath.toFile();
        if (!targetFile.exists() || !targetFile.isFile()) {
            return ToolResult.error("Error: File not found: " + filePath);
        }

        // Create a result holder
        CompletableFuture<ToolResult> resultFuture = new CompletableFuture<>();

        // Run the search in background
        ProgressManager.getInstance().run(new Task.Modal(project, "Searching in " + filePath, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("Searching for matches...");
                    indicator.setIndeterminate(false);

                    // Perform the actual search and replace
                    ReplaceResult replaceResult = performSearchAndReplace(fullPath, searchText, replaceText,
                            caseSensitive, ignoreWhitespace, indicator);

                    // Handle different outcomes
                    if (replaceResult.replacementCount == 0) {
                        // Create a more helpful error message
                        String errorMsg = "No matches found for the search text.";
                        
                        // Check for common escaping issues
                        if (searchText.contains("\\n") && !searchText.contains("\n")) {
                            errorMsg += "\n\nPOSSIBLE ISSUE: Search text contains literal \\n instead of newlines. " +
                                      "For JSON input, use actual newlines (\\n not \\\\n).";
                        }
                        if (searchText.contains("\\?")) {
                            errorMsg += "\n\nPOSSIBLE ISSUE: Search text contains escaped question mark (\\?). " +
                                      "Question marks don't need escaping.";
                        }
                        if (searchText.contains("\\\"") && !searchText.contains("\"")) {
                            errorMsg += "\n\nPOSSIBLE ISSUE: Search text might have over-escaped quotes. " +
                                      "Use \\\" for quotes in JSON, not \\\\\\\".";
                        }
                        
                        // Show first few lines of search pattern
                        String[] searchLines = searchText.split("\n");
                        if (searchLines.length > 0) {
                            errorMsg += "\n\nSearch pattern first line: [" + searchLines[0] + "]";
                            if (searchLines.length > 1) {
                                errorMsg += "\nTotal lines in search pattern: " + searchLines.length;
                            }
                        }
                        
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showInfoMessage(project,
                                    "No matches found for the search text in " + filePath + "\n\n" + 
                                    "Check that the search pattern exactly matches the file content, " +
                                    "including whitespace and newlines.\n\n" +
                                    "Tip: For multi-line searches, ensure proper JSON escaping.",
                                    "Replace in File - No Matches");
                        });
                        resultFuture.complete(ToolResult.error(errorMsg));
                        return;
                    }

                    // If no actual changes (shouldn't happen if replacementCount > 0, but safety check)
                    if (replaceResult.originalContent.equals(replaceResult.modifiedContent)) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showInfoMessage(project,
                                    "Found " + replaceResult.replacementCount + " matches but no changes were needed in " + filePath,
                                    "Replace in File - No Changes");
                        });
                        resultFuture.complete(ToolResult.error("No changes needed."));
                        return;
                    }

                    // Show diff and apply changes on UI thread
                    ApplicationManager.getApplication().invokeLater(() -> {
                        ToolResult result = showDiffAndApplyChanges(fullPath, replaceResult.originalContent,
                                replaceResult.modifiedContent, replaceResult.replacementCount,
                                ignoreWhitespace);
                        resultFuture.complete(result);
                    });

                } catch (Exception e) {
                    LOG.error("Error processing replace in file request", e);
                    String errorMsg = "Error during search and replace operation: " + e.getMessage();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, errorMsg, "Replace in File Error");
                    });
                    resultFuture.complete(ToolResult.error(errorMsg));
                }
            }
        });

        // Wait for result with timeout
        try {
            return resultFuture.get(120, TimeUnit.SECONDS); // 2 minute timeout
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return ToolResult.error("Operation timed out or was interrupted: " + e.getMessage());
        }
    }


    /**
     * Data class to hold the result of search and replace operation.
     */
    public static class ReplaceResult {
        final String originalContent;
        public final String modifiedContent;
        public final int replacementCount;

        ReplaceResult(String originalContent, String modifiedContent, int replacementCount) {
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.replacementCount = replacementCount;
        }
    }

    /**
     * Ultra-simple implementation that:
     * 1. Splits into lines & trims each line
     * 2. Searches for the first line
     * 3. If match found, checks subsequent lines
     * 4. Replaces line by line
     */
    public static ReplaceResult performSearchAndReplace(Path fullPath, String searchText, String replaceText,
                                                        boolean caseSensitive, boolean ignoreWhitespace,
                                                        ProgressIndicator indicator) throws Exception {

        // Validate file is readable
        if (!Files.isReadable(fullPath)) {
            throw new RuntimeException("File is not readable: " + fullPath);
        }

        // Read all lines from the file
        List<String> originalLines;
        try {
            originalLines = Files.readAllLines(fullPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        }

        List<String> modifiedLines = new ArrayList<>(originalLines.size());

        // Validate search text
        if (searchText == null || searchText.trim().isEmpty()) {
            throw new RuntimeException("Search text cannot be empty");
        }

        // Check for common escaping issues and warn
        if (searchText.contains("\\n") && !searchText.contains("\n")) {
            LOG.warn("Search text contains literal \\n instead of newline. This is likely an escaping issue. " +
                    "Use actual newlines in JSON (\\n not \\\\n)");
        }
        if (searchText.contains("\\\"") && !searchText.contains("\"")) {
            LOG.warn("Search text contains escaped quotes but no actual quotes. This might be an escaping issue.");
        }
        if (searchText.contains("\\?")) {
            LOG.warn("Search text contains escaped question mark (\\?). Question marks don't need escaping in this tool.");
        }

        // Split search and replace text into lines & trim each line if needed
        String[] searchLines = searchText.split("(\r?\n)");
        String[] replaceLines = replaceText.split("(\r?\n)");

        // Log search pattern details for debugging
        if (LOG.isDebugEnabled()) {
            LOG.debug("Search pattern has " + searchLines.length + " lines");
            for (int i = 0; i < searchLines.length; i++) {
                LOG.debug("Search line " + i + ": [" + searchLines[i] + "]");
            }
        }

        // If ignoring whitespace, trim each line
        if (ignoreWhitespace) {
            for (int i = 0; i < searchLines.length; i++) {
                searchLines[i] = searchLines[i].trim();
            }
            for (int i = 0; i < replaceLines.length; i++) {
                replaceLines[i] = replaceLines[i].trim();
            }
        }

        // Process the file lines
        int replacementCount = 0;
        int lineIndex = 0;

        while (lineIndex < originalLines.size()) {
            // Check for cancellation
            if (indicator.isCanceled()) {
                throw new RuntimeException("Operation was cancelled by user");
            }

            indicator.setFraction((double) lineIndex / originalLines.size());

            // Search for the first line of the pattern
            String currentLine = originalLines.get(lineIndex);
            String lineToCheck = ignoreWhitespace ? currentLine.trim() : currentLine;
            boolean foundFirstLine = false;

            if (caseSensitive) {
                foundFirstLine = lineToCheck.contains(searchLines[0]);
            } else {
                foundFirstLine = lineToCheck.toLowerCase().contains(searchLines[0].toLowerCase());
            }

            if (foundFirstLine && searchLines.length > 1) {
                // This could be the start of a multi-line match

                // Check if we have enough lines left to potentially match
                if (lineIndex + searchLines.length <= originalLines.size()) {
                    // Check subsequent lines for a complete match
                    boolean isFullMatch = true;

                    for (int i = 0; i < searchLines.length; i++) {
                        String nextLine = originalLines.get(lineIndex + i);
                        String nextLineToCheck = ignoreWhitespace ? nextLine.trim() : nextLine;

                        boolean lineMatches = false;
                        if (caseSensitive) {
                            lineMatches = nextLineToCheck.contains(searchLines[i]);
                        } else {
                            lineMatches = nextLineToCheck.toLowerCase().contains(searchLines[i].toLowerCase());
                        }

                        if (!lineMatches) {
                            if (nextLineToCheck.isEmpty() && searchLines[i].isEmpty()) {
                                // Allow empty lines to match
                            } else {
                                isFullMatch = false;
                                break;
                            }
                        }
                    }

                    if (isFullMatch) {
                        // We have a complete match - replace it
                        replacementCount++;
                        // Replace with the corresponding lines from replaceLine
                        for (int i = 0; i < replaceLines.length; i++) {
                            // Preserve original indentation if not ignoring whitespace
                            if (!ignoreWhitespace && lineIndex + i < originalLines.size()) {
                                String originalIndent = getIndentation(originalLines.get(lineIndex + i));
                                modifiedLines.add(originalIndent + replaceLines[i]);
                            } else {
                                modifiedLines.add(replaceLines[i]);
                            }
                        }

                        // Skip the matched lines
                        lineIndex += searchLines.length;
                        continue;
                    }
                }

                // If we get here, it wasn't a full match - just add the current line and continue
                modifiedLines.add(currentLine);
                lineIndex++;
                continue;
            } else if (foundFirstLine && searchLines.length == 1) {
                // Single-line pattern match
                String newLine;

                if (caseSensitive) {
                    newLine = currentLine.replace(searchLines[0], replaceLines.length > 0 ? replaceLines[0] : "");
                } else {
                    // Case-insensitive replacement
                    String lcLine = currentLine.toLowerCase();
                    String lcSearch = searchLines[0].toLowerCase();
                    int startPos = lcLine.indexOf(lcSearch);

                    if (startPos >= 0) {
                        newLine = currentLine.substring(0, startPos) +
                                (replaceLines.length > 0 ? replaceLines[0] : "") +
                                currentLine.substring(startPos + searchLines[0].length());
                    } else {
                        newLine = currentLine;
                    }
                }

                if (!newLine.equals(currentLine)) {
                    replacementCount++;
                }

                modifiedLines.add(newLine);
                lineIndex++;
                continue;
            }

            // No match, keep the line as is
            modifiedLines.add(currentLine);
            lineIndex++;
        }

        indicator.setFraction(1.0);

        // Build the complete content
        String originalContent = String.join("\n", originalLines);
        String modifiedContent = String.join("\n", modifiedLines);

        return new ReplaceResult(originalContent, modifiedContent, replacementCount);
    }

    /**
     * Get the indentation (leading whitespace) from a line.
     */
    private static String getIndentation(String line) {
        if (line == null) return "";

        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }

        return line.substring(0, i);
    }

    private Path resolvePath(String filePath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return new File(filePath).isAbsolute() ? Paths.get(filePath) : Paths.get(basePath, filePath);
    }

    /**
     * Shows diff dialog and applies changes if confirmed by user.
     */
    private ToolResult showDiffAndApplyChanges(Path filePath, String origContent, String modifiedContent,
                                               int replacementCount, boolean ignoreWhitespace) {
        try {
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.toString());
            if (vFile == null) {
                String error = "Error: Could not find file in virtual file system: " + filePath;
                LOG.error(error);
                return ToolResult.error(error);
            }

            // Create title
            String title = "Replace in File: " + vFile.getName() +
                    " (" + replacementCount + " replacements)" +
                    (ignoreWhitespace ? " [Ignoring whitespace]" : "");

            // Use a CompletableFuture to handle the dialog result
            CompletableFuture<ToolResult> resultFuture = new CompletableFuture<>();

            // Show improved diff dialog with prominent Accept/Reject buttons
            FileDiffDialog.Companion.show(
                project,
                vFile,
                origContent,
                modifiedContent,
                title,
                new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke() {
                        // onAccept callback
                        LOG.info("User accepted replacements");
                        
                        // Execute write action
                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            try {
                                // Check if file is writable
                                if (!vFile.isWritable()) {
                                    resultFuture.complete(ToolResult.error("File is not writable: " + filePath));
                                    return;
                                }

                                // Update file content
                                vFile.refresh(false, false);
                                vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));

                                // Open file in editor
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    FileEditorManager.getInstance(project).openFile(vFile, true);
                                });

                                JsonObject metadata = createMetadata();
                                metadata.addProperty("filePath", filePath.toString());
                                metadata.addProperty("replacementCount", replacementCount);
                                metadata.addProperty("ignoreWhitespace", ignoreWhitespace);

                                resultFuture.complete(ToolResult.success("Successfully applied " + replacementCount + 
                                    " replacements to " + vFile.getName(), metadata));

                            } catch (Exception e) {
                                String error = "Error updating file: " + e.getMessage();
                                LOG.error(error, e);
                                resultFuture.complete(ToolResult.error(error));
                            }
                        });
                        return kotlin.Unit.INSTANCE;
                    }
                },
                new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                    @Override
                    public kotlin.Unit invoke() {
                        // onReject callback
                        LOG.info("User rejected replacements");
                        resultFuture.complete(ToolResult.error("Changes were cancelled by user."));
                        return kotlin.Unit.INSTANCE;
                    }
                },
                true  // showButtons = true
            );

            // Wait for the user's decision - this keeps the tool synchronous
            try {
                return resultFuture.get(5, TimeUnit.MINUTES); // 5 minute timeout for user decision
            } catch (InterruptedException | ExecutionException e) {
                String error = "Error waiting for user decision: " + e.getMessage();
                LOG.error(error, e);
                return ToolResult.error(error);
            } catch (TimeoutException e) {
                return ToolResult.error("Dialog timed out after 5 minutes. Operation cancelled.");
            }

        } catch (Exception e) {
            String error = "Error showing diff dialog: " + e.getMessage();
            LOG.error(error, e);
            return ToolResult.error(error);
        }
    }
}
