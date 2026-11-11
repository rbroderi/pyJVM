#!/bin/bash
# usage: tools/ast.sh [python] [root] [limit]
cd "$(dirname "$0")/.."
rm -rf /tmp/claude-0/pc && mkdir -p /tmp/claude-0/pc
javac --release 21 -d /tmp/claude-0/pc runtime/pyjvm315/parser/*.java 2>&1 | grep -v "Picked\|Note:"
JAVA_TOOL_OPTIONS= ${1:-python3} tools/compare_ast.py /tmp/claude-0/pc "${2:-/usr/local/lib/python3.13}" "${3:-100000}" 2>&1 | grep -v Picked
