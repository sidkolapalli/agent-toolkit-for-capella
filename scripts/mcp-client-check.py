# Usage: python -m pip install mcp; python scripts/mcp-client-check.py http://127.0.0.1:8765/mcp "$(cat ~/.capella-ai/devserver-8765.token)"
# Read-only: lists tools and calls model_summary on the model open in Capella.
"""Official MCP Python SDK against the plug-in's dev server in Capella: modern (auto) and legacy modes, plus a missing token."""
import asyncio, json, sys
from mcp import Client
from mcp.client.streamable_http import streamable_http_client, create_mcp_http_client

URL, TOKEN = sys.argv[1], sys.argv[2]

async def run(mode, token):
    headers = {"Authorization": "Bearer " + token} if token else {}
    transport = streamable_http_client(URL, http_client=create_mcp_http_client(headers=headers))
    async with Client(transport, mode=mode, raise_exceptions=True) as client:
        tools = await client.list_tools()
        summary = await client.call_tool("model_summary", {})
        text = summary.content[0].text
        print(mode, "protocol", client.protocol_version, "server", client.server_info.name,
              "tools", sorted(t.name for t in tools.tools), "summary ok", '"ok":true' in text, "isError", summary.is_error)

async def main():
    await run("auto", TOKEN)
    await run("legacy", TOKEN)
    try:
        await run("auto", None)
        print("UNEXPECTED: connected without a token")
    except BaseException as failure:
        print("no token refused:", type(failure).__name__, str(failure)[:160].replace("\n", " "))

asyncio.run(main())
