#!/usr/bin/env python
"""
Main entry point for Capella MCP Server
Runs with stdio transport for Claude Code integration
"""
import logging
import sys

# Configure logging to stderr so it doesn't interfere with stdio
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    stream=sys.stderr,
)

from capella_mcp.server import main

if __name__ == "__main__":
    # FastMCP's run() handles the async event loop internally
    main()
