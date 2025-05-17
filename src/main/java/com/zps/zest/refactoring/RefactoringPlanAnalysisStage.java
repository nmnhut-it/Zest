package com.zps.zest.refactoring;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage for analyzing the LLM's response and extracting the refactoring plan.
 * Parses the JSON plan and updates the refactoring state.
 */
public class RefactoringPlanAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(RefactoringPlanAnalysisStage.class);
    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Analyzing refactoring plan from LLM response");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get the API response from the context
        String apiResponse = context.getApiResponse();
        if (apiResponse == null || apiResponse.isEmpty()) {
            throw new PipelineExecutionException("API response is empty or null");
        }
        
        // Extract JSON from the response (it might be embedded in markdown code blocks)
        JsonObject planJson = extractJsonFromResponse(apiResponse);
        if (planJson == null) {
            throw new PipelineExecutionException("Failed to extract refactoring plan JSON from response");
        }
        
        LOG.info("Extracted refactoring plan JSON: " + planJson);
        
        // Get the refactoring state manager
        RefactoringStateManager stateManager = new RefactoringStateManager(project);
        
        // Load the existing plan
        RefactoringPlan plan = stateManager.loadPlan();
        if (plan == null) {
            throw new PipelineExecutionException("Failed to load refactoring plan");
        }
        
        // Parse and update the plan with issues from the JSON
        try {
            if (planJson.has("issues") && planJson.get("issues").isJsonArray()) {
                JsonArray issuesArray = planJson.getAsJsonArray("issues");
                for (JsonElement issueElement : issuesArray) {
                    if (issueElement.isJsonObject()) {
                        JsonObject issueJson = issueElement.getAsJsonObject();
                        plan.addIssue(parseIssue(issueJson));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error parsing refactoring plan JSON", e);
            throw new PipelineExecutionException("Failed to parse refactoring plan: " + e.getMessage());
        }
        
        // Save the updated plan
        if (!stateManager.savePlan(plan)) {
            throw new PipelineExecutionException("Failed to save updated refactoring plan");
        }
        
        // Report the number of issues and steps found
        int issueCount = plan.getIssues().size();
        int stepCount = plan.getTotalStepCount();
        
        LOG.info("Refactoring plan analysis complete. Found " + issueCount + " issues with " + stepCount + " steps total.");
        
        // Update the context with a summary for the next stage
        StringBuilder summary = new StringBuilder();
        summary.append("# Refactoring Plan for ").append(plan.getTargetClass()).append("\n\n");
        summary.append("Found **").append(issueCount).append("** testability issues requiring **")
               .append(stepCount).append("** refactoring steps.\n\n");
        
        for (RefactoringIssue issue : plan.getIssues()) {
            summary.append("## Issue ").append(issue.getId()).append(": ").append(issue.getTitle()).append("\n");
            summary.append("**Category:** ").append(issue.getCategory()).append("\n\n");
            summary.append(issue.getDescription()).append("\n\n");
            summary.append("**Steps:**\n");
            
            for (RefactoringStep step : issue.getSteps()) {
                summary.append("- ").append(step.getTitle()).append("\n");
            }
            summary.append("\n");
        }
        
        summary.append("Would you like to proceed with the refactoring?");
        
        // Store the summary in the context
        context.setPrompt(summary.toString());
    }
    
    /**
     * Extracts JSON from the LLM response, which may be embedded in markdown code blocks.
     */
    private JsonObject extractJsonFromResponse(String response) {
        // First try to find JSON in markdown code blocks
        Matcher matcher = JSON_PATTERN.matcher(response);
        if (matcher.find()) {
            String json = matcher.group(1);
            try {
                return JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                LOG.warn("Failed to parse JSON from markdown code block", e);
            }
        }
        
        // If that fails, try to parse the entire response as JSON
        try {
            return JsonParser.parseString(response).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Failed to parse entire response as JSON", e);
        }
        
        // If both approaches fail, try to extract JSON object using a more lenient approach
        try {
            int startIdx = response.indexOf('{');
            int endIdx = response.lastIndexOf('}') + 1;
            
            if (startIdx >= 0 && endIdx > startIdx) {
                String jsonCandidate = response.substring(startIdx, endIdx);
                return JsonParser.parseString(jsonCandidate).getAsJsonObject();
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract JSON using lenient approach", e);
        }
        
        return null;
    }
    
    /**
     * Parses an issue from a JSON object.
     */
    private RefactoringIssue parseIssue(JsonObject issueJson) {
        int id = issueJson.has("id") ? issueJson.get("id").getAsInt() : 0;
        String title = issueJson.has("title") ? issueJson.get("title").getAsString() : "Untitled Issue";
        String description = issueJson.has("description") ? issueJson.get("description").getAsString() : "";
        String category = issueJson.has("category") ? issueJson.get("category").getAsString() : "General";
        
        RefactoringIssue issue = new RefactoringIssue(id, title, description, category);
        
        if (issueJson.has("impact")) {
            issue.setImpact(issueJson.get("impact").getAsString());
        }
        
        // Parse steps
        if (issueJson.has("steps") && issueJson.get("steps").isJsonArray()) {
            JsonArray stepsArray = issueJson.getAsJsonArray("steps");
            for (JsonElement stepElement : stepsArray) {
                if (stepElement.isJsonObject()) {
                    issue.addStep(parseStep(stepElement.getAsJsonObject(), id));
                }
            }
        }
        
        return issue;
    }
    
    /**
     * Parses a step from a JSON object.
     */
    private RefactoringStep parseStep(JsonObject stepJson, int issueId) {
        int id = stepJson.has("id") ? stepJson.get("id").getAsInt() : 0;
        String title = stepJson.has("title") ? stepJson.get("title").getAsString() : "Untitled Step";
        String description = stepJson.has("description") ? stepJson.get("description").getAsString() : "";
        
        RefactoringStep step = new RefactoringStep(id, issueId, title, description);
        
        if (stepJson.has("filePath")) {
            step.setFilePath(stepJson.get("filePath").getAsString());
        }
        
        if (stepJson.has("codeChangeDescription")) {
            step.setCodeChangeDescription(stepJson.get("codeChangeDescription").getAsString());
        }
        
        if (stepJson.has("before")) {
            step.setBefore(stepJson.get("before").getAsString());
        }
        
        if (stepJson.has("after")) {
            step.setAfter(stepJson.get("after").getAsString());
        }
        
        return step;
    }
}
