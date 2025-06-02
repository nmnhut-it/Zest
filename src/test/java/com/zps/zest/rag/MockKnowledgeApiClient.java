package com.zps.zest.rag;

import com.zps.zest.rag.models.KnowledgeCollection;
import com.zps.zest.rag.models.KnowledgeCollection.*;

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
    
    @Override
    public KnowledgeCollection getKnowledgeCollection(String knowledgeId) throws IOException {
        KnowledgeBase kb = knowledgeBases.get(knowledgeId);
        if (kb == null) {
            throw new IOException("Knowledge base not found: " + knowledgeId);
        }
        
        // Create a mock KnowledgeCollection
        KnowledgeCollection collection = new KnowledgeCollection();
        collection.setId(kb.id);
        collection.setUserId("test-user");
        collection.setName(kb.name);
        collection.setDescription(kb.description);
        
        // Set data with file IDs
        KnowledgeData data = new KnowledgeData();
        data.setFileIds(new ArrayList<>(kb.fileIds));
        collection.setData(data);
        
        // Create file metadata for each file
        List<FileMetadata> fileMetadataList = new ArrayList<>();
        for (String fileId : kb.fileIds) {
            FileMetadata fileMeta = new FileMetadata();
            fileMeta.setId(fileId);
            
            FileMetaInfo metaInfo = new FileMetaInfo();
            metaInfo.setName(fileId + ".md");
            metaInfo.setContentType("text/markdown");
            metaInfo.setSize(1000);
            metaInfo.setData(new HashMap<>());
            
            fileMeta.setMeta(metaInfo);
            fileMeta.setCreatedAt(System.currentTimeMillis() / 1000);
            fileMeta.setUpdatedAt(System.currentTimeMillis() / 1000);
            
            fileMetadataList.add(fileMeta);
        }
        collection.setFiles(fileMetadataList);
        
        collection.setCreatedAt(System.currentTimeMillis() / 1000);
        collection.setUpdatedAt(System.currentTimeMillis() / 1000);
        collection.setType("collection");
        collection.setStatus("processed");
        
        return collection;
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
