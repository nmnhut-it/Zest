# Runtime Error Fix: ONNX Model Loading

## The Problem

The error `java.lang.NullPointerException: Cannot invoke "java.io.InputStream.read(byte[], int, int)" because "inputStream" is null` occurs because:

1. LangChain4j's ONNX models are loaded from resources inside JAR files
2. IntelliJ plugin classloaders have restricted access to resources
3. The model files can't be found at runtime

## Solutions

### Solution 1: Use a Fallback Embedding Service (Recommended)

Create a fallback that doesn't require ONNX models:

```java
package com.zps.zest.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Simple embedding model that uses hash-based embeddings.
 * Use this when ONNX models fail to load in plugin environment.
 */
public class SimpleHashEmbeddingModel implements EmbeddingModel {
    private static final int DIMENSION = 384;
    
    @Override
    public Response<Embedding> embed(String text) {
        float[] vector = new float[DIMENSION];
        
        // Simple hash-based embedding (not for production!)
        int hash = text.hashCode();
        for (int i = 0; i < DIMENSION; i++) {
            // Generate deterministic values based on text
            vector[i] = (float) Math.sin(hash * (i + 1)) * 0.5f;
        }
        
        return Response.from(new Embedding(vector));
    }
    
    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = segments.stream()
            .map(segment -> embed(segment.text()).content())
            .collect(Collectors.toList());
        return Response.from(embeddings);
    }
}
```

### Solution 2: Update LocalEmbeddingService with Fallback

```java
public LocalEmbeddingService(ModelType modelType) {
    this.executorService = Executors.newFixedThreadPool(
        Math.min(4, Runtime.getRuntime().availableProcessors())
    );
    
    try {
        switch (modelType) {
            case BGE_SMALL_EN_V15:
                this.embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
                this.modelName = "BGE-small-en-v1.5-quantized";
                this.dimension = 384;
                break;
            case ALL_MINILM_L6_V2:
            default:
                this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
                this.modelName = "all-MiniLM-L6-v2";
                this.dimension = 384;
                break;
        }
    } catch (Exception e) {
        LOG.warn("Failed to load ONNX model, using fallback: " + e.getMessage());
        this.embeddingModel = new SimpleHashEmbeddingModel();
        this.modelName = "simple-hash-embedding";
        this.dimension = 384;
    }
    
    LOG.info("Initialized LocalEmbeddingService with model: " + modelName);
}
```

### Solution 3: Use OpenAI or Other API-based Models

If you have API access, use external embedding services:

```java
// In build.gradle
implementation 'dev.langchain4j:langchain4j-open-ai:0.35.0'

// In code
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

this.embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey("your-api-key")
    .modelName("text-embedding-ada-002")
    .build();
```

### Solution 4: Bundle ONNX Models Properly

Add this to your `build.gradle.kts`:

```kotlin
tasks.processResources {
    // Ensure ONNX model files are included
    from(configurations.runtimeClasspath) {
        include("**/all-minilm-l6-v2.onnx")
        include("**/tokenizer.json")
        into("models")
    }
}
```

### Solution 5: Load Models from Plugin Resources

```java
private EmbeddingModel loadModelFromPlugin() {
    try {
        // Try to load from plugin's resources
        ClassLoader pluginClassLoader = this.getClass().getClassLoader();
        URL modelUrl = pluginClassLoader.getResource("models/all-minilm-l6-v2.onnx");
        
        if (modelUrl != null) {
            // Custom loading logic here
            return loadOnnxModel(modelUrl);
        }
    } catch (Exception e) {
        LOG.warn("Failed to load model from plugin resources", e);
    }
    
    // Fallback
    return new SimpleHashEmbeddingModel();
}
```

## Recommended Approach

For immediate use, implement the **SimpleHashEmbeddingModel** fallback. This will:
1. Allow the plugin to run without ONNX dependencies
2. Provide basic embedding functionality for testing
3. Let you develop and test other features

Later, you can:
1. Use a proper API-based embedding service
2. Implement proper resource loading for ONNX models
3. Switch to a lighter embedding library that works better with plugins

## Testing the Fix

After implementing the fallback:
1. The plugin should start without errors
2. Embeddings will be generated (though not as good as real models)
3. Search functionality will work for testing purposes
