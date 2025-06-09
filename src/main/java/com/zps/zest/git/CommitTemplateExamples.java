package com.zps.zest.git;

/**
 * Provides example commit message templates for different styles and workflows.
 */
public class CommitTemplateExamples {
    
    public static final String CONVENTIONAL_COMMITS = 
        "Generate a commit message following Conventional Commits specification.\n\n" +
        "Files changed:\n{FILES_LIST}\n\n" +
        "Code changes:\n{DIFFS}\n\n" +
        "Requirements:\n" +
        "- First line: <type>(<scope>): <subject> (max 50 chars)\n" +
        "- Types: feat, fix, docs, style, refactor, test, chore\n" +
        "- Body: Explain what and why (not how)\n" +
        "- Footer: Include 'BREAKING CHANGE:' if applicable\n\n" +
        "Generate ONLY the commit message.";
    
    public static final String DETAILED_WITH_CONTEXT = 
        "Project: {PROJECT_NAME}\n" +
        "Branch: {BRANCH_NAME}\n" +
        "Date: {DATE}\n" +
        "Developer: {USER_NAME}\n\n" +
        "Modified Files ({FILES_COUNT} total):\n" +
        "{FILES_LIST}\n\n" +
        "Detailed Changes:\n" +
        "{DIFFS}\n\n" +
        "Please generate a comprehensive commit message that:\n" +
        "1. Summarizes the purpose of these changes\n" +
        "2. Explains the motivation and context\n" +
        "3. Lists key modifications\n" +
        "4. Notes any breaking changes or important warnings\n\n" +
        "Format as a proper Git commit with subject line and body.";
    
    public static final String JIRA_INTEGRATED = 
        "Branch: {BRANCH_NAME}\n" +
        "Files: {FILES_LIST}\n\n" +
        "Changes:\n{DIFFS}\n\n" +
        "Generate a commit message that:\n" +
        "1. Extracts the JIRA ticket number from the branch name (if present)\n" +
        "2. Starts with the ticket number (e.g., 'PROJ-123: ')\n" +
        "3. Follows with a clear summary\n" +
        "4. Includes a body explaining the changes\n\n" +
        "Example: PROJ-123: Add user authentication\n\n" +
        "Implement OAuth2 authentication flow...";
    
    public static final String MINIMAL = 
        "Files: {FILES_LIST}\n" +
        "Changes: {DIFFS}\n\n" +
        "Write a concise commit message.";
    
    public static final String SEMANTIC_RELEASE = 
        "Generate a commit message compatible with semantic-release.\n\n" +
        "Files:\n{FILES_LIST}\n\n" +
        "Changes:\n{DIFFS}\n\n" +
        "Format:\n" +
        "- feat: A new feature (minor version bump)\n" +
        "- fix: A bug fix (patch version bump)\n" +
        "- BREAKING CHANGE: in footer (major version bump)\n" +
        "- docs:, style:, refactor:, test:, chore: (no version bump)\n\n" +
        "Include scope in parentheses when applicable.";
    
    public static final String EMOJI_STYLE = 
        "Create a commit message with emoji prefixes.\n\n" +
        "Files changed:\n{FILES_LIST}\n\n" +
        "Code diff:\n{DIFFS}\n\n" +
        "Use these emoji prefixes:\n" +
        "- ✨ :sparkles: New feature\n" +
        "- 🐛 :bug: Bug fix\n" +
        "- 📚 :books: Documentation\n" +
        "- 🎨 :art: Code style/structure\n" +
        "- ♻️ :recycle: Refactoring\n" +
        "- ✅ :white_check_mark: Tests\n" +
        "- 🔧 :wrench: Configuration\n\n" +
        "Format: [emoji] Short description\n\n" +
        "Detailed explanation...";
    
    /**
     * Gets all available templates as an array.
     */
    public static TemplateExample[] getAllTemplates() {
        return new TemplateExample[] {
            new TemplateExample("Conventional Commits", CONVENTIONAL_COMMITS),
            new TemplateExample("Detailed with Context", DETAILED_WITH_CONTEXT),
            new TemplateExample("JIRA Integration", JIRA_INTEGRATED),
            new TemplateExample("Minimal", MINIMAL),
            new TemplateExample("Semantic Release", SEMANTIC_RELEASE),
            new TemplateExample("Emoji Style", EMOJI_STYLE)
        };
    }
    
    /**
     * Template example with name and content.
     */
    public static class TemplateExample {
        public final String name;
        public final String template;
        
        public TemplateExample(String name, String template) {
            this.name = name;
            this.template = template;
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
}
