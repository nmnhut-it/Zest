package com.zps.zest.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project-level settings for Zest Plugin.
 * These settings are specific to each project.
 */
@State(
    name = "com.zps.zest.settings.ZestProjectSettings",
    storages = @Storage("zest-plugin.xml")
)
public class ZestProjectSettings implements PersistentStateComponent<ZestProjectSettings> {
    
    // Removed unused settings (context injection, docs search, RAG, MCP)
    public int maxIterations = 3;
    
    // Agent Proxy Server
    public boolean proxyServerEnabled = false;
    
    // Migration tracking
    public int promptVersion = 0;
    
    public static ZestProjectSettings getInstance(Project project) {
        return project.getService(ZestProjectSettings.class);
    }
    
    @Nullable
    @Override
    public ZestProjectSettings getState() {
        return this;
    }
    
    @Override
    public void loadState(@NotNull ZestProjectSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
