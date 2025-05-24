package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.Disposer;
import com.zps.zest.autocomplete.ZestInlayRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Enhanced inlay lifecycle management to prevent memory leaks and orphaned inlays.
 * Provides proper tracking and cleanup of all Zest-related inlays.
 */
public class InlayLifecycleManager {
    private static final Logger LOG = Logger.getInstance(InlayLifecycleManager.class);
    
    // Thread-safe tracking of inlays per editor
    private static final Map<Editor, Set<Inlay<?>>> editorInlays = new ConcurrentHashMap<>();
    
    // Track inlay statistics for debugging
    private static volatile int totalInlaysCreated = 0;
    private static volatile int totalInlaysDisposed = 0;
    
    /**
     * Tracks an inlay for proper lifecycle management.
     * Automatically sets up disposal listeners to clean up tracking.
     */
    public static void trackInlay(@NotNull Editor editor, @NotNull Inlay<?> inlay) {
        if (inlay.isValid()) {
            LOG.warn("Attempting to track already disposed inlay");
            return;
        }
        
        // Add to tracking map
        Set<Inlay<?>> inlays = editorInlays.computeIfAbsent(editor, k -> ConcurrentHashMap.newKeySet());
        if (inlays.add(inlay)) {
            totalInlaysCreated++;
            LOG.debug("Tracking inlay for editor, total tracked: {}", inlays.size());
            
            // Set up automatic cleanup when inlay is disposed
            try {
                Disposer.register(inlay, () -> {
                    cleanupInlayTracking(editor, inlay);
                    totalInlaysDisposed++;
                });
            } catch (Exception e) {
                LOG.warn("Failed to register disposal listener for inlay", e);
                // Manual cleanup as fallback
                cleanupInlayTracking(editor, inlay);
            }
        }
    }
    
    /**
     * Safely disposes all tracked inlays for an editor.
     * Returns the number of inlays that were successfully disposed.
     */
    public static int disposeAllInlays(@NotNull Editor editor) {
        Set<Inlay<?>> inlays = editorInlays.remove(editor);
        if (inlays == null || inlays.isEmpty()) {
            LOG.debug("No tracked inlays found for editor");
            return 0;
        }
        
        int disposedCount = 0;
        
        // Create a snapshot to avoid concurrent modification
        Inlay<?>[] inlayArray = inlays.toArray(new Inlay[0]);
        
        for (Inlay<?> inlay : inlayArray) {
            try {
                if (inlay != null && inlay.isValid()) {
                    inlay.dispose();
                    disposedCount++;
                }
            } catch (Exception e) {
                LOG.warn("Error disposing tracked inlay", e);
            }
        }
        
        LOG.debug("Disposed {} inlays for editor", disposedCount);
        return disposedCount;
    }
    
    /**
     * Disposes specific Zest completion inlays only (not all inlays).
     * More targeted cleanup that won't interfere with other plugins.
     */
    public static int disposeZestInlays(@NotNull Editor editor) {
        Set<Inlay<?>> inlays = editorInlays.get(editor);
        if (inlays == null || inlays.isEmpty()) {
            return 0;
        }
        
        int disposedCount = 0;
        
        // Create a snapshot to avoid concurrent modification
        Inlay<?>[] inlayArray = inlays.toArray(new Inlay[0]);
        
        for (Inlay<?> inlay : inlayArray) {
            try {
                if (inlay != null && inlay.isValid() && isZestInlay(inlay)) {
                    inlay.dispose();
                    disposedCount++;
                }
            } catch (Exception e) {
                LOG.warn("Error disposing Zest inlay", e);
            }
        }
        
        LOG.debug("Disposed {} Zest-specific inlays for editor", disposedCount);
        return disposedCount;
    }
    
    /**
     * Checks if an inlay belongs to Zest autocomplete system.
     */
    private static boolean isZestInlay(@NotNull Inlay<?> inlay) {
        try {
            Object renderer = inlay.getRenderer();
            return renderer instanceof ZestInlayRenderer.InlineCompletionRenderer ||
                   renderer instanceof ZestInlayRenderer.BlockCompletionRenderer;
        } catch (Exception e) {
            LOG.warn("Error checking inlay renderer type", e);
            return false;
        }
    }
    
