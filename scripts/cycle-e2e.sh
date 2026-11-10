#!/bin/bash
# End-to-end test of the payroll cycle (C-02) on the target DDL v4.0 tables (docs/db/DDL_V4_ALIGNMENT.md
# section 8), against a deployed stack through NGINX (scripts/local/deploy-local.ps1, profile demo):
#   1. initiate (not ahead of the current period), freeze with OTP;
#   2. dry run (SIMULATION) and computation (ACTUAL) as C-13 jobs, maker submits, checker approves;
#   3. verify (separation of duties), lock (maker requests with OTP, checker approves with OTP);
#   4. nothing recomputes a LOCKED cycle; results immutable;
#   5. DA arrear after a retroactive DA revision (M-04): differentials on arrear_detail.
# Usage: scripts/cycle-e2e.sh   (BASE, ONLINE_LOG, P override the local defaults; P = the current period)
# Not re-runnable on the same database: the cycle ends LOCKED. Redeploy (in-memory H2) to run it again.
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

P=${P:-2026-07}
M=maker.delhi@fci-demo.test
C=ddo.delhi@fci-demo.test
wait_job() {
  local id=$1 st; shift
  for i in $(seq 1 60); do
    call $M GET "/v1/jobs/$id" >/dev/null; st=$(jq_ "d['job']['status']")
    for w in "$@"; do [ "$st" = "$w" ] && { echo "$st"; return; }; done
    sleep 2
  done
  echo "TIMEOUT($st)"
}
approve_job() {
  call $M GET "/v1/jobs/$1" >/dev/null
  local aid; aid=$(jq_ "d['job']['approvalId']")
  local s; s=$(call $C POST "/v1/approvals/$aid/decision" '{"decision":"APPROVE"}')
  [ "$s" = 200 ] && ok "checker approved job $1" || bad "approve job $1: $s $(cat "$DIR/out")"
}
cyc() { call $1 GET "/v1/cycles/$P" >/dev/null; jq_ "$2"; }
submit() { # type params-json reason -> status in $SS, job id in $J
  SS=$(call $M POST /v1/jobs "{\"jobType\":\"$1\",\"params\":$2,\"reason\":\"$3\"}")
  J=$(jq_ "d.get('id')")
}
freeze() { # the freeze body holds only the OTP
  local s; s=$(call $1 POST "/v1/cycles/$P/freeze" '{}')
  if [ "$s" = 428 ]; then
    local id code; id=$(challenge); sleep 1; code=$(lastcode)
    s=$(call $1 POST "/v1/cycles/$P/freeze" "{\"otp\":{\"challengeId\":$id,\"code\":\"$code\"}}")
  fi
  echo "$s"
}

login $M payroll DH001
login $C manager DH001
call $M GET /v1/jobs/types >/dev/null
DH=$(jq_ "[l['id'] for l in d['locations'] if l['code']=='DH001'][0]")
PARAMS="{\"locationId\":\"$DH\",\"payPeriod\":\"$P\"}"

echo "== C-02 $P: initiate, freeze with OTP"
s=$(call $M POST "/v1/cycles/2026-09/initiate")
[ "$s" = 422 ] && ok "initiate of a period after the current one refused ($(jq_ "d['errorCode']"))" || bad "future initiate $s"
st=$(cyc $M "d['status']")
if [ "$st" = None ]; then
  s=$(call $M POST "/v1/cycles/$P/freeze" '{}')
  [ "$s" = 409 ] && ok "freeze before initiate refused ($(jq_ "d['errorCode']"))" || bad "freeze early $s"
  s=$(call $M POST "/v1/cycles/$P/initiate")
  [ "$s" = 200 ] && ok "initiated: $(jq_ "d['status']")" || bad "initiate $s $(cat "$DIR/out")"
elif [ "$st" = INITIATED ]; then ok "cycle already INITIATED (an earlier dry run opened it)"
else bad "precondition status $st"; fi
s=$(call $M POST "/v1/cycles/$P/initiate")
[ "$s" = 409 ] && ok "second initiate refused ($(jq_ "d['errorCode']"))" || bad "re-initiate $s"
s=$(call $M POST "/v1/cycles/$P/freeze" '{}')
[ "$s" = 428 ] && ok "freeze without OTP -> 428" || bad "freeze no OTP $s"
s=$(freeze $M)
[ "$s" = 200 ] && ok "frozen with OTP: $(jq_ "d['status']") by $(jq_ "d['frozenBy']")" || bad "freeze $s $(cat "$DIR/out")"
s=$(call $C POST "/v1/cycles/$P/verify")
[ "$s" = 409 ] && ok "verify of a FROZEN cycle refused ($(jq_ "d['errorCode']"))" || bad "verify early $s"

