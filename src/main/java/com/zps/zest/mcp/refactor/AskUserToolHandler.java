package com.zps.zest.mcp.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.zps.zest.testgen.model.UserQuestion;
import com.zps.zest.testgen.ui.dialogs.UserQuestionDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for AskUser MCP tool.
 * Allows external LLMs to ask users questions via IntelliJ dialogs.
 */
public class AskUserToolHandler {
    private static final Gson gson = new Gson();

    /**
     * Ask user a question via IntelliJ dialog.
     *
     * @param project The IntelliJ project
     * @param questionText The question to ask
     * @param questionType Type: SINGLE_CHOICE, MULTI_CHOICE, FREE_TEXT
     * @param options List of option objects {label, description}
     * @param header Optional header text (defaults to "Question")
     * @return User's answer(s) as JSON
     */
    public static JsonObject askUser(
            Project project,
            String questionText,
            String questionType,
            List<Map<String, String>> options,
            String header
    ) {
        // Build UserQuestion model
        UserQuestion question = new UserQuestion(
                header != null ? header : "Question",
                questionText,
                parseQuestionType(questionType)
        );

        // Add options
        if (options != null && !options.isEmpty()) {
            for (Map<String, String> opt : options) {
                String label = opt.get("label");
                String description = opt.getOrDefault("description", "");
                question.addOption(new UserQuestion.QuestionOption(label, description));
            }
        }

        // Show dialog on EDT and wait for result
        CompletableFuture<UserQuestion> future = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            UserQuestion answered = UserQuestionDialog.showAndGetAnswer(project, question);
            future.complete(answered);
        });

        try {
            UserQuestion answered = future.join();

            if (answered == null) {
                // User cancelled
                JsonObject result = new JsonObject();
                result.addProperty("cancelled", true);
                return result;
            }

            // Build response
            JsonObject result = new JsonObject();
            result.addProperty("cancelled", false);
            result.addProperty("questionType", questionType);

            switch (answered.getType()) {
                case SINGLE_CHOICE:
                case MULTI_CHOICE:
                    List<String> selected = answered.getSelectedOptions();
                    result.add("selectedOptions", gson.toJsonTree(selected));
                    break;
                case FREE_TEXT:
                    result.addProperty("freeTextAnswer", answered.getFreeTextAnswer());
                    break;
            }

            return result;

        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Failed to show dialog: " + e.getMessage());
            return error;
        }
    }

    private static UserQuestion.QuestionType parseQuestionType(String type) {
        if (type == null) {
            return UserQuestion.QuestionType.FREE_TEXT;
        }

        switch (type.toUpperCase()) {
            case "SINGLE_CHOICE":
                return UserQuestion.QuestionType.SINGLE_CHOICE;
            case "MULTI_CHOICE":
                return UserQuestion.QuestionType.MULTI_CHOICE;
            case "FREE_TEXT":
            default:
                return UserQuestion.QuestionType.FREE_TEXT;
        }
    }
}
