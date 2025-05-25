package com.zps.zest.autocompletion2.integration;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.autocompletion2.AutocompleteApiStage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple integration layer that connects v2 AutocompleteService with LLM API.
 * Handles context creation, API calls, and response processing.
 */
public class LLMCompletionProvider {
    private static final Logger LOG = Logger.getInstance(LLMCompletionProvider.class);
    private static final int TIMEOUT_SECONDS = 10;
    
    private final Project project;
    private final AutocompleteApiStage apiStage;
    
    public LLMCompletionProvider(@NotNull Project project) {
        this.project = project;
        this.apiStage = new AutocompleteApiStage(project);
    }
    
    /**
     * Gets completion from LLM API asynchronously.
     * Returns CompletableFuture that resolves to completion text or null.
     */
    @NotNull
    public CompletableFuture<String> getCompletionAsync(@NotNull Editor editor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getCompletion(editor);
            } catch (Exception e) {
                LOG.warn("Error getting LLM completion", e);
                return null;
            }
        }).orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .exceptionally(throwable -> {
            LOG.warn("LLM completion timed out or failed", throwable);
            return null;
        });
    }
    
    /**
     * Gets completion from LLM API synchronously.
     * Returns completion text or null if failed.
     */
    @Nullable
    public String getCompletion(@NotNull Editor editor) {
        try {
            // Create context from editor
            CodeContext context = new CodeContext(project, editor);
            
            // Build prompt using existing context
            String prompt = buildSimplePrompt(context);
            context.setPrompt(prompt);
            
            // Call API
            apiStage.process(context);
            
            // Return processed response
            String response = context.getApiResponse();
            if (response != null && !response.trim().isEmpty()) {
                LOG.debug("Got LLM completion: " + response.substring(0, Math.min(50, response.length())));
                return response;
            }
            
            return null;
            
        } catch (Exception e) {
            LOG.warn("Error in LLM completion", e);
            return null;
        }
    }
    
    /**
     * Builds a simple prompt from the code context.
     * Uses minimal template for fast completions.
     */
    @NotNull
    private String buildSimplePrompt(@NotNull CodeContext context) {
        StringBuilder prompt = new StringBuilder();
        
        // Simple instruction
        prompt.append("Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.\n\n");
        
        // Get context around cursor (500 chars before, 100 after)
        String beforeCursor = context.getBeforeCursor();
        String afterCursor = context.getAfterCursor();
        
        // Limit context size for faster processing
        if (beforeCursor.length() > 500) {
            beforeCursor = beforeCursor.substring(beforeCursor.length() - 500);
        }
        if (afterCursor.length() > 100) {
            afterCursor = afterCursor.substring(0, 100);
        }
        
        // Add code context
        prompt.append("```java\n");
        prompt.append(beforeCursor);
        prompt.append("<CURSOR>");
        if (!afterCursor.trim().isEmpty()) {
            prompt.append(afterCursor);
        }
        prompt.append("\n```\n\n");
        
        prompt.append("🚨 CRITICAL: Only return NEW text to insert at cursor. Never repeat existing text.");
        
        return prompt.toString();
    }
    
    /**
     * Checks if the provider is ready to make API calls.
     */
    public boolean isReady() {
        try {
            return project != null && !project.isDisposed() && apiStage != null;
        } catch (Exception e) {
            return false;
        }
    }
}
