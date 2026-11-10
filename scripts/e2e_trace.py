"""E2E traceability gate: Playwright tests against the SRS / FRS and the KB scenarios.

Every Playwright test carries tags (https://playwright.dev/docs/test-annotations#tag-tests):
  * at least one requirement ID that exists in the KB registries: @SRS-G-10, @BR-CYC-010, @FR-01, @UC-07 ...
  * the KB scenario it proves, where one exists: @SC-01-0010
  * exactly one kind: @positive, @negative or @edge

For every module listed in MODULES, each scenario the module's KB review marks in scope (COVERED or PARTIAL,
not OTHER = owned by an unbuilt module) needs at least one E2E test, or an entry in
frontend/e2e/traceability.json saying why the browser cannot observe it and which test proves it instead.

Usage: python scripts/e2e_trace.py            (writes docs/quality/e2e-traceability.md, exit 1 on a gap)
       python scripts/e2e_trace.py --check    (no file written)
"""

import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRIES = os.path.join(ROOT, "docs", "requirements", "kb", "05-mappings")
EXEMPTIONS = os.path.join(ROOT, "frontend", "e2e", "traceability.json")
REPORT = os.path.join(ROOT, "docs", "quality", "e2e-traceability.md")
KINDS = {"@positive", "@negative", "@edge"}
REQUIREMENT = re.compile(r"^@((?:SRS|BR|FR|UC|NFR|R|OQ)-[A-Z0-9-]+)$")
SCENARIO = re.compile(r"^@(SC-\d{2}-\d{4})$")

# Module -> (scenario file, KB review script whose MAP marks each scenario COVERED / PARTIAL / OTHER).
MODULES = {
    "01": ("docs/requirements/kb/07-analysis/scenarios/01-platform-general.md", "scripts/kb_review_01.py"),
    "02": ("docs/requirements/kb/07-analysis/scenarios/02-security-rbac-approvals.md", "scripts/kb_review_02.py"),
    "03": ("docs/requirements/kb/07-analysis/scenarios/03-audit-trail.md", "scripts/kb_review_03.py"),
    "04": ("docs/requirements/kb/07-analysis/scenarios/04-masters-mdm.md", "scripts/kb_review_04.py"),
    "05": ("docs/requirements/kb/07-analysis/scenarios/05-payroll-cycle-control.md", "scripts/kb_review_05.py"),
    "06": ("docs/requirements/kb/07-analysis/scenarios/06-rule-engine.md", "scripts/kb_review_06.py"),
    "07": ("docs/requirements/kb/07-analysis/scenarios/07-cpf.md", "scripts/kb_review_07.py"),
}


def registry_ids():
    ids = set()
    for name in os.listdir(REGISTRIES):
        if name.startswith("requirement-registry-"):
            for line in io.open(os.path.join(REGISTRIES, name), encoding="utf-8"):
                if line.startswith("### "):
                    ids.add(line[4:].strip())
    return ids


def playwright_tests():
    """Every test as Playwright lists it: file, full title, tags (describe tags included)."""
    out = subprocess.run(
        "npx playwright test --list --reporter=json",
        cwd=os.path.join(ROOT, "frontend"),
        capture_output=True,
        text=True,
        encoding="utf-8",
        shell=True,
        check=True,
    ).stdout
    data = json.loads(out[out.index("{"):])
    tests = []

    def walk(suite, path):
        for spec in suite.get("specs", []):
            tests.append(
                {"file": spec["file"], "title": " > ".join(path + [spec["title"]]), "tags": set(spec.get("tags", []))}
            )
        for child in suite.get("suites", []):
            title = child.get("title", "")
            walk(child, path if title.endswith(".ts") else path + [title])

    for suite in data["suites"]:
        walk(suite, [])
    for t in tests:
        t["tags"] = {tag if tag.startswith("@") else "@" + tag for tag in t["tags"]}
    return tests


