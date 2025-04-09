package com.zps.zest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Enhanced manager for handling knowledge base operations.
 * Provides methods for uploading files, listing files, and managing collections.
 */
public class KnowledgeBaseManager {
    private static final Logger LOG = Logger.getInstance(KnowledgeBaseManager.class);

    private final String apiUrl;
    private final String authToken;
    private final Project project;
    private final Set<String> supportedExtensions = new HashSet<>(Arrays.asList(
            "java", "xml", "txt", "md", "properties", "yaml", "yml", "json",
            "html", "css", "js", "ts", "py", "sql", "gradle", "kt", "kts", "groovy"
    ));

    // Track uploaded files to avoid duplicates
    private final Map<String, String> uploadedFiles = new HashMap<>(); // path -> fileId

    public KnowledgeBaseManager(String apiUrl, String authToken, Project project) {
        this.apiUrl = apiUrl;
        this.authToken = authToken;
        this.project = project;
    }

    /**
     * Uploads a single file to the knowledge base.
     *
     * @param filePath The path to the file
     * @return The file ID if successful, null otherwise
     * @throws IOException If an error occurs during upload
     */
    public String uploadFile(Path filePath) throws IOException {
        if (uploadedFiles.containsKey(filePath.toString())) {
            LOG.info("File already uploaded: " + filePath);
            return uploadedFiles.get(filePath.toString());
        }

        // Check if file extension is supported
        String extension = getFileExtension(filePath.toString());
        if (!isSupportedExtension(extension)) {
            LOG.info("Skipping unsupported file type: " + filePath);
            return null;
        }

        // Set up the connection
        URL url = new URL(apiUrl.replace("/chat/completions","") + "/v1/files/");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        // Create boundary for multipart request
        String boundary = "Boundary-" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        // Get file content
        File file = filePath.toFile();
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            LOG.warn("Cannot read file: " + filePath);
            return null;
        }

        byte[] fileBytes = Files.readAllBytes(filePath);

