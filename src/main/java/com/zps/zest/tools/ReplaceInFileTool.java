package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.EnhancedTodoDiffComponent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool for searching and replacing text in files, with diff preview before applying changes.
 */
public class ReplaceInFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ReplaceInFileTool.class);
    private final Project project;

    public ReplaceInFileTool(Project project) {
        super("replace_in_file", "Searches for text in a file and replaces it. You can use search by regex first, then use this tool.");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String filePath = getStringParam(params, "filePath", null);
        String searchText = getStringParam(params, "search", null);
        String replaceText = getStringParam(params, "replace", null);
        boolean useRegex = getBooleanParam(params, "regex", false);
        boolean caseSensitive = getBooleanParam(params, "caseSensitive", true);

        if (filePath == null || filePath.isEmpty()) {
            return "Error: File path is required";
        }
        if (searchText == null || searchText.isEmpty()) {
            return "Error: Search text is required";
        }
        if (replaceText == null) {
            replaceText = ""; // Allow empty replacement to delete text
        }

        return replaceInFileWithDiff(filePath, searchText, replaceText, useRegex, caseSensitive);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "path/to/file.java");
        params.addProperty("search", "text to find");
        params.addProperty("replace", "replacement text");
        params.addProperty("regex", false);
        params.addProperty("caseSensitive", true);
        return params;
    }
    /**
     * Searches for text in a file and replaces it, showing a Git-like diff for review before applying changes.
     * Uses IntelliJ's built-in diff tools for a familiar interface.
     */
    private String replaceInFileWithDiff(String filePath, String searchText, String replaceText,
                                         boolean useRegex, boolean caseSensitive) {
        try {
            // Handle relative or absolute path
            String basePath = project.getBasePath();
            if (basePath == null) {
                return "No base directory found for the project.";
            }

            Path fullPath;
            if (new File(filePath).isAbsolute()) {
                fullPath = Paths.get(filePath);
            } else {
                fullPath = Paths.get(basePath, filePath);
            }

            // Check if file exists
            File targetFile = fullPath.toFile();
            if (!targetFile.exists() || !targetFile.isFile()) {
                return "Error: File not found: " + filePath;
            }

            // Read the file content
            String originalContent;
            try {
                originalContent = Files.readString(fullPath);
            } catch (Exception e) {
                LOG.error("Could not read file content: " + e.getMessage(), e);
                return "Error: Could not read file content: " + e.getMessage();
            }

            // Process the replacement
            String modifiedContent;
            int replacementCount = 0;

            try {
                if (useRegex) {
                    // Handle regex replacement
                    Pattern pattern = caseSensitive
                            ? Pattern.compile(searchText)
                            : Pattern.compile(searchText, Pattern.CASE_INSENSITIVE);

                    Matcher matcher = pattern.matcher(originalContent);
                    StringBuffer result = new StringBuffer();

                    while (matcher.find()) {
                        replacementCount++;
                        matcher.appendReplacement(result, Matcher.quoteReplacement(replaceText));
                    }
                    matcher.appendTail(result);

                    modifiedContent = result.toString();
                } else {
                    // Handle literal text replacement
                    if (caseSensitive) {
                        // Count occurrences
                        replacementCount = countOccurrences(originalContent, searchText);
                        modifiedContent = originalContent.replace(searchText, replaceText);
                    } else {
                        // Case insensitive replacement
                        Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);
                        Matcher matcher = pattern.matcher(originalContent);
                        StringBuffer result = new StringBuffer();

                        while (matcher.find()) {
                            replacementCount++;
                            matcher.appendReplacement(result, Matcher.quoteReplacement(replaceText));
                        }
                        matcher.appendTail(result);

                        modifiedContent = result.toString();
                    }
                }
            } catch (PatternSyntaxException pse) {
                return "Error: Invalid regular expression: " + pse.getMessage();
            }

            // If no changes were made, return early
            if (replacementCount == 0) {
                return "No matches found for '" + searchText + "' in file: " + filePath;
            }

            // If content is unchanged (perhaps replacing with the same text)
            if (originalContent.equals(modifiedContent)) {
                return "Search text found but replacement resulted in no changes to content.";
            }

            // Get VirtualFile for the file
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
            if (vFile == null) {
                return "Error: Could not find file in virtual file system: " + filePath;
            }

            // Create Git-like diff UI
            // Use IntelliJ's built-in diff tools to create a git-like experience
            final boolean[] userConfirmed = new boolean[1];
            final String[] resultMessage = new String[1];

            int finalReplacementCount1 = replacementCount;
            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    // Create diff contents
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();

                    // Create simple content objects without custom keys
                    DocumentContent leftContent = diffFactory.create(originalContent);
                    DocumentContent rightContent = diffFactory.create(modifiedContent);

                    // Create request with descriptive titles
                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            "Changes to " + vFile.getName() + " (" + finalReplacementCount1 + " replacements). You can confirm after closing this dialog.",
                            leftContent,
                            rightContent,
                            "Original",
                            "After Replacements"
                    );

                    // Use default dialog hints
                    com.intellij.diff.DiffDialogHints dialogHints = DiffDialogHints.MODAL;

                    // Show the diff dialog
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, diffRequest, dialogHints);

                    // Ask for confirmation with git-like terminology
                    int option = Messages.showYesNoDialog(
                            project,
                            "Apply " + finalReplacementCount1 + " replacements to " + filePath + "?",
                            "Confirm Changes",
                            "Apply", // Yes button
                            "Cancel", // No button
                            Messages.getQuestionIcon()
                    );

                    userConfirmed[0] = (option == Messages.YES);

                    if (!userConfirmed[0]) {
                        resultMessage[0] = "Changes discarded by user.";
                    }
                } catch (Exception e) {
                    LOG.error("Error showing diff dialog", e);
                    resultMessage[0] = "Error showing diff dialog: " + e.getMessage();
                }
            });

            // Check if there was an error or user cancellation
            if (resultMessage[0] != null) {
                return resultMessage[0];
            }

            // Apply changes if confirmed
            if (userConfirmed[0]) {
                final int finalReplacementCount = replacementCount;

                return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                    try {
                        // Refresh to make sure we have the latest version
                        vFile.refresh(false, false);

                        // Update file content
                        vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));

                        // Open the file to show the changes
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        return "Successfully applied " + finalReplacementCount + " replacements to " + filePath;
                    } catch (Exception e) {
                        LOG.error("Error updating file: " + filePath, e);
                        return "Error updating file: " + e.getMessage();
                    }
                });
            } else {
                return "Changes were not applied - discarded by user.";
            }
        } catch (Exception e) {
            LOG.error("Error processing replace in file request", e);
            return "Error processing replace in file request: " + e.getMessage();
        }
    }
    /**
     * Counts the number of occurrences of a substring in a string.
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}