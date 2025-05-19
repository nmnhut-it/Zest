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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        // Ignore regex parameter - we're using simple string operations
        return replaceInFileLineByLine(filePath, searchText, replaceText, caseSensitive, ignoreWhitespace);
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
     * Ultra-simplified search and replace using direct line-by-line string operations.
     * Trims lines for comparison but maintains original indentation for replacements.
     */
    private String replaceInFileLineByLine(String filePath, String searchText, String replaceText,
                                         boolean caseSensitive, boolean ignoreWhitespace) {
        try {
            // Resolve file path and check if file exists
            Path fullPath = resolvePath(filePath);
            if (fullPath == null) return "No base directory found for the project.";

            File targetFile = fullPath.toFile();
            if (!targetFile.exists() || !targetFile.isFile()) return "Error: File not found: " + filePath;

            // Read file line by line
            List<String> originalLines = new ArrayList<>();
            List<String> modifiedLines = new ArrayList<>();
            int replacementCount = 0;

            // This is for single-line search: Handle simple case first
            if (!searchText.contains("\n")) {
                String searchForComparison = prepareStringForComparison(searchText, ignoreWhitespace, caseSensitive);
                
                try (BufferedReader reader = Files.newBufferedReader(fullPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        originalLines.add(line);
                        
                        // Compare trimmed versions for potential match
                        String lineForComparison = prepareStringForComparison(line, ignoreWhitespace, caseSensitive);
                        
                        if (lineForComparison.contains(searchForComparison)) {
                            // Found a match - do exact replacement while preserving indentation
                            String newLine = doReplacementPreservingIndentation(line, searchText, replaceText, 
                                                                           ignoreWhitespace, caseSensitive);
                            if (!newLine.equals(line)) {
                                replacementCount++;
                                modifiedLines.add(newLine);
                            } else {
                                modifiedLines.add(line);
                            }
                        } else {
                            // No match, keep the line as is
                            modifiedLines.add(line);
                        }
                    }
                }
            } else {
                // Handle multi-line search and replace
                String[] searchLines = searchText.split("\n");
                String firstSearchLine = searchLines[0];
                String firstSearchLineForComparison = prepareStringForComparison(firstSearchLine, 
                                                                              ignoreWhitespace, caseSensitive);
                
                try (BufferedReader reader = Files.newBufferedReader(fullPath, StandardCharsets.UTF_8)) {
                    List<String> lineBuffer = new ArrayList<>();
                    String line;
                    boolean inPotentialMatch = false;
                    
                    while ((line = reader.readLine()) != null) {
                        originalLines.add(line);
                        
                        if (!inPotentialMatch) {
                            // Look for the first line of the pattern
                            String lineForComparison = prepareStringForComparison(line, ignoreWhitespace, caseSensitive);
                            
                            if (lineForComparison.contains(firstSearchLineForComparison)) {
                                // Potential start of a match
                                inPotentialMatch = true;
                                lineBuffer.clear();
                                lineBuffer.add(line);
                            } else {
                                // Not a potential match
                                modifiedLines.add(line);
                            }
                        } else {
                            // We're collecting lines for a potential match
                            lineBuffer.add(line);
                            
                            // Check if we have enough lines
                            if (lineBuffer.size() >= searchLines.length) {
                                // Try to match the complete pattern
                                boolean isMatch = true;
                                
                                for (int i = 1; i < searchLines.length; i++) {
                                    String bufferLine = lineBuffer.get(i);
                                    String searchLine = searchLines[i];
                                    
                                    String bufferLineForComparison = prepareStringForComparison(bufferLine, 
                                                                                           ignoreWhitespace, caseSensitive);
                                    String searchLineForComparison = prepareStringForComparison(searchLine, 
                                                                                          ignoreWhitespace, caseSensitive);
                                    
                                    if (!bufferLineForComparison.contains(searchLineForComparison)) {
                                        isMatch = false;
                                        break;
                                    }
                                }
                                
                                if (isMatch) {
                                    // We have a match - replace the whole block
                                    replacementCount++;
                                    
                                    // For multi-line replacement, preserve indentation of first line
                                    String firstLine = lineBuffer.get(0);
                                    String indentation = extractIndentation(firstLine);
                                    
                                    // Apply the replacement with proper indentation
                                    String[] replaceLines = replaceText.split("\n");
                                    
                                    // Add the first line with its original indentation
                                    if (replaceLines.length > 0) {
                                        modifiedLines.add(indentation + replaceLines[0]);
                                        
                                        // Add remaining lines with same indentation
                                        for (int i = 1; i < replaceLines.length; i++) {
                                            modifiedLines.add(indentation + replaceLines[i]);
                                        }
                                    }
                                    
                                    // Reset
                                    inPotentialMatch = false;
                                    lineBuffer.clear();
                                } else {
                                    // Not a complete match, add the first line and keep checking
                                    modifiedLines.add(lineBuffer.get(0));
                                    lineBuffer.remove(0);
                                }
                            }
                        }
                    }
                    
                    // Add any remaining buffered lines
                    modifiedLines.addAll(lineBuffer);
                }
            }

            // Return if no matches found
            if (replacementCount == 0) {
                return "No matches found for '" + searchText + "' in file: " + filePath +
                     (ignoreWhitespace ? " (ignoring whitespace variations)" : "");
            }
            
            // Join lines with LF line endings for normalized content
            String originalContent = String.join("\n", originalLines);
            String modifiedContent = String.join("\n", modifiedLines);
            
            // If no actual changes
            if (originalContent.equals(modifiedContent)) {
                return "Search text found but replacement resulted in no changes to content.";
            }
            
            // Show diff and apply changes
            return showDiffAndApplyChanges(fullPath, originalContent, modifiedContent, replacementCount, ignoreWhitespace);
            
        } catch (Exception e) {
            LOG.error("Error processing replace in file request", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Do replacement while preserving the original indentation.
     */
    private String doReplacementPreservingIndentation(String line, String searchText, String replaceText,
                                                   boolean ignoreWhitespace, boolean caseSensitive) {
        // Extract indentation
        String indentation = extractIndentation(line);
        String contentAfterIndent = line.substring(indentation.length());
        
        // If the search text has no whitespace variation concerns, use direct replacement
        if (!ignoreWhitespace) {
            if (caseSensitive) {
                // Simple case - direct replacement
                return indentation + contentAfterIndent.replace(searchText, replaceText);
            } else {
                // Case insensitive, but exact replacement
                return indentation + replaceCaseInsensitive(contentAfterIndent, searchText, replaceText);
            }
        } else {
            // Handle whitespace variations
            return indentation + replaceIgnoringWhitespace(contentAfterIndent, searchText, replaceText, caseSensitive);
        }
    }
    
    /**
     * Replace a string, ignoring case.
     */
    private String replaceCaseInsensitive(String text, String search, String replace) {
        if (text == null || search == null || search.isEmpty()) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        String lowerText = text.toLowerCase();
        String lowerSearch = search.toLowerCase();
        
        int searchLen = search.length();
        int lastIndex = 0;
        int index;
        
        while ((index = lowerText.indexOf(lowerSearch, lastIndex)) != -1) {
            // Add text up to the match
            result.append(text, lastIndex, index);
            // Add the replacement
            result.append(replace);
            // Move past this match
            lastIndex = index + searchLen;
        }
        
        // Add the remainder of the string
        result.append(text.substring(lastIndex));
        
        return result.toString();
    }
    
    /**
     * Replace a string, ignoring whitespace variations.
     */
    private String replaceIgnoringWhitespace(String text, String search, String replace, boolean caseSensitive) {
        if (text == null || search == null || search.isEmpty()) {
            return text;
        }
        
        // Normalize for comparison
        String normalizedText = normalizeWhitespace(text);
        String normalizedSearch = normalizeWhitespace(search);
        
        // For case insensitivity
        if (!caseSensitive) {
            normalizedText = normalizedText.toLowerCase();
            normalizedSearch = normalizedSearch.toLowerCase();
        }
        
        // Find match positions in normalized text
        List<int[]> matches = new ArrayList<>();
        int searchIndex = 0;
        
        while ((searchIndex = normalizedText.indexOf(normalizedSearch, searchIndex)) != -1) {
            int startPos = findOriginalPosition(text, normalizedText, searchIndex);
            int endPos = findMatchEnd(text, startPos, search, caseSensitive, true);
            
            matches.add(new int[] { startPos, endPos });
            searchIndex += normalizedSearch.length();
        }
        
        // No matches found
        if (matches.isEmpty()) {
            return text;
        }
        
        // Replace in reverse order to keep positions valid
        StringBuilder result = new StringBuilder(text);
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] match = matches.get(i);
            result.replace(match[0], match[1], replace);
        }
        
        return result.toString();
    }
    
    /**
     * Find the position in the original string corresponding to a position in the normalized string.
     */
    private int findOriginalPosition(String original, String normalized, int normalizedPos) {
        // Count non-whitespace characters until we reach normalizedPos
        int originalPos = 0;
        int normalizedIndex = 0;
        int nonWhitespaceCount = 0;
        
        // First, count non-whitespace characters up to normalizedPos
        for (int i = 0; i < normalizedPos; i++) {
            if (!Character.isWhitespace(normalized.charAt(i))) {
                nonWhitespaceCount++;
            }
        }
        
        // Now find the corresponding position in the original text
        int countSoFar = 0;
        for (originalPos = 0; originalPos < original.length(); originalPos++) {
            if (!Character.isWhitespace(original.charAt(originalPos))) {
                countSoFar++;
                if (countSoFar > nonWhitespaceCount) {
                    break;
                }
            }
        }
        
        return originalPos;
    }
    
    /**
     * Find where a match ends in the original text, accounting for whitespace variations.
     */
    private int findMatchEnd(String text, int startPos, String search, boolean caseSensitive, boolean ignoreWhitespace) {
        if (!ignoreWhitespace) {
            // Simple case - fixed length
            return startPos + search.length();
        }
        
        // Skip whitespace in both strings
        int textPos = startPos;
        int searchPos = 0;
        
        while (searchPos < search.length() && textPos < text.length()) {
            // Skip whitespace in search string
            while (searchPos < search.length() && Character.isWhitespace(search.charAt(searchPos))) {
                searchPos++;
            }
            
            // Skip whitespace in text
            while (textPos < text.length() && Character.isWhitespace(text.charAt(textPos))) {
                textPos++;
            }
            
            // End of either string
            if (searchPos >= search.length() || textPos >= text.length()) {
                break;
            }
            
            // Compare non-whitespace characters
            char s = search.charAt(searchPos);
            char t = text.charAt(textPos);
            
            if (!caseSensitive) {
                s = Character.toLowerCase(s);
                t = Character.toLowerCase(t);
            }
            
            if (s != t) {
                break;
            }
            
            // Move to next character
            searchPos++;
            textPos++;
        }
        
        // Skip any trailing whitespace in text
        while (textPos < text.length() && Character.isWhitespace(text.charAt(textPos))) {
            textPos++;
        }
        
        return textPos;
    }
    
    /**
     * Prepare a string for comparison by handling whitespace and case.
     */
    private String prepareStringForComparison(String text, boolean ignoreWhitespace, boolean caseSensitive) {
        if (text == null) return "";
        
        String result = text;
        
        if (ignoreWhitespace) {
            result = normalizeWhitespace(result);
        }
        
        if (!caseSensitive) {
            result = result.toLowerCase();
        }
        
        return result;
    }

    /**
     * Normalize whitespace in a string.
     */
    private String normalizeWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
    
    /**
     * Extract leading whitespace from a string.
     */
    private String extractIndentation(String text) {
        if (text == null) return "";
        
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        
        return text.substring(0, i);
    }

    private Path resolvePath(String filePath) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;
        return new File(filePath).isAbsolute() ? Paths.get(filePath) : Paths.get(basePath, filePath);
    }

    /**
     * Shows diff dialog and applies changes if confirmed by user.
     */
    private String showDiffAndApplyChanges(Path filePath, String origContent, String modifiedContent,
                                        int replacementCount, boolean ignoreWhitespace) {
        VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(filePath.toString());
        if (vFile == null) return "Error: Could not find file in virtual file system: " + filePath;

        // Simple thread synchronization
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>();
        
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

                if (option != Messages.YES) {
                    resultRef.set("Changes discarded by user.");
                    latch.countDown();
                    return;
                }
                
                // Execute write action on UI thread since we're already there
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        // Update file content
                        vFile.refresh(false, false);
                        vFile.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));
                        FileEditorManager.getInstance(project).openFile(vFile, true);

                        resultRef.set("Successfully applied " + replacementCount + " replacements to " + filePath +
                                " with normalized LF line endings" +
                                (ignoreWhitespace ? ". Whitespace variations were ignored." : "."));
                    } catch (Exception e) {
                        LOG.error("Error updating file", e);
                        resultRef.set("Error updating file: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                LOG.error("Error showing diff dialog", e);
                resultRef.set("Error showing diff dialog: " + e.getMessage());
                latch.countDown();
            }
        });

        try {
            // Wait with timeout for UI operations to complete
            if (!latch.await(30, TimeUnit.SECONDS)) {
                return "Error: Operation timed out waiting for user response.";
            }
            return resultRef.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Operation was interrupted.";
        }
    }
}