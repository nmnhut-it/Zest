package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import org.cef.browser.CefBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
                case "showDialog":
                    String title = data.has("title") ? data.get("title").getAsString() : "Information";
                    String message = data.has("message") ? data.get("message").getAsString() : "";
                    String dialogType = data.has("type") ? data.get("type").getAsString() : "info";

                    boolean dialogResult = showDialog(title, message, dialogType);
                    response.addProperty("success", true);
                    response.addProperty("result", dialogResult);
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
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
}
