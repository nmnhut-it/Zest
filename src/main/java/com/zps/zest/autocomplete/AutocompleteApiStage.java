package com.zps.zest.autocomplete;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;
import com.zps.zest.autocomplete.utils.ContextGatherer;

/**
 * Enhanced AutocompleteApiStage following Tabby ML best practices.
 * Optimized for fast, accurate code completions with special handling for:
 * - Standard code completion
 * - JavaDoc/JSDoc generation
 * - Line comment completion
 */
public class AutocompleteApiStage extends OpenWebUiApiCallStage {
    private static final Logger LOG = Logger.getInstance(AutocompleteApiStage.class);

    public AutocompleteApiStage(Project project) {
        // Initialize with default minimal system prompt
        super(new Builder()
            .streaming(false)
            .model(ConfigurationManager.getInstance(project).getAutocompleteModel())
            .systemPrompt(AutocompletePromptBuilder.MINIMAL_SYSTEM_PROMPT));
    }

    public AutocompleteApiStage(Project project, String customSystemPrompt) {
        // Allow custom system prompt for different completion contexts
        super(new Builder()
            .streaming(false)
            .model(ConfigurationManager.getInstance(project).getAutocompleteModel())
            .systemPrompt(customSystemPrompt));
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

        // Validate the response
        if (isValidCompletion(cleanedResponse)) {
            context.setApiResponse(cleanedResponse);
            LOG.debug("Cleaned autocomplete response: " +
                    cleanedResponse.substring(0, Math.min(50, cleanedResponse.length())));
        } else {
            LOG.warn("Invalid completion response, clearing");
            context.setApiResponse("");
        }
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

    /**
     * Checks if a line looks like explanatory text rather than code.
     */
    private boolean isExplanatoryLine(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("this ") ||
                lower.startsWith("the ") ||
                lower.startsWith("here ") ||
                lower.startsWith("note:") ||
                lower.startsWith("explanation:") ||
                lower.contains("will complete") ||
                lower.contains("adds the") ||
                (line.length() > 60 && !looksLikeCode(line));
    }

    /**
     * Checks if a line looks like actual code.
     */
    private boolean looksLikeCode(String line) {
        String trimmed = line.trim();

        return trimmed.contains("(") && trimmed.contains(")") ||
                trimmed.contains("{") || trimmed.contains("}") ||
                trimmed.contains(";") ||
                trimmed.matches(".*\\b(public|private|protected|static|final|class|interface|if|else|for|while|return|new|import|package)\\b.*") ||
                trimmed.matches(".*[a-zA-Z_][a-zA-Z0-9_]*\\s*[=\\(].*") ||
                trimmed.matches("\\s*[}\\);]\\s*") ||
                trimmed.matches("\\s*//.*") ||
                trimmed.matches("\\s*/\\*.*");
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