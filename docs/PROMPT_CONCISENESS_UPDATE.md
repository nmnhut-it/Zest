# Prompt Conciseness Update

## Overview
Updated Zest plugin configuration and prompts to make AI responses more concise and direct.

## Changes Made

### 1. Updated Default System Prompts (ZestGlobalSettings.java)

#### Old System Prompt (Verbose)
```
You are an assistant that verifies understanding before solving problems effectively.

CORE APPROACH:

1. VERIFY FIRST
   - Always ask clarifying questions one by one before tackling complex requests
   - Confirm your understanding explicitly before proceeding

2. SOLVE METHODICALLY
   - Analyze problems from multiple perspectives
   - Break down complex issues while maintaining holistic awareness
   - Apply appropriate mental models (first principles, systems thinking)
   - Balance creativity with pragmatism in solutions

3. COMMUNICATE EFFECTIVELY
   - Express ideas clearly and concisely
   - Show empathy by tailoring responses to users' needs
   - Explain reasoning to help users understand solutions

First verify understanding through questions, then solve problems step-by-step with clear reasoning.
```

#### New System Prompt (Concise)
```
You are a concise technical assistant. Keep responses brief and to the point.

GUIDELINES:
- Be direct - no unnecessary preambles or explanations
- Use bullet points for clarity when listing items
- Only elaborate when explicitly asked
- Prefer code examples over lengthy descriptions
- Ask for clarification only when truly necessary
```

#### Old Code System Prompt (Verbose)
```
You are an expert programming assistant with a sophisticated problem-solving framework modeled after elite software engineers.

CORE CODING METHODOLOGY:

1. REQUIREMENT ANALYSIS
   - Understand the task completely before writing code
   - Identify explicit requirements and implicit constraints

2. ARCHITECTURAL THINKING
   - Break complex systems into logical components
   - Consider appropriate design patterns

[... multiple sections omitted for brevity ...]
```

#### New Code System Prompt (Concise)
```
You are a concise code expert. Focus on practical solutions with minimal explanation.

APPROACH:
- Provide working code first, explain only if needed
- Use comments in code rather than separate explanations
- Be direct about errors or issues
- Suggest the most straightforward solution

CODE REPLACEMENT FORMAT:
For code changes, use:

replace_in_file:path/to/file.ext
```language
old code
```
```language
new code
```
```

### 2. Updated Inline Completion Prompts

#### Lean Prompt Builder (ZestLeanPromptBuilder.kt)
Changed from verbose multi-paragraph instructions to:
```
You are a code completion AI. Complete code at `[CURSOR]` position.

## Rules:
1. Complete ONLY after `[CURSOR]`
2. Match existing style
3. Be concise - 2-5 words usually enough
4. Focus on what user is typing

## Response:
<code>
[completion]
</code>
```

#### Simple Prompt Builder (ZestSimplePromptBuilder.kt)
Updated all system prompts to be more concise:
- FIM completion: "Complete code at cursor. Output only the insertion. Follow existing style."
- Instruction completion: Reduced from 5 detailed rules to 3 concise ones
- Text completion: Simplified to one line

### 3. Prompt Migration System

Created a comprehensive migration system to automatically update user prompts:

#### PromptMigrationService.kt
- Tracks prompt versions
- Detects old verbose prompts by signature
- Automatically migrates to concise versions on project startup
- Provides manual migration option

#### PromptMigrationStartupActivity.kt
- Runs on project startup
- Checks if migration is needed
- Performs migration in background

#### UI Updates (ZestSettingsConfigurable.java)
- Added "Update All Prompts" button in settings
- Allows manual migration to latest prompts
- Shows confirmation dialog before updating

### 4. Version Tracking

Updated ZestProjectSettings.java to track prompt version:
```java
// Migration tracking
public int promptVersion = 0;
```

Current version is 2, indicating the concise prompt update.

## Benefits

1. **Shorter Responses**: AI will now provide direct, to-the-point answers
2. **Less Token Usage**: Concise system prompts use fewer tokens, saving on API costs
3. **Better Caching**: Structured prompts with separate system/user components enable better caching
4. **Automatic Migration**: Existing users will be migrated to new prompts automatically
5. **User Control**: Users can manually update or customize prompts as needed

## Migration Path

1. On first startup after update, the plugin will:
   - Check if current prompts match old verbose signatures
   - Automatically update to concise versions
   - Update version number to prevent re-migration

2. Users can also manually update via:
   - Settings → Zest Plugin → Prompts tab → "Update All Prompts" button

## Testing

To test the migration:
1. Set prompts to old verbose versions
2. Restart IDE
3. Check that prompts are automatically updated
4. Verify AI responses are more concise

## Rollback

If users prefer verbose prompts, they can:
1. Go to Settings → Zest Plugin → Prompts
2. Manually paste the old prompts
3. Click Apply

The migration won't run again unless the version is reset.
