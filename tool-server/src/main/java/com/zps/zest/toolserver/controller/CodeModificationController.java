package com.zps.zest.toolserver.controller;

import com.zps.zest.toolserver.service.IntelliJPluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller exposing code modification tools with OpenAPI documentation.
 * These tools require user approval in IntelliJ IDE.
 */
@RestController
@RequestMapping("/api/tools")
@Tag(name = "Code Modification", description = "Tools for modifying code files (requires user approval in IDE)")
public class CodeModificationController {

    private final IntelliJPluginService pluginService;

    public CodeModificationController(IntelliJPluginService pluginService) {
        this.pluginService = pluginService;
    }

    @PostMapping("/replaceCodeInFile")
    @Operation(
        summary = "Replace code in file",
        description = """
            Replace code in a file with diff preview and user confirmation in IntelliJ.
            User accepts with TAB or rejects with ESC.

            IMPORTANT: Search pattern must be UNIQUE (appears exactly once).
            Include 2-3 surrounding lines to ensure uniqueness.

            Parameters:
            - filePath: Relative to project root
            - searchPattern: Code to find (must be unique, exact whitespace)
            - replacement: New code
            - useRegex: Use regex matching (default: false)

            Example:
            {
              "filePath": "src/main/java/User.java",
              "searchPattern": "public String getName() {\\n    return name;\\n}",
              "replacement": "public String getName() {\\n    return this.name;\\n}",
              "useRegex": false
            }
            """,
        operationId = "replaceCodeInFile"
    )
    @ApiResponse(responseCode = "200", description = "Code replacement completed or rejected")
    public String replaceCodeInFile(
        @RequestBody @Schema(description = "Code replacement parameters") Map<String, Object> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/replaceCodeInFile", request);
        return response.getResultOrError();
    }

    @PostMapping("/createNewFile")
    @Operation(
        summary = "Create new file",
        description = """
            Create a new file with specified content.
            Creates parent directories if needed.
            Returns error if file already exists.

            Parameters:
            - filePath: Relative to project root
            - content: Complete file content (include package, imports, formatting)

            Example:
            {
              "filePath": "src/main/java/com/example/NewService.java",
              "content": "package com.example;\\n\\npublic class NewService {\\n    // TODO\\n}\\n"
            }
            """,
        operationId = "createNewFile"
    )
    @ApiResponse(responseCode = "200", description = "File created successfully")
    public String createNewFile(
        @RequestBody @Schema(description = "File creation parameters") Map<String, String> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/createNewFile", request);
        return response.getResultOrError();
    }
}
