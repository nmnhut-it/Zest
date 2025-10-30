# Agent Snapshot System - Usage Guide

## Overview

The Agent Snapshot System allows you to export and import the complete state of test generation agents (ContextAgent, CoordinatorAgent, TestWriterAgent, AITestMergerAgent). This enables you to:

- **Save agent state at any point** during test generation
- **Test different prompts** without re-running expensive context gathering
- **Debug agent behavior** by inspecting exact state at failure points
- **Share reproducible test cases** with your team

## Architecture

### Components

1. **Data Models** (`AgentSnapshotModels.kt`)
   - `AgentSnapshot`: Container for agent state
   - `SerializableChatMessage`: Chat message with compressed content
   - `ContextToolsSnapshot`: State of context gathering tools
   - `PlanningToolsSnapshot`: State of test planning tools
   - `MergingToolsSnapshot`: State of test merging tools

2. **Serializer** (`AgentSnapshotSerializer.kt`)
   - Uses Gson for JSON serialization
   - GZIP + Base64 compression for large text fields
   - Converts LangChain4j ChatMessage types to serializable format
   - File management in `.zest-agent-snapshots/` directory

3. **Agent Integration**
   - Each agent has `exportSnapshot()` and `restoreFromSnapshot()` methods
   - Tool classes have `exportState()` and `restoreState()` methods

4. **UI** (`SnapshotManagerDialog.kt`)
   - Browse existing snapshots
   - View snapshot details
   - Load, delete, and export snapshots

## Usage Examples

### 1. Programmatic Usage

#### Save a Context Agent Snapshot

```java
ContextAgent contextAgent = // ... your agent instance
String sessionId = "test-session-001";
String description = "After gathering context for UserService";
String originalPrompt = "Generate tests for UserService.authenticate()";

AgentSnapshot snapshot = contextAgent.exportSnapshot(sessionId, description, originalPrompt);
File savedFile = AgentSnapshotSerializer.saveToFile(snapshot, project);
System.out.println("Saved to: " + savedFile.getAbsolutePath());
```

#### Load and Restore a Snapshot

```java
// Load from file
AgentSnapshot snapshot = AgentSnapshotSerializer.loadFromFile("/path/to/snapshot.json");

// Restore into agent
ContextAgent contextAgent = new ContextAgent(project, langChainService, llmService);
contextAgent.restoreFromSnapshot(snapshot);

// Now the agent has the exact state from when the snapshot was taken
// You can run different prompts without re-gathering context
```

### 2. Testing Different Prompts

```java
// 1. Run test generation normally and save snapshot after context phase
ContextAgent contextAgent = // ...
// ... after context gathering completes ...
AgentSnapshot contextSnapshot = contextAgent.exportSnapshot(
    "session-001",
    "Context gathered for UserService",
    originalPrompt
);
AgentSnapshotSerializer.saveToFile(contextSnapshot, project);

// 2. Later, test different prompts WITHOUT re-running context gathering
AgentSnapshot snapshot = AgentSnapshotSerializer.loadFromFile(snapshotPath);

// Test Prompt Variation 1
ContextAgent agent1 = new ContextAgent(project, langChainService, llmService);
agent1.restoreFromSnapshot(snapshot);
// Run with prompt variation 1...

// Test Prompt Variation 2
ContextAgent agent2 = new ContextAgent(project, langChainService, llmService);
agent2.restoreFromSnapshot(snapshot);
// Run with prompt variation 2...
```

### 3. Testing with UI

1. Open the Snapshot Manager Dialog:
   - Add menu action or toolbar button to show dialog
   - `new SnapshotManagerDialog(project).show()`

2. View existing snapshots:
   - See agent type, timestamp, description, message count
   - Select a row to view full details

3. Load a snapshot:
   - Select snapshot from list
   - Click "Load Snapshot"
   - Integrate with your test workflow

4. Export/Delete:
   - Export snapshot to custom location
   - Delete snapshots you don't need

## File Structure

Snapshots are saved in your project root:

