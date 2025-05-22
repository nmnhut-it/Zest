package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Service for handling dialog operations from JavaScript bridge.
 * This includes showing various types of dialogs and user confirmations.
 */
public class DialogService {
    private static final Logger LOG = Logger.getInstance(DialogService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    public DialogService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Shows a dialog with the specified title and message.
     */
    public String showDialog(JsonObject data) {
        try {
            String title = data.has("title") ? data.get("title").getAsString() : "Information";
            String message = data.has("message") ? data.get("message").getAsString() : "";
            String dialogType = data.has("type") ? data.get("type").getAsString() : "info";
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().invokeLater(() -> {
                showDialogInternal(title, message, dialogType);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("result", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error showing dialog", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Internal implementation for showing dialogs.
     * This method is now non-blocking and doesn't return the user's response.
     */
    private void showDialogInternal(String title, String message, String dialogType) {
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
     * Shows an informational dialog.
     */
    public void showInfo(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(project, message, title);
        });
    }
    
    /**
     * Shows a warning dialog.
     */
    public void showWarning(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showWarningDialog(project, message, title);
        });
    }
    
    /**
     * Shows an error dialog.
     */
    public void showError(String title, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, message, title);
        });
    }
    
    /**
     * Shows a yes/no confirmation dialog.
     * @return CompletableFuture that resolves to true for YES, false for NO
     */
    public java.util.concurrent.CompletableFuture<Boolean> showConfirmation(String title, String message) {
        java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
        
        ApplicationManager.getApplication().invokeLater(() -> {
            int result = Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon());
            future.complete(result == Messages.YES);
        });
        
        return future;
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
}
