@echo off
setlocal

:: Get the current directory as the base path
set "BASE_PATH=%CD%"

:: Run the mcpo.exe with the config file
"%BASE_PATH%\mcpo.exe" --config "%BASE_PATH%\mcp.json"

echo Command executed successfully!
endlocal