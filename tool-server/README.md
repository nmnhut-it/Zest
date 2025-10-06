# Zest Tool Server

**OpenAPI-compliant REST server** exposing IntelliJ code exploration and modification tools. Works with OpenWebUI, MCP clients, and any OpenAPI-aware system.

## Why OpenAPI Instead of MCP?

✅ **Universal Compatibility** - Works with OpenWebUI, Swagger clients, Postman, and custom integrations
✅ **Auto-Generated Docs** - Swagger UI provides interactive API testing
✅ **Simple REST** - Standard HTTP endpoints, no special protocol
✅ **Flexible Integration** - Easy to add MCP adapter layer if needed

## Architecture

```
┌─────────────────────┐         ┌──────────────────────┐
│  IntelliJ Plugin    │◄────────│  Spring Boot Server  │
│  (Tool Execution)   │  HTTP   │  (REST + OpenAPI)    │
│  Port 63342         │─────────►│  Port 8765           │
└─────────────────────┘         └──────────────────────┘
                                          │
                                          │ OpenAPI Schema
                                          │ /openapi.json
                                          ▼
                                    ┌──────────┐
                                    │ OpenWebUI│
                                    │  Swagger │
                                    │   MCP*   │
                                    └──────────┘
```

## Available Tools (9 endpoints)

### 📂 File Operations
- `POST /api/tools/readFile` - Read any text file
- `POST /api/tools/findFiles` - Find files by glob pattern
- `POST /api/tools/listFiles` - List directory contents with depth control

### 🔍 Code Search
- `POST /api/tools/searchCode` - Ripgrep-based code search with regex

### 🔬 Java Analysis
- `POST /api/tools/analyzeClass` - Analyze Java class structure
- `POST /api/tools/lookupMethod` - Find method signatures
- `POST /api/tools/lookupClass` - Look up class definitions

### ✏️ Code Modification (requires user approval)
- `POST /api/tools/replaceCodeInFile` - Replace code with diff preview
- `POST /api/tools/createNewFile` - Create new files

## Quick Start

### 1. Build the Server

```bash
# From project root
./gradlew :tool-server:bootJar

# Creates: tool-server/build/libs/zest-tool-server.jar
```

### 2. Start the Server

```bash
# Run the JAR
java -jar tool-server/build/libs/zest-tool-server.jar

# OR use Spring Boot gradle plugin
./gradlew :tool-server:bootRun
```

Server starts on http://localhost:8765

### 3. Explore the API

