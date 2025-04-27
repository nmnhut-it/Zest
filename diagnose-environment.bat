@echo off
setlocal enabledelayedexpansion

echo ===================================
echo Environment Diagnostics Script
echo ===================================
echo.

REM Set Java home to the specified JDK
set "JAVA_HOME=C:\Users\LAP14364-local\jdks\jbr-17.0.14"
echo Using Java home: %JAVA_HOME%

REM Check Java installation
echo.
echo Checking Java installation...
if exist "%JAVA_HOME%" (
    echo [OK] JAVA_HOME directory exists
) else (
    echo [ERROR] JAVA_HOME directory does not exist: %JAVA_HOME%
)

if exist "%JAVA_HOME%\bin\java.exe" (
    echo [OK] Java executable found
    echo Java version:
    "%JAVA_HOME%\bin\java" -version 2>&1
) else (
    echo [ERROR] Java executable not found at %JAVA_HOME%\bin\java.exe
)

REM Check PATH environment variable
echo.
echo Checking if Java is in PATH...
where java 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [OK] Java found in PATH
    echo Default Java version:
    java -version 2>&1
) else (
    echo [WARNING] Java not found in PATH
)

REM Check Gradle installation
echo.
echo Checking Gradle installation...
cd %~dp0
if exist "gradlew.bat" (
    echo [OK] Gradle wrapper (gradlew.bat) found in project directory
) else (
    echo [ERROR] Gradle wrapper (gradlew.bat) not found in project directory
)

REM Check project structure
echo.
echo Checking project structure...
if exist "build.gradle.kts" (
    echo [OK] build.gradle.kts found
) else (
    echo [ERROR] build.gradle.kts not found
)

if exist "src\test\java\com\zps\zest\mcp\McpToolAdapterTest.java" (
    echo [OK] McpToolAdapterTest.java found in expected location
) else (
    echo [ERROR] McpToolAdapterTest.java not found in expected location
)

REM Check build directory
echo.
echo Checking build directory...
if exist "build" (
    echo [OK] Build directory exists
    
    if exist "build\classes\java\test\com\zps\zest\mcp" (
        echo [OK] Test classes directory exists
        echo Test class files found:
        dir "build\classes\java\test\com\zps\zest\mcp" /B 2>nul
    ) else (
        echo [WARNING] Test classes directory not found or empty
    )
) else (
    echo [INFO] Build directory not found (project not built yet)
)

REM Check if test class is compiled
echo.
echo Checking if test class is compiled...
if exist "build\classes\java\test\com\zps\zest\mcp\McpToolAdapterTest.class" (
    echo [OK] McpToolAdapterTest.class is compiled
) else (
    echo [WARNING] McpToolAdapterTest.class is not compiled
)

REM Check Gradle project tasks
echo.
echo Gradle project tasks (this may take a moment)...
set "_JAVA_HOME=%JAVA_HOME%"
call gradlew.bat tasks --group="verification" --quiet

echo.
echo ===================================
echo Diagnostics Complete
echo ===================================
echo.
echo Press any key to exit...
pause >nul
