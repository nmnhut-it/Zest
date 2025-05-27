# Inline Chat Testing Guide

## Overview

The inline chat feature has been refactored to support testing with injected LLM responses. This allows developers to test the diff visualization and accept/reject functionality without making actual LLM API calls.

## Test Action: TestInlineChatAction

### Purpose
A test action that simulates the inline chat feature with a fake LLM response that adds analytical comments to each line of selected code.

### How to Use
1. Select some code in the editor
2. Right-click to open the context menu
3. Navigate to **Zest → Test Inline Chat (Fake LLM)**
4. Or use the keyboard shortcut: **Alt+Shift+I**

### What It Does
- Analyzes each line of selected code
- Adds an AI comment after each non-empty line
- Shows the diff highlighting with IntelliJ's diff system
- Enables Accept/Discard buttons in the code vision

### Example Output
```java
public class Example {
// AI: Line 1 analyzed - This line contains: class declaration
    private String name;
    // AI: Line 2 analyzed - This line contains: field declaration
    
    public void sayHello() {
    // AI: Line 4 analyzed - This line contains: method declaration
        System.out.println("Hello!");
        // AI: Line 5 analyzed - This line contains: method call
    }
    // AI: Line 6 analyzed - This line contains: block end
}
// AI: Line 7 analyzed - This line contains: block end
```

## Accept/Reject Mechanism

### Available Actions

1. **Accept Changes** (`InlineChatAcceptAction`)
   - Applies the AI-suggested changes to the document
   - Clears the diff highlighting
   - Shows success notification

2. **Discard Changes** (`InlineChatDiscardAction`)
   - Rejects the AI-suggested changes
   - Clears the diff highlighting
   - Shows discard notification

3. **Cancel Operation** (`InlineChatCancelAction`)
   - Cancels the inline chat operation
   - Clears the diff highlighting
   - Shows cancel notification

### How to Accept/Reject

After running the test action, you'll see:
1. **Diff Highlighting** - Shows added lines in green, deleted in red, modified in orange
2. **Code Vision Buttons** - Appear at the top of the file:
   - "Accept Changes" - Click to apply the changes
   - "Discard Changes" - Click to reject the changes

## Architecture Changes

### 1. Testable Design
- Introduced `LlmResponseProvider` interface
- `DefaultLlmResponseProvider` - Calls actual LLM
- Custom providers can inject fake responses

### 2. Refactored processInlineChatCommand
```kotlin
fun processInlineChatCommand(
    project: Project, 
    params: ChatEditParams,
    responseProvider: LlmResponseProvider = DefaultLlmResponseProvider()
): Deferred<Boolean>
```

### 3. Test Action Implementation
The `TestInlineChatAction` creates a custom `LlmResponseProvider` that returns a fake response without calling the LLM API.

## Testing Workflow

1. **Test Diff Visualization**
   - Select code
   - Run "Test Inline Chat (Fake LLM)"
   - Observe diff highlighting

2. **Test Accept Flow**
   - Click "Accept Changes" button
   - Verify code is updated with comments
   - Check that highlighting is cleared

3. **Test Discard Flow**
   - Run test action again
   - Click "Discard Changes" button
   - Verify original code remains unchanged
   - Check that highlighting is cleared

4. **Test Different Code Types**
   - Try with classes, methods, interfaces
   - Test with existing comments
   - Test with TODO comments

## Additional Test Actions

### Clear Highlights (Alt+Shift+C)
- Manually clear all diff highlighting
- Useful if something goes wrong

### Test Diff Highlighting (Alt+Shift+T)
- Another test action that uses a different approach
- Adds different types of comments

## Benefits

1. **No API Calls** - Test without consuming API credits
2. **Predictable Results** - Same output every time
3. **Fast Testing** - Instant response
4. **Edge Case Testing** - Easy to modify for specific scenarios

## Extending the Test

To create custom test scenarios, modify the `generateFakeLlmResponse` method in `TestInlineChatAction`:

```kotlin
private fun generateFakeLlmResponse(originalText: String): String {
    // Customize the response generation here
    // Add different types of changes
    // Test edge cases
}
```

## Troubleshooting

1. **No Diff Highlighting**
   - Ensure code is selected
   - Check that the response contains code in markdown blocks
   - Verify InlineChatService is processing the response

2. **Buttons Not Appearing**
   - Check code vision is enabled in IDE settings
   - Ensure diff segments are generated
   - Verify code vision providers are registered

3. **Changes Not Applied**
   - Check write actions are properly wrapped
   - Verify editor document is accessible
   - Ensure selection model has valid offsets
