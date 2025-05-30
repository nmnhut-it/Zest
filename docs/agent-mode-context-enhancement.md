# Agent Mode Context Enhancement

## Overview

Agent Mode now automatically enhances prompts with relevant context from your project. When you send a message in Agent Mode, the system will:

1. **Generate Keywords** - Uses LLM to extract up to 10 relevant keywords from your query
2. **Search Git History** - Finds recent commits related to your query (priority, max 2 results)
3. **Search Project Files** - Finds functions and code snippets matching keywords (max 5 results)
4. **Enhance Prompt** - Adds the found context to the system prompt
5. **Cache Results** - Caches search results for 5 minutes to improve performance

## User Experience

When you send a message in Agent Mode:
- A notification appears: "Collecting project context..."
- The system searches your project in the background
- When complete: "Context collected successfully!"
- Your message is then sent with enhanced context

## Implementation Details

### Java Components

1. **AgentModeContextEnhancer.java**
   - Orchestrates the context collection process
   - Manages result caching
   - Coordinates between git and file searches

2. **KeywordGeneratorService.java**
   - Calls LLM API to generate keywords
   - Falls back to simple extraction if LLM unavailable
   - Limits keywords to 10 for efficiency

3. **OpenWebUIAgentModePromptBuilder.java**
   - Builds the enhanced prompt with context
   - Formats git and code results for the LLM

### JavaScript Components

1. **interceptor.js**
   - Intercepts API calls to OpenWebUI
   - Calls IDE to get enhanced prompt for Agent Mode
   - Handles async prompt building

2. **agentModeNotifications.js**
   - Shows toast notifications during context collection
   - Visual feedback for users

### Search Priority

1. **Git History** (Priority 1)
   - Recent changes are most relevant
   - Limited to 2 results to keep context focused

2. **Function Search** (Priority 2)
   - Finds complete function definitions
   - Includes function signatures and types

3. **Text Search** (Priority 3)
   - Fallback when no functions found
   - Includes context lines around matches

## Benefits

- **Better Code Suggestions**: LLM has context about existing patterns
- **Consistent Style**: Finds similar code to maintain consistency
- **Recent Context**: Prioritizes recent changes from git
- **Performance**: Caching prevents redundant searches
- **Transparency**: User sees when context is being collected

## Example Flow

```
User: "Create a button handler for submitting the form"
↓
System generates keywords: ["button", "handler", "submit", "form", "onClick", "handleSubmit"]
↓
Searches git: Found recent commit "Added form validation handlers"
↓
Searches files: Found handleClick(), onSubmit(), validateForm()
↓
Enhanced prompt includes: "Found similar patterns in project..."
↓
LLM generates code following existing patterns
```

## Troubleshooting

If context collection fails:
- Check IDE logs for errors
- Ensure git is accessible from project
- Verify LLM API is configured correctly
- Context enhancement will fall back to basic prompt on errors
