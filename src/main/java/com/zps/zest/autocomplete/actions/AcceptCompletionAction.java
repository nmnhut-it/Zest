package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to accept the current autocomplete suggestion.
 * Typically triggered by the Tab key.
 */
public class AcceptCompletionAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        if (service.hasActiveCompletion(editor)) {
            service.acceptCompletion(editor);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        boolean hasActiveCompletion = false;
        
        if (project != null && editor != null) {
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            hasActiveCompletion = service.hasActiveCompletion(editor);
        }
        
        // Only enable this action when there's an active completion
        event.getPresentation().setEnabled(hasActiveCompletion);
        event.getPresentation().setVisible(hasActiveCompletion);
    }
}
