# Agent Framework Quick Reference

## 🚀 Quick Start

```javascript
// 1. Create an agent
const agent = await window.AgentFramework.createAgent('CODE_GENERATOR');

// 2. Execute a task
agent.queueTask({
    type: 'generate',
    data: { 
        language: 'javascript',
        specification: { name: 'UserService' }
    },
    callback: (err, result) => console.log(result)
});

// 3. Create a team
const team = await window.AgentFramework.createTeam({
    name: 'Dev Team',
    agents: [
        { role: 'CODE_GENERATOR' },
        { role: 'CODE_REVIEWER' },
        { role: 'TESTING' }
    ]
});
```

## 📋 Available Agent Roles

| Role | ID | Key Capabilities |
|------|----|--------------------|
| Coordinator | `COORDINATOR` | orchestrate, delegate, monitor |
| Code Generator | `CODE_GENERATOR` | generate, refactor, optimize |
| Code Reviewer | `CODE_REVIEWER` | review, validate, suggest |
| Documentation | `DOCUMENTATION` | document, explain, guide |
| Testing | `TESTING` | create_tests, run_tests |
| Security | `SECURITY` | scan, validate_auth, audit |
| Performance | `PERFORMANCE` | profile, optimize, benchmark |
| Database | `DATABASE` | query, migrate, schema_design |
| API Specialist | `API_SPECIALIST` | design_api, integrate, validate |
| Debugger | `DEBUGGER` | debug, trace, analyze_logs |

## 🛠️ Core API Methods

### Framework Level
```javascript
// Create agent
await window.AgentFramework.createAgent(role, config)

// Create team
await window.AgentFramework.createTeam(config)

// Register workflow
window.AgentFramework.AgentManager.registerWorkflow(id, workflow)

// Execute workflow
await window.AgentFramework.executeWorkflow(workflowId, data, teamId)

// Get status
window.AgentFramework.AgentManager.getStatus()
```

### Agent Level
```javascript
// Queue task
agent.queueTask({ type, data, priority, callback })

// Send message
await agent.sendMessage(targetAgentId, message)

// Get context
agent.getContext()

// Terminate
agent.terminate()
```

### IntelliJ Bridge
```javascript
// Insert code
await window.intellijBridge.callIDE('insertCode', { code, language })

// Show diff
await window.intellijBridge.callIDE('showCodeDiffAndReplace', { 
    code, language, textToReplace 
})

// File operations
await window.intellijBridge.callIDE('replaceInFile', { 
    filePath, search, replace 
})
```

## 📁 Access Methods

1. **Menu**: Right-click → Zest → Show Agent Framework Demo
2. **URL**: `jcef://resource/html/agentDemo.html`
3. **Code**: `browserManager.loadAgentDemo()`
4. **Console**: `window.AgentFramework`

## 🔧 Configuration

```javascript
window.AgentFramework.config = {
    maxChunkSize: 1400,      // Max message chunk size
    sessionTimeout: 60000,   // Session timeout (ms)
    maxRetries: 3,          // Max retry attempts
    apiEndpoint: '/api'     // API endpoint
};
```

## 📊 Task Structure

```javascript
{
    id: 'task_123',          // Auto-generated
    type: 'generate',        // Task type
    data: {                  // Task-specific data
        language: 'javascript',
        specification: {}
    },
    priority: 5,             // 1-10 (higher = more urgent)
    callback: function,      // Completion callback
    timestamp: Date.now()    // Auto-generated
}
```

## 🔄 Workflow Definition

```javascript
const workflow = {
    name: 'My Workflow',
    steps: [
        {
            name: 'Step 1',
            requiredCapability: 'generate',
            type: 'generate',
            data: { /* step data */ }
        },
        {
            name: 'Step 2',
            requiredCapability: 'review',
            type: 'review',
            data: { /* step data */ }
        }
    ]
};
```

## 🐛 Debugging

```javascript
// Enable debug mode
window.AgentFramework.debug = true;

// Inspect agent
window.AgentFramework.agents.get(agentId)

// View all agents
window.AgentFramework.AgentManager.getStatus()

// Check message queue
window.AgentFramework.messageQueue

// Performance metrics
window.AgentFramework.PerformanceMonitor.getReport()
```

## ⚡ Common Patterns

### Sequential Task Execution
```javascript
async function sequentialTasks() {
    const agent = await window.AgentFramework.createAgent('CODE_GENERATOR');
    
    const task1 = await executeTask(agent, { type: 'analyze' });
    const task2 = await executeTask(agent, { 
        type: 'generate', 
        data: { context: task1.result }
    });
    
    return task2;
}
```

### Parallel Agent Execution
```javascript
async function parallelAgents() {
    const agents = await Promise.all([
        window.AgentFramework.createAgent('CODE_GENERATOR'),
        window.AgentFramework.createAgent('TESTING'),
        window.AgentFramework.createAgent('DOCUMENTATION')
    ]);
    
    const results = await Promise.all(
        agents.map(agent => executeTask(agent, { type: 'process' }))
    );
    
    return results;
}
```

### Error Handling
```javascript
agent.queueTask({
    type: 'risky_operation',
    data: { /* ... */ },
    callback: (error, result) => {
        if (error) {
            console.error('Task failed:', error);
            // Retry or fallback logic
        } else {
            console.log('Success:', result);
        }
    }
});
```

## 🔐 Security

### Permission Check
```javascript
if (window.AgentFramework.AccessControl.checkPermission(agent.role.id, 'write_files')) {
    // Proceed with file operation
}
```

### Input Validation
```javascript
const sanitizedData = JSON.parse(JSON.stringify(untrustedData));
```

## 📚 Resources

- **Demo Page**: `jcef://resource/html/agentDemo.html`
- **Test Page**: `jcef://resource/html/test.html`
- **Framework JS**: `jcef://resource/js/agentFramework.js`
- **UI Components**: `jcef://resource/js/agentUI.js`
- **Google Integration**: `jcef://resource/js/googleAgentIntegration.js`

## 💡 Tips

1. **Start Simple**: Begin with single agents before creating complex workflows
2. **Use Teams**: For multi-step processes, create a team with a coordinator
3. **Monitor Performance**: Keep an eye on task queues and memory usage
4. **Handle Errors**: Always provide error callbacks for critical tasks
5. **Clean Up**: Terminate agents when done to free resources

## 🆘 Troubleshooting

| Issue | Solution |
|-------|----------|
| Framework not loading | Check browser console, ensure JCEF is initialized |
| Agent not responding | Check agent state with `agent.state` |
| Task timeout | Increase `config.timeout` or check task complexity |
| Memory issues | Enable GC, reduce agent count, clear old memories |
| Bridge errors | Verify IntelliJ connection with `window.intellijBridge` |

---

*Version: 1.0.0 | License: Apache 2.0*