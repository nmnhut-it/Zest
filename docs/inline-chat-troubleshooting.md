# Inline Chat Accept/Reject Buttons Troubleshooting

## Problem
The diff highlighting (green background) is showing but the Accept/Reject buttons are not visible at the top of the file.

## Solution

### 1. Enable Code Vision in IntelliJ

The Accept/Reject buttons use IntelliJ's Code Vision feature, which might be disabled by default.

**Steps to enable:**
1. Go to **File → Settings** (Windows/Linux) or **IntelliJ IDEA → Preferences** (macOS)
2. Navigate to **Editor → Inlay Hints → Code Vision**
3. Check the box for **"Code vision"** to enable it
4. Click **Apply** and **OK**
5. **Restart IntelliJ IDEA** (important!)

### 2. Test the Buttons

After enabling Code Vision and restarting:

1. Select some code in the editor
2. Use one of these test actions:
   - **Alt+Shift+B** - "Test Inline Chat Buttons (Show UI)" - This directly shows the buttons without LLM
   - **Alt+Shift+I** - "Test Inline Chat (Fake LLM)" - This simulates the full flow with fake responses

### 3. Check Code Vision Settings

If buttons still don't appear:

1. Go to **Settings → Editor → Inlay Hints → Code Vision**
2. Make sure these are checked:
   - ☑ Code vision
   - ☑ Position: Default position

### 4. Alternative Workarounds

If Code Vision is not working:

1. **Use keyboard shortcuts directly:**
   - The actions are still registered even if buttons don't show
   - You can assign custom shortcuts in Settings → Keymap

2. **Use the Clear Highlights action:**
   - **Alt+Shift+C** - Clears all diff highlighting

### 5. Debug Information

The test action "Test Inline Chat Buttons (Show UI)" will show:
- Whether Code Vision host is available
- Any errors preventing button display

## Technical Details

The Accept/Reject functionality uses:
- `InlineChatAcceptCodeVisionProvider` - Shows "Accept Changes" button
- `InlineChatDiscardCodeVisionProvider` - Shows "Discard Changes" button
- Code Vision API to display buttons at the top of the editor

The buttons only appear when:
1. Code Vision is enabled
2. `inlineChatDiffActionState["Zest.InlineChat.Accept"] = true`
3. The editor has been refreshed with the code vision invalidation

## Common Issues

1. **Code Vision not enabled** - Most common issue
2. **IntelliJ needs restart** - Code Vision changes require restart
3. **Old IntelliJ version** - Code Vision requires newer versions (2020.3+)
4. **Plugin conflict** - Other plugins might interfere with Code Vision

## Verification Steps

1. Run "Test Inline Chat Buttons (Show UI)" (Alt+Shift+B)
2. Check the message dialog for specific errors
3. If it says "Code Vision host not found", enable Code Vision and restart
4. If no errors but no buttons, try invalidating caches (File → Invalidate Caches)
