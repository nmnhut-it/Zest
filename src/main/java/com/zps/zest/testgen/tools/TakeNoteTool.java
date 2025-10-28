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
        Record multiple notes at once about important findings during code exploration.
        Use this tool to capture information that should be considered during test generation.

        Parameters:
        - notes: List of observations or insights to record. Each should be clear and actionable.

        What to record:
        - Important patterns or conventions in the codebase
        - Dependencies between components
        - Configuration requirements or constraints
        - Test strategy recommendations
        - Special considerations for testing
        - Potential edge cases or error conditions
        - Performance or resource considerations
        - Security or validation requirements

        Best practices:
        - Include file:line location at the start for traceability
        - Use category tags: [USAGE], [ERROR], [SCHEMA], [INTEGRATION], [EDGE_CASE], [PATTERN]
        - Be specific and actionable
        - Explain WHY something is important
        - Suggest concrete test scenarios when applicable

        Example:
        takeNotes([
          "[UserService.java:45] [USAGE] depends on external API at config.apiUrl - mock this in tests",
          "[OrderProcessor.java:123] [INTEGRATION] uses async processing - test both success and timeout scenarios",
          "[AuthFilter.java:67] [ERROR] JWT tokens with 1hr expiry - test token expiration handling",
          "[PaymentService.java:89] [INTEGRATION] Database transactions - test rollback on failure"
        ])
        """)
    public String takeNotes(List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return "❌ Notes list cannot be empty.";
        }

        int added = 0;
        StringBuilder result = new StringBuilder();
        result.append("✅ Batch note recording:\n\n");

        for (String note : notes) {
            if (note != null && !note.trim().isEmpty()) {
                contextNotes.add(note.trim());
                added++;
                result.append(String.format("📝 Note #%d: %s\n", contextNotes.size(),
                    note.length() > 60 ? note.substring(0, 60) + "..." : note));
            }
        }

        result.append(String.format("\n✅ Recorded %d notes successfully. Total notes: %d",
            added, contextNotes.size()));

        return result.toString();
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