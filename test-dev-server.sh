#!/bin/bash
# Test script for Capella AI Dev Server
#
# USAGE:
# 1. Launch Capella: <local-path>
# 2. Import the IFE project: File -> Import -> Existing Projects
# 3. Open the IFE model (double-click the .aird file)
# 4. Start Dev Server: Window -> Show View -> Other -> Capella AI -> AI Dev Server -> Start
# 5. Run this script: ./test-dev-server.sh

PORT=${1:-8765}
BASE_URL="http://localhost:$PORT"

echo "============================================"
echo "  Capella AI Dev Server Test Suite"
echo "============================================"
echo ""

# Test 1: Check status
echo ">>> Test 1: Check server status"
STATUS=$(curl -s "$BASE_URL/status")
echo "$STATUS" | jq .
echo ""

if echo "$STATUS" | grep -q '"hasSession": false'; then
    echo "ERROR: No active Capella session!"
    echo "Please open a Capella model first."
    exit 1
fi

# Test 2: Get system prompt
echo ">>> Test 2: Get system prompt (first 500 chars)"
curl -s "$BASE_URL/prompt" | jq -r '.prompt' | head -c 500
echo "..."
echo ""

# Test 3: Execute simple Groovy code
echo ">>> Test 3: Execute Groovy - list all SystemFunctions"
curl -s -X POST "$BASE_URL/execute" \
    -H "Content-Type: text/plain" \
    -d 'def functions = api.findAll("SystemFunction")
log.info("Found ${functions.size()} SystemFunctions")
functions.take(5).each { f -> log.info("  - ${f.name}") }
return functions.size()' | jq .
echo ""

# Test 4: Execute query on IFE model
echo ">>> Test 4: Find 'Make Audio Announcement' activity"
curl -s -X POST "$BASE_URL/execute" \
    -H "Content-Type: text/plain" \
    -d 'def activity = api.find("Make Audio Announcement")
if (activity) {
    log.info("Found: ${activity.name}")
    log.info("Type: ${activity.eClass().name}")
    return activity.name
} else {
    log.warn("Not found")
    return null
}' | jq .
echo ""

# Test 5: Full LLM chat flow
echo ">>> Test 5: Full LLM Chat - ask to describe the model"
curl -s -X POST "$BASE_URL/chat" \
    -H "Content-Type: text/plain" \
    -d "List the top 5 SystemFunctions in this model and their descriptions" | jq .
echo ""

# Test 6: LLM creates new element
echo ">>> Test 6: Full LLM Chat - create new function"
curl -s -X POST "$BASE_URL/chat" \
    -H "Content-Type: text/plain" \
    -d "Create a new SystemFunction called 'Stream Podcast Content' with description 'Allows passengers to stream podcast episodes during flight'" | jq .
echo ""

echo "============================================"
echo "  Test Suite Complete!"
echo "============================================"
