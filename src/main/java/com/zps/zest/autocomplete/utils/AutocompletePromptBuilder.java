package com.zps.zest.autocomplete.utils;

import com.zps.zest.autocomplete.context.CompletionContext;
import com.zps.zest.autocomplete.context.SemanticContextGatherer;
import com.zps.zest.autocomplete.prompts.EnhancedPromptBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Simplified prompt builder that delegates to the enhanced semantic system.
 * Maintains backward compatibility while leveraging the new architecture.
 */
public class AutocompletePromptBuilder {
    
    // Legacy constants for backward compatibility
    public static final String MINIMAL_SYSTEM_PROMPT =
            "Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.";

    public String systemPrompt = "";
    private String fileContext = "";
    private String prefixContext = "";
    private String suffixContext = "";
    private String language = "java";
    
    // New enhanced approach
    private Editor editor;
    private PsiFile psiFile;
    
    /**
     * Creates a context-aware prompt builder using semantic analysis.
     * This is the preferred approach for new code.
     */
    public static AutocompletePromptBuilder createContextAwarePrompt(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        AutocompletePromptBuilder builder = new AutocompletePromptBuilder();
        builder.editor = editor;
        builder.psiFile = psiFile;
        return builder;
    }
    
    /**
     * Legacy method maintained for backward compatibility.
     */
    public static AutocompletePromptBuilder createContextAwarePrompt(String fileContext, String prefix, String suffix, String language) {
        return new AutocompletePromptBuilder()
                .withFileContext(fileContext)
                .withPrefix(prefix)
                .withSuffix(suffix)
                .withLanguage(language);
    }
    
    /**
     * Builds the prompt using the enhanced semantic system when possible.
     */
    public String build() {
        // If we have editor and PSI file, use the enhanced semantic approach
        if (editor != null && psiFile != null) {
            return buildEnhancedPrompt();
        }
        
        // Fall back to legacy approach for backward compatibility
        return buildLegacyPrompt();
    }
    
    /**
     * Builds prompt using the new enhanced semantic context system.
     */
    private String buildEnhancedPrompt() {
        try {
            CompletionContext context = SemanticContextGatherer.gatherContext(editor, psiFile);
            return EnhancedPromptBuilder.buildPrompt(context);
        } catch (Exception e) {
            // Fall back to legacy if enhanced approach fails
            return buildLegacyPrompt();
        }
    }
    
    /**
     * Legacy prompt building for backward compatibility.
     */
    private String buildLegacyPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        // Add system prompt if provided
        if (!systemPrompt.isEmpty()) {
            prompt.append(systemPrompt).append("\n\n");
        } else {
            prompt.append(MINIMAL_SYSTEM_PROMPT).append("\n\n");
        }
        
        // Add language context
        prompt.append("Language: ").append(language).append("\n\n");
        
        // Add file context if available
        if (!fileContext.isEmpty()) {
            prompt.append("File Context:\n```").append(language.toLowerCase()).append("\n");
            prompt.append(truncateContext(fileContext, 1000));
            prompt.append("\n```\n\n");
        }
        
        // Add completion request
        prompt.append("Complete:\n```").append(language.toLowerCase()).append("\n");
        prompt.append(prefixContext);
        prompt.append("<CURSOR>");
        if (!suffixContext.isEmpty()) {
            prompt.append(suffixContext);
        }
        prompt.append("\n```\n\n");
        
        prompt.append("🚨 CRITICAL: Only return NEW text to insert at cursor. Never repeat existing text.");
        
        return prompt.toString();
    }
    
    // Legacy builder methods for backward compatibility
    
    public AutocompletePromptBuilder withSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }
    
    public AutocompletePromptBuilder withFileContext(String fileContext) {
        this.fileContext = fileContext;
        return this;
    }
    
    public AutocompletePromptBuilder withPrefix(String prefixContext) {
        this.prefixContext = prefixContext;
        return this;
    }
    
    public AutocompletePromptBuilder withSuffix(String suffixContext) {
        this.suffixContext = suffixContext;
        return this;
    }
    
    public AutocompletePromptBuilder withLanguage(String language) {
        this.language = language;
        return this;
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
            } else if (truncated.length() > 0) {
                truncated.append(line).append("\n");
                currentLength += line.length() + 1;
            }
        }
        
        return truncated.toString();
    }
}
