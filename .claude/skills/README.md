# Zest Agent Skills for BitZero Development

Skills for testing and reviewing BitZero game server code via MCP tools.

## Available Skills

| Skill | Trigger | Purpose |
|-------|---------|---------|
| `bitzero-test` | "test handler", "test doLogin", "generate tests" | Generate tests (unit/integration/e2e) |
| `bitzero-review` | "review handler", "check security", "audit code" | Review BitZero code for issues |
| `bitzero-methodology` | "how to test this", "code is untestable", "explore code" | Strategies for testing tightly-coupled code |

## Key Point: BitZero is in a JAR

BitZero framework classes are packaged in JAR files. **Standard grep/ripgrep won't work.**

The skills use **PSI-based MCP tools** that read JARs through IntelliJ's indexing:

| Tool | What It Does |
|------|--------------|
| `lookupClass` | Get class/method signatures from JARs |
| `getTypeHierarchy` | See superclasses, interfaces |
| `findImplementations` | Find interface implementations |
| `findUsages` | See how methods are called |
| `getCallHierarchy` | Trace callers/callees |
| `getMethodBody` | Get method implementation |

## How It Works

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Agent     │────▶│  MCP Tools  │────▶│  IntelliJ   │
│  (Claude)   │     │  (Zest)     │     │   PSI API   │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Skills    │     │  Test Gen   │     │  JAR Access │
│  (SKILL.md) │     │  Service    │     │  (Indexed)  │
└─────────────┘     └─────────────┘     └─────────────┘
```

## Test Type Selection

Skills recommend the right test type based on code coupling:

| Code Pattern | Recommended Test |
|--------------|-----------------|
| Pure business logic | Unit test |
| Constructor injection | Unit test |
| Handler with overridable `send()` | Unit test |
| Uses `BitZeroServer.getInstance()` | Integration test |
| `doLogin` in extension | Integration test |
| Complex game flows | E2E test |

**Don't force unit tests.** Integration and E2E tests are valid when code is tightly coupled.

## MCP Server Setup

Zest exposes tools at `http://localhost:45450/mcp`:

```json
{
  "mcpServers": {
    "zest-intellij": {
      "url": "http://localhost:45450/mcp"
    }
  }
}
```

## Installing Skills

Use Zest plugin action: **Zest → Install Skills For... → Qwen Coder / Claude Code**

Or manually copy to:
- Qwen Coder: `~/.qwen-coder/skills/`
- Claude Code: `~/.claude/skills/`

## Skill Invocation

Skills are model-invoked based on user request matching the description:

```
User: "Generate tests for LoginHandler"
→ Agent loads bitzero-test skill
→ Calls mcp_zest_lookupClass (reads JAR)
→ Follows skill instructions
→ Generates appropriate test type
```
