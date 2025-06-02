package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Code Exploration Agent that maintains conversation context and helps users
 * explore their codebase through reflective questions and progressive discovery.
 */
@Service(Service.Level.PROJECT)
public final class CodeExplorationAgent {
    
    private static final Logger LOG = Logger.getInstance(CodeExplorationAgent.class);
    
    private final Project project;
    private final QueryAugmentationAgent augmentationAgent;
    
    // Conversation state management
    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();
    
    // Exploration strategies
    private enum ExplorationStrategy {
        BREADTH_FIRST("Explore different types of components"),
        DEPTH_FIRST("Deep dive into specific component"),
        RELATIONSHIP_FOCUSED("Explore connections between components"),
        PATTERN_BASED("Find similar patterns across codebase"),
        PROBLEM_SOLVING("Debug or fix specific issues")
    }
    
    public CodeExplorationAgent(@NotNull Project project) {
        this.project = project;
        this.augmentationAgent = project.getService(QueryAugmentationAgent.class);
        LOG.info("Initialized CodeExplorationAgent");
    }
    
    /**
     * Starts or continues a code exploration conversation.
     */
    @Tool("Start or continue code exploration conversation")
    public String explore(String userInput, String conversationId) {
        ConversationState state = conversations.computeIfAbsent(
            conversationId, 
            k -> new ConversationState()
        );
        
        // Update conversation history
        state.addUserInput(userInput);
        
        // Determine exploration strategy
        ExplorationStrategy strategy = determineStrategy(userInput, state);
        state.setCurrentStrategy(strategy);
        
        // Generate response based on strategy
        StringBuilder response = new StringBuilder();
        
        // Add exploration header
        response.append("### Code Exploration Assistant ###\n");
        response.append("Strategy: ").append(strategy.description).append("\n\n");
        
        // Analyze the input
        String ambiguityAnalysis = augmentationAgent.analyzeQueryAmbiguity(userInput);
        if (ambiguityAnalysis.contains("ambiguous")) {
            response.append("### Clarification Needed ###\n");
            response.append(ambiguityAnalysis).append("\n\n");
        }
        
        // Get augmented context
        String augmentedContext = augmentationAgent.augmentQuery(userInput);
        response.append(augmentedContext);
        
        // Generate exploration path
        List<String> explorationPath = generateExplorationPath(strategy, state);
        if (!explorationPath.isEmpty()) {
            response.append("\n### Suggested Exploration Path ###\n");
            for (int i = 0; i < explorationPath.size(); i++) {
                response.append((i + 1)).append(". ").append(explorationPath.get(i)).append("\n");
            }
        }
        
        // Generate follow-up questions based on context
        List<String> followUpQuestions = generateContextualFollowUps(userInput, state);
        if (!followUpQuestions.isEmpty()) {
            response.append("\n### Next Steps ###\n");
            response.append("Based on our exploration so far, you might want to:\n");
            for (String question : followUpQuestions) {
                response.append("- ").append(question).append("\n");
            }
        }
        
        // Update state
        state.addAgentResponse(response.toString());
        
        return response.toString();
    }
    
    /**
     * Determines the best exploration strategy based on user input and conversation history.
     */
    private ExplorationStrategy determineStrategy(String userInput, ConversationState state) {
        String lowerInput = userInput.toLowerCase();
        
        // Check for explicit strategy indicators
        if (lowerInput.contains("everything") || lowerInput.contains("all") || 
            lowerInput.contains("overview") || lowerInput.contains("architecture")) {
            return ExplorationStrategy.BREADTH_FIRST;
        }
        
        if (lowerInput.contains("deep") || lowerInput.contains("detail") || 
            lowerInput.contains("specific") || lowerInput.contains("exactly")) {
            return ExplorationStrategy.DEPTH_FIRST;
        }
        
        if (lowerInput.contains("connect") || lowerInput.contains("relation") || 
            lowerInput.contains("depend") || lowerInput.contains("use") || 
            lowerInput.contains("call")) {
            return ExplorationStrategy.RELATIONSHIP_FOCUSED;
        }
        
        if (lowerInput.contains("similar") || lowerInput.contains("pattern") || 
            lowerInput.contains("like") || lowerInput.contains("example")) {
            return ExplorationStrategy.PATTERN_BASED;
        }
        
        if (lowerInput.contains("fix") || lowerInput.contains("error") || 
            lowerInput.contains("problem") || lowerInput.contains("issue") || 
            lowerInput.contains("debug")) {
            return ExplorationStrategy.PROBLEM_SOLVING;
        }
        
        // Default based on conversation context
        if (state.getExplorationDepth() > 3) {
            return ExplorationStrategy.BREADTH_FIRST; // Switch to breadth after going deep
        }
        
        return ExplorationStrategy.DEPTH_FIRST; // Default to focused exploration
    }
    
