#!/usr/bin/env bash
# Applies the branch rulesets of .github/rulesets/*.json to the GitHub repository (creates each one,
# or updates it in place when a ruleset of the same name exists). Re-runnable.
#
#   GITHUB_TOKEN  a token of a repository administrator (fine-grained: "Administration: read and write")
#   GITHUB_REPO   owner/name; default: taken from the "origin" remote
#
# Usage: GITHUB_TOKEN=... scripts/github/apply-branch-rulesets.sh [--dry-run]
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
dry_run=false
[[ "${1:-}" == "--dry-run" ]] && dry_run=true

: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a token of a repository administrator}"
repo="${GITHUB_REPO:-$(git -C "$root" remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')}"
api="https://api.github.com/repos/$repo/rulesets"

call() { # method url [body-file]
  local args=(-sS -X "$1" -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json"
    -H "X-GitHub-Api-Version: 2022-11-28" -w '\n%{http_code}')
  [[ $# -ge 3 ]] && args+=(-H "Content-Type: application/json" --data-binary "@$3")
  curl "${args[@]}" "$2"
}

existing="$(call GET "$api?per_page=100")"
status="${existing##*$'\n'}"
[[ "$status" == 200 ]] || { echo "listing rulesets failed (HTTP $status): ${existing%$'\n'*}" >&2; exit 1; }
existing="${existing%$'\n'*}"

for file in "$root"/.github/rulesets/*.json; do
  name="$(python -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$file")"
  id="$(printf '%s' "$existing" | python -c 'import json,sys; n=sys.argv[1]; print(next((str(r["id"]) for r in json.load(sys.stdin) if r["name"]==n), ""))' "$name")"
  if [[ -n "$id" ]]; then method=PUT; url="$api/$id"; else method=POST; url="$api"; fi
  echo "$method $name ($repo)"
  $dry_run && continue
  out="$(call "$method" "$url" "$file")"
  code="${out##*$'\n'}"
  [[ "$code" == 200 || "$code" == 201 ]] || { echo "  failed (HTTP $code): ${out%$'\n'*}" >&2; exit 1; }
  echo "  ok"
done
