package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autonomous Code Exploration Agent that can call the LLM to ask and answer
 * its own questions, progressively building understanding of the codebase.
 */
@Service(Service.Level.PROJECT)
public final class AutonomousCodeExplorationAgent {

    private static final Logger LOG = Logger.getInstance(AutonomousCodeExplorationAgent.class);

    private final Project project;
    private final QueryAugmentationAgent augmentationAgent;
    private final LLMService llmService;

    // Exploration configuration
    private static final int MAX_EXPLORATION_DEPTH = 5;
    private static final int MAX_QUESTIONS_PER_TOPIC = 3;

    // Pattern for extracting questions from LLM responses
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "(?:Question:|Q:|\\?|What|How|Why|Where|When|Should|Could|Would|Can|Is|Are|Do|Does)" +
                    "([^.!?]+[?])", Pattern.CASE_INSENSITIVE
    );

    public AutonomousCodeExplorationAgent(@NotNull Project project) {
        this.project = project;
        this.augmentationAgent = project.getService(QueryAugmentationAgent.class);
        this.llmService = project.getService(LLMService.class);
        LOG.info("Initialized AutonomousCodeExplorationAgent");
    }

    /**
     * Starts an autonomous exploration session based on an initial query.
     * The agent will ask itself questions and explore the codebase autonomously.
     */
    @Tool("Start autonomous code exploration")
    public String startAutonomousExploration(String initialQuery) {
        try {
            LOG.info("Starting autonomous exploration for: " + initialQuery);

            ExplorationSession session = new ExplorationSession(initialQuery);
            StringBuilder explorationLog = new StringBuilder();

            explorationLog.append("# Autonomous Code Exploration Session\n\n");
            explorationLog.append("## Initial Query: ").append(initialQuery).append("\n\n");

            // Initial analysis
            String initialAnalysis = performInitialAnalysis(initialQuery, session);
            explorationLog.append("## Initial Analysis\n").append(initialAnalysis).append("\n\n");

            // Autonomous exploration loop
            int depth = 0;
            while (depth < MAX_EXPLORATION_DEPTH && session.hasUnansweredQuestions()) {
                depth++;
                explorationLog.append("## Exploration Round ").append(depth).append("\n\n");

                // Get next question to explore
                String nextQuestion = session.getNextQuestion();
                if (nextQuestion == null) break;

                explorationLog.append("### Question: ").append(nextQuestion).append("\n\n");

                // Explore the question
                ExplorationResult result = exploreQuestion(nextQuestion, session);
                explorationLog.append("#### Answer:\n").append(result.answer).append("\n\n");

                // Extract and add new questions
                if (!result.newQuestions.isEmpty()) {
                    explorationLog.append("#### New Questions Discovered:\n");
                    for (String q : result.newQuestions) {
                        explorationLog.append("- ").append(q).append("\n");
                        session.addQuestion(q);
                    }
                    explorationLog.append("\n");
                }

                // Add insights
                if (!result.insights.isEmpty()) {
                    explorationLog.append("#### Insights:\n");
                    for (String insight : result.insights) {
                        explorationLog.append("- ").append(insight).append("\n");
                    }
                    explorationLog.append("\n");
                }

                // Mark question as answered
                session.markQuestionAnswered(nextQuestion);
            }

            // Generate final summary
            String summary = generateExplorationSummary(session);
            explorationLog.append("## Exploration Summary\n").append(summary);

            return explorationLog.toString();

        } catch (Exception e) {
            LOG.error("Error during autonomous exploration", e);
            return "Error during exploration: " + e.getMessage();
        }
    }

    /**
     * Performs initial analysis of the query to generate starting questions.
     */
    private String performInitialAnalysis(String query, ExplorationSession session) {
        try {
            // IMPORTANT: First get augmented context (includes embedding search)
            // This must complete before any LLM calls
            String augmentedContext = augmentationAgent.augmentQuery(query);

            // Now that embedding search is complete, we can safely call LLM
            // Create prompt for initial analysis
            String analysisPrompt = buildInitialAnalysisPrompt(query, augmentedContext);

            // Call LLM for initial analysis
            String response = llmService.query(analysisPrompt);
            
            if (response == null) {
                return "Failed to get response from LLM";
            }

            // Extract questions from the response
            List<String> questions = extractQuestions(response);
            for (String question : questions) {
                session.addQuestion(question);
            }

            // Extract key topics
            session.addTopics(extractTopics(response));

            return response;

        } catch (Exception e) {
            LOG.error("Error in initial analysis", e);
            return "Failed to perform initial analysis: " + e.getMessage();
        }
    }

    /**
     * Explores a specific question autonomously.
     */
    private ExplorationResult exploreQuestion(String question, ExplorationSession session) {
        ExplorationResult result = new ExplorationResult();

        try {
            // IMPORTANT: First get augmented context (includes embedding search)
            // This must complete before any LLM calls
            String augmentedContext = augmentationAgent.augmentQuery(question);

            // Now that embedding search is complete, we can safely call LLM
            // Build exploration prompt
            String explorationPrompt = buildExplorationPrompt(question, augmentedContext, session);

            // Call LLM
            String response = llmService.query(explorationPrompt);
            
            if (response == null) {
                result.answer = "Failed to get response from LLM";
                return result;
            }
            
            result.answer = response;

            // Extract new questions (limit to avoid infinite exploration)
            List<String> newQuestions = extractQuestions(response);
            for (String q : newQuestions) {
                if (!session.hasAskedQuestion(q) && result.newQuestions.size() < MAX_QUESTIONS_PER_TOPIC) {
                    result.newQuestions.add(q);
                }
            }

            // Extract insights
            result.insights = extractInsights(response);

            // Update session knowledge
            session.addKnowledge(question, response);

        } catch (Exception e) {
            LOG.error("Error exploring question: " + question, e);
            result.answer = "Failed to explore: " + e.getMessage();
        }

        return result;
    }

    /**
     * Builds the initial analysis prompt.
     */
    private String buildInitialAnalysisPrompt(String query, String augmentedContext) {
        return String.format("""
                You are an autonomous code exploration agent. Analyze this initial request and generate questions to explore the codebase.
                
                User Query: %s
                
                Code Context:
                %s
                
                Please:
                1. Identify the main topics and components involved
                2. Generate 3-5 specific questions that would help understand the code better
                3. Prioritize questions that reveal architecture, patterns, and relationships
                4. Format questions clearly with "Question:" prefix
                
                Focus on questions that will help build a comprehensive understanding of the requested area.
                """, query, augmentedContext);
    }

    /**
     * Builds the exploration prompt for a specific question.
     */
    private String buildExplorationPrompt(String question, String augmentedContext, ExplorationSession session) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are exploring a codebase autonomously. Answer this question based on the provided context.\n\n");
        prompt.append("Current Question: ").append(question).append("\n\n");

        // Add relevant prior knowledge
        String priorKnowledge = session.getRelevantKnowledge(question);
        if (!priorKnowledge.isEmpty()) {
            prompt.append("Prior Knowledge:\n").append(priorKnowledge).append("\n\n");
        }

        prompt.append("Code Context:\n").append(augmentedContext).append("\n\n");

        prompt.append("""
                Please:
                1. Answer the question thoroughly based on the code context
                2. Identify any follow-up questions that would deepen understanding (prefix with "Question:")
                3. Note any important insights or patterns discovered (prefix with "Insight:")
                4. Reference specific code elements when possible
                
                Be concise but comprehensive. Focus on building actionable knowledge.
                """);

        return prompt.toString();
    }

    /**
     * Generates a final summary of the exploration session.
     */
    private String generateExplorationSummary(ExplorationSession session) {
        try {
            String summaryPrompt = String.format("""
                            Summarize this autonomous code exploration session:
                            
                            Initial Query: %s
                            
                            Topics Explored: %s
                            
                            Questions Asked: %d
                            Questions Answered: %d
                            
                            Key Knowledge Discovered:
                            %s
                            
                            Please provide:
                            1. A concise summary of what was learned
                            2. Key architectural insights
                            3. Important patterns or conventions discovered
                            4. Recommendations for further exploration
                            5. Any potential issues or concerns identified
                            """,
                    session.getInitialQuery(),
                    String.join(", ", session.getTopics()),
                    session.getTotalQuestions(),
                    session.getAnsweredQuestions(),
                    session.getKnowledgeSummary()
            );

            String response = llmService.query(summaryPrompt);
            return response != null ? response : "Failed to generate summary";

        } catch (Exception e) {
            LOG.error("Error generating summary", e);
            return "Failed to generate summary: " + e.getMessage();
        }
    }

    /**
     * Extracts questions from LLM response.
     */
    private List<String> extractQuestions(String response) {
        List<String> questions = new ArrayList<>();

        // Look for explicit questions
        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.trim().toLowerCase().startsWith("question:")) {
                questions.add(line.substring(line.indexOf(":") + 1).trim());
            }
        }

        // Also find questions using pattern matching
        Matcher matcher = QUESTION_PATTERN.matcher(response);
        while (matcher.find() && questions.size() < 10) {
            String question = matcher.group(0).trim();
            if (!questions.contains(question) && question.length() > 10) {
                questions.add(question);
            }
        }

        return questions;
    }

    /**
     * Extracts insights from LLM response.
     */
    private List<String> extractInsights(String response) {
        List<String> insights = new ArrayList<>();

        String[] lines = response.split("\n");
        for (String line : lines) {
            if (line.trim().toLowerCase().startsWith("insight:")) {
                insights.add(line.substring(line.indexOf(":") + 1).trim());
            }
        }

        return insights;
    }

    /**
     * Extracts topics from the response.
     */
    private List<String> extractTopics(String response) {
        List<String> topics = new ArrayList<>();

        // Simple extraction based on common patterns
        Pattern topicPattern = Pattern.compile("(?:topic|component|module|layer|pattern):\\s*([^,\n]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = topicPattern.matcher(response);

        while (matcher.find()) {
            topics.add(matcher.group(1).trim());
        }

        return topics;
    }

    /**
     * Container for exploration results.
     */
    private static class ExplorationResult {
        String answer = "";
        List<String> newQuestions = new ArrayList<>();
        List<String> insights = new ArrayList<>();
    }

    /**
     * Manages the exploration session state.
     */
    private static class ExplorationSession {
        private final String initialQuery;
        private final Queue<String> unansweredQuestions = new LinkedList<>();
        private final Set<String> askedQuestions = new HashSet<>();
        private final Map<String, String> knowledge = new HashMap<>();
        private final List<String> topics = new ArrayList<>();

        public ExplorationSession(String initialQuery) {
            this.initialQuery = initialQuery;
        }

        public void addQuestion(String question) {
            if (!askedQuestions.contains(question)) {
                unansweredQuestions.offer(question);
                askedQuestions.add(question);
            }
        }

        public String getNextQuestion() {
            return unansweredQuestions.poll();
        }

        public boolean hasUnansweredQuestions() {
            return !unansweredQuestions.isEmpty();
        }

        public boolean hasAskedQuestion(String question) {
            return askedQuestions.contains(question);
        }

        public void markQuestionAnswered(String question) {
            // Already removed from queue when getNextQuestion was called
        }

        public void addKnowledge(String question, String answer) {
            knowledge.put(question, answer);
        }

        public String getRelevantKnowledge(String question) {
            // Simple relevance: return knowledge from questions with overlapping keywords
            StringBuilder relevant = new StringBuilder();
            String[] keywords = question.toLowerCase().split("\\s+");

            for (Map.Entry<String, String> entry : knowledge.entrySet()) {
                String prevQuestion = entry.getKey().toLowerCase();
                for (String keyword : keywords) {
                    if (keyword.length() > 3 && prevQuestion.contains(keyword)) {
                        relevant.append("From '").append(entry.getKey()).append("':\n");
                        relevant.append(entry.getValue().substring(0, Math.min(200, entry.getValue().length())));
                        relevant.append("...\n\n");
                        break;
                    }
                }
            }

            return relevant.toString();
        }

        public void addTopics(List<String> newTopics) {
            topics.addAll(newTopics);
        }

        public String getInitialQuery() {
            return initialQuery;
        }

        public List<String> getTopics() {
            return topics;
        }

        public int getTotalQuestions() {
            return askedQuestions.size();
        }

        public int getAnsweredQuestions() {
            return knowledge.size();
        }

        public String getKnowledgeSummary() {
            StringBuilder summary = new StringBuilder();
            int count = 0;
            for (Map.Entry<String, String> entry : knowledge.entrySet()) {
                if (count++ > 5) break; // Limit to avoid huge prompts
                summary.append("Q: ").append(entry.getKey()).append("\n");
                summary.append("A: ").append(entry.getValue().substring(0,
                        Math.min(150, entry.getValue().length()))).append("...\n\n");
            }
            return summary.toString();
        }
    }
}
