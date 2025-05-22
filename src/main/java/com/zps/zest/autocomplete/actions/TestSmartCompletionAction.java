package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Test action to demonstrate smart tab completion behavior.
 * Use this to trigger completion for testing.
 */
public class TestSmartCompletionAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestSmartCompletionAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        LOG.info("Triggering test autocomplete");
        service.triggerAutocomplete(editor);
    }
}
