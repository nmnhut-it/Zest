import os
import asyncio
import json
from typing import Optional, Dict, Any, List
from fastapi import FastAPI, HTTPException, Depends, Security
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import httpx
from contextlib import asynccontextmanager
import logging

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Security
security = HTTPBearer()

# Configuration
PROXY_BASE_URL = os.getenv("ZEST_PROXY_URL", "http://localhost:8765")
API_KEY = os.getenv("OPENAI_TOOL_SERVER_API_KEY", "")
PROXY_TIMEOUT = 3600  # 1 hour for long operations

# Global client
http_client: Optional[httpx.AsyncClient] = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle"""
    global http_client
    # Startup
    http_client = httpx.AsyncClient(timeout=PROXY_TIMEOUT)
    logger.info("Starting OpenAI Tool Server for Zest")
    
    # Try to find proxy
    proxy_found = False
    for port in range(8765, 8776):
        try:
            response = await http_client.get(f"http://localhost:{port}/health", timeout=2.0)
            if response.status_code == 200:
                data = response.json()
                if data.get("service") == "agent-proxy":
                    global PROXY_BASE_URL
                    PROXY_BASE_URL = f"http://localhost:{port}"
                    proxy_found = True
                    logger.info(f"Found Zest Agent Proxy on port {port}")
                    logger.info(f"Project: {data.get('project')}")
                    break
        except Exception:
            continue
    
    if not proxy_found:
        logger.warning("No Zest Agent Proxy found. Please start it from IntelliJ IDEA.")
    
    yield
    
    # Shutdown
    await http_client.aclose()
    logger.info("Shutting down OpenAI Tool Server")


# Create FastAPI app
app = FastAPI(
    title="Zest Code Explorer - OpenAI Tool Server",
    description="OpenAPI-compliant server for IntelliJ code exploration through Zest",
    version="1.0.0",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Authentication dependency
async def verify_api_key(credentials: HTTPAuthorizationCredentials = Security(security)):
    """Verify API key if configured"""
    if API_KEY and credentials.credentials != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")
    return credentials.credentials


# Request/Response models
class ExploreCodeRequest(BaseModel):
    query: str = Field(..., description="Natural language query about the code")
    generate_report: bool = Field(False, description="Generate detailed report")
    config: Optional[Dict[str, Any]] = Field(None, description="Configuration overrides")


class ExploreCodeResponse(BaseModel):
    success: bool
    summary: str
    report: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class ExecuteToolRequest(BaseModel):
    tool: str = Field(..., description="Name of the tool to execute")
    parameters: Dict[str, Any] = Field(..., description="Tool-specific parameters")


class ExecuteToolResponse(BaseModel):
    success: bool
    content: str
    error: Optional[str] = None


class SearchCodeRequest(BaseModel):
    query: str = Field(..., description="Natural language search query")
    max_results: Optional[int] = Field(10, description="Maximum results")


class FindByNameRequest(BaseModel):
    name: str = Field(..., description="Class, method, or package name (case-sensitive)")
    type: Optional[str] = Field("any", description="Type: class, method, package, or any")


class ReadFileRequest(BaseModel):
    file_path: str = Field(..., description="Path to the file to read")


class FindRelationshipsRequest(BaseModel):
    element_id: str = Field(..., description="Fully qualified class name")
    relation_type: Optional[str] = Field(None, description="Type of relationship to find")


class FindUsagesRequest(BaseModel):
    element_id: str = Field(..., description="Class or method to find usages of")


class GetClassInfoRequest(BaseModel):
    class_name: str = Field(..., description="Fully qualified class name")


class AugmentQueryRequest(BaseModel):
    query: str = Field(..., description="User query to augment with code context")


class ConfigUpdateRequest(BaseModel):
    max_tool_calls: Optional[int] = None
    max_rounds: Optional[int] = None
    include_tests: Optional[bool] = None
    deep_exploration: Optional[bool] = None
    timeout_seconds: Optional[int] = None


# Helper function for proxy requests
async def proxy_request(endpoint: str, method: str = "GET", json_data: Optional[Dict] = None) -> Dict[str, Any]:
    """Make request to Zest Agent Proxy"""
    if not http_client:
        raise HTTPException(status_code=500, detail="HTTP client not initialized")
    
    try:
        if method == "GET":
            response = await http_client.get(f"{PROXY_BASE_URL}{endpoint}")
        elif method == "POST":
            response = await http_client.post(f"{PROXY_BASE_URL}{endpoint}", json=json_data)
        else:
            raise ValueError(f"Unsupported method: {method}")
        
        response.raise_for_status()
        return response.json()
    except httpx.HTTPStatusError as e:
        raise HTTPException(status_code=e.response.status_code, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Proxy request failed: {str(e)}")


# API Endpoints
@app.get("/health")
async def health_check():
    """Check server health and proxy connection"""
    try:
        proxy_health = await proxy_request("/health")
        return {
            "status": "healthy",
            "proxy_connected": True,
            "proxy_info": proxy_health
        }
    except Exception:
        return {
            "status": "healthy",
            "proxy_connected": False,
            "proxy_info": None
        }


@app.post("/explore", response_model=ExploreCodeResponse)
async def explore_code(
    request: ExploreCodeRequest,
    api_key: str = Depends(verify_api_key)
):
    """Explore code with a natural language query"""
    try:
        result = await proxy_request("/explore", "POST", request.dict())
        return ExploreCodeResponse(**result)
    except HTTPException:
        raise
    except Exception as e:
        return ExploreCodeResponse(
            success=False,
            summary="",
            error=str(e)
        )


@app.post("/execute-tool", response_model=ExecuteToolResponse)
async def execute_tool(
    request: ExecuteToolRequest,
    api_key: str = Depends(verify_api_key)
):
    """Execute a specific exploration tool"""
    try:
        result = await proxy_request("/execute-tool", "POST", request.dict())
        return ExecuteToolResponse(**result)
    except HTTPException:
        raise
    except Exception as e:
        return ExecuteToolResponse(
            success=False,
            content="",
            error=str(e)
        )


@app.get("/tools")
async def list_tools(api_key: str = Depends(verify_api_key)):
    """List all available exploration tools"""
    return await proxy_request("/tools")


# Convenience endpoints for common tools
@app.post("/search")
async def search_code(
    request: SearchCodeRequest,
    api_key: str = Depends(verify_api_key)
):
    """Search for code using natural language"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="search_code",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/find-by-name")
