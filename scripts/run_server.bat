@echo off
set PYTHONPATH=%~dp0..\src
set CAPELLA_MODEL_PATH=<local-path>
"%~dp0..\.venv\Scripts\python.exe" -m capella_mcp