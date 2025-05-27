# Inline Chat Diff Integration

This document describes the integration of IntelliJ's diff system with the inline chat feature.

## Overview

The inline chat feature has been enhanced to use IntelliJ's built-in diff system for more accurate code comparison and better visualization of AI-suggested changes.

## Key Changes

### 1. InlineChatService.kt
- **Added imports** for IntelliJ diff APIs (`com.intellij.diff.comparison.*`)
- **Refactored `generateDiffSegments()`** to use `ComparisonManager` instead of custom diff algorithm
- **Implemented conversion** from IntelliJ's `LineFragment` objects to custom `DiffSegment` objects
- **Improved diff accuracy** by leveraging IntelliJ's mature diff algorithms
- **Added fallback** to simple diff generation if the diff API fails

### 2. ZestContextProvider.kt
- **Enhanced context collection** similar to `TodoPromptDrafter` approach
- **Added `collectRelatedClassImplementations()`** call to gather related classes
- **Improved prompt building** with structured format based on command type
- **Added TODO detection** methods (`containsTodos()`, `extractTodos()`)
- **Enhanced prompt templates** for common commands (test, explain, refactor, document)

### 3. Utils.kt
- **Enhanced `processInlineChatCommand()`** with code validation
- **Added `validateImplementation()`** method to ensure LLM doesn't drastically alter code structure
- **Implemented `normalizeForComparison()`** for better diff comparison
- **Added `extractStructuralElements()`** to identify and preserve code structure
- **Enhanced error handling** with more descriptive notifications

### 4. DiffHighLightingPass.kt
- **Enhanced color scheme** for better visibility of different change types
- **Added support for "modified" highlighting** (orange) for changed lines
- **Improved tooltip descriptions** for each diff type
- **Added line processing tracking** to avoid overlapping highlights
- **Enhanced diff statistics logging** for debugging

### 5. InlineChatIntentionAction.kt
- **Enhanced command list** with contextual icons based on command type
- **Added dynamic action text** that shows "TODOs detected" when applicable
- **Improved icon selection** for different command types (TODO, test, document, refactor, bug)

### 6. SelectionGutterIconManager.kt
- **Added TODO detection** in selected text
- **Dynamic icon switching** between regular icon and TODO icon
- **Enhanced tooltip** to show TODO count when detected
- **Improved user feedback** with contextual information

## Benefits

1. **More Accurate Diffs**: Using IntelliJ's diff system provides better change detection
2. **Better Visualization**: Enhanced highlighting makes changes easier to review
3. **TODO Integration**: Seamless detection and handling of TODO comments
4. **Improved Context**: Better code analysis provides more relevant AI suggestions
5. **Code Safety**: Validation ensures AI doesn't drastically alter code structure

## Usage

1. Select code in the editor
2. If TODOs are present, the gutter icon changes to indicate this
3. Click the gutter icon or use the intention action
4. The AI will provide context-aware suggestions
5. Review the highlighted changes with improved diff visualization
6. Accept or discard changes as needed

## Performance Considerations

- Diff calculations are performed in read actions to ensure thread safety
- Caching is used where appropriate to avoid redundant calculations
- Fallback mechanisms ensure functionality even if advanced features fail

## Future Enhancements

1. **Caching**: Add caching for diff results on large files
2. **Incremental Updates**: Support for incremental diff updates
3. **Side-by-side View**: Option to show changes in a side-by-side diff viewer
4. **Conflict Resolution**: Better handling of conflicting changes
5. **Multi-file Support**: Extend diff visualization across multiple files
