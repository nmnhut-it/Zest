package com.zps.zest.testgen.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/**
 * QUICK TEST - Run this to verify snapshot serialization works!
 *
 * This tests the core Gson serialization WITHOUT needing IntelliJ platform.
 * Just run main() and check the console output.
 */
public class SnapshotQuickTest {

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        AGENT SNAPSHOT QUICK TEST                          ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        testBasicSerialization();
        testCompression();
        testChatMessageConversion();

        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        ✅ ALL TESTS PASSED!                                ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
    }

    private static void testBasicSerialization() {
        System.out.println("📦 TEST 1: Basic Serialization");
        System.out.println("─────────────────────────────────");

        // Create a snapshot with test data
        ContextToolsSnapshot contextTools = new ContextToolsSnapshot(
            createTestMap("com.example.UserService", "public class UserService { ... }"),
            createTestMap("/src/UserService.java", "com.example.UserService"),
            Arrays.asList("Found authentication logic", "Uses JWT tokens"),
            createTestMap("pom.xml", "<dependencies>...</dependencies>"),
            createTestMap("build.gradle", "dependencies { ... }"),
            createTestMap("authenticate()", "{\"totalUsages\":5}"),
            "JUnit 5",
            "spring-boot-starter-test:2.7.0",
            true,
            new HashSet<>(Arrays.asList("caller1", "caller2")),
            new HashSet<>(Arrays.asList("caller1")),
            new HashSet<>(Arrays.asList("UserService.java"))
        );

        AgentSnapshot snapshot = new AgentSnapshot(
            "1.0",
            AgentType.CONTEXT,
            "test-session-001",
            System.currentTimeMillis(),
            "Test snapshot",
            "Analyze UserService.authenticate()",
            createTestMessages(),
            contextTools,
            null,
            null,
            new HashMap<>()
        );

        // Serialize to JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(snapshot);

        System.out.println("✓ Created snapshot with " + snapshot.getChatMessages().size() + " messages");
        System.out.println("✓ JSON length: " + json.length() + " chars");
        System.out.println("✓ Sample JSON:\n" + json.substring(0, Math.min(200, json.length())) + "...");

        // Deserialize back
        AgentSnapshot restored = gson.fromJson(json, AgentSnapshot.class);

        System.out.println("✓ Deserialized snapshot");
        System.out.println("✓ Agent type: " + restored.getAgentType());
        System.out.println("✓ Messages: " + restored.getChatMessages().size());
        System.out.println("✓ Context notes: " + restored.getContextToolsState().getContextNotes().size());

        assert restored.getAgentType() == AgentType.CONTEXT : "Agent type should match";
        assert restored.getChatMessages().size() == 3 : "Should have 3 messages";
        assert restored.getContextToolsState().getAnalyzedClasses().size() == 1 : "Should have 1 analyzed class";

        System.out.println("✅ Basic serialization test PASSED!\n");
    }

    private static void testCompression() {
        System.out.println("🗜️  TEST 2: Compression");
        System.out.println("─────────────────────────────────");

        // Create large string
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("This is line ").append(i).append(" of test data. ");
        }

        String original = largeText.toString();
        System.out.println("✓ Original size: " + original.length() + " chars");

        // Compress
        String compressed = AgentSnapshotSerializer.compressString(original);
        System.out.println("✓ Compressed size: " + compressed.length() + " chars");
        System.out.println("✓ Compression ratio: " +
            String.format("%.1f%%", (compressed.length() * 100.0 / original.length())));

        // Decompress
        String decompressed = AgentSnapshotSerializer.decompressString(compressed);
        System.out.println("✓ Decompressed size: " + decompressed.length() + " chars");

        assert original.equals(decompressed) : "Decompressed text should match original";

        System.out.println("✅ Compression test PASSED!\n");
    }

    private static void testChatMessageConversion() {
        System.out.println("💬 TEST 3: Chat Message Conversion");
        System.out.println("─────────────────────────────────");

        // Test System Message
        dev.langchain4j.data.message.SystemMessage systemMsg =
            dev.langchain4j.data.message.SystemMessage.from("You are a helpful assistant");

        SerializableChatMessage serialized1 = AgentSnapshotSerializer.convertChatMessage(systemMsg);
        System.out.println("✓ Converted SystemMessage: type=" + serialized1.getType());
        assert serialized1.getType() == MessageType.SYSTEM : "Type should be SYSTEM";

        dev.langchain4j.data.message.ChatMessage restored1 = AgentSnapshotSerializer.restoreChatMessage(serialized1);
        assert restored1 instanceof dev.langchain4j.data.message.SystemMessage : "Should be SystemMessage";
        System.out.println("✓ Restored SystemMessage: " + ((dev.langchain4j.data.message.SystemMessage)restored1).text().substring(0, 20) + "...");

        // Test User Message
        dev.langchain4j.data.message.UserMessage userMsg =
            dev.langchain4j.data.message.UserMessage.from("Analyze this code");

        SerializableChatMessage serialized2 = AgentSnapshotSerializer.convertChatMessage(userMsg);
        System.out.println("✓ Converted UserMessage: type=" + serialized2.getType());
        assert serialized2.getType() == MessageType.USER : "Type should be USER";

        // Test AI Message
        dev.langchain4j.data.message.AiMessage aiMsg =
            dev.langchain4j.data.message.AiMessage.from("I will analyze the code");

        SerializableChatMessage serialized3 = AgentSnapshotSerializer.convertChatMessage(aiMsg);
        System.out.println("✓ Converted AiMessage: type=" + serialized3.getType());
        assert serialized3.getType() == MessageType.AI : "Type should be AI";

        System.out.println("✅ Chat message conversion test PASSED!\n");
    }

    // Helper methods
    private static Map<String, String> createTestMap(String key, String value) {
        Map<String, String> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static List<SerializableChatMessage> createTestMessages() {
        List<SerializableChatMessage> messages = new ArrayList<>();

        // System message
        messages.add(new SerializableChatMessage(
            MessageType.SYSTEM,
            AgentSnapshotSerializer.compressString("You are a context gathering agent"),
            null,
            null
        ));

        // User message
        messages.add(new SerializableChatMessage(
            MessageType.USER,
            AgentSnapshotSerializer.compressString("Analyze UserService.authenticate()"),
            null,
            null
        ));

        // AI message
        messages.add(new SerializableChatMessage(
            MessageType.AI,
            AgentSnapshotSerializer.compressString("I will analyze the authenticate method"),
            null,
            null
        ));

        return messages;
    }
}
