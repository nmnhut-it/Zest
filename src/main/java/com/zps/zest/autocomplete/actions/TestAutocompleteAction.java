package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.ZestNotifications;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to manually trigger autocomplete and verify the service is working.
 * This is for debugging purposes only.
 */
public class TestAutocompleteAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestAutocompleteAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            LOG.warn("No project or editor available");
            return;
        }
        
        LOG.info("TestAutocompleteAction triggered");
        
        try {
            // Get the autocomplete service
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            LOG.info("Got autocomplete service: " + service);
            
            // Show service status
            String status = "Autocomplete Service Status:\n" +
                          "- Enabled: " + service.isEnabled() + "\n" +
                          "- Has active completion: " + service.hasActiveCompletion(editor) + "\n" +
                          "- Cache stats: " + service.getCacheStats();
            
            ZestNotifications.showInfo(project, "Autocomplete Test", status);
            
            // Manually trigger autocomplete
            LOG.info("Manually triggering autocomplete");
            service.triggerAutocomplete(editor);
            
        } catch (Exception e) {
            LOG.error("Error in TestAutocompleteAction", e);
            ZestNotifications.showError(project, "Autocomplete Test Error", 
                "Error: " + e.getMessage());
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        // Only enable when we have both project and editor
        event.getPresentation().setEnabled(project != null && editor != null);
    }
}
