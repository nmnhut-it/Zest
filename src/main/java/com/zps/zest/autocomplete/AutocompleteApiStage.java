package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.LlmApiCallStage;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;
import com.zps.zest.autocomplete.utils.ContextGatherer;

/**
 * Enhanced AutocompleteApiStage following Tabby ML best practices.
 * Optimized for fast, accurate code completions.
 */
public class AutocompleteApiStage extends LlmApiCallStage {
    private static final Logger LOG = Logger.getInstance(AutocompleteApiStage.class);

    // Optimized settings for autocomplete
    private static final int AUTOCOMPLETE_TIMEOUT_MS = 8000; // Slightly longer for better results
    private static final int MAX_COMPLETION_TOKENS = 150;    // Focused completions
    private static final double AUTOCOMPLETE_TEMPERATURE = 0.2; // More deterministic
    private static final double AUTOCOMPLETE_TOP_P = 0.8;   // More focused

    // Minimal system prompt following Tabby ML approach
    private static final String MINIMAL_SYSTEM_PROMPT =
            "Complete the code. Only return the completion text, no explanations.";

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.debug("Processing enhanced autocomplete request");

        try {
            // Enhanced context preparation
            enhanceContextForAutocomplete(context);

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
     * Enhanced context preparation using Tabby ML patterns.
     */
    private void enhanceContextForAutocomplete(CodeContext context) {
        Editor editor = context.getEditor();
        if (editor == null) {
            LOG.warn("No editor available for autocomplete context");
            fallbackToBasicContext(context);
            return;
        }

        try {
            // Get PSI file for language detection
            PsiFile psiFile = null;
            if (context.getProject() != null) {
                psiFile = PsiDocumentManager.getInstance(context.getProject())
                        .getPsiFile(editor.getDocument());
            }

            // Gather enhanced cursor context
            ContextGatherer.CursorContext cursorContext =
                    ContextGatherer.gatherEnhancedCursorContext(editor, psiFile);

            // Gather file context  
            String fileContext = ContextGatherer.gatherFileContext(editor, psiFile);

            // Detect language
            String language = detectLanguage(editor, psiFile);

            // Build minimal, effective prompt
            String enhancedPrompt = AutocompletePromptBuilder.createContextAwarePrompt(
                    fileContext,
                    cursorContext.getPrefixContext(),
                    cursorContext.getSuffixContext(),
                    language
            );

            // Set the enhanced prompt
            context.setPrompt(enhancedPrompt);

            // Store additional context for debugging
            context.setCurrentStageType("ENHANCED_AUTOCOMPLETE_API");

            LOG.debug("Enhanced autocomplete context prepared for language: " + language);

        } catch (Exception e) {
            LOG.warn("Failed to enhance autocomplete context, falling back to basic", e);
            fallbackToBasicContext(context);
        }
    }

    /**
     * Fallback to basic context if enhanced gathering fails.
     */
    private void fallbackToBasicContext(CodeContext context) {
        // Use the legacy approach for backward compatibility
        String basicPrompt = new AutocompletePromptBuilder()
                .withSystemPrompt(MINIMAL_SYSTEM_PROMPT)
                .withFileContext(context.getClassContext() != null ? context.getClassContext() : "")
                .withCursorPosition(getCurrentCursorContext(context))
                .withLanguage(getLanguageFromFile(context))
                .build();

        context.setPrompt(basicPrompt);
        context.setCurrentStageType("FALLBACK_AUTOCOMPLETE_API");
    }

    /**
     * Optimizes configuration specifically for autocomplete requests.
     */
    private void optimizeConfigurationForAutocomplete(CodeContext context) {

        // Use code model if available
        ConfigurationManager config = context.getConfig();
//        if (config != null) {
//            try {
//                String codeModel = config.getCodeModel();
//                if (codeModel != null && !codeModel.isEmpty()) {
//                    if (context.getClass().getMethod("setModelOverride", String.class) != null) {
//                        context.setModelOverride(codeModel);
//                    }
//                }
//            } catch (Exception e) {
//                // Method doesn't exist or config doesn't have getCodeModel, skip
//            }
//        }

        LOG.debug("Autocomplete configuration optimized");
    }

    /**
     * Detects programming language from file context.
     */
    private String detectLanguage(Editor editor, PsiFile psiFile) {
        if (psiFile != null) {
            String fileName = psiFile.getName().toLowerCase();
            if (fileName.endsWith(".java")) return "java";
            if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) return "kotlin";
            if (fileName.endsWith(".js")) return "javascript";
            if (fileName.endsWith(".ts")) return "typescript";
            if (fileName.endsWith(".py")) return "python";
            if (fileName.endsWith(".cpp") || fileName.endsWith(".cc") || fileName.endsWith(".cxx")) return "cpp";
            if (fileName.endsWith(".c")) return "c";
            if (fileName.endsWith(".go")) return "go";
            if (fileName.endsWith(".rs")) return "rust";

            // Use PSI language if available
            String languageName = psiFile.getLanguage().getDisplayName().toLowerCase();
            if (languageName.contains("java")) return "java";
            if (languageName.contains("kotlin")) return "kotlin";
            if (languageName.contains("javascript")) return "javascript";
            if (languageName.contains("typescript")) return "typescript";
            if (languageName.contains("python")) return "python";
        }

        return "java"; // Default fallback
    }

    /**
     * Gets cursor context for fallback scenarios (legacy compatibility).
     */
    private String getCurrentCursorContext(CodeContext context) {
        if (context.getPrompt() != null && !context.getPrompt().isEmpty()) {
            return context.getPrompt();
        }

        if (context.getEditor() != null) {
            // Use the legacy method for backward compatibility
            return ContextGatherer.gatherCursorContext(
                    context.getEditor(),
                    context.getPsiFile()
            );
        }

        return "";
    }

    /**
     * Determines the programming language from the file context (legacy).
     */
    private String getLanguageFromFile(CodeContext context) {
        if (context.getPsiFile() != null) {
            return context.getPsiFile().getLanguage().getDisplayName();
        }
        return "Java"; // Default to Java for this plugin
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