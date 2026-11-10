"""Requirement matrix: every SRS v0.5 requirement (SRS-*) and FRS V0.5 business rule (BR-*) against the build.

For each id, by module (KB module of its family):
  TESTED      a test cites it (test name or comment; SRS_13_5 = SRS-13-5)
  BUILT       main code cites it (Javadoc / comment) but no test names it
  PARKED      only PENDING.md, OWNER_DECISIONS.md or a blocked scenario names it (waiting, with its reason)
  WITHDRAWN   the SRS states it is withdrawn / not defined
  OPERATIONAL met by hosting, process or the performance programme, not by application code: listed with the
              document that meets it in docs/quality/OPERATIONAL_REQUIREMENTS.md
  UNTRACKED   nothing names it: neither built, nor tested, nor parked

Usage: python scripts/quality/requirement_matrix.py [--out docs/quality/REQUIREMENT_MATRIX.md]
"""

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRS = ROOT / "docs/requirements/src/FCI_Payroll_SRS_v0.5_Final.md"
FRS = ROOT / "docs/requirements/src/FCI_Payroll_FRS_V0.5.md"
BR = ROOT / "docs/requirements/kb/05-mappings/requirement-registry-br.md"
SCENARIOS = ROOT / "docs/requirements/kb/07-analysis/scenarios"

SRS_MODULE = {
    "G": "01", "EH": "01", "NF": "01", "22": "02", "SEC": "02", "20": "03", "DM": "03", "16": "04", "15": "05",
    "19": "06", "6": "07", "7": "08", "12": "09", "11": "10", "10": "11", "14": "12", "8": "13", "21": "13",
    "17": "14", "18": "15", "1": "16", "13": "17", "9": "18", "LV": "19", "2": "20", "3": "20", "4": "21",
    "5": "22", "INT": "23", "MIG": "24",
}
BR_MODULE = {
    "ALLOW": "10", "APPR": "02", "AUDIT": "03", "CPF": "07", "CYC": "05", "EMPM": "16", "EMPR": "16",
    "ESEVA": "14", "FNF": "11", "INCR": "17", "INT": "23", "LABM": "20", "LABR": "20", "LEAVE": "19",
    "LOAN": "09", "MIS": "15", "PEN": "12", "RULE": "06", "SEC": "02", "TAX": "08",
    "ARR-DA": "13", "ARR-HRA": "13", "INCR-STAG": "17",
}
NAMES = {
    "01": "Platform general", "02": "Security, RBAC, approvals", "03": "Audit trail", "04": "Masters & MDM",
    "05": "Payroll cycle control", "06": "Rule engine", "07": "CPF / PF / VPF", "08": "Income tax",
    "09": "Loans & advances", "10": "Allowances & reimbursements", "11": "Full & final settlement",
    "12": "Penalty recovery", "13": "Arrears, retro, wage revision", "14": "eSeva", "15": "MIS & reporting",
    "16": "Employee / staff payroll", "17": "Increment & pay fixation", "18": "NPS deduction", "19": "Leave",
    "20": "Labour common + Departmental", "21": "DPS labour", "22": "NWNP labour", "23": "Integrations",
    "24": "Migration", "OOS": "Out of scope (pension)",
}
TEST_GLOBS = ["backend/src/test/**/*.java", "backend/src/integrationTest/**/*.java", "backend/src/batchTest/**/*.java",
              "frontend/tests/unit/**/*.ts", "frontend/tests/unit/**/*.tsx", "frontend/e2e/**/*.spec.ts"]
MAIN_GLOBS = ["backend/src/main/**/*.java", "frontend/src/**/*.ts", "frontend/src/**/*.tsx", "db/migration/**/*.sql",
              "db/seed/**/*.sql"]
ID = re.compile(r"(?<![A-Za-z0-9])((?:SRS|BR)[-_][A-Z0-9]+(?:[-_][A-Z]+)*[-_]\d+[a-z]?)(?![0-9])")


def br_module(rid, family):
    """BR-PEN is shared by the FRS: 001-004 NPS (module 18), 005-008 pension (out of scope), 009- penalty (12)."""
    if family == "PEN":
        n = int(rid.rsplit("-", 1)[1])
        return "18" if n <= 4 else "OOS" if n <= 8 else "12"
    return BR_MODULE[family]


def norm(i):
    return i.replace("_", "-")


def ids_in(globs):
    found = set()
    for g in globs:
        for p in ROOT.glob(g):
            if "generated" in p.parts:
                continue
            found |= {norm(m) for m in ID.findall(p.read_text(encoding="utf-8", errors="ignore"))}
    return found


