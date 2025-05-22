package com.zps.zest.autocomplete.utils;

/**
 * Enhanced prompt builder for intelligent code completion.
 * Creates context-aware prompts for different scenarios including:
 * - Regular code completion
 * - JavaDoc/JSDoc generation
 * - Comment completion
 * - External reference-based completion
 */
public class AutocompletePromptBuilder {
    public static final String ONLY_REMAINING = "IMPORTANT: Only return the part of the text to be inserted at cursor position.\n" +
            "RESPECT the prefix";

    // System prompts for different completion scenarios
    public static final String MINIMAL_SYSTEM_PROMPT =
            "You are a code completion assistant. Return ONLY the code that should be inserted at the cursor position. " +
                    "Do not include explanations, markdown formatting, or any other text.\n\n" +
                    "Examples:\n" +
                    "Input: for (int i = 0; i < <CURSOR>\n" +
                    "Output: arr.length; i++)\n\n" +
                    "Input: public void setName(String name) {\n    this.<CURSOR>\n" +
                    "Output: name = name;";

    public static final String JAVADOC_SYSTEM_PROMPT =
            "You are a documentation assistant. Generate ONLY the complete JavaDoc/JSDoc comment block that should be inserted at the cursor position. " +
                    "Include proper @param, @return, @throws tags as needed. Start with /** and end with */. " +
                    "Do not include explanations or any other text.\n\n" +
                    "Examples:\n" +
                    "Input: <CURSOR>\n    public String getName() { return name; }\n" +
                    "Output: /**\n     * Gets the name.\n     * @return the name\n     */\n\n" +
                    "Input: <CURSOR>\n    public void setAge(int age) throws IllegalArgumentException {\n" +
                    "Output: /**\n     * Sets the age.\n     * @param age the age to set\n     * @throws IllegalArgumentException if age is negative\n     */";

    public static final String LINE_COMMENT_SYSTEM_PROMPT =
            "You are a comment completion assistant. Return ONLY the comment text that explains why this code exists or its purpose. " +
                    "Focus on business logic, edge cases, or important context. Be concise. " +
                    "Do not include the '//' prefix or any explanations.\n\n" +
                    "Examples:\n" +
                    "Input: // <CURSOR>\n    if (user.getAge() < 0) throw new IllegalArgumentException();\n" +
                    "Output: Validate age to prevent negative values in business logic\n\n" +
                    "Input: // <CURSOR>\n    Thread.sleep(100);\n" +
                    "Output: Brief pause to prevent overwhelming the API with requests";

    public static final String EXTERNAL_REFERENCE_PROMPT =
            "You are a code completion assistant. Based on common patterns and best practices, return ONLY the code that should be inserted at the cursor position. " +
                    "Do not include explanations, markdown formatting, or any other text.\n\n" +
                    "Examples:\n" +
                    "Input: List<String> names = new ArrayList<>();\n    names.<CURSOR>\n" +
                    "Output: add(\n\n" +
                    "Input: try {\n    // code\n} <CURSOR>\n" +
                    "Output: catch (Exception e) {\n    e.printStackTrace();\n}";

    private String systemPrompt = "";
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
     * This will incorporate common patterns and best practices from
     * similar code examples.
     */
    public AutocompletePromptBuilder withExternalReferences(boolean enable) {
        this.searchExternalReferences = enable;
        return this;
    }

    /**
     * Builds the prompt using the appropriate approach based on context.
     */
    public String build() {
        // Detect the completion context based on prefix
        CompletionContext context = detectCompletionContext();

        switch (context) {
            case JAVADOC:
                return buildJavadocPrompt();
            case LINE_COMMENT:
                return buildLineCommentPrompt();
            case EXTERNAL_REFERENCE:
                return buildExternalReferencePrompt();
            case MINIMAL:
            default:
                if (useMinimalPrompt && systemPrompt.isEmpty()) {
                    return buildMinimalPrompt();
                } else {
                    return buildLegacyPrompt();
                }
        }
    }

    /**
     * Detects the type of completion needed based on code context.
     */
    private CompletionContext detectCompletionContext() {
        if (prefixContext == null || prefixContext.isEmpty()) {
            return CompletionContext.MINIMAL;
        }

        // Get the last line of prefix to determine context
        String[] lines = prefixContext.split("\n");
        if (lines.length == 0) {
            return CompletionContext.MINIMAL;
        }

        String lastLine = lines[lines.length - 1].trim();

        // Check for JavaDoc/JSDoc comment start
        if (lastLine.startsWith("/**") ||
                (lastLine.equals("/*") && "java".equalsIgnoreCase(language) || "javascript".equalsIgnoreCase(language))) {
            return CompletionContext.JAVADOC;
        }

        // Check for line comment
        if (lastLine.startsWith("//")) {
            return CompletionContext.LINE_COMMENT;
        }

        // Check if external reference search is requested
        if (searchExternalReferences) {
            return CompletionContext.EXTERNAL_REFERENCE;
        }

        return CompletionContext.MINIMAL;
    }

