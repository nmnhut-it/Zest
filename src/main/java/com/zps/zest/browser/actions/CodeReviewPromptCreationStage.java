package com.zps.zest.browser.actions;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Pipeline stage that creates a prompt for code review.
 * This stage analyzes the code in the context and generates
 * a structured prompt for AI-assisted code review.
 */
public class CodeReviewPromptCreationStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(CodeReviewPromptCreationStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Creating code review prompt");
        
        // Get the analyzed class information from the context
        String className = context.getClassName();
        String classContent = ReadAction.compute(()->context.getTargetClass().getText());
        
        if (className == null || classContent == null || className.isEmpty() || classContent.isEmpty()) {
            throw new PipelineExecutionException("Missing class information required for code review");
        }
        
        // Build the prompt for code review
        StringBuilder promptBuilder = new StringBuilder();
        
        // Add header with clear instructions
        promptBuilder.append("# CODE REVIEW REQUEST\n\n");
        promptBuilder.append("Please provide a detailed code review for the following class:\n\n");
        
        // Include class name and content
        promptBuilder.append("## Class: ").append(className).append("\n\n");
        promptBuilder.append("```java\n");
        promptBuilder.append(classContent);
        promptBuilder.append("\n```\n\n");
        
        // Specify review criteria
        promptBuilder.append("## Review Criteria\n\n");
        promptBuilder.append("Please analyze the code for:\n");
        promptBuilder.append("1. Code quality and readability\n");
        promptBuilder.append("2. Potential bugs or edge cases\n");
        promptBuilder.append("3. Performance considerations\n");
        promptBuilder.append("4. Best practices and design patterns\n");
        promptBuilder.append("5. Error handling and robustness\n");
        promptBuilder.append("6. Documentation completeness\n");
        
        // Request specific feedback format
        promptBuilder.append("\n## Requested Feedback Format\n\n");
        promptBuilder.append("- **Summary**: Overall assessment of the code\n");
        promptBuilder.append("- **Strengths**: What aspects of the code are well-implemented\n");
        promptBuilder.append("- **Issues**: Specific problems identified (with line numbers)\n");
        promptBuilder.append("- **Recommendations**: Suggested improvements with code examples\n");
        
        // Store the generated prompt in the context
        context.setPrompt(promptBuilder.toString());
        LOG.info("Code review prompt creation completed");
    }
}