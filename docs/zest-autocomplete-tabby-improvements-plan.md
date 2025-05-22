# Zest Autocomplete Enhancement Plan: Adopting Tabby ML Patterns

## Overview
This document outlines the plan to enhance Zest's autocomplete system by adopting proven patterns from Tabby ML IntelliJ plugin. The improvements focus on three key areas:

1. **Sophisticated Replace Range Handling** - Better handling of text replacement scenarios
2. **Partial Acceptance Support** - Word-by-word and line-by-line completion acceptance
3. **Message Bus Integration** - Better integration with IntelliJ's event system

## Current State Analysis

### Zest Current Architecture
- **Service**: `ZestAutocompleteService` - Project-level service with manual editor management
- **Rendering**: `ZestInlayRenderer` - Custom renderer with separate inline/block handling
- **Data Models**: `ZestCompletionData` - Simple completion item structures
- **Event Handling**: Manual document/caret listeners with direct service calls
- **Replace Range**: Simple offset-based replacement logic

### Tabby ML Architecture (Target)
- **Service**: `InlineCompletionService` - Message bus integrated service
- **Rendering**: `InlineCompletionRenderer` - Sophisticated renderer with smart text splitting
- **Data Models**: `InlineCompletionData` - Rich completion structures with metadata
- **Event Handling**: IntelliJ message bus with comprehensive event coverage
- **Replace Range**: Advanced logic handling 0/1/multiple character replacements

## Implementation Plan

### Phase 1: Sophisticated Replace Range Handling

#### 1.1 Enhance ZestCompletionData
**Target Files**: `ZestCompletionData.java`

**Current State**:
```java
// Simple replace range calculation
int startOffset = context.getOffset();
int endOffset = startOffset;
if (!currentLineSuffix.trim().isEmpty()) {
    if (completion.startsWith(currentLineSuffix.trim())) {
        endOffset = startOffset + currentLineSuffix.trim().length();
    }
}
```

**Target Enhancement**:
- Add `ReplaceRangeCalculator` utility class
- Implement Tabby's 0/1/multiple character replacement logic
- Add suffix length analysis methods
- Support intelligent text matching

**Tasks**:
- [ ] Create `ReplaceRangeCalculator.java` utility class
- [ ] Add `calculateReplaceRange(String insertText, int offset, String lineSuffix)` method
- [ ] Implement zero-character replacement logic
- [ ] Implement single-character smart matching
- [ ] Implement multi-character replacement with markup
- [ ] Add unit tests for replace range calculations

#### 1.2 Enhance ZestInlayRenderer
**Target Files**: `ZestInlayRenderer.java`

**Current State**:
- Basic multi-line handling
- Simple inline/block element creation
- Limited visual feedback

**Target Enhancement**:
- Adopt Tabby's sophisticated text splitting logic
- Add markup highlights for replace ranges
- Improve visual feedback for different replacement scenarios

**Tasks**:
- [ ] Refactor `renderMultiLineCompletion` method with Tabby's logic
- [ ] Add `markupReplaceText` method for visual highlights  
- [ ] Implement intelligent line splitting based on suffix length
- [ ] Add support for partial text replacement visualization
- [ ] Enhance rendering context with replace range information

### Phase 2: Partial Acceptance Support

#### 2.1 Add AcceptType Enum
**Target Files**: New `AcceptType.java`

**Target Enhancement**:
```java
public enum AcceptType {
    FULL_COMPLETION,
    NEXT_WORD, 
    NEXT_LINE
}
```

**Tasks**:
- [ ] Create `AcceptType.java` enum
- [ ] Define acceptance granularity levels
- [ ] Add helper methods for text parsing

#### 2.2 Enhance ZestAutocompleteService
**Target Files**: `ZestAutocompleteService.java`

**Current State**:
- Binary accept/reject model
- Full completion only
- Simple state management

**Target Enhancement**:
- Add partial acceptance methods
- Implement continuation logic for partial accepts
- Support word-by-word and line-by-line acceptance

**Tasks**:
- [ ] Add `acceptCompletion(Editor editor, AcceptType type)` method
- [ ] Implement word-boundary detection logic
- [ ] Implement line-boundary detection logic
- [ ] Add partial acceptance state tracking
- [ ] Add completion continuation after partial accept
- [ ] Update keyboard shortcuts for different accept types

#### 2.3 Update Actions
**Target Files**: `AcceptCompletionAction.java`, New actions

**Tasks**:
- [ ] Add `AcceptWordCompletionAction.java`
- [ ] Add `AcceptLineCompletionAction.java`
- [ ] Update keyboard shortcuts in `plugin.xml`
- [ ] Add action validation for partial acceptance scenarios

### Phase 3: Message Bus Integration

#### 3.1 Create Event Publishers
**Target Files**: New event classes

**Target Enhancement**:
- Replace manual listeners with message bus integration
- Add comprehensive event coverage
- Improve separation of concerns

