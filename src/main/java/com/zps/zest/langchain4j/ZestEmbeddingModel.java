package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.retrieval.core.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Custom EmbeddingModel that uses Zest's existing EmbeddingService.
 * Configured for Qwen3-Embedding-0.6B model.
 */
public class ZestEmbeddingModel implements EmbeddingModel {
    private static final Logger LOG = Logger.getInstance(ZestEmbeddingModel.class);
    
    private final Project project;
    private final EmbeddingService embeddingService;
    
    // Configuration for Qwen3-Embedding-0.6B
    private static final int QWEN_EMBEDDING_DIMENSIONS = 768; // Qwen3-Embedding-0.6B uses 768 dimensions
    private static final int TIMEOUT_SECONDS = 30;
    
    public ZestEmbeddingModel(@NotNull Project project) {
        this.project = project;
        this.embeddingService = project.getService(EmbeddingService.class);
        
        LOG.info("ZestEmbeddingModel initialized for Qwen3-Embedding-0.6B (768 dimensions)");
    }
    
    @Override
    public Response<Embedding> embed(String text) {
        try {
            // Use the existing EmbeddingService to generate embeddings
            CompletableFuture<float[]> future = embeddingService.generateEmbedding(text);
            float[] vector = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (vector != null) {
                // Ensure correct dimensions for Qwen3-Embedding-0.6B
                if (vector.length != QWEN_EMBEDDING_DIMENSIONS) {
                    LOG.debug("Adjusting embedding dimensions from " + vector.length + " to " + QWEN_EMBEDDING_DIMENSIONS);
                    vector = adjustDimensions(vector);
                }
                Embedding embedding = new Embedding(vector);
                return Response.from(embedding);
            } else {
                LOG.warn("EmbeddingService returned null for text: " + text.substring(0, Math.min(50, text.length())));
                return Response.from(new Embedding(createFallbackEmbedding()));
            }
        } catch (Exception e) {
            LOG.error("Error generating embedding", e);
            // Return fallback embedding on error
            return Response.from(new Embedding(createFallbackEmbedding()));
        }
    }
    
    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return embed(textSegment.text());
    }
    
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = new ArrayList<>();
        for (TextSegment segment : textSegments) {
            texts.add(segment.text());
        }
        
        try {
            // Use batch embedding from EmbeddingService
            CompletableFuture<List<float[]>> future = embeddingService.generateBatchEmbeddings(texts);
            List<float[]> vectors = future.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
            
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = (i < vectors.size() && vectors.get(i) != null) 
                    ? vectors.get(i) 
                    : createFallbackEmbedding();
                    
                // Ensure correct dimensions
                if (vector.length != QWEN_EMBEDDING_DIMENSIONS) {
                    vector = adjustDimensions(vector);
                }
                
                embeddings.add(new Embedding(vector));
            }
            
            return Response.from(embeddings);
        } catch (Exception e) {
            LOG.error("Error generating batch embeddings", e);
            // Return fallback embeddings
            List<Embedding> fallback = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                fallback.add(new Embedding(createFallbackEmbedding()));
            }
            return Response.from(fallback);
        }
    }
    
    /**
     * Adjust embedding dimensions if needed (truncate or pad)
     */
    private float[] adjustDimensions(float[] vector) {
        if (vector.length == QWEN_EMBEDDING_DIMENSIONS) {
            return vector;
        }
        
        float[] adjusted = new float[QWEN_EMBEDDING_DIMENSIONS];
        
        if (vector.length > QWEN_EMBEDDING_DIMENSIONS) {
            // Truncate if too long
            System.arraycopy(vector, 0, adjusted, 0, QWEN_EMBEDDING_DIMENSIONS);
        } else {
            // Pad with zeros if too short
            System.arraycopy(vector, 0, adjusted, 0, vector.length);
            // Rest is already initialized to 0
        }
        
        return adjusted;
    }
    
    /**
     * Create a fallback embedding when the service fails
     */
    private float[] createFallbackEmbedding() {
        // Return a zero vector with Qwen3-Embedding-0.6B dimensions
        return new float[QWEN_EMBEDDING_DIMENSIONS];
    }
    
    /**
     * Get the dimension of embeddings produced by this model
     */
    public int dimension() {
        return QWEN_EMBEDDING_DIMENSIONS; // Qwen3-Embedding-0.6B uses 768 dimensions
    }
    
    /**
     * Clear the embedding cache if needed
     */
    public void clearCache() {
        embeddingService.clearCache();
    }
    
    /**
     * Get cache statistics
     */
    public EmbeddingService.EmbeddingCacheStats getCacheStats() {
        return embeddingService.getCacheStats();
    }
}