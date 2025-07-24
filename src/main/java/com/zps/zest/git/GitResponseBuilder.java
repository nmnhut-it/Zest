package com.zps.zest.git;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Builder class for creating consistent JSON responses in Git operations.
 * This eliminates duplication of response creation logic.
 */
public class GitResponseBuilder {
    private static final Gson gson = new Gson();
    
    /**
     * Creates a standard commit operation started response.
     */
    public static String commitOperationStarted() {
        JsonObject response = GitServiceHelper.createSuccessResponse("Commit operation started");
        return GitServiceHelper.toJson(response);
    }
    
    /**
     * Creates a standard push operation started response.
     */
    public static String pushOperationStarted() {
        JsonObject response = GitServiceHelper.createSuccessResponse("Push operation started");
        return GitServiceHelper.toJson(response);
    }
    
    /**
     * Creates a response for successful file selection.
     */
    public static String filesSelectedSuccess() {
        JsonObject response = GitServiceHelper.createSuccessResponse("Files selected and commit pipeline continued");
        return GitServiceHelper.toJson(response);
    }
    
    /**
     * Creates a response with diff data.
     */
    public static String diffResponse(String diff) {
        JsonObject response = GitServiceHelper.createSuccessResponse();
        response.addProperty("diff", diff);
        return GitServiceHelper.toJson(response);
    }
    
    /**
     * Creates a response for file content.
     */
    public static String fileContentResponse(String content, String path) {
        JsonObject response = GitServiceHelper.createSuccessResponse();
        response.addProperty("content", content);
        response.addProperty("path", path);
        return GitServiceHelper.toJson(response);
    }
    
    /**
     * Creates a response for successful operations with a custom message.
     */
    public static String successResponse(String message) {
        return GitServiceHelper.toJson(GitServiceHelper.createSuccessResponse(message));
    }
    
    /**
     * Creates an error response.
     */
    public static String errorResponse(String error) {
        return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(error));
    }
    
    /**
     * Creates an error response from an exception.
     */
    public static String errorResponse(Exception e) {
        return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
    }
}