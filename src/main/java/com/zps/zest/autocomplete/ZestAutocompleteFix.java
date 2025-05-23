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

        try {
            // Get document bounds for safety
            int documentLength = editor.getDocument().getTextLength();
            int totalLines = editor.getDocument().getLineCount();

            // Clean up inline elements across the entire document
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(0, documentLength)) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer) {
                    try {
                        if (inlay.isValid()) {
                            inlay.dispose();
                            count++;
                            LOG.debug("Cleaned up orphaned inline inlay at offset " + inlay.getOffset());
                        }
                    } catch (Exception e) {
                        LOG.warn("Error disposing inline inlay", e);
                    }
                }
            }

            // Clean up block elements across all visible lines
            for (int lineIndex = 0; lineIndex < totalLines; lineIndex++) {
                try {
                    // Check both above and below line variants
                    for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(lineIndex, true)) {
                        if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                            try {
                                if (inlay.isValid()) {
                                    inlay.dispose();
                                    count++;
                                    LOG.debug("Cleaned up orphaned block inlay (above) at line " + lineIndex);
                                }
                            } catch (Exception e) {
                                LOG.warn("Error disposing block inlay (above)", e);
                            }
                        }
                    }
                    
                    for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(lineIndex, false)) {
                        if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                            try {
                                if (inlay.isValid()) {
                                    inlay.dispose();
                                    count++;
                                    LOG.debug("Cleaned up orphaned block inlay (below) at line " + lineIndex);
                                }
                            } catch (Exception e) {
                                LOG.warn("Error disposing block inlay (below)", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Error checking line " + lineIndex + " for block inlays", e);
                    // Continue with next line
                }
            }

        } catch (Exception e) {
            LOG.warn("Error during comprehensive inlay cleanup", e);
        }

        if (count > 0) {
            LOG.info("Cleaned up " + count + " orphaned inlays");
        }
        
        return count;
    }

    /**
     * Verifies that the rendering context is properly managing its inlays.
     * Use this method to detect any discrepancies between what's tracked and what exists.
     * Enhanced to better handle block inlays.
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
            
            // Check inline elements
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(lineStart, lineEnd)) {
                if ((inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer || 
                     inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) &&
                    !context.getInlays().contains(inlay)) {
                    LOG.warn("Found untracked inline inlay at offset " + inlay.getOffset());
                    isConsistent = false;
                    break;
                }
            }
            
            // Check block elements (both above and below line)
            for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(lineNumber, true)) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer &&
                    !context.getInlays().contains(inlay)) {
                    LOG.warn("Found untracked block inlay (above) at line " + lineNumber);
                    isConsistent = false;
                    break;
                }
            }
            
            for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(lineNumber, false)) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer &&
                    !context.getInlays().contains(inlay)) {
                    LOG.warn("Found untracked block inlay (below) at line " + lineNumber);
                    isConsistent = false;
                    break;
                }
            }
        }
        
        return isConsistent;
    }

    /**
     * Diagnostic method to report all Zest-related inlays in the editor.
     * Use this to debug inlay issues and see what's currently active.
     * 
     * @param editor The editor to analyze
     * @return A diagnostic report as a string
     */
    public static String diagnosticReport(Editor editor) {
        if (editor == null || editor.isDisposed()) {
            return "Editor is null or disposed";
        }

        StringBuilder report = new StringBuilder();
        report.append("=== Zest Inlay Diagnostic Report ===\n");
        
        int totalInlays = 0;
        int totalBlockInlays = 0;
        
        try {
            // Check inline elements
            int documentLength = editor.getDocument().getTextLength();
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(0, documentLength)) {
                if (inlay.getRenderer() instanceof ZestInlayRenderer.InlineCompletionRenderer) {
                    report.append("Inline inlay at offset ").append(inlay.getOffset())
                          .append(", valid: ").append(inlay.isValid()).append("\n");
                    totalInlays++;
                }
            }
            
            // Check block elements
            int totalLines = editor.getDocument().getLineCount();
            for (int line = 0; line < totalLines; line++) {
                // Above line
                for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(line, true)) {
                    if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                        report.append("Block inlay (above) at line ").append(line)
                              .append(", offset ").append(inlay.getOffset())
                              .append(", valid: ").append(inlay.isValid()).append("\n");
                        totalBlockInlays++;
                    }
                }
                
                // Below line
                for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(line, false)) {
                    if (inlay.getRenderer() instanceof ZestInlayRenderer.BlockCompletionRenderer) {
                        report.append("Block inlay (below) at line ").append(line)
                              .append(", offset ").append(inlay.getOffset())
                              .append(", valid: ").append(inlay.isValid()).append("\n");
                        totalBlockInlays++;
                    }
                }
            }
            
        } catch (Exception e) {
            report.append("Error during diagnostic: ").append(e.getMessage()).append("\n");
        }
        
        report.append("Total inline inlays: ").append(totalInlays).append("\n");
        report.append("Total block inlays: ").append(totalBlockInlays).append("\n");
        report.append("=== End Report ===");
        
        return report.toString();
    }
}
