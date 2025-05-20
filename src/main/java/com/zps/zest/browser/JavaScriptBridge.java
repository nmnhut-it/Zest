package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
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
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.ReplaceInFileTool;
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().invokeLater(() -> {
                        insertTextToEditor(text);
                    });
                    response.addProperty("success", true);
                    break;

                case "getCurrentFileName":
                    String fileName = getCurrentFileName();
                    response.addProperty("success", true);
                    response.addProperty("result", fileName);
                    break;

                case "codeCompleted":
                    String textToReplace = data.get("textToReplace").getAsString();
                    String resultText = data.get("text").getAsString();
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        handleCodeComplete(textToReplace, resultText);
                    });
                    response.addProperty("success", true);
                    break;

                case "showDialog":
                    String title = data.has("title") ? data.get("title").getAsString() : "Information";
                    String message = data.has("message") ? data.get("message").getAsString() : "";
                    String dialogType = data.has("type") ? data.get("type").getAsString() : "info";
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showDialog(title, message, dialogType);
                    });
                    response.addProperty("success", true);
                    response.addProperty("result", true);
                    break;

                case "contentUpdated":
                    String pageUrl = data.has("url") ? data.get("url").getAsString() : "";
                    handleContentUpdated(pageUrl);
                    response.addProperty("success", true);
                    break;

                case "getProjectInfo":
                    // This is the only one we keep synchronous as you mentioned it's important
                    JsonObject projectInfo = getProjectInfo();
                    response.addProperty("success", true);
                    response.add("result", projectInfo);
                    break;

                case "extractCodeFromResponse":
                    String codeText = data.get("code").getAsString();
                    String language = data.has("language") ? data.get("language").getAsString() : "";
                    String extractTextToReplace = data.get("textToReplace").getAsString();
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        handleExtractedCode(extractTextToReplace, codeText, language);
                    });
                    response.addProperty("success", true);
                    break;

                case "replaceInFile":
                    String filePath = data.get("filePath").getAsString();
                    String searchText = data.get("search").getAsString();
                    String replaceText = data.get("replace").getAsString();
                    boolean useRegex = data.has("regex") && data.get("regex").getAsBoolean();
                    boolean caseSensitive = !data.has("caseSensitive") || data.get("caseSensitive").getAsBoolean();
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        handleReplaceInFile(filePath, searchText, replaceText, useRegex, caseSensitive);
                    });
                    response.addProperty("success", true);
                    break;

                case "batchReplaceInFile":
                    String batchFilePath = data.get("filePath").getAsString();
                    JsonArray replacements = data.getAsJsonArray("replacements");
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        handleBatchReplaceInFile(batchFilePath, replacements);
                    });
                    response.addProperty("success", true);
                    break;

                case "notifyChatResponse":
                    String content = data.get("content").getAsString();
                    String messageId = data.has("id") ? data.get("id").getAsString() : "";
                    // Notify any registered listeners
                    notifyChatResponseReceived(content, messageId);
                    response.addProperty("success", true);
                    break;
                case "showCodeDiffAndReplace":
                    String codeContent = data.get("code").getAsString();
                    String codeLanguage = data.has("language") ? data.get("language").getAsString() : "";
                    String replaceTargetText = data.get("textToReplace").getAsString();
                    // Run async - don't wait for result
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        handleShowCodeDiffAndReplace(replaceTargetText, codeContent, codeLanguage);
                    });
                    response.addProperty("success", true);
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
     * This method is now non-blocking and doesn't return the user's response.
     */
    private void showDialog(String title, String message, String dialogType) {
        switch (dialogType.toLowerCase()) {
            case "info":
                Messages.showInfoMessage(project, message, title);
                break;

            case "warning":
                Messages.showWarningDialog(project, message, title);
                break;

            case "error":
                Messages.showErrorDialog(project, message, title);
                break;

            case "yesno":
                int yesNoResult = Messages.showYesNoDialog(project, message, title,
                        Messages.getQuestionIcon());
                LOG.info("YesNo dialog result: " + (yesNoResult == Messages.YES ? "YES" : "NO"));
                break;

            case "okcancel":
                int okCancelResult = Messages.showOkCancelDialog(project, message, title,
                        "OK", "Cancel",
                        Messages.getQuestionIcon());
                LOG.info("OkCancel dialog result: " + (okCancelResult == Messages.OK ? "OK" : "CANCEL"));
                break;

            default:
                Messages.showInfoMessage(project, message, title);
                break;
        }
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
     * This method is now asynchronous and doesn't block the UI.
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
            return false;
        }

        int endOffset = startOffset + textToReplace.length();

        // Show diff in editor asynchronously
        ApplicationManager.getApplication().invokeLater(() -> {
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
                    if (wrapper != null && wrapper.getWindow() != null) {
                        // Add a listener to detect when the diff window is closed
                        wrapper.getWindow().addWindowListener(new WindowAdapter() {
                            @Override
                            public void windowClosed(WindowEvent e) {
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
                                }
                            }
                        });
                    }
                }
            });

            // Show diff dialog
            diffManager.showDiff(project, diffRequest, hints);
        });

        return true; // Return immediately, don't wait for user interaction
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
            info.addProperty("projectFilePath (path/to/project/.idea/misc.xml )", project.getProjectFilePath());
            info.addProperty("sourceRoots", Arrays.toString(ProjectRootManager.getInstance(project).getContentSourceRoots()));

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
     * This method is now asynchronous and doesn't block the UI.
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

            AgentTool tool = new ReplaceInFileTool(project);
            if (tool == null) {
                LOG.error("Could not find replace_in_file tool");
                return false;
            }

            // Execute the tool
            String result = tool.execute(params);

            // Show result asynchronously
            ApplicationManager.getApplication().invokeLater(() -> {
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
     * This method is now fully asynchronous and doesn't block the UI.
     *
     * @param filePath The path to the file to replace in
     * @param replacements An array of replacement objects, each containing search and replace text
     * @return True if all operations were successful
     */
    private boolean handleBatchReplaceInFile(String filePath, JsonArray replacements) {
        LOG.info("Handling batch replace in file request for: " + filePath + " with " + replacements.size() + " replacements");

        // Always run on background thread
        try {
            // 1. Resolve file
            File targetFile = new File(filePath);
            if (!targetFile.exists()) {
                String basePath = project.getBasePath();
                if (basePath != null)
                    targetFile = new File(basePath, filePath);
            }
            if (!targetFile.exists() || !targetFile.isFile()) {
                LOG.error("File not found: " + filePath);
                return false;
            }
            Path inputPath = targetFile.toPath();

            // 2. Start with original lines/content
            List<String> currentLines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
            String originalContent = String.join("\n", currentLines);

            int totalReplacementCount = 0;

            for (int i = 0; i < replacements.size(); i++) {
                JsonObject replacement = replacements.get(i).getAsJsonObject();
                String searchText = replacement.get("search").getAsString();
                String replaceText = replacement.get("replace").getAsString();
                boolean caseSensitive = !replacement.has("caseSensitive") || replacement.get("caseSensitive").getAsBoolean();
                boolean ignoreWhitespace = replacement.has("ignoreWhitespace") && replacement.get("ignoreWhitespace").getAsBoolean();
                boolean useRegex = replacement.has("regex") && replacement.get("regex").getAsBoolean();

                if (useRegex) {
                    LOG.warn("Regex mode is not supported in batchReplaceInFile when using performSearchAndReplace. Skipping this replacement.");
                    continue;
                }

                // Write currentLines to a temp file for replace
                Path tempInput = Files.createTempFile("batch_replace_", ".tmp");
                Files.write(tempInput, currentLines, StandardCharsets.UTF_8);

                // Call performSearchAndReplace
                ReplaceInFileTool.ReplaceResult result =
                        ReplaceInFileTool.performSearchAndReplace(
                                tempInput,
                                searchText,
                                replaceText,
                                caseSensitive,
                                ignoreWhitespace,
                                new EmptyProgressIndicator()
                        );
                totalReplacementCount += result.replacementCount;
                currentLines = Arrays.asList(result.modifiedContent.split("\n", -1));

                // Clean up temp file
                Files.deleteIfExists(tempInput);
            }

            String modifiedContent = String.join("\n", currentLines);

            if (originalContent.equals(modifiedContent)) {
                LOG.info("No changes made to file content after applying all replacements");
                return true;
            }

            // Show diff and handle user interaction on EDT
            final File finalTargetFile = targetFile;
            final String finalModifiedContent = modifiedContent;
            final int finalTotalReplacementCount = totalReplacementCount;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                    DocumentContent leftContent = diffFactory.create(originalContent);
                    DocumentContent rightContent = diffFactory.create(finalModifiedContent);

                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            "Batch Changes to " + finalTargetFile.getName() + " (" + finalTotalReplacementCount + " replacements)",
                            leftContent,
                            rightContent,
                            "Original",
                            "After Replacements"
                    );

                    DiffManager.getInstance().showDiff(project, diffRequest, DiffDialogHints.MODAL);

                    int option = Messages.showYesNoDialog(
                            project,
                            "Apply " + finalTotalReplacementCount + " replacements to " + filePath + "?",
                            "Confirm Batch Changes",
                            "Apply",
                            "Cancel",
                            Messages.getQuestionIcon()
                    );

                    if (option == Messages.YES) {
                        // Perform file update in write action
                        VirtualFile vFile =
                                LocalFileSystem.getInstance().findFileByPath(finalTargetFile.getPath());
                        if (vFile == null) {
                            LOG.error("Could not find file in virtual file system: " + filePath);
                            return;
                        }

                        WriteCommandAction.runWriteCommandAction(project, () -> {
                            try {
                                vFile.refresh(false, false);
                                vFile.setBinaryContent(finalModifiedContent.getBytes(StandardCharsets.UTF_8));
                                FileEditorManager.getInstance(project).openFile(vFile, true);
                            } catch (Exception e) {
                                LOG.error("Error updating file: " + filePath, e);
                            }
                        });
                    } else {
                        LOG.info("Batch changes were not applied - discarded by user");
                    }
                } catch (Exception e) {
                    LOG.error("Error showing diff dialog", e);
                }
            });

            return true; // Return immediately, don't wait for user interaction
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file request", e);
            return false;
        }
    }

    /**
     * Add these fields to the JavaScriptBridge class
     */
    private CompletableFuture<String> pendingResponseFuture = null;
    private String lastProcessedMessageId = null;

    /**
     * Waits for the next chat response
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return CompletableFuture that will be completed when a response is received
     */
    public CompletableFuture<String> waitForChatResponse(int timeoutSeconds) {
        // Create a new future if there isn't one already or if the existing one is completed
        if (pendingResponseFuture == null || pendingResponseFuture.isDone()) {
            pendingResponseFuture = new CompletableFuture<>();
        }

        // Add timeout
        return pendingResponseFuture.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Notifies about a new chat response
     * @param content The response content
     * @param messageId The message ID
     */
    private void notifyChatResponseReceived(String content, String messageId) {
        // Avoid processing the same message multiple times
        if (messageId != null && messageId.equals(lastProcessedMessageId)) {
            LOG.info("Ignoring duplicate message ID: " + messageId);
            return;
        }

        LOG.info("Received chat response with ID: " + messageId);

        // Update the last processed ID
        lastProcessedMessageId = messageId;

        // Complete the future if it exists
        if (pendingResponseFuture != null && !pendingResponseFuture.isDone()) {
            LOG.info("Completing pending response future with content length: " + (content != null ? content.length() : 0));
            pendingResponseFuture.complete(content);
        } else {
            LOG.info("No pending response future to complete");
        }
    }

    /**
     * Handles showing diff and applying code replacement from the "To IDE" button
     *
     * @param textToReplace The text to find and replace (can be special value __##use_selected_text##__)
     * @param codeContent The new code content
     * @param language The language of the code
     * @return True if the operation was successful
     */
    private boolean handleShowCodeDiffAndReplace(String textToReplace, String codeContent, String language) {
        LOG.info("Handling show code diff and replace from To IDE button, language: " + language);

        try {
            // If special value is used, get selected text from editor
            if ("__##use_selected_text##__".equals(textToReplace)) {
                String selectedText = getSelectedTextFromEditor();
                if (selectedText != null && !selectedText.isEmpty()) {
                    textToReplace = selectedText;
                } else {
                    // No text selected, show error dialog
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showWarningDialog(project,
                                "No text is selected in the editor. Please select the text you want to replace.",
                                "No Text Selected");
                    });
                    return false;
                }
            }

            // If we have valid text to replace, show diff and handle replacement
            if (textToReplace != null && !textToReplace.isEmpty()) {
                return handleAdvancedCodeReplace(textToReplace, codeContent, language);
            } else {
                // No text to replace, offer to insert at cursor position
                ApplicationManager.getApplication().invokeLater(() -> {
                    int option = Messages.showYesNoDialog(project,
                            "No specific text to replace was found. Would you like to insert the code at the current cursor position?",
                            "Insert Code",
                            "Insert at Cursor",
                            "Cancel",
                            Messages.getQuestionIcon());

                    if (option == Messages.YES) {
                        insertTextToEditor(codeContent);
                    }
                });
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error handling show code diff and replace", e);
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project,
                        "Error processing code replacement: " + e.getMessage(),
                        "Code Replacement Error");
            });
            return false;
        }
    }

    /**
     * Advanced code replacement with enhanced diff display and confirmation
     *
     * @param textToReplace The text to find and replace
     * @param newCode The new code to replace it with
     * @param language The programming language
     * @return True if the operation was successful
     */
    private boolean handleAdvancedCodeReplace(String textToReplace, String newCode, String language) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            LOG.warn("No editor is currently open");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showWarningDialog(project,
                        "No editor is currently open.",
                        "No Editor Available");
            });
            return false;
        }

        Document document = editor.getDocument();
        String fullText = document.getText();

        // Find the text to replace
        int startOffset = -1;
        if (startOffset == -1) {
            // Try case-insensitive search
            String lowerFullText = fullText.toLowerCase();
            String lowerTextToReplace = textToReplace.toLowerCase();
            int lowerStartOffset = lowerFullText.indexOf(lowerTextToReplace);

            if (lowerStartOffset != -1) {
                // Found case-insensitive match, get the actual text
                startOffset = lowerStartOffset;
                textToReplace = fullText.substring(startOffset, startOffset + textToReplace.length());
            } else {
                startOffset = fullText.indexOf(textToReplace);
                String finalTextToReplace = textToReplace;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showWarningDialog(project,
                            "The specified text was not found in the current file:\n\n" +
                                    finalTextToReplace.substring(0, Math.min(100, finalTextToReplace.length())) +
                                    (finalTextToReplace.length() > 100 ? "..." : ""),
                            "Text Not Found");
                });
                return false;
            }
        } else {
            startOffset = fullText.indexOf(textToReplace);
        }

        int endOffset = startOffset + textToReplace.length();

        // Show diff in editor asynchronously
        String finalTextToReplace1 = textToReplace;
        int finalStartOffset = startOffset;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Create a temporary document with the replaced content for diff
                String currentContent = document.getText();
                String newContent = currentContent.substring(0, finalStartOffset) + newCode + currentContent.substring(endOffset);

                // Use IntelliJ's diff tool with enhanced titles
                DiffManager diffManager = DiffManager.getInstance();

                String fileName = "";
                if (editor.getVirtualFile() != null) {
                    fileName = editor.getVirtualFile().getName();
                }

                String diffTitle = "Code Replacement" + (fileName.isEmpty() ? "" : " in " + fileName);
                if (!language.isEmpty()) {
                    diffTitle += " (" + language + ")";
                }

                SimpleDiffRequest diffRequest = new SimpleDiffRequest(diffTitle,
                        DiffContentFactory.getInstance().create(currentContent),
                        DiffContentFactory.getInstance().create(newContent),
                        "Current Code", "Code with IDE Changes");

                // Setup hints for modal dialog with custom callback
                String finalFileName = fileName;
                DiffDialogHints hints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), new Consumer<WindowWrapper>() {
                    @Override
                    public void consume(WindowWrapper wrapper) {
                        if (wrapper != null && wrapper.getWindow() != null) {
                            // Add a listener to detect when the diff window is closed
                            wrapper.getWindow().addWindowListener(new WindowAdapter() {
                                @Override
                                public void windowClosed(WindowEvent e) {
                                    // Ask user if they want to apply the changes with more context
                                    String confirmMessage = "Do you want to apply these changes?\n\n" +
                                            "Replacing " + finalTextToReplace1.length() + " characters with " + newCode.length() + " characters" +
                                            (finalFileName.isEmpty() ? "" : " in " + finalFileName);

                                    int dialogResult = Messages.showYesNoDialog(
                                            project,
                                            confirmMessage,
                                            "Apply Code Replacement",
                                            "Apply Changes",
                                            "Cancel",
                                            Messages.getQuestionIcon()
                                    );

                                    if (dialogResult == Messages.YES) {
                                        // Apply the changes if accepted
                                        WriteCommandAction.runWriteCommandAction(project, () -> {
                                            try {
                                                document.replaceString(finalStartOffset, endOffset, newCode);

                                                // Show success message
                                                ApplicationManager.getApplication().invokeLater(() -> {
                                                    Messages.showInfoMessage(project,
                                                            "Code replacement completed successfully!",
                                                            "Replacement Success");
                                                });
                                            } catch (Exception ex) {
                                                LOG.error("Error applying code replacement", ex);
                                                ApplicationManager.getApplication().invokeLater(() -> {
                                                    Messages.showErrorDialog(project,
                                                            "Error applying code replacement: " + ex.getMessage(),
                                                            "Replacement Error");
                                                });
                                            }
                                        });
                                    } else {
                                        LOG.info("Code replacement cancelled by user");
                                    }
                                }
                            });
                        }
                    }
                });

                // Show diff dialog
                diffManager.showDiff(project, diffRequest, hints);

            } catch (Exception e) {
                LOG.error("Error showing diff for code replacement", e);
                Messages.showErrorDialog(project,
                        "Error showing diff: " + e.getMessage(),
                        "Diff Error");
            }
        });

        return true; // Return immediately, don't wait for user interaction
    }
}