package com.zps.zest.autocomplete;

import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.LlmApiCallStage;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;
import com.zps.zest.autocomplete.utils.ContextGatherer;

/**
 * Specialized API stage for handling autocomplete requests.
 * Extends the existing LlmApiCallStage with autocomplete-specific optimizations.
 */
public class AutocompleteApiStage extends LlmApiCallStage {
    private static final int AUTOCOMPLETE_TIMEOUT_MS = 5000;
    private static final String AUTOCOMPLETE_SYSTEM_PROMPT = 
        "You are an AI code completion assistant. Complete the code naturally and concisely.\n" +
        "Rules:\n" +
        "1. Only provide the completion text, no explanations or markdown\n" +
        "2. Complete the current line or logical block of code\n" +
        "3. Maintain consistent indentation and style\n" +
        "4. Don't repeat existing code\n" +
        "5. Keep completions focused and relevant to the context\n" +
        "6. For single-line completions, complete to the end of the statement\n" +
        "7. For multi-line completions, complete the logical block (method, if statement, etc.)";
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        // Enhance the context with autocomplete-specific prompt
        enhanceContextForAutocomplete(context);
        
        // Call the parent implementation with enhanced context
        super.process(context);
        
        // Post-process the response for autocomplete
        postProcessAutocompleteResponse(context);
    }
    
    /**
     * Enhances the context with autocomplete-specific information.
     */
    private void enhanceContextForAutocomplete(CodeContext context) {
        // Build specialized prompt for autocomplete
        String autocompletePrompt = new AutocompletePromptBuilder()
            .withSystemPrompt(AUTOCOMPLETE_SYSTEM_PROMPT)
            .withFileContext(context.getClassContext())
            .withCursorPosition(getCurrentCursorContext(context))
            .withLanguage(getLanguageFromFile(context))
            .build();
        
        context.setPrompt(autocompletePrompt);
        
        // Set current stage type for logging/debugging
        context.setCurrentStageType("AUTOCOMPLETE_API");
    }
    
    /**
     * Gets the context around the current cursor position.
     */
    private String getCurrentCursorContext(CodeContext context) {
        // If we already have a prompt set, use it
        if (context.getPrompt() != null && !context.getPrompt().isEmpty()) {
            return context.getPrompt();
        }
        
        if (context.getEditor() != null) {
            return ContextGatherer.gatherCursorContext(
                context.getEditor(), 
                context.getSelectedText()
            );
        }
        
        return "";
    }
    
    /**
     * Determines the programming language from the file context.
     */
    private String getLanguageFromFile(CodeContext context) {
        if (context.getPsiFile() != null) {
            return context.getPsiFile().getLanguage().getDisplayName();
        }
        return "Java"; // Default to Java for this plugin
    }
    
    /**
     * Post-processes the API response for autocomplete-specific formatting.
     */
    private void postProcessAutocompleteResponse(CodeContext context) {
        String response = context.getApiResponse();
        if (response == null || response.trim().isEmpty()) {
            return;
        }
        
        // Clean up the response
        String cleanedResponse = cleanAutocompleteResponse(response);
        context.setApiResponse(cleanedResponse);
    }
    
    /**
     * Cleans up the autocomplete response by removing unwanted formatting.
     */
    private String cleanAutocompleteResponse(String response) {
        // Remove markdown code blocks
        response = response.replaceAll("```[a-zA-Z]*\\n?", "");
        response = response.replaceAll("```", "");
        
        // Remove common prefixes that might be added by the LLM
        response = response.replaceAll("^(Here's the completion:|Completion:|Code:)\\s*", "");
        
        // Trim whitespace but preserve internal formatting
        response = response.trim();
        
        // Limit response length for performance
        if (response.length() > 2000) {
            // Find a reasonable cutoff point (end of line or statement)
            int cutoff = findReasonableCutoff(response, 2000);
            response = response.substring(0, cutoff);
        }
        
        return response;
    }
    
    /**
     * Finds a reasonable cutoff point in the response to avoid cutting mid-statement.
     */
    private int findReasonableCutoff(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text.length();
        }
        
        // Try to find end of line within reasonable range
        for (int i = maxLength; i > maxLength - 200 && i > 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == ';' || c == '}') {
                return i + 1;
            }
        }
        
        // Fall back to max length
        return maxLength;
    }

}
