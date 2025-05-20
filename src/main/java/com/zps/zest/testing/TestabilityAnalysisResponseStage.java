package com.zps.zest.testing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for analyzing the LLM's testability response and determining whether to proceed.
 * Handles rejection of poorly testable classes and proceeds to planning for acceptable classes.
 */
public class TestabilityAnalysisResponseStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestabilityAnalysisResponseStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Processing testability analysis response");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get the API response from the context
        String apiResponse = context.getApiResponse();
        if (apiResponse == null || apiResponse.isEmpty()) {
            throw new PipelineExecutionException("API response is empty or null");
        }
        
        // Extract JSON from the response
        JsonObject analysisJson = extractJsonFromResponse(apiResponse);
        if (analysisJson == null) {
            throw new PipelineExecutionException("Failed to extract testability analysis JSON from response");
        }
        
        LOG.info("Extracted testability analysis JSON: " + analysisJson);
        
        // Check testability score and recommendation
        if (analysisJson.has("testabilityScore") && analysisJson.has("recommendation")) {
            int testabilityScore = analysisJson.get("testabilityScore").getAsInt();
            String recommendation = analysisJson.get("recommendation").getAsString();
            String testabilityAnalysis = analysisJson.has("testabilityAnalysis") ? 
                    analysisJson.get("testabilityAnalysis").getAsString() : "No analysis provided";
            
            LOG.info("Testability score: " + testabilityScore + "/10, Recommendation: " + recommendation);
            
            if ("REJECT".equals(recommendation) || testabilityScore < 4) {
                // Class is not testable enough - reject the test writing request
                String rejectionReason = analysisJson.has("rejectionReason") ? 
                        analysisJson.get("rejectionReason").getAsString() : "Class testability is insufficient";
                
                StringBuilder rejectionMessage = new StringBuilder();
                rejectionMessage.append("# Test Writing Rejected - Poor Testability\n\n");
                rejectionMessage.append("**Testability Score: ").append(testabilityScore).append("/10**\n\n");
                rejectionMessage.append("**Recommendation: ").append(recommendation).append("**\n\n");
                rejectionMessage.append("## Analysis\n");
                rejectionMessage.append(testabilityAnalysis).append("\n\n");
                
                if (analysisJson.has("rejectionReason") && !analysisJson.get("rejectionReason").isJsonNull()) {
                    rejectionMessage.append("## Rejection Reason\n");
                    rejectionMessage.append(rejectionReason).append("\n\n");
                }
                
                rejectionMessage.append("## Recommendation\n");
                rejectionMessage.append("This class requires refactoring before comprehensive testing can be effectively implemented. ");
                rejectionMessage.append("Please consider using the 'Agent: Step-by-Step Refactor for Testability' action first to improve the class structure.\n\n");
                
                if (analysisJson.has("refactoringSuggestions")) {
                    rejectionMessage.append("## Refactoring Suggestions\n");
                    analysisJson.getAsJsonArray("refactoringSuggestions").forEach(suggestion -> {
                        rejectionMessage.append("- ").append(suggestion.getAsString()).append("\n");
                    });
                    rejectionMessage.append("\n");
                }
                
                rejectionMessage.append("## Common Improvements Needed\n");
                rejectionMessage.append("- Reduce static dependencies\n");
                rejectionMessage.append("- Implement dependency injection\n");
                rejectionMessage.append("- Break down complex methods\n");
                rejectionMessage.append("- Remove singleton patterns\n");
                rejectionMessage.append("- Make classes and methods non-final where appropriate\n");
                rejectionMessage.append("- Separate concerns and reduce coupling");
                
                throw new PipelineExecutionException("Test writing rejected due to poor testability (score: " + 
                        testabilityScore + "/10). " + rejectionMessage.toString());
            }
            
            // Class is testable - store analysis results and proceed
            LOG.info("Class is testable (score: " + testabilityScore + "/10). Proceeding to test planning.");
            
            // Store testability information in context for later use
            context.setPrompt("Testability Score: " + testabilityScore + "/10 - " + testabilityAnalysis);
            
            // Clear the API response so the next stage can make a fresh call
            context.setApiResponse(null);
            
        } else {
            throw new PipelineExecutionException("Invalid testability analysis response format - missing required fields");
        }
        
        LOG.info("Testability analysis response stage completed successfully - proceeding to test planning");
    }
    
    /**
     * Extracts JSON from the LLM response, which may be embedded in markdown code blocks.
     */
    private JsonObject extractJsonFromResponse(String response) {
        // First try to find JSON in markdown code blocks
        java.util.regex.Pattern jsonPattern = java.util.regex.Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = jsonPattern.matcher(response);
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
}
