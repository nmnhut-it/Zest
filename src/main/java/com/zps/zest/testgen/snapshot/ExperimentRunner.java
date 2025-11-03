package com.zps.zest.testgen.snapshot;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.agents.ContextAgent;
import com.zps.zest.testgen.agents.CoordinatorAgent;
import com.zps.zest.testgen.agents.TestWriterAgent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs agent experiments with modified chat memory for prompt testing.
 * Creates temporary agent instances that don't save checkpoints.
 */
public class ExperimentRunner {

    private static final Logger LOG = Logger.getInstance(ExperimentRunner.class);

    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final NaiveLLMService naiveLlmService;

    public ExperimentRunner(@NotNull Project project) {
        this.project = project;
        this.langChainService = project.getService(ZestLangChain4jService.class);
        this.naiveLlmService = project.getService(NaiveLLMService.class);
    }

    /**
     * Run an experiment with modified messages.
     * Creates a temporary agent, loads modified memory, and executes it.
     */
    public void runExperiment(
            @NotNull AgentSnapshot originalSnapshot,
            @NotNull List<SerializableChatMessage> modifiedMessages,
            @NotNull Consumer<String> outputCallback) {

        LOG.info("Running experiment with " + modifiedMessages.size() + " messages");
        outputCallback.accept("📋 Experiment Configuration:\n");
        outputCallback.accept("  Agent Type: " + originalSnapshot.getAgentType() + "\n");
        outputCallback.accept("  Messages: " + modifiedMessages.size() + "\n");
        outputCallback.accept("  Original Session: " + originalSnapshot.getSessionId() + "\n\n");

        try {
            switch (originalSnapshot.getAgentType()) {
                case CONTEXT:
                    runContextAgentExperiment(originalSnapshot, modifiedMessages, outputCallback);
                    break;
                case COORDINATOR:
                    runCoordinatorAgentExperiment(originalSnapshot, modifiedMessages, outputCallback);
                    break;
                case TEST_WRITER:
                    outputCallback.accept("⚠️ TestWriterAgent experiments not yet supported\n");
                    break;
                case TEST_MERGER:
                    outputCallback.accept("⚠️ TestMergerAgent experiments not yet supported\n");
                    break;
                default:
                    outputCallback.accept("❌ Unknown agent type: " + originalSnapshot.getAgentType() + "\n");
            }
        } catch (Exception e) {
            LOG.error("Experiment failed", e);
            outputCallback.accept("\n\n❌ Experiment failed: " + e.getMessage() + "\n");
        }
    }

    private void runContextAgentExperiment(
            AgentSnapshot originalSnapshot,
            List<SerializableChatMessage> modifiedMessages,
            Consumer<String> outputCallback) throws Exception {

        outputCallback.accept("🔍 Creating temporary ContextAgent...\n\n");

        // Create temporary agent (note: this won't save checkpoints)
        ContextAgent agent = new ContextAgent(project, langChainService, naiveLlmService);
        agent.setStreamingConsumer(outputCallback);

        // Restore modified memory
        dev.langchain4j.memory.ChatMemory memory = agent.getChatMemory();
        memory.clear();

        outputCallback.accept("📝 Loading modified messages into agent memory...\n");
        for (SerializableChatMessage message : modifiedMessages) {
            try {
                dev.langchain4j.data.message.ChatMessage restored =
                    AgentSnapshotSerializer.restoreChatMessage(message);
                memory.add(restored);
            } catch (Exception e) {
                LOG.warn("Failed to restore message: " + message.getType(), e);
                outputCallback.accept("⚠️ Warning: Failed to restore " + message.getType() + " message\n");
            }
        }

        outputCallback.accept("✅ Memory loaded: " + memory.messages().size() + " messages\n\n");
        outputCallback.accept("─".repeat(60) + "\n");
        outputCallback.accept("🤖 Agent Response:\n\n");

        // For experiment, we just want to see how the agent would respond
        // We'll send a simple "Continue" message to see its next action
        outputCallback.accept("[Sending: Continue with your analysis]\n\n");

        // Get the agent's response with the modified memory
        // Note: This is a simplified version - full context gathering would need more setup
        String experimentPrompt = "Continue with your analysis based on the context above.";

        // Run through the agent (it will use the restored memory)
        // For ContextAgent, we'd normally call gatherContext, but that needs a request object
        // For experiments, we just want to see the agent's next response given the memory
        outputCallback.accept("⚠️ Full context gathering not available in experiment mode\n");
        outputCallback.accept("   (Would need target file and methods)\n");
        outputCallback.accept("   Memory has been loaded and agent is ready.\n");
        outputCallback.accept("   Total messages in memory: " + memory.messages().size() + "\n");
    }

    private void runCoordinatorAgentExperiment(
            AgentSnapshot originalSnapshot,
            List<SerializableChatMessage> modifiedMessages,
            Consumer<String> outputCallback) throws Exception {

        outputCallback.accept("📋 Creating temporary CoordinatorAgent...\n\n");

        // Create temporary agent (needs contextTools, pass null for experiment)
        CoordinatorAgent agent = new CoordinatorAgent(project, langChainService, naiveLlmService, null);
        agent.setStreamingConsumer(outputCallback);

        // Restore modified memory
        dev.langchain4j.memory.ChatMemory memory = agent.getChatMemory();
        memory.clear();

        outputCallback.accept("📝 Loading modified messages into agent memory...\n");
        for (SerializableChatMessage message : modifiedMessages) {
            try {
                dev.langchain4j.data.message.ChatMessage restored =
                    AgentSnapshotSerializer.restoreChatMessage(message);
                memory.add(restored);
            } catch (Exception e) {
                LOG.warn("Failed to restore message: " + message.getType(), e);
                outputCallback.accept("⚠️ Warning: Failed to restore " + message.getType() + " message\n");
            }
        }

        outputCallback.accept("✅ Memory loaded: " + memory.messages().size() + " messages\n\n");
        outputCallback.accept("─".repeat(60) + "\n");

        // Similar limitation as ContextAgent
        outputCallback.accept("⚠️ Full test planning not available in experiment mode\n");
        outputCallback.accept("   (Would need context data and target methods)\n");
        outputCallback.accept("   Memory has been loaded and agent is ready.\n");
        outputCallback.accept("   Total messages in memory: " + memory.messages().size() + "\n");
    }
}
