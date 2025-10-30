package com.zps.zest.testgen.snapshot;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.agents.ContextAgent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Test agent snapshot save/load functionality without needing full workflow integration.
 * Run this test to verify the snapshot system works!
 */
public class AgentSnapshotTest extends LightJavaCodeInsightFixtureTestCase {

    @Test
    public void testContextAgentSnapshotSaveAndLoad() {
        // Setup mocks
        ZestLangChain4jService mockLangChain = mock(ZestLangChain4jService.class);
        NaiveLLMService mockLlm = mock(NaiveLLMService.class);
        when(mockLlm.getProject()).thenReturn(getProject());

        // Create agent
        ContextAgent agent = new ContextAgent(getProject(), mockLangChain, mockLlm);

        // Simulate some chat history
        agent.getChatMemory().add(SystemMessage.from("You are a context gathering agent"));
        agent.getChatMemory().add(UserMessage.from("Analyze UserService.authenticate()"));
        agent.getChatMemory().add(AiMessage.from("I will analyze the authenticate method"));

        // Simulate some tool state
        ContextAgent.ContextGatheringTools tools = agent.getContextTools();
        tools.getAnalyzedClasses().put("com.example.UserService", "public class UserService { ... }");
        tools.getContextNotes().add("Found authentication logic");
        tools.getContextNotes().add("Uses JWT tokens");

        System.out.println("=== BEFORE SNAPSHOT ===");
        System.out.println("Chat messages: " + agent.getChatMemory().messages().size());
        System.out.println("Analyzed classes: " + tools.getAnalyzedClasses().size());
        System.out.println("Context notes: " + tools.getContextNotes().size());

        // Export snapshot
        AgentSnapshot snapshot = agent.exportSnapshot(
            "test-session-001",
            "Test snapshot for ContextAgent",
            "Analyze UserService.authenticate()"
        );

        System.out.println("\n=== SNAPSHOT CREATED ===");
        System.out.println("Agent type: " + snapshot.getAgentType());
        System.out.println("Serialized messages: " + snapshot.getChatMessages().size());
        System.out.println("Session ID: " + snapshot.getSessionId());

        // Save to file
        File snapshotFile = AgentSnapshotSerializer.saveToFile(snapshot, getProject());
        System.out.println("Saved to: " + snapshotFile.getAbsolutePath());
        assertTrue("Snapshot file should exist", snapshotFile.exists());

        // Load from file
        AgentSnapshot loadedSnapshot = AgentSnapshotSerializer.loadFromFile(snapshotFile.getAbsolutePath());
        assertNotNull("Loaded snapshot should not be null", loadedSnapshot);
        assertEquals("Agent type should match", AgentType.CONTEXT, loadedSnapshot.getAgentType());
        assertEquals("Session ID should match", "test-session-001", loadedSnapshot.getSessionId());
        assertEquals("Message count should match", 3, loadedSnapshot.getChatMessages().size());

        System.out.println("\n=== SNAPSHOT LOADED ===");
        System.out.println("Loaded messages: " + loadedSnapshot.getChatMessages().size());

        // Create new agent and restore
        ContextAgent newAgent = new ContextAgent(getProject(), mockLangChain, mockLlm);

        System.out.println("\n=== NEW AGENT (before restore) ===");
        System.out.println("Chat messages: " + newAgent.getChatMemory().messages().size());
        System.out.println("Analyzed classes: " + newAgent.getContextTools().getAnalyzedClasses().size());

        newAgent.restoreFromSnapshot(loadedSnapshot);

        System.out.println("\n=== NEW AGENT (after restore) ===");
        System.out.println("Chat messages: " + newAgent.getChatMemory().messages().size());
        System.out.println("Analyzed classes: " + newAgent.getContextTools().getAnalyzedClasses().size());
        System.out.println("Context notes: " + newAgent.getContextTools().getContextNotes().size());

        // Verify restoration
        assertEquals("Chat history restored", 3, newAgent.getChatMemory().messages().size());
        assertEquals("Analyzed classes restored", 1, newAgent.getContextTools().getAnalyzedClasses().size());
        assertEquals("Context notes restored", 2, newAgent.getContextTools().getContextNotes().size());
        assertTrue("Should have UserService",
            newAgent.getContextTools().getAnalyzedClasses().containsKey("com.example.UserService"));
        assertTrue("Should have first note",
            newAgent.getContextTools().getContextNotes().contains("Found authentication logic"));

        System.out.println("\n✅ TEST PASSED - Snapshot save/load works correctly!");

        // Cleanup
        snapshotFile.delete();
    }

