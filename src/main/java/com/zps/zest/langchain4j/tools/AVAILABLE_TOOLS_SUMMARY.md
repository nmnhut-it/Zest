# Available Code Exploration Tools Summary

## Tool Locations
All tools are located in: `src/main/java/com/zps/zest/langchain4j/tools/impl/`

## Available Tools (12 total)

### Code Search & Navigation
1. **SearchCodeTool.java** - Natural language code search across entire codebase
2. **FindByNameTool.java** - Find code elements by name/identifier (partial match)
3. **FindSimilarTool.java** - Find semantically similar code using embeddings

### Relationship & Usage Analysis
4. **FindCallersTool.java** - Find all methods calling a specific method
5. **FindUsagesTool.java** - Find all usages of classes, methods, or fields
6. **FindRelationshipsTool.java** - Discover structural relationships (calls, inheritance, implementations)
7. **FindImplementationsTool.java** - Find implementations of interfaces/abstract methods

### Code Structure Inspection
8. **GetClassInfoTool.java** - Get detailed class information (fields, methods, hierarchy)
9. **FindMethodsTool.java** - List all methods in a class/interface
10. **GetCurrentContextTool.java** - Get current IDE context (open file, cursor position)

### File System Operations
11. **ListFilesInDirectoryTool.java** - List files in directories with filtering
12. **ReadFileTool.java** - Read complete file contents

## Base Classes
- **BaseCodeExplorationTool.java** - Common functionality for all tools
- **ThreadSafeCodeExplorationTool.java** - Thread-safe base for PSI operations
- **CodeExplorationTool.java** - Interface defining tool contract

These tools provide comprehensive code exploration capabilities for LLM agents, enabling navigation, search, and analysis of Java projects through natural language queries and structured commands.
