# Zest OpenAI Tool Server

An OpenAPI-compliant server that exposes Zest code exploration capabilities as RESTful APIs, compatible with OpenAI function calling and other AI tools.

## Features

- **OpenAPI 3.0 Compliant**: Full OpenAPI specification support with automatic documentation
- **Authentication**: Optional API key authentication for secure access
- **Auto-discovery**: Automatically finds and connects to Zest Agent Proxy
- **Async Support**: Built with FastAPI for high-performance async operations
- **CORS Enabled**: Ready for web-based integrations
- **OpenAI Compatible**: Includes x-openai extensions for seamless function calling

## Quick Start

1. **Install dependencies:**
```bash
pip install -r requirements.txt
```

2. **Start the Zest Agent Proxy** in IntelliJ IDEA

3. **Run the server:**
```bash
# Basic usage
uvicorn main:app --reload

# With custom port
uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# With API key authentication
export OPENAI_TOOL_SERVER_API_KEY="your-secret-key"
uvicorn main:app --reload
```

4. **Access the API:**
- Interactive docs: http://localhost:8000/docs
- OpenAPI schema: http://localhost:8000/openapi.json
- Health check: http://localhost:8000/health

## API Endpoints

### Core Operations

- `POST /explore` - Explore code with natural language queries
- `POST /execute-tool` - Execute specific exploration tools
- `GET /tools` - List available tools

### Convenience Endpoints

- `POST /search` - Search code using natural language
- `POST /find-by-name` - Find elements by name
- `POST /read-file` - Read file contents
- `POST /find-relationships` - Find code relationships
- `POST /find-usages` - Find element usages
- `POST /class-info` - Get class information
- `POST /augment` - Augment queries with context

### System Endpoints

- `GET /health` - Server and proxy health check
- `GET /status` - Proxy connection status
- `GET /config` - Get configuration
- `POST /config` - Update configuration

## Environment Variables

- `ZEST_PROXY_URL` - Zest Agent Proxy URL (default: auto-discovery)
- `OPENAI_TOOL_SERVER_API_KEY` - API key for authentication (optional)

## Example Usage

### Using curl

```bash
# Search for code
curl -X POST http://localhost:8000/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-api-key" \
  -d '{"query": "find all REST endpoints", "max_results": 20}'

# Explore code with a query
curl -X POST http://localhost:8000/explore \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-api-key" \
  -d '{"query": "How does the authentication system work?", "generate_report": true}'
```

### Using Python

```python
import httpx

# Create client
client = httpx.Client(
    base_url="http://localhost:8000",
    headers={"Authorization": "Bearer your-api-key"}
)

# Explore code
response = client.post("/explore", json={
    "query": "What are the main components of this system?",
    "generate_report": True
})
result = response.json()
print(result["summary"])

# Search for specific code
response = client.post("/search", json={
    "query": "database connection",
    "max_results": 5
})
```

### Integration with OpenAI

```python
import openai

# Configure OpenAI to use your tool server
client = openai.Client()

# Define the function
functions = [{
    "type": "function",
    "function": {
        "name": "explore_code",
        "description": "Explore code using natural language",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Natural language query about the code"
                }
            },
            "required": ["query"]
        }
    }
}]

# Use in conversation
response = client.chat.completions.create(
    model="gpt-4",
    messages=[
        {"role": "user", "content": "How does the user authentication work in this codebase?"}
    ],
    functions=functions,
    function_call="auto"
)
```

## Docker Support

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

Build and run:
```bash
docker build -t zest-openai-server .
docker run -p 8000:8000 -e OPENAI_TOOL_SERVER_API_KEY=secret zest-openai-server
```

## Security

- Enable API key authentication in production
- Use HTTPS when exposing to the internet
- Configure CORS origins appropriately
- Run behind a reverse proxy (nginx, traefik) for additional security

## Development

Run with auto-reload for development:
```bash
uvicorn main:app --reload --log-level debug
```

## License

MIT
