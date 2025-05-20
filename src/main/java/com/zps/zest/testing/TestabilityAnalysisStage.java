package com.zps.zest.testing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for analyzing the testability of the target class.
 * Determines if the class is suitable for test writing or needs refactoring first.
 */
public class TestabilityAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestabilityAnalysisStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Analyzing class testability");
        
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
        
        // Build the testability analysis prompt
        String prompt = createTestabilityAnalysisPrompt(classContext, className, context);
        
        // Store the prompt in the context
        context.setPrompt(prompt);
        
        // Set the current stage type so ChatboxLlmApiCallStage knows which system prompt to use
        context.setCurrentStageType("TESTABILITY_ANALYSIS");
        
        LOG.info("Testability analysis stage completed successfully");
    }
    
    /**
     * Creates a testability analysis prompt with context information.
     */
    private String createTestabilityAnalysisPrompt(String classContext, String className, CodeContext context) {
        try {
            // Try to load the testability analysis prompt from resource template
            String templatePath = "/templates/testability_analysis.template";
            
            java.io.InputStream inputStream = getClass().getResourceAsStream(templatePath);
            if (inputStream != null) {
                String template = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                inputStream.close();
                
                // Get context information
                String junitVersion = context.getJunitVersion() != null ? context.getJunitVersion() : "JUnit 5";
                String mockitoAvailable = context.isMockitoPresent() ? "available" : "not available";
                String osName = System.getProperty("os.name", "Unknown");
                String terminalType = getTerminalType(osName);
                
                // Replace placeholders
                return template.replace("${classContext}", classContext)
                              .replace("${className}", className)
                              .replace("${junitVersion}", junitVersion)
                              .replace("${mockitoAvailable}", mockitoAvailable)
                              .replace("${osName}", osName)
                              .replace("${terminalType}", terminalType);
            } else {
                throw new RuntimeException("Testability analysis template not found: " + templatePath);
            }
        } catch (Exception e) {
            LOG.error("Error loading testability analysis template", e);
            throw new RuntimeException("Failed to load testability analysis template: " + e.getMessage());
        }
    }
    
    /**
     * Determines the terminal type based on OS.
     */
    private String getTerminalType(String osName) {
        if (osName.toLowerCase().contains("windows")) {
            return "Command Prompt/PowerShell";
        } else if (osName.toLowerCase().contains("mac")) {
            return "Terminal (bash/zsh)";
        } else if (osName.toLowerCase().contains("linux")) {
            return "Terminal (bash)";
        } else {
            return "Unknown terminal";
        }
    }
}