    /**
     * Cleans up tracking for a specific inlay.
     * Called automatically when inlay is disposed.
     */
    private static void cleanupInlayTracking(@NotNull Editor editor, @NotNull Inlay<?> inlay) {
        Set<Inlay<?>> inlays = editorInlays.get(editor);
        if (inlays != null) {
            inlays.remove(inlay);
            
            // Clean up empty editor entries
            if (inlays.isEmpty()) {
                editorInlays.remove(editor);
                LOG.debug("Cleaned up empty inlay tracking for editor");
            }
        }
    }
    
    /**
     * Gets the number of tracked inlays for an editor.
     * Useful for debugging and diagnostics.
     */
    public static int getTrackedInlayCount(@NotNull Editor editor) {
        Set<Inlay<?>> inlays = editorInlays.get(editor);
        return inlays != null ? inlays.size() : 0;
    }
    
    /**
     * Validates that all tracked inlays are still valid.
     * Removes invalid inlays from tracking and returns the count of invalid ones found.
     */
    public static int validateAndCleanupInlays(@NotNull Editor editor) {
        Set<Inlay<?>> inlays = editorInlays.get(editor);
        if (inlays == null || inlays.isEmpty()) {
            return 0;
        }
        
        int invalidCount = 0;
        
        // Create snapshot to avoid concurrent modification
        Inlay<?>[] inlayArray = inlays.toArray(new Inlay[0]);
        
        for (Inlay<?> inlay : inlayArray) {
            try {
                if (inlay == null || !inlay.isValid()) {
                    inlays.remove(inlay);
                    invalidCount++;
                }
            } catch (Exception e) {
                LOG.warn("Error validating inlay", e);
                inlays.remove(inlay);
                invalidCount++;
            }
        }
        
        if (invalidCount > 0) {
            LOG.debug("Cleaned up {} invalid inlays for editor", invalidCount);
        }
        
        return invalidCount;
    }
    
    /**
     * Emergency cleanup - disposes all tracked inlays across all editors.
     * Should only be used during plugin shutdown or emergency situations.
     */
    public static void emergencyCleanupAll() {
        LOG.info("Performing emergency cleanup of all tracked inlays");
        
        int totalDisposed = 0;
        for (Editor editor : editorInlays.keySet()) {
            try {
                int disposed = disposeAllInlays(editor);
                totalDisposed += disposed;
            } catch (Exception e) {
                LOG.warn("Error during emergency cleanup for editor", e);
            }
        }
        
        editorInlays.clear();
        LOG.info("Emergency cleanup complete - disposed {} inlays: "+ totalDisposed);
    }
    
    /**
     * Gets diagnostic information about inlay tracking.
     */
    @NotNull
    public static String getDiagnosticInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== Inlay Lifecycle Manager Diagnostic ===\n");
        info.append("Total inlays created: ").append(totalInlaysCreated).append("\n");
        info.append("Total inlays disposed: ").append(totalInlaysDisposed).append("\n");
        info.append("Currently tracking editors: ").append(editorInlays.size()).append("\n");
        
        int totalTracked = 0;
        for (Map.Entry<Editor, Set<Inlay<?>>> entry : editorInlays.entrySet()) {
            int count = entry.getValue().size();
            totalTracked += count;
            
            if (count > 0) {
                info.append("Editor ").append(entry.getKey().hashCode())
                    .append(": ").append(count).append(" inlays\n");
            }
        }
        
        info.append("Total currently tracked inlays: ").append(totalTracked).append("\n");
        
        // Check for potential memory leaks
        if (totalInlaysCreated - totalInlaysDisposed > totalTracked + 10) {
            info.append("⚠️  WARNING: Possible memory leak detected - ")
                .append(totalInlaysCreated - totalInlaysDisposed - totalTracked)
                .append(" inlays may not be properly tracked\n");
        }
        
        info.append("=== End Diagnostic ===");
        return info.toString();
    }
}
