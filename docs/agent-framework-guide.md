# Agent Framework Guide

## Overview

The Agent Framework is a JavaScript-based multi-agent system that runs within the JCEF browser in IntelliJ IDEA. It supports Google's Agent-to-Agent SDK and enables multiple specialized agents to collaborate on complex development tasks.

## Features

- **Multiple Agent Roles**: 10+ specialized agent types (Coordinator, Code Generator, Code Reviewer, etc.)
- **Asynchronous Task Processing**: Non-blocking execution with task queues
- **Agent Collaboration**: Agents can communicate and delegate tasks to each other
- **Google Agent Integration**: Support for Google's Agent-to-Agent SDK
- **Visual UI**: Real-time monitoring and control interface
- **IntelliJ Integration**: Direct code insertion and manipulation in the IDE

## Quick Start

### 1. Loading the Framework

The Agent Framework is automatically loaded when you open the JCEF browser in IntelliJ. To test it:

1. Open the browser panel in IntelliJ
2. Navigate to any page (the framework loads globally)
3. Open browser DevTools (F12) to see console logs

### 2. Using the Demo Page

Load the demo page to interact with the framework:

```javascript
// In browser console or load the HTML file
window.location.href = 'file:///path/to/agentDemo.html';
```

### 3. Creating Agents Programmatically

```javascript
// Create a single agent
const codeGenerator = await window.AgentFramework.createAgent('CODE_GENERATOR', {
    priority: 8,
    timeout: 30000
});

// Create a team of agents
const team = await window.AgentFramework.createTeam({
    name: 'Development Team',
    agents: [
        { role: 'CODE_GENERATOR' },
        { role: 'CODE_REVIEWER' },
        { role: 'TESTING' },
        { role: 'DOCUMENTATION' }
    ]
});
```

## Agent Roles

### Coordinator Agent
- **Role**: Orchestrates workflows and manages other agents
- **Capabilities**: orchestrate, delegate, monitor, aggregate
- **Usage**: Automatically created when you create a team

### Code Generator Agent
- **Role**: Generates code based on specifications
- **Capabilities**: generate, refactor, optimize, analyze_code
- **Example**:
```javascript
const generator = await window.AgentFramework.createAgent('CODE_GENERATOR');
generator.queueTask({
    type: 'generate',
    data: {
        type: 'class',
        language: 'javascript',
        specification: {
            name: 'UserService',
            methods: ['create', 'read', 'update', 'delete']
        }
    },
    callback: (error, result) => {
        if (!error) {
            console.log('Generated code:', result.code);
        }
    }
});
```

### Code Reviewer Agent
- **Role**: Reviews code for quality and best practices
- **Capabilities**: review, validate, suggest_improvements, check_standards
- **Example**:
```javascript
const reviewer = await window.AgentFramework.createAgent('CODE_REVIEWER');
reviewer.queueTask({
    type: 'review',
    data: {
        code: generatedCode,
        language: 'javascript'
    },
    callback: (error, result) => {
        console.log('Review score:', result.score);
        console.log('Issues:', result.issues);
        console.log('Suggestions:', result.suggestions);
    }
});
```

### Other Available Agents
- **DOCUMENTATION**: Creates and maintains documentation
- **TESTING**: Creates and executes tests
- **SECURITY**: Analyzes security vulnerabilities
- **PERFORMANCE**: Analyzes and optimizes performance
- **DATABASE**: Manages database operations
- **API_SPECIALIST**: Designs and integrates APIs
- **DEBUGGER**: Helps debug and troubleshoot issues

## Workflows

### Creating a Workflow

```javascript
window.AgentFramework.AgentManager.registerWorkflow('my-workflow', {
    name: 'My Custom Workflow',
    steps: [
        {
            name: 'Generate API',
            requiredCapability: 'design_api',
            type: 'design_api',
            data: {
                resource: 'products',
                operations: ['CRUD']
            }
        },
        {
            name: 'Generate Code',
            requiredCapability: 'generate',
            type: 'generate',
            data: {
                type: 'rest-api',
                language: 'javascript'
            }
        },
        {
            name: 'Create Tests',
            requiredCapability: 'create_tests',
            type: 'create_tests',
            data: {
                framework: 'jest'
            }
        }
    ]
});
```

### Executing a Workflow

```javascript
const coordinator = team.coordinator;
coordinator.queueTask({
    type: 'orchestrate',
    data: {
        workflow: window.AgentFramework.AgentManager.workflows.get('my-workflow'),
        data: { /* workflow input data */ }
    },
    callback: (error, result) => {
        console.log('Workflow completed:', result);
    }
});
```

## Agent Communication

Agents can communicate with each other:

```javascript
// Direct message between agents
await agent1.sendMessage(agent2.id, {
    type: 'request_review',
    data: { code: '...' },
    priority: 9
});

// Broadcast to all agents
for (const [id, agent] of window.AgentFramework.agents) {
    if (agent.role.capabilities.includes('review')) {
        await sourceAgent.sendMessage(id, message);
    }
}
```

## Google Agent Integration

### Setup

```javascript
await window.GoogleAgentIntegration.initialize({
    apiKey: 'YOUR_GOOGLE_API_KEY',
    projectId: 'YOUR_PROJECT_ID',
    region: 'us-central1'
});
```

### Collaborating with Google Agents

```javascript
// Local agent collaborates with Google agent
const result = await localAgent.collaborateWithGoogleAgent(
    'google-agent-id',
    {
        type: 'code_review',
        data: { /* task data */ }
    }
);
```

