#!/bin/bash

# Test embedding generation with the Zest embedding service
# Using the litellm.zingplay.com endpoint with Qwen3-Embedding model

ENDPOINT="https://litellm.zingplay.com/v1/embeddings"
API_KEY="sk-0c1l7KCScBLmcYDN-Oszmg"

echo "========================================="
echo "Testing Embedding Service"
echo "Endpoint: $ENDPOINT"
echo "Model: Qwen3-Embedding-0.6B"
echo "========================================="
echo ""

# Test 1: Simple text embedding
echo "Test 1: Simple text embedding"
echo "------------------------------"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": "This is a test text for embedding generation"
  }' | head -c 200
echo "..."
echo ""

# Test 2: Code embedding
echo "Test 2: Code embedding"
echo "----------------------"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": "public void testMethod() { System.out.println(\"Hello\"); }"
  }' | head -c 200
echo "..."
echo ""

# Test 3: Batch embeddings
echo "Test 3: Batch embeddings (3 texts)"
echo "----------------------------------"
curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": [
      "First text to embed",
      "Second text to embed",
      "Third text to embed"
    ]
  }' | grep -o '"index":[0-9]*' | head -3
echo ""

# Test 4: Check embedding dimensions
echo "Test 4: Checking embedding dimensions"
echo "-------------------------------------"
RESPONSE=$(curl -s -X POST "$ENDPOINT" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{
    "model": "Qwen3-Embedding-0.6B",
    "input": "Test"
  }')

# Count the number of float values in the embedding array
DIMENSION_COUNT=$(echo "$RESPONSE" | grep -o '\-\?[0-9]*\.[0-9]*' | wc -l)
echo "Embedding dimension: $DIMENSION_COUNT"
echo ""

echo "========================================="
echo "All tests completed!"
echo "========================================="