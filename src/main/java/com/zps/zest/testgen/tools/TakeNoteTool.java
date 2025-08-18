package com.zps.zest.testgen.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tool for recording important observations, insights, and context during code exploration.
 * Notes are preserved and included in the final test generation context.
 */
public class TakeNoteTool {
    private final List<String> contextNotes;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

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
        - Timestamped for reference
        - Included in the final test context
        - Available for the test generation phase
        
        Best practices:
        - Be specific and actionable
        - Include file/class references when relevant
        - Explain WHY something is important
        - Suggest concrete test scenarios when applicable
        
        Example usage:
        - takeNote("UserService depends on external API at config.apiUrl - mock this in tests")
        - takeNote("OrderProcessor uses async processing - test both success and timeout scenarios")
        - takeNote("Authentication uses JWT tokens with 1hr expiry - test token expiration handling")
        - takeNote("Database transactions in PaymentService - test rollback on failure")
        """)
    public String takeNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            return "❌ Note cannot be empty. Please provide meaningful context or observations.";
        }

        // Add timestamp to the note for better tracking
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String formattedNote = String.format("[%s] %s", timestamp, note.trim());
        
        // Store the note
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