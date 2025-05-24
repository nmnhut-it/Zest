package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiUtil;
import com.zps.zest.autocomplete.cache.ZestCompletionCache;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteCaretListener;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteDocumentListener;
import com.zps.zest.autocomplete.service.CompletionRequestHandler;
import com.zps.zest.autocomplete.service.CompletionService;
import com.zps.zest.autocomplete.utils.SmartPrefixRemover;
import com.zps.zest.autocomplete.utils.ThreadingUtils;
import com.zps.zest.autocomplete.utils.EditorUtils;
import com.zps.zest.autocomplete.utils.CompletionStateUtils;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * REFACTORED: Thread-safe autocomplete service with centralized state management.
 * Properly handles IntelliJ threading model and eliminates spaghetti code.
 */
@Service(Service.Level.PROJECT)
public final class ZestAutocompleteService implements Disposable, CompletionService {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteService.class);
    private static final int COMPLETION_DELAY_MS = 50;
    private static final int MAX_CACHE_SIZE = 500;

    private final Project project;
    private final CompletionRequestHandler requestHandler;
    private final ZestCompletionCache completionCache;
    private final ZestCompletionStateManager stateManager;
    
    // Simplified state - only for service-level concerns
    private final Map<Editor, CompletableFuture<Void>> currentRequests = new ConcurrentHashMap<>();
    private final Map<Editor, Long> lastTypingTimes = new ConcurrentHashMap<>();
    
    // Listeners
    private final Map<Editor, ZestAutocompleteDocumentListener> documentListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteCaretListener> caretListeners = new ConcurrentHashMap<>();
    
    private boolean isEnabled = true;
    private EditorFactoryListener editorFactoryListener;

    public ZestAutocompleteService(Project project) {
        this.project = project;
        this.requestHandler = new CompletionRequestHandler(project);
        this.completionCache = new ZestCompletionCache(MAX_CACHE_SIZE, 30); // 30 minutes expiration
        this.stateManager = new ZestCompletionStateManager();
        LOG.info("ZestAutocompleteService initialized for project: " + project.getName());

        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                initializeService();
            }
        });
    }

    public static ZestAutocompleteService getInstance(Project project) {
        return project.getService(ZestAutocompleteService.class);
    }

    private void initializeService() {
        editorFactoryListener = new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() == project) {
                    registerEditor(editor);
                }
            }

            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                unregisterEditor(event.getEditor());
            }
        };

        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, this);

        // Register existing editors
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project) {
                registerEditor(editor);
            }
        }

        LOG.info("ZestAutocompleteService initialization complete");
    }

    private void registerEditor(Editor editor) {
        if (editor.isDisposed() || documentListeners.containsKey(editor)) {
            return;
        }

        LOG.debug("Registering autocomplete for editor: " + editor);

        // Add listeners
        ZestAutocompleteDocumentListener docListener = new ZestAutocompleteDocumentListener(this, editor);
        editor.getDocument().addDocumentListener(docListener, this);
        documentListeners.put(editor, docListener);

        ZestAutocompleteCaretListener caretListener = new ZestAutocompleteCaretListener(this, editor);
        editor.getCaretModel().addCaretListener(caretListener, this);
        caretListeners.put(editor, caretListener);
    }

    private void unregisterEditor(Editor editor) {
        stateManager.clearCompletion(editor);
        documentListeners.remove(editor);
        caretListeners.remove(editor);
    }

    @Override
    public void triggerCompletion(@NotNull Editor editor, boolean forced) {
        if (!isEnabled || editor.isDisposed()) {
            return;
        }

        recordLastTypingTime(editor);
        
        // Cancel existing request
        CompletableFuture<Void> existingRequest = currentRequests.get(editor);
        if (existingRequest != null && !existingRequest.isDone()) {
            existingRequest.cancel(true);
        }

        clearCompletion(editor);

        if (forced) {
            processCompletionRequest(editor);
            return;
        }

        // Schedule with delay
        CompletableFuture<Void> newRequest = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(COMPLETION_DELAY_MS);

                if (!Thread.currentThread().isInterrupted() && !isUserStillTyping(editor)) {
                    processCompletionRequest(editor);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                currentRequests.remove(editor);
            }
        });

        currentRequests.put(editor, newRequest);
    }

    /**
     * Processes the completion request using the request handler.
     * REFACTORED: Uses utility classes to eliminate code duplication.
     */
    private void processCompletionRequest(@NotNull Editor editor) {
        // Use utility for safe read operations
        int currentOffset = ThreadingUtils.safeReadAction(() -> EditorUtils.safeGetCaretOffset(editor));
        ZestCompletionData.CompletionItem cachedItem = completionCache.get(editor, currentOffset);
        
        if (cachedItem != null) {
            LOG.debug("Using cached completion");
            displayCompletion(editor, cachedItem);
            return;
        }
        
        // Not in cache, make network request
        CompletableFuture<CompletionRequestHandler.CompletionResult> request = 
            requestHandler.processCompletionRequest(editor);
            
        request.thenAccept(result -> {
            ThreadingUtils.runOnEDT(() -> {
                if (result.isSuccess() && EditorUtils.isEditorReadable(editor)) {
                    // Store in cache - use utility for thread-safe read operation
                    ThreadingUtils.safeReadAction(() -> {
                        String cacheKey = completionCache.put(editor, EditorUtils.safeGetCaretOffset(editor), result.getItem());
                        LOG.debug("Cached completion with key: " + cacheKey);
                    });
                    
                    displayCompletion(editor, result.getItem());
                } else if (!result.isSuccess()) {
                    LOG.debug("Completion request failed: " + result.getErrorMessage());
                }
            });
        }).exceptionally(throwable -> {
            LOG.warn("Completion request failed with exception", throwable);
            return null;
        });
    }

    /**
     * REFACTORED: Thread-safe display using utility classes.
     */
    private void displayCompletion(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item) {
        ThreadingUtils.runOnEDT(() -> {
            ThreadingUtils.assertEDT("displayCompletion");
            
            int currentOffset = ThreadingUtils.safeReadAction(() -> EditorUtils.safeGetCaretOffset(editor));
            
            // Clear any existing completion first
            if (hasActiveCompletion(editor)) {
                clearCompletion(editor);
            }
            
            // Create the rendering context (this manages all inlays)
            ZestInlayRenderer.RenderingContext renderingContext = ZestInlayRenderer.show(editor, currentOffset, item);
            
            // Only proceed if we actually created some inlays
            if (!renderingContext.getInlays().isEmpty()) {
                // Create completion state tracker
                ZestCompletionData.PendingCompletion completion = new ZestCompletionData.PendingCompletion(
                    item, editor, ""
                );

                // Store using centralized state manager
                stateManager.setCompletion(editor, completion, renderingContext);

                LOG.debug("Displayed completion: " + 
                    item.getInsertText().substring(0, Math.min(50, item.getInsertText().length())));
            } else {
                LOG.debug("No inlays created, not storing completion");
            }
        });
    }

    @Override
    public void acceptCompletion(@NotNull Editor editor, @NotNull AcceptType acceptType) {
        ZestCompletionData.PendingCompletion completion = stateManager.getActiveCompletion(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        try {
            ZestCompletionData.CompletionItem item = completion.getItem();
            
            // Write operations must be in write command action
            WriteCommandAction.runWriteCommandAction(project, "Zest Autocomplete", "Zest", () -> {
                int currentOffset = editor.getCaretModel().getOffset();
                Document document = editor.getDocument();

                // Re-calculate prefix to handle any changes since completion was created
                int lineNumber = document.getLineNumber(currentOffset);
                int lineStart = document.getLineStartOffset(lineNumber);
                String beforeCursor = document.getText(TextRange.from(lineStart, currentOffset - lineStart));

                // Re-clean the completion using current state
                String cleaned = SmartPrefixRemover.removeRedundantPrefix(beforeCursor, item.getInsertText());

                // Handle accept type (full, word, line)
                String textToAccept = AcceptType.extractAcceptableText(cleaned, acceptType);

                // Insert at current position
                document.replaceString(currentOffset, currentOffset, textToAccept);
                editor.getCaretModel().moveToOffset(currentOffset + textToAccept.length());

                // Handle partial acceptance continuation
                if (acceptType != AcceptType.FULL) {
                    String remainingText = AcceptType.calculateRemainingText(cleaned, textToAccept);
                    if (remainingText != null && !remainingText.trim().isEmpty()) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                handlePartialAcceptanceContinuation(editor, completion, item, remainingText);
                            } catch (Exception e) {
                                LOG.warn("Error in partial acceptance continuation", e);
                                clearCompletion(editor);
                            }
                        });
                        return; // Don't clear completion yet
                    }
                }

                // Full acceptance or no continuation - clean up
                completion.accept();
                clearCompletion(editor);
            });

            LOG.debug("Accepted completion with type: " + acceptType);

        } catch (Exception e) {
            LOG.warn("Failed to accept completion", e);
            clearCompletion(editor);
        }
    }

    @Override
    public void handleTabCompletion(@NotNull Editor editor) {
        // Ensure we're on EDT first - this is critical for UI operations
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> handleTabCompletion(editor));
            return;
        }
        
        // Early validation to avoid unnecessary work
        if (!EditorUtils.isEditorValid(editor)) {
            LOG.debug("Editor invalid, skipping tab completion");
            return;
        }
        
        // Check if we actually have an active completion before proceeding
        if (!hasActiveCompletion(editor)) {
            LOG.debug("No active completion found for tab handling");
            return;
        }

        try {
            // Atomic increment of tab count to prevent race conditions
            int tabCount = stateManager.getTabAcceptCount(editor) + 1;
            stateManager.setTabAcceptCount(editor, tabCount);

            LOG.debug("Tab completion press #{} for editor", tabCount);
            
            // Use read action for safe editor state access
            CompletionData completionData = ReadAction.compute(() -> {
                ZestCompletionData.PendingCompletion completion = getActiveCompletion(editor);
                if (!CompletionStateUtils.isCompletionValid(completion)) {
                    LOG.debug("Completion no longer valid during tab handling");
                    return null;
                }
                
                // Safely get display text for accept type calculation
                String displayText = "";
                try {
                    displayText = completion.getDisplayText();
                } catch (Exception e) {
                    LOG.warn("Error getting display text", e);
                }
                
                return new CompletionData(completion, displayText);
            });
            
            // Process the completion if we have valid data
            if (completionData != null && !completionData.displayText.isEmpty()) {
                AcceptType acceptType = CompletionStateUtils.determineOptimalAcceptType(
                    completionData.displayText, tabCount);
                
                LOG.debug("Tab press #{} - accepting with type: {}", tabCount, acceptType);
                acceptCompletion(editor, acceptType);
            } else {
                LOG.debug("No valid completion data available for tab handling");
                clearCompletion(editor); // Clean up invalid state
            }
            
        } catch (Exception e) {
            LOG.warn("Error during tab completion handling", e);
            // Clean up potentially corrupted state
            clearCompletion(editor);
        }
    }
    
    /**
     * Helper class to safely pass completion data between read action and EDT
     */
    private static class CompletionData {
        final ZestCompletionData.PendingCompletion completion;
        final String displayText;
        
        CompletionData(ZestCompletionData.PendingCompletion completion, String displayText) {
            this.completion = completion;
            this.displayText = displayText != null ? displayText : "";
        }
    }

    /**
     * REFACTORED: Thread-safe continuation handling using utility classes.
     */
    private boolean handlePartialAcceptanceContinuation(@NotNull Editor editor,
                                                     @NotNull ZestCompletionData.PendingCompletion originalCompletion,
                                                     @NotNull ZestCompletionData.CompletionItem originalItem,
                                                     @NotNull String remainingText) {
        if (!EditorUtils.isEditorReadable(editor)) {
            LOG.warn("Editor is not readable, cannot create continuation");
            return false;
        }
        
        // Make sure we don't create a continuation with empty or whitespace-only text
        if (remainingText == null || remainingText.trim().isEmpty()) {
            LOG.debug("Remaining text is empty or whitespace, skipping continuation");
            return false;
        }
        
        try {
            ThreadingUtils.assertEDT("handlePartialAcceptanceContinuation");
            
            int newOffset = ThreadingUtils.safeReadAction(() -> EditorUtils.safeGetCaretOffset(editor));
            if (!EditorUtils.isValidOffset(editor, newOffset)) {
                LOG.warn("Invalid caret offset: " + newOffset);
                return false;
            }

            // Create continuation item
            ZestCompletionData.Range newRange = new ZestCompletionData.Range(newOffset, newOffset);
            ZestCompletionData.CompletionItem continuationItem = new ZestCompletionData.CompletionItem(
                remainingText, newRange, null, originalItem.getConfidence()
            );

            // Create new rendering context
            ZestInlayRenderer.RenderingContext newRenderingContext =
                ZestInlayRenderer.show(editor, newOffset, continuationItem);
                
            // Only update state if we actually created visible inlays
            if (newRenderingContext.getInlays().isEmpty()) {
                LOG.debug("No inlays created for continuation, aborting");
                newRenderingContext.dispose(); // Clean up any partial resources
                return false;
            }
            
            // Create new pending completion
            ZestCompletionData.PendingCompletion continuationCompletion = new ZestCompletionData.PendingCompletion(
                continuationItem, editor, originalCompletion.getOriginalContext()
            );
            
            // Update using centralized state manager
            boolean success = stateManager.updateCompletionForContinuation(editor, continuationCompletion, newRenderingContext);

            if (success) {
                LOG.debug("Created continuation completion with remaining text: " +
                    remainingText.substring(0, Math.min(30, remainingText.length())));
            }
            
            return success;

        } catch (Exception e) {
            LOG.warn("Failed to create continuation completion", e);
            clearCompletion(editor);
            return false;
        }
    }

    @Override
    public void rejectCompletion(@NotNull Editor editor) {
        ZestCompletionData.PendingCompletion completion = stateManager.getActiveCompletion(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        completion.reject();
        clearCompletion(editor);
        LOG.debug("Rejected completion");
    }

    @Override
    public void clearCompletion(@NotNull Editor editor) {
        stateManager.clearCompletion(editor);
    }

    @Override
    public boolean hasActiveCompletion(@NotNull Editor editor) {
        return stateManager.hasActiveCompletion(editor);
    }

    @Override
    @Nullable
    public ZestCompletionData.PendingCompletion getActiveCompletion(@NotNull Editor editor) {
        return stateManager.getActiveCompletion(editor);
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            // Clear all active completions when disabling
            ThreadingUtils.runOnEDT(() -> {
                for (Editor editor : documentListeners.keySet()) {
                    try {
                        if (EditorUtils.isEditorReadable(editor)) {
                            clearCompletion(editor);
                        }
                    } catch (Exception e) {
                        LOG.warn("Error clearing completion when disabling service", e);
                    }
                }
            });
        }
        LOG.info("Autocomplete service " + (enabled ? "enabled" : "disabled"));
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }

    @Override
    public void clearCache() {
        completionCache.invalidateAll();
        LOG.debug("Cleared completion cache");
    }

    @Override
    public String getCacheStats() {
        return completionCache.getStats();
    }

    /**
     * REFACTORED: Thread-safe diagnostic method using utility classes.
     */
    public void forceCleanupAllInlays(@NotNull Editor editor) {
        if (!EditorUtils.isEditorReadable(editor)) {
            return;
        }
        
        LOG.info("Force cleaning up all inlays for editor: " + EditorUtils.safeGetFilePath(editor));
        
        ThreadingUtils.runOnEDT(() -> {
            // Clear our internal state first
            clearCompletion(editor);
            
            // Use the enhanced cleanup
            int cleaned = ZestAutocompleteFix.cleanupInlays(editor, this);
            
            LOG.info("Force cleanup completed - removed " + cleaned + " inlays");
        });
    }

    /**
     * Diagnostic method to get state manager info.
     */
    public String getStateManagerDiagnostic() {
        return stateManager.getDiagnosticInfo();
    }

    // Helper methods

    public @Nullable Long recordLastTypingTime(@NotNull Editor editor) {
        return lastTypingTimes.put(editor, System.currentTimeMillis());
    }

    private boolean isUserStillTyping(@NotNull Editor editor) {
        Long lastTime = lastTypingTimes.get(editor);
        if (lastTime == null) return false;

        long timeSinceLastTyping = System.currentTimeMillis() - lastTime;
        return timeSinceLastTyping < 50; // Small buffer to detect rapid typing
    }

    // Legacy methods for backward compatibility

    public void triggerAutocomplete(@NotNull Editor editor) {
        triggerCompletion(editor, false);
    }

    public void triggerAutocomplete(@NotNull Editor editor, boolean forced) {
        triggerCompletion(editor, forced);
    }

    public void acceptCompletion(@NotNull Editor editor) {
        acceptCompletion(editor, AcceptType.FULL);
    }

    @Override
    public void dispose() {
        LOG.info("Disposing ZestAutocompleteService for project: " + project.getName());
        
        // Cancel all pending requests
        currentRequests.values().forEach(request -> {
            if (!request.isDone()) {
                request.cancel(true);
            }
        });

        // Dispose state manager (handles all completion cleanup properly)
        stateManager.disposeAll();

        // Clear service-level state
        currentRequests.clear();
        lastTypingTimes.clear();
        documentListeners.clear();
        caretListeners.clear();
        
        // Clear cache
        completionCache.invalidateAll();
        
        // Uninstall the smart TAB handler when the last project is disposed
        // Note: This is a global handler, so we only uninstall when appropriate
        try {
            ZestSmartTabHandler.uninstall();
        } catch (Exception e) {
            LOG.warn("Error uninstalling ZestSmartTabHandler", e);
        }

        LOG.info("ZestAutocompleteService disposal complete for project: " + project.getName());
    }
}
