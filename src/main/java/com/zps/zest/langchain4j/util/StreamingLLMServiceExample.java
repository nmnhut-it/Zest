package com.zps.zest.langchain4j.util;

import com.intellij.openapi.project.Project;

/**
 * Example usage of StreamingLLMService with request/response logging.
 */
public class StreamingLLMServiceExample {
    
    public static void example(Project project) {
        // Get the service instance
        StreamingLLMService service = project.getService(StreamingLLMService.class);
        
        // Enable logging (it's enabled by default)
        service.setLoggingEnabled(true);
        
        // Optional: Set custom log directory
        // Default is: user.home/zest_llm_logs
        service.setLogDirectory("D:/LLM_Logs");
        
        // Example 1: Simple streaming query
        String prompt = "Write a hello world program in Java";
        service.streamQuery(prompt, chunk -> {
            // Process each chunk as it arrives
            System.out.print(chunk);
        }).thenAccept(fullResponse -> {
            System.out.println("\n\nComplete response received!");
            // The request and response are automatically logged to files
        });
        
        // Example 2: Streaming with custom model
        String customModel = "codellama:13b";
        service.streamQuery(prompt, customModel, chunk -> {
            System.out.print(chunk);
        });
        
        // Example 3: Streaming with cancellation support
        StreamingLLMService.StreamingSession session = service.createStreamingSession(prompt);
        
        session.start(chunk -> {
            System.out.print(chunk);
            
            // Cancel after receiving some content
            if (session.getPartialResponse().length() > 100) {
                session.cancel();
            }
        });
        
        // The logs will be saved in the following format:
        // - request_<model>_<timestamp>.json   - Contains the request JSON
        // - response_<model>_<timestamp>.txt   - Contains the complete response
        // - error_<model>_<timestamp>_code_<code>.txt - Contains any errors
        
        // To disable logging temporarily:
        service.setLoggingEnabled(false);
        
        // To get the current log directory:
        String logDir = service.getLogDirectory();
        System.out.println("Logs are saved to: " + logDir);
    }
}
