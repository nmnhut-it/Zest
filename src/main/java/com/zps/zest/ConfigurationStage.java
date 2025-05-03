package com.zps.zest;

import com.intellij.openapi.project.Project;

/**
 * Stage for loading configuration settings.
 */
public class ConfigurationStage implements PipelineStage {
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("No project available");
        }

        // Load configuration
        ConfigurationManager config = ConfigurationManager.getInstance(project);

        context.setConfig(config);
    }
}

