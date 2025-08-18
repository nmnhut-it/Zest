#!/bin/bash

# Test embedding generation with the Zest embedding service
# Using the litellm.zingplay.com endpoint with Qwen3-Embedding model

ENDPOINT="https://litellm.zingplay.com/api/embeddings"
API_KEY="sk-0c1l7KCScBLmcYDN-Oszmg"

# Test 1: Single text embedding
echo "Testing single text embedding..."
curl -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": "This is a test text for embedding generation"
  }' | python -m json.tool

echo ""
echo "---"
echo ""

# Test 2: Batch embeddings
echo "Testing batch embeddings..."
curl -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": [
      "First text to embed",
      "Second text to embed",
      "Third text to embed"
    ]
  }' | python -m json.tool

echo ""
echo "---"
echo ""

# Test 3: Code embedding (more realistic use case)
echo "Testing code embedding..."
curl -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": "public class HelloWorld { public static void main(String[] args) { System.out.println(\"Hello, World!\"); } }"
  }' | python -m json.tool