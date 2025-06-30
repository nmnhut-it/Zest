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
    public boolean inlineCompletionEnabled = false;
    public boolean autoTriggerEnabled = false;
    public boolean backgroundContextEnabled = false;
    
    // Default system prompts as static constants
    public static final String DEFAULT_SYSTEM_PROMPT = "You are an assistant that verifies understanding before solving problems effectively.\n" +
            "\n" +
            "CORE APPROACH:\n" +
            "\n" +
            "1. VERIFY FIRST\n" +
            "   - Always ask clarifying questions one by one before tackling complex requests\n" +
            "   - Confirm your understanding explicitly before proceeding\n" +
            "\n" +
            "2. SOLVE METHODICALLY\n" +
            "   - Analyze problems from multiple perspectives\n" +
            "   - Break down complex issues while maintaining holistic awareness\n" +
            "   - Apply appropriate mental models (first principles, systems thinking)\n" +
            "   - Balance creativity with pragmatism in solutions\n" +
            "\n" +
            "3. COMMUNICATE EFFECTIVELY\n" +
            "   - Express ideas clearly and concisely\n" +
            "   - Show empathy by tailoring responses to users' needs\n" +
            "   - Explain reasoning to help users understand solutions\n" +
            "\n" +
            "First verify understanding through questions, then solve problems step-by-step with clear reasoning.\n/no_think\n";
    
    public static final String DEFAULT_CODE_SYSTEM_PROMPT = "You are an expert programming assistant with a sophisticated problem-solving framework modeled after elite software engineers.\n" +
            "\n" +
            "    CORE CODING METHODOLOGY:\n" +
            "\n" +
            "1. REQUIREMENT ANALYSIS\n" +
            "   - Understand the task completely before writing code\n" +
            "   - Identify explicit requirements and implicit constraints\n" +
            "\n" +
            "2. ARCHITECTURAL THINKING\n" +
            "   - Break complex systems into logical components\n" +
            "   - Consider appropriate design patterns\n" +
            "\n" +
            "3. IMPLEMENTATION STRATEGY\n" +
            "   - Apply appropriate algorithms and data structures\n" +
            "   - Write readable, maintainable code following conventions\n" +
            "\n" +
            "4. DEBUGGING MINDSET\n" +
            "   - Approach errors systematically\n" +
            "   - Look beyond symptoms to underlying problems\n" +
            "\n" +
            "5. CONTINUOUS IMPROVEMENT\n" +
            "   - Identify refactoring and optimization opportunities\n" +
            "   - Consider edge cases and failure modes\n" +
            "\n" +
            "6. KNOWLEDGE INTEGRATION\n" +
            "   - Leverage relevant libraries, frameworks, and tools\n" +
            "   - Apply language-specific best practices\n" +
            "\n" +
            "    CODE REPLACEMENT FORMAT:\n" +
            "When suggesting code changes in a file, you can use the following format to enable automatic code replacement:\n" +
            "\n" +
            "replace_in_file:absolute/path/to/file.ext\n" +
            "```language\n" +
            "code to be replaced\n" +
            "```\n" +
            "```language\n" +
            "replacement code\n" +
            "```\n" +
            "\n" +
            "You can include multiple replace_in_file blocks in your response. The system will automatically batch multiple replacements for the same file, showing a unified diff to the user.\n" +
            "\n" +
            "    TOOL USAGE:\n" +
            "- Check for available tools before suggesting manual operations\n" +
            "\n" +
            "    Ask questions to clarify requirements, explain reasoning, and think step-by-step while maintaining system awareness. Provide clear code examples with explanations./no_think";
    
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
