package com.zps.zest.testgen.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tool for recording important observations, insights, and context during code exploration.
 * Notes are preserved and included in the final test generation context.
 * Notes should include file:line location for traceability.
 */
public class TakeNoteTool {
    private final List<String> contextNotes;

    public TakeNoteTool(@NotNull List<String> contextNotes) {
        this.contextNotes = contextNotes;
    }

    @Tool("""
        Record an important note, observation, or insight discovered during code exploration.
        Use this tool to capture information that should be considered during test generation.
        
        Parameters:
        - note: The observation or insight to record. Should be clear and actionable.
        
        What to record:
        - Important patterns or conventions in the codebase
        - Dependencies between components
        - Configuration requirements or constraints
        - Test strategy recommendations
        - Special considerations for testing
        - Potential edge cases or error conditions
        - Performance or resource considerations
        - Security or validation requirements
        
        The note will be:
        - Included in the final test context
        - Available for the test generation phase
        - Used by test planning and generation agents
        
        Best practices:
        - Include file:line location at the start for traceability
        - Use category tags: [USAGE], [ERROR], [SCHEMA], [INTEGRATION], [EDGE_CASE], [PATTERN]
        - Be specific and actionable
        - Explain WHY something is important
        - Suggest concrete test scenarios when applicable

        Example usage:
        - takeNote("[UserService.java:45] [USAGE] depends on external API at config.apiUrl - mock this in tests")
        - takeNote("[OrderProcessor.java:123] [INTEGRATION] uses async processing - test both success and timeout scenarios")
        - takeNote("[AuthFilter.java:67] [ERROR] JWT tokens with 1hr expiry - test token expiration handling")
        - takeNote("[PaymentService.java:89] [INTEGRATION] Database transactions - test rollback on failure")
        """)
    public String takeNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            return "❌ Note cannot be empty. Please provide meaningful context or observations.";
        }

        // Store the note as-is (caller should include file:line if relevant)
        String formattedNote = note.trim();
        contextNotes.add(formattedNote);

        // Provide feedback with note number
        int noteNumber = contextNotes.size();
        return String.format("✅ Note #%d recorded successfully:\n" +
                           "📝 %s\n" +
                           "\n" +
                           "This note will be included in the test generation context.\n" +
                           "Total notes recorded: %d",
                           noteNumber, note.trim(), noteNumber);
    }

    /**
     * Get all recorded notes.
     */
    public List<String> getNotes() {
        return contextNotes;
    }

    /**
     * Get a summary of all notes.
     */
    public String getNoteSummary() {
        if (contextNotes.isEmpty()) {
            return "No notes recorded.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("📋 Recorded Notes (").append(contextNotes.size()).append(" total):\n");
        summary.append("─".repeat(50)).append("\n");
        
        for (int i = 0; i < contextNotes.size(); i++) {
            summary.append(String.format("%d. %s\n", i + 1, contextNotes.get(i)));
        }
        
        return summary.toString();
    }

    /**
     * Clear all notes (useful for resetting context).
     */
    public void clearNotes() {
        contextNotes.clear();
    }
}