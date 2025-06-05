package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
    private final String baseUrl;
    private final String authToken;
    private final Gson gson = new Gson();
    
    public OpenWebUIKnowledgeClient(String baseUrl, String authToken) {
        this.baseUrl = baseUrl.replace("/chat/completions", "");
        this.authToken = authToken;
    }
    
    @Override
    public String createKnowledgeBase(String name, String description) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/create";
        HttpURLConnection conn = createConnection(apiUrl, "POST");
        
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("description", description);
        
        writeJson(conn, body);
        
        if (conn.getResponseCode() == 200) {
            JsonObject response = readJsonResponse(conn);
            return response.get("id").getAsString();
        } else {
            throw new IOException("Failed to create knowledge base: " + conn.getResponseCode());
        }
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

        if (conn.getResponseCode() == 200) {
            JsonObject response = readJsonResponse(conn);
            return response.get("id").getAsString();
        } else {
            throw new IOException("Failed to upload file: " + conn.getResponseCode());
        }
    }
    
    @Override
    public void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId + "/file/add";
        HttpURLConnection conn = createConnection(apiUrl, "POST");
        
        JsonObject body = new JsonObject();
        body.addProperty("file_id", fileId);
        
        writeJson(conn, body);
        
        if (conn.getResponseCode() != 200) {
            throw new IOException("Failed to add file to knowledge: " + conn.getResponseCode());
        }
    }
    
    @Override
    public List<String> queryKnowledge(String knowledgeId, String query) throws IOException {
        // For MVP, return empty list - real implementation would query the API
        return new ArrayList<>();
    }
    
    @Override
    public KnowledgeCollection getKnowledgeCollection(String knowledgeId) throws IOException {
        String apiUrl = baseUrl + "/v1/knowledge/" + knowledgeId;
        HttpURLConnection conn = createConnection(apiUrl, "GET");
        conn.setDoOutput(false); // GET request doesn't have a body
        
        if (conn.getResponseCode() == 200) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                return gson.fromJson(reader, KnowledgeCollection.class);
            }
        } else {
            throw new IOException("Failed to get knowledge collection: " + conn.getResponseCode());
        }
    }
    
    private HttpURLConnection createConnection(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
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
