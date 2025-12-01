# Qwen Code Integration for IntelliJ (Zest Plugin)

This directory contains auto-generated configuration for Qwen Code CLI integration with IntelliJ.

## MCP Server (Model Context Protocol)

The settings.json configures the **zest-intellij** MCP server in **HTTP mode**:

### Transport: HTTP (SSE)

The MCP server runs within the IntelliJ plugin as an HTTP server:
- **Auto-starts** when IntelliJ opens
- **Runs on port 45450** (http://localhost:45450/mcp)
- **Application-level** - Single server serves all open projects
- **Direct IntelliJ SDK access** - Uses PSI for code analysis
- **HTTP/SSE communication** - JSON-RPC over HTTP with Server-Sent Events

### Available MCP Tools

**getCurrentFile(projectPath)**
- Gets the currently open file in the IntelliJ editor
- Returns file path, language, and content
- Parameter: projectPath (absolute path to project)

**lookupMethod(projectPath, className, methodName)**
- Looks up method signatures using PSI
- Works with project classes, JARs, and JDK
- Returns method signatures with parameters and return types
- Parameters:
  - projectPath: Absolute path to project
  - className: Fully qualified class name
  - methodName: Method name to find

**lookupClass(projectPath, className)**
- Looks up class implementation using PSI
- Works with project classes, JARs, and JDK
- Returns class structure, fields, methods, inner classes
- Parameters:
  - projectPath: Absolute path to project
  - className: Fully qualified class name (use \$ for inner classes)

### Available MCP Prompts (Slash Commands)

The MCP server exposes prompts that appear as slash commands in Qwen Code CLI:

**test** - Generate unit tests for selected code
- Arguments: code (optional)
- Usage: `/test` or `/test <code snippet>`
- Generates comprehensive JUnit tests with edge cases

**review** - Code quality review and improvements
- Arguments: code (optional)
- Usage: `/review` or `/review <code snippet>`
- Analyzes code quality, bugs, performance, security

**explain** - Explain how code works
- Arguments: code (optional)
- Usage: `/explain` or `/explain <code snippet>`
- Provides clear explanation of code behavior and patterns

**refactor** - Suggest refactoring improvements
- Arguments: code (optional)
- Usage: `/refactor` or `/refactor <code snippet>`
- Recommends design patterns, duplication removal, naming improvements

**bugs** - Find potential bugs
- Arguments: code (optional)
- Usage: `/bugs` or `/bugs <code snippet>`
- Identifies null pointers, race conditions, memory leaks, logic errors

**optimize** - Performance optimization suggestions
- Arguments: code (optional)
- Usage: `/optimize` or `/optimize <code snippet>`
- Analyzes time/space complexity and resource usage

**commit** - Generate git commit message
- Arguments: diff (optional)
- Usage: `/commit` or `/commit <git diff>`
- Creates conventional commit messages from changes

## Usage with Qwen

The MCP server is automatically available when you use Qwen in this project:

```bash
# Using MCP tools
qwen "analyze the current file"
qwen "explain how UserService.getUserById works"
qwen "review the Customer class structure"

# Using MCP prompts as slash commands
qwen /test "function calculateTotal(items) { return items.reduce((a,b) => a + b.price, 0); }"
qwen /review
qwen /bugs "if (user.name = 'admin') { grantAccess(); }"
qwen /commit
```

## Configuration

API credentials are automatically sourced from Zest plugin settings:
- Base URL: Configured in IntelliJ → Settings → Tools → Zest
- API Key: From Zest settings
- Model: Selected model from Zest

No manual API configuration needed!

## Troubleshooting

**MCP server not responding:**
- Verify IntelliJ plugin is running
- Check port 45450 is not in use by another application
- Restart IntelliJ to restart the MCP server
- Check IntelliJ logs: Help → Show Log in Files

**Prompts/tools not working:**
- Verify project is open in IntelliJ (provide correct projectPath)
- Check that files are open in editor for getCurrentFile
- Use fully qualified class names for lookupMethod/lookupClass
- Ensure project path matches exactly (use forward slashes on Windows)

**Connection errors:**
- Visit http://localhost:45450/sse to test server is running
- Check firewall isn't blocking port 45450
- Verify no other MCP servers using same port

## Resources

- Qwen Code Documentation: https://docs.qwen.dev
- MCP Specification: https://modelcontextprotocol.io
- MCP Java SDK: https://github.com/modelcontextprotocol/java-sdk