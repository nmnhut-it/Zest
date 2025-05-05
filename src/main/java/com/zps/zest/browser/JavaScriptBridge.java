package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
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
import com.intellij.util.Consumer;
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
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return "";
        }
        
        String selectedText = editor.getSelectionModel().getSelectedText();
        return selectedText != null ? selectedText : "";
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
}
