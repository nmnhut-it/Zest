# Zest Autocomplete Feature Documentation

## Overview

The Zest autocomplete feature provides AI-powered code completion suggestions within IntelliJ IDEA. It's been enhanced based on Tabby ML patterns to deliver fast, accurate, and contextually relevant code completions.

## Architecture

### Core Components

```
src/main/java/com/zps/zest/autocomplete/
├── ZestAutocompleteService.java          # Main service orchestrator
├── AutocompleteApiStage.java             # API integration and response processing
├── ZestCompletionData.java               # Data structures for completions
├── ZestInlayRenderer.java                # Visual rendering of completions
├── ZestPendingCompletion.java            # Legacy completion wrapper
├── ZestAutocompleteProjectComponent.java # Project initialization
├── actions/                              # User interaction handlers
│   ├── AcceptCompletionAction.java
│   ├── RejectCompletionAction.java
│   └── TestAutocompleteAction.java
├── listeners/                            # Editor event handlers
│   ├── ZestAutocompleteDocumentListener.java
│   └── ZestAutocompleteCaretListener.java
├── ui/
│   └── ZestAutocompleteStatusWidget.java
└── utils/                                # Helper utilities
    ├── AutocompletePromptBuilder.java
    ├── ContextGatherer.java
    └── CompletionCache.java
```

## Key Classes

### 1. ZestAutocompleteService

**Purpose**: Central orchestrator for all autocomplete functionality

**Key Methods**:
- `triggerAutocomplete(Editor editor)` - Initiates completion request
- `acceptCompletion(Editor editor)` - Accepts displayed completion
- `rejectCompletion(Editor editor)` - Dismisses completion
- `clearCompletion(Editor editor)` - Clears any active completion

**Lifecycle**:
1. Registers/unregisters editors automatically
2. Manages completion cache (LRU, max 100 items)
3. Handles API requests in background threads
4. Coordinates with rendering system

### 2. AutocompleteApiStage

**Purpose**: Handles LLM API integration with autocomplete-specific optimizations

**Key Features**:
- Enhanced prompt generation using Tabby ML patterns
- Aggressive response cleaning and validation
- Configuration optimization (temperature=0.2, max_tokens=150)
- Fallback mechanisms for robustness

**Response Processing Pipeline**:
```
Raw LLM Response → Clean Markdown → Remove Explanations → Validate Code → Filter Length
```

### 3. ContextGatherer

**Purpose**: Extracts relevant code context for better completions

**Key Methods**:
- `gatherEnhancedCursorContext(Editor, PsiFile)` - Modern context extraction
- `gatherCursorContext(Editor, PsiFile)` - Legacy compatibility method
- `gatherFileContext(Editor, PsiFile)` - Broader file context

**Context Extraction**:
- **Prefix**: Up to 10 lines before cursor + current line prefix
- **Suffix**: Current line suffix + up to 5 lines after cursor
- **File**: Structural elements (imports, class declarations, method signatures)

### 4. AutocompletePromptBuilder

**Purpose**: Constructs optimized prompts for the LLM

**Approaches**:
- **Minimal** (recommended): Simple, Tabby ML-style prompts
- **Legacy** (fallback): Verbose, instruction-heavy prompts

**Example Minimal Prompt**:
```
Complete the code. Only return the completion text, no explanations.

```java
public class Example {
    public void method() {
        String text = "hello";<CURSOR>
    }
}
```
```

### 5. ZestInlayRenderer

**Purpose**: Visual rendering of completions in the editor

**Features**:
- Single-line inline rendering
- Multi-line block rendering with indicators
- Replace range highlighting
- Theme-aware styling

**Rendering Pipeline**:
```
CompletionItem → Calculate Visible Text → Create Inlays → Display → Track for Cleanup
```

## Data Flow

### 1. Trigger Flow
```
User Types → DocumentListener → Service.triggerAutocomplete() →
Delay (250ms) → ContextGatherer → PromptBuilder → API Call →
Response Processing → Cache → Renderer → Display
```

### 2. Accept Flow
```
User Presses Tab → AcceptCompletionAction → Service.acceptCompletion() →
Document Modification → Cursor Movement → Cleanup
```

### 3. Reject Flow
```
User Presses Esc → RejectCompletionAction → Service.rejectCompletion() →
Clear Rendering → Cleanup
```

## Configuration

### Settings (in ConfigurationManager)

```java
// Autocomplete settings
private boolean enableAutocomplete = true;           // Master toggle
private int autocompleteDelay = 300;                 // Trigger delay (ms)
private boolean enableMultilineCompletions = true;   // Multi-line support
private String autocompleteModel = "code_expert";    // Model preference
```

### API Optimization

