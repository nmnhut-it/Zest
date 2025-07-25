package com.zps.zest;

import com.intellij.openapi.application.ReadAction;
import com.zps.zest.completion.context.ZestMethodContextCollector;

/**
 * Stage for creating test generation prompts for a single JavaScript/TypeScript method or function.
 */
public class JsMethodTestPromptCreationStage implements PipelineStage {
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        // Check if we're in single method mode
        if (!context.isSingleMethodMode() || context.getTargetMethodContext() == null) {
            throw new PipelineExecutionException("Not in single method mode or no method context provided");
        }
        
        ZestMethodContextCollector.MethodContext methodContext = context.getTargetMethodContext();
        
        // Build the prompt in a read action
        String prompt = ReadAction.compute(() -> buildJsMethodTestPrompt(context, methodContext));
        context.setPrompt(prompt);
    }
    
    private String buildJsMethodTestPrompt(CodeContext context, ZestMethodContextCollector.MethodContext methodContext) {
        StringBuilder prompt = new StringBuilder();
        
        // Header
        prompt.append("Generate comprehensive unit tests for the following JavaScript/TypeScript function:\n\n");
        
        // File and function context
        prompt.append("## Context\n\n");
        if (context.getPsiFile() != null && context.getPsiFile().getVirtualFile() != null) {
            String fileName = context.getPsiFile().getVirtualFile().getName();
            prompt.append("**File**: `").append(context.getPsiFile().getVirtualFile().getPath()).append("`\n");
            prompt.append("**Language**: ").append(getLanguageFromFileName(fileName)).append("\n");
        }
        prompt.append("**Function**: `").append(methodContext.getMethodName()).append("`\n\n");
        
        // Add the target function/method
        prompt.append("## Target Function\n```");
        prompt.append(context.getLanguage() != null ? context.getLanguage() : "javascript");
        prompt.append("\n");
        prompt.append(methodContext.getMethodContent());
        prompt.append("\n```\n\n");
        
        // Add imports/dependencies if available
        if (context.getImports() != null && !context.getImports().isEmpty()) {
            prompt.append("## Imports/Dependencies\n```");
            prompt.append(context.getLanguage() != null ? context.getLanguage() : "javascript");
            prompt.append("\n");
            prompt.append(context.getImports());
            prompt.append("```\n\n");
        }
        
        // Add surrounding context if available
        if (context.getTargetContent() != null && !context.getTargetContent().isEmpty()) {
            prompt.append("## Module Context\n```");
            prompt.append(context.getLanguage() != null ? context.getLanguage() : "javascript");
            prompt.append("\n");
            prompt.append(context.getTargetContent());
            prompt.append("```\n\n");
        }
        
        // Framework information
        prompt.append("## Testing Framework\n");
        String testFramework = context.getTestFramework();
        if (testFramework != null) {
            prompt.append("- **Test Framework**: ").append(testFramework).append("\n");
        } else {
            prompt.append("- **Test Framework**: Jest (default)\n");
        }
        
        String frameworkContext = context.getFrameworkContext();
        if (frameworkContext != null) {
            prompt.append("- **Framework**: ").append(frameworkContext).append("\n");
        }
        prompt.append("\n");
        
        // Test requirements
        prompt.append("## Requirements\n");
        prompt.append("1. Create comprehensive unit tests for the `").append(methodContext.getMethodName()).append("` function\n");
        prompt.append("2. Cover all code paths and edge cases\n");
        prompt.append("3. Test both success and error scenarios\n");
        prompt.append("4. Mock external dependencies and API calls\n");
        prompt.append("5. Use descriptive test names using 'describe' and 'it' blocks\n");
        prompt.append("6. Include setup and teardown if needed\n");
        prompt.append("7. Test async behavior if the function is asynchronous\n");
        prompt.append("8. Follow the project's existing test patterns\n\n");
        
        // Instructions
        prompt.append("## Instructions\n");
        prompt.append("Generate test code that:\n");
        prompt.append("- Uses ").append(testFramework != null ? testFramework : "Jest").append(" testing framework\n");
        prompt.append("- Can be added to a test file for this module\n");
        prompt.append("- Includes all necessary imports and mocks\n");
        prompt.append("- Has clear test descriptions and assertions\n");
        prompt.append("- Handles both synchronous and asynchronous scenarios appropriately\n");
        
        if (frameworkContext != null && frameworkContext.toLowerCase().contains("react")) {
            prompt.append("- Uses React Testing Library for component testing if applicable\n");
        }
        
        return prompt.toString();
    }
    
    private String getLanguageFromFileName(String fileName) {
        if (fileName.endsWith(".ts") || fileName.endsWith(".tsx")) {
            return "TypeScript";
        } else if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) {
            return "JavaScript";
        }
        return "JavaScript";
    }
}