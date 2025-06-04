"""
Example of integrating the Zest OpenAI Tool Server with LangChain
"""

import os
from typing import Dict, Any, Optional
from langchain.tools import BaseTool, StructuredTool
from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain.chat_models import ChatOpenAI
from langchain.prompts import ChatPromptTemplate, MessagesPlaceholder
from pydantic import BaseModel, Field
import httpx


# Configuration
TOOL_SERVER_URL = "http://localhost:8000"
TOOL_SERVER_API_KEY = os.getenv("OPENAI_TOOL_SERVER_API_KEY", "")


class ZestToolClient:
    """Client for Zest Tool Server"""
    
    def __init__(self, base_url: str = TOOL_SERVER_URL, api_key: str = TOOL_SERVER_API_KEY):
        self.client = httpx.Client(
            base_url=base_url,
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
            timeout=300.0  # 5 minutes for long operations
        )
    
    def call_endpoint(self, endpoint: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """Generic method to call any endpoint"""
        response = self.client.post(endpoint, json=data)
        response.raise_for_status()
        return response.json()


# Define input schemas for tools
class ExploreCodeInput(BaseModel):
    query: str = Field(description="Natural language question about the code")
    generate_report: bool = Field(default=False, description="Generate detailed report")


class SearchCodeInput(BaseModel):
    query: str = Field(description="Search query for finding code")
    max_results: int = Field(default=10, description="Maximum number of results")


class FindByNameInput(BaseModel):
    name: str = Field(description="Name to search for (case-sensitive)")
    type: str = Field(default="any", description="Type: class, method, package, or any")


class ReadFileInput(BaseModel):
    file_path: str = Field(description="Path to the file to read")


# Create LangChain tools
def create_zest_tools(client: ZestToolClient) -> list:
    """Create LangChain tools for Zest operations"""
    
    def explore_code_func(query: str, generate_report: bool = False) -> str:
        """Explore and understand code using natural language queries"""
        result = client.call_endpoint("/explore", {
            "query": query,
            "generate_report": generate_report
        })
        if result.get("success"):
            return result.get("summary", "No summary available")
        return f"Error: {result.get('error', 'Unknown error')}"
    
    def search_code_func(query: str, max_results: int = 10) -> str:
        """Search for specific code elements or patterns"""
        result = client.call_endpoint("/search", {
            "query": query,
            "max_results": max_results
        })
        if result.get("success"):
            return result.get("content", "No results found")
        return f"Error: {result.get('error', 'Unknown error')}"
    
    def find_by_name_func(name: str, type: str = "any") -> str:
        """Find classes, methods, or packages by exact name"""
        result = client.call_endpoint("/find-by-name", {
            "name": name,
            "type": type
        })
        if result.get("success"):
            return result.get("content", "No results found")
        return f"Error: {result.get('error', 'Unknown error')}"
    
    def read_file_func(file_path: str) -> str:
        """Read the contents of a specific file"""
        result = client.call_endpoint("/read-file", {
            "file_path": file_path
        })
        if result.get("success"):
            return result.get("content", "File is empty")
        return f"Error: {result.get('error', 'Unknown error')}"
    
    # Create structured tools
    tools = [
        StructuredTool(
            name="explore_code",
            func=explore_code_func,
            description="Explore and understand code architecture, functionality, or implementation details using natural language",
            args_schema=ExploreCodeInput
        ),
        StructuredTool(
            name="search_code",
            func=search_code_func,
            description="Search for specific code elements, patterns, or implementations",
            args_schema=SearchCodeInput
        ),
        StructuredTool(
            name="find_by_name",
            func=find_by_name_func,
            description="Find classes, methods, or packages by their exact name",
            args_schema=FindByNameInput
        ),
        StructuredTool(
            name="read_file",
            func=read_file_func,
            description="Read the contents of a specific file in the codebase",
            args_schema=ReadFileInput
        )
    ]
    
    return tools


def create_code_analysis_agent():
    """Create a LangChain agent with Zest tools"""
    
    # Initialize Zest client
    zest_client = ZestToolClient()
    
    # Create tools
    tools = create_zest_tools(zest_client)
    
    # Create prompt
    prompt = ChatPromptTemplate.from_messages([
        ("system", """You are an expert code analysis assistant with access to a codebase through Zest tools.
        
Available tools:
- explore_code: For understanding code architecture and answering complex questions
- search_code: For finding specific implementations or patterns
- find_by_name: For locating specific classes, methods, or packages
- read_file: For examining file contents

Always start with exploration or search to understand the codebase structure before diving into specific files."""),
        ("human", "{input}"),
        MessagesPlaceholder(variable_name="agent_scratchpad"),
    ])
    
    # Create LLM
    llm = ChatOpenAI(
        model="gpt-4",
        temperature=0,
        streaming=True
    )
    
    # Create agent
    agent = create_openai_tools_agent(llm, tools, prompt)
    
    # Create executor
    agent_executor = AgentExecutor(
        agent=agent,
        tools=tools,
        verbose=True,
        handle_parsing_errors=True,
        max_iterations=10
    )
    
    return agent_executor


def main():
    """Example usage"""
    
    # Create agent
    agent = create_code_analysis_agent()
    
    # Example queries
    queries = [
        "How does the authentication system work in this codebase? Find the main components and explain the flow.",
        "Find all REST API endpoints and summarize what each one does.",
        "What design patterns are used in this project? Show me examples.",
        "Analyze the database layer. How is data persistence handled?"
    ]
    
    print("🤖 LangChain + Zest Code Analysis Agent\n")
    
    for query in queries[:1]:  # Run first query as example
        print(f"\n❓ Query: {query}\n")
        print("=" * 80)
        
        try:
            result = agent.invoke({"input": query})
            print(f"\n📝 Answer:\n{result['output']}")
        except Exception as e:
            print(f"\n❌ Error: {str(e)}")
        
        print("\n" + "=" * 80)


if __name__ == "__main__":
    main()
