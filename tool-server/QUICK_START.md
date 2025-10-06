# Quick Start - Zest Tool Server

## 3-Step Setup

### Step 1: Build

```bash
./gradlew :tool-server:bootJar
```

Creates: `tool-server/build/libs/zest-tool-server.jar`

### Step 2: Run

```bash
# Start the server
java -jar tool-server/build/libs/zest-tool-server.jar

# OR use Gradle
./gradlew :tool-server:bootRun
```

Server starts on http://localhost:8765

### Step 3: Test

Open Swagger UI (like FastAPI's `/docs`): **http://localhost:8765/docs**

Try the **readFile** tool:
1. Expand "File Operations" → "POST /api/tools/readFile"
2. Click "Try it out"
3. Enter: `{"filePath": "README.md"}`
4. Click "Execute"

## Prerequisites

- **IntelliJ IDEA** with Zest plugin running
- **Java 17+**
- **Project open in IntelliJ** (Tool API Server auto-starts on port 63342)

## OpenWebUI Integration

### Method 1: Direct Tool Import (per tool)

1. Get OpenAPI schema: http://localhost:8765/openapi.json
2. In OpenWebUI: Settings → Tools → Add Tool
3. For each tool, use URL pattern:
   - `http://localhost:8765/api/tools/readFile`
   - `http://localhost:8765/api/tools/searchCode`
   - etc.

### Method 2: OpenAPI Schema Import (all tools)

1. Download schema:
   ```bash
   curl http://localhost:8765/openapi.json > zest-tools-schema.json
   ```

2. Import in OpenWebUI:
   - Settings → Tools → Import OpenAPI
   - Upload `zest-tools-schema.json`
   - All 9 tools imported automatically

## Example Tool Calls

### Read File
```json
POST /api/tools/readFile
{
  "filePath": "src/main/java/com/example/UserService.java"
}
```

### Search Code
```json
POST /api/tools/searchCode
{
  "query": "TODO|FIXME",
  "filePattern": "*.java,*.kt",
  "excludePattern": "test",
  "beforeLines": 1,
  "afterLines": 1
}
```

### Analyze Class
```json
POST /api/tools/analyzeClass
{
  "filePathOrClassName": "com.example.service.UserService"
}
```

### Find Files
```json
POST /api/tools/findFiles
{
  "pattern": "*Test.java,*IT.java"
}
```

## Verify Setup

### 1. Check IntelliJ Plugin
```bash
curl http://localhost:63342/api/health
```

Should return:
```json
{"status":"healthy","service":"zest-tool-api","project":"YourProject"}
```

### 2. Check Tool Server
```bash
curl http://localhost:8765/actuator/health
```

Should return:
```json
{"status":"UP"}
```

### 3. Check Tool Availability
```bash
curl http://localhost:8765/openapi.json | jq '.paths | keys'
```

Should list all 9 tools

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Server won't start | Check Java 17+ installed: `java -version` |
| Can't connect to IntelliJ | Make sure project is open in IntelliJ |
| Tools not working | Check both health endpoints above |
| Swagger UI blank | Clear browser cache, check console errors |

## Next Steps

- **Full Docs**: See `README.md` for detailed documentation
- **Configuration**: Edit `application.yml` for custom settings
- **Add Tools**: Create new controller methods with `@Operation`
- **MCP Support**: Add Quarkus MCP wrapper if needed

## MCP Support (Optional)

If you need MCP protocol (Claude Desktop, Cursor), use the npm adapter:

```bash
npm install -g @modelcontextprotocol/server-openapi

# Then configure Claude Desktop:
{
  "mcpServers": {
    "zest": {
      "command": "mcp-server-openapi",
      "args": ["http://localhost:8765/openapi.json"]
    }
  }
}
```

This wraps your OpenAPI server with MCP protocol automatically!