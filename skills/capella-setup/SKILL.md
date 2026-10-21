---
name: capella-setup
description: Connect an agent (Claude Code, Codex or any MCP client) to the model open in Capella through the Capella AI plugin's MCP server. Use when the Capella tools are missing, a call returns 401, or the user asks to set up or check the Capella connection.
---

# Connect to Capella

The Capella AI plugin runs an MCP server inside Capella. It sees the model open in the running
Capella, not files on disk. Never edit `.capella` or `.aird` files directly while Capella has them open.

## 1. Start the server in Capella

1. Install the plugin's update site (**Help → Install New Software**) and restart Capella.
2. Open the model.
3. Start the server: **Window → Show View → Other → AI Dev Server → Start**, or launch Capella
   with `CAPELLA_AI_DEVSERVER_AUTOSTART=1`. The port is 8765 unless `CAPELLA_AI_DEVSERVER_PORT` says otherwise.

The server listens on loopback only and needs `Authorization: Bearer <token>` on every request.
By default it writes a fresh random token to `~/.capella-ai/devserver-<port>.token` on every start,
so a token copied into a client config goes stale after a restart. For a stable setup, choose the
token yourself: set `CAPELLA_AI_DEVSERVER_TOKEN` before launching Capella and give the client the
same value through an environment variable.

## 2. Register it with the agent

Claude Code:

```bash
claude mcp add --transport http capella http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)"
```

Codex (`~/.codex/config.toml`; check `codex mcp --help` for your version):

```toml
[mcp_servers.capella]
url = "http://127.0.0.1:8765/mcp"
bearer_token_env_var = "CAPELLA_AI_DEVSERVER_TOKEN"
```

Other clients: Streamable HTTP endpoint `http://127.0.0.1:8765/mcp`, same bearer header.

## 3. Check it

`tools/list` should return the read tools `model_summary`, `model_search`, `model_inspect`,
`model_relationships`, `model_validate`, `native_validation`, `capella_docs`,
`capella_capabilities`, plus `propose_model_edit` and `edit_status`. Then call `model_summary`:
it names the open project and its layers.

| Symptom | Cause |
|---|---|
| Connection refused | Server not started, or another port |
| 401 | Token changed since registration (Capella restarted) |
| 403 | Request carried a browser `Origin` header |
| "Open a Capella model in Capella first." | No model open in Capella |

Next: `arcadia-method` for how to model, `capella-model-edits` for how to change the model.
