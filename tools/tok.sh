#!/bin/bash
# usage: tools/tok.sh [root] [limit]  - build parser classes and compare tokens against CPython tokenize
set -e
cd "$(dirname "$0")/.."
rm -rf /tmp/claude-0/pc && mkdir -p /tmp/claude-0/pc
javac --release 21 -d /tmp/claude-0/pc runtime/pyjvm315/parser/*.java 2>&1 | grep -v "Picked\|Note:" || true
JAVA_TOOL_OPTIONS= python3 tools/compare_tokens.py /tmp/claude-0/pc "${1:-/usr/local/lib/python3.13}" "${2:-100000}" 2>&1 | grep -v Picked
