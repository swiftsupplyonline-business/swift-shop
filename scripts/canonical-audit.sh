#!/usr/bin/env bash
# Strict canonical-only architecture gate.
# Historical migration references are allowed in documentation; tracked operational files are not.
set -u
cd "$(git rev-parse --show-toplevel)"
fail=0

count() {
  git grep -n -E "$1" -- . ':(exclude)docs/**' ':(exclude)*.md' ':(exclude)scripts/canonical-audit.sh' 2>/dev/null | wc -l | tr -d ' '
}

check() {
  n=$(count "$2")
  printf '%-44s %s\n' "$1" "$n"
  if [ "$n" -gt 0 ]; then
    git grep -n -E "$2" -- . ':(exclude)docs/**' ':(exclude)*.md' ':(exclude)scripts/canonical-audit.sh' 2>/dev/null || true
    fail=1
  fi
}

echo "Canonical Architecture Check"
check "calculateOrderFees operational refs" '\bcalculateOrderFees\b'
check "createOrder operational refs" '\bcreateOrder\b'
check "requestDelivery operational refs" '\brequestDelivery\b'
check "logistics module refs" 'from[[:space:]]+["'"']\./logistics["'"']|require\(["'"']\./logistics'
check "obsolete exact PICKUP state refs" '(^|[^A-Za-z0-9_])PICKUP([^A-Za-z0-9_]|$)'

if [ -e functions/src/logistics.ts ]; then
  echo "LEGACY FILE PRESENT: functions/src/logistics.ts"
  fail=1
else
  echo "functions/src/logistics.ts absent"
fi

for sym in calculatePurchaseTotal createPurchaseOrder createDeliveryRequest createDeliveryJob; do
  if ! git grep -n -E "export const ${sym}[[:space:]]*=" -- functions/src >/dev/null 2>&1; then
    echo "MISSING CANONICAL AUTHORITY: ${sym}"
    fail=1
  else
    echo "Canonical authority present: ${sym}"
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "STRICT RESULT: PASS"
else
  echo "STRICT RESULT: FAIL"
fi
exit "$fail"
