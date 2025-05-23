package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import com.zps.zest.autocomplete.ZestAutocompleteService;
//import com.zps.zest.autocomplete.events.ZestCaretEventListener;
import com.zps.zest.autocomplete.events.ZestCompletionEventPublisher;
import org.jetbrains.annotations.NotNull;

/**
 * Enhanced caret listener following Tabby ML patterns.
 * Manages autocomplete behavior with sophisticated caret movement analysis and message bus integration.
 */
public class ZestAutocompleteCaretListener implements CaretListener {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteCaretListener.class);

    // Movement thresholds for different behaviors
    private static final int SMALL_MOVEMENT_THRESHOLD = 1;    // Single character movement
    private static final int MEDIUM_MOVEMENT_THRESHOLD = 5;   // Small navigation
    private static final int LARGE_MOVEMENT_THRESHOLD = 20;   // Significant jump

    // Time threshold for rapid movements (mouse clicks vs keyboard nav)
    private static final long RAPID_MOVEMENT_THRESHOLD = 100; // ms

    private final ZestAutocompleteService autocompleteService;
    private final Editor editor;
    private final Project project;
    private final MessageBus messageBus;

    // Movement tracking
    private long lastMovementTime = 0;
    private LogicalPosition lastPosition = null;
    private int consecutiveSmallMovements = 0;

    public ZestAutocompleteCaretListener(ZestAutocompleteService autocompleteService, Editor editor) {
        this.autocompleteService = autocompleteService;
        this.editor = editor;
        this.project = editor.getProject();
        this.messageBus = project != null ? project.getMessageBus() : null;
        this.lastPosition = editor.getCaretModel().getLogicalPosition();
        LOG.debug("Enhanced ZestAutocompleteCaretListener created for editor");
    }

    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        LOG.debug("Caret position changed: " + event);

        // Analyze the caret movement
        MovementAnalysis analysis = analyzeCaretMovement(event);

        // Update movement tracking
        updateMovementTracking(event);

        // Publish caret events via message bus
