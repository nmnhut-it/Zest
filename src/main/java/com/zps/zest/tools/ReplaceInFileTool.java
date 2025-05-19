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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tool for searching and replacing text in files with LF normalization.
 * For Java files, automatically ignores whitespace variations during search.
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

    private String replaceInFileWithDiff(String filePath, String searchText, String replaceText,
                                         boolean useRegex, boolean caseSensitive, boolean ignoreWhitespace) {
        try {
            // Resolve file path and check if file exists
            Path fullPath = resolvePath(filePath);
            if (fullPath == null) return "No base directory found for the project.";

            File targetFile = fullPath.toFile();
            if (!targetFile.exists() || !targetFile.isFile()) return "Error: File not found: " + filePath;

            // Read and normalize file content
            String originalContent = new String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8);
            if (originalContent.isEmpty()) return "File is empty: " + filePath;

            // Normalize line endings to LF
            String normalizedContent = originalContent.replace("\r\n", "\n").replace("\r", "\n");

            // Process the replacement
            String modifiedContent;
            int replacementCount;

            try {
                if (ignoreWhitespace) {
                    // Process with whitespace flexibility for Java files
                    String flexPattern = useRegex ?
                            makeRegexFlexible(searchText) :
                            makeStringFlexible(searchText);

                    int flags = caseSensitive ? Pattern.DOTALL : Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
                    Pattern pattern = Pattern.compile(flexPattern, flags);

                    // Find all matches and perform replacement with whitespace preservation
                    replacementCount = 0;
                    StringBuffer resultBuffer = new StringBuffer();
                    Matcher matcher = pattern.matcher(normalizedContent);

                    while (matcher.find()) {
                        replacementCount++;
                        String matchedText = matcher.group();

                        // Preserve whitespace from the original match
                        String replacement = preserveWhitespace(matchedText, replaceText);

                        // Escape special characters in replacement string
                        replacement = Matcher.quoteReplacement(replacement);
                        matcher.appendReplacement(resultBuffer, replacement);
                    }

                    matcher.appendTail(resultBuffer);
                    modifiedContent = resultBuffer.toString();
                } else {
                    // Standard replacement without whitespace flexibility
                    if (useRegex) {
                        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                        Pattern pattern = Pattern.compile(searchText, flags);
                        Matcher matcher = pattern.matcher(normalizedContent);

                        replacementCount = countMatches(matcher);
                        matcher.reset();
                        modifiedContent = matcher.replaceAll(replaceText);
                    } else {
                        if (caseSensitive) {
                            replacementCount = countLiteralMatches(normalizedContent, searchText);
                            modifiedContent = normalizedContent.replace(searchText, replaceText);
                        } else {
                            Pattern pattern = Pattern.compile(Pattern.quote(searchText), Pattern.CASE_INSENSITIVE);
                            Matcher matcher = pattern.matcher(normalizedContent);

                            replacementCount = countMatches(matcher);
                            matcher.reset();
                            modifiedContent = matcher.replaceAll(replaceText);
                        }
                    }
                }
            } catch (Exception e) {
                return "Error processing search/replace: " + e.getMessage();
            }

            // Return if no matches or no changes
            if (replacementCount == 0) {
                return "No matches found for '" + searchText + "' in file: " + filePath +
                        (ignoreWhitespace ? " (ignoring whitespace variations)" : "");
            }
            if (normalizedContent.equals(modifiedContent)) {
                return "Search text found but replacement resulted in no changes to content.";
            }

            // Show diff and apply changes
            return showDiffAndApplyChanges(fullPath, originalContent, modifiedContent, replacementCount, ignoreWhitespace);

        } catch (Exception e) {
            LOG.error("Error processing replace in file request", e);
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String filePath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return new File(filePath).isAbsolute() ? Paths.get(filePath) : Paths.get(basePath, filePath);
    }

    /**
     * Makes a regex pattern more flexible for whitespace variations in Java code.
     */
    private String makeRegexFlexible(String regex) {
        // Process regex to make it more flexible with whitespace
        StringBuilder result = new StringBuilder();
        boolean inCharClass = false;
        boolean escaped = false;

        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);

            if (escaped) {
                // Handle escape sequences
                if (c == 's') {
                    // \s becomes flexible whitespace matcher
                    result.append("[ \\t\\r\\n]+");
                } else if (c == 't') {
                    // \t is treated like any whitespace
                    result.append("[ \\t]+");
                } else if (c == 'n' || c == 'r') {
                    // \n and \r match optional whitespace with a newline
                    result.append("\\s*[\\r\\n]\\s*");
                } else {
                    // Keep other escape sequences intact
                    result.append('\\').append(c);
                }
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
                // Any whitespace becomes flexible
                if (c == '\n' || c == '\r') {
                    // Newlines match optional whitespace with a newline
                    result.append("\\s*[\\r\\n]\\s*");
                } else {
                    // Other whitespace matches any horizontal whitespace
                    result.append("[ \\t]*");
                }
            } else if (!inCharClass && isJavaSyntaxChar(c)) {
                // Add optional whitespace around Java syntax chars
                result.append("[ \\t]*").append(c).append("[ \\t]*");
            } else {
                result.append(c);
            }
        }

        if (escaped) result.append('\\');
        return result.toString();
    }

    /**
     * Makes a literal string more flexible for whitespace variations in Java code.
     */
    private String makeStringFlexible(String literal) {
        // Add escape for regex special chars and make whitespace flexible
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);

            if (Character.isWhitespace(c)) {
                if (c == '\n' || c == '\r') {
                    // Newlines match optional whitespace with a newline
                    result.append("\\s*[\\r\\n]\\s*");
                } else {
                    // Other whitespace matches any amount of horizontal whitespace
                    result.append("[ \\t]*");
                }
            } else if (isJavaSyntaxChar(c)) {
                // Add optional whitespace around Java syntax chars
                result.append("[ \\t]*").append(Pattern.quote(String.valueOf(c))).append("[ \\t]*");
            } else if (isRegexSpecial(c)) {
                // Escape regex special chars
                result.append('\\').append(c);
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * Check if character is a Java syntax character that often has whitespace around it.
     */
    private boolean isJavaSyntaxChar(char c) {
        return c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']' ||
                c == ';' || c == ',' || c == '.' || c == ':' || c == '=' || c == '+' ||
                c == '-' || c == '*' || c == '/' || c == '%' || c == '&' || c == '|' ||
                c == '^' || c == '!' || c == '~' || c == '<' || c == '>' || c == '?';
    }

    /**
     * Check if character is a regex special character.
     */
    private boolean isRegexSpecial(char c) {
        return c == '\\' || c == '^' || c == '$' || c == '.' || c == '|' ||
                c == '?' || c == '*' || c == '+' || c == '(' || c == ')' ||
                c == '[' || c == ']' || c == '{' || c == '}';
    }

    /**
     * Preserves whitespace from original matched text when replacing.
     */
    private String preserveWhitespace(String originalMatch, String replacement) {
        // Extract whitespace from original match
        String leadingWhitespace = extractLeadingWhitespace(originalMatch);
        String trailingWhitespace = extractTrailingWhitespace(originalMatch);

        // Extract indentation and line structure from original match
        String indentation = extractIndentation(originalMatch);
        boolean hasNewline = originalMatch.contains("\n");

        if (hasNewline && replacement.contains("\n")) {
            // For multi-line replacements, apply the indentation to each line
            StringBuilder formattedReplacement = new StringBuilder();
            String[] lines = replacement.split("\n");

            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    formattedReplacement.append('\n').append(indentation);
                }
                formattedReplacement.append(lines[i].trim());
            }

            return leadingWhitespace + formattedReplacement.toString() + trailingWhitespace;
        } else {
            // For single-line replacements or when original has no newlines
            return leadingWhitespace + replacement + trailingWhitespace;
        }
    }

    private String extractLeadingWhitespace(String text) {
        StringBuilder ws = new StringBuilder();
        for (int i = 0; i < text.length() && Character.isWhitespace(text.charAt(i)); i++) {
            ws.append(text.charAt(i));
        }
        return ws.toString();
    }

    private String extractTrailingWhitespace(String text) {
        StringBuilder ws = new StringBuilder();
        for (int i = text.length() - 1; i >= 0 && Character.isWhitespace(text.charAt(i)); i--) {
            ws.insert(0, text.charAt(i));
        }
        return ws.toString();
    }

    private String extractIndentation(String text) {
        int lastNewline = text.lastIndexOf('\n');
        if (lastNewline == -1) return "";

        int nonWhitespace = lastNewline + 1;
        while (nonWhitespace < text.length() && Character.isWhitespace(text.charAt(nonWhitespace))
                && text.charAt(nonWhitespace) != '\n' && text.charAt(nonWhitespace) != '\r') {
            nonWhitespace++;
        }

        return text.substring(lastNewline + 1, nonWhitespace);
    }

    private int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private int countLiteralMatches(String text, String str) {
        if (text.isEmpty() || str.isEmpty()) return 0;
        int count = 0, index = 0;
        while ((index = text.indexOf(str, index)) != -1) {
            count++;
            index += str.length();
        }
        return count;
    }
    private String showDiffAndApplyChanges(Path filePath, String origContent, String modifiedContent,
                                           int replacementCount, boolean ignoreWhitespace) {
        VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.toString());
        if (vFile == null) return "Error: Could not find file in virtual file system: " + filePath;

        // Create a CompletableFuture to properly handle thread synchronization
        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
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

                boolean userConfirmed = (option == Messages.YES);

                if (!userConfirmed) {
                    resultFuture.complete("Changes discarded by user.");
                    return;
                }

                // Execute write action on UI thread since we're already there
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        // Update file content
                        vFile.refresh(false, false);
                        vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        resultFuture.complete("Successfully applied " + replacementCount + " replacements to " + filePath +
                                " with normalized LF line endings" +
                                (ignoreWhitespace ? ". Whitespace variations were ignored." : "."));
                    } catch (Exception e) {
                        LOG.error("Error updating file", e);
                        resultFuture.complete("Error updating file: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                LOG.error("Error showing diff dialog", e);
                resultFuture.complete("Error showing diff dialog: " + e.getMessage());
            }
        });

        return  "OK: Changes are being processed. Please check the diff dialog.";
    }
}