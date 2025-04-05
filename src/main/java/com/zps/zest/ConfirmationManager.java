package com.zps.zest;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Manages user confirmations for important actions in the plugin.
 * Provides a centralized way to request user confirmation before executing potentially impactful operations.
 */
public class ConfirmationManager {

    /**
     * Requests confirmation from the user for a specified action.
     *
     * @param project The current project
     * @param title The dialog title
     * @param message The message explaining what action requires confirmation
     * @param yesText The text for the 'yes' button (default: "Proceed")
     * @param noText The text for the 'no' button (default: "Cancel")
     * @return true if the user confirmed, false otherwise
     */
    public static boolean requestConfirmation(
            @NotNull Project project, 
            String title, 
            String message,
            String yesText,
            String noText) {
        
        String confirmYes = yesText != null ? yesText : "Proceed";
        String confirmNo = noText != null ? noText : "Cancel";
        
        int result = Messages.showYesNoDialog(
                project,
                message,
                title,
                confirmYes,
                confirmNo,
                Messages.getQuestionIcon()
        );
        
        return result == Messages.YES;
    }
    
    /**
     * Requests confirmation with default button texts.
     *
     * @param project The current project
     * @param title The dialog title
     * @param message The message explaining what action requires confirmation
     * @return true if the user confirmed, false otherwise
     */
    public static boolean requestConfirmation(
            @NotNull Project project, 
            String title, 
            String message) {
        
        return requestConfirmation(project, title, message, null, null);
    }
}