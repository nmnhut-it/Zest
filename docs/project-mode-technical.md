# Project Mode Technical Implementation

## Overview

Project Mode enhances chat requests by automatically including the complete project knowledge collection from OpenWebUI's RAG system. This document explains the technical implementation details.

## Architecture Changes

### 1. Knowledge Collection Model

Created `KnowledgeCollection.java` to match OpenWebUI's API response structure:

```java
public class KnowledgeCollection {
    private String id;
    private String userId;
    private String name;
    private String description;
    private KnowledgeData data;    // Contains file_ids array
    private List<FileMetadata> files; // Complete file metadata
    private String type = "collection";
    private String status = "processed";
    // ... other fields
}
```

### 2. API Client Enhancement

Extended `KnowledgeApiClient` interface:

```java
KnowledgeCollection getKnowledgeCollection(String knowledgeId) throws IOException;
```

Implementation in `OpenWebUIKnowledgeClient`:
- Makes GET request to `/api/v1/knowledge/{id}`
- Returns complete collection object with all metadata

### 3. JavaScript Bridge

Added new action in `JavaScriptBridgeActions`:

```java
case "getProjectKnowledgeCollection":
    return getProjectKnowledgeCollection();
```

This action:
1. Gets knowledge ID from configuration
2. Fetches complete collection via `RagAgent`
3. Returns JSON representation to JavaScript

### 4. Project Mode Interceptor

Enhanced `projectModeInterceptor.js`:

```javascript
// Fetch complete collection on mode activation
window.loadProjectKnowledgeCollection = function() {
    window.intellijBridge.callIDE('getProjectKnowledgeCollection', {})
        .then(function(response) {
            if (response.success) {
                window.__project_knowledge_collection__ = response.result;
            }
        });
};

// Include complete collection in requests
window.enhanceWithProjectKnowledge = function(data) {
    const knowledgeCollection = window.__project_knowledge_collection__;
    if (knowledgeCollection) {
        data.files.push(knowledgeCollection); // Complete object, not just ID
    }
};
```

## Request Flow

1. **Mode Activation**: User selects "Project Mode"
2. **Collection Fetch**: JavaScript calls `getProjectKnowledgeCollection`
3. **API Call**: Backend fetches from `/api/v1/knowledge/{id}`
4. **Cache Storage**: Complete collection stored in `window.__project_knowledge_collection__`
5. **Request Enhancement**: On chat, complete collection added to `files` array
6. **OpenWebUI Processing**: Backend receives full collection metadata

## Key Differences from Initial Implementation

### Before (Incorrect)
```javascript
data.files.push({
    type: 'collection',
    id: knowledgeId
});
```

### After (Correct)
```javascript
data.files.push({
    id: "kb-xxxxx",
    user_id: "...",
    name: "project-code-...",
    description: "...",
    data: { file_ids: [...] },
    files: [
        {
            id: "file-xxx",
            meta: {
                name: "...",
                content_type: "text/markdown",
                size: 1234
            }
        }
        // ... more files
    ],
    type: "collection",
    status: "processed"
    // ... other fields
});
```

## Benefits

1. **Complete Context**: OpenWebUI receives all metadata needed for RAG
2. **Proper Integration**: Matches expected API structure
3. **Caching**: Collection fetched once per session, not per request
4. **Error Handling**: Graceful fallback if collection unavailable

## Performance Considerations

- Collection fetched only when Project Mode activated
- Cached in browser memory for session duration
- Cleared when switching away from Project Mode
- Re-fetched after project re-indexing

## Testing

Updated `MockKnowledgeApiClient` to support `getKnowledgeCollection`:
- Returns mock collection with proper structure
- Maintains test compatibility
- Allows testing of error scenarios
