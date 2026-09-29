@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

:: ApocBR Project Zomboid Client Patch Uninstaller

cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-client.ps1" -Revert %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo A desinstalação falhou com código %ERRORLEVEL%.
    echo Se a janela fechou rápido demais, abra o PowerShell e execute:
    echo   powershell -ExecutionPolicy Bypass -File "%~dp0patch-client.ps1" -Revert
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Patch cliente do APOCALIPSE removido.
pause
endlocal
