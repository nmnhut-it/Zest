# Diff Implementation Summary

This document summarizes the enhanced diff rendering implementation for the Zest IntelliJ plugin.

## Core Components

### 1. WordDiffUtil.kt
Enhanced utility for word-level and line-level diffing with:
- **Multiple diff algorithms**: Myers (default) and Histogram (better for code)
- **Language-aware tokenization**: Smart splitting for Java, Kotlin, Python, JavaScript
- **CamelCase splitting**: Detects word boundaries in identifiers
- **Code normalization**: Standardizes formatting before diffing
- **Similarity scoring**: Calculates how similar two pieces of text are
- **Common subsequence detection**: Finds matching text blocks for alignment

### 2. HistogramDiff.kt
Implementation of the Histogram diff algorithm:
- **Anchor-based diffing**: Uses unique lines as reference points
- **Better for code**: Handles repeated patterns and moved blocks
- **Recursive approach**: Breaks large diffs into smaller sub-problems
- **Fallback to Myers**: For small sequences or when no anchors found

### 3. MultiLineDiffRenderer.kt
Side-by-side renderer for multi-line changes:
- **Smart text wrapping**: Breaks long lines at appropriate positions
- **Configurable column widths**: Each column uses 45% of editor width by default
- **Font scaling**: Right column uses 90% font size for space efficiency
- **Theme-aware colors**: Automatic adjustment for light/dark themes
- **Arrow alignment**: Consistent positioning across wrapped lines

### 4. DiffRenderingConfig.kt
Configuration service for customizing diff behavior:
- **Algorithm selection**: Choose between Myers and Histogram
- **Multi-line threshold**: When to use side-by-side view
- **Visual settings**: Ghost text alpha, font sizes, column widths
- **Feature toggles**: Enable/disable word-level diff, smart wrapping
- **Persistence**: Settings saved in `zest-diff-rendering.xml`

### 5. ZestInlineMethodDiffRenderer.kt
Main renderer that orchestrates diff display:
- **Automatic mode selection**: Single-line vs multi-line rendering
- **Word-level highlighting**: Shows granular changes within lines
- **Block grouping**: Combines related changes for cleaner display
- **Integration with config**: Respects user preferences

## Key Features

### 1. Multi-Line Block Rendering
When multiple consecutive lines change, they're displayed side-by-side:

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

### 2. Word-Level Diff Highlighting
Changes within lines are highlighted at the word level:
- Deleted words: Red background with strikethrough
- Added words: Green ghost text
- Modified words: Highlighted in both columns

### 3. Smart Line Wrapping
Long lines wrap intelligently with proper continuation:
```
return processData(input,        →    return processData(input,
  configuration,                        configuration,
  DEFAULT_OPTIONS);                     userOptions ?? DEFAULT_OPTIONS);
```

### 4. Language-Aware Normalization
Code is normalized before diffing for cleaner results:
- Java/Kotlin: Brace positioning, array syntax, spacing
- Python: Function definitions, colons, commas
- JavaScript: Arrow functions, object literals

## Usage Flow

1. **Method rewrite triggered** → `ZestInlineMethodDiffRenderer.show()`
2. **Line-level diff performed** → `WordDiffUtil.diffLines()` with selected algorithm
3. **Blocks categorized** → Modified, Added, Deleted, Unchanged
4. **Rendering decision**:
   - Single line change → Inline word diff
   - Multi-line change → Side-by-side block renderer
5. **Word-level diff** → For modified lines if enabled
6. **Visual rendering** → With theme-aware colors and configured settings

## Configuration Options

Users can customize via Settings → Tools → Zest Diff Rendering:

```kotlin
// Example configuration
DiffRenderingConfig.State(
    diffAlgorithm = "HISTOGRAM",          // Better for code
    multiLineThreshold = 1,                // Use multi-line for 2+ lines
    maxColumnWidthPercentage = 0.45,       // 45% width per column
    rightColumnFontSizeFactor = 0.9f,      // 90% font size
    ghostTextAlphaLight = 0.6f,            // Light theme transparency
    ghostTextAlphaDark = 0.7f,             // Dark theme transparency
    enableWordLevelDiff = true,            // Show word-level changes
    enableSmartLineWrapping = true,        // Wrap at operators
    wrapAtOperators = true,                // Smart break points
    continuationIndentSize = 2             // Spaces for wrapped lines
)
```

## Testing

Comprehensive test coverage includes:
- `WordDiffUtilTest`: Core diffing functionality
- `HistogramDiffTest`: Algorithm-specific tests
- `IntegrationExample`: End-to-end examples

Run all diff tests:
```bash
./gradlew test --tests "*Diff*Test"
```

## Performance Optimizations

1. **Lazy evaluation**: Only compute diffs for visible content
2. **Segment merging**: Combine adjacent segments of same type
3. **Threshold fallback**: Use Myers for small diffs (< 20 lines)
4. **Caching**: Reuse tokenization results within same diff operation

## Future Enhancements

1. **Syntax highlighting**: Apply language colors to diff content
2. **AST-based diffing**: Use syntax trees for semantic comparison
3. **Move detection**: Recognize relocated code blocks
4. **Diff folding**: Collapse/expand large change blocks
5. **Custom renderers**: Plugin API for language-specific rendering
