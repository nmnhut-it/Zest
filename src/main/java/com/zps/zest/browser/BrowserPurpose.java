package com.zps.zest.browser;

/**
 * Enum representing different purposes for browser instances in the pool.
 * Each purpose gets its own dedicated browser manager to prevent conflicts.
 */
public enum BrowserPurpose {
    /**
     * Browser for AI Chat interface
     */
    CHAT("Chat UI", "AI Chat Interface"),

    /**
     * Browser for Git operations UI
     */
    GIT("Git Operations", "Git Commit and Push UI"),

    /**
     * Browser for general web browsing
     */
    WEB_BROWSER("Web Browser", "General Web Browsing"),

    /**
     * Browser for code exploration tools
     */
    EXPLORATION("Code Exploration", "Code Exploration Tools"),

    /**
     * Browser for IDE tool windows
     */
    TOOL_WINDOW("Tool Window", "IDE Tool Window"),

    /**
     * Browser for chat memory message detail views
     */
    MESSAGE_DETAIL("Message Detail", "Chat Memory Message Detail View");

    private final String displayName;
    private final String description;

    BrowserPurpose(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }
}