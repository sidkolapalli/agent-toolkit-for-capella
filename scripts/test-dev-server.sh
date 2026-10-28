#!/bin/bash
# Smoke test for the plug-in's dev server in Capella. Read-only: it never changes the model. Needs curl and jq.
#
# 1. Open a disposable copy of a Capella model.
# 2. Start the dev server: Window -> Show View -> Other -> AI Assistant for Capella -> AI Dev Server -> Start.
# 3. scripts/test-dev-server.sh [port]

PORT=${1:-8765}
BASE_URL="http://localhost:$PORT"
TOKEN=${CAPELLA_AI_DEVSERVER_TOKEN:-$(cat "$HOME/.capella-ai/devserver-$PORT.token")}
CURL=(curl -s -H "Authorization: Bearer $TOKEN")

echo ">>> Status"
STATUS=$("${CURL[@]}" "$BASE_URL/status")
echo "$STATUS" | jq .
if ! echo "$STATUS" | jq -e '.hasSession' >/dev/null; then
    echo "ERROR: open a Capella model first." >&2
    exit 1
fi

echo ">>> Execute: count SystemFunctions"
"${CURL[@]}" -X POST "$BASE_URL/execute" \
    -H "Content-Type: text/plain" \
    -d 'def functions = api.findAll("SystemFunction")
functions.take(5).each { f -> log.info("  - ${f.name}") }
return functions.size()' | jq .

echo ">>> MCP: list tools"
"${CURL[@]}" -X POST "$BASE_URL/mcp" \
    -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '[.result.tools[].name]'
