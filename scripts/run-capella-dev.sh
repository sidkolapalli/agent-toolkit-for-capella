#!/usr/bin/env bash
# Launch Capella with the dev server auto-started on a copy of a sample model.
#   CAPELLA_HOME=/path/to/capella SAMPLE_SRC="/path/to/samples/In-Flight Entertainment System" scripts/run-capella-dev.sh
set -euo pipefail

: "${CAPELLA_HOME:?Set CAPELLA_HOME to the Capella installation directory}"
: "${SAMPLE_SRC:?Set SAMPLE_SRC to the sample project directory to copy}"
WORKSPACE=${WORKSPACE:-$PWD/.workspace-capella}
SAMPLE_NAME=${SAMPLE_NAME:-$(basename "$SAMPLE_SRC")}
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
