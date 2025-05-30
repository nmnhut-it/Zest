# Search Limits Configuration

## Overview
The Research Agent now implements search result limits to prevent overwhelming the LLM and focus on the most relevant findings.

## Current Limits

### 1. **EnhancedProjectSearchStrategy**
- **MAX_RESULTS_PER_KEYWORD**: 5 results per keyword
- **MAX_PROJECT_RESULTS**: 5 total result groups
- **MAX_TEXT_RESULTS_PER_KEYWORD**: 2 text search results per keyword
- **MAX_LINES_FOR_FULL_CONTENT**: 150 lines (files larger than this won't include full content)

Example flow for a keyword:
1. Search for functions with the keyword
2. Limit function results to 5
3. If less than 5 results found, search for text matches
4. Add up to (5 - function results) text matches, max 2

### 2. **GitSearchStrategy**
- **MAX_GIT_RESULTS**: 3 keyword groups with commits

### 3. **UnstagedSearchStrategy**
- **MAX_UNSTAGED_RESULTS**: 3 files with changes

## Search Distribution Example

For a research iteration with 10 keywords:

**Best Case Scenario** (all keywords find results):
- Git: Up to 3 keywords with commits
- Unstaged: Up to 3 files with changes  
- Project: Up to 5 keywords with up to 5 results each

**Typical Distribution**:
```
Keyword 1: 3 functions + 2 text matches = 5 results
Keyword 2: 5 functions = 5 results
Keyword 3: 0 functions + 2 text matches = 2 results
Keyword 4: 1 function + 2 text matches = 3 results
Keyword 5: 4 functions = 4 results
(Stops here due to MAX_PROJECT_RESULTS = 5)
```

## Benefits of Limits

1. **LLM Token Efficiency**: Prevents exceeding context windows
2. **Focused Analysis**: Forces selection of most relevant results
3. **Faster Processing**: Less data to analyze per iteration
4. **Better Iterations**: LLM can request specific refinements

## Configuration Points

### To adjust limits, modify these constants:

**EnhancedProjectSearchStrategy.java**:
```java
private static final int MAX_RESULTS_PER_KEYWORD = 5; 
private static final int MAX_PROJECT_RESULTS = 5;
private static final int MAX_TEXT_RESULTS_PER_KEYWORD = 2;
```

**GitSearchStrategy.java**:
```java
private static final int MAX_GIT_RESULTS = 3;
```

**UnstagedSearchStrategy.java**:
```java
private static final int MAX_UNSTAGED_RESULTS = 3;
```

## Impact on Search Quality

With these limits:
- Each iteration is more focused and manageable
- The LLM can better analyze what it receives
- Subsequent iterations can drill down into specific areas
- The final code extraction filters to only what's needed

## Future Enhancements

1. **Dynamic Limits**: Adjust based on result quality/relevance
2. **Priority Scoring**: Sort results by relevance before limiting
3. **Category Balancing**: Ensure mix of functions/text/files
4. **User Configurable**: Allow users to adjust limits based on needs