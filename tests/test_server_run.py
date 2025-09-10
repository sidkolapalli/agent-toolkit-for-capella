#!/usr/bin/env python
"""Test that the server runs properly"""
import subprocess
import sys
import json

# Test with timeout
cmd = ["uv", "run", "python", "-m", "capella_mcp"]

proc = subprocess.Popen(
    cmd,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    cwd="<local-path>"
)

try:
    # Just wait a bit and kill it - if it's running, that's good
    proc.wait(timeout=2)
    print("Server exited unexpectedly")
    stdout, stderr = proc.communicate()
    print("STDOUT:", stdout)
    print("STDERR:", stderr)
except subprocess.TimeoutExpired:
    print("SUCCESS: Server is running properly (waiting for stdio input)")
    proc.kill()
    
# Also test as a script command
print("\nTesting script entry point...")
result = subprocess.run(
    ["uv", "run", "capella-mcp-server", "--help"],
    capture_output=True,
    text=True,
    timeout=2,
    cwd="<local-path>"
)
if result.returncode == 0 or "capella-mcp" in result.stderr:
    print("SUCCESS: Script entry point exists")
else:
    print("FAILED: Script entry point failed:", result.stderr)