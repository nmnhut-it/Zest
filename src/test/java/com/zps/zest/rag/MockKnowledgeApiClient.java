package com.zps.zest.rag;

import java.io.IOException;
import java.util.*;

/**
 * Mock implementation of KnowledgeApiClient for testing.
 */
public class MockKnowledgeApiClient implements KnowledgeApiClient {
    private final Map<String, KnowledgeBase> knowledgeBases = new HashMap<>();
    private final Map<String, String> files = new HashMap<>();
    private int nextId = 1;
    
    // Control test behavior
    private boolean shouldFailOnCreate = false;
    private boolean shouldFailOnUpload = false;
    
    @Override
    public String createKnowledgeBase(String name, String description) throws IOException {
        if (shouldFailOnCreate) {
            throw new IOException("Mock create failure");
        }
        
        String id = "kb-" + nextId++;
        knowledgeBases.put(id, new KnowledgeBase(id, name, description));
        return id;
    }
    
    @Override
    public String uploadFile(String fileName, String content) throws IOException {
        if (shouldFailOnUpload) {
            throw new IOException("Mock upload failure");
        }
        
        String id = "file-" + nextId++;
        files.put(id, content);
        return id;
    }
    
    @Override
    public void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        KnowledgeBase kb = knowledgeBases.get(knowledgeId);
        if (kb == null) {
            throw new IOException("Knowledge base not found: " + knowledgeId);
        }
        
        if (!files.containsKey(fileId)) {
            throw new IOException("File not found: " + fileId);
        }
        
        kb.fileIds.add(fileId);
    }
    
    @Override
    public List<String> queryKnowledge(String knowledgeId, String query) throws IOException {
        KnowledgeBase kb = knowledgeBases.get(knowledgeId);
        if (kb == null) {
            return Collections.emptyList();
        }
        
        // Simple mock: return all file IDs
        return new ArrayList<>(kb.fileIds);
    }
    
    // Test helpers
    public void setShouldFailOnCreate(boolean shouldFail) {
        this.shouldFailOnCreate = shouldFail;
    }
    
    public void setShouldFailOnUpload(boolean shouldFail) {
        this.shouldFailOnUpload = shouldFail;
    }
    
    public int getKnowledgeBaseCount() {
        return knowledgeBases.size();
    }
    
    public int getFileCount() {
        return files.size();
    }
    
    public boolean hasFile(String knowledgeId, String fileId) {
        KnowledgeBase kb = knowledgeBases.get(knowledgeId);
        return kb != null && kb.fileIds.contains(fileId);
    }
    
    // Inner class for tracking
    private static class KnowledgeBase {
        final String id;
        final String name;
        final String description;
        final Set<String> fileIds = new HashSet<>();
        
        KnowledgeBase(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }
}
