package com.zps.zest.autocomplete.utils;

/**
 * Enhanced prompt builder for intelligent code completion.
 * ONLY supports code completion - comments and javadocs are disabled.
 */
public class AutocompletePromptBuilder {
    public static final String ONLY_REMAINING = "\n\n🚨 CRITICAL: Only return NEW text to insert at cursor. Never repeat existing text.\n";

    // System prompts for code completion only
    public static final String MINIMAL_SYSTEM_PROMPT =
            "Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.";

    public static final String EXTERNAL_REFERENCE_PROMPT =
            "Complete code at <CURSOR> using common patterns. Return only new text to insert. Do not repeat existing text.";

    // TODO: Future placeholders for comment/javadoc support
    // These will be implemented when you're ready to add comment support back
    public static final String JAVADOC_SYSTEM_PROMPT =
            "Complete JavaDoc comment at <CURSOR>. Return only the remaining comment block. Do not repeat existing comment text.";

    public static final String LINE_COMMENT_SYSTEM_PROMPT =
            "Complete line comment at <CURSOR>. Return only new comment text to add. Do not repeat existing comment text.";

    public String systemPrompt = "";
    private String fileContext = "";
    private String prefixContext = "";
    private String suffixContext = "";
    private String cursorPosition = "";
    private String language = "java";
    private int maxContextLines = 20;
    private boolean useMinimalPrompt = true;
    private boolean searchExternalReferences = false;

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
     * Enables search for external references when building the prompt.
     */
    public AutocompletePromptBuilder withExternalReferences(boolean enable) {
        this.searchExternalReferences = enable;
        return this;
    }

    /**
     * Builds the prompt - ONLY supports code completion now.
     */
    public String build() {
        // ✅ SIMPLIFIED: Only detect code vs external reference context
        CompletionContext context = detectCompletionContext();

        switch (context) {
            case EXTERNAL_REFERENCE:
                this.systemPrompt = EXTERNAL_REFERENCE_PROMPT;
                return buildExternalReferencePrompt();
            case CODE_ONLY:
            default:
                if (useMinimalPrompt && systemPrompt.isEmpty()) {
                    return buildMinimalPrompt();
                } else {
                    return buildLegacyPrompt();
                }
        }
    }

    /**
     * ✅ SIMPLIFIED: Only detects code vs external reference context.
     * Comments and javadocs are explicitly excluded.
     */
    private CompletionContext detectCompletionContext() {
        if (prefixContext == null || prefixContext.isEmpty()) {
            return CompletionContext.CODE_ONLY;
        }

        // Get the last line of prefix to determine context
        String[] lines = prefixContext.split("\n");
        if (lines.length == 0) {
            return CompletionContext.CODE_ONLY;
        }

        String lastLine = lines[lines.length - 1].trim();

        // ❌ REMOVED: JavaDoc/JSDoc detection
        // TODO: Add back when you want to support javadoc completion
        // if (lastLine.startsWith("/**") || lastLine.equals("/*") ||
        //     (lastLine.startsWith("/*") && !lastLine.endsWith("*/"))) {
        //     return triggerJavadocCompletion(lastLine);
        // }

        // ❌ REMOVED: Line comment detection
        // TODO: Add back when you want to support comment completion
        // if (lastLine.startsWith("//")) {
        //     return triggerLineCommentCompletion(lastLine);
        // }

        // Check if external reference search is requested
        if (searchExternalReferences) {
            return CompletionContext.EXTERNAL_REFERENCE;
        }

        return CompletionContext.CODE_ONLY;
    }

    // TODO: Placeholder methods for future comment/javadoc support
    // Uncomment and implement these when you want to add support back

    /**
     * TODO: Implement this method when you want javadoc completion support.
     * This will be called when user types /** and waits.
     */
    private CompletionContext triggerJavadocCompletion(String lastLine) {
        // TODO: Add your custom logic here for javadoc triggering
        // For now, treat as regular code
        return CompletionContext.CODE_ONLY;
    }

    /**
     * TODO: Implement this method when you want line comment completion support.
     * This will be called when user types // and waits.
     */
    private CompletionContext triggerLineCommentCompletion(String lastLine) {
        // TODO: Add your custom logic here for comment triggering
        // For now, treat as regular code
        return CompletionContext.CODE_ONLY;
    }

    /**
     * Builds a minimal, effective prompt for CODE ONLY.
     */
    private String buildMinimalPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Clear system instruction - CODE ONLY
        prompt.append(MINIMAL_SYSTEM_PROMPT).append("\n\n");

