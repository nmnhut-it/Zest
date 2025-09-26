package com.zps.zest.testgen.util;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Service for analyzing project structure and inferring optimal test paths.
 * Provides utilities for both LLM-based and PSI-based test generation.
 */
public class ModuleAnalysisService {
    
    /**
     * Analyze the project structure to determine build system and source layout.
     */
    @NotNull
    public static ProjectStructureInfo analyzeProject(@NotNull Project project) {
        String projectPath = project.getBasePath();
        if (projectPath == null) {
            return new ProjectStructureInfo(BuildSystem.UNKNOWN, Collections.emptyList(), Collections.emptyList(), "Project path not available");
        }
        
        // Detect build system
        BuildSystem buildSystem = detectBuildSystem(projectPath);
        
        // Get source roots from IntelliJ module system
        List<String> sourceRoots = getSourceRoots(project);
        List<String> testSourceRoots = getTestSourceRoots(project);
        
        // Generate analysis summary
        String summary = generateAnalysisSummary(buildSystem, sourceRoots, testSourceRoots, projectPath);
        
        return new ProjectStructureInfo(buildSystem, sourceRoots, testSourceRoots, summary);
    }
    
    /**
     * Infer the optimal test source root for a given package.
     */
    @Nullable
    public static String inferTestSourceRoot(@NotNull Project project, @NotNull String packageName) {
        List<String> testSourceRoots = getTestSourceRoots(project);
        
        if (!testSourceRoots.isEmpty()) {
            // Use first available test source root
            return testSourceRoots.get(0);
        }
        
        // Fallback to convention-based detection
        String basePath = project.getBasePath();
        if (basePath != null) {
            // Try standard Maven/Gradle structure
            String standardTestRoot = basePath + File.separator + "src" + File.separator + "test" + File.separator + "java";
            if (new File(standardTestRoot).exists()) {
                return standardTestRoot;
            }
            
            // Try simple test directory
            String simpleTestRoot = basePath + File.separator + "test";
            if (new File(simpleTestRoot).exists()) {
                return simpleTestRoot;
            }
            
            // Default to Maven structure (will be created if needed)
            return standardTestRoot;
        }
        
        return null;
    }
    
    /**
     * Generate the full test file path for a given class and package.
     */
    @NotNull
    public static String generateTestFilePath(@NotNull Project project, 
                                             @NotNull String className, 
                                             @NotNull String packageName) {
        String testSourceRoot = inferTestSourceRoot(project, packageName);
        
        if (testSourceRoot == null) {
            // Fallback
            String basePath = project.getBasePath();
            testSourceRoot = basePath + File.separator + "src" + File.separator + "test" + File.separator + "java";
        }
        
        // Convert package to directory path
        String packagePath = packageName.replace('.', File.separatorChar);
        
        // Combine all parts
        String testFilePath = testSourceRoot;
        if (!packagePath.isEmpty()) {
            testFilePath += File.separator + packagePath;
        }
        testFilePath += File.separator + className + ".java";
        
        return testFilePath;
    }
    
    /**
     * Detect build system from project files.
     */
    @NotNull
    private static BuildSystem detectBuildSystem(@NotNull String projectPath) {
        File projectDir = new File(projectPath);
        
        // Check for Maven
        if (new File(projectDir, "pom.xml").exists()) {
            return BuildSystem.MAVEN;
        }
        
        // Check for Gradle
        if (new File(projectDir, "build.gradle").exists() || 
            new File(projectDir, "build.gradle.kts").exists()) {
            return BuildSystem.GRADLE;
        }
        
        // Check for IntelliJ project files
        File[] imlFiles = projectDir.listFiles((dir, name) -> name.endsWith(".iml"));
        if (imlFiles != null && imlFiles.length > 0) {
            return BuildSystem.INTELLIJ;
        }
        
        return BuildSystem.UNKNOWN;
    }
    
    /**
     * Get source roots from IntelliJ modules.
     */
    @NotNull
    private static List<String> getSourceRoots(@NotNull Project project) {
        List<String> sourceRoots = new ArrayList<>();
        
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (VirtualFile sourceRoot : rootManager.getSourceRoots(false)) {
                sourceRoots.add(sourceRoot.getPath());
            }
        }
        
