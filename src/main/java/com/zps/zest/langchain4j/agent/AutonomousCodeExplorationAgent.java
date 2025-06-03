package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.*;

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
            
            // Format the final log with enhanced code elements
            return CodeExplorationUtils.formatExplorationLog(explorationLog, summary, project, session.getCodeReferences());

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

            // Parse structured response
            CodeExplorationUtils.ExplorationParseResult parsed = CodeExplorationUtils.parseExplorationResponse(response);
            
            // Add questions from parsed response
            for (String question : parsed.followUpQuestions) {
                session.addQuestion(question);
            }
            
            // Add code references
            for (String codeRef : parsed.codeReferences) {
                session.addCodeReference(codeRef);
            }
            
            // Extract topics from insights
            session.addTopics(parsed.keyInsights);

            // Return the main answer
            return parsed.mainAnswer.isEmpty() ? response : parsed.mainAnswer;

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
            
            // Parse structured response
            CodeExplorationUtils.ExplorationParseResult parsed = CodeExplorationUtils.parseExplorationResponse(response);
            
            result.answer = parsed.mainAnswer.isEmpty() ? response : parsed.mainAnswer;
            
            // Add new questions (limit to avoid infinite exploration)
            for (String q : parsed.followUpQuestions) {
                if (!session.hasAskedQuestion(q) && result.newQuestions.size() < MAX_QUESTIONS_PER_TOPIC) {
                    result.newQuestions.add(q);
                }
            }
            
            // Add insights
            result.insights = parsed.keyInsights;
            
            // Add code references to session
            for (String codeRef : parsed.codeReferences) {
                session.addCodeReference(codeRef);
            }

            // Update session knowledge
            session.addKnowledge(question, result.answer);

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
                
                Please provide your analysis in the following structured format:
                
                ## MAIN_ANSWER:
                [Provide a comprehensive understanding of what the user is asking for and the main components involved]
                
                ## CODE_REFERENCES:
                [List specific code elements to explore, one per line with '- ' prefix]
                - ClassName#methodName
                - com.package.ClassName
                - InterfaceName
                
                ## FOLLOW_UP_QUESTIONS:
                [List 3-5 specific questions that would help understand the code better, one per line with '- ' prefix]
                - How does X interact with Y?
                - What patterns are used in Z?
                - Where is A implemented?
                
                ## KEY_INSIGHTS:
                [List any immediate insights or patterns noticed, one per line with '- ' prefix]
                - The code uses pattern X
                - Component Y is responsible for Z
                
                Focus on architectural understanding, patterns, and relationships.
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
                Please provide your analysis in the following structured format:
                
                ## MAIN_ANSWER:
                [Provide a thorough answer to the question based on the code context]
                
                ## CODE_REFERENCES:
                [List specific code elements referenced in your answer, one per line with '- ' prefix]
                - ClassName#methodName
                - com.package.ClassName
                
                ## FOLLOW_UP_QUESTIONS:
                [List any follow-up questions that would deepen understanding, one per line with '- ' prefix]
                - Question about specific implementation?
                - Question about related components?
                
                ## KEY_INSIGHTS:
                [List important patterns or architectural insights discovered, one per line with '- ' prefix]
                - Pattern or convention discovered
                - Important relationship identified
                
                Be concise but comprehensive. Reference specific code elements.
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
                            
                            Code References Discovered: %d unique elements
                            
                            Please provide a summary in the following structured format:
                            
                            ## EXECUTIVE_SUMMARY:
                            [2-3 paragraph summary of the exploration findings and main discoveries]
                            
                            ## KEY_CODE_ELEMENTS:
                            [List the most important code pieces developers should examine, one per line with '- ' prefix]
                            - com.package.ClassName#methodName (brief description)
                            - InterfaceName (what it does)
                            
                            ## ARCHITECTURAL_INSIGHTS:
                            [Key architectural patterns and design decisions discovered, one per line with '- ' prefix]
                            - Pattern or architecture decision
                            - How components interact
                            
                            ## IMPLEMENTATION_DETAILS:
                            [Critical implementation details and dependencies, one per line with '- ' prefix]
                            - Important implementation detail
                            - Key dependency or relationship
                            
                            ## NEXT_STEPS:
                            [Suggested next steps for deeper understanding, one per line with '- ' prefix]
                            - Specific area to explore further
                            - Related components to investigate
                            
                            Focus on providing actionable insights and specific code references.
                            Do NOT include code review comments or quality assessments.
                            """,
                    session.getInitialQuery(),
                    String.join(", ", session.getTopics()),
                    session.getTotalQuestions(),
                    session.getAnsweredQuestions(),
                    session.getKnowledgeSummary(),
                    session.getCodeReferences().size()
            );

            String response = llmService.query(summaryPrompt);
            return response != null ? response : "Failed to generate summary";

        } catch (Exception e) {
            LOG.error("Error generating summary", e);
            return "Failed to generate summary: " + e.getMessage();
        }
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
        private final Set<String> codeReferences = new HashSet<>();

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
            // Extract code references from the answer
            extractAndAddCodeReferences(answer);
        }
        
        public void addCodeReference(String reference) {
            if (reference != null && !reference.isEmpty()) {
                codeReferences.add(reference);
            }
        }

        private void extractAndAddCodeReferences(String text) {
            Set<String> references = CodeExplorationUtils.extractCodeReferences(text);
            codeReferences.addAll(references);
        }

        public Set<String> getCodeReferences() {
            return new HashSet<>(codeReferences);
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