echo "== Dry run before computation (SIMULATION, cycle state unchanged)"
submit PAYROLL_DRY_RUN "$PARAMS" preview; s=$SS; approve_job $J
st=$(wait_job $J COMPLETED FAILED); [ "$st" = COMPLETED ] && ok "dry run COMPLETED: $(jq_ "d['job']['resultSummary']")" || bad "dry run $st"
v=$(cyc $M "(d['status'], d['resultsRunType'])")
[ "$v" = "('FROZEN', 'SIMULATION')" ] && ok "cycle still FROZEN, preview shown from the simulation" || bad "after dry run $v"

echo "== ACTUAL computation via C-13 (maker submits, checker approves)"
submit PAYROLL_COMPUTATION "$PARAMS" "computation"; s=$SS
[ "$J" != None ] && ok "computation submitted ($s)" || bad "submit computation $s $(cat "$DIR/out")"
approve_job $J
st=$(wait_job $J COMPLETED FAILED); [ "$st" = COMPLETED ] && ok "computation COMPLETED: $(jq_ "d['job']['resultSummary']")" || bad "computation $st $(cat "$DIR/out")"
call $M GET "/v1/cycles/$P" >/dev/null
[ "$(jq_ "d['status']")" = COMPUTED ] && ok "cycle COMPUTED, run of record $(jq_ "d['currentRunId']"), members $(jq_ "d['totals']['members']"), net $(jq_ "d['totals']['net']")" || bad "cycle after compute $(jq_ "d['status']")"
codes=$(jq_ "sorted(c['payCode'] for c in d['totals']['components'])")
[ "$codes" = "[101, 111, 121, 131]" ] && ok "components $codes" || bad "components $codes"
ok "runs: $(jq_ "[(r['type'], r['status'], r['results'], r['unitsCommitted'], r['unitsFailed']) for r in d['runs']]")"
s=$(call $M GET "/v1/cycles/$P/results?page=0&size=5")
RID=$(jq_ "d[0]['resultId']")
[ "$s" = 200 ] && ok "results page: $(jq_ "len(d)") rows, first $(jq_ "(d[0]['eis'], d[0]['gross'], d[0]['net'], d[0]['status'], d[0]['locked'])")" || bad "results $s"
s=$(call $M GET "/v1/cycles/$P/results/$RID")
[ "$s" = 200 ] && ok "result lines: $(jq_ "[(l['payCode'], l['amount'], l['rate']) for l in d]")" || bad "lines $s"

echo "== Verify: separation of duties"
s=$(call $M POST "/v1/cycles/$P/verify")
[ "$s" = 403 ] && ok "maker cannot verify ($s $(jq_ "d.get('errorCode')"))" || bad "maker verify $s $(cat "$DIR/out")"
s=$(call $C POST "/v1/cycles/$P/verify")
[ "$s" = 200 ] && ok "checker verified: $(jq_ "d['status']") by $(jq_ "d['verifiedBy']")" || bad "verify $s $(cat "$DIR/out")"

echo "== Lock: maker request (OTP), checker approval (OTP)"
s=$(governed $M POST "/v1/cycles/$P/lock" '{"reason":"E2E lock"')
[ "$s" = 200 ] && ok "lock requested: approval $(jq_ "d['lockApprovalId']") $(jq_ "d['lockApprovalStatus']")" || bad "lock request $s $(cat "$DIR/out")"
LA=$(jq_ "d['lockApprovalId']")
s=$(call $M POST "/v1/approvals/$LA/decision" '{"decision":"APPROVE"}')
{ [ "$s" = 403 ] || [ "$s" = 409 ]; } && ok "maker cannot approve own lock ($s $(jq_ "d.get('errorCode')"))" || bad "self-approve $s"
s=$(governed $C POST "/v1/approvals/$LA/decision" '{"decision":"APPROVE"')
[ "$s" = 200 ] && ok "checker approved the lock with OTP" || bad "lock approve $s $(cat "$DIR/out")"
call $M GET "/v1/cycles/$P" >/dev/null
[ "$(jq_ "d['status']")" = LOCKED ] && ok "cycle LOCKED by $(jq_ "d['lockedBy']")" || bad "after lock $(jq_ "d['status']")"
call $M GET "/v1/cycles/$P/results?page=0&size=200" >/dev/null
[ "$(jq_ "all(r['locked'] for r in d)")" = True ] && ok "all $(jq_ "len(d)") results is_locked" || bad "results not locked"

