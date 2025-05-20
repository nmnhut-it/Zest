package com.zps.zest.testing;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationStage;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.PipelineStage;

/**
 * Stage for configuring the test writing pipeline.
 * Sets up the basic configuration and validates prerequisites.
 */
public class TestConfigurationStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(TestConfigurationStage.class);
    
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Configuring test writing pipeline");
        
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("Project is null");
        }
        
        // Delegate to the existing ConfigurationStage for basic setup
        ConfigurationStage configStage = new ConfigurationStage();
        configStage.process(context);
        
        // Additional test-specific configuration
        context.useTestWrightModel(true); // Ensure we're using the test model
        
        LOG.info("Test writing configuration completed successfully");
    }
}
