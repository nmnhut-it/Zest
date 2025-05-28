# JCEF Agents System Overview

## Architecture

The JCEF Agents system integrates AI agents directly into IntelliJ IDEA's embedded browser, enabling intelligent code assistance without external dependencies.

### Core Components

1. **Agent Framework** (`agentFramework.js`)
   - Base agent system with role-based architecture
   - Task queue management
   - Inter-agent communication via message bus
   - Supports 10+ predefined agent roles (Coordinator, Code Generator, Reviewer, etc.)

2. **IntelliJ Bridge** (`JavaScriptBridge.java`, `JavaScriptBridgeActions.java`)
   - Chunked messaging for large payloads
   - Bidirectional communication between browser and IDE
   - File operations, code manipulation, git integration

3. **Research Agent** (`researchAgentIntegrated.js`)
   - Text search across project files
   - JavaScript function discovery (including Cocos2d-x patterns)
   - Usage analysis and reference finding
   - Directory tree visualization

4. **Agent UI** (`agentUI.js`)
   - Floating panel in bottom-right corner
   - Tabs: Agents, Workflows, Create, Research, Console
   - Real-time agent status monitoring

## Key Files

```
src/main/
├── java/com/zps/zest/browser/
│   ├── JCEFBrowserManager.java      # Browser initialization, script loading
│   ├── JavaScriptBridge.java        # Core bridge implementation
│   ├── JavaScriptBridgeActions.java # Action routing
│   └── FileService.java             # File operations for Research Agent
└── resources/js/
    ├── agentFramework.js            # Core agent system
    ├── agentUI.js                   # UI components
    ├── fileAPI.js                   # File API wrapper
    ├── researchAgentIntegrated.js   # Research agent implementation
    └── intellijBridgeChunked.js     # Bridge client code
```

## How It Works

1. **Initialization**: JCEFBrowserManager loads all JS files when browser starts
2. **Agent Creation**: Agents are created through AgentFramework.createAgent(role)
3. **Task Execution**: Tasks are queued and processed asynchronously
4. **IDE Integration**: Actions go through JavaScriptBridge → JavaScriptBridgeActions → Service classes

## Adding New Features

### 1. New Agent Role
```javascript
// In agentFramework.js
const NewAgentRole = {
    id: 'new_agent',
    name: 'New Agent',
    description: 'Description',
    capabilities: ['capability1', 'capability2'],
    priority: 7
};

// Add to AgentRoles
window.AgentFramework.AgentRoles.NEW_AGENT = NewAgentRole;

// Create specialized class
class NewAgent extends Agent {
    async handle_capability1(task) {
        // Implementation
    }
}
```

### 2. New IDE Action
```java
// In JavaScriptBridgeActions.java
case "newAction":
    return handleNewAction(data);

// In appropriate service class
public String handleNewAction(JsonObject data) {
    // Implementation using IntelliJ APIs
}
```

### 3. New File Operation
```java
// In FileService.java
public String newOperation(JsonObject data) {
    return ReadAction.compute(() -> {
        // Implementation using VirtualFile API
    });
}
```

## Current Limitations

1. **File Operations**: Read-only, no write operations for safety
2. **Performance**: Large files (>10MB) are skipped
3. **Security**: Operations limited to project directory
4. **Browser Context**: No direct file system access, everything through bridge

## Next Steps

1. **Agent Persistence**: Save agent memory/state between sessions
2. **Workflow Designer**: Visual workflow creation
3. **LLM Integration**: Connect agents to AI models for enhanced capabilities
4. **Multi-Project**: Support for multiple projects simultaneously
