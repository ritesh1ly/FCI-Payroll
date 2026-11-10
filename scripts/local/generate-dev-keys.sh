#!/bin/sh
# Generates development and test secrets for running both WARs in a local Tomcat behind a local
# NGINX (ADR-0013; deploy-local.ps1 calls the same generator). Nothing generated is ever
# committed (scripts/local/generated/ is ignored).
#
#   scripts/local/generate-dev-keys.sh [output-dir]      default: scripts/local/generated
#
# Produces the same layout as production (scripts/GenerateSecrets.java) plus a self-signed
# TLS certificate for NGINX on localhost (tls/dev.crt, tls/dev.key).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/generated"}

command -v java >/dev/null 2>&1 || { echo "java (JDK 25) is required" >&2; exit 1; }
java "$here/../scripts/GenerateSecrets.java" --out "$out" --force

# Git Bash on Windows rewrites "/CN=..." into a file path unless MSYS_NO_PATHCONV is set; a native
# openssl then needs Windows paths, which cygpath provides. Neither applies on Linux.
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

if command -v openssl >/dev/null 2>&1; then
  mkdir -p "$out/tls"
  MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:3072 -sha256 -days 397 -nodes \
    -keyout "$(native "$out/tls/dev.key")" -out "$(native "$out/tls/dev.crt")" \
    -subj "/CN=localhost/O=FCI development" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  chmod 600 "$out/tls/dev.key"
  echo "TLS certificate for NGINX: $out/tls/dev.crt (self-signed, localhost only)"
else
  echo "openssl not found: no NGINX TLS certificate generated" >&2
fi
