package com.zps.zest.testing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.CodeContext;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for analyzing the target class to understand its structure and testing requirements.
 * Builds context about the class to inform test planning.
 */
public class TestAnalysisStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestAnalysisStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Analyzing class for test writing");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Get class info and context from previous stages
        String className = context.getClassName();
        if (className == null || className.isEmpty()) {
            throw new PipelineExecutionException("Class name not available");
        }
        
        // Analyze the class structure using ClassAnalyzer
        try {
            String classContext = ClassAnalyzer.collectClassContext(context.getTargetClass());
            if (classContext == null || classContext.isEmpty()) {
                throw new PipelineExecutionException("Failed to collect class context for " + className);
            }
            
            // Store the class context for use in planning
            context.setClassContext(classContext);
            
            LOG.info("Class analysis completed for " + className);
            
        } catch (Exception e) {
            LOG.error("Error analyzing class " + className, e);
            throw new PipelineExecutionException("Failed to analyze class: " + e.getMessage());
        }
    }
}
