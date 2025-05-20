package com.zps.zest.refactoring;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;
import com.zps.zest.refactoring.RefactoringPlan;
import com.zps.zest.refactoring.RefactoringStateManager;

import java.util.Date;

/**
 * Stage for planning the refactoring operation based on analysis results.
 * Creates a structured plan for addressing testability issues.
 */
public class RefactoringPlanningStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(RefactoringPlanningStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Planning refactoring for testability");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get class info and context from previous stages
        String classContext = context.getClassContext();
        String className = context.getClassName();
        if (classContext == null || classContext.isEmpty() || className == null || className.isEmpty()) {
            throw new PipelineExecutionException("Class context or name not available");
        }
        
        // Create a refactoring state manager
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        
        // If there's an existing refactoring in progress, prompt the user to resume or start fresh
        if (stateManager.isRefactoringInProgress()) {
            // In an actual implementation, we would show a dialog here.
            // For now, we'll just log a message and continue with a new refactoring.
            LOG.info("Existing refactoring in progress - would normally prompt user to resume or start fresh");
            stateManager.clearRefactoringState();
        }
        
        // Create the refactoring plan
        RefactoringPlan plan = new RefactoringPlan(
                "Refactor " + className + " for Testability",
                className,
                "Refactoring plan to improve testability of " + className
        );
        
        // Save the initial empty plan
        if (!stateManager.savePlan(plan)) {
            throw new PipelineExecutionException("Failed to save initial refactoring plan");
        }
        
        // Create and save the progress
        RefactoringProgress progress = new RefactoringProgress(plan.getName());
        if (!stateManager.saveProgress(progress)) {
            throw new PipelineExecutionException("Failed to save initial refactoring progress");
        }
        
        // Build the prompt from template or use built-in prompt
        String prompt = createPlanningPrompt(classContext);
        
        // Store the prompt in the context
        context.setPrompt(prompt);
        
        LOG.info("Refactoring planning stage completed successfully");
    }
    
    /**
     * Creates a planning prompt either from a template file or using a built-in template.
     */
    private String createPlanningPrompt(String classContext) {
        try {
            // Try to load the planning prompt from resource template
            String templatePath = "/templates/planning.template";
            
            java.io.InputStream inputStream = getClass().getResourceAsStream(templatePath);
            if (inputStream != null) {
                String template = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();
                
                // Replace the class context placeholder
                return template.replace("${classContext}", classContext);
            } else {
                LOG.warn("Planning template resource not found: " + templatePath + ". Using built-in template.");
                return createBuiltInPlanningPrompt(classContext);
            }
        } catch (Exception e) {
            LOG.error("Error creating planning prompt from template", e);
            // Fall back to built-in template
            return createBuiltInPlanningPrompt(classContext);
        }
    }
    
    /**
     * Creates a built-in planning prompt as a fallback.
     */
    private String createBuiltInPlanningPrompt(String classContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Expert Java Testability Refactoring Planner\n\n");
        
        // Set up expert persona
        prompt.append("You are a world-class Java architect specializing in software testability, with deep expertise in refactoring patterns, design principles, and test-driven development practices. Your task is to analyze a Java class and create a comprehensive refactoring plan to improve its testability.\n\n");
        
        // Instructions for creating a testability refactoring plan
        prompt.append("## Instructions\n\n");
        prompt.append("Analyze the provided Java class and create a detailed, step-by-step plan for refactoring it to improve testability. Follow this specific process:\n\n");
        
        // Analysis phase
        prompt.append("### Phase 1: Analysis\n");
        prompt.append("1. First, carefully read and understand the entire class\n");
        prompt.append("2. Think step-by-step about what makes this code difficult to test\n");
        prompt.append("3. Identify specific testability issues, prioritizing based on severity\n");
        prompt.append("4. Only include issues that actually need improvement - skip aspects that are already well-designed for testability\n");
        prompt.append("5. Pay special attention to common anti-patterns:\n");
        prompt.append("   - Hard-coded dependencies\n");
        prompt.append("   - Static dependencies\n");
        prompt.append("   - External resource access\n");
        prompt.append("   - Singletons\n");
        prompt.append("   - Global state\n");
        prompt.append("   - Final classes/methods\n");
        prompt.append("   - Complex methods\n");
        prompt.append("   - Lack of dependency injection\n\n");
        
        // Planning phase 
        prompt.append("### Phase 2: Planning\n");
        prompt.append("Create a structured refactoring plan in JSON format with this exact schema:\n\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"issues\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": <integer>,\n");
        prompt.append("      \"title\": \"<concise issue title>\",\n");
        prompt.append("      \"description\": \"<detailed description of the testability issue>\",\n");
        prompt.append("      \"category\": \"<Dependency Injection|Static Dependencies|External Resources|etc.>\",\n");
        prompt.append("      \"impact\": \"<how this impacts testability>\",\n");
        prompt.append("      \"reasoning\": \"<your analysis of why this is an issue and how to address it>\",\n");
        prompt.append("      \"targetFile\": \"<absolute path to the file containing this issue>\",\n");
        prompt.append("      \"steps\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"id\": <integer>,\n");
        prompt.append("          \"title\": \"<concise step title>\",\n");
        prompt.append("          \"description\": \"<what this step accomplishes>\",\n");
        prompt.append("          \"filePath\": \"<path to file if known>\",\n");
        prompt.append("          \"codeChangeDescription\": \"<precise description of required code change>\",\n");
        prompt.append("          \"before\": \"<code snippet before change>\",\n");
        prompt.append("          \"after\": \"<suggested code snippet after change>\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        
        // Important guidelines
        prompt.append("## Important Guidelines\n");
        prompt.append("- Only include issues where the code actually needs improvement - if the code is already good for a particular testability factor, skip it and don't include it in the plan\n");
        prompt.append("- Prioritize changes that will have the greatest impact on testability\n");
        prompt.append("- Each step should handle multiple related changes (up to 10) but not exceed 20 lines of code change\n");
        prompt.append("- Group logical changes together rather than creating overly fine-grained steps\n");
        prompt.append("- Suggest concrete 'before' and 'after' code snippets for each step\n");
        prompt.append("- Ensure that all suggested changes preserve the original functionality\n");
        prompt.append("- Use standard Java design patterns when appropriate (e.g., dependency injection, factory pattern)\n");
        prompt.append("- Consider testability impact within the broader context of the codebase\n");
        prompt.append("- Keep refactoring steps cohesive but not too small\n\n");
        
        prompt.append("## Class to Analyze\n\n");
        prompt.append("```java\n");
        prompt.append(classContext);
        prompt.append("\n```\n\n");
        
        prompt.append("Now, analyze this class and produce a detailed refactoring plan following the instructions above. Be thorough, precise, and focus exclusively on testability concerns.");
        
        return prompt.toString();
    }
}
