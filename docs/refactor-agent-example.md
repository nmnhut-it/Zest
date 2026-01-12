# Refactor Agent - MCP Integration Example

## Overview

The Refactor Agent is exposed via Zest's MCP server (port 45450) for use with external tools like Claude Code, Continue.dev, Tabby Chat, etc.

## MCP Tools Added

### Refactoring Analysis
1. **askUser** - Ask user questions via IntelliJ dialogs
2. **analyzeRefactorability** - Scan code for refactoring opportunities using IntelliJ inspections

### Test & Coverage (NEW!)
3. **getCoverageData** - Get current test coverage from IntelliJ's coverage runner
4. **analyzeCoverage** - Get coverage analysis with improvement suggestions
5. **getTestInfo** - Get test class information (test methods, framework detection)

## MCP Prompt Added

- **refactor** - Interactive refactoring assistant with dynamic goal discovery

## How to Use

### From Claude Code

1. Ensure Zest MCP server is running (starts automatically with IntelliJ)

2. In Claude Code, use the refactor prompt:
```bash
/refactor
```

3. Claude will:
   - Ask what to refactor (via IntelliJ dialog)
   - Analyze code using IntelliJ inspections
   - Present findings grouped by impact
   - Ask user to choose focus area
   - Generate refactoring options
   - Create detailed plan
   - Get user approval

### From Continue.dev

Add to your `continue/config.json`:

```json
{
  "mcpServers": [
    {
      "name": "zest-intellij",
      "transport": "sse",
      "url": "http://localhost:45450/mcp"
    }
  ]
}
```

Then use in chat:
```
Use the refactor prompt from zest-intellij to help me improve code testability
```

### Manual Tool Usage

You can also call tools directly:

```typescript
// Ask user a question
askUser({
  projectPath: "/path/to/project",
  questionText: "What would you like to refactor?",
  questionType: "SINGLE_CHOICE",
  options: [
    {label: "Current file", description: "Refactor the currently open file"},
    {label: "Specific class", description: "I'll specify the class"}
  ],
  header: "Refactor Code"
})

// Returns:
{
  "cancelled": false,
  "questionType": "SINGLE_CHOICE",
  "selectedOptions": ["Current file"]
}
```

```typescript
// Analyze code for refactoring
analyzeRefactorability({
  projectPath: "/path/to/project",
  className: "com.example.OrderService",
  focusArea: "TESTABILITY"  // or "COMPLEXITY", "CODE_SMELLS", "ALL"
})

// Returns:
{
  "className": "OrderService",
  "filePath": "/src/main/java/com/example/OrderService.java",
  "teamRules": [
    "Use Constructor Injection",
    "Follow Repository pattern"
  ],
  "findings": [
    {
      "category": "TESTABILITY",
      "severity": "HIGH",
      "line": 45,
      "issue": "Static method call: PaymentGateway.charge()",
      "reason": "Cannot mock static methods with standard Mockito",
      "suggestedFix": "Extract to dependency injection",
      "estimatedImpact": "Coverage improvement: 45% → 85%"
    }
  ],
  "metrics": {
    "cyclomaticComplexity": {"avg": 12, "max": 15},
    "testCoverage": 45,
    "mutableFields": 8,
    "staticCalls": 3
  }
}
```

```typescript
// Get test coverage data
getCoverageData({
  projectPath: "/path/to/project",
  className: "com.example.OrderService"
})

// Returns:
{
  "hasCoverage": true,
  "className": "OrderService",
  "classCoverage": "45%",
  "methodsCoverage": [
    {"methodName": "processOrder", "coverage": "30%"},
    {"methodName": "calculateTotal", "coverage": "0%"},
    {"methodName": "validateOrder", "coverage": "80%"}
  ],
  "suiteInfo": {
    "suiteName": "OrderServiceTest",
    "timestamp": 1704097200000
  }
}
```

```typescript
// Analyze coverage with suggestions
analyzeCoverage({
  projectPath: "/path/to/project",
  className: "com.example.OrderService"
})

// Returns:
{
  "className": "OrderService",
  "methodCoveragePercent": 60,
  "coveredMethods": 6,
  "totalMethods": 10,
  "uncoveredMethods": [
    {"methodName": "calculateTotal", "suggestion": "Add unit test for calculateTotal()"},
    {"methodName": "sendEmail", "suggestion": "Add unit test for sendEmail()"}
  ],
  "suggestions": [
    "Add tests for 4 uncovered methods",
    "Coverage is moderate (60%). Add tests for uncovered methods to reach 80%."
  ]
}
```

