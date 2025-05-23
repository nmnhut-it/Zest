package com.zps.zest.autocomplete.prompts;

import com.zps.zest.autocomplete.context.*;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Template-based prompt builder that creates context-aware prompts using semantic analysis.
 * Clean separation between templates and context data.
 */
public class EnhancedPromptBuilder {
    
    // Template constants
    private static final String COMPLETION_INSTRUCTION = 
        "🚨 CRITICAL: Only return NEW text to insert at cursor. Never repeat existing text.";
    
    // Base templates for different completion types
    private static final Map<CompletionType, PromptTemplate> TEMPLATES = new HashMap<>();
    
    static {
        TEMPLATES.put(CompletionType.GENERAL, new GeneralCompletionTemplate());
        TEMPLATES.put(CompletionType.MEMBER_ACCESS, new MemberAccessTemplate());
        TEMPLATES.put(CompletionType.CONSTRUCTOR_CALL, new ConstructorCallTemplate());
        TEMPLATES.put(CompletionType.CLASS_MEMBER_DECLARATION, new ClassMemberTemplate());
        TEMPLATES.put(CompletionType.STATEMENT_START, new StatementStartTemplate());
        TEMPLATES.put(CompletionType.EXPRESSION, new ExpressionTemplate());
    }
    
    /**
     * Builds a prompt using the semantic context.
     */
    public static String buildPrompt(@NotNull CompletionContext context) {
        PromptTemplate template = TEMPLATES.getOrDefault(context.completionType, TEMPLATES.get(CompletionType.GENERAL));
        return template.buildPrompt(context);
    }
    
    /**
     * Base interface for prompt templates.
     */
    interface PromptTemplate {
        String buildPrompt(CompletionContext context);
    }
    
    /**
     * Template for general code completion.
     */
    static class GeneralCompletionTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            // System instruction
            prompt.append("Complete code at <CURSOR>. Return only the new text to insert. Do not repeat existing text.\n\n");
            
            // Add semantic context
            appendSemanticContext(prompt, context);
            
            // Add the code to complete
            prompt.append("Complete:\n```java\n");
            prompt.append(context.localContext.beforeCursor);
            prompt.append("<CURSOR>");
            if (!context.localContext.afterCursor.trim().isEmpty()) {
                prompt.append(context.localContext.afterCursor);
            }
            prompt.append("\n```\n\n");
            
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    /**
     * Template for member access completion (after dot).
     */
    static class MemberAccessTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            prompt.append("Complete member access after dot operator. Return only the member name/call.\n\n");
            
            // Add class context if available
            if (context.semanticInfo.classContext != null) {
                appendClassStructure(prompt, context.semanticInfo.classContext);
            }
            
            // Add local variables context
            if (!context.semanticInfo.localScope.availableVariables.isEmpty()) {
                prompt.append("Available variables:\n");
                for (VariableInfo var : context.semanticInfo.localScope.availableVariables) {
                    prompt.append("- ").append(var.type).append(" ").append(var.name).append("\n");
                }
                prompt.append("\n");
            }
            
            appendCodeContext(prompt, context);
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    /**
     * Template for constructor call completion.
     */
    static class ConstructorCallTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            prompt.append("Complete constructor call after 'new' keyword. Return only the constructor call.\n\n");
            
            // Add available types
            if (!context.semanticInfo.localScope.availableTypes.isEmpty()) {
                prompt.append("Available types in scope:\n");
                for (String type : context.semanticInfo.localScope.availableTypes) {
                    prompt.append("- ").append(type).append("\n");
                }
                prompt.append("\n");
            }
            
            appendSemanticContext(prompt, context);
            appendCodeContext(prompt, context);
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    /**
     * Template for class member declarations.
     */
    static class ClassMemberTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            prompt.append("Complete class member declaration (field, method, constructor). Return only the declaration.\n\n");
            
            if (context.semanticInfo.classContext != null) {
                appendClassStructure(prompt, context.semanticInfo.classContext);
            }
            
            appendCodeContext(prompt, context);
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    /**
     * Template for statement start completion.
     */
    static class StatementStartTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            prompt.append("Complete statement at the beginning of line. Return only the statement.\n\n");
            
            // Add method context
            if (context.semanticInfo.methodContext != null) {
                appendMethodContext(prompt, context.semanticInfo.methodContext);
            }
            
