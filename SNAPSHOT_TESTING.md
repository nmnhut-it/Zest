# Quick Test Guide - Agent Snapshot System

## 🚀 How to Test WITHOUT Full Integration

I've created **3 ways** to test the snapshot system, from simplest to most complete:

---

## Option 1: Quick Standalone Test (EASIEST - NO IDE NEEDED)

**File**: `src/test/java/com/zps/zest/testgen/snapshot/SnapshotQuickTest.java`

**How to run**:
```bash
# In IntelliJ, right-click SnapshotQuickTest.java → Run 'SnapshotQuickTest.main()'
# OR from command line:
javac src/test/java/com/zps/zest/testgen/snapshot/SnapshotQuickTest.java
java -cp ... com.zps.zest.testgen.snapshot.SnapshotQuickTest
```

**What it tests**:
- ✅ Gson serialization of all data models
- ✅ GZIP compression/decompression
- ✅ Chat message conversion (LangChain4j → Serializable)
- ✅ No IntelliJ platform required!

**Expected output**:
```
╔════════════════════════════════════════════════════════════╗
║        AGENT SNAPSHOT QUICK TEST                          ║
╚════════════════════════════════════════════════════════════╝

📦 TEST 1: Basic Serialization
─────────────────────────────────
✓ Created snapshot with 3 messages
✓ JSON length: 1234 chars
✓ Sample JSON:
{
  "version": "1.0",
  "agentType": "CONTEXT",
  ...
✅ Basic serialization test PASSED!

🗜️  TEST 2: Compression
─────────────────────────────────
✓ Original size: 45000 chars
✓ Compressed size: 890 chars
✓ Compression ratio: 2.0%
✅ Compression test PASSED!

💬 TEST 3: Chat Message Conversion
─────────────────────────────────
✓ Converted SystemMessage: type=SYSTEM
✓ Converted UserMessage: type=USER
✓ Converted AiMessage: type=AI
✅ Chat message conversion test PASSED!

╔════════════════════════════════════════════════════════════╗
║        ✅ ALL TESTS PASSED!                                ║
╚════════════════════════════════════════════════════════════╝
```

---

## Option 2: JUnit Test with IntelliJ Platform (MORE COMPLETE)

**File**: `src/test/java/com/zps/zest/testgen/snapshot/AgentSnapshotTest.java`

**How to run**:
```bash
# In IntelliJ, right-click AgentSnapshotTest.java → Run 'AgentSnapshotTest'
```

**What it tests**:
- ✅ Everything from Option 1, PLUS:
- ✅ Real ContextAgent save/restore
- ✅ File system operations (save to `.zest-agent-snapshots/`)
- ✅ Chat memory restoration
- ✅ Tool state restoration (analyzed classes, context notes)
- ✅ Snapshot listing functionality

**Tests included**:
1. `testContextAgentSnapshotSaveAndLoad()` - Full agent snapshot cycle
2. `testSnapshotCompression()` - Large message compression
3. `testListSnapshots()` - Snapshot discovery

**Expected output**:
```
=== BEFORE SNAPSHOT ===
Chat messages: 3
Analyzed classes: 1
Context notes: 2

=== SNAPSHOT CREATED ===
Agent type: CONTEXT
Serialized messages: 3
Session ID: test-session-001
Saved to: D:\Zest\.zest-agent-snapshots\context-20251030-143022.json

=== SNAPSHOT LOADED ===
Loaded messages: 3

=== NEW AGENT (before restore) ===
Chat messages: 0
Analyzed classes: 0

=== NEW AGENT (after restore) ===
Chat messages: 3
Analyzed classes: 1
Context notes: 2

✅ TEST PASSED - Snapshot save/load works correctly!
```

---

## Option 3: Manual Interactive Test (FULL WORKFLOW)

**When to use**: After Options 1 & 2 pass, test with real agents in your workflow.

### Step-by-step:

1. **Add a test button somewhere** (TestGenerationPanel, Tools menu, etc.):

```java
// Example: Add to TestGenerationPanel or create a simple action
JButton testSnapshotButton = new JButton("Test Snapshot");
testSnapshotButton.addActionListener(e -> {
    testSnapshotWorkflow();
});
```

2. **Test snapshot workflow**:

