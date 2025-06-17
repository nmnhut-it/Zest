# Diff Rendering Troubleshooting Guide

## Common Issues and Solutions

### 1. Ghost Text Overlapping with Existing Code

**Problem**: Ghost text appears on top of existing code instead of in a clean separate area.

**Causes**:
- Incorrect line offset calculation
- Inlay placed at line start instead of line end
- Diff blocks not properly aligned with document lines

**Solutions Applied**:
1. **Fixed inlay positioning**: Changed additions to insert AFTER the previous line instead of at the start of the current line
2. **Improved offset calculation**: Track actual document positions instead of relative offsets
3. **Enhanced background clearing**: Use full editor width for ghost text to ensure proper separation

### 2. Line Number Misalignment

**Problem**: Diff changes appear at wrong line numbers.

**Solutions**:
- Use block positions from diff result directly instead of accumulating offsets
- Add debug logging to track block positions
- Ensure additions are placed relative to their context

### 3. Visual Artifacts

**Problem**: Rendering artifacts or incomplete text clearing.

**Solutions**:
- Enable antialiasing for smoother text
- Clear full width background before rendering
- Use proper composite operations for transparency

## Key Fixes Applied

### 1. renderLineAddition Method
```kotlin
// OLD: Placed inlay at line start (causing overlap)
val insertOffset = document.getLineStartOffset(actualLineNumber)

// NEW: Place inlay after previous line
val insertOffset = if (actualLineNumber > 0) {
    document.getLineEndOffset(actualLineNumber - 1)
} else {
    0
}
```

### 2. renderDiffChanges Method
```kotlin
// Better tracking of positions
val blockStartInDocument = methodStartLine + block.originalStartLine

// For additions, insert after preceding content
val insertAfterLine = methodStartLine + block.originalStartLine - 1
```

### 3. Ghost Text Renderers
```kotlin
// Use full editor width for proper clearing
override fun calcWidthInPixels(inlay: Inlay<*>): Int {
    return inlay.editor.contentComponent.width - 20
}

// Clear background completely
g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
```

## Debugging Tips

1. **Enable debug logging**:
   ```kotlin
   logger.info("Diff blocks for method ${context.methodContext.methodName}:")
   lineDiff.blocks.forEach { block ->
       logger.info("  ${block.type}: original[${block.originalStartLine}-${block.originalEndLine}]")
   }
   ```

2. **Check inlay parameters**:
   - `relatesToPrecedingText`: true for additions
   - `showAbove`: true to show above the next line
   - Priority: 0 (default)

3. **Verify diff algorithm**:
   - Histogram diff may produce different blocks than Myers
   - Check if the issue is algorithm-specific

## Testing Scenarios

1. **Single line addition**: Should appear as ghost text after the previous line
2. **Multi-line addition**: Should appear as a block with proper spacing
3. **Mixed changes**: Modifications should hide original and show side-by-side
4. **Beginning/end additions**: Handle edge cases at method boundaries

## Configuration Options

If issues persist, try adjusting these settings:

```kotlin
DiffRenderingConfig:
- diffAlgorithm: Try switching between MYERS and HISTOGRAM
- multiLineThreshold: Adjust when to use block rendering
- enableWordLevelDiff: Disable for simpler rendering
```