Open **Swagger UI** in your browser (like FastAPI's `/docs`):
```
http://localhost:8765/docs
```

Get **OpenAPI Schema** (like FastAPI's `/openapi.json`):
```
http://localhost:8765/openapi.json
```

## Integration Examples

### OpenWebUI Integration

1. Open OpenWebUI Settings → Tools → External Tools
2. Add new tool server:
   - **URL**: `http://localhost:8765/api/tools/{toolName}`
   - **Method**: POST
   - **Auth**: None (or add if needed)

3. Import OpenAPI schema:
   - Go to http://localhost:8765/openapi.json
   - Copy JSON schema
   - Import into OpenWebUI

### Claude Desktop / MCP Integration

Since this is OpenAPI-based, you need an MCP adapter. Two options:

**Option A**: Use `mcp-server-openapi` npm package:
```json
{
  "mcpServers": {
    "zest": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-openapi",
               "http://localhost:8765/openapi.json"]
    }
  }
}
```

**Option B**: Use our Quarkus MCP wrapper (if you need MCP specifically, we can add it back)

### cURL Testing

```bash
# Read a file
curl -X POST http://localhost:8765/api/tools/readFile \
  -H "Content-Type: application/json" \
  -d '{"filePath": "README.md"}'

# Search code
curl -X POST http://localhost:8765/api/tools/searchCode \
  -H "Content-Type: application/json" \
  -d '{
    "query": "TODO",
    "filePattern": "*.java",
    "beforeLines": 0,
    "afterLines": 0
  }'

# Find files
curl -X POST http://localhost:8765/api/tools/findFiles \
  -H "Content-Type: application/json" \
  -d '{"pattern": "*.java,*.kt"}'
```

## Configuration

Edit `tool-server/src/main/resources/application.yml`:

```yaml
server:
  port: 8765  # Change server port

intellij:
  plugin:
    url: http://localhost:63342  # Change IntelliJ plugin URL

logging:
  level:
    com.zps.zest.toolserver: DEBUG  # Enable debug logging
```

## Development

### Run in Dev Mode

```bash
./gradlew :tool-server:bootRun
```

Features:
- Auto-reload on code changes
- Debug logging enabled
- Swagger UI for testing: http://localhost:8765/docs

### Adding New Tools

1. Add endpoint method to appropriate controller
2. Use `@Operation` annotation for OpenAPI docs
3. Implement corresponding method in IntelliJ plugin's ToolApiServer
4. Rebuild and restart

Example:

```java
@PostMapping("/myNewTool")
@Operation(
    summary = "My new tool",
    description = "What it does",
    operationId = "myNewTool"
)
public String myNewTool(@RequestBody Map<String, String> request) {
    var response = pluginService.callTool("/api/tools/myNewTool", request);
    return response.getResultOrError();
}
```

## OpenAPI Schema Features

The auto-generated schema includes:

- **Operation IDs**: Unique identifiers for each tool (e.g., `readFile`, `searchCode`)
- **Parameter Descriptions**: Detailed docs for each parameter
- **Examples**: Sample requests and responses
- **Tags**: Organized by category (File Operations, Code Search, etc.)
- **Schemas**: JSON schema for request/response bodies

This makes it compatible with:
- OpenWebUI's tool import feature
- Swagger Codegen for client SDKs
- API Gateway integrations
- Custom tooling

## Troubleshooting

### "Cannot connect to IntelliJ plugin"

**Cause**: Tool server can't reach IntelliJ plugin

**Solutions**:
1. Ensure IntelliJ plugin is running with project open
2. Check Tool API Server started (see IntelliJ logs)
3. Verify port 63342 is not blocked
4. Test manually: `curl http://localhost:63342/api/health`

### "Tool execution failed"

**Cause**: Plugin received request but couldn't execute

**Solutions**:
1. Check IntelliJ IDEA logs (Help → Show Log)
2. Verify file paths are relative to project root
3. Ensure project is indexed (for Java tools)
4. For code modification, ensure IDE UI is available

### Swagger UI shows "Failed to fetch"

**Cause**: CORS or server not running

**Solutions**:
1. Check server is running: `curl http://localhost:8765/actuator/health`
2. Verify CORS config in application.yml
3. Check browser console for errors

## Deployment

### Standalone JAR

```bash
# Build
./gradlew :tool-server:bootJar

# Run
java -jar tool-server/build/libs/zest-tool-server.jar
```

### Docker (Optional)

```dockerfile
FROM openjdk:17-jdk-slim
COPY tool-server/build/libs/zest-tool-server.jar app.jar
EXPOSE 8765
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Systemd Service (Linux)

```ini
[Unit]
Description=Zest Tool Server
After=network.target

[Service]
Type=simple
User=youruser
ExecStart=/usr/bin/java -jar /path/to/zest-tool-server.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## OpenAPI Schema Example

The generated schema looks like:

```json
{
  "openapi": "3.0.1",
  "info": {
    "title": "Zest Code Tools API",
    "version": "1.0.0"
  },
  "paths": {
    "/api/tools/readFile": {
      "post": {
        "operationId": "readFile",
        "summary": "Read file contents",
        "requestBody": {
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "properties": {
                  "filePath": { "type": "string" }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

## Performance

- **Startup**: ~2-3 seconds (Spring Boot)
- **Memory**: ~200MB heap
- **Response Time**: Depends on IntelliJ plugin execution (typically <1s for file read, <5s for complex searches)

## License

Same as Zest IntelliJ Plugin
