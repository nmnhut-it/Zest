package com.zps.zest.rag;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/**
 * Interface for knowledge base API operations.
 * This allows for easy mocking in tests.
 */
public interface KnowledgeApiClient {
    
    /**
     * Creates a new knowledge base.
     * 
     * @param name The name of the knowledge base
     * @param description The description
     * @return The knowledge base ID
     * @throws IOException if the operation fails
     */
    String createKnowledgeBase(String name, String description) throws IOException;
    
    /**
     * Uploads a file to the knowledge base.
     * 
     * @param fileName The file name
     * @param content The file content
     * @return The file ID
     * @throws IOException if the operation fails
     */
    String uploadFile(String fileName, String content) throws IOException;
    
    /**
     * Adds a file to a knowledge base.
     * 
     * @param knowledgeId The knowledge base ID
     * @param fileId The file ID
     * @throws IOException if the operation fails
     */
    void addFileToKnowledge(String knowledgeId, String fileId) throws IOException;
    
    /**
     * Queries the knowledge base.
     * 
     * @param knowledgeId The knowledge base ID
     * @param query The search query
     * @return List of relevant document IDs
     * @throws IOException if the operation fails
     */
    List<String> queryKnowledge(String knowledgeId, String query) throws IOException;
}
