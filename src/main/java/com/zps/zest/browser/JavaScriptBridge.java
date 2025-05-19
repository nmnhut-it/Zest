package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.Consumer;
import com.zps.zest.tools.ReplaceInFileTool;
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge for communication between JavaScript in the browser and Java code in IntelliJ.
 */
public class JavaScriptBridge {
    private static final Logger LOG = Logger.getInstance(JavaScriptBridge.class);
    private final Project project;
    private final Gson gson = new Gson();

    /**
     * Creates a new JavaScript bridge.
     */
    public JavaScriptBridge(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Handles a JavaScript query from the browser.
     */
    public String handleJavaScriptQuery(String query) {
        LOG.info("Received query from JavaScript: " + query);
        
        try {
            JsonObject request = JsonParser.parseString(query).getAsJsonObject();
            String action = request.get("action").getAsString();
            JsonObject data = request.has("data") ? request.get("data").getAsJsonObject() : new JsonObject();
            
            JsonObject response = new JsonObject();
            
            switch (action) {
                case "getSelectedText":
                    String selectedText = getSelectedTextFromEditor();
                    response.addProperty("success", true);
                    response.addProperty("result", selectedText);
                    break;
                    
                case "insertText":
                    String text = data.get("text").getAsString();
                    boolean success = insertTextToEditor(text);
                    response.addProperty("success", success);
                    break;
                    
                case "getCurrentFileName":
                    String fileName = getCurrentFileName();
                    response.addProperty("success", true);
                    response.addProperty("result", fileName);
                    break;
                case "codeCompleted":
                    String textToReplace = data.get("textToReplace").getAsString();
                    String resultText = data.get("text").getAsString();
                    boolean replaceResult = handleCodeComplete(textToReplace, resultText);
                    response.addProperty("success", replaceResult);
                    break;
                case "showDialog":
                    String title = data.has("title") ? data.get("title").getAsString() : "Information";
                    String message = data.has("message") ? data.get("message").getAsString() : "";
                    String dialogType = data.has("type") ? data.get("type").getAsString() : "info";

                    boolean dialogResult = showDialog(title, message, dialogType);
                    response.addProperty("success", true);
                    response.addProperty("result", dialogResult);
                    break;
                    
                case "contentUpdated":
                    String pageUrl = data.has("url") ? data.get("url").getAsString() : "";
                    handleContentUpdated(pageUrl);
                    response.addProperty("success", true);
                    break;
                case "getProjectInfo":
                    JsonObject projectInfo = getProjectInfo();
                    response.addProperty("success", true);
                    response.add("result", projectInfo);
                    break;
                    
                case "extractCodeFromResponse":
                    String codeText = data.get("code").getAsString();
                    String language = data.has("language") ? data.get("language").getAsString() : "";
                    String extractTextToReplace = data.get("textToReplace").getAsString();
                    boolean extractResult = handleExtractedCode(extractTextToReplace, codeText, language);
                    response.addProperty("success", extractResult);
                    break;
                case "replaceInFile":
                    String filePath = data.get("filePath").getAsString();
                    String searchText = data.get("search").getAsString();
                    String replaceText = data.get("replace").getAsString();
                    boolean useRegex = data.has("regex") && data.get("regex").getAsBoolean();
                    boolean caseSensitive = !data.has("caseSensitive") || data.get("caseSensitive").getAsBoolean();

                    boolean diffReplaceResult = handleReplaceInFile(filePath, searchText, replaceText, useRegex, caseSensitive);
                    response.addProperty("success", diffReplaceResult);
                    break;
                    
                case "batchReplaceInFile":
                    ApplicationManager.getApplication().executeOnPooledThread(()->{
                        String batchFilePath = data.get("filePath").getAsString();
                        com.google.gson.JsonArray replacements = data.getAsJsonArray("replacements");

                        boolean batchReplaceResult = handleBatchReplaceInFile(batchFilePath, replacements);
//                        response.addProperty("success", batchReplaceResult);
                    });

                    break;
                default:
                    LOG.warn("Unknown action: " + action);
                    response.addProperty("success", false);
                    response.addProperty("error", "Unknown action: " + action);
            }
            
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling JavaScript query", e);
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", e.getMessage());
            return gson.toJson(errorResponse);
        }
    }
    
    /**
     * Handles code extracted from API responses
     * 
     * @param textToReplace The text to find and replace (can be special value __##use_selected_text##__)
     * @param codeText The extracted code
     * @param language The language of the code
     * @return True if the operation was successful
     */
    private boolean handleExtractedCode(String textToReplace, String codeText, String language) {
        LOG.info("Handling extracted code from API response, language: " + language);
        
        try {
            // If special value is used, get selected text from editor
            if ("__##use_selected_text##__".equals(textToReplace)) {
                String selectedText = getSelectedTextFromEditor();
                if (selectedText != null && !selectedText.isEmpty()) {
                    textToReplace = selectedText;
                } else {
                    // No text selected, just insert the code
//                    return insertTextToEditor(codeText);
                    return false;
                }
            }
            
            // If we have valid text to replace, handle as code completion
            if (textToReplace != null && !textToReplace.isEmpty()) {
                return handleCodeComplete(textToReplace, codeText);
            } else {
                // No text to replace, just insert the code
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error handling extracted code", e);
            return false;
        }
    }
    
    /**
     * Shows a dialog with the specified title and message.
     */
    private boolean showDialog(String title, String message, String dialogType) {
        final boolean[] result = new boolean[1];

        ApplicationManager.getApplication().invokeAndWait(() -> {
            switch (dialogType.toLowerCase()) {
                case "info":
                    Messages.showInfoMessage(project, message, title);
                    result[0] = true;
                    break;

                case "warning":
                    Messages.showWarningDialog(project, message, title);
                    result[0] = true;
                    break;

                case "error":
                    Messages.showErrorDialog(project, message, title);
                    result[0] = true;
                    break;

                case "yesno":
                    int yesNoResult = Messages.showYesNoDialog(project, message, title,
                            Messages.getQuestionIcon());
                    result[0] = (yesNoResult == Messages.YES);
                    break;

                case "okcancel":
                    int okCancelResult = Messages.showOkCancelDialog(project, message, title,
                            "OK", "Cancel",
                            Messages.getQuestionIcon());
                    result[0] = (okCancelResult == Messages.OK);
                    break;

                default:
                    Messages.showInfoMessage(project, message, title);
                    result[0] = true;
                    break;
            }
        });

        return result[0];
    }
    /**
     * Gets the selected text from the current editor.
     */
    private String getSelectedTextFromEditor() {
        return ReadAction.compute(()->{
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "";
            }

            String selectedText = editor.getSelectionModel().getSelectedText();
            return selectedText != null ? selectedText : "";
        });
    }

    /**
     * Inserts text at the current caret position in the editor.
     */
    private boolean insertTextToEditor(String text) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return false;
        }
        
