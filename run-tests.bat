@echo off
REM Automated test runner for Zest completion service
REM This script runs all completion service tests using the updated build.gradle.kts

echo =================================
echo Zest Completion Service Test Suite
echo =================================

REM Set Java environment to use IntelliJ's JBR
set "JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.1.1.1\jbr"

echo Using JBR: %JAVA_HOME%
echo.

REM Clean previous test results
echo Cleaning previous test results...
if exist "build\reports\tests" rmdir /s /q "build\reports\tests"

REM Run different test categories
echo.
echo [1/4] Running Completion Service Tests...
call gradlew testCompletion --console=plain --info --rerun-tasks

echo.
echo [2/4] Running IntelliJ Integration Tests...
call gradlew testIntellij --console=plain --info --rerun-tasks

echo.
echo [3/4] Running Performance Tests...
call gradlew testPerformance --console=plain --info --rerun-tasks

echo.
echo [4/4] Running All Tests...
call gradlew test --console=plain --info --rerun-tasks

REM Show summary
echo.
echo =================================
echo Test Summary
echo =================================

if %ERRORLEVEL% EQU 0 (
    echo ✅ All tests completed successfully!
    echo.
    echo Available test commands:
    echo - gradlew testCompletion     (Completion service only)
    echo - gradlew testIntellij       (IntelliJ integration)
    echo - gradlew testPerformance    (Performance tests)
    echo - gradlew checkCompletion    (All completion tests)
    echo - gradlew test              (All tests)
) else (
    echo ❌ Some tests failed. Check the output above.
    echo.
    echo Debugging:
    echo - Check build\reports\tests\test\index.html for details
    echo - Run individual test categories for specific output
)

echo.
echo Test run completed at %date% %time%
pause