        return sourceRoots;
    }
    
    /**
     * Get test source roots from IntelliJ modules.
     */
    @NotNull
    private static List<String> getTestSourceRoots(@NotNull Project project) {
        List<String> testSourceRoots = new ArrayList<>();
        
        ModuleManager moduleManager = ModuleManager.getInstance(project);
        for (Module module : moduleManager.getModules()) {
            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            for (VirtualFile testRoot : rootManager.getSourceRoots(true)) {
                // Check if it's actually a test root by path convention
                String path = testRoot.getPath();
                if (path.contains("test") || path.contains("Test")) {
                    testSourceRoots.add(path);
                }
            }
        }
        
        return testSourceRoots;
    }
    
    /**
     * Generate a human-readable analysis summary for LLM consumption.
     */
    @NotNull
    private static String generateAnalysisSummary(@NotNull BuildSystem buildSystem,
                                                 @NotNull List<String> sourceRoots,
                                                 @NotNull List<String> testSourceRoots,
                                                 @NotNull String projectPath) {
        StringBuilder summary = new StringBuilder();
        summary.append("Project Structure Analysis:\n");
        summary.append("- Build System: ").append(buildSystem.getDisplayName()).append("\n");
        summary.append("- Project Path: ").append(projectPath).append("\n");
        
        if (!sourceRoots.isEmpty()) {
            summary.append("- Source Roots (").append(sourceRoots.size()).append("):\n");
            for (String root : sourceRoots) {
                summary.append("  - ").append(root).append("\n");
            }
        } else {
            summary.append("- Source Roots: None detected\n");
        }
        
        if (!testSourceRoots.isEmpty()) {
            summary.append("- Test Source Roots (").append(testSourceRoots.size()).append("):\n");
            for (String testRoot : testSourceRoots) {
                summary.append("  - ").append(testRoot).append("\n");
            }
        } else {
            summary.append("- Test Source Roots: None detected\n");
        }
        
        // Add recommendations
        summary.append("\nRecommendations:\n");
        if (testSourceRoots.isEmpty()) {
            switch (buildSystem) {
                case MAVEN:
                    summary.append("- Use standard Maven test layout: src/test/java/\n");
                    break;
                case GRADLE:
                    summary.append("- Use standard Gradle test layout: src/test/java/\n");
                    break;
                case INTELLIJ:
                    summary.append("- Create test source root or use: test/\n");
                    break;
                default:
                    summary.append("- Create test directory: test/ or src/test/java/\n");
            }
        } else {
            summary.append("- Use existing test source root: ").append(testSourceRoots.get(0)).append("\n");
        }
        
        return summary.toString();
    }
    
    /**
     * Build system types.
     */
    public enum BuildSystem {
        MAVEN("Maven"),
        GRADLE("Gradle"), 
        INTELLIJ("IntelliJ IDEA"),
        UNKNOWN("Unknown");
        
        private final String displayName;
        
        BuildSystem(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Project structure analysis result.
     */
    public static class ProjectStructureInfo {
        private final BuildSystem buildSystem;
        private final List<String> sourceRoots;
        private final List<String> testSourceRoots;
        private final String analysisSummary;
        
        public ProjectStructureInfo(@NotNull BuildSystem buildSystem,
                                   @NotNull List<String> sourceRoots,
                                   @NotNull List<String> testSourceRoots,
                                   @NotNull String analysisSummary) {
            this.buildSystem = buildSystem;
            this.sourceRoots = new ArrayList<>(sourceRoots);
            this.testSourceRoots = new ArrayList<>(testSourceRoots);
            this.analysisSummary = analysisSummary;
        }
        
        @NotNull public BuildSystem getBuildSystem() { return buildSystem; }
        @NotNull public List<String> getSourceRoots() { return sourceRoots; }
        @NotNull public List<String> getTestSourceRoots() { return testSourceRoots; }
        @NotNull public String getAnalysisSummary() { return analysisSummary; }
        
        public boolean hasTestSourceRoots() { return !testSourceRoots.isEmpty(); }
    }
}