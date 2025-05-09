package com.zps.zest;

/**
 * Stage for creating the prompt to send to the LLM.
 */
public class TestPromptCreationStage implements PipelineStage {
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        // Create prompt for test generation using PromptDrafter
        String prompt = PromptDrafter.createPrompt(
                context.getPackageName(),
                context.getClassName(),
                context.getImports(),
                context.getJunitVersion(),
                context.getClassContext(),
                context.isMockitoPresent()
        );

        context.setPrompt(prompt);
        System.out.println(prompt);
    }
}
