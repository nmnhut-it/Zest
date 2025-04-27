package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.BrowserTestHelper;
import org.jetbrains.annotations.NotNull;

/**
 * Action to test the browser functionality.
 */
public class TestBrowserAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        BrowserTestHelper.runBasicTest(project);
    }
}
