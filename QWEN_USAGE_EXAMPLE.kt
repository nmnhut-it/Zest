// Example showing how to use the updated LLMService with Qwen 2.5 Coder

import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities

fun exampleQwenCoderUsage(llmService: LLMService) {
    // Example 1: Basic FIM completion with Qwen 2.5 Coder
    val fimPrompt = """<|fim_prefix|>public class Calculator {
    public int add(int a, int b) {
        <|fim_suffix|>
    }
    
    public int subtract(int a, int b) {
        return a - b;
    }
}<|fim_middle|>"""

    val basicParams = LLMService.LLMQueryParams(fimPrompt)
        .withModel("Qwen/Qwen2.5-Coder-7B")
        .withMaxTokens(50)
        .withTemperature(0.1) // Low temperature for deterministic code completion
        .withStopSequences(listOf(
            "<|fim_suffix|>", 
            "<|fim_prefix|>", 
            "<|fim_pad|>", 
            "<|endoftext|>"
        ))

    val response = llmService.queryWithParams(basicParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
    println("Basic FIM response: $response")

    // Example 2: Multi-line Java static block (your original example)
    val staticBlockPrompt = """<|fim_prefix|>package com.zps.leaderboard;

import com.google.gson.JsonObject;
import com.zps.annotations.AdminApi;
import com.zps.redis.RedisConfig;

public class Leaderboard implements LeaderboardKeyDefine {
    private static final Logger logger = LoggerFactory.getLogger(Leaderboard.class);
    
    static {
        try {
            load();
        } catch (Exception e) {
            <|fim_suffix|>
        }
    }
    
    public static void load() throws Exception {
        // Load leaderboard data
    }
}<|fim_middle|>"""

    val staticBlockParams = LLMService.LLMQueryParams(staticBlockPrompt)
        .withModel("Qwen/Qwen2.5-Coder-7B")
        .withMaxTokens(75) // Allow more tokens for exception handling
        .withTemperature(0.15) // Slightly higher for more creative exception handling
        .withStopSequences(listOf(
            "<|fim_suffix|>", 
            "<|fim_prefix|>", 
            "<|endoftext|>",
            "}"
        ))

    val staticResponse = llmService.queryWithParams(staticBlockParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
    println("Static block exception handling: $staticResponse")

    // Example 3: Fine-tuned parameters for different scenarios
    val methodBodyPrompt = """<|fim_prefix|>public boolean validateUser(String username, String password) {
    if (username == null || username.trim().isEmpty()) {
        <|fim_suffix|>
    }
    // Continue validation...
    return true;
}<|fim_middle|>"""

    val validationParams = LLMService.LLMQueryParams(methodBodyPrompt)
        .withModel("Qwen/Qwen2.5-Coder-7B")
        .withMaxTokens(30) // Short completion for simple validation
        .withTemperature(0.05) // Very deterministic for validation logic
        .withStopSequence("<|fim_suffix|>")
        .withStopSequence("return")
        .withStopSequence("\n    }")

    val validationResponse = llmService.queryWithParams(validationParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
    println("Validation logic: $validationResponse")
}

// Expected outputs:
// Basic FIM response: "return a + b;"
// Static block exception handling: "logger.error(\"Failed to load leaderboard\", e);" or "e.printStackTrace();"
// Validation logic: "return false;" or "throw new IllegalArgumentException(\"Username cannot be empty\");"
