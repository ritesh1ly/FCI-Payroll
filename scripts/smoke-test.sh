#!/bin/sh
# Post-deployment smoke test (runbook "Deploy a release", step 6). Read-only: it signs nobody in
# and changes nothing.
#
#   scripts/smoke-test.sh [base-url] [online-mgmt-url] [batch-mgmt-url] [batch-http-url]
#     base-url         public URL through NGINX            (default https://payroll.fci.gov.in)
#     online-mgmt-url  online instance management connector (default http://127.0.0.1:8081)
#     batch-mgmt-url   batch instance management connector  (default http://127.0.0.1:8091)
#     batch-http-url   batch instance application connector (default http://127.0.0.1:8090)
#   SMOKE_CURL_OPTS="-k"         for a self-signed test certificate
#   SMOKE_METRICS_PASSWORD=...   also proves the scheduler split via /actuator/scheduledtasks
#
# Exit status 0 when every check passes; each failure is printed.
set -u

base=${1:-https://payroll.fci.gov.in}
# The pages' base path (next.config.ts basePath, /nginx by default); the API is at /api/.
page=${SMOKE_PAGE_PATH:-/nginx}
home=$page
online=${2:-http://127.0.0.1:8081}
batch=${3:-http://127.0.0.1:8091}
batch_http=${4:-http://127.0.0.1:8090}
opts=${SMOKE_CURL_OPTS:-}
failures=0
headers=$(mktemp)
body=$(mktemp)
trap 'rm -f "$headers" "$body"' EXIT

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }

# check <description> <expected status> <url> [extra curl args...]
check() {
  description=$1; expected=$2; url=$3; shift 3
  # shellcheck disable=SC2086
  status=$(curl -s $opts -o "$body" -D "$headers" -w '%{http_code}' --max-time 10 "$@" "$url")
  if [ "$status" = "$expected" ]; then pass "$description ($status)"; else fail "$description: expected $expected, got $status"; fi
}

# header_has <description> <header-regex>  (case-insensitive, against the last response)
header_has() {
  if grep -qiE "$2" "$headers"; then pass "$1"; else fail "$1: no header matching /$2/"; fi
}
header_lacks() {
  if grep -qiE "$2" "$headers"; then fail "$1: unexpected header matching /$2/"; else pass "$1"; fi
}
body_has() {
  if grep -qE "$2" "$body"; then pass "$1"; else fail "$1: body does not match /$2/"; fi
}
body_lacks() {
  if grep -qE "$2" "$body"; then fail "$1: body unexpectedly matches /$2/"; else pass "$1"; fi
}

echo "Public endpoint: $base"
check "login page (server-side rendered)" 200 "$base$home"
body_has "  ... sign-in form rendered on the server (user ID + password)" "name=\"username\""
body_lacks "  ... no persona or location picker before sign-in" "\"personas\""
header_has "CSP with a per-request nonce" "^content-security-policy: default-src 'none'; script-src 'self' 'nonce-"
header_lacks "CSP never allows unsafe-inline or unsafe-eval" "^content-security-policy:.*unsafe-(inline|eval)"
header_has "HSTS" "^strict-transport-security: max-age=63072000"
header_has "nosniff" "^x-content-type-options: nosniff"
header_has "frame protection" "^x-frame-options: DENY"
header_has "COOP" "^cross-origin-opener-policy: same-origin"
header_has "pages are never cached" "^cache-control: .*no-store"
header_lacks "no server version disclosed" "^server: .*[0-9]"
header_lacks "no framework banner" "^x-powered-by"

check "static assets are immutable" 200 "$base$(curl -s $opts --max-time 10 "$base$home" | grep -oE "$page/_next/static/[^\"]+\.js" | head -1)"
header_has "  ... with a one-year cache" "^cache-control: public, max-age=31536000, immutable"

check "workspace without a session redirects to login (server-side guard)" 307 "$base$page/payroll"
header_has "  ... to the sign-in page" "^location: $page/?\s*$"
check "Actuator is not reachable from outside" 404 "$base/api/actuator/health"
check "hidden files are not served" 404 "$base/.env"
check "TRACE is refused" 405 "$base$home" -X TRACE

check "CSRF bootstrap" 204 "$base/api/auth/csrf"
header_has "  ... XSRF-TOKEN cookie is Secure and SameSite=Strict" "^set-cookie: XSRF-TOKEN=.*Secure.*SameSite=Strict|^set-cookie: XSRF-TOKEN=.*SameSite=Strict.*Secure"
header_has "  ... correlation id is returned" "^x-correlation-id: [A-Za-z0-9-]{8,64}"
check "anonymous session is rejected" 401 "$base/api/auth/me"
header_has "  ... as RFC 9457 problem details" "^content-type: application/problem\+json"
header_has "  ... with a locked-down CSP" "^content-security-policy: default-src 'none'; frame-ancestors 'none'"
check "business API needs a session" 401 "$base/api/v1/jobs"
check "login options are public" 200 "$base/api/auth/login-options"
check "label bundle is served with an ETag" 200 "$base/api/i18n/hi"
header_has "  ... ETag" "^etag: \""
check "unsafe POST without CSRF token is refused" 403 "$base/api/auth/login" -X POST -H 'Content-Type: application/json' --data '{}'

echo "Online instance (management): $online"
check "online liveness" 200 "$online/api/actuator/health/liveness"
check "online readiness" 200 "$online/api/actuator/health/readiness"
check "online metrics need credentials" 401 "$online/api/actuator/prometheus"

echo "Batch instance (management): $batch"
check "batch liveness" 200 "$batch/api/actuator/health/liveness"
check "batch readiness" 200 "$batch/api/actuator/health/readiness"
check "batch metrics need credentials" 401 "$batch/api/actuator/prometheus"
check "batch instance serves no browser API (deny-all)" 403 "$batch_http/api/auth/login-options"
check "batch instance has no login (deny-all)" 403 "$batch_http/api/auth/csrf"

if [ -n "${SMOKE_METRICS_PASSWORD:-}" ]; then
  echo "Scheduler split (/actuator/scheduledtasks)"
  check "online scheduled tasks" 200 "$online/api/actuator/scheduledtasks" -u "metrics:$SMOKE_METRICS_PASSWORD"
  body_lacks "  ... online runs no poller and no scheduled job" "JobQueuePoller|ScheduledJobs|HousekeepingJob|cleanUpExpiredSessions"
  check "batch scheduled tasks" 200 "$batch/api/actuator/scheduledtasks" -u "metrics:$SMOKE_METRICS_PASSWORD"
  body_has "  ... batch runs the job-queue poller" "JobQueuePoller\.poll"
  body_has "  ... batch runs the month-end and interface-pull schedules" "ScheduledJobs\.monthEnd"
fi

if [ "$failures" -eq 0 ]; then echo "All checks passed."; else echo "$failures check(s) failed."; fi
exit "$failures"
