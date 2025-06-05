# Project Mode - Enhanced Project Understanding

## Overview

Project Mode is a new mode in Zest that provides enhanced project understanding by automatically integrating your project's knowledge base (RAG) into every chat interaction. This mode gives the AI deep insight into your codebase without requiring manual context or code snippets.

## Features

### 1. Automatic Knowledge Integration
- Automatically includes your project's indexed knowledge in chat requests
- No need to manually provide code context
- AI has access to all indexed classes, methods, fields, and documentation

### 2. Enhanced Code Understanding
- **Interfaces**: Now properly indexed with all their methods
- **Javadoc**: Documentation is captured and included in the knowledge base
- **Type Information**: Full type parameters, bounds, and inheritance hierarchies
- **Modifiers**: All access modifiers, static, final, abstract, etc.

### 3. Project-Aware Responses
- AI understands your project's architecture and design patterns
- References actual code from your project, not generic examples
- Considers your project's dependencies and libraries
- Follows your project's coding conventions

## How to Use Project Mode

### 1. Index Your Project First
Before using Project Mode, you must index your project:
- Right-click in the editor → Zest → "Index Project for RAG"
- Or use the Knowledge Base Manager

### 2. Select Project Mode
In the Zest browser panel:
1. Click the "Mode" button
2. Select "Project Mode" from the dropdown
3. The AI now has access to your entire codebase

### 3. Ask Project-Specific Questions
Examples:
- "How does authentication work in this project?"
- "What classes implement the Repository pattern?"
- "Show me all methods that handle user data"
- "Explain the purpose of the ConfigurationManager class"
- "What design patterns are used in the payment module?"

## Technical Implementation

### Interceptor Enhancement
Project Mode uses a custom interceptor (`projectModeInterceptor.js`) that:
1. Detects when Project Mode is active
2. Retrieves the project's knowledge base ID
3. Adds the knowledge collection to the chat request
4. Enhances the system prompt with project context

### Request Structure
When in Project Mode, requests are enhanced with:
```json
{
  "messages": [...],
  "files": [
    {
      "type": "collection",
      "id": "kb-xxxxx-xxxxx"  // Your project's knowledge ID
    }
  ],
  "custom_tool": "Zest|PROJECT_MODE_CHAT"
}
```

### Enhanced Indexing
The improved SignatureExtractor now captures:
- All class types: classes, interfaces, enums, annotations
- Complete method signatures with type parameters and exceptions
- Field modifiers including volatile and transient
- Javadoc documentation for all elements
- Generic type bounds and constraints

## Benefits

### 1. Faster Development
- No need to copy/paste code into chat
- AI understands context immediately
- Get accurate, project-specific suggestions

### 2. Better Code Quality
- AI suggestions follow your project's patterns
- Consistent with existing code style
- Aware of your project's architecture

### 3. Improved Documentation
- AI can explain any part of your codebase
- Generate documentation based on actual code
- Answer questions about code functionality

## Comparison with Other Modes

| Feature | Neutral Mode | Dev Mode | Agent Mode | Project Mode |
|---------|--------------|----------|------------|--------------|
| System Prompt | ❌ | ✅ | ✅ | ✅ |
| Project Context | ❌ | ❌ | ✅ (current file) | ✅ (entire project) |
| RAG Integration | ❌ | ❌ | ❌ | ✅ |
| Code Suggestions | Generic | Generic | Context-aware | Project-specific |
| Tool Access | ❌ | ❌ | ✅ | ❌ |

## Troubleshooting

### "No knowledge base ID found"
- Make sure you've indexed your project first
- Check that indexing completed successfully
- Try re-indexing if the project structure changed significantly

### AI doesn't seem aware of my code
- Verify Project Mode is selected (not another mode)
- Check the browser console for any errors
- Ensure your OpenWebUI instance supports knowledge collections

### Performance Issues
- Large projects may take time to index initially
- Subsequent queries should be fast
- Consider indexing only relevant modules for very large projects

## Future Enhancements

- Incremental indexing on file changes
- Support for more languages (Python, JavaScript)
- Integration with refactoring suggestions
- Project-specific code generation templates
