package com.zps.zest.autocomplete;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized, thread-safe completion state manager.
 * Handles the spaghetti code of clearing completions and removing inlays properly
 * according to IntelliJ's threading model.
 */
public class ZestCompletionStateManager {
    private static final Logger LOG = Logger.getInstance(ZestCompletionStateManager.class);
    
    // Thread-safe state storage
    private final Map<Editor, CompletionState> completionStates = new ConcurrentHashMap<>();
    private final AtomicBoolean isDisposed = new AtomicBoolean(false);
    
    /**
     * Represents the complete state of a completion for an editor.
     * Thread-safe and handles all aspects of completion lifecycle.
     */
    private static class CompletionState {
        private final Editor editor;
        private volatile ZestCompletionData.PendingCompletion pendingCompletion;
        private volatile ZestInlayRenderer.RenderingContext renderingContext;
        private volatile int tabAcceptCount = 0;
        private final AtomicBoolean isClearing = new AtomicBoolean(false);
        private final Object lock = new Object();
        
        CompletionState(@NotNull Editor editor) {
            this.editor = editor;
        }
        
        void setPendingCompletion(@Nullable ZestCompletionData.PendingCompletion completion) {
            synchronized (lock) {
                this.pendingCompletion = completion;
            }
        }
        
        void setRenderingContext(@Nullable ZestInlayRenderer.RenderingContext context) {
            synchronized (lock) {
                this.renderingContext = context;
            }
        }
        
        void setTabAcceptCount(int count) {
            this.tabAcceptCount = count;
        }
        
        @Nullable
        ZestCompletionData.PendingCompletion getPendingCompletion() {
            return pendingCompletion;
        }
        
        @Nullable
        ZestInlayRenderer.RenderingContext getRenderingContext() {
            return renderingContext;
        }
        
        int getTabAcceptCount() {
            return tabAcceptCount;
        }
        
        boolean isActive() {
            synchronized (lock) {
                return pendingCompletion != null && pendingCompletion.isActive() &&
                       renderingContext != null && !renderingContext.getInlays().isEmpty();
            }
        }
        
        boolean isConsistent() {
            synchronized (lock) {
                boolean hasPending = pendingCompletion != null && pendingCompletion.isActive();
                boolean hasRendering = renderingContext != null && !renderingContext.getInlays().isEmpty();
                return hasPending == hasRendering;
            }
        }
        
        /**
         * Thread-safe cleanup that ensures everything is disposed properly.
         * Returns true if cleanup was performed, false if already clearing.
         */
        boolean clear() {
            if (!isClearing.compareAndSet(false, true)) {
                LOG.debug("Clear already in progress for editor");
                return false;
            }
            
            try {
                synchronized (lock) {
                    LOG.debug("Clearing completion state for editor");
                    
                    // Clear pending completion (state only)
                    if (pendingCompletion != null) {
                        try {
                            pendingCompletion.dispose();
                        } catch (Exception e) {
                            LOG.warn("Error disposing pending completion", e);
                        }
                        pendingCompletion = null;
                    }
                    
                    // Clear rendering context (UI elements - must be on EDT)
                    if (renderingContext != null) {
                        final ZestInlayRenderer.RenderingContext contextToDispose = renderingContext;
                        renderingContext = null;
                        
                        if (ApplicationManager.getApplication().isDispatchThread()) {
                            // Already on EDT, dispose directly
                            disposeRenderingContext(contextToDispose);
                        } else {
                            // Not on EDT, invoke later
                            ApplicationManager.getApplication().invokeLater(() -> {
                                disposeRenderingContext(contextToDispose);
                            });
                        }
                    }
                    
                    // Reset tab count
                    tabAcceptCount = 0;
                }
                
                return true;
                
            } finally {
                isClearing.set(false);
            }
        }
        
        private void disposeRenderingContext(@NotNull ZestInlayRenderer.RenderingContext context) {
            ApplicationManager.getApplication().assertIsDispatchThread();
            
            try {
                int disposedCount = context.dispose();
                if (disposedCount > 0) {
                    LOG.debug("Disposed {} rendering elements", disposedCount);
                }
            } catch (Exception e) {
                LOG.warn("Error disposing rendering context", e);
                // Fallback cleanup using ZestAutocompleteFix
                try {
                    ZestAutocompleteFix.cleanupInlays(editor, null);
                } catch (Exception e2) {
                    LOG.warn("Error in fallback cleanup", e2);
                }
            }
        }
    }
    
    /**
     * Sets completion state for an editor.
     * Thread-safe and ensures consistency between pending completion and rendering context.
     */
    public void setCompletion(@NotNull Editor editor,
                             @NotNull ZestCompletionData.PendingCompletion pendingCompletion,
                             @NotNull ZestInlayRenderer.RenderingContext renderingContext) {
        if (isDisposed.get()) {
            LOG.warn("Attempting to set completion on disposed state manager");
            return;
        }
        
        CompletionState state = completionStates.computeIfAbsent(editor, CompletionState::new);
        state.setPendingCompletion(pendingCompletion);
        state.setRenderingContext(renderingContext);
        state.setTabAcceptCount(0);
        
        LOG.debug("Set completion state for editor");
    }
    
