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
        promptBuilder.append("# CODE REVIEW REQUEST - ").append(language.toUpperCase()).append("\n\n");
        promptBuilder.append("Please provide a detailed code review for the following ").append(structureType).append(":\n\n");

        // Include structure name and content
        promptBuilder.append("## ").append(structureType.substring(0, 1).toUpperCase()).append(structureType.substring(1))
                .append(": ").append(targetName).append("\n\n");

        if (frameworkContext != null && !frameworkContext.isEmpty()) {
            promptBuilder.append("**Framework Context**: ").append(frameworkContext).append("\n\n");
        }

        promptBuilder.append("```").append(language.toLowerCase()).append("\n");
        promptBuilder.append(targetContent);
        promptBuilder.append("\n```\n\n");

        // Add imports context if available
        String imports = context.getImports();
        if (imports != null && !imports.trim().isEmpty()) {
            promptBuilder.append("## Imports/Dependencies\n\n");
            promptBuilder.append("```").append(language.toLowerCase()).append("\n");
            promptBuilder.append(imports);
            promptBuilder.append("\n```\n\n");
        }

        // Add code analysis context
        String classContext = context.getClassContext();
        if (classContext != null && !classContext.trim().isEmpty()) {
            promptBuilder.append("## Code Analysis Context\n\n");
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
        promptBuilder.append("   - Unnecessary re-renders (for React)\n");
        promptBuilder.append("   - Bundle size implications\n\n");

        promptBuilder.append("4. **Best Practices**\n");
        promptBuilder.append("   - Error handling patterns\n");
        promptBuilder.append("   - Security considerations (XSS, input validation)\n");
        promptBuilder.append("   - Testing patterns and testability\n");

        if (frameworkContext != null) {
            promptBuilder.append("   - Framework-specific best practices\n");
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
