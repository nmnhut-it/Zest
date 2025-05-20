package com.zps.zest.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages persistent state for the test writing process.
 * Handles saving and loading test plans, progress, and context.
 */
public class TestWritingStateManager {
    private static final Logger LOG = Logger.getInstance(TestWritingStateManager.class);
    private static final String TESTING_DIR = ".zest/testing";
    private static final String PLAN_FILE = "current-test-plan.json";
    private static final String PROGRESS_FILE = "current-test-progress.json";
    
    private final Project project;
    private final Gson gson;
    
    /**
     * Creates a new test writing state manager for the specified project.
     * 
     * @param project The project to manage test writing state for
     */
    public TestWritingStateManager(Project project) {
        this.project = project;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        ensureTestingDirExists();
    }
    
    /**
     * Ensures the testing directory exists.
     */
    private void ensureTestingDirExists() {
        try {
            Path testingDir = getTestingDirPath();
            if (!Files.exists(testingDir)) {
                Files.createDirectories(testingDir);
                LOG.info("Created testing directory: " + testingDir);
            }
        } catch (IOException e) {
            LOG.error("Failed to create testing directory", e);
        }
    }
    
    /**
     * Gets the path to the testing directory.
     */
    private Path getTestingDirPath() {
        return Paths.get(project.getBasePath(), TESTING_DIR);
    }
    
    /**
     * Gets the path to the specified file within the testing directory.
     */
    private Path getFilePath(String filename) {
        return getTestingDirPath().resolve(filename);
    }
    
    /**
     * Saves the test plan to disk.
     * 
     * @param plan The test plan to save
     * @return true if the plan was saved successfully, false otherwise
     */
    public boolean savePlan(TestPlan plan) {
        try {
            String json = gson.toJson(plan);
            try (FileWriter writer = new FileWriter(getFilePath(PLAN_FILE).toFile())) {
                writer.write(json);
            }
            LOG.info("Saved test plan: " + plan.getName());
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save test plan", e);
            return false;
        }
    }
    
    /**
     * Loads the current test plan from disk.
     * 
     * @return The test plan, or null if no plan exists or an error occurred
     */
    public TestPlan loadPlan() {
        File planFile = getFilePath(PLAN_FILE).toFile();
        if (!planFile.exists()) {
            return null;
        }
        
        try (FileReader reader = new FileReader(planFile)) {
            return gson.fromJson(reader, TestPlan.class);
        } catch (IOException e) {
            LOG.error("Failed to load test plan", e);
            return null;
        }
    }
    
    /**
     * Saves the current test writing progress to disk.
     * 
     * @param progress The progress to save
     * @return true if the progress was saved successfully, false otherwise
     */
    public boolean saveProgress(TestWritingProgress progress) {
        try {
            String json = gson.toJson(progress);
            try (FileWriter writer = new FileWriter(getFilePath(PROGRESS_FILE).toFile())) {
                writer.write(json);
            }
            LOG.info("Saved test writing progress. Current test: " + progress.getCurrentTest());
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save test writing progress", e);
            return false;
        }
    }
    
    /**
     * Loads the current test writing progress from disk.
     * 
     * @return The test writing progress, or null if no progress exists or an error occurred
     */
    public TestWritingProgress loadProgress() {
        File progressFile = getFilePath(PROGRESS_FILE).toFile();
        if (!progressFile.exists()) {
            return null;
        }
        
        try (FileReader reader = new FileReader(progressFile)) {
            return gson.fromJson(reader, TestWritingProgress.class);
        } catch (IOException e) {
            LOG.error("Failed to load test writing progress", e);
            return null;
        }
    }
    
    /**
     * Checks if a test writing is currently in progress.
     * 
     * @return true if a test writing is in progress, false otherwise
     */
    public boolean isTestWritingInProgress() {
        // Check if progress file exists
        boolean progressExists = getFilePath(PROGRESS_FILE).toFile().exists();
        
        if (progressExists) {
            // Load the progress and check its status
            TestWritingProgress progress = loadProgress();
            if (progress != null) {
                return progress.getStatus() == TestWritingStatus.IN_PROGRESS;
            }
        }
        
        // If progress file doesn't exist or can't be loaded, check if plan exists
        return getFilePath(PLAN_FILE).toFile().exists();
    }
    
    /**
     * Clears all test writing state files.
     * Called when a test writing is complete or canceled.
     * 
     * @return true if the state was cleared successfully, false otherwise
     */
    public boolean clearTestWritingState() {
        boolean success = true;
        
        // Attempt to delete each file
        success &= deleteFileIfExists(getFilePath(PLAN_FILE).toFile());
        success &= deleteFileIfExists(getFilePath(PROGRESS_FILE).toFile());
        
        if (success) {
            LOG.info("Cleared test writing state");
        } else {
            LOG.warn("Failed to clear some test writing state files");
        }
        
        return success;
    }
    
    /**
     * Deletes a file if it exists.
     */
    private boolean deleteFileIfExists(File file) {
        if (file.exists()) {
            return file.delete();
        }
        return true;
    }
}
