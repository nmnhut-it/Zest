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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool for searching and replacing text in files, with diff preview before applying changes.
 * Enhanced to handle different line endings and optimized for performance.
 */
public class ReplaceInFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ReplaceInFileTool.class);
    private final Project project;

    // Constants for line endings
    private static final String WINDOWS_LINE_ENDING = "\r\n";
    private static final String UNIX_LINE_ENDING = "\n";

    public ReplaceInFileTool(Project project) {
        super("replace_in_file", "Searches for text in a file and replaces it. Handles different line endings and uses regex efficiently.");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String filePath = getStringParam(params, "filePath", null);
        String searchText = getStringParam(params, "search", null);
        String replaceText = getStringParam(params, "replace", null);
        boolean useRegex = getBooleanParam(params, "regex", false);
        boolean caseSensitive = getBooleanParam(params, "caseSensitive", true);
        boolean preserveLineEndings = getBooleanParam(params, "preserveLineEndings", true);

        if (filePath == null || filePath.isEmpty()) {
            return "Error: File path is required";
        }
        if (searchText == null || searchText.isEmpty()) {
            return "Error: Search text is required";
        }
        if (replaceText == null) {
            replaceText = ""; // Allow empty replacement to delete text
        }

        return replaceInFileWithDiff(filePath, searchText, replaceText, useRegex, caseSensitive, preserveLineEndings);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "path/to/file.java");
        params.addProperty("search", "text to find");
        params.addProperty("replace", "replacement text");
        params.addProperty("regex", false);
        params.addProperty("caseSensitive", true);
        params.addProperty("preserveLineEndings", true);
        return params;
    }

    /**
     * Searches for text in a file and replaces it, showing a Git-like diff for review before applying changes.
     * Uses IntelliJ's built-in diff tools for a familiar interface.
     * Enhanced to handle different line endings consistently.
     */
    private String replaceInFileWithDiff(String filePath, String searchText, String replaceText,
                                         boolean useRegex, boolean caseSensitive, boolean preserveLineEndings) {
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

            // Read the file content as a byte array first to preserve exact line endings
            byte[] fileBytes;
            try {
                fileBytes = Files.readAllBytes(fullPath);
            } catch (IOException e) {
                LOG.error("Could not read file content: " + e.getMessage(), e);
                return "Error: Could not read file content: " + e.getMessage();
            }

            // Convert to string
            String originalContent = new String(fileBytes, StandardCharsets.UTF_8);

            // Detect original line ending type
            String originalLineEnding = detectLineEnding(originalContent);

            // Process the replacement
            String modifiedContent;
            AtomicInteger replacementCount = new AtomicInteger(0);

            try {
                modifiedContent = performReplacement(originalContent, searchText, replaceText,
                        useRegex, caseSensitive, replacementCount);
            } catch (PatternSyntaxException pse) {
                return "Error: Invalid regular expression: " + pse.getMessage();
            }

            // If no changes were made, return early
            if (replacementCount.get() == 0) {
                return "No matches found for '" + searchText + "' in file: " + filePath;
            }

            // If content is unchanged (perhaps replacing with the same text)
            if (originalContent.equals(modifiedContent)) {
                return "Search text found but replacement resulted in no changes to content.";
            }

            // Preserve original line endings if requested
            if (preserveLineEndings && !UNIX_LINE_ENDING.equals(originalLineEnding)) {
                // Normalize to LF first (in case the replace operation introduced different line endings)
                modifiedContent = modifiedContent.replace(WINDOWS_LINE_ENDING, UNIX_LINE_ENDING);
                // Then convert back to the original line ending format
                modifiedContent = modifiedContent.replace(UNIX_LINE_ENDING, originalLineEnding);
            }

            // Get VirtualFile for the file
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
            if (vFile == null) {
                return "Error: Could not find file in virtual file system: " + filePath;
            }

            // Create Git-like diff UI with IntelliJ's diff tools
            AtomicBoolean userConfirmed = new AtomicBoolean(false);
            AtomicReference<String> resultMessage = new AtomicReference<>(null);

            String finalModifiedContent1 = modifiedContent;
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    // Create diff contents
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                    DocumentContent leftContent = diffFactory.create(originalContent);
                    DocumentContent rightContent = diffFactory.create(finalModifiedContent1);

                    // Create request with descriptive titles
                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            "Changes to " + vFile.getName() + " (" + replacementCount.get() + " replacements). You can confirm after closing this dialog.",
                            leftContent,
                            rightContent,
                            "Original",
                            "After Replacements"
                    );

                    // Show the diff dialog
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, diffRequest, DiffDialogHints.NON_MODAL);

                    // Ask for confirmation with git-like terminology
                    int option = Messages.showYesNoDialog(
                            project,
                            "Apply " + replacementCount.get() + " replacements to " + filePath + "?",
                            "Confirm Changes",
                            "Apply", // Yes button
                            "Cancel", // No button
                            Messages.getQuestionIcon()
                    );

                    userConfirmed.set(option == Messages.YES);

                    if (!userConfirmed.get()) {
                        resultMessage.set("Changes discarded by user.");
                    }
                } catch (Exception e) {
                    LOG.error("Error showing diff dialog", e);
                    resultMessage.set("Error showing diff dialog: " + e.getMessage());
                }
            });

            // Check if there was an error or user cancellation
            if (resultMessage.get() != null) {
                return resultMessage.get();
            }

            // Apply changes if confirmed
            if (userConfirmed.get()) {
                String finalModifiedContent = modifiedContent;
                return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                    try {
                        // Refresh to make sure we have the latest version
                        vFile.refresh(false, false);

                        // Update file content
                        vFile.setBinaryContent(finalModifiedContent.getBytes(StandardCharsets.UTF_8));

                        // Open the file to show the changes
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        return "Successfully applied " + replacementCount.get() + " replacements to " + filePath;
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
     * Detects the predominant line ending in a string.
     * @param content The content to analyze
     * @return The detected line ending (WINDOWS_LINE_ENDING or UNIX_LINE_ENDING)
     */
    private String detectLineEnding(String content) {
        // Quick check for common case - if there are no CRs, it's definitely Unix style
        if (!content.contains("\r")) {
            return UNIX_LINE_ENDING;
        }

        // Count Windows-style line endings
        int windowsCount = countOccurrences(content, WINDOWS_LINE_ENDING);

        // Count all newlines
        int totalNewlines = countOccurrences(content, "\n");

        // If most newlines have CR before them, it's Windows style
        return (windowsCount > totalNewlines / 2) ? WINDOWS_LINE_ENDING : UNIX_LINE_ENDING;
    }

    /**
     * Performs the actual replacement operation with optimized handling for different pattern types.
     */
    private String performReplacement(String content, String searchText, String replaceText,
                                      boolean useRegex, boolean caseSensitive, AtomicInteger counter) {
        if (useRegex) {
            // Handle regex replacement
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern pattern = Pattern.compile(searchText, flags);
            Matcher matcher = pattern.matcher(content);

            // Use StringBuilder for better performance than StringBuffer
            StringBuilder result = new StringBuilder(content.length());

            int lastEnd = 0;
            while (matcher.find()) {
                counter.incrementAndGet();
                // Append text before the match
                result.append(content, lastEnd, matcher.start());
                // Append replacement text
                String replacement = Matcher.quoteReplacement(replaceText);
                if (replaceText.contains("$")) {
                    // Handle group references if present
                    replacement = matcher.replaceFirst(replaceText);
                    matcher.reset(content); // Reset matcher to start position
                    matcher.find(matcher.start() + 1); // Move to next match position
                } else {
                    result.append(replacement);
                }
                lastEnd = matcher.end();
            }

            // Append remaining content
            result.append(content, lastEnd, content.length());
            return result.toString();
        } else {
            // Handle literal text replacement with optimization for case sensitivity
            if (searchText.isEmpty()) {
                return content; // No changes needed for empty search string
            }

            if (caseSensitive) {
                // Simple case - use indexOf for better performance
                int searchLen = searchText.length();
                int replaceLen = replaceText.length();
                StringBuilder sb = new StringBuilder(content.length() + Math.max(0, (replaceLen - searchLen) * 10)); // Estimate capacity

                int index = 0;
                int foundIndex;
                while ((foundIndex = content.indexOf(searchText, index)) != -1) {
                    counter.incrementAndGet();
                    sb.append(content, index, foundIndex);
                    sb.append(replaceText);
                    index = foundIndex + searchLen;
                }

                if (index < content.length()) {
                    sb.append(content, index, content.length());
                }

                return sb.toString();
            } else {
                // Case insensitive without regex - use Pattern for case insensitivity
                Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(content);

                StringBuilder result = new StringBuilder(content.length());
                int lastEnd = 0;

                while (matcher.find()) {
                    counter.incrementAndGet();
                    // Append text before the match
                    result.append(content, lastEnd, matcher.start());
                    // Append replacement text
                    result.append(replaceText);
                    lastEnd = matcher.end();
                }

                // Append remaining content
                result.append(content, lastEnd, content.length());
                return result.toString();
            }
        }
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     * Optimized implementation using indexOf for better performance.
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