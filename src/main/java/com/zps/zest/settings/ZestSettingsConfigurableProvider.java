package com.zps.zest.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurableProvider;
import com.intellij.openapi.project.Project;

public class ZestSettingsConfigurableProvider extends ConfigurableProvider {
    private final Project project;
    
    public ZestSettingsConfigurableProvider(Project project) {
        this.project = project;
    }
    
    @Override
    public Configurable createConfigurable() {
        return new ZestSettingsConfigurable(project);
    }
}
