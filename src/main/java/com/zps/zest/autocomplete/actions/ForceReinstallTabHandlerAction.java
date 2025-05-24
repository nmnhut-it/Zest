package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Force reinstall the Tab handler - useful for fixing conflicts with other plugins.
 */
public class ForceReinstallTabHandlerAction extends AnAction {
    
    public ForceReinstallTabHandlerAction() {
        super("🔄 Force Reinstall Tab Handler", 
              "Force reinstall ZestSmartTabHandler to fix conflicts", 
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        if (project == null) {
            Messages.showErrorDialog("No project available", "Force Reinstall");
            return;
        }

        try {
            // First uninstall
            ZestSmartTabHandler.uninstall();
            Thread.sleep(100); // Small delay
            
            // Then reinstall
            ZestSmartTabHandler.install();
            
            Messages.showInfoMessage(
                "✅ Tab handler reinstalled successfully!\n\n" +
                "The ZestSmartTabHandler has been uninstalled and reinstalled.\n" +
                "This should fix any conflicts with other plugins.\n\n" +
                "Try using Tab completion now.",
                "Reinstall Complete"
            );
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                "Error reinstalling Tab handler: " + ex.getMessage(),
                "Reinstall Failed"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
