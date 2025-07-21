# Custom LLM Rules in Zest

Zest now supports custom project-specific rules that can be automatically included in all LLM prompts. This allows you to maintain consistent coding standards and patterns across your team.

## How to Use Custom Rules

### 1. Create the Rules File

You can create a `zest_rules.md` file in your project root through:

- **Via UI**: Use the action "Create/Open Zest Rules File" from:
  - Right-click menu → Zest → Create/Open Zest Rules File
  - Tools menu → Create/Open Zest Rules File
  
- **Manually**: Create a file named `zest_rules.md` in your project root

### 2. Define Your Rules

Edit the `zest_rules.md` file to include your custom rules. These rules will be automatically prepended to prompts sent to the LLM during:

- Method rewrites
- Code completions
- Other AI-assisted features

### 3. Example Rules

```markdown
## Your Rules:

### Code Style Guidelines:
- Use descriptive variable names that clearly indicate their purpose
- Prefer early returns to reduce nesting
- Add null checks for all method parameters
- Use guard clauses at the beginning of methods

### Project-Specific Patterns:
- All service methods should return a Result<T> wrapper for error handling
- Use dependency injection through constructor parameters
- Follow the repository pattern for data access
- Log all exceptions with appropriate context

### Framework-Specific Rules (Cocos2d-x Example):
- Use cc.Node() instead of cc.Node.create() - direct constructors preferred
- Maintain .extend() inheritance patterns: var MyClass = cc.Layer.extend({...})
- Keep object literal method syntax: methodName: function() {...}
```

## How It Works

When you trigger any AI-assisted feature like method rewrite:

1. Zest checks if `zest_rules.md` exists in your project root
2. If found, it extracts the custom rules from the "Your Rules" section
3. These rules are prepended to the prompt with a clear header:
   ```
   **CUSTOM PROJECT RULES:**
   [Your rules here]
   
   ---
   
   [Original prompt]
   ```

## Best Practices

1. **Be Specific**: Provide clear, actionable rules that the AI can follow
2. **Include Examples**: When possible, show examples of the pattern you want
3. **Domain Knowledge**: Include any domain-specific terminology or patterns
4. **Team Standards**: Document your team's coding standards
5. **Framework Patterns**: Include framework-specific patterns your project uses

## Rule Categories to Consider

- **Naming Conventions**: Variable names, method names, class names
- **Error Handling**: How errors should be handled and reported
- **Logging**: What should be logged and at what level
- **Security**: Security practices like input validation
- **Performance**: Performance considerations and optimizations
- **Testing**: Test naming conventions and patterns
- **Documentation**: Comment styles and documentation requirements
- **Architecture**: Architectural patterns and principles

## Limitations

- Rules are applied to all LLM requests within the project
- Very long rule sets may impact token limits
- Rules should be guidelines, not absolute requirements
- The AI will try to follow rules but may need to balance them with other concerns

## Troubleshooting

If your rules aren't being applied:

1. Ensure the file is named exactly `zest_rules.md` (case-sensitive)
2. Place it in the project root directory
3. Make sure your rules are in the "Your Rules:" section
4. Check that the file is not empty and contains valid content

## Advanced Usage

### Enabling Rules for All LLM Calls

By default, custom rules are only applied to method rewrites and similar features. To apply rules to ALL LLM calls:

```java
// In your code or via a custom action
LLMService llmService = project.getService(LLMService.class);
llmService.setApplyCustomRulesToAllPrompts(true);
```

This is useful for projects with strict requirements that should apply to all AI interactions.