async def find_by_name(
    request: FindByNameRequest,
    api_key: str = Depends(verify_api_key)
):
    """Find code elements by name"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="find_by_name",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/read-file")
async def read_file(
    request: ReadFileRequest,
    api_key: str = Depends(verify_api_key)
):
    """Read a file from the project"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="read_file",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/find-relationships")
async def find_relationships(
    request: FindRelationshipsRequest,
    api_key: str = Depends(verify_api_key)
):
    """Find relationships between code elements"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="find_relationships",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/find-usages")
async def find_usages(
    request: FindUsagesRequest,
    api_key: str = Depends(verify_api_key)
):
    """Find usages of a code element"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="find_usages",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/class-info")
async def get_class_info(
    request: GetClassInfoRequest,
    api_key: str = Depends(verify_api_key)
):
    """Get detailed information about a class"""
    return await execute_tool(
        ExecuteToolRequest(
            tool="get_class_info",
            parameters=request.dict()
        ),
        api_key
    )


@app.post("/augment")
async def augment_query(
    request: AugmentQueryRequest,
    api_key: str = Depends(verify_api_key)
):
    """Augment a query with code context"""
    return await proxy_request("/augment", "POST", request.dict())


@app.get("/status")
async def get_status(api_key: str = Depends(verify_api_key)):
    """Get current proxy status"""
    return await proxy_request("/status")


@app.get("/config")
async def get_config(api_key: str = Depends(verify_api_key)):
    """Get current proxy configuration"""
    return await proxy_request("/config")


@app.post("/config")
async def update_config(
    request: ConfigUpdateRequest,
    api_key: str = Depends(verify_api_key)
):
    """Update proxy configuration"""
    return await proxy_request("/config", "POST", request.dict(exclude_none=True))


# OpenAPI schema customization
@app.get("/openapi.json")
async def get_openapi():
    """Get OpenAPI schema with tool definitions"""
    openapi_schema = app.openapi()
    
    # Add x-openai extensions for better tool integration
    for path, methods in openapi_schema["paths"].items():
        for method, operation in methods.items():
            if method in ["post", "get"]:
                # Add OpenAI function calling hints
                operation["x-openai-tool"] = {
                    "type": "function",
                    "function": {
                        "name": operation.get("operationId", path.replace("/", "_")),
                        "description": operation.get("summary", ""),
                        "parameters": operation.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema", {})
                    }
                }
    
    return openapi_schema


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
