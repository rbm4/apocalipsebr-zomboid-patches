@echo off
setlocal EnableDelayedExpansion

if /I not "%~1"=="--maximized" (
    start "" /max cmd /c ""%~f0" --maximized %*"
    exit /b
)
shift /1

:: ApocBR Project Zomboid Client Patch Launcher
:: This wrapper bypasses the default PowerShell execution policy so players can
:: run the patch by double-clicking this file instead of right-clicking a .ps1.

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-server.ps1" %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo The patch failed with code %ERRORLEVEL%.
    echo If the window closed too fast, open PowerShell and run:
    echo   powershell -ExecutionPolicy Bypass -File "%~dp0patch-server.ps1"
    pause
    exit /b %ERRORLEVEL%
)

endlocal
