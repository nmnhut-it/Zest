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
 * Runs search and replace in background to avoid blocking UI.
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

        // Run in background
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
        // Resolve file path and check if file exists
        Path fullPath = resolvePath(filePath);
        if (fullPath == null) return "No base directory found for the project.";

        File targetFile = fullPath.toFile();
        if (!targetFile.exists() || !targetFile.isFile()) return "Error: File not found: " + filePath;

        // Run the search in background
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Searching in " + filePath, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("Searching for matches...");
                    indicator.setIndeterminate(false);
                    
                    // Perform the actual search and replace
                    ReplaceResult result = performSearchAndReplace(fullPath, searchText, replaceText, 
                                                                caseSensitive, ignoreWhitespace, indicator);
                    
                    if (result.replacementCount == 0) {
                        return; // No matches found
                    }
                    
                    // If no actual changes
                    if (result.originalContent.equals(result.modifiedContent)) {
                        return; // No changes to content
                    }
                    
                    // Show diff and apply changes on UI thread
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showDiffAndApplyChanges(fullPath, result.originalContent, 
                                             result.modifiedContent, result.replacementCount, 
                                             ignoreWhitespace);
                    });
                    
                } catch (Exception e) {
                    LOG.error("Error processing replace in file request", e);
                }
            }
        });
        
        return "Starting search and replace operation in background. Check the status bar for progress.";
    }
    
    /**
     * Data class to hold the result of search and replace operation.
     */
    private static class ReplaceResult {
        final String originalContent;
        final String modifiedContent;
        final int replacementCount;
        
        ReplaceResult(String originalContent, String modifiedContent, int replacementCount) {
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.replacementCount = replacementCount;
        }
    }
    
    /**
     * Perform the actual search and replace operation line by line.
     * Ultra-simple implementation: replace trimmed search text with trimmed replace text.
     */
    private ReplaceResult performSearchAndReplace(Path fullPath, String searchText, String replaceText,
                                               boolean caseSensitive, boolean ignoreWhitespace,
                                               ProgressIndicator indicator) throws Exception {
        // Count total lines for progress reporting
        long totalLines = Files.lines(fullPath).count();
        long linesProcessed = 0;
        int replacementCount = 0;
        
        List<String> originalLines = new ArrayList<>();
        List<String> modifiedLines = new ArrayList<>();
        
        // Prepare search text - trim if ignoring whitespace
        String trimmedSearchText = ignoreWhitespace ? searchText.trim() : searchText;
        String trimmedReplaceText = ignoreWhitespace ? replaceText.trim() : replaceText;
        
        // For both single-line and multi-line patterns
        try (BufferedReader reader = Files.newBufferedReader(fullPath, StandardCharsets.UTF_8)) {
            String line;
            
            // Process each line
            while ((line = reader.readLine()) != null) {
                originalLines.add(line);
                
                // Update progress periodically
                linesProcessed++;
                if (linesProcessed % 1000 == 0) {
                    indicator.setFraction((double) linesProcessed / totalLines);
                    indicator.setText2("Processed " + linesProcessed + " of " + totalLines + " lines");
                }
                
                // Check if line contains the search text (using trimmed version for comparison)
                String lineToCheck = ignoreWhitespace ? line.trim() : line;
                boolean match = false;
                
                if (caseSensitive) {
                    match = lineToCheck.contains(trimmedSearchText);
                } else {
                    match = lineToCheck.toLowerCase().contains(trimmedSearchText.toLowerCase());
                }
                
                if (match) {
                    // Found a match - replace directly in the original line
                    String newLine;
                    
                    if (caseSensitive) {
                        newLine = line.replace(trimmedSearchText, trimmedReplaceText);
                    } else {
                        // Case-insensitive replacement - have to do it manually
                        String lineToSearch = line;
                        String searchFor = trimmedSearchText;
                        
                        if (!caseSensitive) {
                            lineToSearch = lineToSearch.toLowerCase();
                            searchFor = searchFor.toLowerCase();
                        }
                        
                        // Find all occurrences and replace them
                        int lastIndex = 0;
                        StringBuilder result = new StringBuilder();
                        
                        while (lastIndex < line.length()) {
                            int indexOf = lineToSearch.indexOf(searchFor, lastIndex);
                            if (indexOf == -1) {
                                // No more occurrences
                                result.append(line.substring(lastIndex));
                                break;
                            }
                            
                            // Add text up to this occurrence
                            result.append(line.substring(lastIndex, indexOf));
                            
                            // Add the replacement
                            result.append(trimmedReplaceText);
                            
                            // Move past this occurrence
                            lastIndex = indexOf + searchFor.length();
                        }
                        
                        newLine = result.toString();
                    }
                    
                    modifiedLines.add(newLine);
                    replacementCount++;
                } else {
                    // No match - keep line as is
                    modifiedLines.add(line);
                }
            }
        }
        
        // Build the complete content
        String originalContent = String.join("\n", originalLines);
        String modifiedContent = String.join("\n", modifiedLines);
        
        indicator.setFraction(1.0);
        indicator.setText("Search complete. " + replacementCount + " replacements found.");
        
        return new ReplaceResult(originalContent, modifiedContent, replacementCount);
    }

    private Path resolvePath(String filePath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return new File(filePath).isAbsolute() ? Paths.get(filePath) : Paths.get(basePath, filePath);
    }

    /**
     * Shows diff dialog and applies changes if confirmed by user.
     */
    private void showDiffAndApplyChanges(Path filePath, String origContent, String modifiedContent,
                                       int replacementCount, boolean ignoreWhitespace) {
        VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.toString());
        if (vFile == null) {
            LOG.error("Error: Could not find file in virtual file system: " + filePath);
            return;
        }

        try {
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
                return; // Changes discarded
            }
            
            // Execute write action on UI thread
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    // Update file content
                    vFile.refresh(false, false);
                    vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));
                    FileEditorManager.getInstance(project).openFile(vFile, true);
                } catch (Exception e) {
                    LOG.error("Error updating file", e);
                }
            });
        } catch (Exception e) {
            LOG.error("Error showing diff dialog", e);
        }
    }
}