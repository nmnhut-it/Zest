# Code Extraction Feature - Research Agent

## Overview
When the Research Agent completes its search (either by reaching high confidence or after exploring all relevant paths), it now performs a final code extraction step. This extracts and consolidates all relevant code pieces that directly help answer the user's query.

## How It Works

### 1. Search Completion Detection
The system determines search is complete when:
- **HIGH confidence** is achieved (best understanding of context)
- **MEDIUM confidence** after 3+ iterations (good enough understanding)
- No missing context is identified
- Maximum iterations reached (unless confidence is very LOW)

### 2. Code Extraction Process
When search completes successfully with results:

```java
// Extract relevant code if search completed successfully
if (searchCompleted && context.getTotalResultsFound() > 0) {
    JsonObject extractedCode = codeExtractor.extractRelevantCode(
        userQuery, context
    ).get(30, TimeUnit.SECONDS);
    
    // Add extracted code to context
    context.setExtractedCode(extractedCode);
}
```

### 3. Intelligent Extraction
The `CodeExtractor` uses LLM to:
- Identify which code pieces directly answer the query
- Group related code together
- Include necessary context (imports, class definitions)
- Prioritize by relevance
- Include usage examples if found

### 4. Extraction Criteria
The extractor focuses on:
- **Direct Relevance**: Code must help answer the query
- **Complete Implementations**: Not just signatures
- **Related Utilities**: Include if they're used
- **Configuration**: Include if relevant
- **Exclusion**: Unrelated code is filtered out

## Extracted Code Structure

The extracted code is organized into categories:

```json
{
  "summary": "Brief summary of what was found",
  "main_components": [
    {
      "name": "Component/Function name",
      "file": "File path",
      "purpose": "What this does",
      "code": "Complete code implementation"
    }
  ],
  "utilities": [
    {
      "name": "Utility name",
      "file": "File path",
      "code": "Utility code"
    }
  ],
  "usage_examples": [
    {
      "description": "Example description",
      "code": "Example code"
    }
  ],
  "configuration": [
    {
      "type": "Config type",
      "code": "Configuration code"
    }
  ]
}
```

## Benefits

1. **Focused Results**: Only relevant code is presented to the user
2. **Complete Context**: All necessary pieces are included
3. **Organized Structure**: Code is categorized for easy understanding
4. **Ready to Use**: Complete implementations, not just references
5. **Examples Included**: Usage examples help understanding

## Example Scenarios

### Scenario 1: "How to handle user authentication?"
Extracts:
- Main authentication service/controller
- Password utilities (encoders, validators)
- Configuration (security config, JWT settings)
- Usage examples from tests

### Scenario 2: "Database connection pooling"
Extracts:
- Connection pool configuration
- Database utilities
- Connection manager implementation
- Example usage in repositories

### Scenario 3: "React component for file upload"
Extracts:
- File upload component
- Related hooks and utilities
- CSS/styling if relevant
- Usage examples from other components

## Integration with Research Results

The extracted code is added to the final context:

```json
{
  "userQuery": "...",
  "keywords": [...],
  "recentChanges": [...],
  "unstagedChanges": [...],
  "relatedCode": [...],
  "extractedCode": {
    // Organized, relevant code pieces
  }
}
```

## Fallback Behavior

If LLM extraction fails, the system:
1. Performs default extraction based on patterns
2. Categorizes code by naming conventions
3. Includes small complete files
4. Groups utilities, tests, and configs

## Future Enhancements

1. **Dependency Resolution**: Automatically include dependent code
2. **Import Analysis**: Include necessary imports
3. **Test Coverage**: Show which extracted code has tests
4. **Version Compatibility**: Note framework versions
5. **Performance Hints**: Include performance considerations