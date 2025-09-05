#!/bin/bash

# Zest Embedding Server Startup Script
# This script starts Ollama embedding server and pulls the required model

set -e

echo "🚀 Starting Zest Embedding Server (Ollama)..."

# Check if Docker is running
if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker is not running. Please start Docker and try again."
    exit 1
fi

# Start the services
echo "📦 Starting Ollama container on port 11435..."
docker-compose up -d

# Wait for Ollama to be ready
echo "⏳ Waiting for Ollama to be ready..."
for i in {1..30}; do
    if curl -sf http://localhost:11435/api/version >/dev/null 2>&1; then
        echo "✅ Ollama is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Timeout waiting for Ollama to start"
        exit 1
    fi
    echo "   Attempt $i/30..."
    sleep 2
done

# Pull the embedding model
echo "📥 Pulling all-minilm embedding model..."
docker exec zest-embedding-ollama ollama pull all-minilm

# Verify the model is available
echo "🔍 Verifying model availability..."
docker exec zest-embedding-ollama ollama list | grep all-minilm

echo ""
echo "🎉 Zest Embedding Server is ready!"
echo "📍 API Endpoint: http://localhost:11435/api/embeddings"
echo ""
echo "Test with:"
echo 'curl -X POST http://localhost:11435/api/embeddings \'
echo '  -H "Content-Type: application/json" \'
echo '  -d '"'"'{"model": "all-minilm", "prompt": "Hello world"}'"'"
echo ""
echo "To stop: docker-compose down"