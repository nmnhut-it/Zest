package com.zps.zest;

import java.util.regex.Matcher;
import java.util.regex.Pattern; /**
 * Stage for extracting the code from the LLM response.
 */
public class CodeExtractionStage implements PipelineStage {
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        String response = context.getApiResponse();
        if (response == null || response.isEmpty()) {
            throw new PipelineExecutionException("Empty response from LLM API");
        }

        String testCode = extractCodeFromResponse(response);
        if (testCode == null || testCode.isEmpty()) {
            throw new PipelineExecutionException("Failed to extract test code from response");
        }

        context.setTestCode(testCode);
    }

    private String extractCodeFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        // Handle unicode escape sequences
        response = unescapeUnicode(response);

        // First remove any <think>...</think> tags
        StringBuilder withoutThinkTags = new StringBuilder();
        boolean inThinkTag = false;
        int i = 0;

        while (i < response.length()) {
            if (i + 7 <= response.length() && response.substring(i, i + 7).equals("<think>")) {
                inThinkTag = true;
                i += 7;
            } else if (i + 8 <= response.length() && response.substring(i, i + 8).equals("</think>")) {
                inThinkTag = false;
                i += 8;
            } else if (!inThinkTag) {
                withoutThinkTags.append(response.charAt(i));
                i++;
            } else {
                i++;
            }
        }

        String cleanedResponse = withoutThinkTags.toString().trim();

        // Try to find Java code between ```java and ``` markers
        Pattern pattern = Pattern.compile("```(?:java)?[\\s\\n]*([\\s\\S]*?)[\\s\\n]*```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(cleanedResponse);

        // If we find a code block, return its contents
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // If no markdown code blocks found, return the cleaned response
        return cleanedResponse;
    }

    private String unescapeUnicode(String input) {
        // Handle common Unicode escape sequences
        return input.replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0027", "'")
                .replace("\\u0022", "\"")
                .replace("\\u002F", "/")
                .replace("\\u005C", "\\")
                .replace("\\u0026", "&")
                .replace("\\u0023", "#");
    }
}
