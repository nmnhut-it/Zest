package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
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
        super("replace_in_file", "Searches for text in a file and replaces it. Shows diff before applying changes.");
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

            // Create diff contents
            DiffContentFactory diffFactory = DiffContentFactory.getInstance();
            DocumentContent leftContent = diffFactory.create(originalContent);
            DocumentContent rightContent = diffFactory.create(modifiedContent);

            // Create diff request
            SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                    "Replace in File: " + filePath,
                    leftContent,
                    rightContent,
                    "Original Content",
                    "Modified Content (" + replacementCount + " replacements)");

            // Show diff and get user confirmation
            EnhancedTodoDiffComponent diffComponent = new EnhancedTodoDiffComponent(
                    project,
                    null, // No editor needed for this use case
                    originalContent,
                    modifiedContent,
                    0, // No selection needed
                    0);

            // Show diff and wait for user decision
            diffComponent.showDiff();
            
            // Ask user for confirmation
            int option = Messages.showYesNoDialog(
                    project,
                    "Apply " + replacementCount + " replacements to " + filePath + "?",
                    "Confirm Replacements",
                    "Apply Changes",
                    "Cancel",
                    null);
                    
            boolean applyChanges = (option == Messages.YES);

            if (!applyChanges) {
                return "Operation cancelled by user.";
            }

            // Apply changes
            int finalReplacementCount = replacementCount;
            return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                try {
                    VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fullPath.toString());
                    if (file == null) {
                        return "Error: Could not find file in virtual file system: " + filePath;
                    }
                    
                    // Update file content
                    file.setBinaryContent(modifiedContent.getBytes(StandardCharsets.UTF_8));
                    
                    // Open the file in editor
                    ApplicationManager.getApplication().invokeLater(() -> {
                        FileEditorManager.getInstance(project).openFile(file, true);
                    });
                    
                    return "Successfully replaced " + finalReplacementCount + " occurrences in file: " + filePath;
                } catch (Exception e) {
                    LOG.error("Error updating file: " + filePath, e);
                    return "Error updating file: " + e.getMessage();
                }
            });
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