package com.zps.zest.autocompletion2.integration;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocompletion2.core.AutocompleteService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example integration showing how to connect Zest Autocomplete v2 
 * with real completion logic (LLM APIs, etc.).
 */
public class IntegrationExample {
    private static final Logger LOG = Logger.getInstance(IntegrationExample.class);
    
    /**
     * Example: Set up automatic completion triggering on typing.
     * Call this when an editor is opened to enable auto-completion.
     */
    public static void setupAutoCompletion(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return;
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        
        // Add document listener to trigger completions
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private CompletableFuture<Void> pendingCompletion;
            
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                // Cancel any pending completion
                if (pendingCompletion != null && !pendingCompletion.isDone()) {
                    pendingCompletion.cancel(true);
                }
                
                // Schedule new completion with delay
                pendingCompletion = CompletableFuture.runAsync(() -> {
                    try {
                        // Wait for user to stop typing
                        Thread.sleep(500);
                        
                        if (!Thread.currentThread().isInterrupted()) {
                            // Trigger completion on EDT
                            com.intellij.openapi.application.ApplicationManager
                                .getApplication().invokeLater(() -> {
                                    triggerSmartCompletion(editor, service);
                                });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        });
        
        LOG.info("Auto-completion enabled for editor");
    }
    
    /**
     * Example: Smart completion triggering based on context.
     */
    private static void triggerSmartCompletion(@NotNull Editor editor, @NotNull AutocompleteService service) {
        if (editor.isDisposed() || service.hasCompletion(editor)) {
            return; // Don't trigger if already has completion
        }
        
        try {
            // Get context around cursor
            String context = getCompletionContext(editor);
            
            // Check if we should trigger completion
            if (shouldTriggerCompletion(context)) {
                // Get completion from your API/LLM
                getCompletionAsync(context)
                    .thenAccept(completionText -> {
                        if (completionText != null && !completionText.isEmpty()) {
                            // Show completion on EDT
                            com.intellij.openapi.application.ApplicationManager
                                .getApplication().invokeLater(() -> {
                                    service.showCompletion(editor, completionText);
                                });
                        }
                    })
                    .exceptionally(throwable -> {
                        LOG.warn("Error getting completion", throwable);
                        return null;
                    });
            }
        } catch (Exception e) {
            LOG.warn("Error in smart completion triggering", e);
        }
    }
    
    /**
     * Example: Extract context around the cursor for completion.
     */
    @NotNull
    private static String getCompletionContext(@NotNull Editor editor) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        
        // Get some context before and after cursor
        int contextSize = 200;
        int start = Math.max(0, offset - contextSize);
        int end = Math.min(document.getTextLength(), offset + contextSize);
        
        String beforeCursor = document.getText(TextRange.from(start, offset - start));
        String afterCursor = document.getText(TextRange.from(offset, end - offset));
        
        return beforeCursor + "<CURSOR>" + afterCursor;
    }
    
    /**
     * Example: Determine if we should trigger completion based on context.
     */
    private static boolean shouldTriggerCompletion(@NotNull String context) {
        // Extract the part before cursor
        String beforeCursor = context.split("<CURSOR>")[0];
        String currentLine = beforeCursor.substring(beforeCursor.lastIndexOf('\n') + 1);
        
        // Trigger completion for common patterns
        return currentLine.matches(".*\\w+\\.\\w*$") ||           // method calls: obj.method
               currentLine.matches(".*\\w+\\s*=\\s*\\w*$") ||     // assignments: var = 
               currentLine.matches(".*\\bnew\\s+\\w*$") ||        // object creation: new Something
               currentLine.matches(".*\\b(if|for|while)\\s*\\($"); // control structures
    }
    
    /**
     * Example: Get completion from your API/LLM service.
     * Replace this with your actual implementation.
     */
    @NotNull
    private static CompletableFuture<String> getCompletionAsync(@NotNull String context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // TODO: Replace with your actual API call
                return callYourCompletionAPI(context);
            } catch (Exception e) {
                LOG.warn("Error calling completion API", e);
                return null;
            }
        }).orTimeout(5, TimeUnit.SECONDS); // 5 second timeout
    }
    
    /**
     * Placeholder for your actual completion API.
     * Replace this with calls to your LLM service.
     */
    private static String callYourCompletionAPI(@NotNull String context) {
        // Example implementation - replace with real API call
        LOG.debug("Getting completion for context: {}", context.substring(0, Math.min(50, context.length())));
        
        // Simulate API delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        
        // Return example completion based on context patterns
        if (context.contains("buffer.")) {
            return "writeInt(42);\n    buffer.flip();";
        } else if (context.contains("System.")) {
            return "out.println(\"Hello World!\");";
        } else if (context.contains("new ")) {
            return "ArrayList<>();";
        }
        
        return null; // No completion available
    }
    
    /**
     * Example: Manual completion trigger for testing.
     * You can bind this to a keyboard shortcut or menu action.
     */
    public static void manualTriggerCompletion(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return;
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        String context = getCompletionContext(editor);
        
        getCompletionAsync(context)
            .thenAccept(completionText -> {
                if (completionText != null && !completionText.isEmpty()) {
                    com.intellij.openapi.application.ApplicationManager
                        .getApplication().invokeLater(() -> {
                            service.showCompletion(editor, completionText);
                        });
                }
            });
    }
    
    /**
     * Example: Disable auto-completion for an editor.
     */
    public static void disableAutoCompletion(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return;
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        service.clearCompletion(editor);
        
        // Note: To fully disable, you'd also need to remove document listeners
        LOG.info("Auto-completion disabled for editor");
    }
}