echo "== After the lock"
for T in PAYROLL_DRY_RUN PAYROLL_COMPUTATION; do
  submit $T "$PARAMS" "after lock"; s=$SS
  if [ "$J" = None ]; then ok "$T of a locked cycle refused at submit ($s $(jq_ "d.get('errorCode')"))"
  else approve_job $J; st=$(wait_job $J COMPLETED FAILED); [ "$st" = FAILED ] && ok "$T of a locked cycle FAILED" || bad "$T on locked: $st"; fi
done
[ "$(cyc $M "d['status']")" = LOCKED ] && ok "cycle still LOCKED (forward only)" || bad "status changed"

echo "== DA arrear: no revision yet -> no differential"
submit DA_ARREAR "{\"locationId\":\"$DH\",\"fromPeriod\":\"2026-06\",\"toPeriod\":\"$P\"}" arrear; s=$SS; approve_job $J
st=$(wait_job $J COMPLETED FAILED); [ "$st" = COMPLETED ] && ok "DA arrear COMPLETED: $(jq_ "d['job']['resultSummary']")" || bad "arrear $st $(cat "$DIR/out")"
[ "$(jq_ "'with a differential 0,' in d['job']['resultSummary']")" = True ] && ok "no differential while the DA rate is unchanged" || bad "unexpected differential"

echo "== Retroactive DA IDA revision (M-04: Rule Administrator proposes, another Rule Approver approves)"
R=su.hq@fci-demo.test; A=rule.approver@fci-demo.test
login $R superuser HQ001
call $R GET /v1/masters/staff-rates/DA_IDA/versions >/dev/null
read -r FROM RATE <<<"$(jq_ "' '.join([str(__import__('datetime').date.fromisoformat(max(x['effectiveFrom'] for x in d))+__import__('datetime').timedelta(days=1)), str(int([x for x in d if x['state']=='APPROVED' and x['effectiveTo'] is None][0]['lines'][0]['rateValue'])+4)])")"
if [[ "$FROM" > "$P-01" ]]; then
  bad "the next free DA_IDA date $FROM is after $P-01: a retroactive revision for $P is not possible on this database (redeploy)"
else
  s=$(governed $R POST /v1/masters/staff-rates/DA_IDA/versions "{\"effectiveFrom\":\"$FROM\",\"lines\":[{\"payPattern\":\"IDA\",\"rateCode\":\"DA_PCT\",\"rateValue\":$RATE,\"unit\":\"PERCENT\"}],\"reason\":\"E2E: retroactive DA revision\"")
  [ "$s" = 202 ] && ok "DA IDA $RATE% from $FROM proposed (202)" || bad "propose DA $s $(cat "$DIR/out")"
  MID=$(jq_ "d['approvalId']")
  login $A ruleapprover HQ001
  s=$(call $A POST "/v1/approvals/$MID/decision" '{"decision":"APPROVE"}')
  [ "$s" = 200 ] && ok "rule approver approved the revision" || bad "approve revision $s $(cat "$DIR/out")"
  call $M GET "/v1/cycles/$P/results?page=0&size=200" >/dev/null
  [ "$(jq_ "all(r['locked'] for r in d)")" = True ] && ok "the locked $P results are untouched by the revision" || bad "results changed"
  submit DA_ARREAR "{\"locationId\":\"$DH\",\"fromPeriod\":\"2026-06\",\"toPeriod\":\"$P\"}" "DA revision arrear"; s=$SS; approve_job $J
  st=$(wait_job $J COMPLETED FAILED); sum=$(jq_ "d['job']['resultSummary']")
  [ "$st" = COMPLETED ] && ok "DA arrear COMPLETED: $sum" || bad "arrear $st $sum"
  n=$(python -c "import re,sys;print(re.search(r'with a differential (\d+)',sys.argv[1]).group(1))" "$sum")
  [ "${n:-0}" -gt 0 ] && ok "$n IDA member(s) with a DA differential (CDA staff unaffected)" || bad "no differential after the revision"
fi

echo "== HQ cannot act on a unit cycle"
login superuser.hq@fci-demo.test superuser HQ001
s=$(call superuser.hq@fci-demo.test POST "/v1/cycles/$P/lock" '{"reason":"x"}')
{ [ "$s" = 403 ] || [ "$s" = 422 ]; } && ok "HQ lock refused ($s $(jq_ "d.get('errorCode')"))" || bad "HQ lock $s"

[ $fail = 0 ] && echo "All cycle checks passed." || { echo "$fail cycle check(s) FAILED"; exit 1; }
