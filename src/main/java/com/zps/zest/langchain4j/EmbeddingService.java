package com.zps.zest.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Service interface for generating embeddings from text.
 * Provides abstraction over different embedding models and implementations.
 */
public interface EmbeddingService {
    
    /**
     * Generates an embedding vector for a single text string.
     * 
     * @param text The text to embed
     * @return The embedding vector as a float array
     */
    float[] embed(String text);
    
    /**
     * Generates embeddings for multiple text strings in batch.
     * More efficient than calling embed() multiple times.
     * 
     * @param texts List of texts to embed
     * @return List of embedding vectors
     */
    List<float[]> embedBatch(List<String> texts);
    
    /**
     * Generates embeddings for text segments (used in document processing).
     * 
     * @param segments List of text segments from document splitting
     * @return List of LangChain4j Embedding objects
     */
    List<Embedding> embedSegments(List<TextSegment> segments);
    
    /**
     * Gets the dimension of embeddings produced by this service.
     * 
     * @return The embedding dimension (e.g., 384 for all-MiniLM-L6-v2)
     */
    int getDimension();
    
    /**
     * Gets the model name being used.
     * 
     * @return The model name
     */
    String getModelName();
    
    /**
     * Calculates cosine similarity between two embeddings.
     * 
     * @param embedding1 First embedding vector
     * @param embedding2 Second embedding vector
     * @return Cosine similarity score between -1 and 1
     */
    double cosineSimilarity(float[] embedding1, float[] embedding2);
    
    /**
     * Disposes of resources used by the embedding service.
     */
    void dispose();
}
