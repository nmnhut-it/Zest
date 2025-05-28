# JCEF Agents Quick Start

## Setup

1. **Load the Browser**: The agent system loads automatically when JCEF browser initializes
2. **Open Agent UI**: Look for the floating panel in bottom-right corner
3. **Check Console**: Press F12 for DevTools, check for "Agent Framework initialized" message

## Basic Usage

### Create an Agent
```javascript
// In browser console
const agent = await window.AgentFramework.createAgent('CODE_GENERATOR');
```

### Queue a Task
```javascript
agent.queueTask({
    type: 'generate',
    data: {
        type: 'function',
        language: 'javascript',
        specification: {
            name: 'calculateSum',
            description: 'Sum an array of numbers'
        }
    },
    callback: (error, result) => {
        if (!error) console.log(result.code);
    }
});
```

### Use Research Agent
```javascript
// Search for text
const research = await window.AgentFramework.createAgent('RESEARCH');
research.queueTask({
    type: 'search_text',
    data: {
        searchText: 'TODO',
        folderPath: '/'
    }
});
```

## Agent UI Features

### Tabs
- **Agents**: Monitor active agents and their stats
- **Workflows**: View/manage multi-agent workflows
- **Create**: Create new agents with custom config
- **Research**: Search tools for code exploration
- **Console**: View agent logs and events

### Research Tab Tools
1. **Search Project**: Full-text search with regex support
2. **Find Function**: Locate JavaScript function definitions
3. **Analyze Usage**: Find where functions are called
4. **Directory Tree**: Visualize project structure

## Common Workflows

### Code Generation + Review
```javascript
// Create team
const team = await window.AgentFramework.createTeam({
    name: 'Dev Team',
    agents: [
        { role: 'CODE_GENERATOR' },
        { role: 'CODE_REVIEWER' }
    ]
});

// Run workflow
const coordinator = team.coordinator;
coordinator.queueTask({
    type: 'orchestrate',
    data: {
        workflow: {
            name: 'Generate and Review',
            steps: [
                {
                    name: 'Generate Code',
                    requiredCapability: 'generate',
                    type: 'generate',
                    data: { /* spec */ }
                },
                {
                    name: 'Review Code',
                    requiredCapability: 'review',
                    type: 'review'
                }
            ]
        }
    }
});
```

## Debugging

### Check Agent Status
```javascript
window.AgentFramework.AgentManager.getStatus()
```

### View Agent Memory
```javascript
const agents = window.AgentFramework.agents;
for (const [id, agent] of agents) {
    console.log(agent.id, agent.state, agent.stats);
}
```

### Test Bridge Communication
```javascript
// Test file operations
await window.intellijBridge.callIDE('getProjectPath', {});
await window.intellijBridge.callIDE('listFiles', { path: '/', maxDepth: 2 });
```

## Tips

1. **Performance**: Limit search depth and use specific paths
2. **Memory**: Agents clean up after 1 hour idle
3. **Errors**: Check browser console and IntelliJ logs
4. **File Limits**: 10MB max file size for operations

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Agent UI not visible | Refresh browser, check console errors |
| "Unknown role" error | Ensure scripts loaded in correct order |
| No search results | Check project path, exclude patterns |
| Slow performance | Reduce search scope, check file sizes |

## Next Steps

1. Read [Architecture Overview](jcef-agents-overview.md)
2. Explore [Research Agent Guide](research-agent-guide.md)
3. Study [Bridge API Reference](intellij-bridge-reference.md)
