#!/bin/bash
# Security regression test of the authorisation design (docs/rbac/RBAC_DESIGN.md section 5a), run
# against a deployed stack through NGINX (scripts/local/deploy-local.ps1, profile demo):
#   1. URL tampering on /payroll?s=<screen>: deny by default on the server;
#   2. wireframe bodies only after the VIEW check, never in the browser bundle;
#   3. API tampering: forged paths, bodies, governed actions and scope switches are refused;
#   4-5. separation of duties on identity: a user holding maker AND checker roles can never decide
#        an item they submitted (maker.checker@, su.hq@ demo users).
# Usage: scripts/security-e2e.sh   (BASE, ONLINE_LOG, WEB_STATIC override the local defaults)
# Re-runnable: the master change of step 5 is dated after the newest version.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
BASE=${BASE:-https://localhost:8443/api}
LOG=${ONLINE_LOG:-$REPO/scripts/local/runtime/logs/online/api.json}
PW='Fci@Demo2026'
DIR=$(mktemp -d)
fail=0
ok() { echo "  ok    $1"; }
bad() { echo "  FAIL  $1"; fail=$((fail + 1)); }

uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || python -c "import uuid;print(uuid.uuid4())"; }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$DIR/$1.jar" | tail -1; }

login() { # user persona location
  local who=$1
  curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o /dev/null "$BASE/auth/csrf"
  local body="{\"loginId\":\"$1\",\"password\":\"$PW\"${3:+,\"location\":\"$3\"}}"
  local status cid code i
  # ADR-0026 sign-in OTP: the development sender logs the code with the request's correlation id,
  # chosen here, so the code read back belongs to this sign-in and never to another.
  cid=$(uuid)
  status=$(curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o "$DIR/out" -w '%{http_code}' -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" -H "X-Correlation-Id: $cid" --data "$body")
  if [ "$status" = 202 ]; then
    code=
    for i in 1 2 3 4 5 6 7 8 9 10; do
      code=$(grep -F "\"correlationId\":\"$cid\"" "$LOG" | grep -o 'devCode=[0-9]\{6\}' | tail -1 | cut -d= -f2)
      [ -n "$code" ] && break
      sleep 1
    done
    status=$(curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o "$DIR/out" -w '%{http_code}' -X POST "$BASE/auth/login/otp" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" --data "{\"code\":\"$code\"}")
  fi
  [ "$status" = 200 ] && ok "login $1 as $2 ${3:-}" || { bad "login $1: $status $(cat "$DIR/out")"; }
}

call() { # user method path [body] -> prints status, body in $DIR/out
  local who=$1 method=$2 path=$3 body=${4:-}
  curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o "$DIR/out" -w '%{http_code}' -X "$method" "$BASE$path" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" -H "Idempotency-Key: $(uuid)" \
    ${body:+--data "$body"}
}

lastcode() { grep -o 'devCode=[0-9]\{6\}' "$LOG" | tail -1 | cut -d= -f2; }
challenge() { python -c "import json,sys;print(json.load(open(sys.argv[1]))['args'][0])" "$DIR/out"; }
# A paged list (list-pagination standard) is read as its items.
jq_() { python -c "import json,sys;d=json.load(open(sys.argv[1]));d=d['items'] if isinstance(d,dict) and 'items' in d else d;print(eval(sys.argv[2]))" "$DIR/out" "$1"; }

# governed <user> <method> <path> <json-without-closing-brace>: 428 -> OTP from the log -> resubmit
governed() {
  local who=$1 method=$2 path=$3 body=$4 status
  status=$(call "$who" "$method" "$path" "$body}")
  if [ "$status" = 428 ]; then
    local id code
    id=$(challenge); sleep 1; code=$(lastcode)
    status=$(call "$who" "$method" "$path" "$body,\"otp\":{\"challengeId\":$id,\"code\":\"$code\"}}")
  fi
  echo "$status"
}

ORIGIN=${BASE%/api}
page() { # user path -> "status location"
  curl -sk -b "$DIR/$1.jar" -o /dev/null -w '%{http_code} %{redirect_url}' "$ORIGIN$2"
}
anon() { curl -sk -o /dev/null -w '%{http_code}' "$ORIGIN$1"; }

echo "== 1. URL tampering on the workspace route (server-side guard, deny by default)"
login maker.delhi@fci-demo.test maker DH001
r=$(page maker.delhi@fci-demo.test '/payroll?s=M-01'); [ "${r%% *}" = 200 ] && ok "permitted screen renders: ?s=M-01 ($r)" || bad "M-01 $r"
for s in A-07 A-01 A-05 M-22 M-23 M-24 W-01 Z-99 '../../etc/passwd' '%3Cscript%3E'; do
  r=$(page maker.delhi@fci-demo.test "/payroll?s=$s")
  case "$r" in 307*/payroll|308*/payroll) ok "?s=$s refused -> redirect to /payroll" ;; *) bad "?s=$s gave $r" ;; esac
