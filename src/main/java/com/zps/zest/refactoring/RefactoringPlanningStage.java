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
        
        // Save the context for later use
        JsonObject contextJson = new JsonObject();
        contextJson.addProperty("className", className);
        contextJson.addProperty("packageName", context.getPackageName());
        contextJson.addProperty("classContext", classContext);
        contextJson.addProperty("imports", context.getImports());
        
        if (!stateManager.saveContext(contextJson)) {
            throw new PipelineExecutionException("Failed to save refactoring context");
        }
        
        // Build the prompt for the LLM to analyze testability issues and create a plan
        StringBuilder prompt = new StringBuilder();
        prompt.append("# AI-Assisted Testability Refactoring Plan\n\n");
        
        // Instructions for creating a testability refactoring plan
        prompt.append("## Instructions\n");
        prompt.append("Analyze the provided Java class and create a detailed plan for refactoring it to improve testability. Follow these steps:\n\n");
        
        // Analysis phase
        prompt.append("### Phase 1: Testability Analysis\n");
        prompt.append("1. Analyze the class for testability issues\n");
        prompt.append("2. Identify specific code that presents testability challenges\n");
        prompt.append("3. Categorize each issue using standard testability criteria\n\n");
        
        // Planning phase 
        prompt.append("### Phase 2: Refactoring Plan Creation\n");
        prompt.append("Create a detailed, structured refactoring plan in JSON format with the following components:\n\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"issues\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": 1,\n");
        prompt.append("      \"title\": \"Issue title\",\n");
        prompt.append("      \"description\": \"Detailed description of the testability issue\",\n");
        prompt.append("      \"category\": \"Dependency Injection|Static Dependencies|External Resources|etc.\",\n");
        prompt.append("      \"impact\": \"How this impacts testability\",\n");
        prompt.append("      \"steps\": [\n");
        prompt.append("        {\n");
        prompt.append("          \"id\": 1,\n");
        prompt.append("          \"title\": \"Step title\",\n");
        prompt.append("          \"description\": \"What this step accomplishes\",\n");
        prompt.append("          \"filePath\": \"Path to the file that needs changes (if known)\",\n");
        prompt.append("          \"codeChangeDescription\": \"Description of the code change\",\n");
        prompt.append("          \"before\": \"Code before change (if applicable)\",\n");
        prompt.append("          \"after\": \"Code after change (if applicable)\"\n");
        prompt.append("        }\n");
        prompt.append("      ]\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        
        // Include common testability criteria
        prompt.append("### Common Testability Issues to Look For:\n");
        prompt.append("- **Dependency Injection**: Hard-coded dependencies\n");
        prompt.append("- **Static Dependencies**: Use of static methods/classes that are difficult to mock\n");
        prompt.append("- **External Resources**: Directly accessing files, databases, network resources\n");
        prompt.append("- **Singleton Usage**: Reliance on singleton patterns\n");
        prompt.append("- **Complex Methods**: Methods that are too long or do too many things\n");
        prompt.append("- **Global State**: Reliance on global state or static variables\n");
        prompt.append("- **Final Classes/Methods**: Classes or methods that cannot be extended or overridden\n");
        prompt.append("- **Private Methods**: Critical logic in private methods that are hard to test\n\n");
        
        prompt.append("### Class to Analyze:\n\n");
        prompt.append("```java\n");
        prompt.append(classContext);
        prompt.append("\n```\n");
        
        // Store the prompt in the context
        context.setPrompt(prompt.toString());
        
        LOG.info("Refactoring planning stage completed successfully");
    }
}
