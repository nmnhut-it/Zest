# Commit Message Template Guide

## Overview
The Zest plugin allows you to customize the prompt template used for generating commit messages via AI.

## Available Placeholders

### Required Placeholders
- `{FILES_LIST}` - List of files being committed with their status
- `{DIFFS}` - The actual code changes/diffs

### Optional Placeholders (if implemented)
- `{PROJECT_NAME}` - Current project name
- `{BRANCH_NAME}` - Current git branch
- `{DATE}` - Current date
- `{TIME}` - Current time
- `{USER_NAME}` - System username
- `{FILES_COUNT}` - Number of files being committed

## Template Examples

### Default Template
```
Generate a well-structured git commit message based on the changes below.

## Changed files:
{FILES_LIST}

## File changes:
{DIFFS}

## Instructions:
Please follow this structure for the commit message:

1. First line: Short summary (50-72 chars) following conventional commit format
   - format: <type>(<scope>): <subject>
   - example: feat(auth): implement OAuth2 login

2. Body: Detailed explanation of what changed and why
   - Separated from summary by a blank line
   - Explain what and why, not how
   - Wrap at 72 characters

3. Footer (optional):
   - Breaking changes (BREAKING CHANGE: description)
```

### Minimal Template
```
Files: {FILES_LIST}
Changes: {DIFFS}

Write a concise commit message following conventional commit format.
```

### Detailed Template with Context
```
Project: {PROJECT_NAME}
Branch: {BRANCH_NAME}
Date: {DATE}

Modified Files ({FILES_COUNT} total):
{FILES_LIST}

Detailed Changes:
{DIFFS}

Please generate a comprehensive commit message that:
1. Summarizes the overall purpose of these changes
2. Uses conventional commit format
3. Includes relevant context about why these changes were made
4. Mentions any breaking changes or important notes
```

## Tips

1. **Keep it focused**: The AI works best with clear, specific instructions
2. **Use examples**: Including format examples helps the AI understand your preferred style
3. **Be consistent**: Use the same template across your team for uniform commit messages
4. **Test your template**: Use the "Validate Template" button to ensure placeholders are correct

## Troubleshooting

- **Template not working?** Make sure it contains both `{FILES_LIST}` and `{DIFFS}` placeholders
- **Getting generic messages?** Add more specific instructions to your template
- **Want to reset?** Use the "Reset to Default" button in settings
