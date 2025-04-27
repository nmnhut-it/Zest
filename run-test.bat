@echo off
echo Running McpToolAdapterTest specifically...
cd %~dp0
gradlew.bat test --tests "com.zps.zest.mcp.McpToolAdapterTest" --info
pause
