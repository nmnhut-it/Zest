package com.zps.zest.langchain4j.agent.network;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.UnauthorizedResponse;

/**
 * Example of adding API key authentication to Javalin server (like FastAPI).
 */
public class JavalinProxyServerWithAuth {
    
    private final String apiKey;
    
    public JavalinProxyServerWithAuth(String apiKey) {
        this.apiKey = apiKey;
    }
    
    /**
     * Authentication handler - checks for API key in header or query param.
     */
    private void authenticate(Context ctx) {
        String providedKey = ctx.header("X-API-Key");
        if (providedKey == null) {
            providedKey = ctx.queryParam("api_key");
        }
        
        if (providedKey == null || !providedKey.equals(apiKey)) {
            throw new UnauthorizedResponse("Invalid or missing API key");
        }
    }
    
    /**
     * Wraps a handler with authentication.
     */
    private Handler withAuth(Handler handler) {
        return ctx -> {
            authenticate(ctx);
            handler.handle(ctx);
        };
    }
    
    /**
     * Example of setting up routes with authentication.
     */
    public void setupRoutes(Javalin app) {
        // Public endpoints (no auth)
        app.get("/zest/openapi.json", this::handleOpenApi);
        app.get("/zest/docs", this::handleDocs);
        
        // Protected endpoints (require auth)
        app.post("/zest/explore_code", withAuth(this::handleExploreCode));
        app.post("/zest/search_code", withAuth(this::handleSearchCode));
        
        // Or protect all routes with before filter
        app.before("/zest/*", ctx -> {
            // Skip auth for public endpoints
            String path = ctx.path();
            if (!path.endsWith("/openapi.json") && !path.endsWith("/docs")) {
                authenticate(ctx);
            }
        });
    }
    
    private void handleOpenApi(Context ctx) {
        // Implementation
    }
    
    private void handleDocs(Context ctx) {
        // Implementation
    }
    
    private void handleExploreCode(Context ctx) {
        // Implementation
    }
    
    private void handleSearchCode(Context ctx) {
        // Implementation
    }
}