    /**
     * Builds a minimal, effective prompt following Tabby ML patterns.
     */
    private String buildMinimalPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Minimal system instruction
        prompt.append(MINIMAL_SYSTEM_PROMPT).append("\n\n");

        // Add focused context
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append("Context:\n```").append(language).append("\n");
            prompt.append(relevantContext);
            prompt.append("\n```\n\n");
        }

        // The actual completion request - minimal and clear
        prompt.append("Code to complete:\n```").append(language).append("\n");
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
     * Builds specialized prompt for JavaDoc/JSDoc completion.
     */
    private String buildJavadocPrompt() {
        StringBuilder prompt = new StringBuilder();

        // JavaDoc specific system instruction
        prompt.append(JAVADOC_SYSTEM_PROMPT).append("\n\n");

        // Extract method signature if available
        String methodSignature = findMethodSignature();
        String className = findClassName();

        // Add context
        prompt.append("Context:\n```").append(language).append("\n");
        if (!className.isEmpty()) {
            prompt.append("// Class: ").append(className).append("\n");
        }

        // Include surrounding code for better context
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        // Add current position
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        // Include following code to help understand what needs to be documented
        if (!suffixContext.isEmpty()) {
            String truncatedSuffix = truncateContext(suffixContext, 800);
            prompt.append("\n").append(truncatedSuffix);
        }
        prompt.append("\n```");
        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * Builds specialized prompt for line comment completion.
     */
    private String buildLineCommentPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Line comment specific system instruction
        prompt.append(LINE_COMMENT_SYSTEM_PROMPT).append("\n\n");

        // Add context
        prompt.append("Code context:\n```").append(language).append("\n");
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        // Add code that follows the comment for context
        if (!suffixContext.isEmpty()) {
            String truncatedSuffix = truncateContext(suffixContext, 500);
            prompt.append("\n").append(truncatedSuffix);
        }
        prompt.append("\n```");
        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * Builds prompt for external reference-based completion.
     */
    private String buildExternalReferencePrompt() {
        StringBuilder prompt = new StringBuilder();

        // External reference specific system instruction
        prompt.append(EXTERNAL_REFERENCE_PROMPT).append("\n\n");

        // Add context
        prompt.append("Code context:\n```").append(language).append("\n");
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        // Add current code up to cursor
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        // Add following code if available
        if (!suffixContext.isEmpty()) {
            prompt.append(suffixContext);
        }
        prompt.append("\n```");
        prompt.append(ONLY_REMAINING);

        return prompt.toString();
    }

    /**
     * Builds legacy prompt for backward compatibility.
     */
    private String buildLegacyPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Add system prompt if provided, otherwise use minimal
        if (!systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        } else {
            prompt.append("Return only the code completion that should be inserted at <CURSOR>. No explanations or formatting.\n\n");
        }

        // Add language context
        prompt.append("Language: ").append(language).append("\n\n");

        // Add file context if available
        if (!fileContext.isEmpty()) {
            prompt.append("File Context:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(truncateContext(fileContext, 800));
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
     * Attempts to find the signature of the method being documented.
     */
    private String findMethodSignature() {
        if (suffixContext.isEmpty()) return "";

        String[] lines = suffixContext.split("\n");
        StringBuilder signature = new StringBuilder();
        boolean foundSignature = false;

        for (String line : lines) {
            String trimmed = line.trim();
            // Skip empty lines and other comments
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }

            // Method signature typically contains parentheses and ends with { or ;
            if (trimmed.contains("(") && (trimmed.contains("{") ||
                    trimmed.endsWith(";") ||
                    trimmed.contains("throws"))) {
                signature.append(trimmed);
                foundSignature = true;
                break;
            }

            // Multi-line method signatures
            if (trimmed.contains("(") ||
                    (signature.length() > 0 && !trimmed.contains("*/"))) {
                signature.append(trimmed).append(" ");
                if (trimmed.contains("{") || trimmed.endsWith(";")) {
                    foundSignature = true;
                    break;
                }
            }
        }

        return foundSignature ? signature.toString() : "";
    }

    /**
     * Attempts to find the class name from the context.
     */
    private String findClassName() {
        if (fileContext.isEmpty()) return "";

        String[] lines = fileContext.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("class ") || trimmed.contains("interface ")) {
                // Extract class name using simple regex-like approach
                int classIdx = trimmed.indexOf("class ") + 6;
                if (trimmed.contains("interface ")) {
                    classIdx = trimmed.indexOf("interface ") + 10;
                }

                int endIdx = trimmed.indexOf(" ", classIdx);
                if (endIdx == -1) endIdx = trimmed.indexOf("{", classIdx);
                if (endIdx == -1) endIdx = trimmed.length();

                return trimmed.substring(classIdx, endIdx).trim();
            }
        }

        return "";
    }

    /**
     * Truncates context to a reasonable length while preserving structure.
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
     * Completion context types for different scenarios.
     */
    private enum CompletionContext {
        MINIMAL,       // Standard code completion
        JAVADOC,       // JavaDoc/JSDoc generation
        LINE_COMMENT,  // Line comment completion
        EXTERNAL_REFERENCE // Completion based on external code patterns
    }
}