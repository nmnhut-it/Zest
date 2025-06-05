"""
Example of direct API usage with the Zest OpenAI Tool Server
"""

import httpx
import json
from typing import Dict, Any, Optional


class ZestAPIClient:
    """Direct API client for Zest Tool Server"""
    
    def __init__(self, base_url: str = "http://localhost:8000", api_key: Optional[str] = None):
        headers = {}
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        
        self.client = httpx.Client(
            base_url=base_url,
            headers=headers,
            timeout=300.0
        )
    
    def health_check(self) -> Dict[str, Any]:
        """Check server health"""
        response = self.client.get("/health")
        return response.json()
    
    def explore_code(self, query: str, generate_report: bool = False, 
                    config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Explore code with natural language"""
        payload = {
            "query": query,
            "generate_report": generate_report
        }
        if config:
            payload["config"] = config
        
        response = self.client.post("/explore", json=payload)
        return response.json()
    
    def search_code(self, query: str, max_results: int = 10) -> Dict[str, Any]:
        """Search for code"""
        response = self.client.post("/search", json={
            "query": query,
            "max_results": max_results
        })
        return response.json()
    
    def find_by_name(self, name: str, type: str = "any") -> Dict[str, Any]:
        """Find elements by name"""
        response = self.client.post("/find-by-name", json={
            "name": name,
            "type": type
        })
        return response.json()
    
    def read_file(self, file_path: str) -> Dict[str, Any]:
        """Read file contents"""
        response = self.client.post("/read-file", json={
            "file_path": file_path
        })
        return response.json()
    
    def find_relationships(self, element_id: str, 
                          relation_type: Optional[str] = None) -> Dict[str, Any]:
        """Find relationships between code elements"""
        payload = {"element_id": element_id}
        if relation_type:
            payload["relation_type"] = relation_type
        
        response = self.client.post("/find-relationships", json=payload)
        return response.json()
    
    def find_usages(self, element_id: str) -> Dict[str, Any]:
        """Find usages of an element"""
        response = self.client.post("/find-usages", json={
            "element_id": element_id
        })
        return response.json()
    
    def get_class_info(self, class_name: str) -> Dict[str, Any]:
        """Get detailed class information"""
        response = self.client.post("/class-info", json={
            "class_name": class_name
        })
        return response.json()
    
    def list_tools(self) -> Dict[str, Any]:
        """List all available tools"""
        response = self.client.get("/tools")
        return response.json()
    
    def execute_tool(self, tool: str, parameters: Dict[str, Any]) -> Dict[str, Any]:
        """Execute a specific tool"""
        response = self.client.post("/execute-tool", json={
            "tool": tool,
            "parameters": parameters
        })
        return response.json()
    
    def get_status(self) -> Dict[str, Any]:
        """Get proxy status"""
        response = self.client.get("/status")
        return response.json()
    
    def get_config(self) -> Dict[str, Any]:
        """Get current configuration"""
        response = self.client.get("/config")
        return response.json()
    
    def update_config(self, **kwargs) -> Dict[str, Any]:
        """Update configuration"""
        response = self.client.post("/config", json=kwargs)
        return response.json()
    
    def close(self):
        """Close the client"""
        self.client.close()


def print_result(title: str, result: Dict[str, Any]):
    """Pretty print results"""
    print(f"\n{'='*60}")
    print(f"📌 {title}")
    print('='*60)
    
    if result.get("success"):
        if "content" in result:
            print(result["content"])
        elif "summary" in result:
            print(result["summary"])
        else:
            print(json.dumps(result, indent=2))
    else:
        print(f"❌ Error: {result.get('error', 'Unknown error')}")


def main():
    """Demonstrate various API calls"""
    
    # Initialize client
    client = ZestAPIClient()
    
    try:
        # 1. Health check
        print("🏥 Checking server health...")
        health = client.health_check()
        print(f"Status: {health['status']}")
        print(f"Proxy connected: {health['proxy_connected']}")
        
        if not health['proxy_connected']:
            print("\n⚠️  Warning: Proxy not connected. Please start Zest Agent Proxy in IntelliJ.")
            return
        
        # 2. List available tools
        print("\n🔧 Available tools:")
        tools = client.list_tools()
        print(f"Total tools: {tools['count']}")
        for tool in tools['tools'][:5]:  # Show first 5
            print(f"  - {tool['name']}: {tool['description']}")
        
        # 3. Explore code architecture
        result = client.explore_code(
            "What is the overall architecture of this project?",
            generate_report=True
        )
        print_result("Project Architecture", result)
        
        # 4. Search for specific patterns
        result = client.search_code("REST endpoints", max_results=5)
        print_result("REST Endpoints", result)
        
        # 5. Find a specific class
        result = client.find_by_name("Controller", type="class")
        print_result("Controller Classes", result)
        
        # 6. Get configuration
        config = client.get_config()
        print(f"\n⚙️  Current Configuration:")
        print(json.dumps(config, indent=2))
        
        # 7. Execute a custom tool
        result = client.execute_tool("find_methods", {
            "pattern": "get.*",
            "includePrivate": False
        })
        print_result("Getter Methods", result)
        
    except httpx.HTTPError as e:
        print(f"\n❌ HTTP Error: {e}")
    except Exception as e:
        print(f"\n❌ Error: {e}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
