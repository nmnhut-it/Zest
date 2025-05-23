package com.zps.zest.autocomplete.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.AutocompleteApiStage;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.context.CompletionContext;
import com.zps.zest.autocomplete.context.SemanticContextGatherer;
import com.zps.zest.autocomplete.prompts.EnhancedPromptBuilder;
import com.zps.zest.autocomplete.utils.SmartPrefixRemover;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Handles completion requests with enhanced context analysis.
 * Encapsulates the complex logic of context gathering, API calls, and response processing.
 */
public class CompletionRequestHandler {
    private static final Logger LOG = Logger.getInstance(CompletionRequestHandler.class);
    
    private final com.intellij.openapi.project.Project project;
    
    public CompletionRequestHandler(com.intellij.openapi.project.Project project) {
        this.project = project;
    }
    
    /**
     * Processes a completion request asynchronously.
     */
    public CompletableFuture<CompletionResult> processCompletionRequest(@NotNull Editor editor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ReadAction.compute(() -> processCompletionSync(editor));
            } catch (Exception e) {
                LOG.warn("Failed to process completion request", e);
                return CompletionResult.failure("Failed to process completion: " + e.getMessage());
            }
        });
    }
    
    /**
     * Alternative method name for compatibility.
     */
    public CompletableFuture<CompletionResult> requestCompletion(@NotNull Editor editor) {
        return processCompletionRequest(editor);
    }
    
    /**
     * Synchronous completion processing within read action.
     */
    private CompletionResult processCompletionSync(@NotNull Editor editor) {
        try {
            // Check if completion is enabled
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            if (!config.isAutocompleteEnabled()) {
                return CompletionResult.failure("Autocomplete is disabled");
            }
            
            // Gather semantic context
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            CompletionContext context = SemanticContextGatherer.gatherContext(editor, psiFile);
            
            if (context == null) {
                return CompletionResult.failure("Could not gather context");
            }
            
            // Build enhanced prompt
            String prompt = EnhancedPromptBuilder.buildPrompt(context);
            
            // Create API context
            CodeContext apiContext = createApiContext(editor, prompt, config);
            
            // Make API call - use the existing constructor
            AutocompleteApiStage apiStage = new AutocompleteApiStage(project);
            apiStage.process(apiContext);
            
            String completion = apiContext.getApiResponse();
            if (completion == null || completion.trim().isEmpty()) {
                return CompletionResult.failure("Empty response from API");
            }
            
            // Create completion item
            ZestCompletionData.CompletionItem item = createCompletionItem(completion.trim(), context, editor);
            
            if (item == null) {
                return CompletionResult.failure("Could not create completion item");
            }
            
            return CompletionResult.success(item, context);
            
        } catch (PipelineExecutionException e) {
            LOG.warn("API call failed", e);
            return CompletionResult.failure("API call failed: " + e.getMessage());
        } catch (Exception e) {
            LOG.warn("Unexpected error in completion processing", e);
            return CompletionResult.failure("Unexpected error: " + e.getMessage());
        }
    }
    
    /**
     * Creates a completion item from the API response.
     */
    private ZestCompletionData.CompletionItem createCompletionItem(
            @NotNull String completion, 
            @NotNull CompletionContext context, 
            @NotNull Editor editor) {
        
        try {
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Clean the completion using smart prefix removal
            String beforeCursor = context.cursorPosition.linePrefix;
            String cleanedCompletion = SmartPrefixRemover.removeRedundantPrefix(beforeCursor, completion);
            
            LOG.debug("Cleaned completion: '{}' (from: '{}')", cleanedCompletion, completion);
            
            // Validate cleaned completion
            if (cleanedCompletion.trim().isEmpty()) {
                LOG.debug("Completion is empty after cleaning");
                return null;
            }
            
            // Check for redundancy with line suffix
            String lineSuffix = context.cursorPosition.lineSuffix.trim();
            if (!lineSuffix.isEmpty() && cleanedCompletion.startsWith(lineSuffix)) {
                LOG.debug("Completion redundant with line suffix");
                return null;
            }
            
            // Create range (point insertion at current offset)
            ZestCompletionData.Range replaceRange = new ZestCompletionData.Range(currentOffset, currentOffset);
            
            // Create completion item
            ZestCompletionData.CompletionItem item = new ZestCompletionData.CompletionItem(
                cleanedCompletion, replaceRange, null, 1.0
            );
            
            LOG.debug("Created completion item - text: '{}', range: {}-{}", 
                     cleanedCompletion, currentOffset, currentOffset);
            
            return item;
            
        } catch (Exception e) {
            LOG.warn("Error creating completion item", e);
            
            // Fallback: create simple completion item
            try {
                int currentOffset = editor.getCaretModel().getOffset();
                ZestCompletionData.Range replaceRange = new ZestCompletionData.Range(currentOffset, currentOffset);
                return new ZestCompletionData.CompletionItem(completion, replaceRange, null, 1.0);
            } catch (Exception e2) {
                LOG.warn("Fallback creation also failed", e2);
                return null;
            }
        }
    }
    
    /**
     * Creates API context for the completion request.
     */
    private CodeContext createApiContext(@NotNull Editor editor, @NotNull String prompt, @NotNull ConfigurationManager config) {
        CodeContext context = new CodeContext();
        context.setProject(project);
        context.setEditor(editor);
        context.setConfig(config);
        context.setPrompt(prompt);
        context.useTestWrightModel(false);
        return context;
    }
    
    /**
     * Result of a completion request.
     */
    public static class CompletionResult {
        private final boolean success;
        private final ZestCompletionData.CompletionItem item;
        private final CompletionContext context;
        private final String errorMessage;
        
        private CompletionResult(boolean success, 
                                ZestCompletionData.CompletionItem item, 
                                CompletionContext context, 
                                String errorMessage) {
            this.success = success;
            this.item = item;
            this.context = context;
            this.errorMessage = errorMessage;
        }
        
        public static CompletionResult success(@NotNull ZestCompletionData.CompletionItem item, 
                                             @NotNull CompletionContext context) {
            return new CompletionResult(true, item, context, null);
        }
        
        public static CompletionResult failure(@NotNull String errorMessage) {
            return new CompletionResult(false, null, null, errorMessage);
        }
        
        public boolean isSuccess() { return success; }
        public ZestCompletionData.CompletionItem getItem() { return item; }
        public CompletionContext getContext() { return context; }
        public String getErrorMessage() { return errorMessage; }
    }
}
