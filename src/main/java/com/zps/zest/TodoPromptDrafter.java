package com.zps.zest;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.util.lang.ClasspathCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for creating specialized prompts for TODO implementations.
 * This class extracts TODOs from code and creates detailed prompts for the LLM.
 */
public class TodoPromptDrafter {
    // Pattern to match TODOs with optional comments
    private static final Pattern TODO_PATTERN =
            Pattern.compile("(TODO|todo|ToDo)\\s*:?\\s*(.*?)($|\\n)|// (TODO|todo|ToDo)\\s*:?\\s*(.*?)($|\\n)");

    /**
     * Creates a detailed prompt for implementing TODOs in code.
     *
     * @param selectedText The code containing TODOs
     * @param codeContext Additional context about the code
     * @return A structured prompt for the LLM
     */
    public static String createTodoImplementationPrompt(String selectedText, String codeContext, Map<String, String> relatedClassContext) {
        // Extract TODOs from the code
        List<TodoItem> todos = extractTodos(selectedText);

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Implement the TODOs in the following Java code. Replace each TODO with appropriate code.\n\n");

        // Add context for better understanding
        promptBuilder.append("CODE CONTEXT:\n").append(codeContext).append("\n\n");

        // Add related class implementations if available
        if (relatedClassContext != null && !relatedClassContext.isEmpty()) {
            promptBuilder.append("RELATED CLASS IMPLEMENTATIONS:\n");
            for (Map.Entry<String, String> entry : relatedClassContext.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    promptBuilder.append("// Class: ").append(entry.getKey()).append("\n");
                    promptBuilder.append(entry.getValue()).append("\n\n");
                }
            }
        }

        // Add the code with TODOs
        promptBuilder.append("CODE WITH ").append(todos.size()).append(" TODOs TO IMPLEMENT:\n```java\n")
                .append(selectedText).append("\n```\n\n");

        // Add specific details about each TODO
        if (!todos.isEmpty()) {
            promptBuilder.append("TODOS TO IMPLEMENT:\n");
            for (int i = 0; i < todos.size(); i++) {
                TodoItem todo = todos.get(i);
                promptBuilder.append("TODO #").append(i + 1).append(": ")
                        .append(todo.getType()).append(" - ").append(todo.getDescription())
                        .append("\n");
            }
            promptBuilder.append("\n");
        }

        // Add detailed requirements for implementation
        promptBuilder.append("Requirements:\n");
        promptBuilder.append("1. ONLY replace the TODOs with implementation code\n");
        promptBuilder.append("2. Keep the rest of the code exactly the same\n");
        promptBuilder.append("3. Ensure the implementation matches the surrounding context\n");
        promptBuilder.append("4. Use only methods and classes that exist in the provided context\n");
        promptBuilder.append("5. DO NOT invent or assume the existence of methods that are not shown in the context\n");
        promptBuilder.append("6. Add a brief comment before each implementation explaining your approach\n");
        promptBuilder.append("7. Handle potential errors and edge cases appropriately\n");
        promptBuilder.append("8. Return ONLY the complete implemented code without explanations or markdown formatting\n");

        return promptBuilder.toString();
    }
    /**
     * Extracts TODO items from code.
     *
     * @param code The code containing TODOs
     * @return A list of extracted TODO items
     */
    private static List<TodoItem> extractTodos(String code) {
        List<TodoItem> todos = new ArrayList<>();
        Matcher matcher = TODO_PATTERN.matcher(code);

        while (matcher.find()) {
            String todoType = matcher.group(1);
            String todoDesc = matcher.group(2);

            // Check if it's the second form (// TODO format)
            if (todoType == null) {
                todoType = matcher.group(4);
                todoDesc = matcher.group(5);
            }

            // Clean up the description
            if (todoDesc != null) {
                todoDesc = todoDesc.trim();
            } else {
                todoDesc = "";
            }

            todos.add(new TodoItem(todoType, todoDesc));
        }

        return todos;
    }

    /**
     * Helper class to represent a TODO item.
     */
    private static class TodoItem {
        private final String type;
        private final String description;

        public TodoItem(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }
    }
}