# LangChain4j Embedding Service for IntelliJ RAG

This package provides a local embedding generation service using LangChain4j, designed to work alongside or replace the OpenWebUI-based RAG system.

## Features

- **Local Embedding Generation**: Uses ONNX models for fast, private embedding generation without API calls
- **Multiple Model Support**: Supports all-MiniLM-L6-v2 and BGE-small-en-v1.5 models
- **Document Processing**: Handles various file types with intelligent chunking
- **Vector Store**: In-memory vector store with similarity search
- **Hybrid Search**: Combines vector similarity with keyword matching
- **Integration**: Works seamlessly with existing OpenWebUI-based system

## Usage

### 1. Basic Embedding Generation

```java
// Initialize the service
EmbeddingService embeddingService = new LocalEmbeddingService();

// Generate embedding for a single text
float[] embedding = embeddingService.embed("public class HelloWorld");

// Generate embeddings in batch
List<String> texts = Arrays.asList("text1", "text2", "text3");
List<float[]> embeddings = embeddingService.embedBatch(texts);

// Calculate similarity
double similarity = embeddingService.cosineSimilarity(embedding1, embedding2);
```

### 2. Document Processing

```java
DocumentProcessor processor = new DocumentProcessor();

// Process a file
VirtualFile file = // ... get file
List<TextSegment> segments = processor.processFile(file);

// Process with code signatures
List<CodeSignature> signatures = // ... from your existing code
List<TextSegment> enrichedSegments = processor.processPsiFile(psiFile, signatures);
```

### 3. RAG Service Usage

```java
// Get the service from project
RagService ragService = project.getService(RagService.class);

// Index a file
int segmentCount = ragService.indexFile(virtualFile, codeSignatures);

// Search for relevant content
CompletableFuture<List<RagService.SearchResult>> results = 
    ragService.search("authentication logic", 10);

// Search specifically in code
CompletableFuture<List<RagService.SearchResult>> codeResults = 
    ragService.searchCode("@Override annotation", 5);
```

### 4. Hybrid RAG Agent (Best of Both Worlds)

```java
// Get the hybrid agent
HybridRagAgent hybridAgent = project.getService(HybridRagAgent.class);

// Configure preferences
hybridAgent.configure(true, 0.7); // Prefer local with 70% weight

// Perform hybrid search
CompletableFuture<List<HybridSearchResult>> results = 
    hybridAgent.search("database connection", 10);

// Get full code (tries both sources)
String fullCode = hybridAgent.getFullCode("com.example.MyClass#myMethod");
```

### 5. Enhanced Search Tool

The `EnhancedRagSearchTool` provides an improved search experience:

```java
// Register the tool with your agent
EnhancedRagSearchTool tool = new EnhancedRagSearchTool(project);

// Use in your agent system
JsonObject params = new JsonObject();
params.addProperty("query", "REST API endpoints");
params.addProperty("maxResults", 10);
params.addProperty("codeOnly", true);

String results = tool.execute(params);
```

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Your IntelliJ Plugin                │
├─────────────────────────────────────────────────────┤
│                   HybridRagAgent                     │
│  (Combines both local and cloud search results)     │
├────────────────────────┬────────────────────────────┤
│    LangChain4j RAG     │    OpenWebUI RAG          │
│   (Local, Fast)        │   (Cloud, Persistent)     │
├────────────────────────┼────────────────────────────┤
│  EmbeddingService      │   KnowledgeApiClient      │
│  VectorStore           │   (Your existing code)    │
│  DocumentProcessor     │                            │
└────────────────────────┴────────────────────────────┘
```

## Configuration

### Embedding Models

Choose between two high-quality models:

1. **all-MiniLM-L6-v2** (Default)
   - Dimension: 384
   - Size: ~23MB
   - Good balance of speed and quality

2. **BGE-small-en-v1.5**
   - Dimension: 384
   - Size: ~24MB
   - Slightly better quality for code

### Search Configuration

```java
// Configure hybrid search
ragService.configureSearch(
    true,   // Use hybrid search (vector + keyword)
    0.7     // Vector weight (0-1)
);

// Configure the hybrid agent
hybridAgent.configure(
    true,   // Prefer local search
    0.6     // Local results weight
);
```

## Performance Considerations

1. **Memory Usage**: Each embedding uses ~1.5KB (384 floats × 4 bytes)
2. **Indexing Speed**: ~100-200 files/second depending on size
3. **Search Speed**: <50ms for 10k embeddings
4. **Model Loading**: ~1-2 seconds on first use

## Migration Path

When LanceDB Java SDK becomes available:

1. Implement `LanceDBVectorStore` extending `VectorStore`
2. Replace `InMemoryVectorStore` with `LanceDBVectorStore`
3. No changes needed in other components

## Troubleshooting

### Out of Memory
- Increase heap size: `-Xmx2g`
- Use batch processing for large projects
- Clear index periodically with `ragService.clearIndex()`

### Slow Indexing
- Process files in parallel
- Use smaller chunk sizes for faster processing
- Index incrementally rather than all at once

### Poor Search Results
- Adjust chunk size and overlap
- Tune hybrid search weights
- Use code-specific search for programming queries

## Future Enhancements

1. **Persistent Storage**: Save embeddings to disk
2. **Incremental Updates**: Track file changes
3. **Custom Models**: Support for user-provided ONNX models
4. **Query Expansion**: Automatic synonym/related term inclusion
5. **Relevance Feedback**: Learn from user interactions
