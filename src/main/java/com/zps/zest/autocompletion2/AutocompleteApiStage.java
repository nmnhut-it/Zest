package com.zps.zest.autocompletion2;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;

/**
 * Enhanced AutocompleteApiStage following Tabby ML best practices.
 * Optimized for fast, accurate code completions with special handling for:
 * - Standard code completion
 * - JavaDoc/JSDoc generation
 * - Line comment completion
 */
public class AutocompleteApiStage extends OpenWebUiApiCallStage {
    private static final Logger LOG = Logger.getInstance(AutocompleteApiStage.class);
    private static final String MINIMAL_SYSTEM_PROMPT = "Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.";

    public AutocompleteApiStage(Project project) {
        // Initialize with default minimal system prompt
        super(new Builder()
            .streaming(false)
            .model(ConfigurationManager.getInstance(project).getAutocompleteModel())
            .systemPrompt(AutocompletePromptBuilder.MINIMAL_SYSTEM_PROMPT));
    }

    public AutocompleteApiStage(Project project, String systemPrompt) {
        // Initialize with provided system prompt
        super(new Builder()
            .streaming(false)
            .model(ConfigurationManager.getInstance(project).getAutocompleteModel())
            .systemPrompt(systemPrompt != null ? systemPrompt : MINIMAL_SYSTEM_PROMPT));
    }


    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.debug("Processing enhanced autocomplete request");

