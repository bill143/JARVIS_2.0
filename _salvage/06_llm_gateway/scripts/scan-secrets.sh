#!/usr/bin/env bash
# ============================================================
#  Secret scanner -- runs gitleaks + trufflehog against full history
#  Exits non-zero on ANY finding.
# ============================================================
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

echo ">>> Scanning $REPO_ROOT"
cd "$REPO_ROOT"

# ---- gitleaks ----
echo ""
echo ">>> [1/2] gitleaks"
docker run --rm -v "$REPO_ROOT:/repo" \
    zricethezav/gitleaks:latest \
    detect --source /repo --redact --verbose --no-banner

# ---- trufflehog ----
echo ""
echo ">>> [2/2] trufflehog (filesystem + git history)"
docker run --rm -v "$REPO_ROOT:/repo" \
    trufflesecurity/trufflehog:latest \
    git file:///repo --only-verified --fail

echo ""
echo ">>> OK - No secrets detected."