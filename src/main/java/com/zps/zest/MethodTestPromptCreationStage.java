package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.zps.zest.completion.context.ZestMethodContextCollector;

/**
 * Stage for creating test generation prompts for a single method.
 * Uses the class analysis from previous stages but focuses on a specific method.
 */
public class MethodTestPromptCreationStage implements PipelineStage {
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        // Check if we're in single method mode
        if (!context.isSingleMethodMode() || context.getTargetMethodContext() == null) {
            throw new PipelineExecutionException("Not in single method mode or no method context provided");
        }
        
        ZestMethodContextCollector.MethodContext methodContext = context.getTargetMethodContext();
        
        // Build the prompt in a read action to ensure thread safety
        String prompt = ReadAction.compute(() -> {
            PsiClass targetClass = context.getTargetClass();
            
            if (targetClass == null) {
                throw new RuntimeException("No target class found for method test generation");
            }
            
            // Build the prompt using PromptDrafter but with method-specific focus
            PromptDrafter drafter = new PromptDrafter();
            return buildMethodTestPrompt(context, targetClass, methodContext, drafter);
        });
        
        context.setPrompt(prompt);
    }
    
    private String buildMethodTestPrompt(
            CodeContext context, 
            PsiClass targetClass, 
            ZestMethodContextCollector.MethodContext methodContext,
            PromptDrafter drafter
    ) {
        StringBuilder prompt = new StringBuilder();
        
        // Header
        prompt.append("Generate comprehensive unit tests for the following method:\n\n");
        
        // File and class context - safe to access these as they're already computed
        prompt.append("## Context\n\n");
        if (context.getPsiFile() != null && context.getPsiFile().getVirtualFile() != null) {
            prompt.append("**File**: `").append(context.getPsiFile().getVirtualFile().getPath()).append("`\n");
        }
        prompt.append("**Class**: `").append(targetClass.getName()).append("`\n");
        prompt.append("**Method**: `").append(methodContext.getMethodName()).append("`\n\n");
        
        // Add imports
        if (context.getImports() != null && !context.getImports().isEmpty()) {
            prompt.append("## Imports\n```java\n");
            prompt.append(context.getImports());
            prompt.append("```\n\n");
        }
        
        // Add the target method
        prompt.append("## Target Method\n```java\n");
        prompt.append(methodContext.getMethodContent());
        prompt.append("\n```\n\n");
        
        // Add class context (fields, related methods)
        if (context.getClassContext() != null && !context.getClassContext().isEmpty()) {
            prompt.append("## Class Context\n");
            prompt.append(context.getClassContext());
            prompt.append("\n");
        }
        
        // Add framework information
        prompt.append("## Testing Framework\n");
        prompt.append("- **JUnit Version**: ").append(context.detectJUnitVersion()).append("\n");
        prompt.append("- **Mockito Available**: ").append(context.checkMockitoAvailable() ? "Yes" : "No").append("\n");
        prompt.append("- **Build Tool**: ").append(context.getBuildTool()).append("\n");
        prompt.append("- **Assertion Style**: ").append(context.getRecommendedAssertionStyle()).append("\n\n");
        
        // Add test requirements specific to single method
        prompt.append("## Requirements\n");
        prompt.append("1. Create comprehensive unit tests for the `").append(methodContext.getMethodName()).append("` method\n");
        prompt.append("2. Cover all code paths and branches\n");
        prompt.append("3. Include edge cases and error scenarios\n");
        prompt.append("4. Use appropriate mocking for dependencies\n");
        prompt.append("5. Follow the project's existing test patterns\n");
        prompt.append("6. Use descriptive test method names that clearly indicate what is being tested\n");
        prompt.append("7. Include both positive and negative test cases\n\n");
        
        // Check for existing test class
        if (context.getExistingTestClass() != null) {
            prompt.append("## Existing Test Class\n");
            prompt.append("Add the new test methods to the existing test class: `")
                  .append(context.getExistingTestClass().getName()).append("`\n\n");
            
            if (context.getTestClassStructure() != null) {
                prompt.append("### Existing Test Structure\n```java\n");
                prompt.append(context.getTestClassStructure());
                prompt.append("\n```\n\n");
            }
        } else {
            prompt.append("## Test Class\n");
            prompt.append("Create test methods that can be added to a test class for `")
                  .append(targetClass.getName()).append("`\n\n");
        }
        
        // Add specific instructions
        prompt.append("## Instructions\n");
        prompt.append("Generate test methods that:\n");
        prompt.append("- Are focused specifically on testing the `").append(methodContext.getMethodName()).append("` method\n");
        prompt.append("- Can be easily integrated into the existing test suite\n");
        prompt.append("- Include setup/teardown if needed\n");
        prompt.append("- Have clear assertions and error messages\n");
        
        return prompt.toString();
    }
}