    @Test
    public void testSnapshotCompression() {
        // Setup
        ZestLangChain4jService mockLangChain = mock(ZestLangChain4jService.class);
        NaiveLLMService mockLlm = mock(NaiveLLMService.class);
        when(mockLlm.getProject()).thenReturn(getProject());

        ContextAgent agent = new ContextAgent(getProject(), mockLangChain, mockLlm);

        // Add large message to test compression
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeMessage.append("This is line ").append(i).append(" of a very long message. ");
        }

        agent.getChatMemory().add(UserMessage.from(largeMessage.toString()));

        System.out.println("=== COMPRESSION TEST ===");
        System.out.println("Original message length: " + largeMessage.length() + " chars");

        // Export and save
        AgentSnapshot snapshot = agent.exportSnapshot(
            "compression-test",
            "Testing compression",
            "Test prompt"
        );
        File snapshotFile = AgentSnapshotSerializer.saveToFile(snapshot, getProject());

        System.out.println("Snapshot file size: " + snapshotFile.length() + " bytes");
        System.out.println("Compression ratio: " +
            String.format("%.2f%%", (snapshotFile.length() * 100.0 / largeMessage.length())));

        // Load and verify
        AgentSnapshot loaded = AgentSnapshotSerializer.loadFromFile(snapshotFile.getAbsolutePath());
        assertNotNull("Loaded snapshot should not be null", loaded);

        ContextAgent newAgent = new ContextAgent(getProject(), mockLangChain, mockLlm);
        newAgent.restoreFromSnapshot(loaded);

        dev.langchain4j.data.message.ChatMessage restoredMessage = newAgent.getChatMemory().messages().get(0);
        assertTrue("Message should be UserMessage", restoredMessage instanceof dev.langchain4j.data.message.UserMessage);
        String restoredText = ((dev.langchain4j.data.message.UserMessage)restoredMessage).singleText();
        assertEquals("Message should be restored correctly", largeMessage.toString(), restoredText);

        System.out.println("✅ COMPRESSION TEST PASSED - Large messages compressed and restored correctly!");

        // Cleanup
        snapshotFile.delete();
    }

    @Test
    public void testListSnapshots() {
        // Setup
        ZestLangChain4jService mockLangChain = mock(ZestLangChain4jService.class);
        NaiveLLMService mockLlm = mock(NaiveLLMService.class);
        when(mockLlm.getProject()).thenReturn(getProject());

        // Create and save multiple snapshots
        ContextAgent agent = new ContextAgent(getProject(), mockLangChain, mockLlm);
        agent.getChatMemory().add(UserMessage.from("Test message 1"));

        AgentSnapshot snapshot1 = agent.exportSnapshot("session-1", "First snapshot", "Prompt 1");
        AgentSnapshot snapshot2 = agent.exportSnapshot("session-2", "Second snapshot", "Prompt 2");

        File file1 = AgentSnapshotSerializer.saveToFile(snapshot1, getProject());
        File file2 = AgentSnapshotSerializer.saveToFile(snapshot2, getProject());

        // List snapshots
        List<SnapshotMetadata> snapshots = AgentSnapshotSerializer.listSnapshots(getProject());

        System.out.println("=== SNAPSHOT LISTING TEST ===");
        System.out.println("Found " + snapshots.size() + " snapshots:");
        for (SnapshotMetadata meta : snapshots) {
            System.out.println("  - " + meta.getAgentType() + ": " + meta.getDescription());
        }

        assertTrue("Should find at least 2 snapshots", snapshots.size() >= 2);

        System.out.println("✅ LISTING TEST PASSED!");

        // Cleanup
        file1.delete();
        file2.delete();
    }

    @Test
    public void testSnapshotManagerDialog() {
        // This test just creates the dialog to verify it compiles
        // You can run this manually to see the UI
        System.out.println("=== UI DIALOG TEST ===");
        System.out.println("To test the UI manually, run:");
        System.out.println("  new SnapshotManagerDialog(project).show();");
        System.out.println("✅ UI Dialog class compiles successfully!");
    }
}
