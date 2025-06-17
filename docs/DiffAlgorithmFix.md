# Fix for Diff Algorithm Returning ADDED/DELETED Instead of MODIFIED

## The Root Cause

You correctly identified that the core issue was in `WordDiffUtil.diffLines()`. The diff algorithm was returning separate DELETED and ADDED blocks instead of MODIFIED blocks, causing:

1. Lines to be hidden (DELETED) and shown separately as additions (ADDED)
2. The last line might not be rendered if it was part of an unchanged block
3. Poor visualization with disconnected deletions and additions

## The Fix

### 1. **Merge DELETE+INSERT into MODIFIED**
Added `mergeDeleteInsertBlocks()` method to post-process the diff results:
```kotlin
// Check if DELETE block is followed by INSERT block
if (currentBlock.type == BlockType.DELETED && 
    i + 1 < blocks.size && 
    blocks[i + 1].type == BlockType.ADDED) {
    
    // Merge into a MODIFIED block
    mergedBlocks.add(DiffBlock(
        originalStartLine = currentBlock.originalStartLine,
        originalEndLine = currentBlock.originalEndLine,
        modifiedStartLine = nextBlock.modifiedStartLine,
        modifiedEndLine = nextBlock.modifiedEndLine,
        originalLines = currentBlock.originalLines,
        modifiedLines = nextBlock.modifiedLines,
        type = BlockType.MODIFIED
    ))
}
```

### 2. **Removed Line Normalization**
Changed `diffLines()` to diff the original lines without normalization:
```kotlin
// Perform the diff on original lines (not normalized)
val patch = DiffUtils.diff(originalLines, modifiedLines, diffAlgorithm)
```

Normalization was causing the diff to miss actual whitespace changes and could lead to incorrect line matching.

### 3. **Enhanced Debug Logging**
Added detailed logging to track block types and merging:
- Shows original blocks before merging
- Shows which DELETE+INSERT blocks are merged
- Shows block content for debugging

## How It Works Now

1. **Diff Algorithm Output**: 
   - Line 1: DELETED
   - Line 2: ADDED
   - Line 3: DELETED
   - Line 4: ADDED
   - Line 5: UNCHANGED

2. **After Merging**:
   - Lines 1-2: MODIFIED (merged from DELETE+INSERT)
   - Lines 3-4: MODIFIED (merged from DELETE+INSERT)
   - Line 5: UNCHANGED

3. **Rendering**:
   - MODIFIED blocks → Side-by-side view with `MultiLineDiffRenderer`
   - UNCHANGED blocks → Can be rendered if needed (for completeness)

## Benefits

1. **All lines visible**: The complete method is shown, including unchanged lines
2. **Proper side-by-side view**: MODIFIED blocks use the multi-line renderer
3. **Better visualization**: Changes are shown as modifications, not separate delete/add
4. **Accurate diff**: No normalization means exact changes are detected

## Testing

Use the updated `TestDiffRenderingAction`:
1. Place cursor in any method
2. Run "Test Diff Rendering" from Zest menu
3. It will:
   - Extract code around cursor
   - Create a modified version
   - Show the diff with all lines visible

Check the logs to see:
- Block types before and after merging
- Which lines are being processed
- Any missing lines detected