**Tasks**:
- [ ] Create `ZestDocumentListener.java` interface
- [ ] Create `ZestCaretListener.java` interface  
- [ ] Create `ZestCompletionListener.java` interface
- [ ] Define event topics and message structures
- [ ] Add publisher/subscriber pattern implementation

#### 3.2 Refactor ZestAutocompleteService
**Target Files**: `ZestAutocompleteService.java`

**Current State**:
- Manual listener registration
- Direct service method calls
- Basic event handling

**Target Enhancement**:
- Message bus subscriber registration
- Event-driven architecture
- Better decoupling of components

**Tasks**:
- [ ] Replace manual listeners with message bus subscriptions
- [ ] Add `messageBusConnection` field
- [ ] Implement event handlers as message subscribers
- [ ] Add proper disposal of message bus connections
- [ ] Update initialization logic for message bus

#### 3.3 Integration with IntelliJ Systems
**Target Files**: Various

**Tasks**:
- [ ] Add integration with `LookupManagerListener`
- [ ] Add integration with `FileEditorManagerListener`
- [ ] Add proper lifecycle management
- [ ] Update dispose() methods for message bus cleanup

### Phase 4: Enhanced Visual Feedback

#### 4.1 Improve Rendering Quality
**Target Files**: `ZestInlayRenderer.java`

**Tasks**:
- [ ] Add anti-aliasing and better graphics rendering
- [ ] Implement theme-aware coloring
- [ ] Add visual indicators for multi-line completions
- [ ] Improve font handling and sizing
- [ ] Add accessibility improvements

#### 4.2 Add Replace Range Markup
**Target Files**: `ZestInlayRenderer.java`

**Tasks**:
- [ ] Add `createReplaceRangeMarkup` method
- [ ] Implement visual highlighting for text to be replaced
- [ ] Add different visual styles for different replace scenarios
- [ ] Support highlighting in dark/light themes

## Implementation Timeline

### Week 1-2: Replace Range Enhancement
- Implement `ReplaceRangeCalculator`
- Update `ZestCompletionData` with enhanced range logic
- Add unit tests for replace range calculations

### Week 3-4: Partial Acceptance
- Add `AcceptType` enum and related logic
- Implement partial acceptance in `ZestAutocompleteService`
- Create new action classes and update keyboard shortcuts

### Week 5-6: Message Bus Integration
- Create event interfaces and topics
- Refactor service to use message bus
- Update all listener registrations

### Week 7: Visual Enhancements
- Improve rendering quality
- Add replace range markup
- Final testing and bug fixes

## Testing Strategy

### Unit Tests
- `ReplaceRangeCalculatorTest.java` - Test all replace range scenarios
- `AcceptTypeTest.java` - Test word and line boundary detection
- `PartialAcceptanceTest.java` - Test partial acceptance logic

### Integration Tests
- Message bus event flow testing
- Visual rendering verification
- Performance impact assessment

### Manual Testing Scenarios
1. **Zero-character replacement**: Type completion that matches existing text
2. **Single-character replacement**: Type completion that replaces one character
3. **Multi-character replacement**: Type completion that replaces multiple characters
4. **Partial acceptance**: Accept word-by-word and line-by-line
5. **Message bus events**: Verify proper event handling and cleanup

## Risk Mitigation

### Backward Compatibility
- Keep existing API methods with deprecation warnings
- Provide fallback mechanisms for edge cases
- Gradual migration strategy for existing functionality

### Performance Considerations
- Profile message bus overhead
- Optimize rendering performance for complex completions
- Cache frequently calculated replace ranges

### Error Handling
- Graceful degradation when sophisticated logic fails
- Comprehensive logging for debugging
- Fallback to simple replacement when needed

## Success Metrics

### User Experience Improvements
- More accurate text replacement
- Better visual feedback
- Smoother partial acceptance workflow

### Technical Improvements  
- Reduced coupling between components
- Better integration with IntelliJ platform
- More maintainable and extensible code

### Performance Metrics
- No regression in completion response time
- Improved rendering performance
- Reduced memory usage through better lifecycle management

## Future Enhancements

### Phase 5 (Future)
- **Completion Cycling**: Navigate between multiple completion options
- **Smart Caching**: Enhanced caching with modification stamp tracking
- **Telemetry Integration**: Usage analytics and performance monitoring
- **Advanced Context**: Integration with IntelliJ's completion system

## Conclusion

This plan provides a systematic approach to adopting Tabby ML's proven patterns while maintaining Zest's custom LLM integration advantages. The phased implementation allows for gradual enhancement with minimal disruption to existing functionality.

The key benefits include:
- **Better User Experience**: More sophisticated text handling and partial acceptance
- **Improved Architecture**: Better integration with IntelliJ platform
- **Enhanced Maintainability**: Cleaner separation of concerns and event handling
- **Future-Proof Design**: Foundation for advanced autocomplete features

Each phase builds upon the previous one, ensuring a stable and progressive enhancement of the autocomplete system.
