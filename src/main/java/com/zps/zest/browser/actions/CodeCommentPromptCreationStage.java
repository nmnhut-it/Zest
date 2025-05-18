package com.zps.zest.browser.actions;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Pipeline stage that creates a prompt for generating code comments.
 * This stage analyzes the code in the context and generates
 * a structured prompt for AI-assisted comment generation.
 */
public class CodeCommentPromptCreationStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(CodeCommentPromptCreationStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Creating code comment generation prompt");

        // Get the analyzed class information from the context
        String className = context.getClassName();
        String selectedText = ReadAction.compute(()->context.getEditor().getSelectionModel().getSelectedText());
        context.setSelectedText(selectedText);
        if (selectedText == null || selectedText.isEmpty()) {
            throw new PipelineExecutionException("No code selected for comment generation");
        }

        // Build the prompt for code comment generation
        StringBuilder promptBuilder = new StringBuilder();

        // Add header with clear instructions
        promptBuilder.append("# CODE COMMENT GENERATION REQUEST\n\n");
        promptBuilder.append("Please generate comments for the following Java code selection.\n\n");

        // Include content with file extension information
        promptBuilder.append("## Selected code from: ").append(className != null ? className : "Unknown class").append("\n\n");
        promptBuilder.append("```")
                .append(context.getPsiFile().getFileType().getDefaultExtension())
                .append("\n");
        promptBuilder.append(selectedText);
        promptBuilder.append("\n```\n\n");

        // Specify comment requirements
        promptBuilder.append("## Comment Requirements\n\n");
        promptBuilder.append("Please provide:\n");
        promptBuilder.append("1. Method-level Javadoc comments if method declarations exist in selection\n");
        promptBuilder.append("2. Field/variable comments where appropriate\n");
        promptBuilder.append("3. Inline comments for complex code sections\n");
        promptBuilder.append("4. Any TODO comments for potential improvements\n\n");

        // Specify comment style
        promptBuilder.append("## Comment Style Guidelines\n\n");
        promptBuilder.append("- Follow standard Javadoc conventions (@param, @return, @throws, etc.)\n");
        promptBuilder.append("- Be concise but informative\n");
        promptBuilder.append("- Explain 'why' not just 'what' the code does\n");
        promptBuilder.append("- Maintain consistent style across all comments\n");
        promptBuilder.append("- Use proper grammar and capitalization\n");
        promptBuilder.append("- DO NOT add comment if the code line has good readability and can explain itself\n\n");

        // Request specific output format
        promptBuilder.append("## Requested Output Format\n\n");
        promptBuilder.append("- Provide the fully commented code ready for direct implementation. \n");
        promptBuilder.append("- Include ONLY the code with proper comments - no explanations or additional text. \n");
        promptBuilder.append("- Maintain all existing code behavior exactly as is.\n\n");

        // Store the generated prompt in the context
        context.setPrompt(promptBuilder.toString());
        LOG.info("Code comment prompt creation completed");
    }
}