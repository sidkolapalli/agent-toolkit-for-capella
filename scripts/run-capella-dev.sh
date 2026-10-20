#!/usr/bin/env bash
set -euo pipefail

CAPELLA_HOME=${CAPELLA_HOME:-<local-path>}
WORKSPACE=${WORKSPACE:-<local-path>}
SAMPLE_SRC=${SAMPLE_SRC:-<local-path> Entertainment System}
SAMPLE_NAME=${SAMPLE_NAME:-In-Flight Entertainment System}
PORT=${PORT:-8765}

if [ ! -x "$CAPELLA_HOME/capella" ]; then
  echo "Capella executable not found at: $CAPELLA_HOME/capella" >&2
  exit 1
fi

if [ ! -d "$SAMPLE_SRC" ]; then
  echo "Sample model not found at: $SAMPLE_SRC" >&2
  exit 1
fi

mkdir -p "$WORKSPACE"

if [ ! -d "$WORKSPACE/$SAMPLE_NAME" ]; then
  cp -a "$SAMPLE_SRC" "$WORKSPACE/$SAMPLE_NAME"
  echo "Copied sample model to workspace: $WORKSPACE/$SAMPLE_NAME"
fi

export CAPELLA_AI_DEVSERVER_PORT="$PORT"
export CAPELLA_AI_DEVSERVER_AUTOSTART=1
export CAPELLA_AI_DEVSERVER_OPEN_AIRD="$WORKSPACE/$SAMPLE_NAME/$SAMPLE_NAME.aird"

LOG_FILE="$WORKSPACE/capella-dev.log"
"$CAPELLA_HOME/capella" -clean -consoleLog -data "$WORKSPACE" >"$LOG_FILE" 2>&1 &

cat <<MSG
Capella launched.
- Workspace: $WORKSPACE
- Sample: $WORKSPACE/$SAMPLE_NAME
- Dev server port: $PORT (auto-start via env); token in ~/.capella-ai/devserver-$PORT.token
- Log: $LOG_FILE

If the model is not already open, open the project in Capella and load the .aird.
MSG
