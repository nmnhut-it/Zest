# DEPRECATED - This file has been moved!

Your rules have been migrated to: .zest/rules.md

This file is no longer used and can be safely deleted.
The new location allows better organization of Zest configuration.

---

# Zest Custom Rules

Define your custom LLM rules below. These rules will be included at the top of all prompts sent to the LLM.
You can use this to:
- Define coding standards specific to your project
- Add domain-specific knowledge
- Set preferred coding patterns
- Include project-specific requirements

## Example Rules:

<!-- 
- Always use camelCase for variable names
- Prefer const over let for immutable values
- Include JSDoc comments for all public methods
- Follow the project's error handling patterns
- For Cocos2d-x projects: Use cc.Node() instead of cc.Node.create()
- Always handle null checks before accessing object properties
- Use meaningful variable names that describe their purpose
- Avoid deeply nested callbacks - use async/await or Promises
-->

## Your Rules:

<!-- Add your custom rules below this line -->

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

### Performance Guidelines:
- Avoid creating objects in loops
- Use StringBuilder for string concatenation in loops
- Cache expensive computations when possible
- Prefer lazy initialization for heavy resources

