#!/bin/bash
# End-to-end test of M-16 Reference & Combo List Administration and the master-data cache
# (docs/architecture/CACHING.md, docs/db/DDL_V4_ALIGNMENT.md section 9), against a deployed stack:
#   1. who may see and change M-16 (System Administrator at HQ; nobody else);
#   2. a change and a new value go to the HQ Payroll Checker; nothing changes before the approval;
#   3. the rules: no new code in a system list, one pending change per value, maker never approves;
#   4. after the approval the dropdown API serves the new value at once (new ETag), in English and Hindi;
#   5. the browser revalidation: 304 while nothing changed.
# Usage: scripts/reference-e2e.sh   (BASE, ONLINE_LOG override the local defaults)
# Re-runnable: each run adds a value with a new code and relabels JOB_STATUS.PAUSED with the run's stamp.
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
  local status
  status=$(curl -sk -c "$DIR/$who.jar" -b "$DIR/$who.jar" -o "$DIR/out" -w '%{http_code}' -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(xsrf "$who")" --data "$body")
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
jq_() { python -c "import json,sys;d=json.load(open(sys.argv[1]));print(eval(sys.argv[2]))" "$DIR/out" "$1"; }

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

A=sysadmin@fci-demo.test
C=checker.hq@fci-demo.test
U=maker.delhi@fci-demo.test
STAMP=$(date +%H%M%S)
login $A sysadmin HQ001
login $C manager HQ001
login $U payroll DH001
lists() { call "$1" GET /v1/reference/lists >/dev/null; }
etag() { curl -sk -b "$DIR/$1.jar" -D - -o /dev/null "$BASE/v1/reference/lists" | awk 'tolower($1)=="etag:"{print $2}' | tr -d '\r'; }

echo "== 1. Who may use M-16"
s=$(call $A GET /v1/masters/reference-lists)
[ "$s" = 200 ] && ok "System Administrator reads M-16: $(jq_ "len(d)") lists" || bad "admin read $s"
s=$(call $U GET /v1/masters/reference-lists)
[ "$s" = 403 ] && ok "Payroll Maker cannot open M-16 ($s)" || bad "maker read $s"
s=$(call $U POST /v1/masters/reference-lists/JOB_STATUS/values/PAUSED '{"labelEn":"x","reason":"tamper"}')
[ "$s" = 403 ] && ok "Payroll Maker cannot propose a change ($s)" || bad "maker change $s"
s=$(call $C GET /v1/masters/reference-lists)
[ "$s" = 403 ] && ok "HQ Checker approves in W-01 but has no M-16 screen ($s)" || bad "checker read $s"
call $U GET /menu >/dev/null; [ "$(jq_ "any(i['code']=='M-16' for s in d['sections'] for i in s['items'])")" = False ] && ok "M-16 not in the maker's menu" || bad "maker menu"
call $A GET /menu >/dev/null; [ "$(jq_ "any(i['code']=='M-16' for s in d['sections'] for i in s['items'])")" = True ] && ok "M-16 in the administrator's menu" || bad "admin menu"

echo "== 2. Rules"
s=$(call $A POST /v1/masters/reference-lists/JOB_STATUS/values "{\"code\":\"E2E_$STAMP\",\"labelEn\":\"x\",\"effectiveFrom\":\"$(date +%F)\",\"reason\":\"e2e\"}")
[ "$s" = 422 ] && ok "no new code in a system list ($(jq_ "d['errorCode']"))" || bad "system list add $s"
s=$(call $A POST /v1/masters/reference-lists/JOB_STATUS/values/NOPE '{"labelEn":"x","reason":"e2e"}')
[ "$s" = 404 ] && ok "unknown value refused ($(jq_ "d['errorCode']"))" || bad "unknown value $s"
s=$(call $A POST "/v1/masters/reference-lists/JOB_STATUS/values/PAUSED" '{"labelEn":"","reason":"e2e"}')
[ "$s" = 400 ] && ok "a blank label is refused ($s)" || bad "blank label $s"

echo "== 3. Relabel JOB_STATUS.PAUSED (English and Hindi) and add a value to an open list"
lists $U; BEFORE=$(jq_ "[v['label'] for v in d['lists']['JOB_STATUS']['values'] if v['code']=='PAUSED'][0]")
E1=$(etag $U)
s=$(call $A POST /v1/masters/reference-lists/JOB_STATUS/values/PAUSED "{\"labelEn\":\"On hold $STAMP\",\"labelHi\":\"\u0930\u0941\u0915\u093e \u0939\u0941\u0906 $STAMP\",\"sortOrder\":50,\"reason\":\"E2E: clearer status name\"}")
[ "$s" = 202 ] && ok "change proposed: request $(jq_ "d['approvalId']")" || bad "propose change $s $(cat "$DIR/out")"
CA=$(jq_ "d['approvalId']")
s=$(call $A POST /v1/masters/reference-lists/JOB_STATUS/values/PAUSED "{\"labelEn\":\"Again\",\"reason\":\"e2e\"}")
[ "$s" = 409 ] && ok "a second change of the same value waits ($(jq_ "d['errorCode']"))" || bad "second change $s"
OPEN=$(call $A GET /v1/masters/reference-lists >/dev/null; jq_ "[l['code'] for l in d if not l['system']][0]")
s=$(call $A POST "/v1/masters/reference-lists/$OPEN/values" "{\"code\":\"E2E_$STAMP\",\"labelEn\":\"E2E value $STAMP\",\"labelHi\":\"\u092a\u0930\u0940\u0915\u094d\u0937\u0923 $STAMP\",\"sortOrder\":999,\"effectiveFrom\":\"$(date +%F)\",\"reason\":\"E2E: new value\"}")
[ "$s" = 202 ] && ok "new value E2E_$STAMP proposed for open list $OPEN: request $(jq_ "d['approvalId']")" || bad "propose new $s $(cat "$DIR/out")"
NA=$(jq_ "d['approvalId']")
lists $U; NOW=$(jq_ "[v['label'] for v in d['lists']['JOB_STATUS']['values'] if v['code']=='PAUSED'][0]")
[ "$NOW" = "$BEFORE" ] && ok "nothing changed before the approval (still '$NOW')" || bad "changed early: $NOW"
[ "$(etag $U)" = "$E1" ] && ok "dropdown ETag unchanged before the approval" || bad "etag moved early"

echo "== 4. Separation of duties and the checker's office"
s=$(call $A POST "/v1/approvals/$CA/decision" '{"decision":"APPROVE"}')
[ "$s" = 403 ] && ok "the administrator cannot approve its own change ($s)" || bad "self approve $s"
login ddo.delhi@fci-demo.test manager DH001
s=$(call ddo.delhi@fci-demo.test POST "/v1/approvals/$CA/decision" '{"decision":"APPROVE"}')
[ "$s" = 403 ] && ok "a Payroll Checker outside HQ cannot approve ($s)" || bad "DH001 checker approve $s"
s=$(governed $C POST "/v1/approvals/$CA/decision" '{"decision":"APPROVE"')
[ "$s" = 200 ] && ok "HQ Checker approved the change" || bad "approve change $s $(cat "$DIR/out")"
s=$(governed $C POST "/v1/approvals/$NA/decision" '{"decision":"APPROVE"')
[ "$s" = 200 ] && ok "HQ Checker approved the new value" || bad "approve new $s $(cat "$DIR/out")"

echo "== 5. Every screen gets it at once"
lists $U
v=$(jq_ "[(x['label'], x['labelHi']=='\u0930\u0941\u0915\u093e \u0939\u0941\u0906 $STAMP', x['sortOrder'], x['active']) for x in d['lists']['JOB_STATUS']['values'] if x['code']=='PAUSED'][0]")
[ "$v" = "('On hold $STAMP', True, 50, True)" ] && ok "JOB_STATUS.PAUSED now (label, Hindi label as sent, order, in force) = $v" || bad "after approval $v"
n=$(jq_ "[(x['label'], x['active'], x['labelHi']=='\u092a\u0930\u0940\u0915\u094d\u0937\u0923 $STAMP') for x in d['lists']['$OPEN']['values'] if x['code']=='E2E_$STAMP']")
[ "$n" = "[('E2E value $STAMP', True, True)]" ] && ok "new value in force in $OPEN: $n" || bad "new value $n"
E2=$(etag $U)
[ "$E2" != "$E1" ] && ok "dropdown ETag moved: $E1 -> $E2" || bad "etag not moved"
s=$(curl -sk -b "$DIR/$U.jar" -o /dev/null -w '%{http_code}' -H "If-None-Match: $E2" "$BASE/v1/reference/lists")
[ "$s" = 304 ] && ok "browser revalidation with the new ETag: 304" || bad "revalidation $s"
call $A GET /v1/masters/reference-lists >/dev/null
p=$(jq_ "[(x['versionNo'], x['changedBy'], x['pending']) for l in d if l['code']=='JOB_STATUS' for x in l['values'] if x['code']=='PAUSED'][0]")
ok "M-16 row: (version, changed by, pending) = $p"

[ $fail = 0 ] && echo "All reference-list checks passed." || { echo "$fail reference-list check(s) FAILED"; exit 1; }
