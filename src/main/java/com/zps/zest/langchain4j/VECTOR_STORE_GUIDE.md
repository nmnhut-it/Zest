# Vector Store Options for LangChain4j

## Current Implementation

We use LangChain4j's `InMemoryEmbeddingStore` wrapped in our `LangChain4jVectorStore` adapter. This provides:

- ✅ Official LangChain4j implementation
- ✅ Fast in-memory operations
- ✅ No external dependencies
- ❌ No persistence (data lost on restart)
- ❌ Limited to available RAM

## Alternative Vector Stores

### 1. Chroma (Recommended for local persistence)

```java
// Add dependency
implementation 'dev.langchain4j:langchain4j-chroma:0.35.0'

// Usage
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;

ChromaEmbeddingStore chromaStore = ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8000")
    .collectionName("my-collection")
    .build();
```

**Pros:**
- Local persistence
- Good performance
- Easy to set up

**Cons:**
- Requires running Chroma server

### 2. Weaviate (Production-ready)

```java
// Add dependency
implementation 'dev.langchain4j:langchain4j-weaviate:0.35.0'

// Usage
import dev.langchain4j.store.embedding.weaviate.WeaviateEmbeddingStore;

WeaviateEmbeddingStore weaviateStore = WeaviateEmbeddingStore.builder()
    .scheme("http")
    .host("localhost:8080")
    .objectClass("Document")
    .build();
```

**Pros:**
- Production-ready
- Scalable
- Advanced features

**Cons:**
- More complex setup
- Requires Weaviate server

### 3. Pinecone (Cloud-based)

```java
// Add dependency
implementation 'dev.langchain4j:langchain4j-pinecone:0.35.0'

// Usage
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;

PineconeEmbeddingStore pineconeStore = PineconeEmbeddingStore.builder()
    .apiKey("your-api-key")
    .environment("your-environment")
    .projectId("your-project-id")
    .index("your-index")
    .build();
```

**Pros:**
- Fully managed
- Highly scalable
- No infrastructure

**Cons:**
- Requires API key
- Costs money
- Network latency

### 4. Qdrant (High-performance)

```java
// Add dependency
implementation 'dev.langchain4j:langchain4j-qdrant:0.35.0'

// Usage
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;

QdrantEmbeddingStore qdrantStore = QdrantEmbeddingStore.builder()
    .host("localhost")
    .port(6333)
    .collectionName("my-collection")
    .build();
```

**Pros:**
- Very fast
- Good filtering
- Production-ready

**Cons:**
- Requires Qdrant server

## How to Switch Stores

1. **Add the dependency** to your `build.gradle`

2. **Modify LangChain4jVectorStore constructor**:

```java
public class LangChain4jVectorStore implements VectorStore {
    
    public LangChain4jVectorStore(EmbeddingService embeddingService, String storeType) {
        this.embeddingService = embeddingService;
        this.entryMap = new HashMap<>();
        
        switch (storeType) {
            case "chroma":
                this.embeddingStore = ChromaEmbeddingStore.builder()
                    .baseUrl("http://localhost:8000")
                    .collectionName("intellij-rag")
                    .build();
                break;
                
            case "weaviate":
                this.embeddingStore = WeaviateEmbeddingStore.builder()
                    .scheme("http")
                    .host("localhost:8080")
                    .objectClass("Document")
                    .build();
                break;
                
            case "memory":
            default:
                this.embeddingStore = new InMemoryEmbeddingStore<>();
                break;
        }
    }
}
```

3. **Update RagService** to use the new store:

```java
public RagService(Project project, String vectorStoreType) {
    this.project = project;
    this.embeddingService = new LocalEmbeddingService();
    this.vectorStore = new LangChain4jVectorStore(embeddingService, vectorStoreType);
    this.documentProcessor = new DocumentProcessor();
}
```

## Future: LanceDB Integration

When LanceDB releases their Java SDK:

1. Create `LanceDBEmbeddingStore` implementing LangChain4j's `EmbeddingStore`
2. Add to the switch statement in `LangChain4jVectorStore`
3. No other code changes needed!

## Performance Comparison

| Store | Speed | Persistence | Setup | Cost |
|-------|-------|-------------|-------|------|
| InMemory | ⚡⚡⚡ | ❌ | ⚡⚡⚡ | Free |
| Chroma | ⚡⚡ | ✅ | ⚡⚡ | Free |
| Weaviate | ⚡⚡ | ✅ | ⚡ | Free/Paid |
| Pinecone | ⚡ | ✅ | ⚡⚡⚡ | Paid |
| Qdrant | ⚡⚡⚡ | ✅ | ⚡ | Free |

## Recommendation

- **Development**: Use InMemoryEmbeddingStore (current)
- **Local Development with Persistence**: Use Chroma
- **Production**: Use Weaviate or Qdrant
- **Cloud/SaaS**: Use Pinecone
- **Future**: Use LanceDB when available
