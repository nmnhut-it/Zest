# RAG (Retrieval Augmented Generation) System Guide

## Overview

The RAG system in Zest enables intelligent code search and retrieval by indexing your project's code signatures (classes, methods, fields) and project information. This allows the AI to find relevant code when answering questions or generating new code.

## Features

### 1. Automatic Code Indexing
- Indexes all Java and Kotlin files in your project
- Extracts class, interface, enum, and annotation signatures
- Captures method and field signatures with full type information
- Includes Javadoc documentation for all elements
- Captures project information (build system, dependencies, libraries)
- Stores signatures in OpenWebUI's knowledge base for fast retrieval

### 2. Intelligent Code Search
- Use the `search_project_code` tool to find relevant code
- Search by natural language queries
- Returns matching signatures with relevance scores
- Provides full code snippets for highly relevant matches

### 3. Project Context Awareness
- Understands your project's technology stack
- Knows about dependencies and libraries
- Helps avoid redundant implementations
- Maintains code style consistency

## Getting Started

### First-Time Setup

1. **Open your project** - When you open a project for the first time, Zest will prompt you to index it.

2. **Manual indexing** - You can also manually index via:
   - Right-click in editor → Zest → "Index Project for RAG"
   - Or open Knowledge Base Manager and click "Index Project"

3. **Indexing process** - The indexing runs in the background and:
   - Extracts all code signatures
   - Analyzes project structure
   - Uploads to OpenWebUI knowledge base
   - Takes a few minutes for large projects

### Using the RAG System

#### In Chat
When chatting with the AI, it can now search your codebase:

```
You: "How does the authentication work in this project?"
AI: Let me search for authentication-related code...
[Uses search_project_code tool automatically]
```

#### With Tools
The `search_project_code` tool is available for agents:

```json
{
  "tool": "search_project_code",
  "query": "authentication logic",
  "maxResults": 5
}
```

#### Example Queries
- "Find all classes that handle user data"
- "Show me methods related to database operations"
- "What payment processing code exists?"
- "Find the configuration manager"

### Project Mode
For enhanced project understanding, use **Project Mode** in the Zest browser:
1. Select "Project Mode" from the mode dropdown
2. The AI automatically has access to your entire indexed codebase
3. No need to manually search or provide context
4. Get project-specific answers and code suggestions

See [Project Mode Documentation](project-mode.md) for details.

## Configuration

### Settings Location
Configuration is stored in `zest-plugin.properties`:

```properties
# Knowledge base ID (auto-generated)
knowledgeId=kb-xxxxx-xxxxx

# Other settings...
```

### Knowledge Base Management

Open the Knowledge Base Manager dialog to:
- View indexing status
- Re-index the project
- See indexed file count

## How It Works

### 1. Signature Extraction
```java
// Example: From this interface...
/**
 * Repository for user operations.
 */
public interface UserRepository<T extends User> {
    /**
     * Finds a user by ID.
     * @param id the user ID
     * @return the user or null
     */
    T findById(Long id);
}

// Extracts these signatures:
// Interface: "public interface UserRepository<T extends User>"
// Method: "public T findById(Long id)"
// Plus javadoc: "Repository for user operations."
```

### 2. Knowledge Storage
Signatures are stored as structured markdown documents:

```markdown
---
file: /src/main/java/com/example/UserService.java
type: code-signatures
---

## Classes
### `com.example.UserService`
```java
public class UserService
```

## Methods
- `com.example.UserService#findById`
  ```java
  public User findById(Long id)
  ```
```

### 3. Intelligent Retrieval
When searching, the system:
1. Queries the knowledge base
2. Calculates relevance scores
3. Retrieves full code for relevant matches
4. Returns formatted results

## Best Practices

### 1. Keep Index Updated
- Re-index after major refactoring
- The system auto-detects some changes
- Use "Refresh Index" for full update

### 2. Effective Queries
- Use descriptive search terms
- Include technical terms (e.g., "JWT authentication")
- Search by functionality, not just class names

### 3. Performance Tips
- Initial indexing is resource-intensive
- Subsequent searches are fast
- Index runs in background threads

## Troubleshooting

### Index Not Working
1. Check if API credentials are configured
2. Ensure OpenWebUI is accessible
3. Check the IDE logs for errors

### Missing Code in Results
1. Verify the file was indexed
2. Try more specific search terms
3. Re-index if files were recently added

### Performance Issues
1. Indexing is CPU-intensive - normal during first run
2. Close unnecessary applications during indexing
3. Index smaller modules separately if needed

## Architecture

```
com.zps.zest.rag/
├── RagAgent.java           # Main service for indexing and search
├── SignatureExtractor.java # Extracts signatures from PSI
├── CodeSignature.java      # Model for code signatures
├── ProjectInfoExtractor.java # Extracts project metadata
├── ProjectInfo.java        # Model for project info
├── RagSearchTool.java      # Agent tool for searching
├── IndexProjectAction.java # UI action for indexing
│
# Testability interfaces
├── KnowledgeApiClient.java # Interface for API operations
├── OpenWebUIKnowledgeClient.java # Production implementation
├── CodeAnalyzer.java       # Interface for code analysis
├── DefaultCodeAnalyzer.java # Production implementation
├── RagComponentFactory.java # Factory for dependency injection
```

## Testing & Testability

The RAG system is designed with testability in mind:

### 1. Dependency Injection
All major components use interfaces and dependency injection:

```java
// Production code
RagAgent agent = RagComponentFactory.createRagAgent(project);

// Test code with mocks
MockKnowledgeApiClient mockApi = new MockKnowledgeApiClient();
RagAgent agent = RagComponentFactory.createRagAgent(
    project, mockConfig, mockAnalyzer, mockApi
);
```

### 2. Interface-Based Design
Key components have interfaces for easy mocking:
- `KnowledgeApiClient` - API operations
- `CodeAnalyzer` - Code analysis operations

### 3. Testing Utilities
- `MockKnowledgeApiClient` - In-memory mock for API testing
- `RagComponentFactory` - Simplifies component creation
- Protected/package methods marked with `@VisibleForTesting`

### 4. Example Test
```java
@Test
public void testIndexing() {
    // Given
    MockKnowledgeApiClient mockApi = new MockKnowledgeApiClient();
    RagAgent agent = new RagAgent(project, config, analyzer, mockApi);
    
    // When
    agent.performIndexing(indicator, false);
    
    // Then
    assertEquals(1, mockApi.getKnowledgeBaseCount());
    assertTrue(mockApi.getFileCount() > 0);
}
```

## Future Enhancements

- Incremental indexing on file changes
- Support for more languages (Python, JavaScript)
- Semantic search using embeddings
- Cross-file relationship mapping
- Integration with refactoring tools
