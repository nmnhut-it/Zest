# Multi-Line Diff Rendering Implementation

This document describes the enhanced multi-line diff rendering implementation for the Zest IntelliJ plugin.

## Features

### 1. Histogram Diff Algorithm

The implementation now supports the Histogram diff algorithm, which is particularly effective for code diffs:

```kotlin
val lineDiff = WordDiffUtil.diffLines(
    originalText, 
    modifiedText,
    WordDiffUtil.DiffAlgorithm.HISTOGRAM, // or MYERS
    "java" // language for normalization
)
```

**Benefits of Histogram Diff:**
- Better handling of code with many similar lines
- More intuitive diffs for refactored code  
- Better recognition of moved code blocks
- Reduces "diff noise" in complex changes

### 2. Multi-Line Side-by-Side Rendering

When multiple consecutive lines are modified, they're now rendered in a side-by-side view:

```
Original Code                    →    Modified Code
─────────────────────────────────────────────────────
public void process(String       →    public void process(String 
    input) {                          input, Options opts) {
    validate(input);                      if (opts.validate) {
    transform(input);                         validate(input);
}                                         }
                                         transform(input, opts);
                                     }
```

### 3. Smart Text Wrapping

Long lines are intelligently wrapped with proper continuation indentation:

```
Original Code                    →    Modified Code
─────────────────────────────────────────────────────
return processData(input,        →    return processData(input,
  configuration,                        configuration,
  DEFAULT_OPTIONS);                     userOptions != null ?
                                          userOptions :
                                          DEFAULT_OPTIONS);
```

### 4. Visual Improvements

- **Font Sizing**: Right column uses 90% font size for better space utilization
- **Ghost Text**: Modified code appears as semi-transparent "ghost text"
- **Theme Support**: Automatic adjustment for light/dark themes
- **Arrow Alignment**: Arrows remain aligned even across multiple wrapped lines

## Usage

The diff renderer automatically detects multi-line changes and applies the appropriate rendering:

```kotlin
// In ZestInlineMethodDiffRenderer
private fun renderDiffChanges(context: RenderingContext) {
    // Perform line-level diff
    val lineDiff = WordDiffUtil.diffLines(
        originalText, 
        modifiedText,
        WordDiffUtil.DiffAlgorithm.HISTOGRAM,
        context.methodContext.language
    )
    
    // Process each diff block
    for (block in lineDiff.blocks) {
        when (block.type) {
            WordDiffUtil.BlockType.MODIFIED -> {
                if (block.originalLines.size > 1 || 
                    block.modifiedLines.size > 1) {
                    // Multi-line change - use side-by-side renderer
                    renderMultiLineBlock(context, block, lineNumber)
                } else {
                    // Single line - use existing renderer
                    renderLineModification(...)
                }
            }
            // ... handle other block types
        }
    }
}
```

## Configuration

The multi-line renderer supports configuration options:

```kotlin
val renderer = MultiLineDiffRenderer(
    originalLines = block.originalLines,
    modifiedLines = block.modifiedLines,
    scheme = editor.colorsScheme,
    language = "java",
    maxWidthPercentage = 0.45 // Each column takes 45% of editor width
)
```

## Smart Line Breaking

The renderer intelligently breaks lines at appropriate positions:

1. **Preferred break points** (in order of preference):
   - After operators: `,` `;` `)` `]` `}`
   - Before operators: `(` `[` `{` `.` `+` `-` `*` `/` `=` `<` `>` `&` `|`
   - At whitespace

2. **Continuation indentation**: Wrapped lines receive additional indentation

## Performance Considerations

1. **Grouping**: Consecutive changed lines are grouped into single blocks
2. **Lazy rendering**: Only visible portions are rendered
3. **Caching**: Diff results are cached per method context

## Testing

Run the histogram diff tests to verify functionality:

```bash
./gradlew test --tests "com.zps.zest.completion.diff.HistogramDiffTest"
```

## Future Enhancements

1. **Syntax highlighting**: Apply language-specific highlighting to diff content
2. **Inline word diff**: Show word-level changes within multi-line blocks
3. **Collapsible blocks**: Allow collapsing large diff blocks
4. **Customizable column widths**: Let users adjust the split ratio
5. **Better handling of indentation changes**: Normalize indentation for cleaner diffs
