#!/usr/bin/env bash
# Canonical architecture audit. Report-only by default; --strict exits 1 if any legacy reference remains.
set -u
cd "$(git rev-parse --show-toplevel)"
INC=(--include=*.ts --include=*.kt --include=*.js --include=*.html --include=*.json)
EXC=(--exclude-dir=node_modules --exclude-dir=build --exclude-dir=.git --exclude-dir=test --exclude=package-lock.json)
count() { grep -rEn "${INC[@]}" "${EXC[@]}" -e "$1" . 2>/dev/null | wc -l | tr -d ' '; }
files() { grep -rEl "${INC[@]}" "${EXC[@]}" -e "$1" . 2>/dev/null | sed 's/^/    /'; }
total=0
check() { # label pattern
  n=$(count "$2"); total=$((total+n)); printf '%-42s %s\n' "$1" "$n"; [ "$n" -gt 0 ] && files "$2"
}
echo "Canonical Architecture Check"
check "Legacy createOrder("            '\bcreateOrder\b'
check "Legacy calculateOrderFees("     '\bcalculateOrderFees\b'
check "Legacy /listing/ routes"        '"/listing/|/listing/\{|/listing/\*\*'
check "Invalid fulfillment status PICKUP" '["'"'"']PICKUP["'"'"']'
check "Legacy logistics module refs"   "from ['\"]\./logistics['\"]|require\(['\"]\./logistics"
echo "TOTAL legacy references: $total"
[ "${1:-}" = "--strict" ] && [ "$total" -gt 0 ] && exit 1
exit 0
