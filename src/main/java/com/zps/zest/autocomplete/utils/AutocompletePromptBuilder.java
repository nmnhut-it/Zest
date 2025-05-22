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
    public static final String ONLY_REMAINING = "\nIMPORTANT: Only return the part of the text to be inserted at cursor position. Do not include any text that already exists before the cursor.\n";

    // System prompts for different completion scenarios
    public static final String MINIMAL_SYSTEM_PROMPT =
            "You are a precise code completion assistant. Your task is to complete code at the cursor position marked with <CURSOR>.\n\n" +
                    "RULES:\n" +
                    "1. Return ONLY the code that should be inserted at the cursor position\n" +
                    "2. Do not repeat any existing code that appears before the cursor\n" +
                    "3. Do not include explanations, markdown formatting, backticks, or any other text\n" +
                    "4. Ensure the completion creates syntactically correct and logically sound code\n" +
                    "5. Consider the context and follow common programming patterns\n\n" +
                    "EXAMPLES:\n" +
                    "Input: `for (int i = 0; i < <CURSOR>`\n" +
                    "Output: `arr.length; i++)`\n\n" +
                    "Input: `public void setName(String name) {\n    this.<CURSOR>`\n" +
                    "Output: `name = name;\n}`\n\n" +
                    "Input: `List<String> items = new ArrayList<>();\nitems.<CURSOR>`\n" +
                    "Output: `add(`";

    public static final String JAVADOC_SYSTEM_PROMPT =
            "You are a documentation generation assistant. Your task is to generate complete JavaDoc/JSDoc comments for the code that follows the cursor position.\n\n" +
                    "RULES:\n" +
                    "1. Generate ONLY the complete comment block that should be inserted at the cursor\n" +
                    "2. Do not repeat any existing comment text that appears before the cursor\n" +
                    "3. Start with /** and end with */ (for block comments) or use appropriate comment syntax\n" +
                    "4. Include relevant @param, @return, @throws, @deprecated tags based on the method signature\n" +
                    "5. Write clear, concise descriptions that explain the purpose and behavior\n" +
                    "6. Do not include explanations or any text outside the comment block\n\n" +
                    "EXAMPLES:\n" +
                    "Input: `/*<CURSOR>\npublic String getName() { return name; }`\n" +
                    "Output: `/**\n     * Gets the name of this object.\n     * @return the name as a String\n     */`\n\n" +
                    "Input: `<CURSOR>\npublic void setAge(int age) throws IllegalArgumentException {\n    if (age < 0) throw new IllegalArgumentException(\"Age cannot be negative\");\n    this.age = age;\n}`\n" +
                    "Output: `/**\n     * Sets the age of this person.\n     * @param age the age to set, must be non-negative\n     * @throws IllegalArgumentException if age is negative\n     */`";

    public static final String LINE_COMMENT_SYSTEM_PROMPT =
            "You are a code commenting assistant. Your task is to complete line comments that explain the purpose or reasoning behind the code.\n\n" +
                    "RULES:\n" +
                    "1. Return ONLY the comment text (without the // prefix)\n" +
                    "2. Do not repeat any existing comment text that appears before the cursor\n" +
                    "3. If there's existing comment text, continue the sentence naturally by providing only the remaining words\n" +
                    "4. Focus on WHY the code exists, not WHAT it does (the code shows what)\n" +
                    "5. Explain business logic, edge cases, performance considerations, or important context\n" +
                    "6. Keep comments concise but informative\n" +
                    "7. Do not include explanations or any text outside the comment\n\n" +
                    "EXAMPLES:\n" +
                    "Input: `// <CURSOR>\nif (user.getAge() < 0) throw new IllegalArgumentException(\"Invalid age\");`\n" +
                    "Output: `Validate age to prevent business logic errors with negative values`\n\n" +
                    "Input: `// This function needs to <CURSOR>\nfunction optimizeQuery() { ... }`\n" +
                    "Output: `be called before any database operations to improve performance`\n\n" +
                    "Input: `// Need to wait here because <CURSOR>\nThread.sleep(100);`\n" +
                    "Output: `the API has rate limiting that requires delays between requests`\n\n" +
                    "Input: `// <CURSOR>\nString result = expensive_operation().toLowerCase().trim();`\n" +
                    "Output: `Cache the result since this operation is called frequently in the main loop`";

    public static final String EXTERNAL_REFERENCE_PROMPT =
            "You are a code completion assistant with knowledge of common programming patterns and best practices. Complete the code at the cursor position using established patterns.\n\n" +
                    "RULES:\n" +
                    "1. Return ONLY the code that should be inserted at the cursor position\n" +
                    "2. Do not repeat any existing code that appears before the cursor\n" +
                    "3. Follow language conventions and common patterns\n" +
                    "4. Consider the surrounding context to determine the most appropriate completion\n" +
                    "5. Ensure the completion follows best practices for the given language\n" +
                    "6. Do not include explanations, markdown formatting, or any other text\n\n" +
                    "EXAMPLES:\n" +
                    "Input: `List<String> names = new ArrayList<>();\nnames.<CURSOR>`\n" +
                    "Output: `add(`\n\n" +
                    "Input: `try {\n    processData();\n} <CURSOR>`\n" +
                    "Output: `catch (Exception e) {\n    logger.error(\"Failed to process data\", e);\n    throw new ProcessingException(\"Data processing failed\", e);\n}`\n\n" +
                    "Input: `@Override\npublic boolean equals(Object obj) {\n    if (this == obj) return true;\n    if (obj == null || getClass() != obj.getClass()) return false;\n    <CURSOR>`\n" +
                    "Output: `Person person = (Person) obj;\n    return Objects.equals(name, person.name) && age == person.age;`";

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
        if (lastLine.startsWith("/**") || lastLine.equals("/*") ||
                (lastLine.startsWith("/*") && !lastLine.endsWith("*/"))) {
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
     * Builds a minimal, effective prompt following best practices.
     */
    private String buildMinimalPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Clear system instruction with examples
        prompt.append(MINIMAL_SYSTEM_PROMPT).append("\n\n");

        // Add focused context if available
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append("CONTEXT:\n```").append(language).append("\n");
            prompt.append(relevantContext);
            prompt.append("\n```\n\n");
        }

        // The completion request with clear formatting
        prompt.append("COMPLETE THIS CODE:\n```").append(language).append("\n");
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

        // Extract method signature and class context
        String methodSignature = findMethodSignature();
        String className = findClassName();

        // Add structured context
        prompt.append("CONTEXT:\n```").append(language).append("\n");
        if (!className.isEmpty()) {
            prompt.append("// Class: ").append(className).append("\n");
        }

        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        // Current position and following code
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        if (!suffixContext.isEmpty()) {
            String truncatedSuffix = truncateContext(suffixContext, 1000);
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

        // Add structured context
        prompt.append("CODE TO COMMENT:\n```").append(language).append("\n");
        String relevantContext = extractRelevantContext();
        if (!relevantContext.isEmpty()) {
            prompt.append(relevantContext).append("\n");
        }

        prompt.append(prefixContext);
        prompt.append("<CURSOR>");

        // Add code that follows for better context understanding
        if (!suffixContext.isEmpty()) {
            String truncatedSuffix = truncateContext(suffixContext, 600);
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

        // Add structured context
        prompt.append("CODE CONTEXT:\n```").append(language).append("\n");
        String relevantContext = extractRelevantContext();
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
     * Builds legacy prompt for backward compatibility.
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

    // ... (rest of the methods remain the same as they're primarily helper methods)

    /**
     * Extracts only the most relevant context around the cursor.
     */
    private String extractRelevantContext() {
        if (fileContext.isEmpty()) {
            return "";
        }

        String fullContext = fileContext;
        String[] lines = fullContext.split("\n");

        StringBuilder context = new StringBuilder();
        int contextLines = 0;

        // Add package, imports, and class declarations
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

        // Add current method context
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
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }

            if (trimmed.contains("(") && (trimmed.contains("{") ||
                    trimmed.endsWith(";") ||
                    trimmed.contains("throws"))) {
                signature.append(trimmed);
                foundSignature = true;
                break;
            }

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
     * Creates a context-aware prompt with best practices.
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