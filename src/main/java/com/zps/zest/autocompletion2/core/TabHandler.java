package com.zps.zest.autocompletion2.core;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles Tab key presses for Zest Autocomplete v2.
 * Simple, focused implementation that only handles completion acceptance.
 */
public class TabHandler extends EditorActionHandler {
    private static final Logger LOG = Logger.getInstance(TabHandler.class);
    
    private final EditorActionHandler originalHandler;
    private static volatile TabHandler installedHandler;
    private static final Object INSTALL_LOCK = new Object();
    
    private TabHandler(@NotNull EditorActionHandler originalHandler) {
        this.originalHandler = originalHandler;
    }
    
    @Override
    protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        
        // Check if we should handle this Tab press
        if (project != null && shouldHandleTab(editor, project)) {
            LOG.debug("Handling Tab for Zest completion");
            
            AutocompleteService service = AutocompleteService.getInstance(project);
            boolean handled = service.handleTab(editor);
            
            if (handled) {
                LOG.debug("Tab handled by Zest autocomplete");
                return; // Don't delegate to original handler
            }
        }
        
        // Delegate to original handler
        LOG.debug("Delegating Tab to original handler");
        originalHandler.execute(editor, caret, dataContext);
    }
    
    /**
     * Determines if we should handle the Tab press.
     * Simple logic: only handle if there's an active Zest completion.
     */
    private boolean shouldHandleTab(@NotNull Editor editor, @NotNull Project project) {
        try {
            AutocompleteService service = AutocompleteService.getInstance(project);
            return service.isEnabled() && service.hasCompletion(editor);
        } catch (Exception e) {
            LOG.warn("Error checking if should handle Tab", e);
            return false;
        }
    }
    
    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        // Always enabled - we decide in doExecute whether to handle or delegate
        return originalHandler.isEnabled(editor, caret, dataContext);
    }
    
    /**
     * Installs the Tab handler globally.
     * Thread-safe and safe to call multiple times.
     */
    public static void install() {
        synchronized (INSTALL_LOCK) {
            EditorActionManager actionManager = EditorActionManager.getInstance();
            EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
            
            // Don't install if already installed
            if (currentHandler instanceof TabHandler) {
                LOG.debug("TabHandler already installed");
                return;
            }
            
            try {
                TabHandler newHandler = new TabHandler(currentHandler);
                actionManager.setActionHandler("EditorTab", newHandler);
                installedHandler = newHandler;
                
                LOG.info("TabHandler v2 installed successfully");
            } catch (Exception e) {
                LOG.error("Failed to install TabHandler", e);
            }
        }
    }
    
    /**
     * Uninstalls the Tab handler and restores the original.
     * Thread-safe.
     */
    public static void uninstall() {
        synchronized (INSTALL_LOCK) {
            if (installedHandler == null) {
                LOG.debug("TabHandler not installed, nothing to uninstall");
                return;
            }
            
            try {
                EditorActionManager actionManager = EditorActionManager.getInstance();
                EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
                
                if (currentHandler instanceof TabHandler) {
                    TabHandler tabHandler = (TabHandler) currentHandler;
                    actionManager.setActionHandler("EditorTab", tabHandler.originalHandler);
                    LOG.info("TabHandler v2 uninstalled successfully");
                } else {
                    LOG.warn("Current handler is not TabHandler, cannot uninstall safely");
                }
                
                installedHandler = null;
            } catch (Exception e) {
                LOG.error("Failed to uninstall TabHandler", e);
            }
        }
    }
    
    /**
     * Checks if the Tab handler is currently installed.
     */
    public static boolean isInstalled() {
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
        return currentHandler instanceof TabHandler;
    }
    
    @Override
    public String toString() {
        return "TabHandler{originalHandler=" + originalHandler.getClass().getSimpleName() + "}";
    }
}
