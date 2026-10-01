#!/usr/bin/env bash
# Strict canonical-only architecture gate.
# Historical migration references are allowed in documentation; tracked operational files are not.
set -u
cd "$(git rev-parse --show-toplevel)"
fail=0

count() {
  git grep -n -E "$1" -- . ':(exclude)docs/**' ':(exclude)*.md' ':(exclude)scripts/canonical-audit.sh' ':(exclude)**/*.test.ts' ':(exclude)functions/src/test/**' 2>/dev/null | wc -l | tr -d ' '
}

check() {
  n=$(count "$2")
  printf '%-44s %s\n' "$1" "$n"
  if [ "$n" -gt 0 ]; then
    git grep -n -E "$2" -- . ':(exclude)docs/**' ':(exclude)*.md' ':(exclude)scripts/canonical-audit.sh' ':(exclude)**/*.test.ts' ':(exclude)functions/src/test/**' 2>/dev/null || true
    fail=1
  fi
}

echo "Canonical Architecture Check"
check "calculateOrderFees operational refs" '\bcalculateOrderFees\b'
check "createOrder operational refs" '\bcreateOrder\b'
check "requestDelivery operational refs" '\brequestDelivery\b'
check "logistics module refs" "from[[:space:]]+[\"']\\./logistics[\"']|require\\([\"']\\./logistics"
check "obsolete exact PICKUP state refs" '(^|[^A-Za-z0-9_])PICKUP([^A-Za-z0-9_]|$)'

# --- Build-integrity checks the architecture grep cannot see (these are what broke Android CI) ---
# 1. A Kotlin file may declare exactly one package.
while IFS= read -r f; do
  n=$(grep -c '^package ' "$f" || true)
  if [ "${n:-0}" -gt 1 ]; then echo "KOTLIN: $n package declarations in $f"; fail=1; fi
done < <(git ls-files '*.kt')

# 2. Environment web hosts may only be hard-coded in the per-flavor config (app/build.gradle.kts).
bad_hosts=$(git grep -n -E 'swift-(dev-3d3ae|staging-3bed1)|swift-d1baa' -- '*.kt' || true)
if [ -n "$bad_hosts" ]; then echo "HARDCODED ENVIRONMENT HOST IN KOTLIN SOURCE:"; echo "$bad_hosts"; fail=1; else echo "No hard-coded environment hosts in Kotlin source"; fi

# 3. The CI workflow must cover the canonical default branch.
if ! grep -q 'canonical-platform-tree-2026-09-29' .github/workflows/verify.yml; then
  echo "CI: verify.yml does not trigger on canonical-platform-tree-2026-09-29"; fail=1
else echo "CI covers canonical-platform-tree-2026-09-29"; fi

# 4. Every module with unit tests under src/test must declare a test dependency.
for d in $(git ls-files '*/src/test/*.kt' | sed -E 's#/src/test/.*##' | sort -u); do
  if [ -f "$d/build.gradle.kts" ] && ! grep -q 'testImplementation' "$d/build.gradle.kts"; then
    echo "ANDROID: $d has unit tests but no testImplementation dependency"; fail=1
  fi
done

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
