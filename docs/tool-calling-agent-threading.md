# Threading Guide for Tool-Calling Agent in IntelliJ

## Overview

IntelliJ IDEA has strict threading rules that must be followed to avoid UI freezes, deadlocks, and exceptions. This guide explains how the tool-calling agent implementation handles threading correctly.

## IntelliJ Threading Rules

### 1. Event Dispatch Thread (EDT)
- All UI updates must happen on the EDT
- Use `SwingUtilities.invokeLater()` or `ApplicationManager.getApplication().invokeLater()`
- Never perform long operations on EDT

### 2. Read Actions
- PSI (Program Structure Interface) access requires read action
- Use `ReadAction.compute()` or `ApplicationManager.getApplication().runReadAction()`
- Can be performed from any thread
- Multiple read actions can run concurrently

### 3. Write Actions
- Modifying PSI requires write action
- Use `WriteCommandAction.runWriteCommandAction()` or `ApplicationManager.getApplication().runWriteAction()`
- Only one write action at a time
- Blocks all read actions

### 4. Background Tasks
- Use `ProgressManager` for long-running operations
- Provides progress indication and cancellation
- Automatically handles threading

## Implementation Details

### ThreadSafeCodeExplorationTool Base Class

```java
public abstract class ThreadSafeCodeExplorationTool extends BaseCodeExplorationTool {
    
    @Override
    protected final ToolResult doExecute(JsonObject parameters) {
        // Automatically wrap in read action if needed
        if (requiresReadAction()) {
            return ReadAction.compute(() -> doExecuteInReadAction(parameters));
        } else {
            return doExecuteInReadAction(parameters);
        }
    }
    
    // Tools override this to indicate if they need read action
    protected boolean requiresReadAction() {
        return true; // Default to safe
    }
    
    // Tools implement this instead of doExecute
    protected abstract ToolResult doExecuteInReadAction(JsonObject parameters);
}
```

### ThreadSafeIndexTool for Index Access

```java
public abstract class ThreadSafeIndexTool extends ThreadSafeCodeExplorationTool {
    
    protected ToolResult executeWithIndices(IndexOperation operation) {
        // Check dumb mode
        if (DumbService.isDumb(project)) {
            return ToolResult.error("Indices updating...");
        }
        
        // Ensure read action for index access
        return ApplicationManager.getApplication().runReadAction(() -> {
            FileBasedIndex.getInstance().ensureUpToDate(...);
            return operation.execute();
        });
    }
}
```

### Tool Examples

#### PSI-Heavy Tool (FindMethodsTool)
```java
public class FindMethodsTool extends ThreadSafeCodeExplorationTool {
    @Override
    protected boolean requiresReadAction() {
        return true; // PSI access needs read action
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        // Safe to access PSI here
        PsiClass psiClass = findClass(className);
        PsiMethod[] methods = psiClass.getMethods();
        // ...
    }
}
```

#### Async Tool (SearchCodeTool)
```java
public class SearchCodeTool extends ThreadSafeCodeExplorationTool {
    @Override
    protected boolean requiresReadAction() {
        return false; // Async operation
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        // Execute search asynchronously
        CompletableFuture<List<Result>> future = searchUtility.searchRelatedCode(query);
        
        // Wait with timeout
        List<Result> results = future.get(30, TimeUnit.SECONDS);
        return ToolResult.success(formatResults(results));
    }
}
```

### ToolCallingAutonomousAgent Threading

#### Synchronous Method
```java
public ExplorationResult exploreWithTools(String query) {
    // Can be called from any thread
    // Tool execution handles its own threading
    // No UI updates here
}
```

#### Asynchronous Method with Progress
```java
public CompletableFuture<ExplorationResult> exploreWithToolsAsync(
        String query, ProgressCallback callback) {
    
    CompletableFuture<ExplorationResult> future = new CompletableFuture<>();
    
    // Use ProgressManager for proper background execution
    ProgressManager.getInstance().run(
        new Task.Backgroundable(project, "Exploring: " + query, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // This runs in background thread
                ExplorationResult result = exploreWithToolsWithProgress(
                    query, indicator, callback);
                future.complete(result);
            }
        });
    
    return future;
}
```

### UI Updates from Background

```java
// In ProgressCallback implementation
@Override
public void onToolExecution(ToolExecution execution) {
    // Ensure UI updates happen on EDT
    SwingUtilities.invokeLater(() -> {
        toolListModel.addElement(execution.getToolName());
        outputArea.append("Executed: " + execution.getToolName());
    });
}
```

## Best Practices

### 1. Tool Implementation
- Always extend `ThreadSafeCodeExplorationTool` or `ThreadSafeIndexTool`
- Override `requiresReadAction()` appropriately
- Never access PSI outside read action
- Handle exceptions gracefully

### 2. Long Operations
- Use `ProgressIndicator` for cancellation support
- Check `indicator.isCanceled()` frequently
- Update progress with `indicator.setFraction()`
- Set descriptive text with `indicator.setText()`

### 3. UI Integration
- Never block EDT with tool execution
- Use `invokeLater()` for all UI updates
- Provide visual feedback during execution
- Handle cancellation gracefully

### 4. Index Access
- Check `DumbService.isDumb()` before index operations
- Use `FileBasedIndex.getInstance().ensureUpToDate()`
- Handle index not ready scenarios
- Provide meaningful error messages

## Common Pitfalls to Avoid

1. **Accessing PSI without read action**
   ```java
   // WRONG
   PsiClass psiClass = findClass(name);
   
   // CORRECT
   PsiClass psiClass = ReadAction.compute(() -> findClass(name));
   ```

2. **UI updates from background thread**
   ```java
   // WRONG
   statusLabel.setText("Done");
   
   // CORRECT
   SwingUtilities.invokeLater(() -> statusLabel.setText("Done"));
   ```

3. **Long operations on EDT**
   ```java
   // WRONG
   button.addActionListener(e -> {
       // Long operation blocks UI
       agent.exploreWithTools(query);
   });
   
   // CORRECT
   button.addActionListener(e -> {
       agent.exploreWithToolsAsync(query, callback);
   });
   ```

4. **Not checking dumb mode**
   ```java
   // WRONG
   searchIndex.search(query);
   
   // CORRECT
   if (!DumbService.isDumb(project)) {
       searchIndex.search(query);
   }
   ```

## Testing Threading

1. Enable threading assertions:
   ```
   -Didea.is.internal=true
   -Didea.debug.mode=true
   ```

2. Test scenarios:
   - Run tools during indexing
   - Cancel operations mid-execution
   - Rapid tool executions
   - Large result sets

3. Monitor for:
   - UI freezes
   - `AWT-EventQueue` exceptions
   - Deadlocks
   - Progress indicator issues

## Summary

Proper threading in IntelliJ plugins requires:
- Understanding EDT vs background threads
- Proper use of read/write actions
- Progress indication for long operations
- Careful UI update handling

The tool-calling agent implementation follows these principles to ensure a responsive, stable user experience while performing complex code exploration operations.
