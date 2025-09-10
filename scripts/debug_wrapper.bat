@echo off
echo [DEBUG] Starting MCP server at %date% %time% >> mcp_debug.log 2>&1
set PYTHONPATH=%~dp0src >> mcp_debug.log 2>&1
set CAPELLA_MODEL_PATH=<local-path> >> mcp_debug.log 2>&1
echo [DEBUG] Running server command >> mcp_debug.log 2>&1
"%~dp0.venv\Scripts\python.exe" "%~dp0test_simple.py" >> mcp_debug.log 2>&1