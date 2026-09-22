#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v codex >/dev/null 2>&1 || {
  echo "codex introuvable. Installe/connecte Codex dans le Codespace avant la revue." >&2
  exit 2
}
command -v git >/dev/null 2>&1 || exit 2

BASE_REF="${1:-origin/main}"
HEAD_REF="${2:-HEAD}"
BASE_SHA="$(git rev-parse "$BASE_REF")"
HEAD_SHA="$(git rev-parse "$HEAD_REF")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ROUTE="$TMP/route.json"
REPORT="$TMP/report.json"
COMMENT="$TMP/comment.md"

python3 scripts/agent_router.py --base "$BASE_SHA" --head "$HEAD_SHA" --output "$ROUTE" --pretty >/dev/null

export BASE_SHA HEAD_SHA

PROMPT="$(cat scripts/agent-review-prompt.md)

BASE_SHA=$BASE_SHA
HEAD_SHA=$HEAD_SHA
Le dépôt courant est $(pwd).
N'utilise aucun autre SHA pour la décision finale."

echo "=== Route agents ==="
cat "$ROUTE"
echo

codex exec --full-auto   --output-schema scripts/agent-review.schema.json   -o "$REPORT"   "$PROMPT"

python3 scripts/validate_agent_review.py   --route "$ROUTE"   --report "$REPORT"   --head "$HEAD_SHA"

python3 scripts/format_agent_review_comment.py   --route "$ROUTE"   --report "$REPORT" > "$COMMENT"

if command -v gh >/dev/null 2>&1; then
  PR_NUMBER="${PR_NUMBER:-$(gh pr view --json number -q .number 2>/dev/null || true)}"
  if [[ -n "$PR_NUMBER" ]]; then
    gh pr comment "$PR_NUMBER" --body-file "$COMMENT"
    echo "Rapport publié sur la PR #$PR_NUMBER."
  else
    echo "Aucune PR détectée : rapport non publié."
    cat "$COMMENT"
  fi
else
  echo "gh introuvable : rapport non publié."
  cat "$COMMENT"
fi

echo "Rapport local validé pour $HEAD_SHA."
