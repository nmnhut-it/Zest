package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Simplified autocomplete service that delegates complex logic to specialized components.
 * Focuses on state management and coordination.
 */
@Service(Service.Level.PROJECT)
public final class ZestAutocompleteService implements Disposable, CompletionService {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteService.class);
    private static final int COMPLETION_DELAY_MS = 50;
    private static final int MAX_CACHE_SIZE = 500;

    private final Project project;
    private final CompletionRequestHandler requestHandler;
    private final ZestCompletionCache completionCache;
    
    // State management - simplified
    private final Map<Editor, ZestCompletionData.PendingCompletion> activeCompletions = new ConcurrentHashMap<>();
    private final Map<Editor, ZestInlayRenderer.RenderingContext> renderingContexts = new ConcurrentHashMap<>();
    private final Map<Editor, Integer> tabAcceptCounts = new ConcurrentHashMap<>();
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
        LOG.info("ZestAutocompleteService initialized for project: " + project.getName());
//        Keymap active = KeymapManager.getInstance().getActiveKeymap();
//        @NotNull String[] tabs = active.getActionIds(KeyStroke.getKeyStroke("TAB"));
//        for (String t: tabs){
//            if (t.equals("Zest.AcceptWordCompletion")){
//
//            }
//            else {
//                Shortcut[] shortcuts = active.getShortcuts(t);
//                if (shortcuts.length > 0) {
//                    active.removeShortcut(t, shortcuts[0]);
//                }
//            }
//        }
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
        clearCompletion(editor);
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
     * Now with cache check before making a network request.
     */
    private void processCompletionRequest(@NotNull Editor editor) {
        // Check cache first
        int currentOffset = editor.getCaretModel().getOffset();
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
            ApplicationManager.getApplication().invokeLater(() -> {
                if (result.isSuccess() && !editor.isDisposed()) {
                    // Store in cache for future use
                    String cacheKey = completionCache.put(editor, editor.getCaretModel().getOffset(), result.getItem());
                    LOG.debug("Cached completion with key: " + cacheKey);
                    
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
     * Displays the completion using the renderer.
     * FIXED: Proper separation of concerns - PendingCompletion tracks state, RenderingContext manages inlays.
     */
    private void displayCompletion(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item) {
        try {
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Clear any existing completion first
            if (hasActiveCompletion(editor)){
                clearCompletion(editor);
            }
            
            // Create the rendering context (this manages all inlays)
            ZestInlayRenderer.RenderingContext renderingContext = ZestInlayRenderer.show(editor, currentOffset, item);
            
            // Only proceed if we actually created some inlays
            if (!renderingContext.getInlays().isEmpty()) {
                // Create completion state tracker (does NOT manage inlays)
                ZestCompletionData.PendingCompletion completion = new ZestCompletionData.PendingCompletion(
                    item, editor, ""
                );

                // Store both - they have separate responsibilities
                activeCompletions.put(editor, completion);
                renderingContexts.put(editor, renderingContext);
                tabAcceptCounts.put(editor, 0);

                LOG.debug("Displayed completion: " + 
                    item.getInsertText().substring(0, Math.min(50, item.getInsertText().length())));
            } else {
                LOG.debug("No inlays created, not storing completion");
            }
        } catch (Exception e) {
            LOG.warn("Failed to display completion", e);
        }
    }

    @Override
    public void acceptCompletion(@NotNull Editor editor, @NotNull AcceptType acceptType) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        try {
            ZestCompletionData.CompletionItem item = completion.getItem();
            int currentOffset = editor.getCaretModel().getOffset();
            Document document = editor.getDocument();

            // Re-calculate prefix to handle any changes since completion was created
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String beforeCursor = document.getText().substring(lineStart, currentOffset);

            // Re-clean the completion using current state
            String cleaned = SmartPrefixRemover.removeRedundantPrefix(beforeCursor, item.getInsertText());

            // Handle accept type (full, word, line)
            String textToAccept = AcceptType.extractAcceptableText(cleaned, acceptType);

            // Insert at current position
            VirtualFile virtualFile = editor.getVirtualFile();
            WriteCommandAction.runWriteCommandAction(project, "autocomplete", "Zest", () -> {
                document.replaceString(currentOffset, currentOffset, textToAccept);
                editor.getCaretModel().moveToOffset(currentOffset + textToAccept.length());
            }, PsiUtil.getPsiFile(project, virtualFile));

            // Handle partial acceptance continuation
            if (acceptType != AcceptType.FULL) {
                String remainingText = AcceptType.calculateRemainingText(cleaned, textToAccept);
                if (remainingText != null && !remainingText.trim().isEmpty()) {
                    try {
                        handlePartialAcceptanceContinuation(editor, completion, item, remainingText);
                        // Check if continuation was successful
                        if (!hasActiveCompletion(editor)) {
                            LOG.debug("Partial acceptance failed to create continuation, cleaning up");
                            clearCompletion(editor);
                            tabAcceptCounts.remove(editor);
                        }
                        return; // Don't clear completion if continuation was successful
                    } catch (Exception e) {
                        LOG.warn("Error in partial acceptance continuation", e);
                        // If continuation fails, fall through to cleanup
                    }
                }
            }

            completion.accept();
            clearCompletion(editor);
            tabAcceptCounts.remove(editor);

            LOG.debug("Accepted completion with type: " + acceptType);

        } catch (Exception e) {
            LOG.warn("Failed to accept completion", e);
        }
    }

    @Override
    public void handleTabCompletion(@NotNull Editor editor) {
        if (!hasActiveCompletion(editor)) {
            return;
        }

        int tabCount = tabAcceptCounts.getOrDefault(editor, 0) + 1;
        tabAcceptCounts.put(editor, tabCount);

        LOG.debug("Tab completion press #" + tabCount);
        
        // Get current completion to determine best progression
        ZestCompletionData.PendingCompletion completion = getActiveCompletion(editor);
        if (completion == null) {
            LOG.debug("No active completion found despite hasActiveCompletion returning true");
            return;
        }
        Document document = editor.getDocument();
        int lineNumber = editor.getCaretModel().getLogicalPosition().line;
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        String currentLinePrefix = document.getText(document.createRangeMarker(lineStart, lineEnd).getTextRange());
        String remainingText = completion.getDisplayText().replace(currentLinePrefix.trim(),"'");
        AcceptType acceptType;
        
        // Determine most appropriate accept type based on remaining text and tab count
        switch (tabCount) {
            case 1: // First tab press - word
                acceptType = AcceptType.WORD;
                LOG.debug("First tab press - accepting next word");
                break;
                
            case 2: // Second tab press - line
                acceptType = AcceptType.LINE;
                LOG.debug("Second tab press - accepting next line");
                break;
                
            default: // Third or more tab presses - full
                acceptType = AcceptType.FULL;
                LOG.debug("Third+ tab press - accepting full completion");
        }
        
        // Smart fallback: if word is very short but full text isn't, go to next level
        if (acceptType == AcceptType.WORD) {
            String wordText = AcceptType.extractAcceptableText(remainingText, AcceptType.WORD);
            if (wordText.length() <= 2 && remainingText.length() > wordText.length() * 3) {
                acceptType = AcceptType.LINE;
                LOG.debug("Word too short, upgraded to line acceptance");
            }
        }
        
        // Smart fallback: if line is very short but full text isn't, go to next level
        if (acceptType == AcceptType.LINE) {
            String lineText = AcceptType.extractAcceptableText(remainingText, AcceptType.LINE);
            if (lineText.length() <= 3 && remainingText.length() > lineText.length() * 3) {
                acceptType = AcceptType.FULL;
                LOG.debug("Line too short, upgraded to full acceptance");
            }
        }

        acceptCompletion(editor, acceptType);
    }

    /**
     * Handles continuation after partial acceptance.
     * FIXED: Proper separation - PendingCompletion for state, RenderingContext for inlays.
     * 
     * @return true if the continuation was successfully created and displayed, false otherwise
     */
    private boolean handlePartialAcceptanceContinuation(@NotNull Editor editor,
                                                     @NotNull ZestCompletionData.PendingCompletion originalCompletion,
                                                     @NotNull ZestCompletionData.CompletionItem originalItem,
                                                     @NotNull String remainingText) {
        if (editor.isDisposed()) {
            LOG.warn("Editor is disposed, cannot create continuation");
            return false;
        }
        
        // Make sure we don't create a continuation with empty or whitespace-only text
        if (remainingText == null || remainingText.trim().isEmpty()) {
            LOG.debug("Remaining text is empty or whitespace, skipping continuation");
            return false;
        }
        
        try {
            int newOffset = editor.getCaretModel().getOffset();
            if (newOffset < 0 || newOffset >= editor.getDocument().getTextLength()) {
                LOG.warn("Invalid caret offset: " + newOffset);
                return false;
            }

            // First, clean up the existing rendering context (this handles all inlay disposal)
            clearRenderingContext(editor);
            
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
            
            // Create new pending completion (state only, no inlay management)
            ZestCompletionData.PendingCompletion continuationCompletion = new ZestCompletionData.PendingCompletion(
                continuationItem, editor, originalCompletion.getOriginalContext()
            );
            
            // Replace current completion with continuation and save the new rendering context
            activeCompletions.put(editor, continuationCompletion);
            renderingContexts.put(editor, newRenderingContext);

            LOG.debug("Created continuation completion with remaining text: " +
                remainingText.substring(0, Math.min(30, remainingText.length())));
            return true;

        } catch (Exception e) {
            LOG.warn("Failed to create continuation completion", e);
            clearCompletion(editor);
            return false;
        }
    }

    @Override
    public void rejectCompletion(@NotNull Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        completion.reject();
        clearCompletion(editor);
        LOG.debug("Rejected completion");
    }

    @Override
    public void clearCompletion(@NotNull Editor editor) {
        if (editor == null || editor.isDisposed()) {
            return;
        }

        LOG.debug("Clearing completion for editor");

        // Clear pending completion state (this does NOT manage inlays anymore)
        ZestCompletionData.PendingCompletion completion = activeCompletions.remove(editor);
        if (completion != null) {
            completion.dispose(); // Only marks as disposed, doesn't handle inlays
        }

        // Reset tab count
        tabAcceptCounts.remove(editor);
        
        // Clear rendering context (this handles ALL inlay cleanup)
        clearRenderingContext(editor);
    }

    private void clearRenderingContext(@NotNull Editor editor) {
        if (editor == null || editor.isDisposed()) {
            return;
        }

        ZestInlayRenderer.RenderingContext renderingContext = renderingContexts.remove(editor);

        if (renderingContext != null) {
            try {
                int disposedCount = renderingContext.dispose();
                LOG.debug("Successfully disposed " + disposedCount + " rendering elements from context");
            } catch (Exception e) {
                LOG.warn("Error disposing rendering context", e);
                // Fallback: Try to dispose each inlay individually
                for (Inlay<?> inlay : renderingContext.getInlays()) {
                    try {
                        if (inlay != null && inlay.isValid()) {
                            inlay.dispose();
                            LOG.debug("Individually disposed inlay at offset " + inlay.getOffset());
                        }
                    } catch (Exception ex) {
                        LOG.warn("Error disposing individual inlay", ex);
                    }
                }
            }
        }
        
        // Additional cleanup using ZestAutocompleteFix for any orphaned inlays
        try {
            ZestAutocompleteFix.cleanupInlays(editor, this);
        } catch (Exception e) {
            LOG.warn("Error in ZestAutocompleteFix cleanup", e);
        }
    }

    @Override
    public boolean hasActiveCompletion(@NotNull Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        ZestInlayRenderer.RenderingContext renderingContext = renderingContexts.get(editor);
        
        // Both must be present and active for a completion to be considered active
        boolean hasValidCompletion = completion != null && completion.isActive();
        boolean hasValidRendering = renderingContext != null && !renderingContext.getInlays().isEmpty();
        
        // If they're inconsistent, clean up and return false
        if (hasValidCompletion != hasValidRendering) {
            LOG.debug("Inconsistent completion state detected, cleaning up");
            clearCompletion(editor);
            return false;
        }
        
        return hasValidCompletion && hasValidRendering;
    }

    @Override
    @Nullable
    public ZestCompletionData.PendingCompletion getActiveCompletion(@NotNull Editor editor) {
        return activeCompletions.get(editor);
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            activeCompletions.keySet().forEach(this::clearCompletion);
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
     * Forces cleanup of all inlays in the editor. 
     * This is a diagnostic method for when inlays get stuck.
     */
    public void forceCleanupAllInlays(@NotNull Editor editor) {
        if (editor == null || editor.isDisposed()) {
            return;
        }
        
        LOG.info("Force cleaning up all inlays for editor");
        
        // Clear our internal state first
        clearCompletion(editor);
        
        // Use the enhanced cleanup
        int cleaned = ZestAutocompleteFix.cleanupInlays(editor, this);
        
        LOG.info("Force cleanup completed - removed " + cleaned + " inlays");
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

        // Clear all completions properly (this will handle both state and rendering cleanup)
        activeCompletions.keySet().forEach(editor -> {
            try {
                clearCompletion(editor);
            } catch (Exception e) {
                LOG.warn("Error clearing completion during dispose", e);
            }
        });

        // Clear all remaining state
        currentRequests.clear();
        lastTypingTimes.clear();
        activeCompletions.clear();
        renderingContexts.clear();
        documentListeners.clear();
        caretListeners.clear();
        tabAcceptCounts.clear();
        
        // Clear cache
        completionCache.invalidateAll();

        LOG.info("ZestAutocompleteService disposal complete for project: " + project.getName());
    }
}