        Document document = editor.getDocument();
        CaretModel caretModel = editor.getCaretModel();
        
        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (Caret caret : caretModel.getAllCarets()) {
                int offset = caret.getOffset();
                document.insertString(offset, text);
            }
        });
        
        return true;
    }
    
    /**
     * Gets the name of the current file.
     */
    private String getCurrentFileName() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return "";
        }
        
        return FileEditorManager.getInstance(project).getSelectedFiles()[0].getName();
    }
    
    /**
     * Handles code completion by finding text to replace and showing a diff.
     * 
     * @param textToReplace The text to find and replace
     * @param resultText The new code to replace it with
     * @return True if the operation was successful
     */
    private boolean handleCodeComplete(String textToReplace, String resultText) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            LOG.warn("No editor is currently open");
            return false;
        }
        
        Document document = editor.getDocument();
        String text = document.getText();
        
        // Find the text to replace
        int startOffset = text.indexOf(textToReplace);
        if (startOffset == -1) {
            showDialog("Code Complete Error", "Could not find the text to replace in the current editor", "error");
            return false;
        }
        
        int endOffset = startOffset + textToReplace.length();
        
        // Show diff in editor and handle user decision
        final boolean[] result = new boolean[1];
        final WindowWrapper[] windowWrapper = new WindowWrapper[1];
        
        ApplicationManager.getApplication().invokeAndWait(() -> {
            // Create a temporary document with the replaced content for diff
            String currentContent = document.getText();
            String newContent = currentContent.substring(0, startOffset) + resultText + currentContent.substring(endOffset);
            
            // Use IntelliJ's diff tool
            DiffManager diffManager = DiffManager.getInstance();
            SimpleDiffRequest diffRequest = new SimpleDiffRequest("Code Completion", 
                    DiffContentFactory.getInstance().create(currentContent),
                    DiffContentFactory.getInstance().create(newContent),
                    "Current Code", "Code With Replacement");
            
            // Setup hints for modal dialog with custom callback
            DiffDialogHints hints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), new Consumer<WindowWrapper>() {
                @Override
                public void consume(WindowWrapper wrapper) {
                    windowWrapper[0] = wrapper;

                }
            }  );
            // Show diff dialog
            diffManager.showDiff(project, diffRequest, hints);
            
            // Create and show the apply changes dialog after the diff is shown
            if (windowWrapper[0] != null && windowWrapper[0].getWindow() != null) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        // Add a listener to detect when the diff window is closed
                        windowWrapper[0].getWindow().addWindowListener(new java.awt.event.WindowAdapter() {
                            @Override
                            public void windowClosed(java.awt.event.WindowEvent e) {
                                // Ask user if they want to apply the changes
                                int dialogResult = Messages.showYesNoDialog(
                                        project,
                                        "Do you want to apply these changes?",
                                        "Apply Code Completion",
                                        "Apply",
                                        "Cancel",
                                        Messages.getQuestionIcon()
                                );
                                
                                if (dialogResult == Messages.YES) {
                                    // Apply the changes if accepted
                                    WriteCommandAction.runWriteCommandAction(project, () -> {
                                        document.replaceString(startOffset, endOffset, resultText);
                                    });
                                    result[0] = true;
                                } else {
                                    result[0] = false;
                                }
                            }
                        });
                    } catch (Exception ex) {
                        LOG.error("Error adding window listener", ex);
                        // Fallback to standard dialog
                        int dialogResult = Messages.showYesNoDialog(
                                project,
                                "Do you want to apply these changes?",
                                "Apply Code Completion",
                                "Apply",
                                "Cancel",
                                Messages.getQuestionIcon()
                        );
                        
                        if (dialogResult == Messages.YES) {
                            // Apply the changes if accepted
                            WriteCommandAction.runWriteCommandAction(project, () -> {
                                document.replaceString(startOffset, endOffset, resultText);
                            });
                            result[0] = true;
                        } else {
                            result[0] = false;
                        }
                    }
                });
            }
        });
        
        return result[0];
    }
    
    /**
     * Handles content updated events from JavaScript.
     * Used to mark pages as loaded after dynamic content updates.
     *
     * @param url The URL of the page that was updated
     */
    private void handleContentUpdated(String url) {
        LOG.info("Content updated notification received for: " + url);
        
        // Mark the page as loaded in WebBrowserToolWindow
        ApplicationManager.getApplication().invokeLater(() -> {
            // Get the key using the same format as in WebBrowserToolWindow
            String key = project.getName() + ":" + url;
            
            // Update the page loaded state
            WebBrowserToolWindow.pageLoadedState.put(key, true);
            
            // Complete any pending futures for this URL
            CompletableFuture<Boolean> future = WebBrowserToolWindow.pageLoadedFutures.remove(key);
            if (future != null && !future.isDone()) {
                future.complete(true);
            }
        });
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
    /**
     * Gets real-time information about the current project and editor state.
     */
    private JsonObject getProjectInfo() {
        JsonObject info = new JsonObject();

        try {
            // Project info
            info.addProperty("projectName", project.getName());
            info.addProperty("projectFilePath", project.getProjectFilePath());

            // Editor info
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                if (editor.getVirtualFile() != null) {
                    info.addProperty("currentOpenFile", editor.getVirtualFile().getPath());
                } else {
                    info.addProperty("currentOpenFile", "");
                }

                // Get code context
                String codeContext = getCodeAroundCaret(editor, 25);
                info.addProperty("codeContext", codeContext);
            } else {
                info.addProperty("currentOpenFile", "");
                info.addProperty("codeContext", "");
            }
        } catch (Exception e) {
            LOG.error("Error getting project info", e);
        }

        return info;
    }

    /**
     * Gets code surrounding the caret position
     * @param editor Current editor
     * @param lineCount Number of lines before and after caret to include
     * @return String containing code context
     */
    private String getCodeAroundCaret(Editor editor, int lineCount) {
        if (editor == null) {
            return "";
        }
        return ReadAction.compute(()->{

            Document document = editor.getDocument();

            int caretOffset = editor.getCaretModel().getOffset();
            int caretLine = document.getLineNumber(caretOffset);

            int startLine = Math.max(0, caretLine - lineCount);
            int endLine = Math.min(document.getLineCount() - 1, caretLine + lineCount);

            int startOffset = document.getLineStartOffset(startLine);
            int endOffset = document.getLineEndOffset(endLine);

            String codeContext = document.getText(new TextRange(startOffset, endOffset));
            return codeContext;
        });
    }

    /**
     * Handles replace in file requests from JavaScript by delegating to the ReplaceInFileTool
     *
     * @param filePath The path to the file to replace in
     * @param searchText The text to search for
     * @param replaceText The text to replace with
     * @param useRegex Whether to use regex for search
     * @param caseSensitive Whether the search is case sensitive
     * @return True if the operation was successful
     */
    private boolean handleReplaceInFile(String filePath, String searchText, String replaceText, boolean useRegex, boolean caseSensitive) {
        LOG.info("Handling replace in file request for: " + filePath);

        try {
            // Create a JSON object with the parameters for the ReplaceInFileTool
            JsonObject params = new JsonObject();
            params.addProperty("filePath", filePath);
            params.addProperty("search", searchText);
            params.addProperty("replace", replaceText);
            params.addProperty("regex", useRegex);
            params.addProperty("caseSensitive", caseSensitive);

            com.zps.zest.tools.AgentTool tool = new ReplaceInFileTool(project);
            if (tool == null) {
                LOG.error("Could not find replace_in_file tool");
                return false;
            }

            // Execute the tool
            String result = tool.execute(params);
            ApplicationManager.getApplication().invokeLater(()->{
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
     *
     * @param filePath The path to the file to replace in
     * @param replacements An array of replacement objects, each containing search and replace text
     * @return True if all operations were successful
     */
    private boolean handleBatchReplaceInFile(String filePath, com.google.gson.JsonArray replacements) {
        LOG.info("Handling batch replace in file request for: " + filePath + " with " + replacements.size() + " replacements");

        // Ensure we're not blocking the EDT for file operations
        if (ApplicationManager.getApplication().isDispatchThread()) {
            final java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                result.set(doHandleBatchReplaceInFile(filePath, replacements));
            });
            // Wait for completion - you may want to make this asynchronous if appropriate
            try {
                // Wait with a timeout to prevent indefinite blocking
                for (int i = 0; i < 100 && !result.get(); i++) {
                    Thread.sleep(100);
                }
                return result.get();
            } catch (InterruptedException e) {
                LOG.error("Interrupted while waiting for batch replace operation", e);
                return false;
            }
        } else {
            return doHandleBatchReplaceInFile(filePath, replacements);
        }
    }

    /**
     * Actual implementation of batch replace functionality, meant to be run off the EDT
     */
    private boolean doHandleBatchReplaceInFile(String filePath, com.google.gson.JsonArray replacements) {
        try {
            // 1. Resolve file
            java.io.File targetFile = new java.io.File(filePath);
            if (!targetFile.exists()) {
                String basePath = project.getBasePath();
                if (basePath != null)
                    targetFile = new java.io.File(basePath, filePath);
            }
            if (!targetFile.exists() || !targetFile.isFile()) {
                LOG.error("File not found: " + filePath);
                return false;
            }
            java.nio.file.Path inputPath = targetFile.toPath();

            // 2. Start with original lines/content
            java.util.List<String> currentLines = java.nio.file.Files.readAllLines(inputPath, java.nio.charset.StandardCharsets.UTF_8);
            String originalContent = String.join("\n", currentLines);

            int totalReplacementCount = 0;

            for (int i = 0; i < replacements.size(); i++) {
                com.google.gson.JsonObject replacement = replacements.get(i).getAsJsonObject();
                String searchText = replacement.get("search").getAsString();
                String replaceText = replacement.get("replace").getAsString();
                boolean caseSensitive = !replacement.has("caseSensitive") || replacement.get("caseSensitive").getAsBoolean();
                boolean ignoreWhitespace = replacement.has("ignoreWhitespace") && replacement.get("ignoreWhitespace").getAsBoolean();
                // performSearchAndReplace doesn't handle regex -- we skip it if true
                boolean useRegex = replacement.has("regex") && replacement.get("regex").getAsBoolean();
                if (useRegex) {
                    LOG.warn("Regex mode is not supported in batchReplaceInFile when using performSearchAndReplace. Skipping this replacement.");
                    continue;
                }

                // Write currentLines to a temp file for replace
                java.nio.file.Path tempInput = java.nio.file.Files.createTempFile("batch_replace_", ".tmp");
                java.nio.file.Files.write(tempInput, currentLines, java.nio.charset.StandardCharsets.UTF_8);

                // Call performSearchAndReplace (returns ReplaceInFileTool.ReplaceResult)
                ReplaceInFileTool.ReplaceResult result =
                        ReplaceInFileTool.performSearchAndReplace(
                                tempInput,
                                searchText,
                                replaceText,
                                caseSensitive,
                                ignoreWhitespace,
                                new com.intellij.openapi.progress.EmptyProgressIndicator()
                        );
                totalReplacementCount += result.replacementCount;
                currentLines = java.util.Arrays.asList(result.modifiedContent.split("\n", -1)); // preserve trailing empty line if present

                // Clean up temp file
                java.nio.file.Files.deleteIfExists(tempInput);
            }

            String modifiedContent = String.join("\n", currentLines);

            if (originalContent.equals(modifiedContent)) {
                LOG.info("No changes made to file content after applying all replacements");
                return true;
            }

            // Show diff with all replacements - must be done on EDT
            final java.util.concurrent.atomic.AtomicBoolean userConfirmed = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.io.File finalTargetFile = targetFile;
            final String finalModifiedContent = modifiedContent;
            final int finalTotalReplacementCount = totalReplacementCount;

            // Use invokeAndWait from a background thread, not from EDT
            try {
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    try {
                        com.intellij.diff.DiffContentFactory diffFactory = com.intellij.diff.DiffContentFactory.getInstance();
                        com.intellij.diff.contents.DocumentContent leftContent = diffFactory.create(originalContent);
                        com.intellij.diff.contents.DocumentContent rightContent = diffFactory.create(finalModifiedContent);

                        com.intellij.diff.requests.SimpleDiffRequest diffRequest = new com.intellij.diff.requests.SimpleDiffRequest(
                                "Batch Changes to " + finalTargetFile.getName() + " (" + finalTotalReplacementCount + " replacements)",
                                leftContent,
                                rightContent,
                                "Original",
                                "After Replacements"
                        );

                        com.intellij.diff.DiffManager.getInstance().showDiff(project, diffRequest, com.intellij.diff.DiffDialogHints.MODAL);

                        int option = Messages.showYesNoDialog(
                                project,
                                "Apply " + finalTotalReplacementCount + " replacements to " + filePath + "?",
                                "Confirm Batch Changes",
                                "Apply",
                                "Cancel",
                                Messages.getQuestionIcon()
                        );
                        userConfirmed.set(option == Messages.YES);
                    } catch (Exception e) {
                        LOG.error("Error showing diff dialog", e);
                    }
                });
            } catch (Exception e) {
                LOG.error("Error executing on EDT", e);
                return false;
            }

            if (userConfirmed.get()) {
                // Perform file update in write action - must be on EDT
                try {
                    com.intellij.openapi.vfs.VirtualFile vFile =
                            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(finalTargetFile.getPath());
                    if (vFile == null) {
                        LOG.error("Could not find file in virtual file system: " + filePath);
                        return false;
                    }

                    // Execute write command action safely
                    final java.util.concurrent.atomic.AtomicBoolean writeSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
                    ApplicationManager.getApplication().invokeAndWait(() -> {
                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            try {
                                vFile.refresh(false, false);
                                vFile.setBinaryContent(finalModifiedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                FileEditorManager.getInstance(project).openFile(vFile, true);
                                writeSuccess.set(true);
                            } catch (Exception e) {
                                LOG.error("Error updating file: " + filePath, e);
                            }
                        });
                    });
                    return writeSuccess.get();
                } catch (Exception e) {
                    LOG.error("Error during write command execution", e);
                    return false;
                }
            } else {
                LOG.info("Batch changes were not applied - discarded by user");
                return true; // Still "successful" from a function perspective - user just chose not to apply
            }
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file request", e);
            return false;
        }
    }
}


