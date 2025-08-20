package com.zps.zest.explanation.tools;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tool for recording findings and insights during code explanation analysis.
 * Helps build a comprehensive understanding of the code being explained.
 */
public class TakeExplanationNoteTool {
    private final List<String> explanationNotes;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public TakeExplanationNoteTool(@NotNull List<String> explanationNotes) {
        this.explanationNotes = explanationNotes;
    }

    /**
     * Record an important finding or insight about the code being explained
     */
    public String takeNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            return "Note cannot be empty.";
        }

        String timestampedNote = String.format("[%s] %s", 
                                             LocalDateTime.now().format(TIME_FORMAT), 
                                             note.trim());
        
        explanationNotes.add(timestampedNote);

        return String.format("✅ Note recorded (%d total notes): %s", 
                           explanationNotes.size(), 
                           note.length() > 80 ? note.substring(0, 80) + "..." : note);
    }

    /**
     * Get all recorded notes
     */
    public List<String> getNotes() {
        return explanationNotes;
    }

    /**
     * Clear all notes (for new analysis sessions)
     */
    public void clearNotes() {
        explanationNotes.clear();
    }

    /**
     * Get a formatted summary of all notes
     */
    public String getNoteSummary() {
        if (explanationNotes.isEmpty()) {
            return "No notes recorded yet.";
        }

        StringBuilder summary = new StringBuilder();
        summary.append(String.format("📝 Explanation Analysis Notes (%d total):\n", explanationNotes.size()));
        summary.append("═".repeat(50)).append("\n");

        for (int i = 0; i < explanationNotes.size(); i++) {
            summary.append(String.format("%d. %s\n", i + 1, explanationNotes.get(i)));
        }

        return summary.toString();
    }
}