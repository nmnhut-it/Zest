package com.zps.zest.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Local embedding service using LangChain4j with ONNX models.
 * Provides fast, private embedding generation without external API calls.
 */
@Service(Service.Level.PROJECT)
public final class LocalEmbeddingService implements EmbeddingService, Disposable {
    private static final Logger LOG = Logger.getInstance(LocalEmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final String modelName;
    private final int dimension;
    private final ExecutorService executorService;
    private final ClassLoader pluginClassLoader;

    /**
     * Creates a LocalEmbeddingService with the default model (all-MiniLM-L6-v2).
     */
    public LocalEmbeddingService() {
        this(ModelType.ALL_MINILM_L6_V2);
    }

    /**
     * Creates a LocalEmbeddingService with the specified model type.
     *
     * @param modelType The type of embedding model to use
     */
    public LocalEmbeddingService(ModelType modelType) {
        this.executorService = Executors.newFixedThreadPool(
                Math.min(4, Runtime.getRuntime().availableProcessors())
        );

        // Store the plugin classloader for later use
        this.pluginClassLoader = this.getClass().getClassLoader();

        // Initialize the model with proper classloader context
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        try {
            // Set the context classloader to the plugin's classloader
            currentThread.setContextClassLoader(pluginClassLoader);

            switch (modelType) {

                case ALL_MINILM_L6_V2:
                default:
                    this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
                    this.modelName = "all-MiniLM-L6-v2";
                    this.dimension = 384;
                    break;
            }

            LOG.info("Initialized LocalEmbeddingService with model: " + modelName);

        } catch (Exception e) {
            LOG.error("Failed to initialize embedding model: " + modelType, e);
            throw new RuntimeException("Failed to initialize embedding model", e);
        } finally {
            // Always restore the original classloader
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[dimension];
        }

        // Wrap embedding call with proper classloader context
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        try {
            currentThread.setContextClassLoader(pluginClassLoader);
            Embedding embedding = embeddingModel.embed(text).content();
            return embedding.vector();
        } catch (Exception e) {
            LOG.error("Failed to generate embedding for text", e);
            return new float[dimension];
        } finally {
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        // Process in parallel for better performance
        List<CompletableFuture<float[]>> futures = texts.stream()
                .map(text -> CompletableFuture.supplyAsync(
                        () -> embed(text),
                        executorService
                ))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Override
    public List<Embedding> embedSegments(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new ArrayList<>();
        }

        // Wrap embedAll call with proper classloader context
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        try {
            currentThread.setContextClassLoader(pluginClassLoader);
            // LangChain4j can handle batch embedding efficiently
            return embeddingModel.embedAll(segments).content();
        } catch (Exception e) {
            LOG.error("Failed to embed text segments", e);
            // Fallback to individual embedding
            return segments.stream()
                    .map(segment -> {
                        try {
                            currentThread.setContextClassLoader(pluginClassLoader);
                            return embeddingModel.embed(segment).content();
                        } catch (Exception ex) {
                            LOG.warn("Failed to embed segment: " + segment.text(), ex);
                            return new Embedding(new float[dimension]);
                        } finally {
                            currentThread.setContextClassLoader(originalClassLoader);
                        }
                    })
                    .collect(Collectors.toList());
        } finally {
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public double cosineSimilarity(float[] embedding1, float[] embedding2) {
        if (embedding1.length != embedding2.length) {
            throw new IllegalArgumentException(
                    "Embeddings must have the same dimension. Got " +
                            embedding1.length + " and " + embedding2.length
            );
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < embedding1.length; i++) {
            dotProduct += embedding1[i] * embedding2[i];
            norm1 += embedding1[i] * embedding1[i];
            norm2 += embedding2[i] * embedding2[i];
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    @Override
    public void dispose() {
        executorService.shutdown();
        LOG.info("LocalEmbeddingService disposed");
    }

    /**
     * Supported embedding model types.
     */
    public enum ModelType {
        ALL_MINILM_L6_V2,     // Balanced performance and quality
        BGE_SMALL_EN_V15      // Slightly better quality, similar size
    }
}