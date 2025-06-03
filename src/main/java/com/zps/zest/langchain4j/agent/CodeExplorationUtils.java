package com.zps.zest.langchain4j.agent;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.zps.zest.ClassAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for code exploration and analysis.
 */
public class CodeExplorationUtils {
    private static final Logger LOG = Logger.getInstance(CodeExplorationUtils.class);
    
    // Pattern for extracting questions from LLM responses
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "(?:Question:|Q:|\\?|What|How|Why|Where|When|Should|Could|Would|Can|Is|Are|Do|Does)" +
                    "([^.!?]+[?])", Pattern.CASE_INSENSITIVE
    );
    
    /**
     * Extracts questions from LLM response.
     */
    public static List<String> extractQuestions(String response) {
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
    public static List<String> extractInsights(String response) {
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
    public static List<String> extractTopics(String response) {
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
     * Extracts code references (class names, method names) from text.
     */
    public static Set<String> extractCodeReferences(String text) {
        Set<String> references = new HashSet<>();
        
        // Pattern to match Java class/method references
        // Matches patterns like: ClassName#methodName, package.ClassName, ClassName.methodName
        Pattern classMethodPattern = Pattern.compile(
            "\\b([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*(?:#|\\.))[a-zA-Z_][a-zA-Z0-9_]*\\b|" +
            "\\b[A-Z][a-zA-Z0-9_]*(?:\\.[A-Z][a-zA-Z0-9_]*)*\\b"
        );
        
        Matcher matcher = classMethodPattern.matcher(text);
        while (matcher.find()) {
            String ref = matcher.group();
            // Filter out common words that might match pattern but aren't code
            if (!isCommonWord(ref) && (ref.contains(".") || ref.contains("#"))) {
                references.add(ref);
            }
        }
        
        return references;
    }

    /**
     * Checks if a string is a common word that shouldn't be treated as a code reference.
     */
    private static boolean isCommonWord(String word) {
        Set<String> commonWords = Set.of(
            "Question", "Answer", "From", "The", "This", "That",
            "What", "How", "Why", "Where", "When", "Should", "Could",
            "Would", "Can", "Is", "Are", "Do", "Does"
        );
        return commonWords.contains(word);
    }

    /**
     * Gets the full code for a given element reference using PSI.
     */
    public static String getFullCodeForElement(@NotNull Project project, String elementRef) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // Handle different reference formats
                // Format 1: ClassName#methodName
                // Format 2: com.package.ClassName
                // Format 3: ClassName.methodName
                
                String className = null;
                String methodName = null;
                
                if (elementRef.contains("#")) {
                    String[] parts = elementRef.split("#");
                    className = parts[0];
                    if (parts.length > 1) {
                        methodName = parts[1].replaceAll("\\(.*\\)", ""); // Remove parameters
                    }
                } else if (elementRef.contains("(")) {
                    // Method with parameters
                    int parenIndex = elementRef.indexOf("(");
                    String beforeParen = elementRef.substring(0, parenIndex);
                    int lastDot = beforeParen.lastIndexOf(".");
                    if (lastDot > 0) {
                        className = beforeParen.substring(0, lastDot);
                        methodName = beforeParen.substring(lastDot + 1);
                    }
                } else if (elementRef.matches(".*\\.[a-z][a-zA-Z0-9_]*$")) {
                    // Likely a method reference (lowercase after last dot)
                    int lastDot = elementRef.lastIndexOf(".");
                    className = elementRef.substring(0, lastDot);
                    methodName = elementRef.substring(lastDot + 1);
                } else {
                    // Assume it's a class name
                    className = elementRef;
                }
                
                // Find the class
                PsiClass psiClass = findClassByName(project, className);
                if (psiClass == null) {
                    return null;
                }
                
                // If method name specified, find and return the method
                if (methodName != null) {
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (method.getName().equals(methodName)) {
                            return ClassAnalyzer.getTextOfPsiElement(method);
                        }
                    }
                }
                
                // Return class structure if no method specified or method not found
                StringBuilder classStructure = new StringBuilder();
                ClassAnalyzer.appendClassStructure(classStructure, psiClass);
                return classStructure.toString();
                
            } catch (Exception e) {
                LOG.warn("Failed to get code for element: " + elementRef, e);
                return null;
            }
        });
    }

    /**
     * Finds a class by name (simple or qualified).
     */
    @Nullable
    public static PsiClass findClassByName(@NotNull Project project, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        
        // Try as qualified name first
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass psiClass = facade.findClass(name, GlobalSearchScope.projectScope(project));
        
        if (psiClass == null && !name.contains(".")) {
            // Try as simple name
            PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
            PsiClass[] classes = cache.getClassesByName(name, GlobalSearchScope.projectScope(project));
            if (classes.length > 0) {
                psiClass = classes[0]; // Take the first match
            }
        }
        
        return psiClass;
    }

    /**
     * Collects the most relevant code pieces for given references.
     */
    public static String collectRelevantCodePieces(@NotNull Project project, Set<String> codeReferences, int maxPieces) {
        StringBuilder codePieces = new StringBuilder();
        
        try {
            int count = 0;
            for (String element : codeReferences) {
                if (count >= maxPieces) break;
                
                String fullCode = getFullCodeForElement(project, element);
                if (fullCode != null && !fullCode.isEmpty()) {
                    codePieces.append("\n\n### ").append(element).append("\n");
                    codePieces.append("```java\n").append(fullCode).append("\n```");
                    count++;
                }
            }
            
            if (codePieces.length() == 0) {
                codePieces.append("No specific code pieces were found. The exploration focused on understanding the following elements:\n");
                count = 0;
                for (String element : codeReferences) {
                    codePieces.append("- ").append(element).append("\n");
                    if (++count >= maxPieces) break;
                }
            }
            
        } catch (Exception e) {
            LOG.error("Error collecting code pieces", e);
            codePieces.append("Error retrieving code pieces: ").append(e.getMessage());
        }
        
        return codePieces.toString();
    }

    /**
     * Parses structured exploration results from LLM response.
     */
    public static ExplorationParseResult parseExplorationResponse(String response) {
        ExplorationParseResult result = new ExplorationParseResult();
        
        String[] sections = response.split("##");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            
            if (trimmed.startsWith("MAIN_ANSWER:")) {
                result.mainAnswer = trimmed.substring("MAIN_ANSWER:".length()).trim();
            } else if (trimmed.startsWith("CODE_REFERENCES:")) {
                String refsSection = trimmed.substring("CODE_REFERENCES:".length()).trim();
                String[] refs = refsSection.split("\n");
                for (String ref : refs) {
                    ref = ref.trim();
                    if (ref.startsWith("- ")) {
                        result.codeReferences.add(ref.substring(2).trim());
                    }
                }
            } else if (trimmed.startsWith("FOLLOW_UP_QUESTIONS:")) {
                String questionsSection = trimmed.substring("FOLLOW_UP_QUESTIONS:".length()).trim();
                String[] questions = questionsSection.split("\n");
                for (String q : questions) {
                    q = q.trim();
                    if (q.startsWith("- ")) {
                        result.followUpQuestions.add(q.substring(2).trim());
                    }
                }
            } else if (trimmed.startsWith("KEY_INSIGHTS:")) {
                String insightsSection = trimmed.substring("KEY_INSIGHTS:".length()).trim();
                String[] insights = insightsSection.split("\n");
                for (String insight : insights) {
                    insight = insight.trim();
                    if (insight.startsWith("- ")) {
                        result.keyInsights.add(insight.substring(2).trim());
                    }
                }
            }
        }
        
        return result;
    }

    /**
     * Parses structured summary response from LLM.
     */
    public static SummaryParseResult parseSummaryResponse(String response) {
        SummaryParseResult result = new SummaryParseResult();
        
        String[] sections = response.split("##");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            
            if (trimmed.startsWith("EXECUTIVE_SUMMARY:")) {
                result.executiveSummary = trimmed.substring("EXECUTIVE_SUMMARY:".length()).trim();
            } else if (trimmed.startsWith("KEY_CODE_ELEMENTS:")) {
                parseListSection(trimmed.substring("KEY_CODE_ELEMENTS:".length()).trim(), result.keyCodeElements);
            } else if (trimmed.startsWith("ARCHITECTURAL_INSIGHTS:")) {
                parseListSection(trimmed.substring("ARCHITECTURAL_INSIGHTS:".length()).trim(), result.architecturalInsights);
            } else if (trimmed.startsWith("IMPLEMENTATION_DETAILS:")) {
                parseListSection(trimmed.substring("IMPLEMENTATION_DETAILS:".length()).trim(), result.implementationDetails);
            } else if (trimmed.startsWith("NEXT_STEPS:")) {
                parseListSection(trimmed.substring("NEXT_STEPS:".length()).trim(), result.nextSteps);
            }
        }
        
        return result;
    }
    
    private static void parseListSection(String section, List<String> targetList) {
        String[] items = section.split("\n");
        for (String item : items) {
            item = item.trim();
            if (item.startsWith("- ")) {
                targetList.add(item.substring(2).trim());
            }
        }
    }

    /**
     * Post-processes exploration results to enhance with full code elements.
     * This allows us to add code after LLM processing, not requiring the LLM to handle full code.
     */
    public static String enhanceExplorationResult(String originalResult, @NotNull Project project, Set<String> codeReferences) {
        // If the result doesn't contain code blocks, enhance it
        if (!originalResult.contains("```java")) {
            StringBuilder enhanced = new StringBuilder(originalResult);
            
            // Add a section with relevant code
            enhanced.append("\n\n## Relevant Code Elements\n");
            
            int count = 0;
            for (String codeRef : codeReferences) {
                if (count >= 3) break; // Limit to 3 code pieces in exploration results
                
                String fullCode = getFullCodeForElement(project, codeRef);
                if (fullCode != null && !fullCode.isEmpty()) {
                    enhanced.append("\n### ").append(codeRef).append("\n");
                    enhanced.append("```java\n").append(fullCode).append("\n```\n");
                    count++;
                }
            }
            
            return enhanced.toString();
        }
        
        return originalResult;
    }

    /**
     * Formats the final exploration log with proper structure.
     */
    public static String formatExplorationLog(StringBuilder log, String summary, @NotNull Project project, Set<String> allCodeReferences) {
        // Parse the summary to get structured data
        SummaryParseResult parsed = parseSummaryResponse(summary);
        
        StringBuilder formattedLog = new StringBuilder(log);
        formattedLog.append("\n## Exploration Summary\n\n");
        
        // Add executive summary
        if (!parsed.executiveSummary.isEmpty()) {
            formattedLog.append("### Executive Summary\n");
            formattedLog.append(parsed.executiveSummary).append("\n\n");
        }
        
        // Add key code elements with actual code
        if (!parsed.keyCodeElements.isEmpty()) {
            formattedLog.append("### Key Code Elements\n");
            for (String element : parsed.keyCodeElements) {
                // Extract just the code reference part (before any description)
                String codeRef = element.split("\\s+\\(")[0].trim();
                formattedLog.append("\n**").append(element).append("**\n");
                
                String fullCode = getFullCodeForElement(project, codeRef);
                if (fullCode != null && !fullCode.isEmpty()) {
                    formattedLog.append("```java\n").append(fullCode).append("\n```\n");
                }
            }
            formattedLog.append("\n");
        }
        
        // Add other sections without code
        if (!parsed.architecturalInsights.isEmpty()) {
            formattedLog.append("### Architectural Insights\n");
            for (String insight : parsed.architecturalInsights) {
                formattedLog.append("- ").append(insight).append("\n");
            }
            formattedLog.append("\n");
        }
        
        if (!parsed.implementationDetails.isEmpty()) {
            formattedLog.append("### Implementation Details\n");
            for (String detail : parsed.implementationDetails) {
                formattedLog.append("- ").append(detail).append("\n");
            }
            formattedLog.append("\n");
        }
        
        if (!parsed.nextSteps.isEmpty()) {
            formattedLog.append("### Next Steps\n");
            for (String step : parsed.nextSteps) {
                formattedLog.append("- ").append(step).append("\n");
            }
            formattedLog.append("\n");
        }
        
        return formattedLog.toString();
    }

    /**
     * Container for parsed exploration results.
     */
    public static class ExplorationParseResult {
        public String mainAnswer = "";
        public List<String> codeReferences = new ArrayList<>();
        public List<String> followUpQuestions = new ArrayList<>();
        public List<String> keyInsights = new ArrayList<>();
    }

    /**
     * Container for parsed summary results.
     */
    public static class SummaryParseResult {
        public String executiveSummary = "";
        public List<String> keyCodeElements = new ArrayList<>();
        public List<String> architecturalInsights = new ArrayList<>();
        public List<String> implementationDetails = new ArrayList<>();
        public List<String> nextSteps = new ArrayList<>();
    }
}
