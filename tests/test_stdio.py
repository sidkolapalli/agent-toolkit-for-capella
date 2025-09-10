#!/usr/bin/env python
"""Test the MCP server stdio communication"""
import subprocess
import sys
import json

# Test simple server
cmd = [sys.executable, "src/capella_mcp/simple_server.py"]

proc = subprocess.Popen(
    cmd,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    cwd="<local-path>"
)

# Send a simple test - just close stdin to see what happens
proc.stdin.close()

# Get output
try:
    stdout, stderr = proc.communicate(timeout=2)
    print("STDOUT:", stdout)
    print("STDERR:", stderr)
    print("Return code:", proc.returncode)
except subprocess.TimeoutExpired:
    print("Server is running (good sign)")
    proc.kill()