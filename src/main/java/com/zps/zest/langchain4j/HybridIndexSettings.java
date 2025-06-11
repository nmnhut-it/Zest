package com.zps.zest.langchain4j;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Settings for the hybrid code search index.
 * Manages configuration for disk-based vs in-memory storage.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "HybridIndexSettings",
    storages = @Storage("zest-hybrid-index.xml")
)
public final class HybridIndexSettings implements PersistentStateComponent<HybridIndexSettings> {
    
    // Whether to use disk-based storage for indices
    private boolean useDiskStorage = true; // Default to disk storage for better memory efficiency
    
    // Cache sizes for disk-based indices
    private int nameIndexCacheSize = 10000;
    private int semanticIndexCacheSize = 1000;
    private int structuralIndexCacheSize = 5000;
    
    // Memory limits
    private int maxMemoryUsageMB = 500; // Max memory for all indices combined
    
    // Auto-persist settings
    private boolean autoPersist = true;
    private int autoPersistIntervalMinutes = 10;
    
    public HybridIndexSettings() {
        // Default constructor required for service
    }
    
    public static HybridIndexSettings getInstance(Project project) {
        return project.getService(HybridIndexSettings.class);
    }
    
    @Nullable
    @Override
    public HybridIndexSettings getState() {
        return this;
    }
    
    @Override
    public void loadState(@NotNull HybridIndexSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
    
    // Getters and setters
    
    public boolean isUseDiskStorage() {
        return useDiskStorage;
    }
    
    public void setUseDiskStorage(boolean useDiskStorage) {
        this.useDiskStorage = useDiskStorage;
    }
    
    public int getNameIndexCacheSize() {
        return nameIndexCacheSize;
    }
    
    public void setNameIndexCacheSize(int nameIndexCacheSize) {
        this.nameIndexCacheSize = nameIndexCacheSize;
    }
    
    public int getSemanticIndexCacheSize() {
        return semanticIndexCacheSize;
    }
    
    public void setSemanticIndexCacheSize(int semanticIndexCacheSize) {
        this.semanticIndexCacheSize = semanticIndexCacheSize;
    }
    
    public int getStructuralIndexCacheSize() {
        return structuralIndexCacheSize;
    }
    
    public void setStructuralIndexCacheSize(int structuralIndexCacheSize) {
        this.structuralIndexCacheSize = structuralIndexCacheSize;
    }
    
    public int getMaxMemoryUsageMB() {
        return maxMemoryUsageMB;
    }
    
    public void setMaxMemoryUsageMB(int maxMemoryUsageMB) {
        this.maxMemoryUsageMB = maxMemoryUsageMB;
    }
    
    public boolean isAutoPersist() {
        return autoPersist;
    }
    
    public void setAutoPersist(boolean autoPersist) {
        this.autoPersist = autoPersist;
    }
    
    public int getAutoPersistIntervalMinutes() {
        return autoPersistIntervalMinutes;
    }
    
    public void setAutoPersistIntervalMinutes(int autoPersistIntervalMinutes) {
        this.autoPersistIntervalMinutes = autoPersistIntervalMinutes;
    }
}
