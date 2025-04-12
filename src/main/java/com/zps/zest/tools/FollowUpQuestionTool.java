package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * A tool that allows the AI to ask follow-up questions to the user.
 * This tool takes precedence over other tools when detected.
 */
public class FollowUpQuestionTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(FollowUpQuestionTool.class);
    private final Project project;

    public FollowUpQuestionTool(Project project) {
        super("follow_up_question", "Asks the user a follow-up question");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String question = getStringParam(params, "question", null);
        
        if (question == null || question.isEmpty()) {
            return "Error: 'question' parameter is required";
        }
        
        // Return a special format that will be recognized by the tool handler
        // to show this as a follow-up question to the user
        return "### FOLLOW_UP_QUESTION\n" + question + "\n### END_FOLLOW_UP_QUESTION";
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("question", "What specific part of the code would you like me to focus on?");
        return params;
    }
}