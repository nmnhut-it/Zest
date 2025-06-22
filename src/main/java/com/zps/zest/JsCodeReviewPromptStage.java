package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Stage for creating code review prompts specifically for JavaScript/TypeScript code.
 * Tailored to JS/TS patterns, frameworks, and best practices.
 */
public class JsCodeReviewPromptStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(JsCodeReviewPromptStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Creating JS/TS code review prompt");

        String targetName = context.getClassName();
        String targetContent = context.getTargetContent();
        String structureType = context.getStructureType();
        String language = context.getLanguage();
        String frameworkContext = context.getFrameworkContext();

        if (targetName == null || targetContent == null || targetContent.trim().isEmpty()) {
            throw new PipelineExecutionException("Missing code structure information required for code review");
        }

        // Build the prompt for JS/TS code review
        StringBuilder promptBuilder = new StringBuilder();

        // Add header with clear instructions
        promptBuilder.append("# CODE REVIEW REQUEST - ").append(language.toUpperCase()).append(" FILE\n\n");
        promptBuilder.append("Please provide a detailed code review for the following ").append(language).append(" file:\n\n");

        // Include file name and content
        promptBuilder.append("## File: ").append(targetName).append(".").append(language.toLowerCase().substring(0, 2)).append("\n\n");

        if (frameworkContext != null && !frameworkContext.isEmpty()) {
            promptBuilder.append("**Framework Context**: ").append(frameworkContext).append("\n\n");
        }

        promptBuilder.append("```").append(language.toLowerCase()).append("\n");
        promptBuilder.append(targetContent);
        promptBuilder.append("\n```\n\n");

        // Add code analysis context if available (since we have full file, this provides structure overview)
        String classContext = context.getClassContext();
        if (classContext != null && !classContext.trim().isEmpty()) {
            promptBuilder.append("## File Structure Analysis\n\n");
            promptBuilder.append("```\n");
            promptBuilder.append(classContext);
            promptBuilder.append("\n```\n\n");
        }

        // Specify review criteria specific to JS/TS
        promptBuilder.append("## Review Criteria\n\n");
        promptBuilder.append("Please analyze the code for:\n");
        promptBuilder.append("1. **Code Quality & Readability**\n");
        promptBuilder.append("   - Variable naming and function structure\n");
        promptBuilder.append("   - Code organization and modularity\n");
        promptBuilder.append("   - Use of modern ").append(language).append(" features\n\n");

        promptBuilder.append("2. **Potential Issues**\n");
        promptBuilder.append("   - Type safety concerns (especially for TypeScript)\n");
        promptBuilder.append("   - Null/undefined handling\n");
        promptBuilder.append("   - Memory leaks (event listeners, timers)\n");
        promptBuilder.append("   - Async/await vs Promise chains\n\n");

        promptBuilder.append("3. **Performance Considerations**\n");
        promptBuilder.append("   - Inefficient loops or operations\n");
        if (frameworkContext != null && frameworkContext.toLowerCase().contains("cocos")) {
            promptBuilder.append("   - Frame rate optimization (60 FPS target)\n");
            promptBuilder.append("   - Texture memory usage and sprite batching\n");
            promptBuilder.append("   - Action performance and object pooling\n");
            promptBuilder.append("   - Unnecessary node updates or calculations\n");
        } else if (frameworkContext != null && frameworkContext.toLowerCase().contains("react")) {
            promptBuilder.append("   - Unnecessary re-renders (for React)\n");
        } else {
            promptBuilder.append("   - Memory leaks and resource management\n");
        }
        promptBuilder.append("   - Bundle size implications\n\n");

        promptBuilder.append("4. **Best Practices**\n");
        promptBuilder.append("   - Error handling patterns\n");
        promptBuilder.append("   - Security considerations (XSS, input validation)\n");
        promptBuilder.append("   - Testing patterns and testability\n");

        if (frameworkContext != null) {
            if (frameworkContext.toLowerCase().contains("cocos")) {
                promptBuilder.append("   - Cocos2d-x best practices:\n");
                promptBuilder.append("     * Proper memory management and cleanup\n");
                promptBuilder.append("     * Efficient sprite and texture usage\n");
                promptBuilder.append("     * Appropriate use of object pooling\n");
                promptBuilder.append("     * Scene transition patterns\n");
                promptBuilder.append("     * Event listener cleanup in onExit\n");
            } else {
                promptBuilder.append("   - Framework-specific best practices\n");
            }
        }
        promptBuilder.append("\n");

        promptBuilder.append("5. **Modern Standards**\n");
        promptBuilder.append("   - ES6+ features usage\n");
        promptBuilder.append("   - Functional vs OOP patterns\n");
        promptBuilder.append("   - Code splitting and lazy loading opportunities\n\n");

        // Request specific feedback format
        promptBuilder.append("## Requested Feedback Format\n\n");
        promptBuilder.append("- **Summary**: Overall assessment of the ").append(structureType).append("\n");
        promptBuilder.append("- **Strengths**: Well-implemented aspects\n");
        promptBuilder.append("- **Issues**: Specific problems identified (with line references)\n");
        promptBuilder.append("- **Recommendations**: Suggested improvements with code examples\n");
        promptBuilder.append("- **Security**: Any security concerns or improvements\n");
        promptBuilder.append("- **Performance**: Performance optimization opportunities\n");

        if (language.equals("TypeScript")) {
            promptBuilder.append("- **Type Safety**: TypeScript-specific type improvements\n");
        }

        if (frameworkContext != null && !frameworkContext.isEmpty()) {
            promptBuilder.append("- **Framework Specific**: ").append(frameworkContext).append(" best practices\n");
        }

        promptBuilder.append("\n");

        // Store the generated prompt in the context
        context.setPrompt(promptBuilder.toString());
        LOG.info("JS/TS code review prompt creation completed");
    }
}
