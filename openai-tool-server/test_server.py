"""
Test script for the Zest OpenAI Tool Server
"""

import httpx
import json
import time
from typing import Dict, Any


def test_endpoint(client: httpx.Client, method: str, endpoint: str, 
                 json_data: Dict[str, Any] = None, expected_status: int = 200):
    """Test a single endpoint"""
    print(f"\n🧪 Testing {method} {endpoint}")
    
    try:
        if method == "GET":
            response = client.get(endpoint)
        elif method == "POST":
            response = client.post(endpoint, json=json_data)
        else:
            raise ValueError(f"Unsupported method: {method}")
        
        print(f"   Status: {response.status_code} {'✅' if response.status_code == expected_status else '❌'}")
        
        if response.status_code == 200:
            data = response.json()
            print(f"   Response: {json.dumps(data, indent=2)[:200]}...")
            return True, data
        else:
            print(f"   Error: {response.text}")
            return False, None
            
    except Exception as e:
        print(f"   ❌ Exception: {str(e)}")
        return False, None


def run_tests(base_url: str = "http://localhost:8000", api_key: str = ""):
    """Run all tests"""
    
    print("🚀 Starting Zest OpenAI Tool Server Tests")
    print(f"   Server: {base_url}")
    print(f"   API Key: {'Set' if api_key else 'Not set'}")
    
    # Create client
    headers = {}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    
    client = httpx.Client(base_url=base_url, headers=headers, timeout=30.0)
    
    try:
        # Test health endpoint
        success, health = test_endpoint(client, "GET", "/health")
        if not success:
            print("\n❌ Server not responding. Is it running?")
            return
        
        proxy_connected = health.get("proxy_connected", False)
        if not proxy_connected:
            print("\n⚠️  Warning: Proxy not connected. Some tests may fail.")
        
        # Test OpenAPI schema
        test_endpoint(client, "GET", "/openapi.json")
        
        # Test tools listing
        test_endpoint(client, "GET", "/tools")
        
        # Test status
        test_endpoint(client, "GET", "/status")
        
        # Test config
        test_endpoint(client, "GET", "/config")
        
        if proxy_connected:
            # Test explore
            test_endpoint(client, "POST", "/explore", {
                "query": "What programming languages are used in this project?",
                "generate_report": False
            })
            
            # Test search
            test_endpoint(client, "POST", "/search", {
                "query": "main method",
                "max_results": 3
            })
            
            # Test find by name
            test_endpoint(client, "POST", "/find-by-name", {
                "name": "Main",
                "type": "class"
            })
            
            # Test augment
            test_endpoint(client, "POST", "/augment", {
                "query": "How does authentication work?"
            })
            
            # Test execute tool
            test_endpoint(client, "POST", "/execute-tool", {
                "tool": "list_files",
                "parameters": {"directory": "/"}
            })
        
        print("\n✅ All tests completed!")
        
    finally:
        client.close()


def performance_test(base_url: str = "http://localhost:8000", api_key: str = ""):
    """Run performance tests"""
    
    print("\n⚡ Running Performance Tests")
    
    headers = {}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    
    with httpx.Client(base_url=base_url, headers=headers, timeout=300.0) as client:
        # Test concurrent requests
        print("\n📊 Testing concurrent requests...")
        
        queries = [
            "What is the main purpose of this application?",
            "Find all database models",
            "List all API endpoints",
            "What design patterns are used?",
            "Find security-related code"
        ]
        
        start_time = time.time()
        
        for i, query in enumerate(queries):
            print(f"   Query {i+1}: {query[:50]}...")
            response = client.post("/search", json={"query": query, "max_results": 5})
            print(f"   Status: {response.status_code}")
        
        elapsed = time.time() - start_time
        print(f"\n   Total time: {elapsed:.2f}s")
        print(f"   Average per query: {elapsed/len(queries):.2f}s")


if __name__ == "__main__":
    import argparse
    
    parser = argparse.ArgumentParser(description="Test Zest OpenAI Tool Server")
    parser.add_argument("--url", default="http://localhost:8000", help="Server URL")
    parser.add_argument("--api-key", default="", help="API key for authentication")
    parser.add_argument("--performance", action="store_true", help="Run performance tests")
    
    args = parser.parse_args()
    
    # Run basic tests
    run_tests(args.url, args.api_key)
    
    # Run performance tests if requested
    if args.performance:
        performance_test(args.url, args.api_key)
