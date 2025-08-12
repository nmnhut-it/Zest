package com.zps.zest.testgen.model;

import com.zps.zest.langchain4j.ZestLangChain4jService.ContextItem;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TestContext {
    private final List<ContextItem> codeContext;
    private final List<String> relatedFiles;
    private final Map<String, String> dependencies;
    private final List<String> existingTestPatterns;
    private final String frameworkInfo;
    private final Map<String, Object> additionalMetadata;
    
    public TestContext(@NotNull List<ContextItem> codeContext,
                      @NotNull List<String> relatedFiles,
                      @NotNull Map<String, String> dependencies,
                      @NotNull List<String> existingTestPatterns,
                      @NotNull String frameworkInfo,
                      @NotNull Map<String, Object> additionalMetadata) {
        this.codeContext = new ArrayList<>(codeContext);
        this.relatedFiles = new ArrayList<>(relatedFiles);
        this.dependencies = Map.copyOf(dependencies);
        this.existingTestPatterns = new ArrayList<>(existingTestPatterns);
        this.frameworkInfo = frameworkInfo;
        this.additionalMetadata = Map.copyOf(additionalMetadata);
    }
    
    @NotNull
    public List<ContextItem> getCodeContext() {
        return new ArrayList<>(codeContext);
    }
    
    @NotNull
    public List<String> getRelatedFiles() {
        return new ArrayList<>(relatedFiles);
    }
    
    @NotNull
    public Map<String, String> getDependencies() {
        return Map.copyOf(dependencies);
    }
    
    @NotNull
    public List<String> getExistingTestPatterns() {
        return new ArrayList<>(existingTestPatterns);
    }
    
    @NotNull
    public String getFrameworkInfo() {
        return frameworkInfo;
    }
    
    @NotNull
    public Map<String, Object> getAdditionalMetadata() {
        return Map.copyOf(additionalMetadata);
    }
    
    public boolean hasTestFramework() {
        return !frameworkInfo.isEmpty();
    }
    
    public boolean hasExistingPatterns() {
        return !existingTestPatterns.isEmpty();
    }
    
    public int getContextItemCount() {
        return codeContext.size();
    }
    
    @Override
    public String toString() {
        return "TestContext{" +
               "codeContextItems=" + codeContext.size() +
               ", relatedFiles=" + relatedFiles.size() +
               ", dependencies=" + dependencies.size() +
               ", existingPatterns=" + existingTestPatterns.size() +
               ", hasFramework=" + hasTestFramework() +
               '}';
    }
}