    /**
     * Generates an exploration path based on the strategy.
     */
    private List<String> generateExplorationPath(ExplorationStrategy strategy, ConversationState state) {
        List<String> path = new ArrayList<>();
        
        switch (strategy) {
            case BREADTH_FIRST:
                path.add("Identify main component types in your project");
                path.add("Map high-level relationships between layers");
                path.add("Find entry points (controllers, main classes)");
                path.add("Explore configuration and setup");
                break;
                
            case DEPTH_FIRST:
                path.add("Select a specific component to examine");
                path.add("Understand its public interface");
                path.add("Trace through its implementation");
                path.add("Find and analyze its dependencies");
                path.add("Locate its tests and usage examples");
                break;
                
            case RELATIONSHIP_FOCUSED:
                path.add("Map direct dependencies");
                path.add("Identify inverse dependencies (who uses this)");
                path.add("Trace data flow through the system");
                path.add("Find integration points");
                break;
                
            case PATTERN_BASED:
                path.add("Identify the pattern in current component");
                path.add("Search for similar patterns");
                path.add("Compare implementations");
                path.add("Extract common abstractions");
                break;
                
            case PROBLEM_SOLVING:
                path.add("Reproduce and understand the issue");
                path.add("Locate the problematic code");
                path.add("Analyze recent changes");
                path.add("Check related tests");
                path.add("Identify potential fixes");
                break;
        }
        
        // Filter based on what's already been explored
        return path.stream()
            .filter(step -> !state.hasExploredTopic(step))
            .limit(3)
            .toList();
    }
    
    /**
     * Generates contextual follow-up questions based on the conversation.
     */
    private List<String> generateContextualFollowUps(String userInput, ConversationState state) {
        List<String> followUps = new ArrayList<>();
        
        // Get components mentioned in conversation
        Set<String> mentionedComponents = state.getMentionedComponents();
        
        // Strategy-specific follow-ups
        switch (state.getCurrentStrategy()) {
            case BREADTH_FIRST:
                followUps.add("Would you like to zoom in on any specific layer?");
                followUps.add("Should we explore the data flow between these components?");
                followUps.add("Are there any architectural patterns you'd like to understand better?");
                break;
                
            case DEPTH_FIRST:
                if (!mentionedComponents.isEmpty()) {
                    String component = mentionedComponents.iterator().next();
                    followUps.add("Should we look at the tests for " + component + "?");
                    followUps.add("Would you like to see what calls " + component + "?");
                    followUps.add("Should we explore the error handling in " + component + "?");
                }
                break;
                
            case RELATIONSHIP_FOCUSED:
                followUps.add("Would you like to visualize these relationships?");
                followUps.add("Should we trace a specific data flow path?");
                followUps.add("Are there any circular dependencies we should check for?");
                break;
                
            case PATTERN_BASED:
                followUps.add("Should we create a template based on these patterns?");
                followUps.add("Would you like to refactor to a common abstraction?");
                followUps.add("Are there any anti-patterns we should look for?");
                break;
                
            case PROBLEM_SOLVING:
                followUps.add("Have you checked the logs for error messages?");
                followUps.add("Should we look at recent commits to this area?");
                followUps.add("Would you like to add debug logging to trace the issue?");
                break;
        }
        
        return followUps.stream().limit(3).toList();
    }
    
    /**
     * Resets the conversation state for a given ID.
     */
    @Tool("Reset exploration conversation")
    public String resetConversation(String conversationId) {
        conversations.remove(conversationId);
        return "Conversation reset. Ready to start fresh exploration.";
    }
    
    /**
     * Gets the current exploration summary.
     */
    @Tool("Get exploration summary")
    public String getExplorationSummary(String conversationId) {
        ConversationState state = conversations.get(conversationId);
        if (state == null) {
            return "No active exploration session.";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("### Exploration Summary ###\n");
        summary.append("Current Strategy: ").append(state.getCurrentStrategy()).append("\n");
        summary.append("Exploration Depth: ").append(state.getExplorationDepth()).append("\n");
        summary.append("Topics Explored: ").append(state.getExploredTopics().size()).append("\n");
        summary.append("Components Mentioned: ").append(
            String.join(", ", state.getMentionedComponents())
        ).append("\n");
        
        return summary.toString();
    }
    
    /**
     * Conversation state tracking.
     */
    private static class ConversationState {
        private final List<String> userInputs = new ArrayList<>();
        private final List<String> agentResponses = new ArrayList<>();
        private final Set<String> exploredTopics = new HashSet<>();
        private final Set<String> mentionedComponents = new HashSet<>();
        private ExplorationStrategy currentStrategy = ExplorationStrategy.DEPTH_FIRST;
        private int explorationDepth = 0;
        
        public void addUserInput(String input) {
            userInputs.add(input);
            extractComponentNames(input);
        }
        
        public void addAgentResponse(String response) {
            agentResponses.add(response);
            explorationDepth++;
        }
        
        public void setCurrentStrategy(ExplorationStrategy strategy) {
            this.currentStrategy = strategy;
        }
        
        public ExplorationStrategy getCurrentStrategy() {
            return currentStrategy;
        }
        
        public int getExplorationDepth() {
            return explorationDepth;
        }
        
        public Set<String> getExploredTopics() {
            return exploredTopics;
        }
        
        public Set<String> getMentionedComponents() {
            return mentionedComponents;
        }
        
        public boolean hasExploredTopic(String topic) {
            return exploredTopics.contains(topic.toLowerCase());
        }
        
        public void markTopicExplored(String topic) {
            exploredTopics.add(topic.toLowerCase());
        }
        
        private void extractComponentNames(String input) {
            // Simple extraction of PascalCase words
            String[] words = input.split("\\s+");
            for (String word : words) {
                if (word.matches("[A-Z][a-zA-Z0-9]*") && word.length() > 3) {
                    mentionedComponents.add(word);
                }
            }
        }
    }
}