        try {
            // Optimize configuration for autocomplete
            optimizeConfigurationForAutocomplete(context);

            // Call parent with optimized context
            super.process(context);

            // Clean up the response
            postProcessAutocompleteResponse(context);

        } catch (Exception e) {
            LOG.warn("Error in autocomplete processing", e);
            throw new PipelineExecutionException("Autocomplete processing failed", e);
        }
    }

    /**
     * Optimizes configuration specifically for autocomplete requests.
     */
    private void optimizeConfigurationForAutocomplete(CodeContext context) {
        LOG.debug("Autocomplete configuration optimized");
    }

    /**
     * Enhanced post-processing following Tabby ML patterns.
     */
    private void postProcessAutocompleteResponse(CodeContext context) {
        String response = context.getApiResponse();
        if (response == null || response.trim().isEmpty()) {
            return;
        }

        // Apply aggressive cleaning following Tabby ML approach
        String cleanedResponse = aggressiveCleanResponse(response);

        // Apply redundant prefix removal to the cleaned response
        // This ensures the completion won't duplicate existing text
        String finalResponse = ReadAction.compute(()->applyRedundantPrefixRemoval(cleanedResponse, context));

        // Validate the response
        if (isValidCompletion(finalResponse)) {
            context.setApiResponse(finalResponse);
            LOG.debug("Final processed autocomplete response: " +
                    finalResponse.substring(0, Math.min(50, finalResponse.length())));
        } else {
            LOG.warn("Invalid completion response after processing, clearing");
            context.setApiResponse("");
        }
    }

    /**
     * Applies redundant prefix removal to the API response.
     * This prevents rendering and acceptance issues by cleaning the response upfront.
     */
    private String applyRedundantPrefixRemoval(String response, CodeContext context) {
        if (response == null || response.isEmpty() || context.getEditor() == null) {
            return response;
        }

        try {
            // Get current editor context
            Editor editor = context.getEditor();
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Get current line prefix
            Document document = editor.getDocument();
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String currentLinePrefix = document.getText().substring(lineStart, currentOffset).trim();

            // Apply the same logic we use in ZestAutocompleteService
            String optimizedResponse = removeRedundantPrefixFromResponse(currentLinePrefix, response.trim());
            
            LOG.debug("Applied redundant prefix removal to response: '{}' -> '{}'", 
                     response.substring(0, Math.min(30, response.length())),
                     optimizedResponse.substring(0, Math.min(30, optimizedResponse.length())));
            
            return optimizedResponse;
            
        } catch (Exception e) {
            LOG.warn("Error applying redundant prefix removal to response", e);
            return response; // Return original if processing fails
        }
    }

    /**
     * Removes redundant prefix from API response (improved logic to avoid partial word matches).
     */
    private String removeRedundantPrefixFromResponse(String currentLinePrefix, String response) {
        if (currentLinePrefix == null || response == null || response.isEmpty()) {
            return response;
        }

        String currentPrefix = currentLinePrefix.trim();
        if (currentPrefix.isEmpty()) {
            return response;
        }

        LOG.debug("Checking redundant prefix - current: '{}', response: '{}'", currentPrefix, response);

        // Special handling for comments - be very conservative
        if (currentPrefix.startsWith("//") && response.startsWith("//")) {
            return handleCommentRedundancy(currentPrefix, response);
        }

        // Find the longest common suffix of currentPrefix and prefix of response
        int commonLength = 0;
        int maxCheck = Math.min(currentPrefix.length(), response.length());
        
        // Check for exact character match from the end of currentPrefix and start of response
        for (int i = 1; i <= maxCheck; i++) {
            String prefixSuffix = currentPrefix.substring(currentPrefix.length() - i);
            String responsePrefix = response.substring(0, i);
            
            if (prefixSuffix.equals(responsePrefix)) {
                commonLength = i;
            } else {
                break; // Stop at first mismatch for exact matching
            }
        }

        // If we found common characters, validate it's a meaningful match
        if (commonLength > 0) {
            String matchedText = response.substring(0, commonLength);
            
            // Only remove if the match is substantial (not just partial words)
            if (commonLength >= 5 && isValidPrefixToRemove(currentPrefix, matchedText, response)) {
                String result = response.substring(commonLength);
                LOG.debug("Removed redundant prefix from response: '{}', result: '{}'", matchedText, result);
                return result;
            }
        }

        // Check for complete word redundancy (safer approach)
        return handleWordLevelRedundancy(currentPrefix, response);
    }

    /**
     * Special handling for comment redundancy - very conservative approach.
     */
    private String handleCommentRedundancy(String currentPrefix, String response) {
        // For comments, only remove if there's exact word duplication
        // Example: "// call" + "// call the method" -> " the method"
        
        if (currentPrefix.equals(response.substring(0, Math.min(currentPrefix.length(), response.length())))) {
            String result = response.substring(currentPrefix.length());
            LOG.debug("Removed exact comment prefix: '{}', result: '{}'", currentPrefix, result);
            return result;
        }
        
        // Don't do partial comment removal - too risky
        return response;
    }

    /**
     * Handle word-level redundancy safely.
     */
    private String handleWordLevelRedundancy(String currentPrefix, String response) {
        String[] currentWords = currentPrefix.split("\\s+");
        String[] responseWords = response.split("\\s+");
        
        if (currentWords.length > 0 && responseWords.length > 0) {
            String lastCurrentWord = currentWords[currentWords.length - 1].toLowerCase();
            String firstResponseWord = responseWords[0].toLowerCase();
            
            // Only remove if it's an exact word match and substantial
            if (lastCurrentWord.equals(firstResponseWord) && lastCurrentWord.length() > 3) {
                // Find the exact word boundary in the response
                int wordEndIndex = response.toLowerCase().indexOf(firstResponseWord) + firstResponseWord.length();
                if (wordEndIndex < response.length()) {
                    // Check if next character is a word boundary
                    char nextChar = response.charAt(wordEndIndex);
                    if (!Character.isLetterOrDigit(nextChar)) {
                        String result = response.substring(wordEndIndex);
                        LOG.debug("Removed redundant complete word from response: '{}'", firstResponseWord);
                        return result;
                    }
                }
            }
        }
        
        return response;
    }

    /**
     * Validates if a prefix match is meaningful enough to remove.
     */
    private boolean isValidPrefixToRemove(String currentPrefix, String matchedText, String fullResponse) {
        // Don't remove if it would leave an empty or very short result
        String remainder = fullResponse.substring(matchedText.length());
        if (remainder.trim().length() < 3) {
            return false;
        }
        
        // Don't remove partial words - check if match ends at word boundary  
        if (matchedText.length() < fullResponse.length()) {
            char nextChar = fullResponse.charAt(matchedText.length());
            
            // If next character is alphanumeric, we might be breaking a word
            if (Character.isLetterOrDigit(nextChar)) {
                LOG.debug("Skipping prefix removal - would break word boundary: '{}'", matchedText);
                return false;
            }
        }
        
        // Don't remove if it creates malformed comments or statements
        if (currentPrefix.contains("//") || currentPrefix.contains("/*")) {
            // Be very conservative with comments
            if (matchedText.length() < currentPrefix.length()) {
                LOG.debug("Skipping prefix removal - would create malformed comment");
                return false;
            }
        }
        
        // Don't remove very short matches that are likely coincidental
        if (matchedText.trim().length() < 5) {
            LOG.debug("Skipping prefix removal - match too short: '{}'", matchedText);
            return false;
        }
        
        return true;
    }

    /**
     * Aggressive response cleaning following Tabby ML patterns.
     */
    private String aggressiveCleanResponse(String response) {
        if (response == null) return "";

        String cleaned = response;

        // Remove markdown code blocks completely
        cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*", "");
        cleaned = cleaned.replaceAll("```", "");

        // Remove backticks wrapping the entire response
        cleaned = cleaned.trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        
        // Remove multiple backticks at start/end
        while (cleaned.startsWith("`")) {
            cleaned = cleaned.substring(1).trim();
        }
        while (cleaned.endsWith("`")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        // Remove common LLM prefixes and explanations
        cleaned = cleaned.replaceAll("^(Here's|This|The|You can|To complete|Completion:).*?\\n", "");
        cleaned = cleaned.replaceAll("^(Complete|Add|Insert|Replace).*?:\\s*", "");

        // Remove any lines that are clearly explanatory
        String[] lines = cleaned.split("\n");
        StringBuilder result = new StringBuilder();
        boolean foundCode = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines at the beginning
            if (trimmed.isEmpty() && !foundCode) {
                continue;
            }

            // Skip explanatory lines
            if (isExplanatoryLine(trimmed)) {
                continue;
            }

            // If we find code-like content, start including everything
            if (looksLikeCode(trimmed)) {
                foundCode = true;
            }

            if (foundCode || !isExplanatoryLine(trimmed)) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(line);
            }
        }

        cleaned = result.toString().trim();

        // Remove trailing explanatory text
        while (cleaned.endsWith("...") || cleaned.endsWith("..")) {
            cleaned = cleaned.substring(0, cleaned.length() - (cleaned.endsWith("...") ? 3 : 2)).trim();
        }

        // Length limiting with smart cutoff
        if (cleaned.length() > 1000) {
            cleaned = smartTruncate(cleaned, 1000);
        }

        return cleaned;
    }

    private boolean isExplanatoryLine(String line) {
        String lower = line.toLowerCase();

        // ❌ BLOCK: Comments and javadocs
        String trimmed = line.trim();
        if (trimmed.startsWith("//") ||
                trimmed.startsWith("/*") ||
                trimmed.startsWith("/**") ||
                trimmed.startsWith("*") ||
                trimmed.startsWith("*/")) {
            return true;
        }

        return lower.startsWith("this ") ||
                lower.startsWith("the ") ||
                lower.startsWith("here ") ||
                lower.startsWith("note:") ||
                lower.startsWith("explanation:") ||
                lower.contains("will complete") ||
                lower.contains("adds the") ||
                (line.length() > 60 && !looksLikeCode(line));
    }
    private boolean looksLikeCode(String line) {
        String trimmed = line.trim();

        // ❌ BLOCK: Explicitly exclude comments and javadocs
        if (trimmed.startsWith("//") ||
                trimmed.startsWith("/*") ||
                trimmed.startsWith("/**") ||
                trimmed.startsWith("*") ||
                trimmed.startsWith("*/")) {
            return false;
        }

        // ✅ ALLOW: Code patterns only
        return trimmed.contains("(") && trimmed.contains(")") ||
                trimmed.contains("{") || trimmed.contains("}") ||
                trimmed.contains(";") ||
                trimmed.matches(".*\\b(public|private|protected|static|final|class|interface|if|else|for|while|return|new|import|package)\\b.*") ||
                trimmed.matches(".*[a-zA-Z_][a-zA-Z0-9_]*\\s*[=\\(].*") ||
                trimmed.matches("\\s*[}\\);]\\s*");
    }

    /**
     * Smart truncation that tries to preserve code structure.
     */
    private String smartTruncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }

        // Try to find a good breakpoint
        for (int i = maxLength; i > maxLength - 200 && i > 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == ';' || c == '}' || c == ')') {
                return text.substring(0, i + 1);
            }
        }

        // Find last complete line
        int lastNewline = text.lastIndexOf('\n', maxLength);
        if (lastNewline > maxLength / 2) {
            return text.substring(0, lastNewline);
        }

        return text.substring(0, maxLength);
    }

    /**
     * Validates that the completion looks reasonable.
     */
    private boolean isValidCompletion(String completion) {
        if (completion == null || completion.trim().isEmpty()) {
            return false;
        }

        // Check for obvious issues
        String trimmed = completion.trim();

        // Reject if it's just explanatory text
        if (isExplanatoryLine(trimmed) && !looksLikeCode(trimmed)) {
            return false;
        }

        // Reject if it's too short to be useful (unless it's a simple completion)
        if (trimmed.length() < 2 && !trimmed.matches("[;})]")) {
            return false;
        }

        // Reject if it contains obvious LLM artifacts
        if (trimmed.contains("I can help") ||
                trimmed.contains("Here's how") ||
                trimmed.contains("Let me ") ||
                trimmed.contains("You should")) {
            return false;
        }

        return true;
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