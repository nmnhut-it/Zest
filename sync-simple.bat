@echo off
setlocal enabledelayedexpansion

REM Extract version from build.gradle.kts
for /f "tokens=3 delims= " %%a in ('findstr /c:"version = " "build.gradle.kts"') do (
    set "VERSION_RAW=%%a"
    set "VERSION_RAW=!VERSION_RAW:"=!"
    set "VERSION=!VERSION_RAW!"
)

echo Version: %VERSION%

REM Set paths
set "GITLAB_DIR=D:\Gitlab\Zest\Zest"

REM Find the newest ZIP file in build folder
set "NEWEST_ZIP="
for /f "delims=" %%f in ('dir /b /o-d "build\distributions\Zest-*.zip" 2^>nul') do (
    if not defined NEWEST_ZIP (
        set "NEWEST_ZIP=build\distributions\%%f"
    )
)

if not defined NEWEST_ZIP (
    echo ERROR: No ZIP file found in build\distributions\
    pause
    exit /b 1
)

echo Using ZIP: %NEWEST_ZIP%

REM Delete old latest if exists
if exist "%GITLAB_DIR%\release\Zest-latest.zip" (
    del /f "%GITLAB_DIR%\release\Zest-latest.zip"
    echo Deleted old Zest-latest.zip
)

REM Copy files
copy /Y "README.md" "%GITLAB_DIR%\README.md"
copy /Y "%NEWEST_ZIP%" "%GITLAB_DIR%\release\Zest-%VERSION%.zip"
copy /Y "%NEWEST_ZIP%" "%GITLAB_DIR%\release\Zest-latest.zip"
copy /Y "src\main\resources\META-INF\updatePlugins.xml" "%GITLAB_DIR%\release\updatePlugins.xml"

REM Git operations
cd /d "%GITLAB_DIR%"
git add .
git commit -m "Release version %VERSION%"
git push

echo Done.
pause