def definitions():
    out = []
    for line in SRS.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^- (SRS-([A-Z0-9]+)-\d+[a-z]?)\b[^:]*:\s*(.*)$", line)
        if m and m.group(2) in SRS_MODULE:
            out.append((m.group(1), SRS_MODULE[m.group(2)], m.group(3)))
    lines = BR.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^### (BR-([A-Z]+)(?:-[A-Z]+)*-\d+)$", line)
        if m and m.group(2) in BR_MODULE:
            text = lines[i + 1].split("):", 1)[-1].strip() if i + 1 < len(lines) else ""
            out.append((m.group(1), br_module(m.group(1), m.group(2)), text))
    # Business rules the FRS defines in its rule tables (| BR-x-nnn | Name | Rule |), also those the KB registry missed.
    for line in FRS.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\| (BR-((?:[A-Z]+-)*[A-Z]+)-\d+) \| ([^|]+) \| ([^|]+) \|", line)
        if m and m.group(2) in BR_MODULE:
            out.append((m.group(1), br_module(m.group(1), m.group(2)), m.group(3).strip() + " / " + m.group(4).strip()))
    seen, unique = set(), []
    for d in out:
        if d[0] not in seen:
            seen.add(d[0])
            unique.append(d)
    return unique


def parked_ids():
    text = "\n".join((ROOT / f).read_text(encoding="utf-8") for f in
                     ("docs/tracker/PENDING.md", "docs/tracker/OWNER_DECISIONS.md"))
    ids = {norm(m) for m in ID.findall(text)}
    blocked = json.loads((ROOT / "docs/quality/edge_case_blocked.json").read_text(encoding="utf-8"))
    row = re.compile(r"^\| (SC-\d{2}-\d{4}) \| [A-Z-]+ \| ((?:SRS|BR)-[A-Z0-9-]+?\d+)")
    for f in SCENARIOS.glob("*.md"):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = row.match(line)
            if m and m.group(1) in blocked:
                ids.add(m.group(2))
    return ids


def operational_ids():
    f = ROOT / "docs/quality/OPERATIONAL_REQUIREMENTS.md"
    if not f.exists():
        return set()
    row = re.compile(r"^\| ((?:SRS|BR)-[A-Z0-9-]+?\d+) \|")
    return {m.group(1) for line in f.read_text(encoding="utf-8").splitlines() if (m := row.match(line))}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="docs/quality/REQUIREMENT_MATRIX.md")
    a = ap.parse_args()
    tested, built, parked = ids_in(TEST_GLOBS), ids_in(MAIN_GLOBS), parked_ids()
    operational = operational_ids()
    per = defaultdict(list)
    for rid, mod, text in definitions():
        low = text.lower()
        if rid in tested:
            status = "TESTED"
        elif rid in built:
            status = "BUILT"
        elif "withdrawn" in low or "not defined" in low:
            status = "WITHDRAWN"
        elif rid in parked:
            status = "PARKED"
        elif rid in operational:
            status = "OPERATIONAL"
        else:
            status = "UNTRACKED"
        per[mod].append((rid, status, text))
    order = ["TESTED", "BUILT", "PARKED", "WITHDRAWN", "OPERATIONAL", "UNTRACKED"]
    lines = ["# Requirement matrix (SRS v0.5 and FRS V0.5 business rules against the build)", "",
             "Generated by `scripts/quality/requirement_matrix.py`. TESTED: a test names the id; BUILT: main code cites it,",
             "no test names it; PARKED: named only in PENDING / OWNER_DECISIONS or a blocked scenario; WITHDRAWN: the SRS",
             "withdraws it; UNTRACKED: named nowhere. Ids are matched as cited, so a requirement proved by a test that",
             "cites a neighbouring id shows as BUILT.", "",
             "| Module | Ids | " + " | ".join(order) + " |", "|---|---|" + "---|" * len(order)]
    total = Counter()
    for mod in sorted(per):
        c = Counter(s for _, s, _ in per[mod])
        total.update(c)
        lines.append(f"| {mod} {NAMES[mod]} | {len(per[mod])} | " + " | ".join(str(c[o]) for o in order) + " |")
    lines.append(f"| **All** | {sum(total.values())} | " + " | ".join(f"**{total[o]}**" for o in order) + " |")
    for mod in sorted(per):
        lines += ["", f"## Module {mod} - {NAMES[mod]}", "", "| Id | Status | Requirement (source text, cut) |", "|---|---|---|"]
        for rid, status, text in sorted(per[mod], key=lambda r: (order.index(r[1]), r[0])):
            cell = text.replace("|", "/")[:180]
            lines.append(f"| {rid} | {status} | {cell} |")
    (ROOT / a.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[8:9 + len(per) + 1]))


if __name__ == "__main__":
    main()
