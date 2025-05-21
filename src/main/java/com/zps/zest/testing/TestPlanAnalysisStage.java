package com.zps.zest.testing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage for analyzing the LLM's response and extracting the test plan.
 * Parses the JSON plan and updates the test writing state.
 */
public class TestPlanAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestPlanAnalysisStage.class);
    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Analyzing test plan from LLM response");
        
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
            throw new PipelineExecutionException("Failed to extract test plan JSON from response");
        }
        
        LOG.info("Extracted test plan JSON: " + planJson);
        
        // Check testability score first
        if (planJson.has("testabilityScore")) {
            int testabilityScore = planJson.get("testabilityScore").getAsInt();
            String testabilityAnalysis = planJson.has("testabilityAnalysis") ? 
                    planJson.get("testabilityAnalysis").getAsString() : "No analysis provided";
            
            LOG.info("Testability score: " + testabilityScore + "/10");
            
            if (testabilityScore < 4) {
                // Class is not testable enough - reject the test writing request
                StringBuilder rejectionMessage = new StringBuilder();
                rejectionMessage.append("# Test Writing Rejected - Poor Testability\n\n");
                rejectionMessage.append("**Testability Score: ").append(testabilityScore).append("/10**\n\n");
                rejectionMessage.append("## Analysis\n");
                rejectionMessage.append(testabilityAnalysis).append("\n\n");
                rejectionMessage.append("## Recommendation\n");
                rejectionMessage.append("This class requires refactoring before comprehensive testing can be effectively implemented. ");
                rejectionMessage.append("Please consider using the 'Agent: Step-by-Step Refactor for Testability' action first to improve the class structure.\n\n");
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
        }
        
        // Get the test writing state manager
        TestWritingStateManager stateManager = new TestWritingStateManager(project);
        
        // Load the existing plan
        TestPlan plan = stateManager.loadPlan();
        if (plan == null) {
            throw new PipelineExecutionException("Failed to load test plan");
        }
        
        // Parse and update the plan with scenarios from the JSON
        try {
            if (planJson.has("scenarios") && planJson.get("scenarios").isJsonArray()) {
                JsonArray scenariosArray = planJson.getAsJsonArray("scenarios");
                for (JsonElement scenarioElement : scenariosArray) {
                    if (scenarioElement.isJsonObject()) {
                        JsonObject scenarioJson = scenarioElement.getAsJsonObject();
                        plan.addScenario(parseScenario(scenarioJson));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error parsing test plan JSON", e);
            throw new PipelineExecutionException("Failed to parse test plan: " + e.getMessage());
        }
        
        // Save the updated plan
        if (!stateManager.savePlan(plan)) {
            throw new PipelineExecutionException("Failed to save updated test plan");
        }
        
        // Report the number of scenarios and test cases found
        int scenarioCount = plan.getScenarios().size();
        int testCaseCount = plan.getTotalTestCaseCount();
        
        if (scenarioCount == 0) {
            throw new PipelineExecutionException("No test scenarios were generated. The class may not require additional testing or may have testability issues.");
        }
        
        LOG.info("Test plan analysis complete. Found " + scenarioCount + " scenarios with " + testCaseCount + " test cases total.");
        
        // Update the context with a summary for the next stage
        StringBuilder summary = new StringBuilder();
        summary.append("# Test Plan for ").append(plan.getTargetClass()).append("\n\n");
        
        // Include testability information if available
        if (planJson.has("testabilityScore")) {
            int score = planJson.get("testabilityScore").getAsInt();
            summary.append("**Testability Score: ").append(score).append("/10** ✅\n\n");
        }
        
        summary.append("Found **").append(scenarioCount).append("** test scenarios requiring **")
               .append(testCaseCount).append("** test cases.\n\n");
        
        for (TestScenario scenario : plan.getScenarios()) {
            summary.append("## Scenario ").append(scenario.getId()).append(": ").append(scenario.getTitle()).append("\n");
            summary.append("**Category:** ").append(scenario.getCategory()).append("\n");
            summary.append("**Priority:** ").append(scenario.getPriority()).append("\n\n");
            summary.append(scenario.getDescription()).append("\n\n");
            
            if (scenario.getReasoning() != null && !scenario.getReasoning().isEmpty()) {
                summary.append("**Reasoning:** ").append(scenario.getReasoning()).append("\n\n");
            }
            
            summary.append("**Test Cases:**\n");
            
            for (TestCase testCase : scenario.getTestCases()) {
                summary.append("- ").append(testCase.getTitle()).append("\n");
            }
            summary.append("\n");
        }
        
        summary.append("Would you like to proceed with writing the tests?");
        
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
     * Parses a scenario from a JSON object.
     */
    private TestScenario parseScenario(JsonObject scenarioJson) {
        int id = scenarioJson.has("id") ? scenarioJson.get("id").getAsInt() : 0;
        String title = scenarioJson.has("title") ? scenarioJson.get("title").getAsString() : "Untitled Scenario";
        String description = scenarioJson.has("description") ? scenarioJson.get("description").getAsString() : "";
        String category = scenarioJson.has("category") ? scenarioJson.get("category").getAsString() : "Unit Test";
        
        TestScenario scenario = new TestScenario(id, title, description, category);
        
        if (scenarioJson.has("testType")) {
            scenario.setTestType(scenarioJson.get("testType").getAsString());
        }
        
        if (scenarioJson.has("priority")) {
            scenario.setPriority(scenarioJson.get("priority").getAsString());
        }
        
        if (scenarioJson.has("reasoning")) {
            scenario.setReasoning(scenarioJson.get("reasoning").getAsString());
        }
        
        if (scenarioJson.has("targetMethod")) {
            scenario.setTargetMethod(scenarioJson.get("targetMethod").getAsString());
        }
        
        // Parse test cases
        if (scenarioJson.has("testCases") && scenarioJson.get("testCases").isJsonArray()) {
            JsonArray testCasesArray = scenarioJson.getAsJsonArray("testCases");
            for (JsonElement testCaseElement : testCasesArray) {
                if (testCaseElement.isJsonObject()) {
                    scenario.addTestCase(parseTestCase(testCaseElement.getAsJsonObject(), id));
                }
            }
        }
        
        return scenario;
    }

    /**
     * Parses a test case from a JSON object.
     */
    private TestCase parseTestCase(JsonObject testCaseJson, int scenarioId) {
        int id = testCaseJson.has("id") ? testCaseJson.get("id").getAsInt() : 0;
        String title = testCaseJson.has("title") ? testCaseJson.get("title").getAsString() : "Untitled Test Case";
        String description = testCaseJson.has("description") ? testCaseJson.get("description").getAsString() : "";
        
        TestCase testCase = new TestCase(id, scenarioId, title, description);
        
        if (testCaseJson.has("testMethodName")) {
            testCase.setTestMethodName(testCaseJson.get("testMethodName").getAsString());
        }
        
        if (testCaseJson.has("setup")) {
            testCase.setSetup(testCaseJson.get("setup").getAsString());
        }
        
        if (testCaseJson.has("assertions")) {
            testCase.setAssertions(testCaseJson.get("assertions").getAsString());
        }
        
        return testCase;
    }
}
