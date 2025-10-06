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
 * REST controller exposing code search tools with OpenAPI documentation.
 */
@RestController
@RequestMapping("/api/tools")
@Tag(name = "Code Search", description = "High-performance ripgrep-based code search tools")
public class SearchToolsController {

    private final IntelliJPluginService pluginService;

    public SearchToolsController(IntelliJPluginService pluginService) {
        this.pluginService = pluginService;
    }

    @PostMapping("/searchCode")
    @Operation(
        summary = "Search code contents",
        description = """
            Search for patterns INSIDE file contents using high-performance ripgrep.
            Searches the text content of files, not file names.

            KEY SYNTAX:
            - query: REGEX syntax (use | for OR, e.g., "TODO|FIXME")
            - filePattern: GLOB syntax (use comma for multiple, e.g., "*.java,*.kt")

            Parameters:
            - query: Search pattern (regex)
            - filePattern: Optional comma-separated glob patterns to filter files
            - excludePattern: Optional comma-separated patterns to exclude
            - beforeLines: Number of context lines before match (0-10)
            - afterLines: Number of context lines after match (0-10)

            Examples:
            - Search TODOs: query="TODO|FIXME", filePattern="*.java,*.kt"
            - Find method: query="getUserById", filePattern="*.java", excludePattern="test,generated"
            - With context: query="import.*React", filePattern="*.tsx,*.jsx", beforeLines=2, afterLines=2
            """,
        operationId = "searchCode"
    )
    @ApiResponse(responseCode = "200", description = "Search results returned successfully")
    public String searchCode(
        @RequestBody @Schema(description = "Search request parameters") Map<String, Object> request
    ) {
        IntelliJPluginService.ToolResponse response =
            pluginService.callTool("/api/tools/searchCode", request);
        return response.getResultOrError();
    }
}
