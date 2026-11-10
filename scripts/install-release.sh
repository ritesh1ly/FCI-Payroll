#!/bin/sh
# Installs a release bundle built by CI, or rolls back to the previous one (runbook "Deploy a
# release"). Run as root on the application host.
#
#   install-release.sh <bundle-dir>       install and switch to the bundle
#   install-release.sh --rollback         switch back to the previous release
#
# A bundle directory contains:
#   VERSION                 e.g. 1.4.0+3f2a9c1 (letters, digits, . + - only)
#   SHA256SUMS              sha256sum of every other file (verified before anything changes)
#   fci-payroll.war         the backend (./gradlew :backend:bootWar), run as online AND as batch
#   web/                    the Next.js standalone server (.next/standalone + .next/static)
#
# The backend and the frontend always switch together. Order on switch: stop frontend, batch,
# online; switch the links; start online (it migrates the Oracle schema), batch, frontend.
set -eu

APPS=${FCI_APPS_DIR:-/opt/fci/apps}
WEB=${FCI_WEB_DIR:-/opt/fci/web}
ONLINE_MGMT=${FCI_ONLINE_MGMT_URL:-http://127.0.0.1:8081}
BATCH_MGMT=${FCI_BATCH_MGMT_URL:-http://127.0.0.1:8091}
BATCH_HTTP=${FCI_BATCH_HTTP_URL:-http://127.0.0.1:8090}
PUBLIC=${FCI_PUBLIC_URL:-https://payroll.fci.gov.in}
KEEP=${FCI_KEEP_RELEASES:-5}
here=$(cd "$(dirname "$0")" && pwd)

log() { printf '%s install-release: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"

wait_ready() {
  name=$1; url=$2; i=0
  until curl -fsS -o /dev/null --max-time 3 "$url"; do
    i=$((i + 1)); [ "$i" -le 90 ] || return 1; sleep 2
  done
  log "$name is ready"
}

# switch <version>: points every "current" link at <version> and restarts the services. Returns
# non-zero (never exits) on any failure, so the caller can roll back.
switch() {
  target=$1
  if [ ! -f "$APPS/releases/$target/fci-payroll.war" ] || [ ! -f "$WEB/releases/$target/server.js" ]; then
    log "release $target is incomplete"; return 1
  fi
  log "switching to $target"
  systemctl stop fci-frontend fci-tomcat@batch fci-tomcat@online || return 1
  # Expanded WARs of the old release: Tomcat must unpack the new one.
  rm -rf -- /opt/tomcat-online/webapps-fci/api /opt/tomcat-batch/webapps-fci/api
  ln -sfn "releases/$target" "$APPS/current.new" && mv -T "$APPS/current.new" "$APPS/current" || return 1
  ln -sfn "releases/$target" "$WEB/current.new" && mv -T "$WEB/current.new" "$WEB/current" || return 1
  systemctl start fci-tomcat@online || return 1
  wait_ready online "$ONLINE_MGMT/api/actuator/health/readiness" || return 1
  systemctl start fci-tomcat@batch || return 1
  wait_ready batch "$BATCH_MGMT/api/actuator/health/readiness" || return 1
  systemctl start fci-frontend || return 1
  wait_ready frontend "http://127.0.0.1:3000/" || return 1
  if ! nginx -t -q; then log "nginx configuration test failed"; return 1; fi
  systemctl reload nginx
}

if [ "${1:-}" = "--rollback" ]; then
  previous=$(cat "$APPS/previous" 2>/dev/null) || die "no previous release recorded"
  switch "$previous" || die "rollback to $previous did not become ready; see the runbook"
  log "rolled back to $previous"
  exit 0
fi

bundle=${1:?usage: install-release.sh <bundle-dir> | --rollback}
version=$(cat "$bundle/VERSION")
printf '%s' "$version" | grep -Eq '^[A-Za-z0-9.+-]{1,64}$' || die "invalid VERSION"
(cd "$bundle" && sha256sum --quiet -c SHA256SUMS) || die "checksum verification failed"
for f in fci-payroll.war web/server.js; do
  [ -f "$bundle/$f" ] || die "bundle lacks $f"
done
# The database (owner 2026-11-01): DEV, UAT and production run on the on-premises Oracle (profile oracle in
# the instances' env files) and get NO H2 at all - no H2 classpath, no H2 server, no H2 dump. H2 is only for
# local test runs and Playwright (deploy-local.ps1). The bundle's h2/ folder is not installed.
for env in "${FCI_ONLINE_ENV:-/etc/fci/tomcat-online.env}" "${FCI_BATCH_ENV:-/etc/fci/tomcat-batch.env}"; do
  grep -Eq '^FCI_PROFILE=.*\boracle\b' "$env" 2>/dev/null \
    || die "$env must set FCI_PROFILE=<env>,oracle: a server runs on Oracle only (H2 is for local tests)"
done
[ ! -e "$APPS/releases/$version" ] || die "release $version is already installed"

current=$(basename "$(readlink "$APPS/current" 2>/dev/null || echo none)")
log "installing $version (current: $current)"

install -d -m 0750 -o root -g tomcat "$APPS/releases/$version"
install -m 0640 -o root -g tomcat "$bundle/fci-payroll.war" "$APPS/releases/$version/"
install -d -m 0755 "$WEB/releases/$version"
cp -R "$bundle/web/." "$WEB/releases/$version/"
chmod -R u=rwX,go=rX "$WEB/releases/$version"

# The restore point before the schema can change is the DBA's RMAN backup of the Oracle database, taken
# before the release window (Flyway migrates at the online start; rollbacks: db/rollback/oracle).
log "database: Oracle (profile oracle); restore point: the DBA's RMAN backup taken before this release"


[ "$current" = "none" ] || printf '%s\n' "$current" > "$APPS/previous"
if switch "$version" && "$here/smoke-test.sh" "$PUBLIC" "$ONLINE_MGMT" "$BATCH_MGMT" "$BATCH_HTTP"; then
  log "release $version is live"
else
  [ "$current" != "none" ] || die "first installation failed; nothing to roll back to"
  log "release $version failed its checks: rolling back to $current"
  log "NOTE: Flyway does not undo migrations; apply db/rollback/<vendor>/U*.sql for versions newer than $current first if the schema changed"
  switch "$current" || die "rollback to $current did not become ready; see the runbook"
  die "rolled back to $current"
fi

# Keep the newest $KEEP releases (by install time); never the current or previous one.
previous=$(cat "$APPS/previous" 2>/dev/null || echo none)
for dir in "$APPS/releases" "$WEB/releases"; do
  ls -1t "$dir" | tail -n +"$((KEEP + 1))" | while read -r old; do
    [ "$old" = "$version" ] || [ "$old" = "$previous" ] && continue
    log "removing old release $old from $dir"
    rm -rf -- "${dir:?}/$old"
  done
done
