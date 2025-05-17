package com.zps.zest.refactoring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent state for the refactoring process.
 * Handles saving and loading refactoring plans, progress, and context.
 */
public class RefactoringStateManager {
    private static final Logger LOG = Logger.getInstance(RefactoringStateManager.class);
    private static final String REFACTORING_DIR = ".zest/refactorings";
    private static final String PLAN_FILE = "current-plan.json";
    private static final String PROGRESS_FILE = "current-progress.json";
    private static final String CONTEXT_FILE = "current-context.json";
    
    private final Project project;
    private final Gson gson;
    
    /**
     * Creates a new refactoring state manager for the specified project.
     * 
     * @param project The project to manage refactoring state for
     */
    public RefactoringStateManager(Project project) {
        this.project = project;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        ensureRefactoringDirExists();
    }
    
    /**
     * Ensures the refactoring directory exists.
     */
    private void ensureRefactoringDirExists() {
        try {
            Path refactoringDir = getRefactoringDirPath();
            if (!Files.exists(refactoringDir)) {
                Files.createDirectories(refactoringDir);
                LOG.info("Created refactoring directory: " + refactoringDir);
            }
        } catch (IOException e) {
            LOG.error("Failed to create refactoring directory", e);
        }
    }
    
    /**
     * Gets the path to the refactoring directory.
     */
    private Path getRefactoringDirPath() {
        return Paths.get(project.getBasePath(), REFACTORING_DIR);
    }
    
    /**
     * Gets the path to the specified file within the refactoring directory.
     */
    private Path getFilePath(String filename) {
        return getRefactoringDirPath().resolve(filename);
    }
    
    /**
     * Saves the refactoring plan to disk.
     * 
     * @param plan The refactoring plan to save
     * @return true if the plan was saved successfully, false otherwise
     */
    public boolean savePlan(RefactoringPlan plan) {
        try {
            String json = gson.toJson(plan);
            try (FileWriter writer = new FileWriter(getFilePath(PLAN_FILE).toFile())) {
                writer.write(json);
            }
            LOG.info("Saved refactoring plan: " + plan.getName());
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save refactoring plan", e);
            return false;
        }
    }
    
    /**
     * Loads the current refactoring plan from disk.
     * 
     * @return The refactoring plan, or null if no plan exists or an error occurred
     */
    public RefactoringPlan loadPlan() {
        File planFile = getFilePath(PLAN_FILE).toFile();
        if (!planFile.exists()) {
            return null;
        }
        
        try (FileReader reader = new FileReader(planFile)) {
            return gson.fromJson(reader, RefactoringPlan.class);
        } catch (IOException e) {
            LOG.error("Failed to load refactoring plan", e);
            return null;
        }
    }
    
    /**
     * Saves the current refactoring progress to disk.
     * 
     * @param progress The progress to save
     * @return true if the progress was saved successfully, false otherwise
     */
    public boolean saveProgress(RefactoringProgress progress) {
        try {
            String json = gson.toJson(progress);
            try (FileWriter writer = new FileWriter(getFilePath(PROGRESS_FILE).toFile())) {
                writer.write(json);
            }
            LOG.info("Saved refactoring progress. Current step: " + progress.getCurrentStep());
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save refactoring progress", e);
            return false;
        }
    }
    
    /**
     * Loads the current refactoring progress from disk.
     * 
     * @return The refactoring progress, or null if no progress exists or an error occurred
     */
    public RefactoringProgress loadProgress() {
        File progressFile = getFilePath(PROGRESS_FILE).toFile();
        if (!progressFile.exists()) {
            return null;
        }
        
        try (FileReader reader = new FileReader(progressFile)) {
            return gson.fromJson(reader, RefactoringProgress.class);
        } catch (IOException e) {
            LOG.error("Failed to load refactoring progress", e);
            return null;
        }
    }
    
    /**
     * Saves the refactoring context to disk.
     * 
     * @param contextJson The context as a JSON object
     * @return true if the context was saved successfully, false otherwise
     */
    public boolean saveContext(JsonObject contextJson) {
        try {
            try (FileWriter writer = new FileWriter(getFilePath(CONTEXT_FILE).toFile())) {
                gson.toJson(contextJson, writer);
            }
            LOG.info("Saved refactoring context");
            return true;
        } catch (IOException e) {
            LOG.error("Failed to save refactoring context", e);
            return false;
        }
    }
    
    /**
     * Loads the refactoring context from disk.
     * 
     * @return The context as a JSON object, or null if no context exists or an error occurred
     */
    public JsonObject loadContext() {
        File contextFile = getFilePath(CONTEXT_FILE).toFile();
        if (!contextFile.exists()) {
            return null;
        }
        
        try (FileReader reader = new FileReader(contextFile)) {
            return gson.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            LOG.error("Failed to load refactoring context", e);
            return null;
        }
    }
    
    /**
     * Checks if a refactoring is currently in progress.
     * 
     * @return true if a refactoring is in progress, false otherwise
     */
    public boolean isRefactoringInProgress() {
        return getFilePath(PROGRESS_FILE).toFile().exists();
    }
    
    /**
     * Clears all refactoring state files.
     * Called when a refactoring is complete or canceled.
     * 
     * @return true if the state was cleared successfully, false otherwise
     */
    public boolean clearRefactoringState() {
        boolean success = true;
        
        // Attempt to delete each file
        success &= deleteFileIfExists(getFilePath(PLAN_FILE).toFile());
        success &= deleteFileIfExists(getFilePath(PROGRESS_FILE).toFile());
        success &= deleteFileIfExists(getFilePath(CONTEXT_FILE).toFile());
        
        if (success) {
            LOG.info("Cleared refactoring state");
        } else {
            LOG.warn("Failed to clear some refactoring state files");
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
