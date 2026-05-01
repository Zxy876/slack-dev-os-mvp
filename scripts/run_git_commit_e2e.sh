#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# B-021 Human Git Commit Snapshot — E2E proof script
#
# Flow:
#   1. Create a fresh temp directory as a git fixture repo
#   2. git init + local user config + baseline commit
#   3. Modify a file (introduce a pending change)
#   4. POST /devos/git-commit confirm=false  → must return 400
#   5. POST /devos/git-commit confirm=true   → must return COMMITTED
#   6. Assert git rev-list --count HEAD == 2 (no push occurred)
#   7. Assert no remote is configured (never touched remote)
#   8. Run secret scan (no secrets in responses or scripts)
#
# Safety invariants verified by this script:
#   - confirm=false always rejected (safety gate)
#   - Local commit created exactly once
#   - No git push, no remote configured
#   - Response contains commitHash (40 chars)
#   - Response contains changed file name
#
# Usage:
#   bash scripts/run_git_commit_e2e.sh              # requires running backend
#   SKIP_BACKEND=1 bash scripts/run_git_commit_e2e.sh  # fixture/logic only
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
SKIP_BACKEND="${SKIP_BACKEND:-}"

PASS=0
FAIL=0
FIXTURE_DIR=""

# ─── Helpers ─────────────────────────────────────────────────────────────────

log()  { echo "[INFO]  $*"; }
ok()   { echo "[PASS]  $*"; PASS=$((PASS + 1)); }
fail() { echo "[FAIL]  $*"; FAIL=$((FAIL + 1)); }

assert_eq() {
    local label="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        ok "$label: '$actual'"
    else
        fail "$label: expected='$expected' actual='$actual'"
    fi
}

assert_contains() {
    local label="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -q "$needle"; then
        ok "$label: contains '$needle'"
    else
        fail "$label: expected to find '$needle' in: $haystack"
    fi
}

cleanup() {
    if [ -n "$FIXTURE_DIR" ] && [ -d "$FIXTURE_DIR" ]; then
        rm -rf "$FIXTURE_DIR"
        log "Cleaned up fixture dir: $FIXTURE_DIR"
    fi
}
trap cleanup EXIT

# ─── Setup fixture git repo ───────────────────────────────────────────────────

FIXTURE_DIR="$(mktemp -d)"
# Resolve to canonical path (macOS /var/folders is a symlink to /private/var/folders)
FIXTURE_DIR="$(cd "$FIXTURE_DIR" && pwd -P)"
log "Fixture repo: $FIXTURE_DIR"

# git init + local identity (never writes global config)
git -C "$FIXTURE_DIR" init -q
git -C "$FIXTURE_DIR" config user.email "devos-e2e@example.local"
git -C "$FIXTURE_DIR" config user.name  "DevOS E2E Test"

# Baseline commit
echo "# Hello World" > "$FIXTURE_DIR/README.md"
git -C "$FIXTURE_DIR" add -A
git -C "$FIXTURE_DIR" commit -q -m "initial"

INITIAL_HASH="$(git -C "$FIXTURE_DIR" rev-parse HEAD)"
log "Baseline commit: $INITIAL_HASH"

# Introduce a change
echo "# Modified by DevOS E2E" >> "$FIXTURE_DIR/README.md"
echo "# E2E notes" > "$FIXTURE_DIR/NOTES.md"

log "Pending changes:"
git -C "$FIXTURE_DIR" status --short

# ─── Skip backend calls if SKIP_BACKEND=1 ────────────────────────────────────

if [ -n "$SKIP_BACKEND" ]; then
    log "SKIP_BACKEND=1 — verifying fixture logic without backend"

    # Verify git status sees changes
    STATUS_OUT="$(git -C "$FIXTURE_DIR" status --porcelain)"
    if [ -n "$STATUS_OUT" ]; then
        ok "Fixture has pending changes (skip mode)"
    else
        fail "Fixture should have pending changes but is clean"
    fi

    # Verify git init produced a valid repo
    TOP_LEVEL="$(git -C "$FIXTURE_DIR" rev-parse --show-toplevel)"
    assert_eq "git top-level resolves" "$FIXTURE_DIR" "$TOP_LEVEL"

    # Simulate: after commit, count becomes 2
    git -C "$FIXTURE_DIR" add -A
    git -C "$FIXTURE_DIR" commit -q -m "devos: E2E test commit (skip-backend)"
    COMMIT_COUNT="$(git -C "$FIXTURE_DIR" rev-list --count HEAD)"
    assert_eq "commit count == 2 after local commit" "2" "$COMMIT_COUNT"

    # Verify no remote configured
    REMOTE_COUNT="$(git -C "$FIXTURE_DIR" remote | wc -l | tr -d ' ')"
    assert_eq "no remotes configured" "0" "$REMOTE_COUNT"

    log ""
    log "Skipped backend assertions (SKIP_BACKEND=1)"
    log "Results: PASS=$PASS FAIL=$FAIL"
    [ "$FAIL" -eq 0 ] && exit 0 || exit 1
fi

# ─── Live backend assertions ──────────────────────────────────────────────────

log "Backend URL: $BACKEND_URL"

# ── Assertion 1: confirm=false → 400 ─────────────────────────────────────────
log ""
log "Assertion 1: confirm=false → HTTP 400"