    /**
     * Clears completion for an editor.
     * Thread-safe and handles IntelliJ threading model properly.
     */
    public void clearCompletion(@NotNull Editor editor) {
        if (isDisposed.get()) {
            LOG.debug("State manager is disposed, skipping clear");
            return;
        }
        
        CompletionState state = completionStates.get(editor);
        if (state != null) {
            boolean cleared = state.clear();
            if (cleared) {
                completionStates.remove(editor);
                LOG.debug("Cleared and removed completion state for editor");
            }
        } else {
            LOG.debug("No completion state found for editor");
        }
    }
    
    /**
     * Checks if editor has active completion.
     * Thread-safe with consistency checking.
     */
    public boolean hasActiveCompletion(@NotNull Editor editor) {
        if (isDisposed.get()) {
            return false;
        }
        
        CompletionState state = completionStates.get(editor);
        if (state == null) {
            return false;
        }
        
        if (!state.isConsistent()) {
            LOG.debug("Inconsistent completion state detected, clearing");
            clearCompletion(editor);
            return false;
        }
        
        return state.isActive();
    }
    
    /**
     * Gets active completion for an editor.
     * Thread-safe.
     */
    @Nullable
    public ZestCompletionData.PendingCompletion getActiveCompletion(@NotNull Editor editor) {
        if (isDisposed.get()) {
            return null;
        }
        
        CompletionState state = completionStates.get(editor);
        return state != null ? state.getPendingCompletion() : null;
    }
    
    /**
     * Gets rendering context for an editor.
     * Thread-safe.
     */
    @Nullable
    public ZestInlayRenderer.RenderingContext getRenderingContext(@NotNull Editor editor) {
        if (isDisposed.get()) {
            return null;
        }
        
        CompletionState state = completionStates.get(editor);
        return state != null ? state.getRenderingContext() : null;
    }
    
    /**
     * Gets tab accept count for an editor.
     * Thread-safe.
     */
    public int getTabAcceptCount(@NotNull Editor editor) {
        if (isDisposed.get()) {
            return 0;
        }
        
        CompletionState state = completionStates.get(editor);
        return state != null ? state.getTabAcceptCount() : 0;
    }
    
    /**
     * Sets tab accept count for an editor.
     * Thread-safe.
     */
    public void setTabAcceptCount(@NotNull Editor editor, int count) {
        if (isDisposed.get()) {
            return;
        }
        
        CompletionState state = completionStates.get(editor);
        if (state != null) {
            state.setTabAcceptCount(count);
        }
    }
    
    /**
     * Updates completion state after partial acceptance.
     * Thread-safe and handles the complex continuation logic.
     */
    public boolean updateCompletionForContinuation(@NotNull Editor editor,
                                                  @NotNull ZestCompletionData.PendingCompletion newCompletion,
                                                  @NotNull ZestInlayRenderer.RenderingContext newRenderingContext) {
        if (isDisposed.get()) {
            return false;
        }
        
        CompletionState state = completionStates.get(editor);
        if (state == null) {
            LOG.warn("No existing state found for continuation update");
            return false;
        }
        
        // Clear existing rendering context on EDT
        ZestInlayRenderer.RenderingContext oldContext = state.getRenderingContext();
        if (oldContext != null) {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                try {
                    oldContext.dispose();
                } catch (Exception e) {
                    LOG.warn("Error disposing old context during continuation", e);
                }
            } else {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        oldContext.dispose();
                    } catch (Exception e) {
                        LOG.warn("Error disposing old context during continuation", e);
                    }
                });
            }
        }
        
        // Update with new completion and rendering
        state.setPendingCompletion(newCompletion);
        state.setRenderingContext(newRenderingContext);
        
        // CRITICAL FIX: Reset tab count for continuation
        state.setTabAcceptCount(0);
        
        LOG.debug("Updated completion state for continuation (tab count reset to 0)");
        return true;
    }
    
    /**
     * Force cleanup of all completions.
     * Thread-safe disposal of all state.
     */
    public void disposeAll() {
        if (!isDisposed.compareAndSet(false, true)) {
            LOG.debug("State manager already disposed");
            return;
        }
        
        LOG.info("Disposing all completion states");
        
        // Clear all completions
        for (Editor editor : completionStates.keySet()) {
            try {
                clearCompletion(editor);
            } catch (Exception e) {
                LOG.warn("Error clearing completion during disposal", e);
            }
        }
        
        completionStates.clear();
        LOG.info("Completion state manager disposed");
    }
    
    /**
     * Diagnostic method to check state consistency.
     */
    public String getDiagnosticInfo() {
        if (isDisposed.get()) {
            return "State manager is disposed";
        }
        
        StringBuilder info = new StringBuilder();
        info.append("=== Completion State Manager Diagnostic ===\n");
        info.append("Total editors with state: ").append(completionStates.size()).append("\n");
        
        for (Map.Entry<Editor, CompletionState> entry : completionStates.entrySet()) {
            Editor editor = entry.getKey();
            CompletionState state = entry.getValue();
            
            info.append("Editor: ").append(editor.hashCode()).append("\n");
            info.append("  - Has pending completion: ").append(state.getPendingCompletion() != null).append("\n");
            info.append("  - Has rendering context: ").append(state.getRenderingContext() != null).append("\n");
            info.append("  - Is active: ").append(state.isActive()).append("\n");
            info.append("  - Is consistent: ").append(state.isConsistent()).append("\n");
            info.append("  - Tab accept count: ").append(state.getTabAcceptCount()).append("\n");
        }
        
        info.append("=== End Diagnostic ===");
        return info.toString();
    }
}
