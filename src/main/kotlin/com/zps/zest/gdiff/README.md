# GDiff - Multi-Language Diff Utility

GDiff is a comprehensive diff utility integrated into the Zest IntelliJ plugin. It provides powerful text comparison capabilities that work seamlessly with any language and character encoding.

## Features

### Core Functionality
- **Multi-language support**: Works with Unicode text including Chinese, Arabic, emoji, and special characters
- **Flexible comparison options**: Ignore case, whitespace, and custom preprocessing
- **Multiple output formats**: Unified diff (Git-style), side-by-side comparison, and structured change objects
- **IntelliJ integration**: Native VFS support for comparing files and documents
- **Performance optimized**: Handles large files efficiently using Myers' diff algorithm

### Supported Operations
- String-to-string comparison
- File-to-file comparison
- VirtualFile comparison (IntelliJ)
- Document vs file comparison (unsaved changes)
- Quick identity checks
- Change-only filtering

## Usage Examples

### Basic String Comparison

```kotlin
val gdiff = GDiff()

val source = """
    Hello World
    This is line 2
    Another line
""".trimIndent()

val target = """
    Hello World
    This is line 2 modified
    Another line
    New line added
""".trimIndent()

val result = gdiff.diffStrings(source, target)
println("Has changes: ${result.hasChanges()}")
println("Statistics: +${result.getStatistics().additions} -${result.getStatistics().deletions}")
```

### File Comparison

```kotlin
val gdiff = GDiff()

// Compare two files
val result = gdiff.diffFiles("file1.txt", "file2.txt")

// With custom configuration
val config = GDiff.DiffConfig(
    ignoreWhitespace = true,
    ignoreCase = false,
    contextLines = 5
)
val result = gdiff.diffFiles("file1.txt", "file2.txt", config)
```

### IntelliJ VFS Integration

```kotlin
// Compare two VirtualFiles
val result = GDiffVfsUtil.diffVirtualFiles(virtualFile1, virtualFile2)

// Check for unsaved changes
val hasChanges = GDiffVfsUtil.hasUnsavedChanges(virtualFile)

// Compare file with current document
val document = FileDocumentManager.getInstance().getDocument(virtualFile)
val result = GDiffVfsUtil.diffFileWithDocument(virtualFile, document)
```

### Unified Diff Generation

```kotlin
val gdiff = GDiff()

val unifiedDiff = gdiff.generateUnifiedDiff(
    source = sourceText,
    target = targetText,
    sourceFileName = "original.java",
    targetFileName = "modified.java"
)
println(unifiedDiff)
```

### Side-by-Side Comparison

```kotlin
val gdiff = GDiff()

val sideBySide = gdiff.generateSideBySideDiff(sourceText, targetText)
sideBySide.forEach { row ->
    val marker = when (row.type) {
        GDiff.ChangeType.EQUAL -> "="
        GDiff.ChangeType.INSERT -> "+"
        GDiff.ChangeType.DELETE -> "-"
        GDiff.ChangeType.CHANGE -> "~"
    }
    println("$marker | ${row.sourceText} | ${row.targetText}")
}
```

## Configuration Options

The `DiffConfig` class allows you to customize comparison behavior:

```kotlin
val config = GDiff.DiffConfig(
    ignoreWhitespace = true,    // Ignore leading/trailing whitespace
    ignoreCase = true,          // Case-insensitive comparison
    charset = StandardCharsets.UTF_8, // Character encoding
    contextLines = 3            // Context lines for unified diff
)
```

## Multi-Language Support

GDiff handles all Unicode characters correctly:

```kotlin
val source = """
    // English comment
    const message = "Hello World";
    
    // 中文注释
    const 中文变量 = "你好世界";
    
    // العربية
    const arabicText = "مرحبا بالعالم";
    
    // Emoji test 🚀
    const rocket = "🚀 Launch!";
""".trimIndent()

val result = gdiff.diffStrings(source, modifiedSource)
// Works perfectly with all character sets
```

## IntelliJ Action Integration

The `CompareFilesAction` provides UI integration:

1. Right-click on 1-2 files in Project view
2. Select "Compare Files with GDiff"
3. View detailed comparison results in a dialog

### Keyboard Shortcuts
- Available through the Zest menu in editor context menu
- Integrated with Project view popup menu

## API Reference

### Core Classes

#### `GDiff`
Main class providing diff functionality.

**Methods:**
- `diffStrings(source, target, config?)`: Compare two strings
- `diffFiles(file1, file2, config?)`: Compare two files
- `generateUnifiedDiff(...)`: Generate Git-style diff
- `generateSideBySideDiff(...)`: Generate side-by-side comparison
- `areIdentical(source, target, config?)`: Quick identity check

#### `GDiffVfsUtil`
Utility for IntelliJ VFS integration.

**Methods:**
- `diffVirtualFiles(file1, file2, config?)`: Compare VirtualFiles
- `diffFileWithDocument(file, document, config?)`: Compare file with document
- `hasUnsavedChanges(file)`: Check for unsaved changes
- `generateUnifiedDiffForFiles(...)`: Generate unified diff for VirtualFiles

#### `DiffResult`
Contains comparison results.

**Properties:**
- `changes`: List of `DiffChange` objects
- `identical`: Boolean indicating if files are identical
- `sourceFile`, `targetFile`: Optional file names

**Methods:**
- `hasChanges()`: Check if there are any changes
- `getStatistics()`: Get detailed statistics

#### `DiffChange`
Represents a single change.

**Properties:**
- `type`: `ChangeType` (EQUAL, INSERT, DELETE, CHANGE)
- `sourceLineNumber`, `targetLineNumber`: Line positions
- `sourceLines`, `targetLines`: Affected lines

## Dependencies

GDiff uses the following library:
- `io.github.java-diff-utils:java-diff-utils:4.12` - Myers' diff algorithm implementation

## Testing

Comprehensive tests are available in `GDiffTest.kt`:

```bash
# Run tests
./gradlew test --tests "*GDiffTest*"
```

## Integration with Zest Plugin

GDiff is fully integrated into the Zest plugin ecosystem:

1. **Settings**: Configure default diff behavior in Zest settings
2. **Actions**: Available through Zest action menu
3. **Tool Windows**: Can be integrated with future diff tool windows
4. **VCS Integration**: Potential integration with Git workflow actions

## Performance Considerations

- **Memory**: Uses streaming for large files to minimize memory usage
- **Algorithm**: Myers' diff algorithm provides O(ND) performance
- **Caching**: Results can be cached for repeated comparisons
- **Threading**: All operations are thread-safe and can be run on background threads

## Future Enhancements

Potential future features:
- Visual diff viewer tool window
- Three-way merge support
- Directory comparison
- Binary file comparison
- Integration with version control systems
- Custom diff algorithms
- Patch application functionality

## Contributing

When contributing to GDiff:

1. Ensure all tests pass
2. Add tests for new functionality
3. Follow Kotlin coding conventions
4. Update documentation
5. Test with various character encodings and languages

## License

GDiff is part of the Zest plugin and follows the same licensing terms.