```typescript
// Get test class information
getTestInfo({
  projectPath: "/path/to/project",
  className: "com.example.OrderService"
})

// Returns:
{
  "className": "OrderService",
  "hasTestClass": true,
  "testClassName": "com.example.OrderServiceTest",
  "testFilePath": "/src/test/java/com/example/OrderServiceTest.java",
  "testMethodCount": 8,
  "testMethods": [
    {"name": "testProcessOrder"},
    {"name": "testCalculateTotal"},
    {"name": "testValidateOrder_withValidInput"}
  ],
  "testFramework": "JUnit 5"
}
```

## Example Conversation

```
User: /refactor

Claude: [Calls askUser tool]
        Dialog appears in IntelliJ with options

User: [Selects "Current file"]

Claude: [Calls getCurrentFile, getCoverageData, getTestInfo, then analyzeRefactorability]

        I analyzed OrderService.java and found:

        📊 CURRENT COVERAGE:
        • Overall: 45%
        • processOrder(): 30% covered
        • calculateTotal(): 0% covered (NOT TESTED!)

        📋 TEST INFO:
        • Test class: OrderServiceTest.java (8 test methods, JUnit 5)
        • Missing tests for: calculateTotal(), sendEmail()

        🔴 HIGH IMPACT (Testability Blockers):
        • Line 45: Static PaymentGateway.charge()
          Why: Cannot mock static methods with Mockito
          Fix: Extract to dependency injection
          Impact: Coverage 45% → 85%

        🟡 MEDIUM IMPACT (Complexity):
        • Line 89: Cyclomatic complexity 15
          Why: Complex methods hard to test
          Fix: Extract pure methods

        Your team rules (from .zest/rules.md):
        ✓ Use Constructor Injection
        ✓ Follow Repository pattern

        [Calls askUser to choose focus area]

User: [Selects "HIGH impact"]

Claude: To improve testability, I can:

        OPTION 1: Introduce Dependency Injection
        • Extract PaymentGateway to constructor
        • Follows your rule: "Use Constructor Injection"
        ✓ Pros: Can mock dependencies, +40% coverage
        ✗ Cons: Requires updating 5 callers
        Risk: LOW

        OPTION 2: Extract Pure Methods
        ✓ Pros: No API changes
        ✗ Cons: Doesn't fix static calls

        [Calls askUser to select approach]

User: [Selects "Option 1: DI"]

Claude: [Calls askUser for constraints]

User: [Selects "Keep API compatible", "Generate tests"]

Claude: Here's the refactoring plan:

        STEP 1: Extract PaymentGateway Dependency
        • Create PaymentGateway interface
        • Add constructor with injection
        • Keep old constructor @Deprecated

        STEP 2: Generate Tests
        • Create OrderServiceTest.java
        • 8 test cases with Mockito

        Estimated improvements:
        • Coverage: 45% → 87% (+42%)
        • Mockable dependencies: 0 → 2

        [Calls askUser for approval]

User: [Selects "Yes, apply it"]

Claude: I've created the detailed plan. The execution tools
        will be available soon. For now, I can show you the
        exact code to write for each step.
```

## What Gets Analyzed

### IntelliJ Inspections Used

- **Complexity**: MethodLength, OverlyComplexMethod, CyclomaticComplexity
- **Code Quality**: ClassWithTooManyMethods, ClassWithTooManyFields, MethodWithTooManyParameters
- **Testability**: StaticMethodOnlyUsedInOneClass

### PSI-Based Analysis

- Static method calls (testability blockers)
- Long methods (>30 lines)
- Too many parameters (>5)
- God classes (>15 methods)
- High mutable state (>8 mutable fields)

### Team Rules Integration

Automatically loads rules from `.zest/rules.md` and includes them in analysis:
- Suggests refactorings that follow team conventions
- Cites applicable rules in recommendations

## Architecture

```
External LLM (Claude Code / Continue.dev)
    ↓ MCP (HTTP/SSE on port 45450)
Zest MCP Server
    ├─ askUser → IntelliJ Dialog (UserQuestionDialog)
    └─ analyzeRefactorability
        ├─ IntelliJ Inspections API
        ├─ CodePatternAnalyzer (PSI-based)
        └─ ZestRulesLoader (.zest/rules.md)
```

## Benefits

1. **No Zest UI needed** - External tools drive interaction
2. **IntelliJ native** - Uses IntelliJ's battle-tested inspections
3. **Team rules aware** - Respects project conventions
4. **Interactive** - Dynamic dialogs, not static forms
5. **Composable** - Works with any MCP client

## Next Steps (Future Work)

- `planRefactoring` tool - Generate detailed execution plan
- `applyRefactoring` tool - Execute refactoring via PSI API
- `getRefactoringOptions` tool - LLM-powered option generation
