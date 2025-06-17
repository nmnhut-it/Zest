# Lean Strategy Implementation for Zest Completion

This document describes the new **Lean Strategy** implementation that provides A/B testing capabilities for code completion approaches.

## Overview

The system now supports two completion strategies:

1. **SIMPLE Strategy** (Original): Fast FIM-based completions using prefix/suffix context
2. **LEAN Strategy** (New): Reasoning-based completions using full file context with diff extraction

## Architecture

### Strategy Pattern Implementation

```kotlin
// ZestCompletionProvider now supports multiple strategies
enum class CompletionStrategy {
    SIMPLE,  // Original FIM-based approach  
    LEAN     // Full-file with reasoning approach
}

// Switch strategies at runtime
completionProvider.setStrategy(CompletionStrategy.LEAN)
```

### Lean Strategy Components

#### 1. ZestLeanContextCollector
- Collects **full file content** instead of just prefix/suffix
- Inserts `[CURSOR]` marker at cursor position
- Detects cursor context type (method body, class declaration, imports, etc.)

#### 2. ZestLeanPromptBuilder  
- Creates reasoning-based prompts with context analysis
- Provides different prompt strategies based on cursor context
- Includes language-specific guidelines and best practices

#### 3. ZestLeanResponseParser
- Extracts reasoning and code sections from AI response
- Uses diff library to calculate changes between original and AI-generated file
- Falls back to simple parsing if diff library unavailable
- Extracts only relevant changes near cursor position

## Key Features

### Reasoning Prompts
```kotlin
fun buildReasoningPrompt(context: LeanContext): String {
    return """
You are an expert ${context.language} developer. Analyze the following code file and complete it at the cursor position marked with [CURSOR].

**Analysis Instructions:**
- What is happening around the [CURSOR] position?
- What patterns do I see in the existing code?
- What would be the most logical completion?
- What related changes (imports, etc.) might be needed?

<reasoning>
[Your analysis and reasoning here]
</reasoning>

<code>
[Complete file with changes]
</code>
"""
}
```

### Diff-Based Change Extraction
- AI generates complete modified file
- Diff library calculates precise changes
- Extracts only changes near cursor position
- Handles multiple related changes (imports + methods)

### Context-Aware Completions
- **METHOD_BODY**: Focus on implementing method logic
- **CLASS_DECLARATION**: Add fields, methods, constructors
- **IMPORT_SECTION**: Add necessary imports
- **VARIABLE_ASSIGNMENT**: Appropriate value assignment
- **AFTER_OPENING_BRACE**: Block content completion

## Usage

### Switching Strategies

#### Via Action
Use the "Switch Completion Strategy" action to toggle between SIMPLE and LEAN modes.

#### Programmatically
```kotlin
val completionService = project.getService(ZestInlineCompletionService::class.java)
completionService.setCompletionStrategy(CompletionStrategy.LEAN)
```

### Testing Components
Use the "Test Lean Strategy" action to verify all components are working correctly.

## Configuration Differences

### SIMPLE Strategy
- **Timeout**: 8 seconds
- **Max Tokens**: 16 (small completions)
- **Model**: qwen/qwen2.5-coder-32b-instruct
- **Temperature**: 0.1 (deterministic)

### LEAN Strategy  
- **Timeout**: 15 seconds (reasoning takes longer)
- **Max Tokens**: 1000 (full file generation)
- **Model**: local-model (full model)
- **Temperature**: 0.2 (slightly creative for reasoning)

## Benefits of Lean Strategy

1. **Better Context**: AI sees full file structure
2. **Multiple Changes**: Can add imports + methods in one go
3. **Coherent Code**: Understands overall file context
4. **Reasoning**: AI explains its thought process
5. **Quality**: More thoughtful completions

## Fallback Behavior

- Lean strategy falls back to simple strategy on error
- Diff parsing falls back to simple text analysis if library unavailable
- Maintains compatibility with existing workflows

## Performance Considerations

- Lean strategy is slower but higher quality
- Simple strategy remains fast for quick completions
- Users can choose based on their needs
- A/B testing helps determine optimal strategy per use case

## Future Enhancements

1. **Adaptive Strategy**: Automatically choose strategy based on context
2. **Hybrid Mode**: Use lean for complex contexts, simple for basic completions
3. **Learning**: Track which strategy works better for different scenarios
4. **Caching**: Cache reasoning results for similar contexts

## File Structure

```
src/main/kotlin/com/zps/zest/completion/
├── ZestCompletionProvider.kt          # Strategy management
├── context/
│   ├── ZestSimpleContextCollector.kt  # Original simple context
│   └── ZestLeanContextCollector.kt    # New full-file context
├── prompt/
│   ├── ZestSimplePromptBuilder.kt     # Original FIM prompts
│   └── ZestLeanPromptBuilder.kt       # New reasoning prompts  
├── parser/
│   ├── ZestSimpleResponseParser.kt    # Original simple parsing
│   └── ZestLeanResponseParser.kt      # New diff-based parsing
└── actions/
    ├── ZestSwitchStrategyAction.kt    # Strategy switching
    └── ZestTestLeanStrategyAction.kt  # Component testing
```

## Getting Started

1. **Test Components**: Run "Test Lean Strategy" action
2. **Switch Mode**: Use "Switch Completion Strategy" action  
3. **Compare Results**: Try both strategies on same code
4. **Monitor Logs**: Check IDE logs for detailed analysis
5. **Provide Feedback**: Note which strategy works better for different scenarios

The lean strategy represents a significant evolution in code completion capabilities, providing more intelligent and context-aware suggestions while maintaining the option to use the fast, simple approach when needed.
