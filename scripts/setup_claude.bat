@echo off
echo ==================================================
echo Capella MCP Server Setup for Claude Code
echo ==================================================
echo.

REM Get the directory of this script
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

echo Step 1: Creating virtual environment with uv...
uv venv

echo.
echo Step 2: Installing dependencies...
uv pip install mcp pydantic

echo.
echo Step 3: Setting up environment variables...
set PYTHONPATH=%SCRIPT_DIR%src

echo.
echo Step 4: Creating run script...
echo @echo off > run_server.bat
echo set PYTHONPATH=%SCRIPT_DIR%src >> run_server.bat
echo "%SCRIPT_DIR%.venv\Scripts\python.exe" -m capella_mcp >> run_server.bat

echo.
echo ==================================================
echo Setup Complete!
echo ==================================================
echo.
echo To add this server to Claude Code, run:
echo.
echo claude mcp add capella-mcp "%SCRIPT_DIR%run_server.bat"
echo.
echo Or with a specific model path:
echo.
echo claude mcp add capella-mcp --env CAPELLA_MODEL_PATH="<local-path>" -- "%SCRIPT_DIR%run_server.bat"
echo.
pause