        // Write multipart form data
        try (var os = conn.getOutputStream()) {
            // Write file part
            os.write(("--" + boundary + "\r\n").getBytes());
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" +
                    file.getName() + "\"\r\n").getBytes());
            os.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes());
            os.write(fileBytes);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        // Read the response
        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                // Parse file ID from the response
                String fileId = extractFileId(response.toString());
                if (fileId != null) {
                    uploadedFiles.put(filePath.toString(), fileId);
                }
                return fileId;
            }
        } else {
            LOG.warn("Failed to upload file: " + conn.getResponseCode() + " - " + filePath);
            try (var errorReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getErrorStream()))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                LOG.warn("Error response: " + errorResponse);
            } catch (Exception e) {
                LOG.warn("Could not read error stream", e);
            }

            throw new IOException("Failed to upload file: " + conn.getResponseCode());
        }
    }

    /**
     * Recursively uploads files from a directory.
     *
     * @param directoryPath The directory path
     * @param fileExtensions List of file extensions to include, null for all supported types
     * @param recursive Whether to process subdirectories
     * @return List of successfully uploaded files
     */
    public List<UploadResult> uploadDirectory(Path directoryPath, List<String> fileExtensions, boolean recursive) {
        List<UploadResult> results = new ArrayList<>();
        File directory = directoryPath.toFile();

        if (!directory.exists() || !directory.isDirectory()) {
            LOG.warn("Not a valid directory: " + directoryPath);
            return results;
        }

        Set<String> extensions = fileExtensions != null && !fileExtensions.isEmpty()
                ? new HashSet<>(fileExtensions)
                : supportedExtensions;

        try {
            processDirectory(directory, extensions, recursive, results);
        } catch (Exception e) {
            LOG.error("Error uploading directory", e);
        }

        return results;
    }

    /**
     * Processes a directory, uploading files and recursively processing subdirectories if requested.
     */
    private void processDirectory(File directory, Set<String> extensions, boolean recursive, List<UploadResult> results) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile()) {
                String extension = getFileExtension(file.getName());
                if (extensions.contains(extension)) {
                    try {
                        String fileId = uploadFile(file.toPath());
                        if (fileId != null) {
                            results.add(new UploadResult(file.getPath(), fileId, true));
                        } else {
                            results.add(new UploadResult(file.getPath(), null, false));
                        }
                    } catch (IOException e) {
                        LOG.warn("Failed to upload file: " + file.getPath(), e);
                        results.add(new UploadResult(file.getPath(), null, false));
                    }
                }
            } else if (recursive && file.isDirectory()) {
                processDirectory(file, extensions, true, results);
            }
        }
    }

    /**
     * Lists all files in the knowledge base.
     *
     * @return List of file information objects
     */
    public List<KnowledgeBaseFile> listFiles() {
        List<KnowledgeBaseFile> files = new ArrayList<>();

        try {
            URL url = new URL(apiUrl + "/v1/files/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    return parseFileList(response.toString());
                }
            } else {
                LOG.warn("Failed to list files: " + conn.getResponseCode());
            }
        } catch (Exception e) {
            LOG.error("Error listing files", e);
        }

        return files;
    }

    /**
     * Removes a file from the knowledge base.
     *
     * @param fileId The ID of the file to remove
     * @return true if successful, false otherwise
     */
    public boolean removeFile(String fileId) {
        try {
            URL url = new URL(apiUrl + "/v1/files/" + fileId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + authToken);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                // Update local tracking
                String pathToRemove = null;
                for (Map.Entry<String, String> entry : uploadedFiles.entrySet()) {
                    if (entry.getValue().equals(fileId)) {
                        pathToRemove = entry.getKey();
                        break;
                    }
                }

                if (pathToRemove != null) {
                    uploadedFiles.remove(pathToRemove);
                }

                return true;
            }
        } catch (Exception e) {
            LOG.error("Error removing file", e);
        }

        return false;
    }

    /**
     * Parses file list from JSON response.
     */
    private List<KnowledgeBaseFile> parseFileList(String jsonResponse) {
        List<KnowledgeBaseFile> files = new ArrayList<>();

        try {
            JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (jsonObject.has("data") && jsonObject.get("data").isJsonArray()) {
                JsonArray dataArray = jsonObject.getAsJsonArray("data");

                for (int i = 0; i < dataArray.size(); i++) {
                    JsonObject fileObj = dataArray.get(i).getAsJsonObject();

                    String id = fileObj.has("id") ? fileObj.get("id").getAsString() : "";
                    String name = fileObj.has("filename") ? fileObj.get("filename").getAsString() : "";
                    String created = fileObj.has("created_at") ? fileObj.get("created_at").getAsString() : "";

                    files.add(new KnowledgeBaseFile(id, name, created));
                }
            }
        } catch (Exception e) {
            LOG.error("Error parsing file list", e);
        }

        return files;
    }

    /**
     * Extracts file ID from JSON response.
     */
    private String extractFileId(String response) {
        try {
            JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
            if (jsonObject.has("id")) {
                return jsonObject.get("id").getAsString();
            }
        } catch (Exception e) {
            LOG.error("Error extracting file ID", e);
        }
        return null;
    }

    /**
     * Gets the file extension from a path.
     */
    private String getFileExtension(String filePath) {
        int lastDotIndex = filePath.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < filePath.length() - 1) {
            return filePath.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }

    /**
     * Checks if the file extension is supported.
     */
    private boolean isSupportedExtension(String extension) {
        return supportedExtensions.contains(extension);
    }

    /**
     * Adds an extension to the supported list.
     */
    public void addSupportedExtension(String extension) {
        if (extension != null && !extension.isEmpty()) {
            supportedExtensions.add(extension.toLowerCase());
        }
    }

    /**
     * Gets the set of supported file extensions.
     */
    public Set<String> getSupportedExtensions() {
        return new HashSet<>(supportedExtensions);
    }

    /**
     * Gets the map of uploaded files.
     */
    public Map<String, String> getUploadedFiles() {
        return new HashMap<>(uploadedFiles);
    }

    /**
     * Represents a knowledge base file.
     */
    public static class KnowledgeBaseFile {
        private final String id;
        private final String name;
        private final String createdAt;

        public KnowledgeBaseFile(String id, String name, String createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        @Override
        public String toString() {
            return name + " (ID: " + id + ")";
        }
    }

    /**
     * Represents the result of a file upload operation.
     */
    public static class UploadResult {
        private final String filePath;
        private final String fileId;
        private final boolean success;

        public UploadResult(String filePath, String fileId, boolean success) {
            this.filePath = filePath;
            this.fileId = fileId;
            this.success = success;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getFileId() {
            return fileId;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}