```java
private void testSnapshotWorkflow() {
    try {
        // 1. Create mock services
        ZestLangChain4jService langChain = project.getService(ZestLangChain4jService.class);
        NaiveLLMService llmService = project.getService(NaiveLLMService.class);

        // 2. Create agent and simulate some work
        ContextAgent agent = new ContextAgent(project, langChain, llmService);
        agent.getChatMemory().add(UserMessage.from("Test prompt: Analyze UserService"));
        agent.getContextTools().getContextNotes().add("Test note 1");
        agent.getContextTools().getAnalyzedClasses().put("UserService", "class code...");

        // 3. Export snapshot
        AgentSnapshot snapshot = agent.exportSnapshot(
            "manual-test-" + System.currentTimeMillis(),
            "Manual test snapshot",
            "Test prompt: Analyze UserService"
        );

        // 4. Save to file
        File file = AgentSnapshotSerializer.saveToFile(snapshot, project);

        // 5. Show success dialog
        JOptionPane.showMessageDialog(null,
            "✅ Snapshot saved!\n\n" +
            "File: " + file.getName() + "\n" +
            "Messages: " + snapshot.getChatMessages().size() + "\n" +
            "Notes: " + snapshot.getContextToolsState().getContextNotes().size(),
            "Snapshot Test",
            JOptionPane.INFORMATION_MESSAGE
        );

        // 6. Test load
        AgentSnapshot loaded = AgentSnapshotSerializer.loadFromFile(file.getAbsolutePath());

        // 7. Restore into new agent
        ContextAgent newAgent = new ContextAgent(project, langChain, llmService);
        newAgent.restoreFromSnapshot(loaded);

        // 8. Verify
        JOptionPane.showMessageDialog(null,
            "✅ Snapshot restored!\n\n" +
            "Restored messages: " + newAgent.getChatMemory().messages().size() + "\n" +
            "Restored notes: " + newAgent.getContextTools().getContextNotes().size(),
            "Snapshot Test",
            JOptionPane.INFORMATION_MESSAGE
        );

    } catch (Exception ex) {
        JOptionPane.showMessageDialog(null,
            "❌ Test failed: " + ex.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE
        );
        ex.printStackTrace();
    }
}
```

3. **Open the Snapshot Manager UI**:

```kotlin
// Add menu action or toolbar button
val dialog = SnapshotManagerDialog(project)
dialog.show()
```

---

## What's Been Built

### Files Created:
1. **Core**:
   - `AgentSnapshotModels.kt` - Data classes
   - `AgentSnapshotSerializer.kt` - Gson serialization with compression
   - `SNAPSHOT_USAGE.md` - Complete documentation

2. **UI**:
   - `SnapshotManagerDialog.kt` - Browse/load/delete snapshots

3. **Tests**:
   - `SnapshotQuickTest.java` - Standalone test (no IDE)
   - `AgentSnapshotTest.java` - JUnit tests with IntelliJ platform

### Files Modified:
- `ContextAgent.java` - Added exportSnapshot/restoreFromSnapshot
- `CoordinatorAgent.java` - Added exportSnapshot/restoreFromSnapshot
- `TestWriterAgent.java` - Added exportSnapshot/restoreFromSnapshot
- `AITestMergerAgent.java` - Added exportSnapshot/restoreFromSnapshot

---

## Recommended Testing Order

1. ✅ **Run SnapshotQuickTest.main()** - Verify Gson & compression work
2. ✅ **Run AgentSnapshotTest JUnit** - Verify full save/load cycle
3. ✅ **Open SnapshotManagerDialog** - Verify UI works
4. ✅ **Integrate into workflow** - Add to StateMachine/TestGenerationPanel

---

## Expected Results

After running tests, you should see:
- ✅ Console output showing successful serialization
- ✅ Files created in `.zest-agent-snapshots/` directory
- ✅ JSON files are readable and compressed
- ✅ Agents restore with exact same state

---

## Troubleshooting

**"Cannot find symbol: AgentSnapshotSerializer"**
- Build the project in IntelliJ first (Ctrl+F9)
- Make sure Kotlin compilation succeeded

**"JAVA_HOME not set"**
- Use IntelliJ's build system instead of gradlew
- Build → Build Project (Ctrl+F9)

**"Snapshot file not found"**
- Check project root for `.zest-agent-snapshots/` directory
- Verify file permissions

---

## Next Steps After Testing

1. **Integrate with StateMachine** - Auto-save after each phase
2. **Add to UI** - Button in TestGenerationPanel
3. **Test with real LLM** - Use actual agent workflow
4. **Prompt experimentation** - Load snapshot, test different prompts!

🎉 **You now have a complete snapshot testing system!**
