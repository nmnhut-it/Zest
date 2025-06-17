# Code Formatting Enhancement for Method Rewrite

## Overview

Added automatic code formatting to the method rewrite feature. When users accept a rewritten method, the replaced code is now automatically reformatted according to the project's code style settings.

## Implementation Details

### Changes to `ZestMethodRewriteService`

Modified the `acceptMethodRewriteInternal` method to include code reformatting:

1. **After text replacement**: Calculate the new end offset of the replaced method
2. **Get PSI file**: Retrieve the PSI (Program Structure Interface) file from the document
3. **Commit document**: Sync the document changes with the PSI tree
4. **Reformat range**: Use `CodeStyleManager` to reformat only the replaced code range

### Code Changes

```kotlin
// Calculate the new end offset after replacement
val newEndOffset = methodContext.methodStartOffset + rewrittenMethod.length

// Reformat the replaced code using IntelliJ's code style
val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)
if (psiFile != null) {
    // Commit the document to sync PSI
    com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
    
    // Reformat the replaced range
    val codeStyleManager = com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
    try {
        codeStyleManager.reformatRange(psiFile, methodContext.methodStartOffset, newEndOffset)
        logger.info("Reformatted replaced method range")
    } catch (e: Exception) {
        logger.warn("Failed to reformat replaced method: ${e.message}")
        // Continue even if reformatting fails
    }
}
```

## Benefits

1. **Consistent Code Style**: The AI-generated code now automatically follows the project's code style settings
2. **Better Integration**: The rewritten method blends seamlessly with the existing codebase
3. **No Manual Formatting**: Users don't need to manually reformat after accepting changes
4. **Range-based Formatting**: Only the replaced method is reformatted, preserving the rest of the file

## Error Handling

The implementation includes proper error handling:
- If reformatting fails, the operation continues and logs a warning
- The text replacement is still applied even if formatting fails
- Users are notified of the successful rewrite regardless of formatting issues

## Usage

The formatting happens automatically when users:
1. Trigger a method rewrite
2. Review the inline diff
3. Press TAB or click Accept to apply the changes

The reformatting respects all project-specific code style settings including:
- Indentation (tabs vs spaces)
- Brace placement
- Line wrapping
- Import organization
- Spacing rules
- And all other code style preferences
