#!/bin/bash
# GetGoat MCP stdio server — connects Claude Code to terrain/unit tools
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Ensure compiled
if [ ! -d "target/classes" ]; then
    mvn -q compile 2>&1 | tail -1
fi

# Full classpath: target/classes + all deps
CP="target/classes"
DEPS_FILE="target/mcp-classpath.txt"
if [ ! -f "$DEPS_FILE" ]; then
    mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$DEPS_FILE" 2>/dev/null
fi
if [ -f "$DEPS_FILE" ]; then
    CP="$CP:$(cat $DEPS_FILE)"
fi

exec java -Xmx4g -Xms512m -cp "$CP" com.getgoat.app.Main --mcp
