package com.zps.zest.autocomplete.handlers;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.completion.CompletionService;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.utils.TabCompletionContext;
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
            LOG.debug("No project - delegating to original handler");
            originalHandler.execute(editor, caret, dataContext);
            return;
        }
        
        // Enhanced debugging
        com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "START");
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
        
        // CRITICAL FIX: Check active completion FIRST before context detection
        boolean hasActiveZestCompletion = service.hasActiveCompletion(editor);
        
        if (hasActiveZestCompletion) {
            LOG.debug("DIRECT ZEST COMPLETION ACTIVE - handling TAB (BYPASSING CONTEXT)");
            com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "ZEST_DIRECT");
            
            try {
                service.handleTabCompletion(editor);
                LOG.debug("Tab completion handled by Zest successfully");
                return; // IMPORTANT: Return here to avoid delegating to IntelliJ
            } catch (Exception e) {
                LOG.warn("Error handling Zest tab completion, falling back", e);
                // Fall through to context detection
            }
        }
        
        // Use context-aware detection with ZEST PRIORITY (only if no direct completion)
        TabCompletionContext.ContextType context = TabCompletionContext.detectContext(editor, dataContext);
        LOG.debug("Detected context: {} (hasActiveCompletion={})", context, hasActiveZestCompletion);
        
        switch (context) {
            case PARAMETER_HINTS_ACTIVE:
                LOG.debug("Parameter hints active - delegating to IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "PARAM_HINTS");
                originalHandler.execute(editor, caret, dataContext);
                break;
                
            case BRACKET_NAVIGATION:
                LOG.debug("Bracket navigation context - delegating to IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "BRACKET_NAV");
                originalHandler.execute(editor, caret, dataContext);
                break;
                
            case ZEST_COMPLETION_ACTIVE:
                // This should have been caught above, but handle it just in case
                LOG.debug("ZEST completion active (context detection) - handling TAB");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "ZEST_CONTEXT");
                service.handleTabCompletion(editor);
                break;
                
            case ZEST_OPPORTUNITY:
                LOG.debug("ZEST opportunity detected - triggering Zest completion instead of IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "ZEST_OPPORTUNITY");
                // Cancel any IntelliJ popup to prioritize Zest
                cancelIntelliJCompletion(editor);
                service.triggerCompletion(editor, true); // Force trigger
                break;
                
            case LIVE_TEMPLATE_ACTIVE:
                LOG.debug("Live template active - delegating to IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "LIVE_TEMPLATE");
                originalHandler.execute(editor, caret, dataContext);
                break;
                
            case STRING_LITERAL:
            case COMMENT_CONTEXT:
                LOG.debug("Special text context - delegating to IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "SPECIAL_TEXT");
                originalHandler.execute(editor, caret, dataContext);
                break;
                
            case INTELLIJ_POPUP_ACTIVE:
                LOG.debug("IntelliJ popup active (lower priority) - checking for Zest override");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "INTELLIJ_POPUP");
                // Give Zest a chance to provide better completion
                if (shouldZestOverrideIntelliJ(editor)) {
                    LOG.debug("Zest overriding IntelliJ completion");
                    cancelIntelliJCompletion(editor);
                    service.triggerCompletion(editor, true);
                } else {
                    LOG.debug("Delegating to IntelliJ completion");
                    originalHandler.execute(editor, caret, dataContext);
                }
                break;

            case NORMAL_TYPING:
            default:
                LOG.debug("Normal context - delegating to IntelliJ");
                com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "NORMAL");
                originalHandler.execute(editor, caret, dataContext);
                
                // After normal TAB, check if Zest can provide completion
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!editor.isDisposed() && !service.hasActiveCompletion(editor)) {
                        // Create a simple DataContext for the delayed check
                        com.intellij.openapi.actionSystem.DataContext delayedDataContext = new com.intellij.openapi.actionSystem.DataContext() {
                            @Override
                            public Object getData(String dataId) {
                                if (com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.is(dataId)) {
                                    return editor;
                                }
                                if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.is(dataId)) {
                                    return editor.getProject();
                                }
                                return null;
                            }
                        };
                        
                        TabCompletionContext.ContextType newContext = TabCompletionContext.detectContext(editor, delayedDataContext);
                        if (newContext == TabCompletionContext.ContextType.ZEST_OPPORTUNITY) {
                            LOG.debug("Post-TAB Zest opportunity detected");
                            service.triggerCompletion(editor, false);
                        }
                    }
                });
                break;
        }
        
        com.zps.zest.autocomplete.debug.TabCompletionDebugger.logTabPress(editor, "END");
    }
    
    /**
     * Determines if Zest should override IntelliJ's completion popup.
     * This is where Zest takes priority over IntelliJ.
     */
    private boolean shouldZestOverrideIntelliJ(@NotNull Editor editor) {
        try {
            if (editor.getProject() == null) {
                return false;
            }
            
            // Get current context to see if Zest would be more useful
            Document document = editor.getDocument();
            int offset = editor.getCaretModel().getOffset();
            
            if (offset < 3) {
                return false; // Need context
            }
            
            // Get text before cursor
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String beforeCursor = document.getText(TextRange.from(lineStart, offset - lineStart));
            String trimmed = beforeCursor.trim();
            
            // Zest should override for complex completions that IntelliJ can't handle well
            
            // Method chaining: obj.method1().method2()
            if (trimmed.matches(".*\\w+\\.\\w+\\(.*\\)\\.\\w*$")) {
                LOG.debug("Zest override: complex method chaining detected");
                return true;
            }
            
            // Complex assignments with method calls
            if (trimmed.matches(".*=\\s*\\w+\\.\\w*$")) {
                LOG.debug("Zest override: assignment with method call detected");
                return true;
            }
            
            // Buffer/Stream operations (your specific use case)
            if (trimmed.matches(".*\\b(buffer|stream|writer|reader)\\.\\w*$")) {
                LOG.debug("Zest override: buffer/stream operation detected");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            LOG.warn("Error checking Zest override condition", e);
            return false;
        }
    }
    
    /**
     * Cancels IntelliJ's completion popup to give Zest priority.
     */
    private void cancelIntelliJCompletion(@NotNull Editor editor) {
        try {
            if (editor.getProject() == null) {
                return;
            }
            
            LookupManager lookupManager = LookupManager.getInstance(editor.getProject());
            if (lookupManager != null) {
                var activeLookup = lookupManager.getActiveLookup();
                if (activeLookup != null) {
                    activeLookup.hideLookup(true);
                    LOG.debug("Cancelled IntelliJ completion popup for Zest priority");
                }
            }
        } catch (Exception e) {
            LOG.warn("Error cancelling IntelliJ completion", e);
        }
    }
    
    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        Project project = editor.getProject();
        if (project == null) {
            return originalHandler.isEnabled(editor, caret, dataContext);
        }
        
        // Use context detection to determine if we should be enabled
        TabCompletionContext.ContextType context = TabCompletionContext.detectContext(editor, dataContext);
        
        // Enable for Zest contexts or delegate to original handler
        return context == TabCompletionContext.ContextType.ZEST_COMPLETION_ACTIVE ||
               context == TabCompletionContext.ContextType.ZEST_OPPORTUNITY ||
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
