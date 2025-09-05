@echo off
setlocal enabledelayedexpansion

REM Zest Embedding Server Startup Script for Windows
REM This script starts Ollama embedding server and pulls the required model

echo 🚀 Starting Zest Embedding Server (Ollama)...

REM Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    echo ❌ Docker is not running. Please start Docker and try again.
    exit /b 1
)

REM Start the services
echo 📦 Starting Ollama container on port 11435...
docker-compose up -d

REM Wait for Ollama to be ready
echo ⏳ Waiting for Ollama to be ready...
set /a count=0
:wait_loop
set /a count+=1
if !count! gtr 30 (
    echo ❌ Timeout waiting for Ollama to start
    exit /b 1
)

curl -sf http://localhost:11435/api/version >nul 2>&1
if !errorlevel! equ 0 (
    echo ✅ Ollama is ready!
    goto :ready
)

echo    Attempt !count!/30...
timeout /t 2 /nobreak >nul
goto :wait_loop

:ready
REM Pull the embedding model
echo 📥 Pulling all-minilm embedding model...
docker exec zest-embedding-ollama ollama pull all-minilm

REM Verify the model is available
echo 🔍 Verifying model availability...
docker exec zest-embedding-ollama ollama list | findstr all-minilm

echo.
echo 🎉 Zest Embedding Server is ready!
echo 📍 API Endpoint: http://localhost:11435/api/embeddings
echo.
echo Test with:
echo curl -X POST http://localhost:11435/api/embeddings ^
echo   -H "Content-Type: application/json" ^
echo   -d "{\"model\": \"all-minilm\", \"prompt\": \"Hello world\"}"
echo.
echo To stop: docker-compose down

pause