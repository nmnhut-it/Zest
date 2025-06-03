package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent that uses exploration reports as context for coding tasks.
 */
@Service(Service.Level.PROJECT)
public final class CodingTaskAgent {
    private static final Logger LOG = Logger.getInstance(CodingTaskAgent.class);
    
    private final Project project;
    private final LLMService llmService;
    
    public CodingTaskAgent(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(LLMService.class);
    }
    
    /**
     * Executes a coding task using the exploration report as context.
     */
    public CodingTaskResult executeCodingTask(String task, CodeExplorationReport explorationReport) {
        LOG.info("Executing coding task: " + task);
        
        CodingTaskResult result = new CodingTaskResult();
        result.setTask(task);
        result.setExplorationReport(explorationReport);
        
        try {
            // Build the coding prompt with comprehensive context
            String prompt = buildCodingPrompt(task, explorationReport);
            
            // Execute the task
            String response = llmService.query(prompt);
            
            if (response == null) {
                result.setSuccess(false);
                result.setError("Failed to get response from LLM");
                return result;
            }
            
            // Parse the response
            parseCodingResponse(response, result);
            
            // If code was generated, validate it against the context
            if (result.getGeneratedCode() != null) {
                validateGeneratedCode(result, explorationReport);
            }
            
            result.setSuccess(true);
            
        } catch (Exception e) {
            LOG.error("Error executing coding task", e);
            result.setSuccess(false);
            result.setError("Error: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Builds the coding prompt with comprehensive context.
     */
    private String buildCodingPrompt(String task, CodeExplorationReport report) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are an expert programmer working on a coding task with comprehensive context.\n\n");
        
        prompt.append("## Original Exploration Query\n");
        prompt.append(report.getOriginalQuery()).append("\n\n");
        
        prompt.append("## Coding Task\n");
        prompt.append(task).append("\n\n");
        
        prompt.append("## Code Context\n");
        prompt.append("You have access to the following code context discovered through exploration:\n\n");
        prompt.append(report.getCodingContext()).append("\n\n");
        
        prompt.append("## Instructions\n");
        prompt.append("1. Analyze the provided context carefully\n");
        prompt.append("2. Implement the requested task using the existing code patterns and conventions\n");
        prompt.append("3. Ensure compatibility with the discovered code structure\n");
        prompt.append("4. Follow the same coding style and patterns observed in the context\n");
        prompt.append("5. Include all necessary imports and dependencies\n\n");
        
        prompt.append("## Response Format\n");
        prompt.append("Provide your response in the following structure:\n\n");
        prompt.append("### APPROACH\n");
        prompt.append("[Explain your approach and how you'll use the context]\n\n");
        prompt.append("### IMPLEMENTATION\n");
        prompt.append("```java\n");
        prompt.append("[Your code implementation]\n");
        prompt.append("```\n\n");
        prompt.append("### INTEGRATION\n");
        prompt.append("[Explain how this integrates with existing code]\n\n");
        prompt.append("### DEPENDENCIES\n");
        prompt.append("[List any new dependencies or imports needed]\n\n");
        
        return prompt.toString();
    }
    
    /**
     * Parses the coding response from LLM.
     */
    private void parseCodingResponse(String response, CodingTaskResult result) {
        result.setRawResponse(response);
        
        // Extract approach
        String approach = extractSection(response, "APPROACH", "IMPLEMENTATION");
        result.setApproach(approach);
        
        // Extract code
        String code = extractCodeBlock(response);
        result.setGeneratedCode(code);
        
        // Extract integration notes
        String integration = extractSection(response, "INTEGRATION", "DEPENDENCIES");
        result.setIntegration(integration);
        
        // Extract dependencies
        String dependencies = extractSection(response, "DEPENDENCIES", null);
        result.setDependencies(parseDependencies(dependencies));
    }
    
    /**
     * Validates generated code against the exploration context.
     */
    private void validateGeneratedCode(CodingTaskResult result, CodeExplorationReport report) {
        List<String> warnings = new ArrayList<>();
        
        String generatedCode = result.getGeneratedCode();
        if (generatedCode == null || generatedCode.isEmpty()) {
            return;
        }
        
        // Check if generated code uses discovered classes
        for (String element : report.getDiscoveredElements()) {
            if (element.contains(".") && generatedCode.contains(element.substring(element.lastIndexOf('.') + 1))) {
                // Good - using discovered elements
                result.addUsedElement(element);
            }
        }
        
        // Check for potential issues
        if (result.getUsedElements().isEmpty()) {
            warnings.add("Generated code doesn't seem to use any discovered elements");
        }
        
        // Check if following patterns from context
        boolean followsPatterns = false;
        for (CodeExplorationReport.CodePiece piece : report.getCodePieces()) {
            if (piece.getType().equals("method") || piece.getType().equals("method_signature")) {
                // Check for similar patterns
                if (hasSimilarStructure(generatedCode, piece.getContent())) {
                    followsPatterns = true;
                    break;
                }
            }
        }
        
        if (!followsPatterns) {
            warnings.add("Generated code might not follow established patterns");
        }
        
        result.setValidationWarnings(warnings);
    }
    
    /**
     * Checks if generated code has similar structure to existing code.
     */
    private boolean hasSimilarStructure(String generatedCode, String existingCode) {
        // Simple check for similar patterns
        // In a real implementation, this would be more sophisticated
        
        // Check for similar annotations
        if (existingCode.contains("@") && generatedCode.contains("@")) {
            return true;
        }
        
        // Check for similar method patterns
        if (existingCode.contains("public") && existingCode.contains("return") &&
            generatedCode.contains("public") && generatedCode.contains("return")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Extracts a section from the response.
     */
    private String extractSection(String response, String startMarker, String endMarker) {
        int start = response.indexOf("### " + startMarker);
        if (start < 0) return "";
        
        start = response.indexOf('\n', start) + 1;
        
        int end;
        if (endMarker != null) {
            end = response.indexOf("### " + endMarker, start);
            if (end < 0) end = response.length();
        } else {
            end = response.length();
        }
        
        return response.substring(start, end).trim();
    }
    
    /**
     * Extracts code block from response.
     */
    private String extractCodeBlock(String response) {
        int start = response.indexOf("```java");
        if (start < 0) {
            start = response.indexOf("```");
            if (start < 0) return "";
        }
        
        int codeStart = response.indexOf('\n', start) + 1;
        int end = response.indexOf("```", codeStart);
        
        if (end < 0) return "";
        
        return response.substring(codeStart, end).trim();
    }
    
    /**
     * Parses dependencies from text.
     */
    private List<String> parseDependencies(String dependencies) {
        List<String> deps = new ArrayList<>();
        
        if (dependencies != null && !dependencies.isEmpty()) {
            String[] lines = dependencies.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("-") || line.startsWith("*")) {
                    deps.add(line.substring(1).trim());
                } else if (!line.isEmpty()) {
                    deps.add(line);
                }
            }
        }
        
        return deps;
    }
    
    /**
     * Result of a coding task execution.
     */
    public static class CodingTaskResult {
        private String task;
        private CodeExplorationReport explorationReport;
        private boolean success;
        private String error;
        private String rawResponse;
        private String approach;
        private String generatedCode;
        private String integration;
        private List<String> dependencies = new ArrayList<>();
        private List<String> usedElements = new ArrayList<>();
        private List<String> validationWarnings = new ArrayList<>();
        
        // Getters and setters
        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }
        
        public CodeExplorationReport getExplorationReport() { return explorationReport; }
        public void setExplorationReport(CodeExplorationReport report) { this.explorationReport = report; }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        
        public String getRawResponse() { return rawResponse; }
        public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }
        
        public String getApproach() { return approach; }
        public void setApproach(String approach) { this.approach = approach; }
        
        public String getGeneratedCode() { return generatedCode; }
        public void setGeneratedCode(String generatedCode) { this.generatedCode = generatedCode; }
        
        public String getIntegration() { return integration; }
        public void setIntegration(String integration) { this.integration = integration; }
        
        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
        
        public List<String> getUsedElements() { return usedElements; }
        public void addUsedElement(String element) { this.usedElements.add(element); }
        
        public List<String> getValidationWarnings() { return validationWarnings; }
        public void setValidationWarnings(List<String> warnings) { this.validationWarnings = warnings; }
    }
}
