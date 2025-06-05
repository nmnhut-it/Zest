package com.zps.zest.langchain4j.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Utility class for handling IntelliJ threading requirements.
 * Ensures PSI operations are performed in read/write actions.
 */
public final class ThreadingUtils {
    
    private ThreadingUtils() {
        // Utility class
    }
    
    /**
     * Executes a task that requires read access to PSI.
     */
    public static <T> T computeInReadAction(Supplier<T> supplier) {
        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            // Already in read action
            return supplier.get();
        }
        return ReadAction.compute(supplier::get);
    }
    
    /**
     * Executes a task asynchronously with proper read action handling.
     */
    public static <T> CompletableFuture<T> computeAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(() -> 
            computeInReadAction(supplier), 
            executor
        );
    }
    
    /**
     * Runs a background task with progress indicator.
     */
    public static void runInBackground(Project project, String title, boolean canBeCancelled, 
                                     BackgroundTask task) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, canBeCancelled) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                task.run(indicator);
            }
        });
    }
    
    /**
     * Ensures we're in a read action, throws if not possible.
     */
    public static void assertReadAccess() {
        ApplicationManager.getApplication().assertReadAccessAllowed();
    }
    
    /**
     * Ensures we're NOT in a read action (for long operations).
     */
    public static void assertNotInReadAction() {
        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            throw new IllegalStateException("Long operation should not be performed in read action");
        }
    }
    
    /**
     * Interface for background tasks.
     */
    @FunctionalInterface
    public interface BackgroundTask {
        void run(ProgressIndicator indicator);
    }
    
    /**
     * Smart executor that handles IntelliJ threading.
     */
    public static class SmartExecutor implements Executor {
        private final Executor delegate;
        
        public SmartExecutor(Executor delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void execute(@NotNull Runnable command) {
            if (ApplicationManager.getApplication().isDispatchThread()) {
                // Don't block EDT, delegate to background
                delegate.execute(command);
            } else if (ApplicationManager.getApplication().isReadAccessAllowed()) {
                // In read action, execute directly
                command.run();
            } else {
                // Normal background execution
                delegate.execute(command);
            }
        }
    }
}
