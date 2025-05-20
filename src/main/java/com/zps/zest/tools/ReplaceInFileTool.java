package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
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
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Ultra-simplified tool for searching and replacing text in files.
 * Uses a direct line-by-line approach with simple string operations for better performance.
 */
public class ReplaceInFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ReplaceInFileTool.class);
    private final Project project;

    public ReplaceInFileTool(Project project) {
        super("replace_in_file", "Searches for text in a file and replaces it with LF line endings. For Java files, ignores whitespace variations.");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String filePath = getStringParam(params, "filePath", null);
        String searchText = getStringParam(params, "search", null);
        String replaceText = getStringParam(params, "replace", null);
        boolean useRegex = getBooleanParam(params, "regex", false);
        boolean caseSensitive = getBooleanParam(params, "caseSensitive", true);
        boolean ignoreWhitespace = getBooleanParam(params, "ignoreWhitespace", false);

        if (filePath == null || filePath.isEmpty()) return "Error: File path is required";
        if (searchText == null || searchText.isEmpty()) return "Error: Search text is required";
        if (replaceText == null) replaceText = ""; // Allow empty replacement to delete text

        // Auto-enable whitespace ignoring for Java files
        if (filePath.toLowerCase().endsWith(".java")) ignoreWhitespace = true;

        // Run in background with proper error handling
        return replaceInFileInBackground(filePath, searchText, replaceText, caseSensitive, ignoreWhitespace);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "path/to/file.java");
        params.addProperty("search", "text to find");
        params.addProperty("replace", "replacement text");
        params.addProperty("regex", false);
        params.addProperty("caseSensitive", true);
        params.addProperty("ignoreWhitespace", false); // Auto-enabled for Java files
        return params;
    }

    /**
     * Run the search and replace operation in a background thread to avoid blocking UI.
     */
    private String replaceInFileInBackground(String filePath, String searchText, String replaceText,
                                             boolean caseSensitive, boolean ignoreWhitespace) {
        // Validate inputs first
        Path fullPath = resolvePath(filePath);
        if (fullPath == null) {
            String error = "Error: No base directory found for the project.";
            showErrorDialog("Replace in File Error", error);
            return error;
        }

        File targetFile = fullPath.toFile();
        if (!targetFile.exists() || !targetFile.isFile()) {
            String error = "Error: File not found: " + filePath;
            showErrorDialog("Replace in File Error", error);
            return error;
        }

        // Run the search in background with comprehensive error handling
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Searching in " + filePath, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("Searching for matches...");
                    indicator.setIndeterminate(false);

                    // Perform the actual search and replace
                    ReplaceResult result = performSearchAndReplace(fullPath, searchText, replaceText,
                            caseSensitive, ignoreWhitespace, indicator);

                    // Handle different outcomes
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (result.replacementCount == 0) {
                            // Show info dialog for no matches
                            Messages.showInfoMessage(project,
                                    "No matches found for the search text in " + filePath,
                                    "Replace in File - No Matches");
                            return;
                        }

                        // If no actual changes (shouldn't happen if replacementCount > 0, but safety check)
                        if (result.originalContent.equals(result.modifiedContent)) {
                            Messages.showInfoMessage(project,
                                    "Found " + result.replacementCount + " matches but no changes were needed in " + filePath,
                                    "Replace in File - No Changes");
                            return;
                        }

                        // Show diff and apply changes
                        showDiffAndApplyChanges(fullPath, result.originalContent,
                                result.modifiedContent, result.replacementCount,
                                ignoreWhitespace);
                    });

                } catch (Exception e) {
                    LOG.error("Error processing replace in file request", e);
                    // Show error dialog on UI thread
                    ApplicationManager.getApplication().invokeLater(() -> {
                        String errorMsg = "Error during search and replace operation:\n" + e.getMessage();
                        showErrorDialog("Replace in File Error", errorMsg);
                    });
                }
            }
        });

        return "Starting search and replace operation in background. Check the status bar for progress.";
    }

    /**
     * Shows an error dialog to the user
     */
    private void showErrorDialog(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, message, title);
        });
    }

    /**
     * Shows an info dialog to the user
     */
    private void showInfoDialog(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(project, message, title);
        });
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
     * 4. Replaces line by line, performing replacement on the original line,
     *    replacing search text (trimmed) by replace text (trimmed)
     */
    public static ReplaceResult performSearchAndReplace(Path fullPath, String searchText, String replaceText,
                                                        boolean caseSensitive, boolean ignoreWhitespace,
                                                        ProgressIndicator indicator) throws Exception {

        // Validate file is readable
        if (!Files.isReadable(fullPath)) {
            throw new RuntimeException("File is not readable: " + fullPath);
        }

        // Read all lines from the file with proper error handling
        List<String> originalLines;
        try {
            originalLines = Files.readAllLines(fullPath, StandardCharsets.UTF_8)
                    .stream()
                    .filter(v -> !v.isEmpty())
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        }

        List<String> modifiedLines = new ArrayList<>(originalLines.size());

        // Validate search text
        if (searchText == null || searchText.trim().isEmpty()) {
            throw new RuntimeException("Search text cannot be empty");
        }

        // 1. Split search and replace text into lines & trim each line if needed
        String[] searchLines = searchText.split("(\r?\n)");
        String[] replaceLines = replaceText.split("(\r?\n)");

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

            // 2. Search for the first line of the pattern
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
                    // 3. Check subsequent lines for a complete match
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
                            }
                            else {
                                isFullMatch = false;
                                break;
                            }
                        }
                    }

                    if (isFullMatch) {
                        // 4. We have a complete match - replace it
                        replacementCount++;
                        // Replace with the corresponding lines from replaceLine
                        for (int i = 0; i < replaceLines.length; i++) {
                            // Add the indentation to each replacement line
                            modifiedLines.add(replaceLines[i]);
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
     * Now includes comprehensive error handling and user feedback.
     */
    private void showDiffAndApplyChanges(Path filePath, String origContent, String modifiedContent,
                                         int replacementCount, boolean ignoreWhitespace) {
        try {
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.toString());
            if (vFile == null) {
                String error = "Error: Could not find file in virtual file system: " + filePath;
                LOG.error(error);
                showErrorDialog("Replace in File Error", error);
                return;
            }

            // Create diff contents
            DiffContentFactory diffFactory = DiffContentFactory.getInstance();
            DocumentContent leftContent = diffFactory.create(origContent);
            DocumentContent rightContent = diffFactory.create(modifiedContent);

            // Create diff request with titles
            String title = "Changes with LF normalization: " + vFile.getName() +
                    " (" + replacementCount + " replacements)" +
                    (ignoreWhitespace ? " [Ignoring whitespace variations]" : "");

            SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                    title, leftContent, rightContent, "Original", "After Replacements (with LF line endings)");

            // Show diff dialog
            com.intellij.diff.DiffManager.getInstance().showDiff(project, diffRequest, DiffDialogHints.MODAL);

            // Ask for confirmation
            String message = "Apply " + replacementCount + " replacements to " + filePath + "?\n" +
                    "Note: Line endings will be normalized to LF (\\n)" +
                    (ignoreWhitespace ? "\nNote: Whitespace variations were ignored in Java file" : "");

            int option = Messages.showYesNoDialog(project, message, "Confirm Changes",
                    "Apply", "Cancel", Messages.getQuestionIcon());

            if (option != Messages.YES) {
                showInfoDialog("Replace in File", "Changes were cancelled by user.");
                return; // Changes discarded
            }

            // Execute write action on UI thread with error handling
            try {
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        // Check if file is writable
                        if (!vFile.isWritable()) {
                            throw new RuntimeException("File is not writable: " + filePath);
                        }

                        // Update file content
                        vFile.refresh(false, false);
                        vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        // Show success message
                        showInfoDialog("Replace in File Success",
                                "Successfully applied " + replacementCount + " replacements to " + vFile.getName());

                    } catch (Exception e) {
                        String error = "Error updating file: " + e.getMessage();
                        LOG.error(error, e);
                        showErrorDialog("Replace in File Error", error);
                    }
                });
            } catch (Exception e) {
                String error = "Error executing write command: " + e.getMessage();
                LOG.error(error, e);
                showErrorDialog("Replace in File Error", error);
            }

        } catch (Exception e) {
            String error = "Error showing diff dialog: " + e.getMessage();
            LOG.error(error, e);
            showErrorDialog("Replace in File Error", error);
        }
    }
}