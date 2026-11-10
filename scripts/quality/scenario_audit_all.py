#!/usr/bin/env python3
"""Scenario audit over all six scenario types (owner 2026-11-03: "verify all the testcase and scenarios are covered or
not. do deep analysis of each and every module and check scope of more testing including edge cases").

A sibling of edge_case_audit.py with the same matching rules, over every type of KB scenario derived from SRS v0.5 / FRS
(docs/requirements/kb/07-analysis/scenarios) instead of the three edge types only:
  FIELD     a field's format, length or mandatory check
  RULE-NEG  a rule's refusal or limit
  RULE-POS  a rule's positive statement (what the system does)
  UC-ALT    an alternate or exception flow of a use case
  UC-MAIN   the main flow of a use case
  UC-POST   a use case's postcondition (its "verify" text)
Nothing outside that catalogue is counted, so the audit stays inside the SRS / FRS boundary.

A scenario counts as tested in a layer when a test of that layer cites the scenario id (SC-nn-nnnn) or the
requirement id the scenario restates (SRS-13-5, BR-ARR-LE-003, ... written SRS-13-5 or SRS_13_5):
  backend   backend/src/{test,integrationTest,batchTest}/**/*.java
  frontend  frontend/tests/unit/**/*.ts(x)
  e2e       frontend/e2e/**/*.spec.ts
A FIELD scenario names a screen field ("M-03 ... · Scale Maximum: ..."); it counts when a test file of the layer names
the screen (code or i18n prefix) and every word of the field label (a weak proxy: the label can appear without the
check being asserted). UC-MAIN and UC-POST rows carry no requirement id, so they count only when a test cites the SC id:
tag the spec or test that drives the flow with it.

A scenario that cannot be tested yet (an FCI answer, a module not built, a setting left unseeded) is listed with its
reason in docs/quality/edge_case_blocked.json, shared with edge_case_audit.py, and reported as blocked, apart from the
gaps; a blocked scenario that a test now cites counts as tested.

Usage: python scripts/quality/scenario_audit_all.py [--modules 01,02,...] [--out docs/quality/SCENARIO_AUDIT_ALL.md]
                                                    [--json <file>]
--json also writes every scenario with its layers, its blocked reason and its Playwright exemption
(frontend/e2e/traceability.json), for a per-scenario follow-up.
Exit code 0 always: the report is the result; the gaps are worked off module by module.
"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "docs/requirements/kb/07-analysis/scenarios"
BLOCKED = ROOT / "docs/quality/edge_case_blocked.json"
EXEMPT = ROOT / "frontend/e2e/traceability.json"
BUILT = "01,02,03,04,05,06,07,08,09,10,11,12,13,14,15,16,17,18,19,20,21,22,23"
TYPES = ("FIELD", "RULE-NEG", "RULE-POS", "UC-ALT", "UC-MAIN", "UC-POST")
REQ = re.compile(r"\b(SRS-[A-Z0-9]+-\d+|BR-[A-Z]+(?:-[A-Z]+)*-\d+)\b")
ROW = re.compile(r"^\| (SC-(\d{2})-\d{4}) \| ([A-Z-]+) \| (.*?) \| (.*?) \| (\d*) \|\s*$")
# Ids in test names (SRS_13_11_aMember...) are followed by a letter or underscore: look around, no word boundary.
TEST_ID = re.compile(r"(?<![A-Za-z0-9])(SRS[-_][A-Z0-9]+[-_]\d+|BR[-_][A-Z]+(?:[-_][A-Z]+)*[-_]\d+)(?![0-9])")
TEST_SC = re.compile(r"(?<![A-Za-z0-9])SC-\d{2}-\d{4}(?![0-9])")
FIELD = re.compile(r"\s*([A-Z]{1,2}-\d{2}[A-Z]?)\b.*?·\s*([^:]+):")
LAYERS = {
    "backend": ["backend/src/test/**/*.java", "backend/src/integrationTest/**/*.java", "backend/src/batchTest/**/*.java"],
    "frontend": ["frontend/tests/unit/**/*.ts", "frontend/tests/unit/**/*.tsx"],
    "e2e": ["frontend/e2e/**/*.spec.ts"],
}


def norm(req):
    """SRS-13-5 and SRS_13_5 are the same id."""
    return req.replace("_", "-").upper()


def scenarios(modules):
    out = []
    for f in sorted(SCENARIOS.glob("*.md")):
        for line in f.read_text(encoding="utf-8").splitlines():
            m = ROW.match(line)
            if not m or m.group(2) not in modules or m.group(3) not in TYPES:
                continue
            sid, mod, kind, cond = m.group(1), m.group(2), m.group(3), m.group(4)
            field = None
            if kind == "FIELD":
                fm = FIELD.match(cond)
                if fm:
                    field = (fm.group(1), [w.lower() for w in re.findall(r"[A-Za-z]{4,}", fm.group(2))])
            reqs = [norm(r) for r in REQ.findall(cond)]
            out.append({
                "id": sid,
                "module": mod,
                "type": kind,
                "text": cond,
                "reqs": reqs[:1],
                "field": field,
                "src": f.name + ":" + m.group(6),
                "expected": m.group(5),
            })
    return out


def corpus(globs):
    files = [p.read_text(encoding="utf-8", errors="ignore") for g in globs for p in ROOT.glob(g)]
    joined = "\n".join(files)
    return {norm(x) for x in TEST_ID.findall(joined)}, set(TEST_SC.findall(joined)), [f.lower() for f in files]


def field_tested(field, files):
    screen, words = field
    keys = (screen.lower(), screen.lower().replace("-", "") + ".")
    return any(any(k in text for k in keys) and all(w in text for w in words) for text in files)


def layers_of(s, found):
    hit = []
    for layer, (ids, scs, files) in found.items():
        ok = s["id"] in scs or any(r in ids for r in s["reqs"])
        if not ok and s["field"]:
            ok = field_tested(s["field"], files)
        if ok:
            hit.append(layer)
    return hit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modules", default=BUILT)
    ap.add_argument("--out", default="docs/quality/SCENARIO_AUDIT_ALL.md")
    ap.add_argument("--json", default=None, help="also write every scenario with its layers to this file")
    a = ap.parse_args()
    found = {layer: corpus(globs) for layer, globs in LAYERS.items()}
    blocked = json.loads(BLOCKED.read_text(encoding="utf-8")) if BLOCKED.exists() else {}
    exempt = json.loads(EXEMPT.read_text(encoding="utf-8")) if EXEMPT.exists() else {}
    held = defaultdict(list)
    per = defaultdict(lambda: defaultdict(int))
    gaps = defaultdict(list)
    rows = []
    for s in scenarios(set(a.modules.split(","))):
        hit = layers_of(s, found)
        c = per[(s["module"], s["type"])]
        c["total"] += 1
        c["any"] += 1 if hit else 0
        for layer in hit:
            c[layer] += 1
        if not hit and s["id"] in blocked:
            c["blocked"] += 1
            held[s["module"]].append(s)
        elif not hit:
            gaps[s["module"]].append(s)
        row = {k: v for k, v in s.items() if k != "field"}
        row.update({"layers": hit, "blocked": blocked.get(s["id"]), "e2e_exempt": exempt.get(s["id"])})
        rows.append(row)
    lines = [
        "# Scenario audit (all six types)",
        "",
        "Generated by `scripts/quality/scenario_audit_all.py` (owner 2026-11-03), the all-types sibling of",
        "`edge_case_audit.py`. Scenarios = the KB scenarios (SRS v0.5 / FRS) of type FIELD, RULE-NEG, RULE-POS, UC-ALT,",
        "UC-MAIN and UC-POST for the built modules; tested = a test of the layer cites the scenario id or the requirement it",
        "restates (FIELD: a test file names the screen and the field's label words; UC-MAIN / UC-POST: the SC id only).",
        "",
        "| Module | Type | Scenarios | Tested (any layer) | Blocked | Backend | Frontend unit | Playwright |",
        "|---|---|---|---|---|---|---|---|",
    ]
    total = covered = parked = 0
    for key in sorted(per):
        c = per[key]
        total += c["total"]
        covered += c["any"]
        parked += c["blocked"]
        lines.append(
            f"| {key[0]} | {key[1]} | {c['total']} | {c['any']} ({100 * c['any'] // max(c['total'], 1)} %) "
            f"| {c['blocked']} | {c['backend']} | {c['frontend']} | {c['e2e']} |"
        )
    lines += ["", f"**All built modules: {covered} / {total} scenarios tested ({100 * covered / max(total, 1):.1f} %), "
              f"{parked} blocked with a reason, {total - covered - parked} to test.**", ""]
    lines += ["## Untested scenarios", "", "Each is checked against the build: tested under another id, out of the built scope (a",
              "PENDING item), or a test to add.", ""]
    for mod in sorted(gaps):
        lines += [f"### Module {mod}", ""] + [f"- {s['id']} {s['type']}: {s['text'][:160]}" for s in gaps[mod]] + [""]
    lines += ["## Blocked scenarios", "", "Not testable yet; the reason is in docs/quality/edge_case_blocked.json.", ""]
    for mod in sorted(held):
        lines += [f"### Module {mod}", ""] + [f"- {s['id']} {s['type']}: {blocked[s['id']]}" for s in held[mod]] + [""]
    out = ROOT / a.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    if a.json:
        target = Path(a.json)
        target = target if target.is_absolute() else ROOT / target
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(rows, indent=1), encoding="utf-8", newline="\n")
    print(f"{covered} / {total} scenarios tested, {parked} blocked, {total - covered - parked} to test; report {out}")
    for key in sorted(per):
        c = per[key]
        if c["any"] + c["blocked"] < c["total"]:
            print(f"  {key[0]} {key[1]}: {c['total'] - c['any'] - c['blocked']} to test of {c['total']}")


if __name__ == "__main__":
    main()