## UI Controls

The Agent UI provides visual monitoring and control:

- **Agent Status**: Real-time view of all agents and their states
- **Task Queues**: Monitor pending and active tasks
- **Create Agents**: UI for creating new agents
- **Workflow Visualization**: See workflow steps and progress

### Accessing the UI

```javascript
// Initialize the UI (auto-initialized with framework)
window.AgentUI.initialize('agent-ui-container');

// Programmatic controls
window.AgentUI.createAgent(); // Opens create dialog
window.AgentUI.showAgentStatus(); // Updates status display
```

## IntelliJ Integration

The framework integrates with IntelliJ through the JavaScript bridge:

```javascript
// Insert generated code into IDE
if (window.intellijBridge) {
    await window.intellijBridge.callIDE('insertCode', {
        code: generatedCode,
        language: 'javascript'
    });
}

// Show diff and replace
await window.intellijBridge.callIDE('showCodeDiffAndReplace', {
    code: newCode,
    language: 'javascript',
    textToReplace: oldCode
});
```

## Performance Considerations

- Agents run asynchronously to avoid blocking
- Task queues prevent overwhelming the system
- Automatic cleanup of completed tasks
- Memory management through task limits

## Debugging

Enable debug logging:

```javascript
// See all agent activities in console
window.AgentFramework.debug = true;

// Monitor specific agent
const agent = window.AgentFramework.agents.get(agentId);
console.log('Agent state:', agent.state);
console.log('Task queue:', agent.taskQueue);
console.log('Memory:', agent.memory);
```

## Best Practices

1. **Use Teams for Complex Tasks**: Create teams with specialized agents
2. **Define Clear Workflows**: Structure tasks with well-defined steps
3. **Handle Errors**: Always provide error callbacks for tasks
4. **Monitor Performance**: Use the UI to track agent performance
5. **Clean Up**: Terminate agents when no longer needed

## Example: Full Development Workflow

```javascript
// 1. Create a development team
const devTeam = await window.AgentFramework.createTeam({
    name: 'Full Stack Team',
    agents: [
        { role: 'API_SPECIALIST' },
        { role: 'CODE_GENERATOR' },
        { role: 'CODE_REVIEWER' },
        { role: 'TESTING' },
        { role: 'DOCUMENTATION' },
        { role: 'SECURITY' }
    ]
});

// 2. Define the workflow
window.AgentFramework.AgentManager.registerWorkflow('full-stack-app', {
    name: 'Full Stack Application',
    steps: [
        // API Design
        {
            name: 'Design API',
            requiredCapability: 'design_api',
            type: 'design_api',
            data: {
                resources: ['users', 'products', 'orders'],
                authentication: 'JWT',
                standards: 'REST'
            }
        },
        // Backend Generation
        {
            name: 'Generate Backend',
            requiredCapability: 'generate',
            type: 'generate',
            data: {
                type: 'express-server',
                language: 'javascript',
                database: 'mongodb'
            }
        },
        // Frontend Generation
        {
            name: 'Generate Frontend',
            requiredCapability: 'generate',
            type: 'generate',
            data: {
                type: 'react-app',
                language: 'javascript',
                ui_library: 'material-ui'
            }
        },
        // Security Review
        {
            name: 'Security Scan',
            requiredCapability: 'scan_vulnerabilities',
            type: 'security_scan',
            data: {
                scan_type: 'full'
            }
        },
        // Test Generation
        {
            name: 'Generate Tests',
            requiredCapability: 'create_tests',
            type: 'create_tests',
            data: {
                coverage_target: 80,
                frameworks: ['jest', 'cypress']
            }
        },
        // Documentation
        {
            name: 'Generate Docs',
            requiredCapability: 'document',
            type: 'generate_docs',
            data: {
                format: 'markdown',
                include_api_docs: true
            }
        }
    ]
});

// 3. Execute the workflow
devTeam.coordinator.queueTask({
    type: 'orchestrate',
    data: {
        workflow: window.AgentFramework.AgentManager.workflows.get('full-stack-app'),
        data: {
            project_name: 'MyApp',
            description: 'E-commerce platform'
        }
    },
    callback: (error, result) => {
        if (error) {
            console.error('Workflow failed:', error);
        } else {
            console.log('Workflow completed!');
            console.log('Results:', result);
            
            // Insert generated code into IntelliJ
            result.outputs.forEach(output => {
                if (output.result.code) {
                    window.intellijBridge.callIDE('insertCode', {
                        code: output.result.code,
                        language: output.result.language || 'javascript'
                    });
                }
            });
        }
    }
});

// 4. Monitor progress
const interval = setInterval(() => {
    const status = window.AgentFramework.AgentManager.getStatus();
    console.log('Active tasks:', status.activeTasks);
    
    if (status.activeTasks === 0) {
        clearInterval(interval);
        console.log('All tasks completed!');
    }
}, 1000);
```

## Troubleshooting

### Framework Not Loading
- Check browser console for errors
- Ensure JCEF browser is properly initialized
- Verify JavaScript files are in resources

### Agents Not Responding
- Check agent state: `agent.state`
- Verify task queue: `agent.taskQueue`
- Look for errors in console

### Google Integration Issues
- Verify API credentials
- Check network connectivity
- Review WebSocket connection status

## Future Enhancements

- Real AI model integration
- More sophisticated workflow orchestration
- Enhanced collaboration protocols
- Performance metrics and analytics
- Plugin system for custom agents