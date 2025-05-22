package com.zps.zest.autocomplete.utils;

/**
 * Enhanced prompt builder following Tabby ML best practices.
 * Creates minimal, focused prompts that work better with LLMs.
 * Maintains backward compatibility with old API.
 */
public class AutocompletePromptBuilder {
    private String systemPrompt = "";
    private String fileContext = "";
    private String prefixContext = "";
    private String suffixContext = "";
    private String cursorPosition = "";
    private String language = "java";
    private int maxContextLines = 20;
    private boolean useMinimalPrompt = true;

    /**
     * Sets the system prompt (for backward compatibility).
     *
     * @deprecated Use minimal prompts instead for better results
     */
    @Deprecated
    public AutocompletePromptBuilder withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        this.useMinimalPrompt = false; // Use old style if system prompt is set
        return this;
    }

    /**
     * Sets the file context (limited to relevant surrounding code).
     */
    public AutocompletePromptBuilder withFileContext(String fileContext) {
        this.fileContext = fileContext;
        return this;
    }

    /**
     * Sets the cursor position context (for backward compatibility).
     *
     * @deprecated Use withPrefix and withSuffix for better context
     */
    @Deprecated
    public AutocompletePromptBuilder withCursorPosition(String cursorPosition) {
        this.cursorPosition = cursorPosition;
        // Try to extract prefix/suffix from cursor position
        if (cursorPosition.contains("<CURSOR>")) {
            String[] parts = cursorPosition.split("<CURSOR>", 2);
            this.prefixContext = parts[0];
            this.suffixContext = parts.length > 1 ? parts[1] : "";
        } else {
            this.prefixContext = cursorPosition;
        }
        return this;
    }

    /**
     * Sets the code before the cursor position.
     */
    public AutocompletePromptBuilder withPrefix(String prefixContext) {
        this.prefixContext = prefixContext;
        return this;
    }

    /**
     * Sets the code after the cursor position.
     */
    public AutocompletePromptBuilder withSuffix(String suffixContext) {
        this.suffixContext = suffixContext;
        return this;
    }

    /**
     * Sets the programming language.
     */
    public AutocompletePromptBuilder withLanguage(String language) {
        this.language = language;
        return this;
    }

    /**
     * Sets maximum context lines to include.
     */
    public AutocompletePromptBuilder withMaxContextLines(int maxLines) {
        this.maxContextLines = maxLines;
        return this;
    }

    /**
     * Builds the prompt using either minimal or legacy approach.
     */
    public String build() {
        if (useMinimalPrompt && systemPrompt.isEmpty()) {
            return buildMinimalPrompt();
        } else {
            return buildLegacyPrompt();
        }
    }

    /**
     * Builds a minimal, effective prompt following Tabby ML patterns.
     */
    private String buildMinimalPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Minimal system instruction
        prompt.append("Complete the code. Only return the completion text, no explanations.\n\n");

        // Add focused context
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append("```").append(language).append("\n");
            prompt.append(relevantContext);
            prompt.append("\n```\n\n");
        }

        // The actual completion request - minimal and clear
        prompt.append("Complete:\n```").append(language).append("\n");
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");
        if (!suffixContext.isEmpty()) {
            prompt.append(suffixContext);
        }
        prompt.append("\n```");

        return prompt.toString();
    }

    /**
     * Builds legacy prompt for backward compatibility.
     */
    private String buildLegacyPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Add system prompt if provided
        if (!systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        }

        // Add language context
        prompt.append("Language: ").append(language).append("\n\n");

        // Add file context if available
        if (!fileContext.isEmpty()) {
            prompt.append("File Context:\n");
            prompt.append("```").append(language.toLowerCase()).append("\n");
            prompt.append(truncateContext(fileContext, 800));
            prompt.append("\n```\n\n");
        }

        // Add cursor context
        if (!cursorPosition.isEmpty()) {
            prompt.append("Complete the code at the <CURSOR> position:\n");
            prompt.append("```").append(language.toLowerCase()).append("\n");
            prompt.append(cursorPosition);
            prompt.append("\n```\n\n");
        } else {
            prompt.append("Complete:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(prefixContext);
            prompt.append("<CURSOR>");
            if (!suffixContext.isEmpty()) {
                prompt.append(suffixContext);
            }
            prompt.append("\n```\n\n");
        }

        // Add final instruction
        prompt.append("Completion (provide only the code to insert at <CURSOR>, no markdown, no explanations):");

        return prompt.toString();
    }

    /**
     * Extracts only the most relevant context around the cursor.
     */
    private String extractRelevantContext() {
        if (fileContext.isEmpty()) {
            return "";
        }

        // Find the cursor position in the full context
        String fullContext = fileContext;
        String[] lines = fullContext.split("\n");

        // Look for current method/class context
        StringBuilder context = new StringBuilder();
        int contextLines = 0;

        // Add class declaration and imports if present
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ") ||
                    trimmed.contains("class ") || trimmed.contains("interface ") ||
                    trimmed.contains("public class") || trimmed.contains("private class")) {
                context.append(line).append("\n");
                contextLines++;
                if (contextLines >= 5) break; // Limit initial context
            }
        }

        // Add method signature if we're inside a method
        String methodContext = findCurrentMethodContext();
        if (!methodContext.isEmpty()) {
            context.append(methodContext).append("\n");
        }

        return context.toString();
    }

    /**
     * Finds the current method context from prefix.
     */
    private String findCurrentMethodContext() {
        if (prefixContext.isEmpty()) return "";

        String[] lines = prefixContext.split("\n");
        String methodSignature = "";

        // Look backwards for method signature
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.contains("(") && (line.contains("public ") || line.contains("private ") ||
                    line.contains("protected ") || line.contains("static "))) {
                methodSignature = lines[i];
                break;
            }
        }

        return methodSignature;
    }

    /**
     * Truncates context to a reasonable length while preserving structure (legacy).
     */
    private String truncateContext(String context, int maxLength) {
        if (context.length() <= maxLength) {
            return context;
        }

        // Try to keep important parts (imports, class declaration)
        String[] lines = context.split("\n");
        StringBuilder truncated = new StringBuilder();
        int currentLength = 0;
        boolean inImports = true;
        boolean foundClassDeclaration = false;

        for (String line : lines) {
            if (currentLength + line.length() > maxLength && truncated.length() > maxLength / 2) {
                truncated.append("\n// ... (truncated for brevity) ...\n");
                break;
            }

            // Always include package, imports, and class declaration
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("package ") ||
                    trimmedLine.startsWith("import ") ||
                    trimmedLine.startsWith("public class ") ||
                    trimmedLine.startsWith("class ") ||
                    trimmedLine.startsWith("public interface ")) {

                truncated.append(line).append("\n");
                currentLength += line.length() + 1;

                if (trimmedLine.contains("class ") || trimmedLine.contains("interface ")) {
                    foundClassDeclaration = true;
                    inImports = false;
                }
            } else if (!inImports || foundClassDeclaration) {
                // Include other lines if we have space
                truncated.append(line).append("\n");
                currentLength += line.length() + 1;
            }
        }

        return truncated.toString();
    }

    /**
     * Creates a simple, focused prompt for basic completions.
     */
    public static String createSimplePrompt(String prefix, String suffix, String language) {
        return new AutocompletePromptBuilder()
                .withPrefix(prefix)
                .withSuffix(suffix)
                .withLanguage(language)
                .build();
    }

    /**
     * Creates a context-aware prompt similar to Tabby ML approach.
     */
    public static String createContextAwarePrompt(String fileContext, String prefix, String suffix, String language) {
        return new AutocompletePromptBuilder()
                .withFileContext(fileContext)
                .withPrefix(prefix)
                .withSuffix(suffix)
                .withLanguage(language)
                .withMaxContextLines(15)
                .build();
    }

    /**
     * Creates a quick single-line completion prompt (legacy support).
     */
    public static String createQuickPrompt(String cursorContext, String language) {
        return new AutocompletePromptBuilder()
                .withLanguage(language)
                .withCursorPosition(cursorContext)
                .withMaxContextLines(5)
                .build();
    }

    /**
     * Creates a multi-line completion prompt for more complex scenarios (legacy support).
     */
    public static String createMultiLinePrompt(String fileContext, String cursorContext, String language) {
        return new AutocompletePromptBuilder()
                .withLanguage(language)
                .withFileContext(fileContext)
                .withCursorPosition(cursorContext)
                .build();
    }
}