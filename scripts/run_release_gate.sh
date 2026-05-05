#!/usr/bin/env bash
# run_release_gate.sh — Slack Dev OS one-command release gate
#
# Aggregates core verification steps into one deterministic command.
# Default mode runs fast checks (Java tests + Python tests + config check + secret scan).
# Optional E2E mode also runs local demo/page-fault E2E scripts.
#
# Usage:
#   bash scripts/run_release_gate.sh
#   bash scripts/run_release_gate.sh --with-e2e
#   bash scripts/run_release_gate.sh --skip-java
#   bash scripts/run_release_gate.sh --skip-python
#
# Exit code:
#   0 -> all selected checks passed
#   1 -> any selected check failed

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"

RUN_JAVA=true
RUN_PYTHON=true
RUN_CONFIG=true
RUN_SECRET_SCAN=true
RUN_E2E=false

for arg in "$@"; do
  case "$arg" in
    --with-e2e)
      RUN_E2E=true
      ;;
    --skip-java)
      RUN_JAVA=false
      ;;
    --skip-python)
      RUN_PYTHON=false
      ;;
    --skip-config)
      RUN_CONFIG=false
      ;;
    --skip-secret-scan)
      RUN_SECRET_SCAN=false
      ;;
    -h|--help)
      cat << 'EOF'
run_release_gate.sh options:
  --with-e2e          include run_demo_e2e.sh and run_page_fault_e2e.sh
  --skip-java         skip Maven tests
  --skip-python       skip Python worker tests
  --skip-config       skip production config readiness check
  --skip-secret-scan  skip secret scan
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: ${arg}" >&2
      exit 1
      ;;
  esac
done

PASS_COUNT=0
FAIL_COUNT=0
FAILED_STEPS=()

run_step() {
  local name="$1"
  shift

  echo ""
  echo "================================================="
  echo "[STEP] ${name}"
  echo "================================================="

  if "$@"; then
    echo "[PASS] ${name}"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "[FAIL] ${name}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    FAILED_STEPS+=("${name}")
  fi
}

echo "==============================================="
echo " Slack Dev OS — Release Gate"
echo "==============================================="
echo "repo: ${REPO_ROOT}"
echo "with_e2e: ${RUN_E2E}"

action_mvn_test() {
  cd "${REPO_ROOT}"
  mvn test -Dspring.profiles.active=local
}

action_python_tests() {
  cd "${WORKER_DIR}"
  python3 -m pytest test_tool_manager.py test_runtime_config.py -q
}

action_config_check() {
  cd "${REPO_ROOT}"
  DEMO_MODE=true bash scripts/run_production_config_check.sh
}

action_secret_scan() {
  cd "${REPO_ROOT}"
  bash scripts/secret_scan.sh
}

action_demo_e2e() {
  cd "${REPO_ROOT}"
  bash scripts/run_demo_e2e.sh
}

action_page_fault_e2e() {
  cd "${REPO_ROOT}"
  bash scripts/run_page_fault_e2e.sh
}

if $RUN_JAVA; then
  run_step "Java tests (mvn test)" action_mvn_test
else
  echo "[SKIP] Java tests"
fi

if $RUN_PYTHON; then
  run_step "Python tests (worker)" action_python_tests
else
  echo "[SKIP] Python tests"
fi

if $RUN_CONFIG; then
  run_step "Production config readiness" action_config_check
else
  echo "[SKIP] Production config readiness"
fi

if $RUN_SECRET_SCAN; then
  run_step "Secret scan" action_secret_scan
else
  echo "[SKIP] Secret scan"
fi

if $RUN_E2E; then
  run_step "Demo E2E" action_demo_e2e
  run_step "Page Fault E2E" action_page_fault_e2e
fi

echo ""
echo "==============================================="
echo " Release Gate Summary"
echo "==============================================="
echo "passed: ${PASS_COUNT}"
echo "failed: ${FAIL_COUNT}"

if [ "$FAIL_COUNT" -gt 0 ]; then
  echo "failed steps:"
  for s in "${FAILED_STEPS[@]}"; do
    echo "  - ${s}"
  done
  exit 1
fi

echo "All selected checks passed."
