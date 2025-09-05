# Zest Embedding Server

Local embedding server using Ollama for the Zest IntelliJ plugin.

## Quick Start

### Windows
```cmd
cd embedding-server
start.bat
```

### Linux/Mac
```bash
cd embedding-server
./start.sh
```

## Manual Setup

1. Start Ollama container:
```bash
docker-compose up -d
```

2. Pull embedding model:
```bash
docker exec zest-embedding-ollama ollama pull all-minilm
```

## API Usage

- **Endpoint**: `http://localhost:11435/api/embeddings`
- **Model**: `all-minilm`
- **Dimensions**: 384

### Example Request
```bash
curl -X POST http://localhost:11435/api/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model": "all-minilm", "prompt": "Your text here"}'
```

### Example Response
```json
{
  "embedding": [0.1, 0.2, 0.3, ...]
}
```

## Management

- **Start**: `docker-compose up -d`
- **Stop**: `docker-compose down`
- **Logs**: `docker-compose logs -f`
- **Models**: `docker exec zest-embedding-ollama ollama list`

## Integration

The Zest plugin automatically detects if the local embedding server is running on `localhost:11435` and will use it as the primary embedding provider, falling back to remote APIs if unavailable.