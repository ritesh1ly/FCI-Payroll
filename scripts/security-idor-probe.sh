#!/bin/bash
# Differential IDOR probe (threat T6) against a deployed stack: two makers whose office scopes do not overlap
# (maker.delhi@ DH001, maker.chennai@ CH001) request the same ids on every id-addressed GET endpoint. An office-owned
# record answers 200 to at most one of them; an id both can read is an IDOR (or a national record: check it). A 5xx
# is reported too. Usage: scripts/security-idor-probe.sh [endpoint-pattern...]  ('{}' stands for the id)
#   BASE, ONLINE_LOG as scripts/security-e2e.sh; IDS overrides the ids tried (default 1-40, 185-191, 905-914).
# Add the GET endpoint of every new module here (security-test-2026-10-29.md). Read only: nothing is changed.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
BASE=${BASE:-https://localhost:8443/api}
LOG=${ONLINE_LOG:-$REPO/scripts/local/runtime/logs/online/api.json}
PW='Fci@Demo2026'
DIR=$(mktemp -d)
A=maker.delhi@fci-demo.test
B=maker.chennai@fci-demo.test
IDS=${IDS:-"$(seq 1 40) $(seq 185 191) $(seq 905 914)"}
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c "import uuid;print(uuid.uuid4())"; }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$DIR/$1.jar" | tail -1; }

login() { # the sign-in OTP is read back from the log by the request's correlation id (ADR-0026)
  local who=$1 cid code i status
  curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o /dev/null "$BASE/auth/csrf"
  cid=$(uuid)
  status=$(curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" -H "X-Correlation-Id: $cid" \
    --data "{\"loginId\":\"$who\",\"password\":\"$PW\"}")
  if [ "$status" = 202 ]; then
    code=
    for i in 1 2 3 4 5 6 7 8 9 10; do
      code=$(grep -F "\"correlationId\":\"$cid\"" "$LOG" | grep -o 'devCode=[0-9]\{6\}' | tail -1 | cut -d= -f2)
      [ -n "$code" ] && break
      sleep 1
    done
    status=$(curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login/otp" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" --data "{\"code\":\"$code\"}")
  fi
  [ "$status" = 200 ] || { echo "sign-in of $who failed ($status)"; exit 2; }
}

get() { curl -sk -b "$DIR/$1.jar" -o /dev/null -w '%{http_code}' "$BASE$2"; }

login "$A"
login "$B"
EPS=${*:-"/v1/claims/{} /v1/arrear-events/{}/details /v1/cpf/sanctions/{} /v1/hrms-change-orders/{} /v1/hrms-change-tasks/{}
/v1/hrms-update-requests/{}/documents /v1/hrms-fallback/uploads/{}/lines /v1/off-cycle/{} /v1/queries/{} /v1/supplementary/{}
/v1/jobs/{} /v1/labour/gangs/{}/members /v1/labour/gangs/{}/attendance /v1/labour/gangs/{}/work-slips
/v1/labour/members/{}/nominations /v1/loans/{} /v1/simulations/{} /v1/penalties/{} /v1/settlement-cases/{}
/v1/tax/declarations/{} /v1/tax/form16-certificates/{}/part-a /v1/tax/returns/{}/deductees /v1/cycles/results/{}
/v1/wage-revisions/{} /v1/admin/users/{}"}
found=0
for ep in $EPS; do
  both=
  na=0
  nb=0
  for i in $IDS; do
    p=${ep//\{\}/$i}
    a=$(get "$A" "$p")
    b=$(get "$B" "$p")
    [ "$a" = 200 ] && na=$((na + 1))
    [ "$b" = 200 ] && nb=$((nb + 1))
    [ "$a" = 200 ] && [ "$b" = 200 ] && both="$both $i"
    case "$a$b" in *5??*) echo "  5xx  $p: $a $b"; found=$((found + 1)) ;; esac
  done
  [ -n "$both" ] && found=$((found + 1))
  echo "$ep  DH001 read $na, CH001 read $nb, both [${both# }]"
done
[ "$found" = 0 ] && echo "No id readable by both offices, no 5xx." || { echo "$found finding(s) to check."; exit 1; }
