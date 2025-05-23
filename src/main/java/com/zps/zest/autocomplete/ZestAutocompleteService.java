package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.util.PsiUtil;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteCaretListener;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteDocumentListener;
import com.zps.zest.autocomplete.service.CompletionRequestHandler;
import com.zps.zest.autocomplete.service.CompletionService;
import com.zps.zest.autocomplete.utils.SmartPrefixRemover;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private static final int COMPLETION_DELAY_MS = 100;
    private static final int MAX_CACHE_SIZE = 100;

    private final Project project;
    private final CompletionRequestHandler requestHandler;
    
    // State management - simplified
    private final Map<Editor, ZestCompletionData.PendingCompletion> activeCompletions = new ConcurrentHashMap<>();
    private final Map<String, ZestCompletionData.CompletionItem> completionCache = new ConcurrentHashMap<>();
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
     */
    private void processCompletionRequest(@NotNull Editor editor) {
        CompletableFuture<CompletionRequestHandler.CompletionResult> request = 
            requestHandler.processCompletionRequest(editor);
            
        request.thenAccept(result -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (result.isSuccess() && !editor.isDisposed()) {
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
     */
    private void displayCompletion(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item) {
        try {
            int currentOffset = editor.getCaretModel().getOffset();
            ZestInlayRenderer.RenderingContext renderingContext = ZestInlayRenderer.show(editor, currentOffset, item);
            
            if (!renderingContext.getInlays().isEmpty()) {
                ZestCompletionData.PendingCompletion completion = new ZestCompletionData.PendingCompletion(
                    item, editor, ""
                );

                activeCompletions.put(editor, completion);
                renderingContexts.put(editor, renderingContext);
                tabAcceptCounts.put(editor, 0);

                LOG.debug("Displayed completion: " + 
                    item.getInsertText().substring(0, Math.min(50, item.getInsertText().length())));
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
            if (acceptType != AcceptType.FULL_COMPLETION) {
                String remainingText = AcceptType.calculateRemainingText(cleaned, textToAccept);
                if (remainingText != null && !remainingText.trim().isEmpty()) {
                    handlePartialAcceptanceContinuation(editor, completion, item, remainingText);
                    return; // Don't clear completion yet
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

        AcceptType acceptType;
        switch (tabCount) {
            case 1:
                acceptType = AcceptType.NEXT_WORD;
                break;
            case 2:
                acceptType = AcceptType.NEXT_LINE;
                break;
            default:
                acceptType = AcceptType.FULL_COMPLETION;
        }

        acceptCompletion(editor, acceptType);
    }

    /**
     * Handles continuation after partial acceptance.
     */
    private void handlePartialAcceptanceContinuation(@NotNull Editor editor,
                                                     @NotNull ZestCompletionData.PendingCompletion originalCompletion,
                                                     @NotNull ZestCompletionData.CompletionItem originalItem,
                                                     @NotNull String remainingText) {
        try {
            int newOffset = editor.getCaretModel().getOffset();

            // Create continuation item
            ZestCompletionData.Range newRange = new ZestCompletionData.Range(newOffset, newOffset);
            ZestCompletionData.CompletionItem continuationItem = new ZestCompletionData.CompletionItem(
                remainingText, newRange, null, originalItem.getConfidence()
            );

            // Create new pending completion
            ZestCompletionData.PendingCompletion continuationCompletion = new ZestCompletionData.PendingCompletion(
                continuationItem, editor, originalCompletion.getOriginalContext()
            );

            // Replace current completion with continuation
            activeCompletions.put(editor, continuationCompletion);

            // Update rendering context
            clearRenderingContext(editor);
            ZestInlayRenderer.RenderingContext newRenderingContext =
                ZestInlayRenderer.show(editor, newOffset, continuationItem);
            renderingContexts.put(editor, newRenderingContext);

            LOG.debug("Created continuation completion with remaining text: " +
                remainingText.substring(0, Math.min(30, remainingText.length())));

        } catch (Exception e) {
            LOG.warn("Failed to create continuation completion", e);
            clearCompletion(editor);
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
        // Clear rendering context
        clearRenderingContext(editor);

        // Clear pending completion
        ZestCompletionData.PendingCompletion completion = activeCompletions.remove(editor);
        if (completion != null) {
            completion.dispose();
        }

        // Reset tab count
        tabAcceptCounts.remove(editor);
    }

    private void clearRenderingContext(@NotNull Editor editor) {
        ZestInlayRenderer.RenderingContext renderingContext = renderingContexts.remove(editor);
        if (renderingContext != null) {
            renderingContext.dispose();
        }
    }

    @Override
    public boolean hasActiveCompletion(@NotNull Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        return completion != null && completion.isActive();
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
        completionCache.clear();
        LOG.debug("Cleared completion cache");
    }

    @Override
    public String getCacheStats() {
        return String.format("Cache size: %d/%d", completionCache.size(), MAX_CACHE_SIZE);
    }

    // Helper methods

    public @Nullable Long recordLastTypingTime(@NotNull Editor editor) {
        return lastTypingTimes.put(editor, System.currentTimeMillis());
    }

    private boolean isUserStillTyping(@NotNull Editor editor) {
        Long lastTime = lastTypingTimes.get(editor);
        if (lastTime == null) return false;

        long timeSinceLastTyping = System.currentTimeMillis() - lastTime;
        return timeSinceLastTyping < 10; // Small buffer to detect rapid typing
    }

    // Legacy methods for backward compatibility

    public void triggerAutocomplete(@NotNull Editor editor) {
        triggerCompletion(editor, false);
    }

    public void triggerAutocomplete(@NotNull Editor editor, boolean forced) {
        triggerCompletion(editor, forced);
    }

    public void acceptCompletion(@NotNull Editor editor) {
        acceptCompletion(editor, AcceptType.FULL_COMPLETION);
    }

    @Override
    public void dispose() {
        // Cancel all pending requests
        currentRequests.values().forEach(request -> {
            if (!request.isDone()) {
                request.cancel(true);
            }
        });

        // Clear all state
        currentRequests.clear();
        lastTypingTimes.clear();
        activeCompletions.keySet().forEach(this::clearCompletion);
        activeCompletions.clear();
        renderingContexts.clear();
        documentListeners.clear();
        caretListeners.clear();
        completionCache.clear();
        tabAcceptCounts.clear();

        LOG.info("ZestAutocompleteService disposed for project: " + project.getName());
    }
}