done

echo "== 2. Wireframe bodies are served only after the VIEW check (never in the bundle)"
r=$(page maker.delhi@fci-demo.test '/payroll/wireframe/M-01'); [ "${r%% *}" = 200 ] && ok "permitted wireframe M-01 (200)" || bad "M-01 wireframe $r"
r=$(page maker.delhi@fci-demo.test '/payroll/wireframe/C-02'); [ "${r%% *}" = 404 ] && ok "C-02 is a live screen: no wireframe body (404)" || bad "C-02 wireframe $r"
for s in I-17 M-11 A-07 M-04 XX; do
  r=$(page maker.delhi@fci-demo.test "/payroll/wireframe/$s"); [ "${r%% *}" = 404 ] && ok "wireframe $s refused (404: no body or no permission)" || bad "wireframe $s $r"
done
login emp.rsharma@fci-demo.test eseva
for s in C-02 I-17 M-11 M-01; do
  r=$(page emp.rsharma@fci-demo.test "/payroll/wireframe/$s"); [ "${r%% *}" = 404 ] && ok "eSeva employee: wireframe $s refused (404)" || bad "eSeva wireframe $s $r"
done
r=$(page emp.rsharma@fci-demo.test '/payroll?s=M-01'); case "$r" in 307*/payroll|308*/payroll) ok "eSeva employee: ?s=M-01 refused" ;; *) bad "eSeva M-01 $r" ;; esac
r=$(anon '/payroll/wireframe/M-01'); [ "$r" = 401 ] && ok "anonymous wireframe request refused (401)" || bad "anonymous wireframe $r"
CHUNKS=${WEB_STATIC:-$REPO/scripts/local/runtime/web/current/.next/static}
if grep -rqs "Rates not seeded\|Deputation allowance % — within station" "$CHUNKS"; then bad "wireframe text found in the browser bundle"; else ok "no wireframe body in the browser bundle"; fi

