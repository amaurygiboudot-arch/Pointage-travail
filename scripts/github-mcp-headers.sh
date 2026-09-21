#!/usr/bin/env bash
set -euo pipefail

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI absent : impossible d'authentifier GitHub MCP" >&2
  exit 1
fi

token="$(gh auth token 2>/dev/null || true)"
if [ -z "$token" ]; then
  echo "Aucune session GitHub CLI active : exécuter gh auth login" >&2
  exit 1
fi

GITHUB_MCP_TOKEN="$token" python3 - <<'PY'
import json
import os

token = os.environ["GITHUB_MCP_TOKEN"]
print(json.dumps({"Authorization": f"Bearer {token}"}))
PY
