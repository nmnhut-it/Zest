# Inline Chat Debug Guide

This guide explains how to use the debug logging added to the inline chat feature to diagnose issues.

## Debug Flags

Debug logging has been added to the following classes with flags that can be turned on/off:

### 1. InlineChatService.kt
```kotlin
companion object {
    const val DEBUG_SERVICE = true         // General service operations
    const val DEBUG_DIFF_SEGMENTS = true   // Diff segment generation
    const val DEBUG_CODE_EXTRACTION = true // Code extraction from LLM response
}
```

### 2. DiffHighLightingPass.kt
```kotlin
companion object {
    const val DEBUG_HIGHLIGHTING = true    // Highlighting pass operations
}
```

### 3. InlineChatCodeVisionProvider.kt
```kotlin
companion object {
    const val DEBUG_CODE_VISION = true    // Code Vision button creation
}
```

### 4. TestInlineChatAction.kt
```kotlin
companion object {
    const val DEBUG_TEST_ACTION = true    // Test action operations
}
```

### 5. Utils.kt
```kotlin
private const val DEBUG_PROCESS_COMMAND = true    // Command processing
private const val DEBUG_RESPONSE_HANDLING = true  // LLM response handling
```

## Debug Output

All debug output is sent to `System.out.println()` rather than logger, making it visible in the console when running the plugin.

## Test Actions

Several test actions have been added to help diagnose issues:

1. **Test Inline Chat (Fake LLM)** - Uses a fake LLM response to test the flow
2. **Test Direct Diff (Replace Text)** - Directly replaces text and sets up diff
3. **Debug Inline Chat State** - Shows current state in a dialog
4. **Clear Diff Highlighting** - Clears all diff highlights

## Common Issues to Check

When debugging, look for:

1. **Code Extraction**
   - Check if the regex is finding code blocks: `Regex match found: true/false`
   - Verify extracted code preview is shown

2. **Diff Segments**
   - Check the number of segments generated
   - Verify segment types and line numbers

3. **Code Vision**
   - Check if diff action states are set to true
   - Verify Code Vision is computing entries

4. **Highlighting**
   - Check document line count vs segment line numbers
   - Verify highlights are being created

## Running Debug

1. Set the debug flags you want to `true`
2. Run the plugin in a test IntelliJ instance
3. Open the console to see debug output
4. Use the test actions to trigger specific scenarios
5. Use the Debug action to see current state

## Turning Off Debug

To disable debug output, simply set the relevant flags to `false` in the companion objects.