echo "== 3. API tampering: every call is authorised server side, whatever the client sends"
s=$(call maker.delhi@fci-demo.test GET /v1/admin/parameters); [ "$s" = 403 ] && ok "maker GET A-07 parameters -> 403" || bad "A-07 $s"
s=$(call maker.delhi@fci-demo.test GET /v1/admin/users); [ "$s" = 403 ] && ok "maker GET A-01 users -> 403" || bad "A-01 $s"
s=$(call maker.delhi@fci-demo.test GET /v1/submissions); [ "$s" = 200 ] && ok "maker may use its own W-02 (200)" || bad "W-02 view $s"
s=$(call maker.delhi@fci-demo.test POST /v1/masters/staff-rates/DA_IDA/versions '{"effectiveFrom":"2026-11-01","lines":[{"payPattern":"IDA","rateCode":"DA_PCT","rateValue":99,"unit":"PERCENT"}],"reason":"tamper"}')
[ "$s" = 403 ] && ok "maker POST M-04 version (no CREATE) -> 403 $(jq_ "d['errorCode']")" || bad "M-04 create $s"
s=$(call maker.delhi@fci-demo.test POST /v1/actions '{"screen":"A-07","action":"EDIT","reason":"tamper"}'); [ "$s" = 403 ] && ok "maker forged governed action A-07 EDIT -> 403" || bad "action A-07 $s"
s=$(call maker.delhi@fci-demo.test POST /v1/actions '{"screen":"M-04","action":"APPROVE","reason":"tamper"}'); case "$s" in 403|404) ok "maker forged APPROVE action -> $s" ;; *) bad "APPROVE action $s" ;; esac
s=$(call maker.delhi@fci-demo.test POST /auth/switch-scope '{"role":"SYSTEM_ADMINISTRATOR","location":"HQ001"}'); [ "$s" = 403 ] && ok "maker forged scope switch to System Administrator -> 403" || bad "switch $s"
s=$(call maker.delhi@fci-demo.test GET /v1/masters/staff-rates/..%2F..%2Fadmin/versions); case "$s" in 400|403|404) ok "path tampering refused ($s)" ;; *) bad "path tampering $s" ;; esac
login su.hq@fci-demo.test superuser HQ001
s=$(call su.hq@fci-demo.test POST /auth/switch-scope '{"role":"RULE_ADMINISTRATOR","location":"HQ001"}')
s=$(call su.hq@fci-demo.test POST /v1/masters/special-rates/DA_IDA/versions '{"effectiveFrom":"2026-11-01","lines":[{"payPattern":"IDA","rateCode":"DA_PCT","rateValue":60,"unit":"PERCENT"}],"reason":"cross-screen"}')
[ "$s" = 404 ] && ok "M-22 endpoint cannot change an M-04 rate set ($s $(jq_ "d['errorCode']"))" || bad "cross-screen $s"

echo "== 4. Maker can never approve their own request, even holding the checker role (two users)"
login maker.checker@fci-demo.test makerchecker DH001
s=$(call maker.checker@fci-demo.test POST /auth/switch-scope '{"role":"PAYROLL_MAKER","location":"DH001"}'); [ "$s" = 204 ] && ok "dual-role user acts as Payroll Maker" || bad "switch maker $s"
s=$(call maker.checker@fci-demo.test POST /v1/actions '{"screen":"C-02","action":"CREATE","reason":"E2E: SoD check"}')
[ "$s" = 200 ] || [ "$s" = 202 ] && ok "maker submitted C-02 CREATE ($s)" || bad "submit $s $(cat "$DIR/out")"
AID=$(jq_ "d['approvalId']")
s=$(call maker.checker@fci-demo.test POST /auth/switch-scope '{"role":"PAYROLL_CHECKER","location":"DH001"}'); [ "$s" = 204 ] && ok "same user now acts as Payroll Checker" || bad "switch checker $s"
s=$(call maker.checker@fci-demo.test GET /v1/inbox)
v=$(jq_ "[(i['canDecide'], i['denialKey']) for i in d if i['approvalId']==$AID]")
[ "$v" = "[(False, 'error.WF-403-SOD')]" ] && ok "own item in the inbox is not decidable: $v" || bad "inbox view $v"
s=$(call maker.checker@fci-demo.test POST "/v1/approvals/$AID/decision" '{"decision":"APPROVE"}')
[ "$s" = 403 ] && ok "self-approval refused ($s $(jq_ "d['errorCode']"))" || bad "self approve $s $(cat "$DIR/out")"
s=$(call maker.checker@fci-demo.test POST "/v1/approvals/$AID/decision" '{"decision":"REJECT","reason":"own"}')
[ "$s" = 403 ] && ok "self-rejection refused too ($s)" || bad "self reject $s"
login ddo.delhi@fci-demo.test ddo DH001
s=$(call ddo.delhi@fci-demo.test POST "/v1/approvals/$AID/decision" '{"decision":"APPROVE"}')
[ "$s" = 200 ] && ok "another Payroll Checker of DH001 approves ($s)" || bad "other approve $s $(cat "$DIR/out")"

