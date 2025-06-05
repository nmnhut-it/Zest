"""
Example of integrating the Zest OpenAI Tool Server with OpenAI's function calling
"""

import os
import httpx
from openai import OpenAI
from typing import Dict, Any
import json

# Configuration
TOOL_SERVER_URL = "http://localhost:8000"
TOOL_SERVER_API_KEY = os.getenv("OPENAI_TOOL_SERVER_API_KEY", "")


class ZestToolClient:
    """Client for interacting with Zest Tool Server"""
    
    def __init__(self, base_url: str = TOOL_SERVER_URL, api_key: str = TOOL_SERVER_API_KEY):
        self.client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {}
        )
    
    def explore_code(self, query: str, generate_report: bool = False) -> Dict[str, Any]:
        """Explore code with a natural language query"""
        response = self.client.post("/explore", json={
            "query": query,
            "generate_report": generate_report
        })
        response.raise_for_status()
        return response.json()
    
    def search_code(self, query: str, max_results: int = 10) -> Dict[str, Any]:
        """Search for code"""
        response = self.client.post("/search", json={
            "query": query,
            "max_results": max_results
        })
        response.raise_for_status()
        return response.json()
    
    def find_by_name(self, name: str, type: str = "any") -> Dict[str, Any]:
        """Find code elements by name"""
        response = self.client.post("/find-by-name", json={
            "name": name,
            "type": type
        })
        response.raise_for_status()
        return response.json()
    
    def read_file(self, file_path: str) -> Dict[str, Any]:
        """Read a file"""
        response = self.client.post("/read-file", json={
            "file_path": file_path
        })
        response.raise_for_status()
        return response.json()


# Define OpenAI function schemas
ZEST_FUNCTIONS = [
    {
        "type": "function",
        "function": {
            "name": "explore_code",
            "description": "Explore and understand code using natural language queries. Use this for complex questions about code architecture, functionality, or implementation details.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Natural language question about the code"
                    },
                    "generate_report": {
                        "type": "boolean",
                        "description": "Whether to generate a detailed report",
                        "default": False
                    }
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_code",
            "description": "Search for specific code elements, patterns, or implementations",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Search query"
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Maximum number of results",
                        "default": 10
                    }
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "find_by_name",
            "description": "Find classes, methods, or packages by exact name",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name to search for (case-sensitive)"
                    },
                    "type": {
                        "type": "string",
                        "enum": ["class", "method", "package", "any"],
                        "description": "Type of element to find",
                        "default": "any"
                    }
                },
                "required": ["name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a specific file",
            "parameters": {
                "type": "object",
                "properties": {
                    "file_path": {
                        "type": "string",
                        "description": "Path to the file to read"
                    }
                },
                "required": ["file_path"]
            }
        }
    }
]


def main():
    """Example usage of Zest tools with OpenAI"""
    
    # Initialize clients
    zest_client = ZestToolClient()
    openai_client = OpenAI()
    
    # Function to handle tool calls
    def handle_tool_call(tool_call):
        function_name = tool_call.function.name
        arguments = json.loads(tool_call.function.arguments)
        
        print(f"\n🔧 Calling {function_name} with args: {arguments}")
        
        try:
            if function_name == "explore_code":
                result = zest_client.explore_code(**arguments)
            elif function_name == "search_code":
                result = zest_client.search_code(**arguments)
            elif function_name == "find_by_name":
                result = zest_client.find_by_name(**arguments)
            elif function_name == "read_file":
                result = zest_client.read_file(**arguments)
            else:
                result = {"error": f"Unknown function: {function_name}"}
            
            # Extract the relevant content
            if "content" in result:
                return result["content"]
            elif "summary" in result:
                return result["summary"]
            else:
                return json.dumps(result, indent=2)
                
        except Exception as e:
            return f"Error: {str(e)}"
    
    # Example conversation
    messages = [
        {"role": "system", "content": "You are a helpful code analysis assistant with access to a codebase through Zest tools."},
        {"role": "user", "content": "Can you help me understand how the authentication system works in this codebase? Find the main authentication classes and explain the flow."}
    ]
    
    print("🤖 Starting conversation with OpenAI + Zest tools...\n")
    
    # Initial request
    response = openai_client.chat.completions.create(
        model="gpt-4",
        messages=messages,
        tools=ZEST_FUNCTIONS,
        tool_choice="auto"
    )
    
    # Process the response
    assistant_message = response.choices[0].message
    messages.append(assistant_message)
    
    # Handle tool calls if any
    if assistant_message.tool_calls:
        print("🔍 Assistant is exploring the codebase...\n")
        
        for tool_call in assistant_message.tool_calls:
            # Execute the tool
            result = handle_tool_call(tool_call)
            
            # Add tool response to conversation
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "content": result
            })
        
        # Get final response from assistant
        final_response = openai_client.chat.completions.create(
            model="gpt-4",
            messages=messages
        )
        
        print("\n📝 Assistant's analysis:")
        print(final_response.choices[0].message.content)
    else:
        print("\n📝 Assistant's response:")
        print(assistant_message.content)


if __name__ == "__main__":
    main()
