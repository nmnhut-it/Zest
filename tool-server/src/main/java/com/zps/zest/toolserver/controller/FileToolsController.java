package com.zps.zest.toolserver.controller;

import com.zps.zest.toolserver.service.IntelliJPluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing file operation tools with OpenAPI documentation.
 */
@RestController
@RequestMapping("/api/tools")
@Tag(name = "File Operations", description = "Tools for reading and finding files in the project")
public class FileToolsController {

    private final IntelliJPluginService pluginService;

    public FileToolsController(IntelliJPluginService pluginService) {
        this.pluginService = pluginService;
    }

    @PostMapping("/readFile")
    @Operation(
        summary = "Read file contents",
        description = """
            Read the complete content of any text-based file in the project.
            Supports source code, documentation, configuration files, scripts, and more.

            Supported formats: Java, Kotlin, Python, JavaScript, TypeScript, XML, JSON, YAML,
            Properties, Markdown, HTML, CSS, Shell scripts, SQL, and other text files.
            """,
        operationId = "readFile"
    )
    @ApiResponse(responseCode = "200", description = "File content retrieved successfully",
                content = @Content(schema = @Schema(implementation = String.class)))
    public String readFile(
        @Parameter(description = "File path (relative to project root, absolute, or package-style notation)",
                  example = "src/main/java/com/example/UserService.java")
        @RequestBody Map<String, String> request
    ) {
        String filePath = request.get("filePath");
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/readFile", request);
        return response.getResultOrError();
    }

    @PostMapping("/findFiles")
    @Operation(
        summary = "Find files by pattern",
        description = """
            Find files by NAME/PATH matching glob patterns.
            Searches file names, NOT file contents. Use searchCode() to search inside files.
            Supports comma-separated patterns for multiple file types.

            Examples:
            - "*.java" → All Java files
            - "*.java,*.kt" → All Java and Kotlin files
            - "*Test.java" → All test classes
            - "pom.xml,build.gradle" → Build files
            """,
        operationId = "findFiles"
    )
    @ApiResponse(responseCode = "200", description = "Files found successfully")
    public String findFiles(
        @Parameter(description = "Comma-separated glob patterns",
                  example = "*.java,*.kt")
        @RequestBody Map<String, String> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/findFiles", request);
        return response.getResultOrError();
    }

    @PostMapping("/listFiles")
    @Operation(
        summary = "List directory contents",
        description = """
            List files and subdirectories in a directory with controlled recursion depth.

            Recursion levels:
            - 0 = Current directory only
            - 1 = Current + immediate subdirectories
            - 2 = Two levels deep
            - 3+ = Deeper exploration
            """,
        operationId = "listFiles"
    )
    @ApiResponse(responseCode = "200", description = "Directory listing retrieved successfully")
    public String listFiles(
        @Parameter(description = "Directory path (relative to project root)", example = "src/main")
        @RequestBody Map<String, Object> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/listFiles", request);
        return response.getResultOrError();
    }
}
