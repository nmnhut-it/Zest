# WordDiffUtil Enhancements

This document describes the enhanced word-level diff functionality in the Zest plugin.

## Features

### 1. Advanced Tokenization

The enhanced WordDiffUtil provides language-aware tokenization with the following features:

#### Language-Specific Settings

```kotlin
val settings = TokenizationSettings(
    preserveWhitespace = true,      // Keep whitespace tokens
    splitCamelCase = true,           // Split camelCase words
    treatNumbersAsOneToken = false,  // Group consecutive digits
    customDelimiters = setOf('@')    // Additional delimiters
)
```

#### Supported Languages

- **Java/Kotlin**: CamelCase splitting, smart operator handling
- **Python**: Underscore handling, colon normalization
- **JavaScript/TypeScript**: Arrow function normalization, object literal formatting

### 2. Enhanced Word-Level Diffing

```kotlin
val result = WordDiffUtil.diffWords(
    original = "myOldVariable", 
    modified = "myNewVariable", 
    language = "java"
)

// Result includes:
// - Segmented changes (my|Old|Variable -> my|New|Variable)
// - Change types for each segment
// - Similarity score (0.67 in this case)
// - Character offsets for precise highlighting
```

### 3. Line-Level Diff with Multiple Algorithms

```kotlin
// Use Histogram diff for better code diffing
val lineDiff = WordDiffUtil.diffLines(
    originalText, 
    modifiedText,
    algorithm = WordDiffUtil.DiffAlgorithm.HISTOGRAM,
    language = "java"
)

// Process diff blocks
for (block in lineDiff.blocks) {
    when (block.type) {
        BlockType.MODIFIED -> // Lines changed in place
        BlockType.ADDED -> // New lines added
        BlockType.DELETED -> // Lines removed
        BlockType.UNCHANGED -> // No changes
    }
}
```

### 4. Code Normalization

Automatic normalization for cleaner diffs:

```kotlin
// Java/Kotlin normalization
"if(x){return;}" -> "if (x) {return;}"
"Type  []  array" -> "Type[] array"

// Python normalization
"def  func(x,y):" -> "def func(x, y):"

// JavaScript normalization
"()=>{}" -> "() => {}"
```

### 5. Common Subsequence Detection

Find matching subsequences for better alignment:

```kotlin
val subsequences = WordDiffUtil.findCommonSubsequences(
    original = "The quick brown fox",
    modified = "The fast brown fox",
    minLength = 3
)
// Finds: ["The ", "brown fox"]
```

## Usage Examples

### Basic Word Diff

```kotlin
val diff = WordDiffUtil.diffWords(
    "public void process(String input)",
    "public void process(String input, Options opts)",
    "java"
)

// Segments will show:
// - "public void process(String input" (UNCHANGED)
// - ", Options opts" (ADDED)  
// - ")" (UNCHANGED)
```

### Multi-Line Method Diff

```kotlin
val original = """
    public void calculate() {
        int result = compute();
        return result;
    }
""".trimIndent()

val modified = """
    public int calculate() {
        int result = compute();
        validate(result);
        return result;
    }
""".trimIndent()

val lineDiff = WordDiffUtil.diffLines(original, modified, language = "java")

// Will identify:
// - Line 1: MODIFIED (void -> int)
// - Line 3: ADDED (validate call)
```

### Segment Merging

```kotlin
// Original segments might be fragmented
val segments = listOf(
    WordSegment("Hello", UNCHANGED),
    WordSegment(" ", UNCHANGED),
    WordSegment("world", UNCHANGED)
)

// Merge for cleaner rendering
val merged = WordDiffUtil.mergeSegments(segments)
// Result: [WordSegment("Hello world", UNCHANGED)]
```

## Configuration Integration

The WordDiffUtil works with `DiffRenderingConfig` for customizable behavior:

```kotlin
val config = DiffRenderingConfig.getInstance()

// Check if word-level diff is enabled
if (config.isWordLevelDiffEnabled()) {
    val wordDiff = WordDiffUtil.diffWords(original, modified, language)
    // Render with word-level highlighting
}

// Choose diff algorithm
val algorithm = config.getDiffAlgorithm() // HISTOGRAM or MYERS
val lineDiff = WordDiffUtil.diffLines(original, modified, algorithm, language)
```

## Performance Considerations

1. **Tokenization Caching**: Token results are computed once per diff operation
2. **Algorithm Selection**: 
   - Myers: Faster for small diffs
   - Histogram: Better for code with repeated patterns
3. **Similarity Threshold**: Use similarity score to skip expensive rendering for very different content

## Testing

Run the comprehensive test suite:

```bash
./gradlew test --tests "com.zps.zest.completion.diff.WordDiffUtilTest"
```

## Future Enhancements

1. **Semantic Diff**: Use AST for language-aware structural diffing
2. **Fuzzy Matching**: Handle renamed variables/methods
3. **Move Detection**: Identify relocated code blocks
4. **Custom Tokenizers**: Plugin API for language-specific tokenization
5. **Diff Compression**: Optimize storage of large diff results
