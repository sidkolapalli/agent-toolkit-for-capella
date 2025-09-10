@echo off
REM Windows batch file to run Capella MCP server for Claude Code
cd /d "%~dp0\.."
set CAPELLA_MODEL_PATH=<local-path>
python -m capella_mcp