```
your-project/
└── .zest-agent-snapshots/
    ├── context-20251030-143022.json
    ├── coordinator-20251030-143145.json
    ├── test_writer-20251030-143300.json
    └── test_merger-20251030-143500.json
```

**Important**: Add `.zest-agent-snapshots/` to your `.gitignore` to avoid committing large snapshot files.

## Snapshot Content

Each snapshot contains:

1. **Version**: For schema compatibility
2. **Agent Type**: CONTEXT, COORDINATOR, TEST_WRITER, TEST_MERGER
3. **Session ID**: Identifier for the test generation session
4. **Timestamp**: When snapshot was created
5. **Description**: Human-readable description
6. **Original Prompt**: The user's original request
7. **Chat Messages**: Complete conversation history (compressed)
8. **Tool State**: Agent-specific tool state
   - Context: analyzed classes, notes, method usages, etc.
   - Coordinator: test plan, scenarios, reasoning
   - Merger: current test code, fix strategy

## Advanced: Workflow Testing

### Full Workflow Snapshot Chain

```java
// Save snapshots after each phase
AgentSnapshot contextSnapshot = contextAgent.exportSnapshot(...);
AgentSnapshotSerializer.saveToFile(contextSnapshot, project);

AgentSnapshot coordinatorSnapshot = coordinatorAgent.exportSnapshot(...);
AgentSnapshotSerializer.saveToFile(coordinatorSnapshot, project);

AgentSnapshot writerSnapshot = testWriterAgent.exportSnapshot(...);
AgentSnapshotSerializer.saveToFile(writerSnapshot, project);
```

### Resume from Specific Phase

```java
// Skip context gathering, start from coordinator
AgentSnapshot contextSnapshot = AgentSnapshotSerializer.loadFromFile(contextPath);
ContextAgent contextAgent = new ContextAgent(...);
contextAgent.restoreFromSnapshot(contextSnapshot);

// Use restored context tools in coordinator
CoordinatorAgent coordinator = new CoordinatorAgent(project, services, contextAgent.getContextTools());
AgentSnapshot coordinatorSnapshot = AgentSnapshotSerializer.loadFromFile(coordinatorPath);
coordinator.restoreFromSnapshot(coordinatorSnapshot);

// Now run coordinator with different system prompt...
```

## Best Practices

1. **Descriptive Names**: Use clear descriptions when saving snapshots
   - Good: "Context gathered for AuthService - 15 classes analyzed"
   - Bad: "test snapshot 1"

2. **Save at Key Points**:
   - After expensive operations (context gathering)
   - Before prompt variations
   - At failure points for debugging

3. **Clean Up Regularly**: Delete old snapshots to save disk space

4. **Version Control**: Don't commit snapshots - they're large and specific to your machine

5. **Share Snapshots**: For bug reports, export snapshot JSON and share with team

## Integration with StateMachine

To integrate snapshot functionality with your `StateMachineTestGenerationService`:

```java
// Add snapshot save points in your state machine
public class StateMachineTestGenerationService {

    private void saveContextSnapshot(String sessionId) {
        if (contextAgent != null) {
            AgentSnapshot snapshot = contextAgent.exportSnapshot(
                sessionId,
                "Context phase completed",
                lastUserPrompt
            );
            AgentSnapshotSerializer.saveToFile(snapshot, project);
        }
    }

    // Call after context phase
    handleContextGathered(...) {
        // ... existing logic ...
        saveContextSnapshot(sessionId);
    }
}
```

## Troubleshooting

### Snapshot file is huge
- This is normal for agents with many tool calls
- Files are compressed with GZIP
- Consider cleaning up old snapshots

### Failed to restore snapshot
- Check version compatibility
- Ensure agent type matches
- Verify file is valid JSON

### Changes not reflected after restore
- Ensure you restored into correct agent type
- Check that tool states are being exported/restored
- Verify chat memory was cleared before restore

## Future Enhancements

Possible improvements:
- Replay dialog with prompt editing UI
- Diff viewer for comparing snapshots
- Automatic snapshot on error/cancellation
- Snapshot compression/archiving
- Integration tests using snapshots as fixtures
