package com.zps.zest.autocomplete.handlers;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Smart TAB handler that integrates with IntelliJ's built-in TAB functionality.
 * Only intercepts TAB when Zest has an active completion, otherwise delegates to IntelliJ.
 */
public class ZestSmartTabHandler extends EditorActionHandler {
    private static final Logger LOG = Logger.getInstance(ZestSmartTabHandler.class);
    
    private final EditorActionHandler originalHandler;
    
    public ZestSmartTabHandler(EditorActionHandler originalHandler) {
        this.originalHandler = originalHandler;
    }
    
    @Override
    protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        if (project == null) {
            originalHandler.execute(editor, caret, dataContext);
            return;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        
        // Only handle TAB if we have an active Zest completion
        if (service.hasActiveCompletion(editor)) {
            LOG.debug("Zest completion active - handling TAB");
            service.handleTabCompletion(editor);
        } else {
            // Delegate to IntelliJ's built-in TAB handling
            LOG.debug("No Zest completion - delegating to IntelliJ TAB handler");
            originalHandler.execute(editor, caret, dataContext);
        }
    }
    
    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        if (project == null) {
            return originalHandler.isEnabled(editor, caret, dataContext);
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        
        // Enable if we have completion OR if the original handler would be enabled
        return service.hasActiveCompletion(editor) || 
               originalHandler.isEnabled(editor, caret, dataContext);
    }
    
    /**
     * Install this handler to replace the default TAB handler
     */
    public static void install() {
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler originalHandler = actionManager.getActionHandler("EditorTab");
        
        if (!(originalHandler instanceof ZestSmartTabHandler)) {
            ZestSmartTabHandler smartHandler = new ZestSmartTabHandler(originalHandler);
            actionManager.setActionHandler("EditorTab", smartHandler);
            LOG.info("ZestSmartTabHandler installed successfully");
        }
    }
    
    /**
     * Uninstall this handler and restore the original
     */
    public static void uninstall() {
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
        
        if (currentHandler instanceof ZestSmartTabHandler) {
            ZestSmartTabHandler smartHandler = (ZestSmartTabHandler) currentHandler;
            actionManager.setActionHandler("EditorTab", smartHandler.originalHandler);
            LOG.info("ZestSmartTabHandler uninstalled successfully");
        }
    }
}
