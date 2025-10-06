package com.zps.zest.toolserver.controller;

import com.zps.zest.toolserver.service.IntelliJPluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing Java code analysis tools with OpenAPI documentation.
 */
@RestController
@RequestMapping("/api/tools")
@Tag(name = "Java Analysis", description = "PSI-based Java code structure analysis tools")
public class JavaAnalysisController {

    private final IntelliJPluginService pluginService;

    public JavaAnalysisController(IntelliJPluginService pluginService) {
        this.pluginService = pluginService;
    }

    @PostMapping("/analyzeClass")
    @Operation(
        summary = "Analyze Java class structure",
        description = """
            Analyze a Java class to extract complete structure, dependencies, and relationships.

            Provides:
            - Class hierarchy and interfaces
            - Public/private methods and signatures
            - Field declarations
            - Annotations
            - Inner classes and enums
            - Direct dependencies

            Parameters:
            - filePathOrClassName: FQN (com.example.UserService), file path, or simple name

            Examples:
            - "com.example.service.UserService" (FQN - most reliable)
            - "src/main/java/com/example/UserService.java" (file path)
            - "UserService" (simple name - may find multiple matches)
            """,
        operationId = "analyzeClass"
    )
    @ApiResponse(responseCode = "200", description = "Class analysis completed")
    public String analyzeClass(
        @Parameter(description = "Class identifier (FQN, file path, or simple name)",
                  example = "com.example.service.UserService")
        @RequestBody Map<String, String> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/analyzeClass", request);
        return response.getResultOrError();
    }

    @PostMapping("/lookupMethod")
    @Operation(
        summary = "Lookup method definition",
        description = """
            Look up method signatures using class name and method name.
            Works with project classes, library JARs, and JDK classes.

            Parameters:
            - className: Fully qualified or simple class name
            - methodName: Method name to find

            Returns: Method signatures with modifiers, return type, parameters, and exceptions

            Examples:
            - className="java.util.List", methodName="add"
            - className="com.example.UserService", methodName="findById"
            - className="UserService", methodName="save"
            """,
        operationId = "lookupMethod"
    )
    @ApiResponse(responseCode = "200", description = "Method lookup completed")
    public String lookupMethod(
        @RequestBody @Schema(description = "Method lookup parameters") Map<String, String> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/lookupMethod", request);
        return response.getResultOrError();
    }

    @PostMapping("/lookupClass")
    @Operation(
        summary = "Lookup class definition",
        description = """
            Look up class implementation using fully qualified class name.
            Works with project classes, library JARs, and JDK classes.

            Returns:
            - Class signature with modifiers
            - Type parameters
            - Superclass and interfaces
            - Fields summary
            - Methods summary
            - Inner classes

            Examples:
            - "java.util.ArrayList"
            - "com.example.service.UserService"
            - "org.junit.jupiter.api.Test"
            """,
        operationId = "lookupClass"
    )
    @ApiResponse(responseCode = "200", description = "Class lookup completed")
    public String lookupClass(
        @Parameter(description = "Fully qualified class name",
                  example = "com.example.service.UserService")
        @RequestBody Map<String, String> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/lookupClass", request);
        return response.getResultOrError();
    }
}
