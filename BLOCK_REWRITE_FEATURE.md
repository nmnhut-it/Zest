# Zest Block Rewrite Feature

This document describes the new block rewrite functionality that allows AI to rewrite entire code blocks, functions, or lines with comprehensive improvements displayed in a floating window.

## Overview

The Block Rewrite feature extends Zest's completion capabilities from simple inline completions to comprehensive code block improvements. Instead of just filling in the blank at the cursor position, it analyzes and rewrites entire code structures.

## Features

### Three Completion Modes

1. **Simple Mode** (⚡)
   - Fast FIM-based inline completions
   - Single-line suggestions
   - Minimal context analysis
   - Good for quick suggestions

2. **Lean Mode** (🧠)
   - Context-aware completions with reasoning
   - Multi-line suggestions
   - Full file context analysis
   - Better code understanding

3. **Block Rewrite Mode** (🔄)
   - Whole block/function/line rewrites
   - Floating window previews with side-by-side diff
   - Comprehensive improvements
   - Perfect for refactoring

### Block Rewrite Capabilities

The system can intelligently detect and rewrite:

- **Methods/Functions** - Complete function improvements with error handling, documentation, and optimization
- **Classes** - Class-level improvements following SOLID principles
- **Code Blocks** - Logical code sections with better structure and flow
- **Statements** - Individual statements with better practices
- **Lines** - Single line improvements with better readability
- **Custom Instructions** - User-specified improvements

## Usage

### Switching Modes

Use the **"Switch Zest Mode"** action to cycle between modes:
- Simple → Lean → Block Rewrite → Simple (cycles)

### Triggering Block Rewrites

1. **Automatic** - When in Block Rewrite mode, completions automatically show as floating windows
2. **Manual** - Use **"Trigger Block Rewrite"** action for on-demand rewrites with custom instructions

### Floating Window Interface

The floating window provides:
- **Side-by-side diff view** showing original vs. suggested code
- **Follow-up refinement** - Type additional instructions to refine the suggestion
- **Accept/Reject buttons** - Apply or dismiss the changes
- **Keyboard shortcuts**:
  - `Ctrl+Enter` or `Alt+A` - Accept changes
  - `Escape` or `Alt+R` - Reject changes
  - `Enter` in follow-up field - Refine suggestion
  - `F12` - Open DevTools for debugging

## Examples

### Method Rewrite
```java
// Original
public int add(int a, int b) {
    return a + b;
}

// AI Rewritten
/**
 * Adds two integers with overflow protection
 */
public int add(int a, int b) {
    try {
        return Math.addExact(a, b);
    } catch (ArithmeticException e) {
        throw new IllegalArgumentException("Integer overflow in addition: " + a + " + " + b, e);
    }
}
```

### Code Block Rewrite
```java
// Original
String name = user.getName();
if (name != null) {
    name = name.trim();
    if (!name.isEmpty()) {
        processName(name);
    }
}

// AI Rewritten
Optional.ofNullable(user.getName())
    .map(String::trim)
    .filter(name -> !name.isEmpty())
    .ifPresent(this::processName);
```

## Architecture

### Core Components

1. **ZestBlockContextCollector** - Detects and extracts code blocks using PSI and textual analysis
2. **ZestBlockRewritePromptBuilder** - Generates specialized prompts for different block types
3. **ZestBlockRewriteResponseParser** - Validates and cleans AI responses
4. **ZestBlockRewriteService** - Orchestrates the rewrite process with floating windows
5. **FloatingCodeWindow** - Displays diff preview with interactive controls

### Block Detection

The system uses multiple strategies to identify code blocks:

1. **PSI Analysis** (when available)
   - Uses IntelliJ's Program Structure Interface
   - Accurate detection of methods, classes, statements
   - Language-aware parsing

2. **Textual Analysis** (fallback)
   - Pattern matching for method signatures
   - Brace matching for code blocks
   - Line-based detection for simple cases

### Validation

AI responses are validated for:
- Syntactic correctness
- Structural integrity (matching braces, proper signatures)
- Length reasonableness (not too short/long)
- Language-specific patterns
- Improvement indicators (error handling, documentation, etc.)

## Configuration

### Default Settings
- **Strategy**: Simple Mode
- **Auto-trigger**: Disabled (manual trigger recommended for block rewrites)
- **Timeout**: 30 seconds for block rewrites
- **Max tokens**: 2000 for comprehensive responses

### Customization
- Custom instructions can be provided for specific improvement requests
- Different block types get specialized prompts
- Language-specific validation and improvement patterns

## Actions Available

- **Switch Zest Mode** - Cycle between completion modes
- **Show Zest Mode Status** - Display current mode and settings
- **Trigger Block Rewrite** - Manual block rewrite with custom instructions

## Best Practices

1. **Use appropriate modes**:
   - Simple mode for quick inline completions
   - Lean mode for context-aware multi-line suggestions
   - Block Rewrite mode for comprehensive refactoring

2. **Review changes carefully**:
   - Always review the diff before accepting
   - Pay attention to validation warnings
   - Test changes after applying

3. **Provide clear custom instructions**:
   - Be specific about desired improvements
   - Mention constraints or requirements
   - Use follow-up refinements for iterative improvement

## Troubleshooting

### Common Issues

1. **No block detected**
   - Ensure cursor is within a meaningful code structure
   - Try selecting the code you want to rewrite

2. **Validation failures**
   - Check for syntax errors in original code
   - Ensure the code structure is recognizable

3. **Floating window not appearing**
   - Check if JCef is enabled in IDE registry
   - Ensure no modal dialogs are blocking

### Performance Notes

- Block rewrites take longer than inline completions (15-30 seconds)
- Complex blocks may require more time
- Large files may have reduced context to improve performance

## Future Enhancements

- Support for more programming languages
- Integration with version control for change tracking
- Batch processing of multiple blocks
- Learning from user feedback to improve suggestions
- Integration with code quality tools and linters
