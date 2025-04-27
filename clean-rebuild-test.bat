@echo off
setlocal enabledelayedexpansion

echo ===================================
echo Clean, Rebuild, and Test Script
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

REM Clean the project
echo Cleaning project...
set "_JAVA_HOME=%JAVA_HOME%"
call gradlew.bat clean --info --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to clean the project.
    goto :error
)
echo Clean completed successfully.
echo.

REM Compile the test classes specifically
echo Compiling test classes...
set "_JAVA_HOME=%JAVA_HOME%"
call gradlew.bat compileTestJava --info --no-daemon
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Failed to compile test classes.
    goto :error
)
echo Compilation completed successfully.
echo.

REM Run the test
echo Running McpToolAdapterTest...
echo.
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
