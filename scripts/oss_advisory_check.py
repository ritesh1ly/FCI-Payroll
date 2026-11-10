"""OSS vulnerability check of the backend's resolved runtime dependencies against the GitHub Advisory
Database (REST, no key needed; GITHUB_TOKEN raises the rate limit). The NVD-backed OWASP
Dependency-Check (`dependencyCheckAggregate`) needs NVD_API_KEY and runs in CI.

Usage: ./gradlew :backend:dependencies --configuration runtimeClasspath > deps.log
       python -I scripts/oss_advisory_check.py deps.log [--fail-on high]
Lists every advisory that affects a resolved version (severity, GHSA / CVE, package, patched
version) and exits 1 when one reaches the severity given (default high).
"""

import json
import os
import re
import sys
import urllib.parse
import urllib.request

API = "https://api.github.com/advisories"
COORD = re.compile(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.+-]*)(?: -> ([0-9][A-Za-z0-9_.+-]*))?")
RANK = {"low": 1, "medium": 2, "high": 3, "critical": 4}


def components(log: str) -> dict[str, str]:
    found: dict[str, str] = {}
    for m in COORD.finditer(log):
        group, name, version, resolved = m.groups()
        found[f"{group}:{name}"] = resolved or version
    return found


def advisories(batch: list[str]) -> list[dict]:
    out: list[dict] = []
    page = 1
    while True:
        query = urllib.parse.urlencode(
            {"ecosystem": "maven", "affects": ",".join(batch), "per_page": 100, "page": page}
        )
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "fci-payroll-oss-check"}
        token = os.environ.get("GITHUB_TOKEN")
        if token:
            headers["Authorization"] = "Bearer " + token
        with urllib.request.urlopen(urllib.request.Request(API + "?" + query, headers=headers), timeout=120) as resp:
            rows = json.load(resp)
        out.extend(rows)
        if len(rows) < 100:
            return out
        page += 1


def main() -> int:
    log = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    fail_on = sys.argv[sys.argv.index("--fail-on") + 1] if "--fail-on" in sys.argv else "high"
    comps = components(log)
    pairs = [f"{name}@{version}" for name, version in sorted(comps.items())]
    print(f"{len(pairs)} components")
    worst = 0
    rows = []
    for i in range(0, len(pairs), 40):
        for a in advisories(pairs[i : i + 40]):
            for v in a.get("vulnerabilities", []):
                pkg = v.get("package") or {}
                name = pkg.get("name", "")
                if pkg.get("ecosystem") != "maven" or name not in comps:
                    continue
                sev = a.get("severity", "unknown")
                worst = max(worst, RANK.get(sev, 0))
                rows.append((RANK.get(sev, 0), sev, a.get("cve_id") or a.get("ghsa_id"), name, comps[name],
                             v.get("vulnerable_version_range", ""), v.get("first_patched_version") or "-"))
    for _, sev, cve, name, version, rng, patched in sorted(set(rows), reverse=True):
        print(f"{sev:8} {cve:20} {name}@{version}  affected {rng}  patched {patched}")
    print(f"{len(set(rows))} advisories, worst severity rank {worst}")
    return 1 if worst >= RANK.get(fail_on, 3) else 0


if __name__ == "__main__":
    sys.exit(main())
