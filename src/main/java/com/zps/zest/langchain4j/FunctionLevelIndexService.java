package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;

/**
 * Service that delegates to HybridIndexManager for function-level indexing.
 * Maintains backward compatibility while using the new hybrid index system.
 */
@Service(Service.Level.PROJECT)
public final class FunctionLevelIndexService {
    private static final Logger LOG = Logger.getInstance(FunctionLevelIndexService.class);
    
    private final Project project;
    private final HybridIndexManager indexManager;
    
    public FunctionLevelIndexService(Project project) {
        this.project = project;
        this.indexManager = project.getService(HybridIndexManager.class);
        LOG.info("Initialized FunctionLevelIndexService for project: " + project.getName());
    }
    
    /**
     * Indexes the entire project at function level.
     * 
     * @param forceReindex If true, re-indexes all files even if already indexed
     */
    public void indexProject(boolean forceReindex) {
        indexManager.indexProject(forceReindex);
    }
    
    /**
     * Indexes a single file at function level.
     * 
     * @param file The file to index
     * @return Number of signatures indexed
     */
    public int indexFile(VirtualFile file) {
        return indexManager.indexFile(file);
    }
    
    /**
     * Checks if a file needs re-indexing.
     */
    public boolean needsReindex(VirtualFile file) {
        // Delegate to index manager's internal logic
        // For now, always return true to let the manager decide
        return true;
    }
    
    /**
     * Gets indexing statistics.
     */
    public Map<String, Object> getStatistics() {
        return indexManager.getStatistics();
    }
    
    /**
     * Clears the index and resets statistics.
     */
    public void clearIndex() {
        indexManager.clearIndices();
    }
}
