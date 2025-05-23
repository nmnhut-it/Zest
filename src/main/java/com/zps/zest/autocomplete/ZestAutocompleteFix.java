package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;

/**
 * Fix for issues with the ZestAutocomplete service.
 * This class provides diagnostic and repair functionality for common autocomplete issues.
 */
public class ZestAutocompleteFix {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteFix.class);

    /**
     * Performs diagnostics and cleanup of any lingering inlays or rendering artifacts.
     * Call this method when you suspect there are "ghost" inlays or rendering issues.
     * 
     * @param editor The editor to clean up
     * @param service The autocomplete service instance
     * @return The number of inlays cleaned up
     */
    public static int cleanupInlays(Editor editor, ZestAutocompleteService service) {
        if (editor == null || editor.isDisposed()) {
            return 0;
        }

        int count = 0;

        // First, ensure all tracked completions are cleared
        if (service.hasActiveCompletion(editor)) {
            service.clearCompletion(editor);
            LOG.info("Cleared active completion for editor");
            count++;
        }

        // Force cleanup of any inlays in the visible area
        // This is a more aggressive approach to catch orphaned inlays
        int visibleLineStart = editor.getScrollingModel().getVisibleArea().y / editor.getLineHeight();
        int visibleLineEnd = visibleLineStart + 
            (editor.getScrollingModel().getVisibleArea().height / editor.getLineHeight()) + 1;

        for (int i = visibleLineStart; i <= visibleLineEnd; i++) {
            if (i < 0 || i >= editor.getDocument().getLineCount()) {
                continue;
            }

            int lineStartOffset = editor.getDocument().getLineStartOffset(i);
            int lineEndOffset = editor.getDocument().getLineEndOffset(i);

            // Check for and dispose any inlays in this line range
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(
                    lineStartOffset, lineEndOffset)) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer ||
                    inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                    inlay.dispose();
                    count++;
                }
            }
        }

        if (count > 0) {
            LOG.info("Cleaned up " + count + " orphaned inlays");
        }
        
        return count;
    }

    /**
     * Verifies that the rendering context is properly managing its inlays.
     * Use this method to detect any discrepancies between what's tracked and what exists.
     * 
     * @param context The rendering context to verify
     * @return true if the context is consistent, false if issues were detected
     */
    public static boolean verifyRenderingContext(ZestInlayRenderer.RenderingContext context) {
        if (context == null) {
            return true;  // Nothing to verify
        }
        
        boolean isConsistent = true;
        
        // Check that all tracked inlays are valid
        for (Inlay<?> inlay : context.getInlays()) {
            if (!inlay.isValid()) {
                LOG.warn("Rendering context contains invalid inlay");
                isConsistent = false;
                break;
            }
        }
        
        // Check that all inlays are properly tracked
        Editor editor = context.getEditor();
        if (editor != null && !editor.isDisposed()) {
            int offset = context.getOffset();
            int lineNumber = editor.getDocument().getLineNumber(offset);
            int lineStart = editor.getDocument().getLineStartOffset(lineNumber);
            int lineEnd = editor.getDocument().getLineEndOffset(lineNumber);
            
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(lineStart, lineEnd)) {
                if ((inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer || 
                     inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) &&
                    !context.getInlays().contains(inlay)) {
                    LOG.warn("Found untracked inlay at offset " + inlay.getOffset());
                    isConsistent = false;
                    break;
                }
            }
        }
        
        return isConsistent;
    }
}
