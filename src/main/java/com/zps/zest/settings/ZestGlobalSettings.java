package com.zps.zest.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Global (application-level) settings for Zest Plugin.
 * These settings are shared across all projects.
 */
@State(
    name = "com.zps.zest.settings.ZestGlobalSettings",
    storages = @Storage("zest-plugin.xml")
)
public class ZestGlobalSettings implements PersistentStateComponent<ZestGlobalSettings> {
    
    // API Settings
    public String apiUrl = "https://chat.zingplay.com/api/chat/completions";
    public String authToken = "";
    
    // Model Settings (defaults)
    public String testModel = "unit_test_generator";
    public String codeModel = "code-expert";
    
    // Feature Toggles
    public boolean inlineCompletionEnabled = true;  // Changed to true by default
    public boolean autoTriggerEnabled = true;  // Changed to true by default
    public boolean backgroundContextEnabled = false;
    public boolean continuousCompletionEnabled = true; // Auto-trigger next completion after acceptance
    
    // RAG and AST Settings for inline completion
    public boolean inlineCompletionRagEnabled = true;  // Enable RAG for inline completion
    public boolean astPatternMatchingEnabled = true;   // Enable AST pattern matching
    public int maxRagContextSize = 1000;              // Max size for RAG context in chars
    public int embeddingCacheSize = 100;              // Number of files to cache embeddings for
    
    // Default system prompts as static constants
    public static final String DEFAULT_SYSTEM_PROMPT = "You are a concise technical assistant. Keep responses brief and to the point.\n" +
            "\n" +
            "GUIDELINES:\n" +
            "- Be direct - no unnecessary preambles or explanations\n" +
            "- Use bullet points for clarity when listing items\n" +
            "- Only elaborate when explicitly asked\n" +
            "- Prefer code examples over lengthy descriptions\n" +
            "- Ask for clarification only when truly necessary\n" +
            "\n" +
            "TOOLS:\n" +
            "- When available, use Zest Code Explorer tools to explore the codebase\n" +
            "- Each tool is project-specific - use the one matching the user's context\n" +
            "- Tools provide real-time code analysis from the IDE\n/no_think\n";
    
    public static final String DEFAULT_CODE_SYSTEM_PROMPT = "You are a concise code expert. Focus on practical solutions with minimal explanation.\n" +
            "\n" +
            "    APPROACH:\n" +
            "- Provide working code first, explain only if needed\n" +
            "- Use comments in code rather than separate explanations\n" +
            "- Be direct about errors or issues\n" +
            "- Suggest the most straightforward solution\n" +
            "\n" +
            "    TOOLS:\n" +
            "- Use Zest Code Explorer tools when you need to:\n" +
            "  • Explore project structure or find files\n" +
            "  • Search for specific code patterns or implementations\n" +
            "  • Understand relationships between classes/modules\n" +
            "  • Analyze code before making recommendations\n" +
            "\n" +
            "    CODE REPLACEMENT FORMAT:\n" +
            "For code changes, use:\n" +
            "\n" +
            "replace_in_file:path/to/file.ext\n" +
            "```language\n" +
            "old code\n" +
            "```\n" +
            "```language\n" +
            "new code\n" +
            "```\n/no_think";
    
    public static final String DEFAULT_COMMIT_PROMPT_TEMPLATE = "Generate a well-structured git commit message based on the changes below.\n\n" +
            "## Changed files:\n" +
            "{FILES_LIST}\n\n" +
            "## File changes:\n" +
            "{DIFFS}\n\n" +
            "## Instructions:\n" +
            "Please follow this structure for the commit message:\n\n" +
            "1. First line: Short summary (50-72 chars) following conventional commit format\n" +
            "   - format: <type>(<scope>): <subject>\n" +
            "   - example: feat(auth): implement OAuth2 login\n\n" +
            "2. Body: Detailed explanation of what changed and why\n" +
            "   - Separated from summary by a blank line\n" +
            "   - Explain what and why, not how\n" +
            "   - Wrap at 72 characters\n\n" +
            "3. Footer (optional):\n" +
            "   - Breaking changes (BREAKING CHANGE: description)\n\n" +
            "Example output:\n" +
            "feat(user-profile): implement password reset functionality\n\n" +
            "Add secure password reset flow with email verification and rate limiting.\n" +
            "This change improves security by requiring email confirmation before\n" +
            "allowing password changes.\n\n" +
            "- Added PasswordResetController with email verification\n" +
            "- Implemented rate limiting to prevent brute force attacks\n" +
            "- Added unit and integration tests\n\n" +
            "BREAKING CHANGE: Password reset API endpoint changed from /reset to /users/reset\n\n" +
            "Please provide ONLY the commit message, no additional explanation, no markdown formatting, no code blocks.";
    
    // System Prompts (instance fields initialized with defaults)
    public String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    public String codeSystemPrompt = DEFAULT_CODE_SYSTEM_PROMPT;
    public String commitPromptTemplate = DEFAULT_COMMIT_PROMPT_TEMPLATE;
    
    public static ZestGlobalSettings getInstance() {
        return ApplicationManager.getApplication().getService(ZestGlobalSettings.class);
    }
    
    @Nullable
    @Override
    public ZestGlobalSettings getState() {
        return this;
    }
    
    @Override
    public void loadState(@NotNull ZestGlobalSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
