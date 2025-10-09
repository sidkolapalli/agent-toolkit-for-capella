#!/usr/bin/env bash
set -euo pipefail

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

echo "Using JAVA_HOME=${JAVA_HOME:-}"
mvn "$@"
