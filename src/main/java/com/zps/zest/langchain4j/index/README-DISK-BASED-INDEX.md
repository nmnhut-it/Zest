# Disk-Based Index Implementation

This document describes the disk-based storage implementation for the Hybrid Code Search indices.

## Overview

The disk-based implementation provides memory-efficient storage for large code bases by:
- Storing data on disk with configurable in-memory LRU caches
- Using memory-mapped files for efficient I/O
- Maintaining the same API as the in-memory implementation

## Architecture

### DiskBasedNameIndex
- **Storage**: JSON files in `.idea/zest/name-index/`
- **Token Index**: Kept in memory (relatively small)
- **Element Cache**: LRU cache with 10,000 items
- **Features**:
  - Individual element files for better performance
  - Batch writes to reduce I/O
  - Thread-safe with read/write locks

### DiskBasedSemanticIndex
- **Storage**: 
  - Binary embeddings in memory-mapped file (`.idea/zest/semantic-index/embeddings.bin`)
  - Metadata and text segments in JSON files
- **Embedding Cache**: LRU cache with 1,000 embeddings
- **Features**:
  - Memory-mapped file for efficient embedding access
  - Auto-expanding file size
  - Thread-safe operations

### DiskBasedStructuralIndex
- **Storage**: 
  - Elements in hash-based directory structure (`.idea/zest/structural-index/elements/`)
  - Reverse indices in JSON file
- **Element Cache**: LRU cache with 5,000 elements
- **Features**:
  - Hash-based directory structure to avoid too many files per directory
  - Background disk writes using executor service
  - Batch write buffer

## Key Design Decisions

1. **LRU Caching**: Frequently accessed items stay in memory for fast access
2. **Thread Safety**: All caches use synchronized maps and read/write locks
3. **Lazy Loading**: Elements are loaded from disk only when needed
4. **Batch Operations**: Write operations are batched to reduce I/O
5. **Memory-Mapped Files**: Used for embeddings to provide efficient random access

## Configuration

Configure via Settings → Tools → Hybrid Code Search Index:
- **Use disk-based storage**: Toggle between disk and memory storage
- **Cache sizes**: Configure cache size for each index type
- **Auto-persist**: Enable periodic saving to disk
- **Memory limit**: Set maximum memory usage

## Performance Considerations

1. **Initial Load**: Loading from disk is slower than in-memory
2. **Cache Hits**: Performance depends on cache hit rate
3. **Disk I/O**: Batch operations reduce disk access frequency
4. **Memory Usage**: Configurable based on available memory

## File Structure

```
.idea/zest/
├── name-index/
│   ├── elements.json        # All elements (fallback)
│   ├── tokens.json         # Token index
│   └── elements/           # Individual element files
├── semantic-index/
│   ├── embeddings.bin      # Binary embeddings (memory-mapped)
│   ├── metadata.json       # Element metadata
│   └── segments.json       # Text segments
└── structural-index/
    ├── reverse-index.json  # Reverse relationship indices
    └── elements/           # Hash-based directory structure
        ├── 00/
        │   └── 00/         # Element files
        └── ...
```

## Error Handling

- Failed disk operations are logged but don't block indexing
- Corrupted files are detected and skipped
- Missing files trigger re-indexing

## Future Improvements

1. **Compression**: Compress JSON files to save disk space
2. **Sharding**: Split large indices into multiple files
3. **Incremental Updates**: Track changes more efficiently
4. **Background Optimization**: Periodic defragmentation and cleanup