def in_scope(module):
    scenario_file, review = MODULES[module]
    ns = {}
    exec(io.open(os.path.join(ROOT, review), encoding="utf-8").read().split("\ndef ")[0], ns)
    status = {}
    for first, last, st, _ in ns["MAP"]:
        for i in range(first, last + 1):
            status[i] = st
    rows = [l for l in io.open(os.path.join(ROOT, scenario_file), encoding="utf-8") if l.startswith("| SC-")]
    result = []
    for i, line in enumerate(rows, 1):
        cells = [c.strip() for c in line.split("|")]
        # In scope: built here (COVERED / PARTIAL). OTHER is another module's; TODO a phase not built yet.
        if status.get(i, "OTHER") in ("COVERED", "PARTIAL"):
            result.append((cells[1], cells[2], cells[3]))
    return result


def main():
    known = registry_ids()
    tests = playwright_tests()
    exemptions = json.load(io.open(EXEMPTIONS, encoding="utf-8")) if os.path.exists(EXEMPTIONS) else {}
    problems = []
    covered = {}
    for t in tests:
        reqs = {m.group(1) for tag in t["tags"] for m in [REQUIREMENT.match(tag)] if m}
        scs = {m.group(1) for tag in t["tags"] for m in [SCENARIO.match(tag)] if m}
        kinds = t["tags"] & KINDS
        where = f"{t['file']}: {t['title']}"
        if not reqs:
            problems.append(f"no requirement tag: {where}")
        for r in sorted(reqs - known):
            problems.append(f"unknown requirement @{r}: {where}")
        if len(kinds) != 1:
            problems.append(f"needs exactly one of {sorted(KINDS)}: {where}")
        for sc in scs:
            covered.setdefault(sc, []).append(t)
    scope = [s for m in MODULES for s in in_scope(m)]
    scope_ids = {s[0] for s in scope}
    for sc in sorted(set(covered) - scope_ids):
        problems.append(f"@{sc} is not an in-scope scenario of {sorted(MODULES)}")
    gaps = [s for s in scope if s[0] not in covered and s[0] not in exemptions]
    for sc, kind, text in gaps:
        problems.append(f"no E2E test and no exemption: {sc} ({kind}) {text[:90]}")
    for sc in sorted(set(exemptions) - scope_ids):
        problems.append(f"exemption for a scenario that is not in scope: {sc}")

    if "--check" not in sys.argv:
        with io.open(REPORT, "w", encoding="utf-8", newline="\n") as out:
            out.write("# E2E traceability (Playwright → SRS / FRS / KB scenarios)\n\n")
            out.write("Generated by `python scripts/e2e_trace.py` from the test tags and the KB. Do not edit by hand.\n\n")
            by_kind = {k: sum(1 for t in tests if k in t["tags"]) for k in sorted(KINDS)}
            out.write(f"Tests: {len(tests)} ({', '.join(f'{k[1:]} {v}' for k, v in by_kind.items())}). ")
            out.write(f"In-scope scenarios: {len(scope)}; with an E2E test {len(scope_ids & set(covered))}; ")
            out.write(f"exempt (proved below the UI) {len(scope_ids & set(exemptions))}; gaps {len(gaps)}.\n\n")
            out.write("| Scenario | Type | E2E tests / reason |\n|---|---|---|\n")
            for sc, kind, text in scope:
                if sc in covered:
                    evidence = "<br>".join(f"`frontend/e2e/{t['file']}` {t['title'].split(' > ')[-1]}" for t in covered[sc])
                elif sc in exemptions:
                    evidence = "exempt: " + exemptions[sc]
                else:
                    evidence = "**GAP**"
                out.write(f"| {sc} | {kind} | {evidence} |\n")
            if problems:
                out.write("\n## Problems\n\n" + "\n".join(f"- {p}" for p in problems) + "\n")
    for p in problems:
        print(p)
    print(f"e2e trace: {len(tests)} tests, {len(scope)} in-scope scenarios, {len(gaps)} gaps, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