echo "== 5. A master change: su.hq@ (Rule Administrator AND Rule Approver) proposes; only an HQ Payroll Checker decides (V033_3)"
s=$(call su.hq@fci-demo.test GET /v1/masters/staff-rates/DA_IDA/versions)
read -r FROM VNEXT BEFORE <<<"$(jq_ "' '.join([str(__import__('datetime').date.fromisoformat(max(x['effectiveFrom'] for x in d))+__import__('datetime').timedelta(days=1)), str(max(x['versionNo'] for x in d)+1), str([x for x in d if x['state']=='APPROVED' and x['effectiveTo'] is None][0]['lines'][0]['rateValue'])])")"
# A rate change cites the Active circular that orders it (M-25).
s=$(call su.hq@fci-demo.test GET /v1/masters/circulars/current); CIRC=$(jq_ "d[0]['id']")
s=$(governed su.hq@fci-demo.test POST /v1/masters/staff-rates/DA_IDA/versions "{\"effectiveFrom\":\"$FROM\",\"lines\":[{\"payPattern\":\"IDA\",\"rateCode\":\"DA_PCT\",\"rateValue\":53,\"unit\":\"PERCENT\"}],\"circularOrderId\":$CIRC,\"reason\":\"E2E: DA 53% revision\"")
[ "$s" = 202 ] && ok "DA IDA 53% from $FROM proposed as v$VNEXT (202)" || bad "propose DA $s $(cat "$DIR/out")"
MID=$(jq_ "d['approvalId']")
s=$(call su.hq@fci-demo.test GET /v1/masters/staff-rates)
v=$(jq_ "[(m['current']['lines'][0]['rateValue'], m['pending']['versionNo']) for m in d if m['code']=='DA_IDA'][0]")
case "$v" in "($BEFORE, $VNEXT)"|"(${BEFORE%.0}, $VNEXT)") ok "value in force unchanged until approval, v$VNEXT pending: $v" ;; *) bad "list after propose $v (before $BEFORE)" ;; esac
s=$(call su.hq@fci-demo.test POST /v1/masters/staff-rates/DA_IDA/versions "{\"effectiveFrom\":\"2026-01-01\",\"lines\":[{\"payPattern\":\"IDA\",\"rateCode\":\"DA_PCT\",\"rateValue\":1,\"unit\":\"PERCENT\"}],\"reason\":\"overlap\"}")
case "$s" in 409|422) ok "an overlapping / second pending version is refused ($s $(jq_ "d['errorCode']"))" ;; *) bad "overlap $s" ;; esac
s=$(call su.hq@fci-demo.test POST /auth/switch-scope '{"role":"RULE_APPROVER","location":"HQ001"}')
s=$(call su.hq@fci-demo.test POST "/v1/approvals/$MID/decision" '{"decision":"APPROVE"}')
[ "$s" = 403 ] && ok "su.hq@ as Rule Approver cannot approve its own change (not on the route) ($s $(jq_ "d['errorCode']"))" || bad "self master approve $s"
login checker.hq@fci-demo.test checker HQ001
s=$(call checker.hq@fci-demo.test POST /auth/switch-scope '{"role":"PAYROLL_CHECKER","location":"HQ001"}')
s=$(call checker.hq@fci-demo.test POST "/v1/approvals/$MID/decision" '{"decision":"APPROVE"}')
[ "$s" = 200 ] && ok "the HQ Payroll Checker approves ($s)" || bad "HQ checker $s $(cat "$DIR/out")"
s=$(call checker.hq@fci-demo.test GET /v1/masters/staff-rates/DA_IDA/versions)
v=$(jq_ "[(x['versionNo'], x['effectiveFrom'], x['effectiveTo'], x['state']) for x in d]")
echo "      versions: $v"
PREV_END=$(python -c "import datetime,sys;print(datetime.date.fromisoformat(sys.argv[1])-datetime.timedelta(days=1))" "$FROM")
case "$v" in "[($VNEXT, '$FROM', None, 'APPROVED')"*"'$PREV_END', 'APPROVED')"*) ok "v$VNEXT in force from $FROM, previous version ended $PREV_END (no overlap)" ;; *) bad "versions $v" ;; esac
s=$(call checker.hq@fci-demo.test GET /v1/masters/staff-rates)
v=$(jq_ "[m['current']['lines'][0]['rateValue'] for m in d if m['code']=='DA_IDA'][0]")
echo "      DA IDA in force today: $v% (the new version applies from $FROM)"

