package com.zps.zest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

public class KnowledgeBaseManager {
    private final String apiUrl;
    private final String authToken;
    
    public KnowledgeBaseManager(String apiUrl, String authToken) {
        this.apiUrl = apiUrl;
        this.authToken = authToken;
    }
    
    public String uploadFile(Path filePath) throws IOException {
        // Set up the connection
        URL url = new URL(apiUrl + "/v1/files/");
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
                // This depends on the exact response format from OpenWebUI
                return extractFileId(response.toString());
            }
        } else {
            throw new IOException("Failed to upload file: " + conn.getResponseCode());
        }
    }
    
    private String extractFileId(String response) {
        // Parse JSON response to extract the file ID
        // This is a simplified example - adjust based on the actual response format
        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
        if (jsonObject.has("id")) {
            return jsonObject.get("id").getAsString();
        }
        return null;
    }
}