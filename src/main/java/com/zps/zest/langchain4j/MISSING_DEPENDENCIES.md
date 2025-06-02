# Missing Dependencies for ONNX Models

## Required Dependencies

Add these to your `build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...
    
    // ONNX Runtime - REQUIRED for ONNX models to work
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    
    // Optional but recommended for better performance
    implementation("ai.djl:api:0.28.0")
    implementation("ai.djl.onnxruntime:onnxruntime-engine:0.28.0")
    
    // If you still have issues, try adding:
    implementation("com.microsoft.onnxruntime:onnxruntime_gpu:1.19.2") // For GPU support
}
```

## Complete Updated Dependencies Section

```kotlin
dependencies {
    // JUnit 4 for IntelliJ platform tests
    testImplementation("junit:junit:4.13.2")

    // Mockito
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    // LSP4J for language server protocol support
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.20.1")
    
    // MCP SDK dependencies
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.9.0"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webflux")

    // LangChain4j
    implementation("dev.langchain4j:langchain4j:0.35.0")
    implementation("dev.langchain4j:langchain4j-embeddings:0.35.0")

    // ONNX embedding models
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:0.35.0")
    implementation("dev.langchain4j:langchain4j-embeddings-bge-small-en-v15-q:0.35.0")

    // ONNX Runtime - CRITICAL MISSING DEPENDENCY
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")

    // Document processing
    implementation("dev.langchain4j:langchain4j-document-parser-apache-tika:0.35.0")

    // Apache Tika
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")
}
```

## Why ONNX Runtime is Required

The LangChain4j ONNX embedding models (`langchain4j-embeddings-all-minilm-l6-v2` etc.) contain the model files but they need ONNX Runtime to actually execute the models. Without it, you get the null pointer exception when trying to load resources.

## Additional Configuration

If you still have issues after adding ONNX Runtime, try:

1. **Ensure resources are included in the plugin:**
```kotlin
tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(configurations.runtimeClasspath) {
            include("**/*.onnx")
            include("**/*.json")
            include("**/vocab.txt")
            into("META-INF/models")
        }
    }
}
```

2. **Add system properties for ONNX Runtime:**
```kotlin
tasks.runIde {
    jvmArgs(
        "-Djava.library.path=${System.getProperty("java.library.path")}",
        "-Donnxruntime.native.lib.path=${configurations.runtimeClasspath.asPath}"
    )
}
```

## Alternative: Use Simpler Embedding Model

If ONNX models continue to cause issues, consider using a simpler embedding model that doesn't require native libraries:

```kotlin
// Instead of ONNX models, use:
implementation("dev.langchain4j:langchain4j-embeddings-hugging-face:0.35.0")

// Or use OpenAI embeddings (requires API key):
implementation("dev.langchain4j:langchain4j-open-ai:0.35.0")
```

## Test After Adding Dependencies

1. Sync your Gradle project
2. Clean and rebuild
3. Run the plugin
4. Test with the "Test LangChain4j Embeddings" action

The ONNX Runtime dependency should resolve the model loading issues!
