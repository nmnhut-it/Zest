# Testing LangChain4j Integration

This guide shows how to test the LangChain4j embedding service in your IntelliJ plugin.

## Quick Start

1. **Build and run your plugin** with the new dependencies added
2. **Right-click in the editor** → **Zest** → **Test LangChain4j Embeddings**
3. Choose a test option from the dialog

## Available Tests

### 1. Quick Test (Basic Embedding)
Tests basic embedding generation functionality:
- Single text embedding
- Batch embedding
- Cosine similarity calculation
- Shows model info and performance metrics

### 2. Document Processing Test
Tests document chunking and processing:
- Code document processing
- Segment creation
- Token estimation

### 3. Search Test (Index Current File)
Tests the vector search functionality:
- Indexes the currently open file
- Extracts code signatures (for Java files)
- Performs sample searches
- Shows search performance

**Note:** You need to have a file open in the editor for this test!

### 4. Hybrid Search Test
Tests the hybrid search combining local and OpenWebUI:
- Searches across both systems
- Shows result sources (LOCAL/CLOUD/BOTH)
- Displays search statistics

### 5. Full System Test
Runs all tests in sequence for comprehensive testing.

### 6. Performance Benchmark
Detailed performance testing:
- Embedding generation speed for different text lengths
- Similarity calculation throughput
- Vector search performance at different scales

## Quick Search

After indexing files, you can use the quick search feature:

1. **Right-click** → **Zest** → **Quick Search with LangChain4j**
2. Or use the keyboard shortcut: **Ctrl+Shift+L**
3. Enter your search query
4. View results with relevance scores

## Understanding the Results

### Embedding Information
- **Model**: The embedding model being used (e.g., all-MiniLM-L6-v2)
- **Dimension**: Size of embedding vectors (typically 384)
- **Time**: Processing time in milliseconds

### Search Results
- **Score**: Relevance score (0-1, higher is better)
- **Source**: Where the result came from:
  - **LOCAL**: LangChain4j in-memory store
  - **CLOUD**: OpenWebUI knowledge base
  - **BOTH**: Found in both systems

### Performance Metrics
- **Throughput**: Operations per second
- **Latency**: Time per operation
- **Memory**: Approximate memory usage

## Troubleshooting

### "No file currently open in editor"
Open a Java file in the editor before running the Search Test.

### "No results found"
1. Make sure you've indexed files using the Search Test
2. Try broader search terms
3. Check that the file contains relevant code

### Out of Memory
Add JVM options to your run configuration:
```
-Xmx2g
```

### Slow Performance
- First embedding generation takes longer (model loading)
- Subsequent operations should be much faster
- Use batch operations when possible

## Example Search Queries

Good queries for testing:
- "public method"
- "class definition"
- "import statements"
- "exception handling"
- "configuration"
- Method names from your code
- Class names
- Specific annotations

## Integration with Existing RAG

The LangChain4j system works alongside your OpenWebUI RAG:
- Both systems can be used independently
- Hybrid search combines results from both
- Local search is faster but not persistent
- OpenWebUI search is persistent but requires API calls

Choose based on your needs:
- **Local only**: Fast, private, no persistence
- **OpenWebUI only**: Slower, persistent, requires setup
- **Hybrid**: Best of both worlds

## Next Steps

1. Configure hybrid search weights in `HybridRagAgent`
2. Implement persistent storage when LanceDB is available
3. Add more embedding models if needed
4. Customize chunking strategies for your code
