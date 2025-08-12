package com.zps.zest.testgen.util;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Integration utilities for test generation with existing file/search tools
 */
public class TestGenerationToolIntegration {
    private static final Logger LOG = Logger.getInstance(TestGenerationToolIntegration.class);
    
    private final Project project;
    
    public TestGenerationToolIntegration(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Create a new file using the project's file creation capabilities
     */
    @NotNull
    public String createFile(@NotNull String filePath, @NotNull String content) {
        try {
            Path path = Paths.get(filePath);
            
            // Ensure parent directories exist
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            // Write the file content
            Files.writeString(path, content);
            
            // Refresh VFS to make IntelliJ aware of the new file
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
            
            LOG.info("Created file: " + filePath);
            return filePath;
            
        } catch (IOException e) {
            LOG.error("Failed to create file: " + filePath, e);
            throw new RuntimeException("File creation failed: " + e.getMessage());
        }
    }
    
    /**
     * Search for files with specific patterns
     */
    @NotNull
    public List<String> searchFiles(@NotNull String pattern) {
        List<String> results = new ArrayList<>();
        
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                return results;
            }
            
            // Use Java NIO to search for files
            Path projectPath = Paths.get(basePath);
            
            Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase().contains(pattern.toLowerCase()))
                .limit(50) // Limit results to avoid performance issues
                .forEach(path -> results.add(path.toString()));
                
        } catch (Exception e) {
            LOG.warn("File search failed for pattern: " + pattern, e);
        }
        
        return results;
    }
    
    /**
     * Read file content
     */
    @Nullable
    public String readFile(@NotNull String filePath) {
        try {
            VirtualFile virtualFile = VirtualFileManager.getInstance().findFileByUrl("file://" + filePath);
            if (virtualFile == null) {
                return null;
            }
            
            PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
            if (psiFile == null) {
                return null;
            }
            
            return psiFile.getText();
            
        } catch (Exception e) {
            LOG.warn("Failed to read file: " + filePath, e);
            return null;
        }
    }
    
    /**
     * Check if a file exists
     */
    public boolean fileExists(@NotNull String filePath) {
        return Files.exists(Paths.get(filePath));
    }
    
    /**
     * Find test files in the project
     */
    @NotNull
    public List<String> findTestFiles() {
        List<String> testFiles = new ArrayList<>();
        
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                return testFiles;
            }
            
            // Search for test files in common test directories
            Path projectPath = Paths.get(basePath);
            
            Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .filter(path -> isTestFile(path.toString()))
                .limit(100) // Limit to avoid performance issues
                .forEach(path -> testFiles.add(path.toString()));
                
        } catch (Exception e) {
            LOG.warn("Failed to find test files", e);
        }
        
        return testFiles;
    }
    
    /**
     * Determine if a file is a test file based on naming conventions
     */
    private boolean isTestFile(@NotNull String filePath) {
        String fileName = Paths.get(filePath).getFileName().toString().toLowerCase();
        String pathLower = filePath.toLowerCase();
        
        return fileName.contains("test") || 
               pathLower.contains("/test/") || 
               pathLower.contains("\\test\\") ||
               fileName.endsWith("test.java") ||
               fileName.endsWith("test.kt") ||
               fileName.endsWith("spec.js") ||
               fileName.endsWith("spec.ts");
    }
    
    /**
     * Get project statistics for context
     */
    @NotNull
    public ProjectStatistics getProjectStatistics() {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                return new ProjectStatistics(0, 0, 0);
            }
            
            Path projectPath = Paths.get(basePath);
            
            long totalFiles = Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .count();
            
            long codeFiles = Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .filter(path -> isCodeFile(path.toString()))
                .count();
                
            long testFiles = Files.walk(projectPath)
                .filter(Files::isRegularFile)
                .filter(path -> isTestFile(path.toString()))
                .count();
                
            return new ProjectStatistics((int) totalFiles, (int) codeFiles, (int) testFiles);
            
        } catch (Exception e) {
            LOG.warn("Failed to get project statistics", e);
            return new ProjectStatistics(0, 0, 0);
        }
    }
    
    private boolean isCodeFile(@NotNull String filePath) {
        String fileName = Paths.get(filePath).getFileName().toString().toLowerCase();
        
        return fileName.endsWith(".java") ||
               fileName.endsWith(".kt") ||
               fileName.endsWith(".js") ||
               fileName.endsWith(".ts") ||
               fileName.endsWith(".jsx") ||
               fileName.endsWith(".tsx") ||
               fileName.endsWith(".py") ||
               fileName.endsWith(".go") ||
               fileName.endsWith(".rs") ||
               fileName.endsWith(".cpp") ||
               fileName.endsWith(".c") ||
               fileName.endsWith(".cs") ||
               fileName.endsWith(".php") ||
               fileName.endsWith(".rb");
    }
    
    /**
     * Data class for project statistics
     */
    public static class ProjectStatistics {
        private final int totalFiles;
        private final int codeFiles;
        private final int testFiles;
        
        public ProjectStatistics(int totalFiles, int codeFiles, int testFiles) {
            this.totalFiles = totalFiles;
            this.codeFiles = codeFiles;
            this.testFiles = testFiles;
        }
        
        public int getTotalFiles() { return totalFiles; }
        public int getCodeFiles() { return codeFiles; }
        public int getTestFiles() { return testFiles; }
        public double getTestCoverage() { 
            return codeFiles > 0 ? (double) testFiles / codeFiles : 0.0; 
        }
        
        @Override
        public String toString() {
            return String.format("ProjectStats{total=%d, code=%d, test=%d, coverage=%.1f%%}", 
                               totalFiles, codeFiles, testFiles, getTestCoverage() * 100);
        }
    }
}