//        publishCaretEvents(event, analysis);

        // Handle the movement based on analysis
        handleCaretMovement(event, analysis);
    }

    /**
     * Analyzes caret movement to determine appropriate autocomplete behavior.
     */
    private MovementAnalysis analyzeCaretMovement(CaretEvent event) {
        LogicalPosition oldPos = event.getOldPosition();
        LogicalPosition newPos = event.getNewPosition();

        // Calculate movement metrics
        int lineMovement = Math.abs(newPos.line - oldPos.line);
        int columnMovement = Math.abs(newPos.column - oldPos.column);
        int totalMovement = lineMovement + columnMovement;

        long currentTime = System.currentTimeMillis();
        boolean isRapidMovement = (currentTime - lastMovementTime) < RAPID_MOVEMENT_THRESHOLD;

        // Classify movement type
        MovementType movementType = classifyMovement(lineMovement, columnMovement, totalMovement, isRapidMovement);

        // Determine behavior
        boolean shouldClear = shouldClearCompletion(movementType, totalMovement);
        boolean shouldTrigger = shouldTriggerCompletion(movementType, event);
        ClearReason clearReason = determineClearReason(movementType, totalMovement);

        LOG.debug("Movement analysis: type={}, total={}, rapid={}, clear={}, trigger={}",
                 movementType, totalMovement, isRapidMovement, shouldClear, shouldTrigger);

        return new MovementAnalysis(movementType, shouldClear, shouldTrigger, clearReason, totalMovement);
    }

    /**
     * Classifies the type of caret movement.
     */
    private MovementType classifyMovement(int lineMovement, int columnMovement, int totalMovement, boolean isRapid) {
        // Multi-caret scenarios
        if (editor.getCaretModel().getCaretCount() > 1) {
            return MovementType.MULTI_CARET;
        }

        // Large jumps (clicks, goto, etc.)
        if (totalMovement > LARGE_MOVEMENT_THRESHOLD || isRapid) {
            return MovementType.LARGE_JUMP;
        }

        // Line changes
        if (lineMovement > 0) {
            return lineMovement == 1 ? MovementType.SINGLE_LINE_CHANGE : MovementType.MULTI_LINE_CHANGE;
        }

        // Column movements on same line
        if (columnMovement <= SMALL_MOVEMENT_THRESHOLD) {
            return MovementType.SMALL_MOVEMENT;
        } else if (columnMovement <= MEDIUM_MOVEMENT_THRESHOLD) {
            return MovementType.MEDIUM_MOVEMENT;
        } else {
            return MovementType.LARGE_MOVEMENT;
        }
    }

    /**
     * Determines if completion should be cleared based on movement.
     * ✅ ADJUSTED: More lenient for your 200ms delay use case
     */
    private boolean shouldClearCompletion(MovementType movementType, int totalMovement) {
        if (!autocompleteService.hasActiveCompletion(editor)) {
            return false; // Nothing to clear
        }

        switch (movementType) {
            case SMALL_MOVEMENT:
                // Allow small movements within completion context (up to 2 chars)
                return true;

            case MEDIUM_MOVEMENT:

                return totalMovement > 4; // Was 2, now 4

            case LARGE_MOVEMENT:
            case LARGE_JUMP:
            case SINGLE_LINE_CHANGE:
            case MULTI_LINE_CHANGE:
            case MULTI_CARET:
                // Always clear for significant movements
                return true;

            default:
                return true;
        }
    }

    /**
     * Determines if completion should be triggered after movement.
     */
    private boolean shouldTriggerCompletion(MovementType movementType, CaretEvent event) {
        // Generally don't auto-trigger on caret movement
        // This could be enhanced for specific scenarios like "go to end of identifier"

        switch (movementType) {
//            case SMALL_MOVEMENT:
//                // Might trigger if we moved to a good completion position
//                return isGoodCompletionPosition(event.getNewPosition());

            default:
                return false;
        }
    }

    /**
     * Determines the reason for clearing completion.
     */
    private ClearReason determineClearReason(MovementType movementType, int totalMovement) {
        switch (movementType) {
            case LARGE_JUMP:
                return ClearReason.LARGE_JUMP;
            case MULTI_CARET:
                return ClearReason.MULTI_CARET;
            case SINGLE_LINE_CHANGE:
            case MULTI_LINE_CHANGE:
                return ClearReason.LINE_CHANGE;
            case MEDIUM_MOVEMENT:
            case LARGE_MOVEMENT:
                return ClearReason.SIGNIFICANT_MOVEMENT;
            default:
                return ClearReason.OTHER;
        }
    }

    /**
     * Checks if the current position is good for triggering completion.
     */
    private boolean isGoodCompletionPosition(LogicalPosition position) {
        try {
            int offset = editor.logicalPositionToOffset(position);
            String text = editor.getDocument().getText();

            if (offset <= 0 || offset >= text.length()) {
                return false;
            }

            // Check if we're at the end of an identifier
            char currentChar = text.charAt(offset);
            char prevChar = offset > 0 ? text.charAt(offset - 1) : ' ';

            return Character.isJavaIdentifierPart(prevChar) &&
                   !Character.isJavaIdentifierPart(currentChar);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Updates movement tracking for pattern analysis.
     */
    private void updateMovementTracking(CaretEvent event) {
        long currentTime = System.currentTimeMillis();
        LogicalPosition newPos = event.getNewPosition();

        // Track consecutive small movements
        if (lastPosition != null) {
            int movement = Math.abs(newPos.line - lastPosition.line) + Math.abs(newPos.column - lastPosition.column);
            if (movement <= SMALL_MOVEMENT_THRESHOLD && (currentTime - lastMovementTime) < RAPID_MOVEMENT_THRESHOLD) {
                consecutiveSmallMovements++;
            } else {
                consecutiveSmallMovements = 0;
            }
        }

        lastPosition = newPos;
        lastMovementTime = currentTime;
    }
//
//    /**
//     * Publishes caret events via message bus.
//     */
//    private void publishCaretEvents(CaretEvent event, MovementAnalysis analysis) {
//        if (messageBus == null) return;
//
//        var publisher = messageBus.syncPublisher(ZestCaretEventListener.TOPIC);
//
//        if (analysis.shouldClear) {
//            publisher.caretMovedForCompletion(editor, event, true);
//        }
//
//        if (analysis.shouldTrigger) {
//            int newOffset = editor.logicalPositionToOffset(event.getNewPosition());
//            publisher.caretMovedTriggerCompletion(editor, event, newOffset);
//        }
//
//        if (analysis.movementType == MovementType.MULTI_CARET) {
//            publisher.multipleCaretsDetected(editor, editor.getCaretModel().getCaretCount());
//        }
//    }
//
    /**
     * Handles caret movement based on analysis.
     */
    private void handleCaretMovement(CaretEvent event, MovementAnalysis analysis) {
        if (analysis.shouldClear) {
            LOG.debug("Clearing completion due to caret movement: " + analysis.clearReason);

            // Publish completion rejection event
            var activeCompletion = autocompleteService.getActiveCompletion(editor);
            if (activeCompletion != null && messageBus != null) {
                var publisher = messageBus.syncPublisher(ZestCompletionEventPublisher.TOPIC);
                publisher.completionRejected(editor, activeCompletion.getItem(),
                    ZestCompletionEventPublisher.RejectionReason.CARET_MOVED);
            }

            autocompleteService.clearCompletion(editor);
        }

        if (analysis.shouldTrigger) {
            LOG.debug("Triggering completion due to caret movement to good position");
            autocompleteService.triggerAutocomplete(editor);
        }
    }

    // Data classes for analysis
    private static class MovementAnalysis {
        final MovementType movementType;
        final boolean shouldClear;
        final boolean shouldTrigger;
        final ClearReason clearReason;
        final int totalMovement;

        MovementAnalysis(MovementType movementType, boolean shouldClear, boolean shouldTrigger,
                        ClearReason clearReason, int totalMovement) {
            this.movementType = movementType;
            this.shouldClear = shouldClear;
            this.shouldTrigger = shouldTrigger;
            this.clearReason = clearReason;
            this.totalMovement = totalMovement;
        }
    }

    // Enums for movement analysis
    private enum MovementType {
        SMALL_MOVEMENT,      // 1-2 character movement
        MEDIUM_MOVEMENT,     // 3-5 character movement
        LARGE_MOVEMENT,      // 6-20 character movement
        LARGE_JUMP,          // >20 character movement or rapid
        SINGLE_LINE_CHANGE,  // Moving to adjacent line
        MULTI_LINE_CHANGE,   // Moving multiple lines
        MULTI_CARET          // Multiple carets active
    }

    private enum ClearReason {
        LARGE_JUMP, MULTI_CARET, LINE_CHANGE, SIGNIFICANT_MOVEMENT, OTHER
    }
}
