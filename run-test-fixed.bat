@echo off
setlocal enabledelayedexpansion

echo ===================================
echo Test Runner Script
echo ===================================
echo.

REM Set Java home to the specified JDK
set "JAVA_HOME=C:\Users\LAP14364-local\jdks\jbr-17.0.14"
echo Using Java home: %JAVA_HOME%

REM Check if Java home directory exists
if not exist "%JAVA_HOME%" (
    echo ERROR: The specified JAVA_HOME directory does not exist.
    echo Please check the path: %JAVA_HOME%
    goto :error
)

REM Check if Java executable exists
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Java executable not found at %JAVA_HOME%\bin\java.exe
    goto :error
)

REM Display Java version
echo.
echo Java version:
"%JAVA_HOME%\bin\java" -version
echo.

REM Change to project directory
cd %~dp0
echo Current directory: %CD%
echo.

REM Check if gradlew exists
if not exist "gradlew.bat" (
    echo ERROR: gradlew.bat not found in the current directory.
    goto :error
)

echo Running McpToolAdapterTest...
echo.

REM Run the test with Gradle, using environment variable to set JAVA_HOME
set "_JAVA_HOME=%JAVA_HOME%"
call gradlew.bat test --tests "com.zps.zest.mcp.McpToolAdapterTest" --info --stacktrace --no-daemon

REM Check if the test succeeded
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo TEST EXECUTION FAILED with exit code %ERRORLEVEL%
    goto :error
) else (
    echo.
    echo TEST EXECUTION COMPLETED SUCCESSFULLY
    goto :end
)

:error
echo.
echo Press any key to exit...
pause >nul
exit /b 1

:end
echo.
echo Press any key to exit...
pause >nul
exit /b 0
