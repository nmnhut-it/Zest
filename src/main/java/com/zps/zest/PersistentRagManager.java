package com.zps.zest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages persistent tracking of RAG files across project sessions.
 * Monitors file changes and ensures the knowledge base stays in sync.
 */
public class PersistentRagManager {
    private static final Logger LOG = Logger.getInstance(PersistentRagManager.class);
    private static final String TRACKING_FILE_NAME = ".zest-rag-files.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Project project;
    private final KnowledgeBaseManager kbManager;
    private final Map<String, TrackedFile> trackedFiles = new ConcurrentHashMap<>();
    private final Set<String> supportedExtensions = new HashSet<>();
    private final VirtualFileListener fileListener;
    private boolean isInitialized = false;

    /**
     * Creates a new persistent RAG manager.
     *
     * @param project The current project
     * @param apiUrl The API URL for the knowledge base
     * @param authToken The authentication token
     */
    public PersistentRagManager(Project project, String apiUrl, String authToken) {
        this.project = project;
        this.kbManager = new KnowledgeBaseManager(apiUrl, authToken, project);
        
        // Copy supported extensions from the KB manager
        this.supportedExtensions.addAll(kbManager.getSupportedExtensions());
        
        // Create file listener
        this.fileListener = createFileListener();
    }

    /**
     * Initializes the manager by loading tracked files and setting up listeners.
     */
    public void initialize() {
        if (isInitialized) return;
        
        // Load tracked files
        loadTrackedFiles();
        
        // Add file listener
        VirtualFileManager.getInstance().addVirtualFileListener(fileListener);
        
        isInitialized = true;
        LOG.info("PersistentRagManager initialized for project: " + project.getName());
    }

    /**
     * Shuts down the manager, saving state and removing listeners.
     */
    public void shutdown() {
        if (!isInitialized) return;
        
        // Save tracked files
        saveTrackedFiles();
        
        // Remove file listener
        VirtualFileManager.getInstance().removeVirtualFileListener(fileListener);
        
        isInitialized = false;
        LOG.info("PersistentRagManager shut down for project: " + project.getName());
    }

    /**
     * Creates a file listener to detect file changes.
     */
    private VirtualFileListener createFileListener() {
        return new VirtualFileListener() {
            @Override
            public void contentsChanged(VirtualFileEvent event) {
                handleFileChanged(event.getFile());
            }
            
            @Override
            public void fileCreated(VirtualFileEvent event) {
                handleFileCreated(event.getFile());
            }
            
            @Override
            public void fileDeleted(VirtualFileEvent event) {
                handleFileDeleted(event.getFile());
            }
        };
    }

    /**
     * Handles file content changes.
     */
    private void handleFileChanged(VirtualFile file) {
        if (!isSupported(file) || !isInTrackedDirectory(file)) return;
        
        String filePath = file.getPath();
        TrackedFile trackedFile = trackedFiles.get(filePath);
        
        if (trackedFile != null) {
            // Check if the file has been modified
            if (file.getTimeStamp() > trackedFile.lastModified) {
                LOG.info("File changed, will re-upload: " + filePath);
                uploadFile(file);
            }
        }
    }

    /**
     * Handles file creation.
     */
    private void handleFileCreated(VirtualFile file) {
        if (!isSupported(file) || !isInTrackedDirectory(file)) return;
        
        // Auto-upload new files in tracked directories
        uploadFile(file);
    }

    /**
     * Handles file deletion.
     */
    private void handleFileDeleted(VirtualFile file) {
        String filePath = file.getPath();
        TrackedFile trackedFile = trackedFiles.get(filePath);
        
        if (trackedFile != null) {
            // Remove from knowledge base
            try {
                if (kbManager.removeFile(trackedFile.fileId)) {
                    LOG.info("Removed file from knowledge base: " + filePath);
                }
                
                trackedFiles.remove(filePath);
                saveTrackedFiles();
            } catch (Exception e) {
                LOG.error("Error removing file from knowledge base: " + filePath, e);
            }
        }
    }

    /**
     * Uploads a single file to the knowledge base.
     */
    public boolean uploadFile(VirtualFile file) {
        if (!isSupported(file)) return false;
        
        try {
            String filePath = file.getPath();
            Path path = Paths.get(filePath);
            String fileId = kbManager.uploadFile(path);
            
            if (fileId != null) {
                trackedFiles.put(filePath, new TrackedFile(fileId, file.getTimeStamp()));
                saveTrackedFiles();
                LOG.info("Uploaded file to knowledge base: " + filePath);
                return true;
            }
        } catch (IOException e) {
            LOG.error("Error uploading file to knowledge base: " + file.getPath(), e);
        }
        
        return false;
    }

    /**
     * Uploads a directory to the knowledge base.
     */
    public List<KnowledgeBaseManager.UploadResult> uploadDirectory(String directoryPath, boolean recursive) {
        Path path = Paths.get(directoryPath);
        List<String> extensions = new ArrayList<>(supportedExtensions);
        
        try {
            List<KnowledgeBaseManager.UploadResult> results = 
                    kbManager.uploadDirectory(path, extensions, recursive);
            
            // Update tracked files
            for (KnowledgeBaseManager.UploadResult result : results) {
                if (result.isSuccess()) {
                    File file = new File(result.getFilePath());
                    trackedFiles.put(result.getFilePath(), 
                            new TrackedFile(result.getFileId(), file.lastModified()));
                }
            }
            
            saveTrackedFiles();
            return results;
        } catch (Exception e) {
            LOG.error("Error uploading directory: " + directoryPath, e);
            return Collections.emptyList();
        }
    }

