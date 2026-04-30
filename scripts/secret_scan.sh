#!/usr/bin/env bash
# secret_scan.sh — B-014 Release Candidate Audit
# Scans tracked files for patterns that look like real API keys or tokens.
# Exit 0: nothing found (PASS)
# Exit 1: potential secret detected (FAIL)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PASS=true

# Patterns to look for (regex, case-insensitive)
declare -a PATTERNS=(
  'sk-[A-Za-z0-9]{20,}'         # OpenAI-style key
  'xoxb-[0-9]+-[A-Za-z0-9-]+'   # Slack bot token
  'xoxp-[0-9]+-[A-Za-z0-9-]+'   # Slack user token
  'xapp-[A-Za-z0-9-]+'          # Slack app token
  'AIzaSy[A-Za-z0-9_-]{33}'     # Google API key
  'AKIA[0-9A-Z]{16}'            # AWS access key
)

# Files / dirs to skip
EXCLUDES=(
  '.git'
  'target'
  '.env.example'            # template file — allowed to contain placeholder text
  'secret_scan.sh'          # this script contains the patterns themselves
  'test_runtime_config.py'  # contains fake token fixtures for redact_secret() tests
  'test_slack_posting.py'   # contains fake Slack token fixtures for mock tests
  '*.class'
  '*.jar'
)

build_exclude_args() {
  local args=()
  for ex in "${EXCLUDES[@]}"; do
    args+=(--exclude-dir="$ex" --exclude="$ex")
  done
  echo "${args[@]}"
}

EXCLUDE_ARGS=$(build_exclude_args)

echo "=== Secret Scan ==="
echo "Repo: $REPO_ROOT"
echo ""

for pattern in "${PATTERNS[@]}"; do
  # shellcheck disable=SC2086
  matches=$(grep -rEI $EXCLUDE_ARGS "$pattern" . 2>/dev/null || true)
  if [[ -n "$matches" ]]; then
    echo "[FAIL] Pattern matched: $pattern"
    echo "$matches" | head -20
    PASS=false
  fi
done

echo ""
if $PASS; then
  echo "PASS — no hardcoded secrets found."
  exit 0
else
  echo "FAIL — potential secret(s) detected. Review the output above."
  exit 1
fi