HTTP_CODE_REJECT="$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BACKEND_URL/devos/git-commit" \
    -H "Content-Type: application/json" \
    -d "{
        \"repoPath\": \"$FIXTURE_DIR\",
        \"slackThreadId\": \"C-E2E/1000000000.000001\",
        \"message\": \"should not commit\",
        \"confirm\": false
    }")"

assert_eq "confirm=false → 400" "400" "$HTTP_CODE_REJECT"

# Verify no commit was added
COMMIT_COUNT_BEFORE="$(git -C "$FIXTURE_DIR" rev-list --count HEAD)"
assert_eq "commit count unchanged after reject" "1" "$COMMIT_COUNT_BEFORE"

# ── Assertion 2: confirm=true + changes → COMMITTED ──────────────────────────
log ""
log "Assertion 2: confirm=true + pending changes → COMMITTED"

COMMIT_RESPONSE="$(curl -s \
    -X POST "$BACKEND_URL/devos/git-commit" \
    -H "Content-Type: application/json" \
    -d "{
        \"repoPath\": \"$FIXTURE_DIR\",
        \"slackThreadId\": \"C-E2E/1000000000.000002\",
        \"message\": \"devos: E2E commit test\",
        \"confirm\": true
    }")"

log "Response: $COMMIT_RESPONSE"

assert_contains "response status=COMMITTED" "COMMITTED" "$COMMIT_RESPONSE"
assert_contains "response contains commitHash" "commitHash" "$COMMIT_RESPONSE"
assert_contains "response contains changedFiles" "changedFiles" "$COMMIT_RESPONSE"
assert_contains "response contains README" "README" "$COMMIT_RESPONSE"

# ── Assertion 3: git commit count == 2 ───────────────────────────────────────
log ""
log "Assertion 3: local git history has exactly 2 commits"

COMMIT_COUNT_AFTER="$(git -C "$FIXTURE_DIR" rev-list --count HEAD)"
assert_eq "commit count == 2 after COMMITTED" "2" "$COMMIT_COUNT_AFTER"

# ── Assertion 4: commitHash in response matches actual HEAD ──────────────────
log ""
log "Assertion 4: commitHash matches git HEAD"

ACTUAL_HEAD="$(git -C "$FIXTURE_DIR" rev-parse HEAD)"
assert_contains "response contains actual HEAD hash" "$ACTUAL_HEAD" "$COMMIT_RESPONSE"

# ── Assertion 5: no remote configured (never pushed) ─────────────────────────
log ""
log "Assertion 5: no remote configured (no push)"

REMOTE_COUNT="$(git -C "$FIXTURE_DIR" remote | wc -l | tr -d ' ')"
assert_eq "no remotes configured" "0" "$REMOTE_COUNT"

# ── Assertion 6: idempotent — second call with no changes → NO_CHANGES ───────
log ""
log "Assertion 6: no pending changes → NO_CHANGES"

NO_CHANGE_RESPONSE="$(curl -s \
    -X POST "$BACKEND_URL/devos/git-commit" \
    -H "Content-Type: application/json" \
    -d "{
        \"repoPath\": \"$FIXTURE_DIR\",
        \"slackThreadId\": \"C-E2E/1000000000.000003\",
        \"message\": \"devos: nothing to commit\",
        \"confirm\": true
    }")"

log "No-change response: $NO_CHANGE_RESPONSE"
assert_contains "second call → NO_CHANGES" "NO_CHANGES" "$NO_CHANGE_RESPONSE"

# ── Assertion 7: non-git dir → 400 ───────────────────────────────────────────
log ""
log "Assertion 7: non-git directory → 400"

NON_GIT_DIR="$(mktemp -d)"
NON_GIT_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BACKEND_URL/devos/git-commit" \
    -H "Content-Type: application/json" \
    -d "{
        \"repoPath\": \"$NON_GIT_DIR\",
        \"slackThreadId\": \"C-E2E/1000000000.000004\",
        \"message\": \"invalid\",
        \"confirm\": true
    }")"
rm -rf "$NON_GIT_DIR"

assert_eq "non-git dir → 400" "400" "$NON_GIT_CODE"

# ── Assertion 8: commit message > 200 chars → 400 ────────────────────────────
log ""
log "Assertion 8: commit message > 200 chars → 400"

LONG_MSG="$(python3 -c "print('x' * 201)")"
LONG_MSG_CODE="$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BACKEND_URL/devos/git-commit" \
    -H "Content-Type: application/json" \
    -d "{
        \"repoPath\": \"$FIXTURE_DIR\",
        \"slackThreadId\": \"C-E2E/1000000000.000005\",
        \"message\": \"$LONG_MSG\",
        \"confirm\": true
    }")"

assert_eq "oversized message → 400" "400" "$LONG_MSG_CODE"

# ── Assertion 9: secret scan ──────────────────────────────────────────────────
log ""
log "Assertion 9: secret scan passes"

if bash scripts/secret_scan.sh; then
    ok "secret scan passed"
else
    fail "secret scan found issues"
fi

# ─── Summary ──────────────────────────────────────────────────────────────────

log ""
log "═══════════════════════════════════════"
log "Results: PASS=$PASS  FAIL=$FAIL"
log "═══════════════════════════════════════"

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
