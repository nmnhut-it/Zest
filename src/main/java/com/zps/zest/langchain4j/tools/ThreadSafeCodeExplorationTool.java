package com.zps.zest.langchain4j.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Thread-safe base class for code exploration tools that properly handles IntelliJ's threading model.
 */
public abstract class ThreadSafeCodeExplorationTool extends BaseCodeExplorationTool {
    
    protected ThreadSafeCodeExplorationTool(@NotNull Project project, String name, String description) {
        super(project, name, description);
    }
    
    @Override
    protected final ToolResult doExecute(JsonObject parameters) {
        // Ensure we execute in a read action if needed
        if (requiresReadAction()) {
            return ReadAction.compute(() -> doExecuteInReadAction(parameters));
        } else {
            return doExecuteInReadAction(parameters);
        }
    }
    
    /**
     * Override this to indicate if the tool requires a read action.
     * Default is true for safety.
     */
    protected boolean requiresReadAction() {
        return true;
    }
    
    /**
     * Execute the tool logic. This will be wrapped in a read action if needed.
     */
    protected abstract ToolResult doExecuteInReadAction(JsonObject parameters);
    
    /**
     * Execute a task in the EDT (Event Dispatch Thread) and wait for result.
     */
    protected <T> T runInEDTAndWait(Computable<T> task) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return task.compute();
        } else {
            final Object[] result = new Object[1];
            ApplicationManager.getApplication().invokeAndWait(() -> {
                result[0] = task.compute();
            });
            return (T) result[0];
        }
    }
    
    /**
     * Execute a task in a background thread.
     */
    protected <T> Future<T> runInBackground(Callable<T> task) {
        return AppExecutorUtil.getAppExecutorService().submit(task);
    }
    
    /**
     * Execute a write action safely.
     */
    protected void runWriteAction(Runnable task) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication().runWriteAction(task);
        });
    }
    
    /**
     * Execute a write action and wait for completion.
     */
    protected void runWriteActionAndWait(Runnable task) {
        if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
            task.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                ApplicationManager.getApplication().runWriteAction(task);
            });
        }
    }
}
