package com.zps.zest.rag;

import com.google.gson.Gson;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.rag.models.KnowledgeCollection;
import org.junit.Test;

/**
 * Tests for knowledge collection fetching functionality.
 */
public class KnowledgeCollectionTest extends BasePlatformTestCase {
    
    private RagAgent ragAgent;
    private MockKnowledgeApiClient mockApi;
    private ConfigurationManager mockConfig;
    private MockCodeAnalyzer mockAnalyzer;
    private Gson gson = new Gson();
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        Project project = getProject();
        mockConfig = new MockConfigurationManager();
        mockAnalyzer = new MockCodeAnalyzer();
        mockApi = new MockKnowledgeApiClient();
        
        ragAgent = RagComponentFactory.createRagAgent(project, mockConfig, mockAnalyzer, mockApi);
    }
    
    @Test
    public void testGetKnowledgeCollection() throws Exception {
        // First, index the project to create a knowledge base
        ProgressIndicator indicator = new EmptyProgressIndicator();
        ragAgent.performIndexing(indicator, false);
        
        // Get the knowledge ID that was created
        String knowledgeId = mockConfig.getKnowledgeId();
        assertNotNull("Knowledge ID should be set after indexing", knowledgeId);
        
        // Fetch the knowledge collection
        KnowledgeCollection collection = ragAgent.getKnowledgeCollection(knowledgeId);
        
        // Verify the collection structure
        assertNotNull("Collection should not be null", collection);
        assertEquals("Knowledge ID should match", knowledgeId, collection.getId());
        assertEquals("Collection type should be 'collection'", "collection", collection.getType());
        assertEquals("Collection status should be 'processed'", "processed", collection.getStatus());
        
        // Verify collection metadata
        assertNotNull("Collection name should be set", collection.getName());
        assertNotNull("Collection description should be set", collection.getDescription());
        assertNotNull("Collection data should be set", collection.getData());
        assertNotNull("Collection files should be set", collection.getFiles());
        
        // Verify file metadata
        assertFalse("Collection should have files", collection.getFiles().isEmpty());
        KnowledgeCollection.FileMetadata firstFile = collection.getFiles().get(0);
        assertNotNull("File ID should be set", firstFile.getId());
        assertNotNull("File meta should be set", firstFile.getMeta());
        assertNotNull("File name should be set", firstFile.getMeta().getName());
        assertEquals("File content type should be markdown", "text/markdown", firstFile.getMeta().getContentType());
    }
    
    @Test
    public void testGetKnowledgeCollectionNotFound() {
        // Try to fetch a non-existent collection
        KnowledgeCollection collection = ragAgent.getKnowledgeCollection("non-existent-id");
        
        // Should return null for non-existent collection
        assertNull("Collection should be null for non-existent ID", collection);
    }
    
    @Test
    public void testKnowledgeCollectionSerialization() throws Exception {
        // First, index the project
        ProgressIndicator indicator = new EmptyProgressIndicator();
        ragAgent.performIndexing(indicator, false);
        
        String knowledgeId = mockConfig.getKnowledgeId();
        KnowledgeCollection collection = ragAgent.getKnowledgeCollection(knowledgeId);
        
        // Serialize to JSON
        String json = gson.toJson(collection);
        assertNotNull("JSON should not be null", json);
        
        // Verify JSON contains expected fields
        assertTrue("JSON should contain id", json.contains("\"id\":"));
        assertTrue("JSON should contain user_id", json.contains("\"user_id\":"));
        assertTrue("JSON should contain name", json.contains("\"name\":"));
        assertTrue("JSON should contain files", json.contains("\"files\":"));
        assertTrue("JSON should contain type", json.contains("\"type\":\"collection\""));
        assertTrue("JSON should contain status", json.contains("\"status\":\"processed\""));
        
        // Deserialize back
        KnowledgeCollection deserialized = gson.fromJson(json, KnowledgeCollection.class);
        assertNotNull("Deserialized collection should not be null", deserialized);
        assertEquals("IDs should match", collection.getId(), deserialized.getId());
        assertEquals("Types should match", collection.getType(), deserialized.getType());
    }
    
    /**
     * Mock configuration manager for testing.
     */
    private static class MockConfigurationManager extends ConfigurationManager {
        private String knowledgeId;
        
        public MockConfigurationManager() {
            super(null);
        }
        
        @Override
        public String getKnowledgeId() {
            return knowledgeId;
        }
        
        @Override
        public void setKnowledgeId(String id) {
            this.knowledgeId = id;
        }
        
        @Override
        public void saveConfig() {
            // No-op for testing
        }
    }
    
    /**
     * Mock code analyzer for testing.
     */
    private static class MockCodeAnalyzer implements CodeAnalyzer {
        @Override
        public ProjectInfo extractProjectInfo() {
            ProjectInfo info = new ProjectInfo();
            info.setBuildSystem("Maven");
            info.setMainLanguage("Java");
            info.setTotalSourceFiles(5);
            return info;
        }
        
        @Override
        public List<com.intellij.openapi.vfs.VirtualFile> findAllSourceFiles() {
            return new java.util.ArrayList<>();
        }
        
        @Override
        public List<CodeSignature> extractSignatures(com.intellij.psi.PsiFile file) {
            return new java.util.ArrayList<>();
        }
    }
}
