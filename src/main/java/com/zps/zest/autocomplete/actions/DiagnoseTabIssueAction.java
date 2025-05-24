package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.zps.zest.autocomplete.debug.TabCompletionDebugger;
import org.jetbrains.annotations.NotNull;

/**
 * Action to diagnose and fix Tab completion issues.
 * This will help identify why Tab is canceling completion instead of accepting it.
 */
public class DiagnoseTabIssueAction extends AnAction {
    
    public DiagnoseTabIssueAction() {
        super("Diagnose Tab Completion Issue", 
              "Diagnose why Tab key cancels completion instead of accepting it", 
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            TabCompletionDebugger.showDiagnosticDialog(editor);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
    }
}
