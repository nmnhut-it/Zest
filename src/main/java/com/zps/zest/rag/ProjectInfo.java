package com.zps.zest.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains project information including build system, dependencies, and structure.
 */
public class ProjectInfo {
    private String buildSystem = "Unknown";
    private List<String> dependencies = new ArrayList<>();
    private List<String> libraries = new ArrayList<>();
    private int totalSourceFiles = 0;
    private String mainLanguage = "Java";
    
    public String getBuildSystem() {
        return buildSystem;
    }
    
    public void setBuildSystem(String buildSystem) {
        this.buildSystem = buildSystem;
    }
    
    public List<String> getDependencies() {
        return dependencies;
    }
    
    public void addDependency(String dependency) {
        if (!dependencies.contains(dependency)) {
            dependencies.add(dependency);
        }
    }
    
    public List<String> getLibraries() {
        return libraries;
    }
    
    public void addLibrary(String library) {
        if (!libraries.contains(library)) {
            libraries.add(library);
        }
    }
    
    public int getTotalSourceFiles() {
        return totalSourceFiles;
    }
    
    public void setTotalSourceFiles(int totalSourceFiles) {
        this.totalSourceFiles = totalSourceFiles;
    }
    
    public String getMainLanguage() {
        return mainLanguage;
    }
    
    public void setMainLanguage(String mainLanguage) {
        this.mainLanguage = mainLanguage;
    }
}
