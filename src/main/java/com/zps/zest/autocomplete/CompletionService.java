package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.context.CompletionContext;
import com.zps.zest.autocomplete.context.SemanticContextGatherer;
import com.zps.zest.autocomplete.prompts.EnhancedPromptBuilder;
import com.zps.zest.autocomplete.utils.SmartPrefixRemover;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Simplified completion service focused on the core completion logic.
 * Separated from the main ZestAutocompleteService to improve maintainability.
 */
public class CompletionService {
    private static final Logger LOG = Logger.getInstance(CompletionService.class);
    
    private final Project project;
    
    public CompletionService(Project project) {
        this.project = project;
    }
    
    /**
     * Requests a completion for the given editor asynchronously.
     */
    public CompletableFuture<CompletionResult> requestCompletion(@NotNull Editor editor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return generateCompletion(editor);
            } catch (Exception e) {
                LOG.warn("Failed to generate completion", e);
                return CompletionResult.failure(e.getMessage());
            }
        });
    }
    
    /**
     * Generates a completion synchronously.
     */
    private CompletionResult generateCompletion(@NotNull Editor editor) throws PipelineExecutionException {
        // Check if autocomplete is enabled
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        if (!config.isAutocompleteEnabled()) {
            return CompletionResult.failure("Autocomplete disabled");
        }
        
        // Gather semantic context
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        CompletionContext context = SemanticContextGatherer.gatherContext(editor, psiFile);
        
        // Build prompt
        String prompt = EnhancedPromptBuilder.buildPrompt(context);
        
        // Create code context for API call
        CodeContext codeContext = createCodeContext(editor, prompt, config);
        
        // Make API request
        AutocompleteApiStage apiStage = new AutocompleteApiStage(project, getSystemPrompt(context));
        apiStage.process(codeContext);
        
        String rawCompletion = codeContext.getApiResponse();
        if (rawCompletion == null || rawCompletion.trim().isEmpty()) {
            return CompletionResult.failure("Empty response from API");
        }
        
        // Process the completion
        String processedCompletion = processCompletion(rawCompletion, editor, context);
        if (processedCompletion.trim().isEmpty()) {
            return CompletionResult.failure("Completion became empty after processing");
        }
        
        // Create completion item
        ZestCompletionData.CompletionItem item = createCompletionItem(processedCompletion, editor);
        
        return CompletionResult.success(item, context);
    }
    
    /**
     * Processes the raw completion response.
     */
    private String processCompletion(String rawCompletion, Editor editor, CompletionContext context) {
        String trimmed = rawCompletion.trim();
        
        // Apply smart prefix removal
        int currentOffset = editor.getCaretModel().getOffset();
        String beforeCursor = context.localContext.beforeCursor;
        
        // Extract just the current line prefix for prefix removal
        String[] lines = beforeCursor.split("\n");
        String currentLinePrefix = lines.length > 0 ? lines[lines.length - 1] : "";
        
        return SmartPrefixRemover.removeRedundantPrefix(currentLinePrefix, trimmed);
    }
    
    /**
     * Creates a completion item from the processed text.
     */
    private ZestCompletionData.CompletionItem createCompletionItem(String completionText, Editor editor) {
        int currentOffset = editor.getCaretModel().getOffset();
        ZestCompletionData.Range replaceRange = new ZestCompletionData.Range(currentOffset, currentOffset);
        
        return new ZestCompletionData.CompletionItem(completionText, replaceRange, null, 1.0);
    }
    
    /**
     * Gets the appropriate system prompt based on context.
     */
    private String getSystemPrompt(CompletionContext context) {
        // Could be enhanced to return different prompts based on completion type
        return "Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.";
    }
    
    /**
     * Creates code context for API request.
     */
    private CodeContext createCodeContext(Editor editor, String prompt, ConfigurationManager config) {
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
        
        private CompletionResult(boolean success, ZestCompletionData.CompletionItem item, 
                                CompletionContext context, String errorMessage) {
            this.success = success;
            this.item = item;
            this.context = context;
            this.errorMessage = errorMessage;
        }
        
        public static CompletionResult success(ZestCompletionData.CompletionItem item, CompletionContext context) {
            return new CompletionResult(true, item, context, null);
        }
        
        public static CompletionResult failure(String errorMessage) {
            return new CompletionResult(false, null, null, errorMessage);
        }
        
        public boolean isSuccess() { return success; }
        public ZestCompletionData.CompletionItem getItem() { return item; }
        public CompletionContext getContext() { return context; }
        public String getErrorMessage() { return errorMessage; }
    }
}