echo "== 6. Races (T7, T23): concurrent requests cannot decide an item twice or out-guess the OTP attempt limit"
s=$(call maker.checker@fci-demo.test POST /auth/switch-scope '{"role":"PAYROLL_MAKER","location":"DH001"}')
s=$(call maker.checker@fci-demo.test POST /v1/actions '{"screen":"C-02","action":"CREATE","reason":"E2E: decision race"}')
RID=$(jq_ "d['approvalId']")
X=$(xsrf ddo.delhi@fci-demo.test)
for i in $(seq 1 10); do
  ( curl -sk -b "$DIR/ddo.delhi@fci-demo.test.jar" -o /dev/null -w '%{http_code}\n' -X POST "$BASE/v1/approvals/$RID/decision" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $X" -H "Idempotency-Key: $(uuid)" --data '{"decision":"APPROVE"}' > "$DIR/race$i" ) &
done; wait
won=$(cat "$DIR"/race* | grep -c '^200$'); lost=$(cat "$DIR"/race* | grep -c '^409$')
[ "$won" = 1 ] && [ "$lost" = 9 ] && ok "ten parallel approvals of item $RID: one decides, nine refused (409)" || bad "decision race: $won decided, $lost refused"
W=maker.kolkata@fci-demo.test
curl -sk -c "$DIR/race.jar" -b "$DIR/race.jar" -o /dev/null "$BASE/auth/csrf"
cid=$(uuid)
curl -sk -c "$DIR/race.jar" -b "$DIR/race.jar" -o /dev/null -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -H "X-XSRF-TOKEN: $(xsrf race)" -H "X-Correlation-Id: $cid" --data "{\"loginId\":\"$W\",\"password\":\"$PW\"}"
code=
for i in 1 2 3 4 5 6 7 8 9 10; do
  code=$(grep -F "\"correlationId\":\"$cid\"" "$LOG" | grep -o 'devCode=[0-9]\{6\}' | tail -1 | cut -d= -f2)
  [ -n "$code" ] && break
  sleep 1
done
X=$(xsrf race)
for i in $(seq 1 10); do
  ( curl -sk -b "$DIR/race.jar" -o /dev/null -X POST "$BASE/auth/login/otp" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $X" \
      --data "{\"code\":\"$(printf '%06d' $(( (10#$code + i) % 1000000 )))\"}" ) &
done; wait
s=$(curl -sk -c "$DIR/race.jar" -b "$DIR/race.jar" -o /dev/null -w '%{http_code}' -X POST "$BASE/auth/login/otp" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $X" --data "{\"code\":\"$code\"}")
[ "$s" = 400 ] && ok "ten parallel wrong sign-in codes exhaust the challenge: the right code is then refused ($s)" || bad "OTP race: right code after the limit gave $s"

echo
[ "${fail:-0}" = 0 ] && echo "All security checks passed." || { echo "$fail security check(s) failed."; exit 1; }