```java
// AutocompleteApiStage constants
private static final int AUTOCOMPLETE_TIMEOUT_MS = 8000;     // API timeout
private static final int MAX_COMPLETION_TOKENS = 150;       // Token limit
private static final double AUTOCOMPLETE_TEMPERATURE = 0.2; // Determinism
private static final double AUTOCOMPLETE_TOP_P = 0.8;       // Focus
```

## Testing & Debugging

### Test Action
Use `TestAutocompleteAction` to manually trigger and debug:
- Shows service status
- Displays cache statistics
- Manually triggers completion
- Reports errors

### Logging
Enable debug logging for:
```
com.zps.zest.autocomplete.ZestAutocompleteService
com.zps.zest.autocomplete.AutocompleteApiStage
com.zps.zest.autocomplete.utils.ContextGatherer
```

### Common Issues & Solutions

1. **No completions appearing**:
    - Check `isAutocompleteEnabled()` in config
    - Verify API connectivity
    - Check document/caret listeners are registered

2. **Poor completion quality**:
    - Review prompt construction in `AutocompletePromptBuilder`
    - Check context gathering in `ContextGatherer`
    - Verify response cleaning in `AutocompleteApiStage`

3. **Performance issues**:
    - Monitor cache hit rates
    - Check API response times
    - Verify proper cleanup of rendering contexts

## Development Guidelines

### Adding New Features

1. **Context Enhancement**:
    - Modify `ContextGatherer` to extract additional context
    - Update `AutocompletePromptBuilder` to utilize new context
    - Test with various code scenarios

2. **Rendering Improvements**:
    - Extend `ZestInlayRenderer` for new visual features
    - Update `ZestCompletionData` if new data structures needed
    - Ensure proper cleanup in `RenderingContext.dispose()`

3. **API Optimizations**:
    - Modify `AutocompleteApiStage` for API improvements
    - Update response processing pipeline
    - Add new validation rules as needed

### Code Style

- Follow existing IntelliJ plugin patterns
- Use proper logging with appropriate levels
- Handle exceptions gracefully with fallbacks
- Maintain backward compatibility where possible
- Document public APIs thoroughly

### Testing Strategy

1. **Unit Tests**: Focus on `ContextGatherer` and `AutocompletePromptBuilder`
2. **Integration Tests**: Test full completion flow
3. **Manual Testing**: Use `TestAutocompleteAction` extensively
4. **Performance Tests**: Monitor cache efficiency and API response times

## Plugin Integration

### Registration (plugin.xml)
```xml
<extensions defaultExtensionNs="com.intellij">
    <projectService serviceImplementation="com.zps.zest.autocomplete.ZestAutocompleteService"/>
</extensions>

<actions>
    <action id="ZestAcceptCompletion" 
            class="com.zps.zest.autocomplete.actions.AcceptCompletionAction">
        <keyboard-shortcut keymap="$default" first-keystroke="TAB"/>
    </action>
    <action id="ZestRejectCompletion" 
            class="com.zps.zest.autocomplete.actions.RejectCompletionAction">
        <keyboard-shortcut keymap="$default" first-keystroke="ESCAPE"/>
    </action>
</actions>
```

### Service Lifecycle
- **Initialization**: Automatic via `ZestAutocompleteProjectComponent`
- **Editor Registration**: Automatic via `EditorFactoryListener`
- **Cleanup**: Automatic on project close/editor release

## Future Enhancements

### Planned Features
1. **Partial Acceptance**: Accept word/line at a time
2. **Multiple Suggestions**: Cycle through alternatives
3. **Smart Triggering**: Context-aware activation
4. **Analytics**: Completion acceptance rates
5. **Model Selection**: Per-language model preferences

### Extension Points
1. **Custom Context Providers**: Plugin-specific context extraction
2. **Custom Renderers**: Alternative completion displays
3. **Custom Validators**: Domain-specific completion filtering
4. **Custom Triggers**: Non-typing based activation

## Troubleshooting

### Debug Checklist
1. ✅ Service initialized? Check project component logs
2. ✅ Listeners registered? Check editor factory events
3. ✅ Context extracted? Check context gatherer output
4. ✅ API called? Check autocomplete API stage logs
5. ✅ Response received? Check API response processing
6. ✅ Rendering attempted? Check inlay renderer logs

### Performance Monitoring
```java
// Add to ZestAutocompleteService for monitoring
public String getPerformanceStats() {
    return String.format(
        "Cache: %d/%d, Active: %d, Last API: %dms",
        completionCache.size(), MAX_CACHE_SIZE,
        activeCompletions.size(), lastApiDuration
    );
}
```

This documentation provides a comprehensive guide for developers to understand, maintain, and extend the Zest autocomplete feature.