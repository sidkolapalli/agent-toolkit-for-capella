---
name: capella-setup
description: Connect your agent to the model open in Eclipse Capella through the MCP server of AI Assistant for Capella.
disable-model-invocation: true
---

# Connect to Eclipse Capella®

The AI Assistant for Capella plug-in runs an MCP server inside Capella. The server works on the model open in
Capella, live: every change goes through it, so Capella stays the single owner of the model files.

## 1. Start the server

First check for a running server: `curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8765/status`.
Any HTTP status (401 without a token) means Capella is already serving: skip to step 2. Never launch a
second Capella: only one can hold the port and the model files.

1. Install the plugin's update site (**Help → Install New Software**) and restart Capella.
2. Open the model.
3. Start the server: **Window → Show View → Other → AI Dev Server → Start**, or launch Capella with
   `CAPELLA_AI_DEVSERVER_AUTOSTART=1`. Port 8765 unless `CAPELLA_AI_DEVSERVER_PORT` sets another.

Done when `~/.capella-ai/devserver-<port>.token` exists with a fresh timestamp. A large model can take
minutes to open; wait for the token instead of launching again.

The server listens on loopback only and wants `Authorization: Bearer <token>` on every request. It
writes a new random token on every start, so a copied token goes stale at the next restart. For a
stable token, set `CAPELLA_AI_DEVSERVER_TOKEN` before launching Capella and hand the same value to
the client through an environment variable.

## 2. Register it with the agent

| Client | Registration |
|---|---|
| Claude Code | `claude mcp add --transport http capella http://127.0.0.1:8765/mcp --header "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)"` |
| Codex | In `~/.codex/config.toml`: `[mcp_servers.capella]` with `url = "http://127.0.0.1:8765/mcp"` and `bearer_token_env_var = "CAPELLA_AI_DEVSERVER_TOKEN"` |
| Any MCP client | Streamable HTTP at `http://127.0.0.1:8765/mcp` with the bearer header |

Done when the client lists a `capella` server.

## 3. Check it

Call `model_summary`. Done when it names the open project and its layers.

| Symptom | Cause |
|---|---|
| Connection refused | Server not started, or on another port |
| 401 | Token changed since registration (Capella restarted) |
| 403 | The request carried a browser `Origin` header |
| "Open a Capella model in Capella first." | No model open |
