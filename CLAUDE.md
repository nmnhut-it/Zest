# Claude Development Guide for Zest

## Project Overview
Zest is an IntelliJ IDEA plugin that provides AI-powered code assistance features including:
- Code completion and generation
- Git operations with AI-generated commit messages
- Code Health Review (formerly Code Guardian) for code quality analysis
- File exploration and context collection
- Integration with various LLM services
- Dynamic tool injection for OpenWebUI with mode-specific behavior

## Key Technologies
- **Languages**: Java, Kotlin, JavaScript/HTML
- **Build System**: Gradle
- **IDE**: IntelliJ Platform SDK
- **UI**: JCEF (Java Chromium Embedded Framework) for web-based UI

## Important Commands

### Build & Test (Use IntelliJ IDEA's UI)
**Note: Since this is an IntelliJ plugin, always use IntelliJ IDEA's build system instead of command line:**

1. **Build**: Use `Build → Build Project` (Ctrl+F9) in IntelliJ
2. **Rebuild**: Use `Build → Rebuild Project` 
3. **Run Plugin**: Use the "Run Plugin" configuration in IntelliJ (usually a green arrow)
4. **Debug Plugin**: Use the "Debug Plugin" configuration

### Gradle Tasks (if needed)
```bash
# Build the plugin distributable
./gradlew buildPlugin

# Clean build files
./gradlew clean

# Run IntelliJ with the plugin (alternative to IDE run config)
./gradlew runIde
```

### Common IntelliJ Actions
- **Sync Project**: Click the Gradle sync button when build.gradle changes
- **Invalidate Caches**: `File → Invalidate Caches and Restart` if you encounter strange issues
- **Check for Errors**: Look at the Problems view (Alt+6) for compilation errors

## Project Structure

### Main Source Directories
- `src/main/java/com/zps/zest/` - Java source files
  - `browser/` - Web browser integration and services
  - `git/` - Git-related functionality (GitService, etc.)
  - `codehealth/` - Code Health Review functionality
  - `langchain4j/` - LLM integration
  - `completion/` - Code completion features

- `src/main/kotlin/com/zps/zest/` - Kotlin source files
  - `codehealth/` - Code Health analyzer and related components

- `src/main/resources/` - Resources
  - `html/` - HTML files for web UI (e.g., git-ui.html)
  - `js/` - JavaScript files

## Key Components

### Git Integration
- **GitService.java** - Main service for Git operations
- **git-ui.html** - Web-based Git UI with commit, push, and Code Health Review features
- Supports streaming commit message generation using OpenAI-compatible APIs

### Code Health Review
- **CodeHealthAnalyzer.kt** - Analyzes code for quality issues
- **CodeHealthTracker.kt** - Tracks code modifications
- Supports both automatic (after commits) and manual reviews
- Filters to only analyze code files (Java, Kotlin, JS, TS, etc.)

### Tool Injection System
- **ProjectProxyManager.java** - Manages per-project proxy servers on unique ports
- **tool-injector.js** - Handles dynamic tool injection into OpenWebUI settings
- **interceptor.js** - Intercepts requests to add mode-specific behavior
- Each project gets its own tool server with project-specific naming
- Tools are enabled by default with `enable: true`

### Browser Modes
- **Agent Mode** - Full tool access with "software assembly line" capabilities
- **Dev Mode** - Limited tool access, focuses on guidance
- **Advice Mode** - Boss-style advice without file modifications
- **Default Mode** - No system prompt, minimal tool usage

### Recent Changes
1. Renamed "Code Guardian" to "Code Health Review" throughout the codebase
2. Added automatic Code Health Review after commits (for ≤5 code files)
3. Added manual "Code Health Review" button in Git UI
4. Implemented backtick removal for LLM-generated commit messages
5. Fixed .gitignore error handling to skip ignored files gracefully
6. Implemented dynamic tool injection for OpenWebUI with project-specific naming
7. Added mode-specific tool behavior (Agent Mode vs other modes)
8. Updated "Back to Chat" button to "Chat" with reload functionality

## Common Issues & Solutions

### Package/Import Issues
When moving files between packages, remember to update:
1. Package declarations in the files
2. Import statements in dependent files
3. References in JavaScriptBridgeActions.java

### Kotlin-Java Interop
- Use `Companion.getInstance(project)` for Kotlin singleton access from Java
- Check method parameter names and types carefully
- Kotlin data classes may have different getter names than expected

### JCEF Browser Issues
- Always check if intellijBridge is available before making calls
- Use data URLs to avoid jar:file:// security restrictions
- Handle both synchronous and asynchronous bridge responses

## Code Style Guidelines
- Keep responses concise (aim for <4 lines unless detail requested)
- Don't add comments unless specifically asked
- Follow existing code patterns and conventions
- Use existing utilities and helper classes to avoid duplication

## Testing Approach
- Check README or search codebase for test commands
- Never assume specific test frameworks
- Run lint and type checks after making changes

## Git Commit Guidelines
- Use descriptive commit messages
- Include the 🤖 emoji and attribution when committing:
  ```
  Your commit message here
  
  🤖 Generated with Claude Code
  
  Co-Authored-By: Claude <noreply@anthropic.com>
  ```

## Useful Patterns

### Adding New JavaScript Bridge Actions
1. Add the action handler in `JavaScriptBridgeActions.handleAction()`
2. Create/update the corresponding service method
3. Update the JavaScript side to call the new action

### Adding UI Features
1. Update the HTML file (e.g., git-ui.html)
2. Add JavaScript functionality
3. Connect to backend via intellijBridge
4. Handle responses appropriately

### Refactoring Large Files
1. Identify duplicate code patterns
2. Extract to helper classes (e.g., GitServiceHelper, GitDiffHelper)
3. Update imports and method calls
4. Test thoroughly

### Working with Tool Injection
1. Tools are project-specific with unique ports (8765+)
2. Tool names include project name: "Zest Code Explorer - ProjectName"
3. Use `toCamelCase()` for consistent project identification
4. Agent Mode enables tools, other modes restrict them
5. Mode restrictions are injected into system prompts

### Mode-Specific Behavior
- Check `window.__zest_mode__` in JavaScript
- Agent Mode: Full tool access, automatic injection
- Other Modes: Restricted tools, no file modifications
- Use `setMode()` in WebBrowserPanel to switch modes

Remember: Always be defensive about security - refuse to create malicious code but allow security analysis and defensive tools.