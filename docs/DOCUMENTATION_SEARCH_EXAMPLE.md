# Documentation Search Example

This file demonstrates how the documentation search tool works.

## Features

### Natural Language Search
The search tool uses semantic embeddings to find relevant documentation based on natural language queries. You don't need to know exact keywords - just describe what you're looking for.

### Code Block Support
The tool can also search through code blocks in documentation:

```java
public class ExampleSearch {
    public void demonstrateSearch() {
        // This code block will be indexed separately
        // and can be found when searching for code examples
    }
}
```

### Hierarchical Context
The tool preserves document structure, so when you find a section, you'll see its parent headers for context.

## How to Use

1. Enable documentation search in settings
2. Configure the docs path (default is "docs")
3. Use the tool with queries like:
   - "how to configure search"
   - "code examples for search"
   - "natural language queries"

## Technical Details

The documentation search uses:
- LangChain4j for embeddings
- Custom markdown splitter that respects document structure
- Hybrid search combining semantic and keyword matching
- Automatic indexing on first use

### Configuration

```properties
# In zest-plugin.properties
docsSearchEnabled=true
docsPath=docs
```

## Benefits

- **Fast Search**: Local embeddings mean no API calls
- **Context Aware**: Preserves document hierarchy
- **Code Friendly**: Indexes code blocks separately
- **Natural Queries**: No need for exact keywords
