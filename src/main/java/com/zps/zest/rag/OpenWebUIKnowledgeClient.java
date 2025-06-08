package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.rag.models.KnowledgeCollection;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of KnowledgeApiClient for OpenWebUI.
 */
public class OpenWebUIKnowledgeClient implements KnowledgeApiClient {
    private static final Logger LOG = Logger.getInstance(OpenWebUIKnowledgeClient.class);
    
    private final String baseUrl;
    private final String authToken;
    private final Gson gson = new Gson();
    
    public OpenWebUIKnowledgeClient(String baseUrl, String authToken) {
        this.baseUrl = baseUrl.replace("/chat/completions", "");
        this.authToken = authToken;
    }
    
    @Override
    public String createKnowledgeBase(String name, String description) throws IOException {
        // First check if a knowledge base with this name already exists
        String existingId = findKnowledgeByName(name);
        if (existingId != null) {
            LOG.info("Found existing knowledge base with name: " + name + ", ID: " + existingId);
            return existingId;
        }
        
        String apiUrl = baseUrl + "/v1/knowledge/create";
        HttpURLConnection conn = createConnection(apiUrl, "POST");
        
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("description", description);
        
        writeJson(conn, body);
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200 || responseCode == 201) {
            JsonObject response = readJsonResponse(conn);
            String knowledgeId = response.get("id").getAsString();
            LOG.info("Created new knowledge base: " + knowledgeId + " with name: " + name);
            return knowledgeId;
        } else if (responseCode == 422) {
            // Unprocessable Entity - might be duplicate name
            String errorMessage = "Failed to create knowledge base (422): ";
            try {
                if (conn.getErrorStream() != null) {
                    try (InputStreamReader errorReader = new InputStreamReader(conn.getErrorStream())) {
                        JsonObject errorResponse = gson.fromJson(errorReader, JsonObject.class);
                        if (errorResponse != null && errorResponse.has("detail")) {
                            errorMessage += errorResponse.get("detail").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                errorMessage += "Unknown validation error";
            }
            
            // Try to find if it was created anyway
            existingId = findKnowledgeByName(name);
            if (existingId != null) {
                LOG.warn("Knowledge base was created despite 422 error, using existing ID: " + existingId);
                return existingId;
            }
            
            throw new IOException(errorMessage);
        } else {
            throw new IOException("Failed to create knowledge base: " + responseCode);
        }
    }
    
    /**
     * Finds a knowledge base by name.
     * @return The knowledge ID if found, null otherwise
     */
    private String findKnowledgeByName(String name) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/";
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false);
        
        try {
            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    JsonArray knowledgeArray = gson.fromJson(reader, JsonArray.class);
                    
                    for (int i = 0; i < knowledgeArray.size(); i++) {
                        JsonObject knowledge = knowledgeArray.get(i).getAsJsonObject();
                        if (knowledge.has("name") && knowledge.get("name").getAsString().equals(name)) {
                            return knowledge.get("id").getAsString();
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        
        return null;
    }
    
    @Override
    public String uploadFile(String fileName, String content) throws IOException {
        String apiUrl = baseUrl + "/v1/files/";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setDoOutput(true);

        String boundary = "Boundary-" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (var os = conn.getOutputStream()) {
            // Write file part
            os.write(("--" + boundary + "\r\n").getBytes());
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
            os.write(("Content-Type: text/markdown\r\n\r\n").getBytes());
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200 || responseCode == 201) {
            JsonObject response = readJsonResponse(conn);
            String fileId = response.get("id").getAsString();
            LOG.info("File uploaded: " + fileName + ", ID: " + fileId);
            return fileId;
        } else {
            String errorMessage = "Failed to upload file: " + responseCode;
            try {
                if (conn.getErrorStream() != null) {
                    try (InputStreamReader errorReader = new InputStreamReader(conn.getErrorStream())) {
                        JsonObject errorResponse = gson.fromJson(errorReader, JsonObject.class);
                        if (errorResponse != null && errorResponse.has("detail")) {
                            errorMessage += " - " + errorResponse.get("detail").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error reading
            }
            throw new IOException(errorMessage);
        }
    }
    
    @Override
    public void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId + "/file/add";
        HttpURLConnection conn = createConnection(apiUrl, "POST");
        
        JsonObject body = new JsonObject();
        body.addProperty("file_id", fileId);
        
        writeJson(conn, body);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 201 && responseCode != 204) {
            String errorMessage = "Failed to add file to knowledge: " + responseCode;
            try {
                if (conn.getErrorStream() != null) {
                    try (InputStreamReader errorReader = new InputStreamReader(conn.getErrorStream())) {
                        JsonObject errorResponse = gson.fromJson(errorReader, JsonObject.class);
                        if (errorResponse != null && errorResponse.has("detail")) {
                            errorMessage += " - " + errorResponse.get("detail").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error reading
            }
            throw new IOException(errorMessage);
        }
    }
    
    @Override
    public void removeFileFromKnowledge(String knowledgeId, String fileId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId + "/file/remove";
        HttpURLConnection conn = createConnection(apiUrl, "POST");
        
        JsonObject body = new JsonObject();
        body.addProperty("file_id", fileId);
        
        writeJson(conn, body);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 201 && responseCode != 204) {
            String errorMessage = "Failed to remove file from knowledge: " + responseCode;
            try {
                if (conn.getErrorStream() != null) {
                    try (InputStreamReader errorReader = new InputStreamReader(conn.getErrorStream())) {
                        JsonObject errorResponse = gson.fromJson(errorReader, JsonObject.class);
                        if (errorResponse != null && errorResponse.has("detail")) {
                            errorMessage += " - " + errorResponse.get("detail").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error reading
            }
            throw new IOException(errorMessage);
        }
    }
    
    @Override
    public List<String> queryKnowledge(String knowledgeId, String query) throws IOException {
        // For MVP, return empty list - real implementation would query the API
        return new ArrayList<>();
    }
    
    @Override
    public KnowledgeCollection getKnowledgeCollection(String knowledgeId) throws IOException {
        // First, try to get the specific knowledge collection
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId;
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false); // GET request doesn't have a body
        
        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                return gson.fromJson(reader, KnowledgeCollection.class);
            }
        } else if (responseCode == 404) {
            // Knowledge collection not found - let's verify by checking the list
            conn.disconnect();
            return verifyKnowledgeExists(knowledgeId);
        } else if (responseCode == 401 || responseCode == 403) {
            // Authentication/authorization issue
            throw new IOException("Authentication failed for knowledge collection: " + responseCode);
        } else {
            // Other error
            String errorMessage = "Failed to get knowledge collection: " + responseCode;
            try {
                if (conn.getErrorStream() != null) {
                    try (InputStreamReader errorReader = new InputStreamReader(conn.getErrorStream())) {
                        JsonObject errorResponse = gson.fromJson(errorReader, JsonObject.class);
                        if (errorResponse != null && errorResponse.has("detail")) {
                            errorMessage += " - " + errorResponse.get("detail").getAsString();
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error reading
            }
            throw new IOException(errorMessage);
        }
    }
    
    /**
     * Verifies if a knowledge ID exists by checking the list of all knowledge bases.
     * This is a fallback when direct access returns 404.
     */
    private KnowledgeCollection verifyKnowledgeExists(String knowledgeId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/";
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false);
        
        if (conn.getResponseCode() == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                // Parse the array of knowledge collections
                JsonArray knowledgeArray = gson.fromJson(reader, JsonArray.class);
                
                // Search for our knowledge ID
                for (int i = 0; i < knowledgeArray.size(); i++) {
                    JsonObject knowledge = knowledgeArray.get(i).getAsJsonObject();
                    if (knowledge.has("id") && knowledge.get("id").getAsString().equals(knowledgeId)) {
                        // Found it! Convert to KnowledgeCollection
                        return gson.fromJson(knowledge, KnowledgeCollection.class);
                    }
                }
            }
        }
        
        // Not found in the list
        LOG.warn("Knowledge ID not found in list: " + knowledgeId);
        return null;
    }
    
    /**
     * Checks if a knowledge base exists by ID.
     * More efficient than getKnowledgeCollection for validation purposes.
     */
    public boolean knowledgeExists(String knowledgeId) throws IOException {
        // Try direct access first
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId;
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false);
        conn.setRequestMethod("HEAD"); // Use HEAD for efficiency if supported
        
        try {
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                return true;
            } else if (responseCode == 404) {
                // Fall back to checking the list
                return checkKnowledgeInList(knowledgeId);
            } else if (responseCode == 405) {
                // HEAD not supported, try GET
                conn.disconnect();
                conn = createConnection(apiUrl, "GET");
                conn.setDoOutput(false);
                responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    return true;
                } else if (responseCode == 404) {
                    return checkKnowledgeInList(knowledgeId);
                }
            }
        } finally {
            conn.disconnect();
        }
        
        return false;
    }
    
    /**
     * Checks if a knowledge ID exists in the list of all knowledge bases.
     */
    private boolean checkKnowledgeInList(String knowledgeId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/";
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false);
        
        try {
            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                    JsonArray knowledgeArray = gson.fromJson(reader, JsonArray.class);
                    
                    for (int i = 0; i < knowledgeArray.size(); i++) {
                        JsonObject knowledge = knowledgeArray.get(i).getAsJsonObject();
                        if (knowledge.has("id") && knowledge.get("id").getAsString().equals(knowledgeId)) {
                            return true;
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        
        return false;
    }
    
    private HttpURLConnection createConnection(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        if ("POST".equals(method) || "PUT".equals(method)) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
        } else {
            conn.setDoOutput(false);
        }
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }
    
    private void writeJson(HttpURLConnection conn, JsonObject json) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream())) {
            writer.write(json.toString());
        }
    }
    
    private JsonObject readJsonResponse(HttpURLConnection conn) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }
}
