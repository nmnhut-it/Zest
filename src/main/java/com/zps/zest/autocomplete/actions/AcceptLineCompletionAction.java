package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.autocomplete.AcceptType;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to accept the next line from the current autocomplete suggestion.
 * Useful for multi-line completions following Tabby ML patterns.
 */
public class AcceptLineCompletionAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AcceptLineCompletionAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        if (service.hasActiveCompletion(editor)) {
            LOG.debug("Accepting line completion");
            service.acceptCompletion(editor, AcceptType.LINE);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        boolean hasActiveCompletion = false;
        boolean isLineAcceptable = false;
        
        if (project != null && editor != null) {
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            hasActiveCompletion = service.hasActiveCompletion(editor);
            
            if (hasActiveCompletion) {
                // Check if line acceptance makes sense for current completion
                var completion = service.getActiveCompletion(editor);
                if (completion != null) {
                    String completionText = completion.getItem().getInsertText();
                    isLineAcceptable = true; //AcceptType.AcceptanceValidator.isValidAcceptType(completionText, AcceptType.NEXT_LINE);
                }
            }
        }
        
        // Only enable when there's an active completion that supports line acceptance
        event.getPresentation().setEnabled(hasActiveCompletion && isLineAcceptable);
        event.getPresentation().setVisible(hasActiveCompletion);
    }
}
