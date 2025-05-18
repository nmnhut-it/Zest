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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool for searching and replacing text in files with LF normalization.
 * For Java files, ignores whitespace and tabs during search.
 * Shows diff preview before applying changes.
 */
public class ReplaceInFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ReplaceInFileTool.class);
    private final Project project;

    public ReplaceInFileTool(Project project) {
        super("replace_in_file", "Searches for text in a file and replaces it with LF line endings. Ignores whitespace in Java files.");
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

        if (filePath == null || filePath.isEmpty()) {
            return "Error: File path is required";
        }
        if (searchText == null || searchText.isEmpty()) {
            return "Error: Search text is required";
        }
        if (replaceText == null) {
            replaceText = ""; // Allow empty replacement to delete text
        }

        // If file is Java, automatically enable whitespace ignoring
        if (filePath.toLowerCase().endsWith(".java")) {
            ignoreWhitespace = true;
        }

        return replaceInFileWithDiff(filePath, searchText, replaceText, useRegex, caseSensitive, ignoreWhitespace);
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
     * Performs search and replace with a diff preview, normalizing to LF line endings.
     * For Java files, can ignore whitespace and tabs during search.
     */
    private String replaceInFileWithDiff(String filePath, String searchText, String replaceText,
                                         boolean useRegex, boolean caseSensitive, boolean ignoreWhitespace) {
        try {
            // Handle relative or absolute path
            String basePath = project.getBasePath();
            if (basePath == null) {
                return "No base directory found for the project.";
            }

            Path fullPath = new File(filePath).isAbsolute() ?
                    Paths.get(filePath) :
                    Paths.get(basePath, filePath);

            // Check if file exists
            File targetFile = fullPath.toFile();
            if (!targetFile.exists() || !targetFile.isFile()) {
                return "Error: File not found: " + filePath;
            }

            // Read the file content
            String originalContent;
            try {
                byte[] fileBytes = Files.readAllBytes(fullPath);
                originalContent = new String(fileBytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.error("Could not read file content: " + e.getMessage(), e);
                return "Error: Could not read file content: " + e.getMessage();
            }

            // Normalize line endings to LF
            String normalizedContent = normalizeToLF(originalContent);

            // Process the replacement
            String modifiedContent;
            int replacementCount = 0;

            try {
                if (normalizedContent.isEmpty()) {
                    return "File is empty: " + filePath;
                }

                if (useRegex) {
                    // Handle regex differently based on ignoreWhitespace flag
                    if (ignoreWhitespace) {
                        // Modify the regex to be whitespace-insensitive
                        searchText = prepareWhitespaceInsensitiveRegex(searchText);
                        int flags = caseSensitive ? Pattern.DOTALL : Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
                        replacementCount = performWhitespaceInsensitiveReplacement(
                                normalizedContent, searchText, replaceText, flags);
                        modifiedContent = performRegexReplacementPreservingWhitespace(
                                normalizedContent, searchText, replaceText, flags);
                    } else {
                        // Standard regex replacement
                        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                        replacementCount = performRegexReplacement(normalizedContent, searchText,
                                replaceText, caseSensitive);
                        Pattern pattern = Pattern.compile(searchText, flags);
                        modifiedContent = pattern.matcher(normalizedContent).replaceAll(replaceText);
                    }
                } else {
                    // Literal text replacement
                    if (ignoreWhitespace) {
                        // Convert literal search to regex with whitespace flexibility
                        String whitespaceInsensitiveRegex = Pattern.quote(searchText)
                                .replaceAll("\\\\\\s+", "\\\\s*")
                                .replaceAll("\\s+", "\\\\s+");

                        int flags = caseSensitive ? Pattern.DOTALL : Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
                        replacementCount = performWhitespaceInsensitiveReplacement(
                                normalizedContent, whitespaceInsensitiveRegex, replaceText, flags);
                        modifiedContent = performRegexReplacementPreservingWhitespace(
                                normalizedContent, whitespaceInsensitiveRegex, replaceText, flags);
                    } else {
                        // Standard literal replacement
                        if (caseSensitive) {
                            replacementCount = countMatches(normalizedContent, searchText);
                            modifiedContent = normalizedContent.replace(searchText, replaceText);
                        } else {
                            // Case insensitive
                            Pattern pattern = Pattern.compile(Pattern.quote(searchText),
                                    Pattern.CASE_INSENSITIVE);
                            Matcher matcher = pattern.matcher(normalizedContent);

                            // Count matches
                            int count = 0;
                            while (matcher.find()) {
                                count++;
                            }
                            replacementCount = count;

                            // Perform replacement
                            matcher.reset();
                            modifiedContent = matcher.replaceAll(replaceText);
                        }
                    }
                }
            } catch (PatternSyntaxException pse) {
                return "Error: Invalid regular expression: " + pse.getMessage();
            }

            // If no changes were made, return early
            if (replacementCount == 0) {
                String message = "No matches found for '" + searchText + "' in file: " + filePath;
                if (ignoreWhitespace) {
                    message += " (with whitespace ignored)";
                }
                return message;
            }

            // If content is unchanged
            if (normalizedContent.equals(modifiedContent)) {
                return "Search text found but replacement resulted in no changes to content.";
            }

            // Show diff and get confirmation
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
            if (vFile == null) {
                return "Error: Could not find file in virtual file system: " + filePath;
            }

            final boolean[] userConfirmed = new boolean[1];
            final String[] resultMessage = new String[1];
            final int finalCount = replacementCount;
            final boolean finalIgnoreWhitespace = ignoreWhitespace;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    // Create diff contents
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                    DocumentContent leftContent = diffFactory.create(originalContent);
                    DocumentContent rightContent = diffFactory.create(modifiedContent);

                    // Create request with descriptive titles
                    String title = "Changes with LF normalization: " + vFile.getName() +
                            " (" + finalCount + " replacements)";
                    if (finalIgnoreWhitespace) {
                        title += " [Whitespace ignored]";
                    }

                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            title,
                            leftContent,
                            rightContent,
                            "Original",
                            "After Replacements (with LF line endings)"
                    );

                    // Show the diff dialog
                    com.intellij.diff.DiffManager.getInstance().showDiff(project, diffRequest, DiffDialogHints.MODAL);

                    // Ask for confirmation
                    String message = "Apply " + finalCount + " replacements to " + filePath + "?\n" +
                            "Note: Line endings will be normalized to LF (\\n)";
                    if (finalIgnoreWhitespace) {
                        message += "\nNote: Whitespace differences were ignored for this Java file";
                    }

                    int option = Messages.showYesNoDialog(
                            project,
                            message,
                            "Confirm Changes",
                            "Apply",
                            "Cancel",
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

            if (resultMessage[0] != null) {
                return resultMessage[0];
            }

            // Apply changes if confirmed
            if (userConfirmed[0]) {
                return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                    try {
                        // Refresh to make sure we have the latest version
                        vFile.refresh(false, false);

                        // Update file content
                        vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));

                        // Open the file to show the changes
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        String message = "Successfully applied " + finalCount +
                                " replacements to " + filePath + " with normalized LF line endings.";
                        if (finalIgnoreWhitespace) {
                            message += " Whitespace differences were ignored.";
                        }
                        return message;
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
     * Convert a regular expression to be whitespace-insensitive.
     * This replaces whitespace with flexible whitespace patterns.
     */
    private String prepareWhitespaceInsensitiveRegex(String regex) {
        // This is a simplified approach - for complex regex, a proper parser would be better
        StringBuilder result = new StringBuilder();
        boolean inCharClass = false;
        boolean escaped = false;

        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);

            if (escaped) {
                result.append('\\').append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '[') {
                inCharClass = true;
                result.append(c);
            } else if (c == ']') {
                inCharClass = false;
                result.append(c);
            } else if (Character.isWhitespace(c) && !inCharClass) {
                // Replace whitespace with a pattern that matches any whitespace
                result.append("\\s*");
            } else {
                result.append(c);
            }
        }

        if (escaped) {
            result.append('\\'); // Trailing backslash
        }

        return result.toString();
    }

    /**
     * Performs a whitespace-insensitive regex search and counts matches.
     */
    private int performWhitespaceInsensitiveReplacement(String text, String regex,
                                                        String replacement, int flags) {
        Pattern pattern = Pattern.compile(regex, flags);
        Matcher matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Performs a regex replacement while attempting to preserve original whitespace.
     */
    private String performRegexReplacementPreservingWhitespace(String text, String regex,
                                                               String replacement, int flags) {
        Pattern pattern = Pattern.compile(regex, flags);
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String match = matcher.group();
            String modified = replacement;

            // Try to preserve leading/trailing whitespace from the original match
            String leadingWhitespace = "";
            String trailingWhitespace = "";

            int leadingCount = 0;
            while (leadingCount < match.length() && Character.isWhitespace(match.charAt(leadingCount))) {
                leadingWhitespace += match.charAt(leadingCount);
                leadingCount++;
            }

            int trailingCount = 0;
            while (trailingCount < match.length() &&
                    Character.isWhitespace(match.charAt(match.length() - 1 - trailingCount))) {
                trailingWhitespace = match.charAt(match.length() - 1 - trailingCount) + trailingWhitespace;
                trailingCount++;
            }

            // Add the preserved whitespace to the replacement
            modified = leadingWhitespace + modified + trailingWhitespace;

            // Escape $ and \ for the replacement string
            modified = modified.replace("\\", "\\\\").replace("$", "\\$");

            matcher.appendReplacement(result, modified);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Normalize all line endings to LF.
     */
    private String normalizeToLF(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        // First replace all Windows line endings (\r\n) with \n
        String result = content.replace("\r\n", "\n");

        // Then replace any remaining old Mac line endings (\r) with \n
        result = result.replace("\r", "\n");

        return result;
    }

    /**
     * Count matches for a regex pattern.
     */
    private int performRegexReplacement(String text, String regex, String replacement,
                                        boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern pattern = Pattern.compile(regex, flags);
        Matcher matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Count occurrences of a literal string.
     */
    private int countMatches(String text, String str) {
        if (text.isEmpty() || str.isEmpty()) {
            return 0;
        }

        int count = 0;
        int index = 0;
        while ((index = text.indexOf(str, index)) != -1) {
            count++;
            index += str.length();
        }

        return count;
    }
}