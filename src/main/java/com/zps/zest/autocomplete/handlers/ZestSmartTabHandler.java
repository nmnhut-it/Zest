package com.zps.zest.autocomplete.handlers;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
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
     * Install this handler to replace the default TAB handler with enhanced conflict detection
     */
    public static void install() {
        EditorActionManager actionManager = EditorActionManager.getInstance();
        EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
        
        // Check if another plugin has already wrapped our handler
        if (currentHandler instanceof ZestSmartTabHandler) {
            LOG.warn("ZestSmartTabHandler already installed, skipping duplicate installation");
            return;
        }
        
        // Store reference to original handler class for conflict detection
        String originalHandlerClass = currentHandler.getClass().getSimpleName();
        LOG.info("Installing ZestSmartTabHandler, wrapping: " + originalHandlerClass);
        
        try {
            ZestSmartTabHandler smartHandler = new ZestSmartTabHandler(currentHandler);
            actionManager.setActionHandler("EditorTab", smartHandler);
            LOG.info("ZestSmartTabHandler installed successfully over " + originalHandlerClass);
            
            // Schedule a delayed check to detect if another plugin replaced us
            ApplicationManager.getApplication().invokeLater(() -> {
                EditorActionHandler newHandler = actionManager.getActionHandler("EditorTab");
                if (!(newHandler instanceof ZestSmartTabHandler)) {
                    LOG.warn("CONFLICT DETECTED: TAB handler was replaced by another plugin: " + 
                             newHandler.getClass().getSimpleName() + 
                             ". Zest autocomplete may not work properly.");
                    
                    // Attempt to re-install if it's safe to do so
                    if (shouldAttemptReinstall(newHandler)) {
                        LOG.info("Attempting to re-install ZestSmartTabHandler");
                        ZestSmartTabHandler newSmartHandler = new ZestSmartTabHandler(newHandler);
                        actionManager.setActionHandler("EditorTab", newSmartHandler);
                        LOG.info("ZestSmartTabHandler re-installed successfully");
                    }
                }
            });
            
        } catch (Exception e) {
            LOG.error("Failed to install ZestSmartTabHandler", e);
        }
    }
    
    /**
     * Determines if it's safe to attempt re-installation of our handler
     */
    private static boolean shouldAttemptReinstall(EditorActionHandler conflictingHandler) {
        // Don't re-install if the conflicting handler looks like it might be from another autocomplete plugin
        String handlerName = conflictingHandler.getClass().getSimpleName().toLowerCase();
        
        // List of known conflicting handler patterns
        String[] conflictingPatterns = {
            "copilot", "tabnine", "codegpt", "aiassistant", "completion", "autocomplete"
        };
        
        for (String pattern : conflictingPatterns) {
            if (handlerName.contains(pattern)) {
                LOG.info("Detected potential autocomplete plugin conflict: " + handlerName + 
                        ", not attempting re-install to avoid conflicts");
                return false;
            }
        }
        
        return true; // Safe to re-install
    }
    
    /**
     * Uninstall this handler and restore the original with enhanced safety checks
     */
    public static void uninstall() {
        try {
            EditorActionManager actionManager = EditorActionManager.getInstance();
            EditorActionHandler currentHandler = actionManager.getActionHandler("EditorTab");
            
            if (currentHandler instanceof ZestSmartTabHandler) {
                ZestSmartTabHandler smartHandler = (ZestSmartTabHandler) currentHandler;
                EditorActionHandler originalHandler = smartHandler.originalHandler;
                
                // Validate that the original handler is still valid
                if (originalHandler != null) {
                    actionManager.setActionHandler("EditorTab", originalHandler);
                    LOG.info("ZestSmartTabHandler uninstalled successfully, restored: " + 
                            originalHandler.getClass().getSimpleName());
                } else {
                    LOG.warn("Original handler was null, cannot restore properly");
                }
            } else {
                LOG.info("ZestSmartTabHandler not currently installed, current handler: " + 
                        currentHandler.getClass().getSimpleName());
            }
        } catch (Exception e) {
            LOG.error("Error during ZestSmartTabHandler uninstallation", e);
        }
    }
}
