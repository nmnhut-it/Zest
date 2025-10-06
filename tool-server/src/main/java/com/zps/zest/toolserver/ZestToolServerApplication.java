package com.zps.zest.toolserver;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Zest Tool Server - OpenAPI-compliant REST server exposing IntelliJ code tools.
 * Compatible with OpenWebUI, MCP clients, and any OpenAPI-aware system.
 */
@SpringBootApplication
@OpenAPIDefinition(
    info = @Info(
        title = "Zest Code Tools API",
        version = "1.0.0",
        description = "OpenAPI-compliant tool server for IntelliJ code exploration and modification. " +
                     "Provides file reading, code search, Java analysis, and code modification capabilities.",
        contact = @Contact(
            name = "Zest Plugin",
            url = "https://github.com/your-repo/zest"
        )
    ),
    servers = {
        @Server(url = "http://localhost:8765", description = "Local Development Server")
    }
)
public class ZestToolServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZestToolServerApplication.class, args);
    }

    /**
     * Configure CORS to allow OpenWebUI and other web clients to access the API.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*");
            }
        };
    }
}
