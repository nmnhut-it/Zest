package com.zps.zest.autocomplete.utils;

/**
 * Builder class for creating well-structured prompts for autocomplete requests.
 * Formats context and instructions to get the best possible completions from the LLM.
 */
public class AutocompletePromptBuilder {
    private String systemPrompt = "";
    private String fileContext = "";
    private String cursorContext = "";
    private String language = "Java";
    private boolean preferSingleLine = false;
    private boolean includeComments = false;
    
    /**
     * Sets the system prompt that defines the completion behavior.
     */
    public AutocompletePromptBuilder withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }
    
    /**
     * Sets the broader file context (class structure, imports, etc.).
     */
    public AutocompletePromptBuilder withFileContext(String fileContext) {
        this.fileContext = fileContext;
        return this;
    }
    
    /**
     * Sets the immediate context around the cursor position.
     */
    public AutocompletePromptBuilder withCursorPosition(String cursorContext) {
        this.cursorContext = cursorContext;
        return this;
    }
    
    /**
     * Sets the programming language for context.
     */
    public AutocompletePromptBuilder withLanguage(String language) {
        this.language = language;
        return this;
    }
    
    /**
     * Indicates preference for single-line completions.
     */
    public AutocompletePromptBuilder preferSingleLine(boolean preferSingleLine) {
        this.preferSingleLine = preferSingleLine;
        return this;
    }
    
    /**
     * Indicates whether to include comments in completions.
     */
    public AutocompletePromptBuilder includeComments(boolean includeComments) {
        this.includeComments = includeComments;
        return this;
    }
    
    /**
     * Builds the final prompt string.
     */
    public String build() {
        StringBuilder prompt = new StringBuilder();
        
        // Add system instructions
        if (!systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        }
        
        // Add language context
        prompt.append("Language: ").append(language).append("\n\n");
        
        // Add completion preferences
        if (preferSingleLine) {
            prompt.append("Preference: Complete only the current line or statement.\n");
        } else {
            prompt.append("Preference: Complete the logical block of code (method, if-statement, etc.).\n");        }
        
        if (!includeComments) {
            prompt.append("Focus: Provide code completion only, no comments or explanations.\n");
        }
        
        prompt.append("\n");
        
        // Add file context if available
        if (!fileContext.isEmpty() && !fileContext.equals(cursorContext)) {
            prompt.append("File Context:\n");
            prompt.append("```").append(language.toLowerCase()).append("\n");
            prompt.append(truncateContext(fileContext, 800));
            prompt.append("\n```\n\n");
        }
        
        // Add cursor context
        prompt.append("Complete the code at the <CURSOR> position:\n");
        prompt.append("```").append(language.toLowerCase()).append("\n");
        prompt.append(cursorContext);
        prompt.append("\n```\n\n");
        
        // Add final instruction
        prompt.append("Completion (provide only the code to insert at <CURSOR>, no markdown, no explanations):");
        
        return prompt.toString();
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
     * Creates a quick single-line completion prompt.
     */
    public static String createQuickPrompt(String cursorContext, String language) {
        return new AutocompletePromptBuilder()
            .withLanguage(language)
            .withCursorPosition(cursorContext)
            .preferSingleLine(true)
            .includeComments(false)
            .build();
    }
    
    /**
     * Creates a multi-line completion prompt for more complex scenarios.
     */
    public static String createMultiLinePrompt(String fileContext, String cursorContext, String language) {
        return new AutocompletePromptBuilder()
            .withLanguage(language)
            .withFileContext(fileContext)
            .withCursorPosition(cursorContext)
            .preferSingleLine(false)
            .includeComments(false)
            .build();
    }
}
