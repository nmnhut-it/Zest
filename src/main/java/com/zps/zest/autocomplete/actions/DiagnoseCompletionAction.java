package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.project.Project;
import com.zps.zest.ZestNotifications;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.ZestAutocompleteFix;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.ZestInlayRenderer;
import org.jetbrains.annotations.NotNull;

/**
 * Diagnostic action to find and fix common completion issues.
 * This action can help clean up lingering inlays and debug completion state.
 */
public class DiagnoseCompletionAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(DiagnoseCompletionAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            LOG.warn("No project or editor available");
            return;
        }
        
        LOG.info("DiagnoseCompletionAction triggered");
        
        try {
            // Get the autocomplete service
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            LOG.info("Got autocomplete service: " + service);
            
            // Check current completion state
            boolean hasActiveCompletion = service.hasActiveCompletion(editor);
            ZestCompletionData.PendingCompletion completion = service.getActiveCompletion(editor);
            
            StringBuilder diagnosticInfo = new StringBuilder();
            diagnosticInfo.append("Completion Diagnostic:\n");
            diagnosticInfo.append("- Has active completion: ").append(hasActiveCompletion).append("\n");
            
            if (completion != null) {
                diagnosticInfo.append("- Completion details: ").append(completion).append("\n");
                diagnosticInfo.append("- Completion is active: ").append(completion.isActive()).append("\n");
                diagnosticInfo.append("- Completion has valid inlay: ").append(
                    completion.getInlay() != null && completion.getInlay().isValid()).append("\n");
            }
            
            // Count visible inlays
            int inlayCount = 0;
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(0, editor.getDocument().getTextLength())) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer ||
                    inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                    inlayCount++;
                }
            }
            diagnosticInfo.append("- Visible completion inlays: ").append(inlayCount).append("\n");
            
            // Run cleanup
            int cleanedInlays = ZestAutocompleteFix.cleanupInlays(editor, service);
            diagnosticInfo.append("- Cleaned up orphaned inlays: ").append(cleanedInlays).append("\n");
            
            // Force refresh completion state if needed
            if (cleanedInlays > 0 && hasActiveCompletion) {
                service.clearCompletion(editor);
                diagnosticInfo.append("- Forced completion state reset\n");
            }
            
            // Completion state after cleanup
            hasActiveCompletion = service.hasActiveCompletion(editor);
            diagnosticInfo.append("- Has active completion after cleanup: ").append(hasActiveCompletion).append("\n");
            
            // Display results
            ZestNotifications.showInfo(project, "Completion Diagnostics", diagnosticInfo.toString());
            LOG.info(diagnosticInfo.toString());
            
        } catch (Exception e) {
            LOG.error("Error in DiagnoseCompletionAction", e);
            ZestNotifications.showError(project, "Completion Diagnostics Error", 
                "Error: " + e.getMessage());
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        // Always enable when we have both project and editor
        event.getPresentation().setEnabled(project != null && editor != null);
    }
}