    /**
     * Removes a file from the knowledge base.
     */
    public boolean removeFile(String filePath) {
        TrackedFile trackedFile = trackedFiles.get(filePath);
        
        if (trackedFile != null) {
            if (kbManager.removeFile(trackedFile.fileId)) {
                trackedFiles.remove(filePath);
                saveTrackedFiles();
                return true;
            }
        }
        
        return false;
    }

    /**
     * Checks if a file is supported for RAG.
     */
    private boolean isSupported(VirtualFile file) {
        if (file == null || file.isDirectory()) return false;
        
        String extension = file.getExtension();
        return extension != null && supportedExtensions.contains(extension.toLowerCase());
    }

    /**
     * Checks if a file is in a tracked directory.
     */
    private boolean isInTrackedDirectory(VirtualFile file) {
        // Check if any parent directory is already being tracked
        String filePath = file.getPath();
        
        for (String trackedPath : trackedFiles.keySet()) {
            File trackedFile = new File(trackedPath);
            File parentDir = trackedFile.getParentFile();
            
            if (parentDir != null && filePath.startsWith(parentDir.getPath())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Loads tracked files from the tracking file.
     */
    private void loadTrackedFiles() {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        
        Path trackingFilePath = Paths.get(projectPath, TRACKING_FILE_NAME);
        
        if (Files.exists(trackingFilePath)) {
            try {
                String json = Files.readString(trackingFilePath);
                Type mapType = new TypeToken<Map<String, TrackedFile>>() {}.getType();
                Map<String, TrackedFile> loaded = GSON.fromJson(json, mapType);
                
                if (loaded != null) {
                    trackedFiles.putAll(loaded);
                    LOG.info("Loaded " + trackedFiles.size() + " tracked files");
                }
            } catch (Exception e) {
                LOG.error("Error loading tracked files", e);
            }
        }
    }

    /**
     * Saves tracked files to the tracking file.
     */
    private void saveTrackedFiles() {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        
        Path trackingFilePath = Paths.get(projectPath, TRACKING_FILE_NAME);
        
        try {
            String json = GSON.toJson(trackedFiles);
            Files.writeString(trackingFilePath, json, StandardCharsets.UTF_8);
            LOG.info("Saved " + trackedFiles.size() + " tracked files");
        } catch (Exception e) {
            LOG.error("Error saving tracked files", e);
        }
    }

    /**
     * Validates tracked files against the knowledge base.
     */
    public void validateTrackedFiles() {
        try {
            List<KnowledgeBaseManager.KnowledgeBaseFile> files = kbManager.listFiles();
            Set<String> knownFileIds = files.stream()
                    .map(KnowledgeBaseManager.KnowledgeBaseFile::getId)
                    .collect(Collectors.toSet());
            
            // Find missing files
            List<String> missingPaths = new ArrayList<>();
            
            for (Map.Entry<String, TrackedFile> entry : trackedFiles.entrySet()) {
                String filePath = entry.getKey();
                TrackedFile trackedFile = entry.getValue();
                
                // Check if file exists on disk
                File file = new File(filePath);
                if (!file.exists()) {
                    missingPaths.add(filePath);
                    continue;
                }
                
                // Check if file exists in knowledge base
                if (!knownFileIds.contains(trackedFile.fileId)) {
                    LOG.info("File exists locally but not in knowledge base, will re-upload: " + filePath);
                    missingPaths.add(filePath);
                }
            }
            
            // Remove missing files from tracking
            for (String missingPath : missingPaths) {
                trackedFiles.remove(missingPath);
            }
            
            saveTrackedFiles();
            
            LOG.info("Validated tracked files: " + trackedFiles.size() + " valid, " + missingPaths.size() + " removed");
        } catch (Exception e) {
            LOG.error("Error validating tracked files", e);
        }
    }

    /**
     * Gets the list of tracked files.
     */
    public List<TrackedFileInfo> getTrackedFiles() {
        List<TrackedFileInfo> result = new ArrayList<>();
        
        for (Map.Entry<String, TrackedFile> entry : trackedFiles.entrySet()) {
            File file = new File(entry.getKey());
            if (file.exists()) {
                result.add(new TrackedFileInfo(
                        file.getName(),
                        entry.getKey(),
                        entry.getValue().fileId,
                        new Date(entry.getValue().lastModified)
                ));
            }
        }
        
        return result;
    }

    /**
     * Gets the underlying knowledge base manager.
     */
    public KnowledgeBaseManager getKnowledgeBaseManager() {
        return kbManager;
    }

    /**
     * Represents a tracked file.
     */
    private static class TrackedFile {
        String fileId;
        long lastModified;
        
        public TrackedFile(String fileId, long lastModified) {
            this.fileId = fileId;
            this.lastModified = lastModified;
        }
    }
    
    /**
     * Information about a tracked file.
     */
    public static class TrackedFileInfo {
        private final String name;
        private final String path;
        private final String fileId;
        private final Date lastModified;
        
        public TrackedFileInfo(String name, String path, String fileId, Date lastModified) {
            this.name = name;
            this.path = path;
            this.fileId = fileId;
            this.lastModified = lastModified;
        }
        
        public String getName() { return name; }
        public String getPath() { return path; }
        public String getFileId() { return fileId; }
        public Date getLastModified() { return lastModified; }
    }
}