            // Add local scope
            if (!context.semanticInfo.localScope.availableVariables.isEmpty()) {
                prompt.append("Variables in scope:\n");
                for (VariableInfo var : context.semanticInfo.localScope.availableVariables) {
                    prompt.append("- ").append(var.type).append(" ").append(var.name).append("\n");
                }
                prompt.append("\n");
            }
            
            appendCodeContext(prompt, context);
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    /**
     * Template for expression completion.
     */
    static class ExpressionTemplate implements PromptTemplate {
        @Override
        public String buildPrompt(CompletionContext context) {
            StringBuilder prompt = new StringBuilder();
            
            prompt.append("Complete expression. Return only the remaining expression text.\n\n");
            
            appendSemanticContext(prompt, context);
            appendCodeContext(prompt, context);
            prompt.append(COMPLETION_INSTRUCTION);
            
            return prompt.toString();
        }
    }
    
    // Helper methods for building context sections
    
    private static void appendSemanticContext(StringBuilder prompt, CompletionContext context) {
        // Package and imports
        if (!context.semanticInfo.packageName.isEmpty()) {
            prompt.append("Package: ").append(context.semanticInfo.packageName).append("\n");
        }
        
        if (!context.semanticInfo.imports.isEmpty()) {
            prompt.append("Key imports:\n");
            // Show only non-standard imports
            context.semanticInfo.imports.stream()
                .filter(imp -> !imp.startsWith("java.lang."))
                .limit(10)
                .forEach(imp -> prompt.append("import ").append(imp).append(";\n"));
            prompt.append("\n");
        }
        
        // Class context
        if (context.semanticInfo.classContext != null) {
            appendClassStructure(prompt, context.semanticInfo.classContext);
        }
        
        // Method context
        if (context.semanticInfo.methodContext != null) {
            appendMethodContext(prompt, context.semanticInfo.methodContext);
        }
    }
    
    private static void appendClassStructure(StringBuilder prompt, ClassContext classContext) {
        prompt.append("Current class: ");
        if (classContext.isInterface) {
            prompt.append("interface ");
        } else {
            prompt.append("class ");
        }
        prompt.append(classContext.className);
        
        if (classContext.superClass != null) {
            prompt.append(" extends ").append(classContext.superClass);
        }
        
        if (!classContext.interfaces.isEmpty()) {
            prompt.append(" implements ").append(String.join(", ", classContext.interfaces));
        }
        
        prompt.append(" {\n");
        
        // Show key fields
        if (!classContext.fields.isEmpty()) {
            prompt.append("  // Fields:\n");
            for (FieldInfo field : classContext.fields) {
                prompt.append("  ");
                if (field.isStatic) prompt.append("static ");
                if (field.isFinal) prompt.append("final ");
                prompt.append(field.type).append(" ").append(field.name).append(";\n");
            }
        }
        
        // Show method signatures
        if (!classContext.methods.isEmpty()) {
            prompt.append("  // Methods:\n");
            for (MethodSignature method : classContext.methods) {
                prompt.append("  ");
                if (method.isStatic) prompt.append("static ");
                prompt.append(method.returnType).append(" ").append(method.name).append("(");
                prompt.append(String.join(", ", method.parameterTypes));
                prompt.append(");\n");
            }
        }
        
        prompt.append("}\n\n");
    }
    
    private static void appendMethodContext(StringBuilder prompt, MethodContext methodContext) {
        prompt.append("Current method: ");
        if (methodContext.isStatic) prompt.append("static ");
        prompt.append(methodContext.returnType).append(" ").append(methodContext.methodName).append("(");
        
        StringJoiner paramJoiner = new StringJoiner(", ");
        for (ParameterInfo param : methodContext.parameters) {
            paramJoiner.add(param.type + " " + param.name);
        }
        prompt.append(paramJoiner.toString()).append(")\n\n");
    }
    
    private static void appendCodeContext(StringBuilder prompt, CompletionContext context) {
        prompt.append("Code context:\n```java\n");
        prompt.append(context.localContext.beforeCursor);
        prompt.append("<CURSOR>");
        if (!context.localContext.afterCursor.trim().isEmpty()) {
            prompt.append(context.localContext.afterCursor);
        }
        prompt.append("\n```\n\n");
    }
}
