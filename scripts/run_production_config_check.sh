#!/usr/bin/env bash
# run_production_config_check.sh — B-010 Production Mode Config Readiness Check
#
# Validates current environment configuration WITHOUT calling real LLM or Slack.
# Outputs LLM backend selection and redacted secrets.
#
# Usage:
#   ./scripts/run_production_config_check.sh              # check current env
#   DEMO_MODE=false GLM_API_KEY=sk-xxx ./scripts/run_production_config_check.sh
#   DEMO_MODE=false ./scripts/run_production_config_check.sh  # should fail clearly
#
# Exit codes:
#   0 — configuration valid (DEMO_MODE=true, or real LLM key present)
#   1 — configuration error (DEMO_MODE=false + no LLM key)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKER_DIR="${REPO_ROOT}/python-workers/devos_chat_worker"

echo "================================================="
echo " Slack Dev OS — Production Config Check (B-010)"
echo "================================================="
echo ""

python3 - << PYEOF
import sys
sys.path.insert(0, "${WORKER_DIR}")
from worker import validate_runtime_config

config = validate_runtime_config()

print("  ASYNCAIFLOW_URL    :", config["asyncaiflow_url"])
print("  WORKER_ID          :", config["worker_id"])
print("  DEMO_MODE          :", config["demo_mode"])
print("  LLM backend        :", config["llm_backend"])
print("  LLM ok             :", config["llm_ok"])
print("  SLACK_BOT_TOKEN    :", config["slack_token_redacted"])
print("  SLACK_WEBHOOK_URL  :", config["slack_webhook_redacted"])
print("  REQUIRE_SLACK_POST :", config["require_slack_post"])
print("  Slack ok           :", config["slack_ok"])
print()

if config["llm_ok"]:
    print("  ✅ Config OK — LLM backend:", config["llm_backend"])
    sys.exit(0)
else:
    print("  ❌ Config ERROR:", config["llm_error"])
    print()
    print("  To fix:")
    print("    - For local/CI testing:   export DEMO_MODE=true")
    print("    - For real LLM (GLM):     export DEMO_MODE=false && export GLM_API_KEY=<your-key>")
    print("    - For real LLM (OpenAI):  export DEMO_MODE=false && export OPENAI_API_KEY=<your-key>")
    sys.exit(1)
PYEOF