        // Add focused context if available
        String relevantContext = extractRelevantCodeContext();
        if (!relevantContext.isEmpty()) {
            prompt.append("Context:\n```").append(language).append("\n");
            prompt.append(relevantContext);
            prompt.append("\n```\n\n");
        }

        // The completion request
        prompt.append("Complete:\n```").append(language).append("\n");
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");
        if (!suffixContext.isEmpty()) {
            prompt.append(suffixContext);
        }
        prompt.append("\n```");

        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * Builds prompt for external reference-based CODE completion.
     */
    private String buildExternalReferencePrompt() {
        StringBuilder prompt = new StringBuilder();

        // Add structured context - CODE ONLY
        prompt.append("CODE CONTEXT:\n```").append(language).append("\n");
        String relevantContext = extractRelevantCodeContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        if (!suffixContext.isEmpty()) {
            prompt.append(suffixContext);
        }
        prompt.append("\n```");
        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * Builds legacy prompt for backward compatibility - CODE ONLY.
     */
    private String buildLegacyPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Add system prompt if provided, otherwise use minimal
        if (!systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        } else {
            prompt.append("Complete the code at the <CURSOR> position. Return only the completion text without explanations or formatting.\n\n");
        }

        // Add language context
        prompt.append("Language: ").append(language).append("\n\n");

        // Add file context if available
        if (!fileContext.isEmpty()) {
            prompt.append("File Context:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(truncateContext(fileContext, 1000));
            prompt.append("\n```\n\n");
        }

        // Add cursor context
        if (!cursorPosition.isEmpty()) {
            prompt.append("Code to complete:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(cursorPosition);
            prompt.append("\n```");
        } else {
            prompt.append("Code to complete:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(prefixContext);
            prompt.append("<CURSOR>");
            if (!suffixContext.isEmpty()) {
                prompt.append(suffixContext);
            }
            prompt.append("\n```");
        }
        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * ✅ RENAMED: Extracts only CODE context (no comments/javadocs).
     */
    private String extractRelevantCodeContext() {
        if (fileContext.isEmpty()) {
            return "";
        }

        String fullContext = fileContext;
        String[] lines = fullContext.split("\n");

        StringBuilder context = new StringBuilder();
        int contextLines = 0;

        // Add package, imports, and class declarations (CODE ONLY)
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ") ||
                    trimmed.contains("class ") || trimmed.contains("interface ") ||
                    trimmed.contains("public class") || trimmed.contains("private class")) {
                context.append(line).append("\n");
                contextLines++;
                if (contextLines >= 5) break;
            }
        }

        // Add current method context (CODE ONLY)
        String methodContext = findCurrentMethodContext();
        if (!methodContext.isEmpty()) {
            context.append(methodContext).append("\n");
        }

        return context.toString();
    }

    /**
     * Finds the current method context from prefix (CODE ONLY).
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
     * Truncates context to a reasonable length while preserving structure.
     */
    private String truncateContext(String context, int maxLength) {
        if (context.length() <= maxLength) {
            return context;
        }

        String[] lines = context.split("\n");
        StringBuilder truncated = new StringBuilder();
        int currentLength = 0;
        boolean inImports = true;
        boolean foundClassDeclaration = false;

        for (String line : lines) {
            if (currentLength + line.length() > maxLength && truncated.length() > maxLength / 2) {
                truncated.append("\n// ... (context truncated) ...\n");
                break;
            }

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
                truncated.append(line).append("\n");
                currentLength += line.length() + 1;
            }
        }

        return truncated.toString();
    }

    /**
     * Creates a context-aware prompt for CODE completion only.
     */
    public static AutocompletePromptBuilder createContextAwarePrompt(String fileContext, String prefix, String suffix, String language) {
        return new AutocompletePromptBuilder()
                .withFileContext(fileContext)
                .withPrefix(prefix)
                .withSuffix(suffix)
                .withLanguage(language)
                .withMaxContextLines(15);
    }

    /**
     * ✅ SIMPLIFIED: Completion context types - CODE ONLY now.
     */
    private enum CompletionContext {
        CODE_ONLY,         // Standard code completion (renamed from MINIMAL)
        EXTERNAL_REFERENCE // Completion based on external code patterns
        // TODO: Add these back when you need comment/javadoc support:
        // JAVADOC,       // JavaDoc/JSDoc generation
        // LINE_COMMENT,  // Line comment completion
    }
}