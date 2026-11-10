#!/usr/bin/env python3
"""Traceability check (CLAUDE.md section 5).

Every requirement ID cited in code, tests, migrations or E2E specs must exist in the knowledge base:
a typo or an invented ID fails the build. IDs are defined by the "### <ID>" headings of
docs/requirements/kb/05-mappings/requirement-registry-*.md, plus the governance and analysis
registers (CF-nn, OQ-n, DB-n, Q-nnn). Test names may cite an ID in underscore form
(SRS_G_10_..., BR_CYC_010_...).

    python scripts/trace_check.py            # fail (exit 1) on unknown IDs
    python scripts/trace_check.py --report   # also: SRS / BR coverage by code and by tests
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KB = ROOT / "docs" / "requirements" / "kb"

# Requirement-ID shapes (error codes such as AUTH-403-LOCKED or WF-403-SOD are deliberately not matched).
ID = re.compile(
    r"\b(SRS-(?:[A-Z]{1,4}|\d{1,2})-\d{1,3}"
    r"|BR-[A-Z]{2,8}-\d{3}"
    r"|UC-\d{2}|FR-\d{2}|R-0\d"
    r"|OQ-\d{1,2}|CF-\d{2}|DB-\d{1,2}|Q-\d{3})\b"
)
# Underscore form in test method names: SRS_G_10_..., SRS_6_58_..., BR_CYC_010_...
UNDERSCORE = re.compile(r"\b(SRS|BR)_([A-Z]{1,8}|\d{1,2})_(\d{1,3})(?=_|\b)")

SCANNED = [
    ("backend/src/main", "*.java", "code"),
    ("backend/src/test", "*.java", "test"),
    ("backend/src/integrationTest", "*.java", "test"),
    ("backend/src/batchTest", "*.java", "test"),
    ("backend/src/architectureTest", "*.java", "test"),
    ("frontend/src", "*.ts*", "code"),
    ("frontend/tests", "*.ts*", "test"),
    ("frontend/e2e", "*.ts", "test"),
    ("db/migration", "*.sql", "code"),
]


def known_ids() -> set[str]:
    ids: set[str] = set()
    for registry in (KB / "05-mappings").glob("requirement-registry-*.md"):
        ids.update(re.findall(r"^### +(\S+)", registry.read_text(encoding="utf-8"), re.M))
    for register in [
        KB / "00-governance" / "conflicts-and-decisions.md",
        KB / "00-governance" / "open-questions.md",
        KB / "07-analysis" / "clarification-queries.md",
    ]:
        ids.update(ID.findall(register.read_text(encoding="utf-8")))
    return ids


def allowlist() -> dict[str, set[str]]:
    """ID -> files where an unknown ID is knowingly kept (scripts/trace_allow.txt: ID path  # reason)."""
    allowed: dict[str, set[str]] = defaultdict(set)
    path = ROOT / "scripts" / "trace_allow.txt"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if line:
                ident, file = line.split()
                allowed[ident].add(file)
    return allowed


def cited() -> dict[str, dict[str, set[str]]]:
    """id -> kind (code / test) -> files."""
    found: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
    for folder, pattern, kind in SCANNED:
        base = ROOT / folder
        if not base.exists():
            continue
        for path in base.rglob(pattern):
            if any(part in {"node_modules", "generated", "build", "bin"} for part in path.parts):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            rel = path.relative_to(ROOT).as_posix()
            for match in ID.findall(text):
                found[match][kind].add(rel)
            for prefix, middle, number in UNDERSCORE.findall(text):
                ident = f"{prefix}-{middle}-{number}"
                if prefix == "BR":
                    ident = f"BR-{middle}-{int(number):03d}"
                found[ident][kind].add(rel)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", action="store_true", help="print SRS / BR coverage by code and tests")
    args = parser.parse_args()

    known = known_ids()
    refs = cited()
    allowed = allowlist()
    unknown = sorted(i for i in refs if i not in known and not set().union(*refs[i].values()) <= allowed.get(i, set()))
    for ident in unknown:
        files = sorted(set().union(*refs[ident].values()))
        print(f"UNKNOWN {ident}: {', '.join(files[:5])}{' ...' if len(files) > 5 else ''}")

    if args.report:
        for family in ("SRS-", "BR-"):
            total = sorted(i for i in known if i.startswith(family))
            in_code = [i for i in total if refs.get(i, {}).get("code")]
            in_test = [i for i in total if refs.get(i, {}).get("test")]
            print(f"{family[:-1]}: {len(total)} defined, {len(in_code)} cited by code, {len(in_test)} cited by tests")

    print(f"trace check: {len(refs)} distinct IDs cited, {len(unknown)} unknown")
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
