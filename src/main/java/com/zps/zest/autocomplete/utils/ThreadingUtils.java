package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Utility class to eliminate threading-related code duplication.
 * Provides common patterns for EDT operations and read actions.
 */
public class ThreadingUtils {
    private static final Logger LOG = Logger.getInstance(ThreadingUtils.class);

    /**
     * Executes a runnable on EDT, either immediately or via invokeLater.
     * Eliminates the repeated pattern of checking isDispatchThread().
     */
    public static void runOnEDT(@NotNull Runnable task) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            try {
                task.run();
            } catch (Exception e) {
                LOG.warn("Error executing task on EDT", e);
            }
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    LOG.warn("Error executing task on EDT via invokeLater", e);
                }
            });
        }
    }

    /**
     * Executes a runnable on EDT with error handling.
     * Returns a CompletableFuture that completes when the task is done.
     */
    public static CompletableFuture<Void> runOnEDTAsync(@NotNull Runnable task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Runnable wrappedTask = () -> {
            try {
                task.run();
                future.complete(null);
            } catch (Exception e) {
                LOG.warn("Error executing async task on EDT", e);
                future.completeExceptionally(e);
            }
        };
        
        if (ApplicationManager.getApplication().isDispatchThread()) {
            wrappedTask.run();
        } else {
            ApplicationManager.getApplication().invokeLater(wrappedTask);
        }
        
        return future;
    }

    /**
     * Safely computes a value in a read action.
     * Eliminates the repeated ReadAction.compute() pattern.
     */
    public static <T> T safeReadAction(@NotNull Supplier<T> computation) {
        try {
            return ReadAction.compute(computation::get);
        } catch (Exception e) {
            LOG.warn("Error in read action", e);
            throw e;
        }
    }

    /**
     * Safely computes a value in a read action with error handling.
     * Returns a default value if the computation fails.
     */
    public static <T> T safeReadAction(@NotNull Supplier<T> computation, T defaultValue) {
        try {
            return ReadAction.compute(computation::get);
        } catch (Exception e) {
            LOG.warn("Error in read action, returning default", e);
            return defaultValue;
        }
    }

    /**
     * Runs a read action safely without return value.
     */
    public static void safeReadAction(@NotNull Runnable computation) {
        try {
            ReadAction.run(computation::run);
        } catch (Exception e) {
            LOG.warn("Error in read action", e);
            throw e;
        }
    }

    /**
     * Asserts we're on the EDT and logs if not.
     * Useful for debugging threading issues.
     */
    public static void assertEDT(@NotNull String context) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("Expected to be on EDT for: " + context);
        }
        ApplicationManager.getApplication().assertIsDispatchThread();
    }

    /**
     * Checks if we're on the EDT without throwing.
     */
    public static boolean isOnEDT() {
        return ApplicationManager.getApplication().isDispatchThread();
    }
}
