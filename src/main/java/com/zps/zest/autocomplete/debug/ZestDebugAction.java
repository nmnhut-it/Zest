package com.zps.zest.autocomplete.debug;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Debug action to check the state of Zest autocomplete system
 */
public class ZestDebugAction extends AnAction {
    
    public ZestDebugAction() {
        super("Zest Autocomplete Debug Info");
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        if (project == null || editor == null) {
            return;
        }
        
        StringBuilder info = new StringBuilder();
        
        // Check TAB handler installation
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler tabHandler = actionManager.getActionHandler("EditorTab");
        
        info.append("TAB Handler Information:\n");
        info.append("Current handler class: ").append(tabHandler.getClass().getName()).append("\n");
        info.append("Is ZestSmartTabHandler: ").append(tabHandler instanceof ZestSmartTabHandler).append("\n\n");
        
        // Check Zest service state
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        info.append("Zest Service State:\n");
        info.append("Service enabled: ").append(service.isEnabled()).append("\n");
        info.append("Has active completion: ").append(service.hasActiveCompletion(editor)).append("\n");
        
        // Get state manager diagnostic
        info.append("\n").append(service.getStateManagerDiagnostic());
        
        // Get cache stats
        info.append("\nCache Stats:\n").append(service.getCacheStats());
        
        Messages.showMessageDialog(project, info.toString(), "Zest Debug Info", Messages.getInformationIcon());
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }
}
