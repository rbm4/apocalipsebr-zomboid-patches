@echo off
setlocal

if not defined APOCBR_CLIENT_MAXIMIZED (
    set "APOCBR_CLIENT_MAXIMIZED=1"
    start "" /max cmd /c ""%~f0" %*"
    exit /b 0
)

:: ApocBR Project Zomboid Client Patch Launcher
:: This wrapper bypasses the default PowerShell execution policy so players can
:: run the patch by double-clicking this file instead of right-clicking a .ps1.
:: Routes to 42.22.2-client\patchApocalipseBr.ps1 (compile and deploy).

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-client.ps1" %*
set "patchExitCode=%ERRORLEVEL%"

if not "%patchExitCode%"=="0" (
    echo.
    echo The client telemetry build failed with code %patchExitCode%.
    echo If the window closed too fast, open PowerShell and run:
    echo   powershell -ExecutionPolicy Bypass -File "%~dp0patch-client.ps1"
    pause
    exit /b %patchExitCode%
)

endlocal & exit /b 0
