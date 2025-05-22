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
 * Action to accept the next word from the current autocomplete suggestion.
 * Provides granular acceptance following Tabby ML patterns.
 */
public class AcceptWordCompletionAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AcceptWordCompletionAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        if (service.hasActiveCompletion(editor)) {
            LOG.debug("Accepting word completion");
            service.acceptCompletion(editor, AcceptType.NEXT_WORD);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        boolean hasActiveCompletion = false;
        boolean isWordAcceptable = false;
        
        if (project != null && editor != null) {
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            hasActiveCompletion = service.hasActiveCompletion(editor);
            
            if (hasActiveCompletion) {
                // Check if word acceptance makes sense for current completion
                var completion = service.getActiveCompletion(editor);
                if (completion != null) {
                    String completionText = completion.getItem().getInsertText();
                    isWordAcceptable = true;//AcceptType.AcceptanceValidator.isValidAcceptType(completionText, AcceptType.NEXT_WORD);
                }
            }
        }
        
        // Only enable when there's an active completion that supports word acceptance
        event.getPresentation().setEnabled(hasActiveCompletion && isWordAcceptable);
        event.getPresentation().setVisible(hasActiveCompletion);
    }
}
