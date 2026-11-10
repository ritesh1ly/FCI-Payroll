"""One self-contained HTML quality report from the build's own result files.

Inputs (produce them first):
  backend   ./gradlew :backend:jacocoTestReport :backend:pitest
            -> backend/build/reports/jacoco/test/jacocoTestReport.xml (unit + integration + batch merged)
            -> backend/build/reports/pitest/mutations.xml
            -> backend/build/test-results/{test,integrationTest,batchTest,architectureTest}/TEST-*.xml
  frontend  npx vitest run --coverage --coverage.reporter=json-summary  -> frontend/coverage/coverage-summary.json
            npx playwright test --reporter=json > frontend/playwright-results.json   (against the deployed stack)
  trace     python scripts/e2e_trace.py  -> docs/quality/e2e-traceability.md

Output: docs/quality/quality-report.html (python scripts/quality_report.py [--out path]).
"""

import collections
import datetime
import html
import io
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BACKEND = os.path.join(ROOT, "backend", "build")
FRONTEND = os.path.join(ROOT, "frontend")
OUT = os.path.join(ROOT, "docs", "quality", "quality-report.html")
TARGET = 95.0


def pct(covered, total):
    return 100.0 * covered / total if total else 100.0


# ---------------------------------------------------------------- readers


def jacoco():
    path = os.path.join(BACKEND, "reports", "jacoco", "test", "jacocoTestReport.xml")
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    modules = collections.defaultdict(lambda: collections.Counter())
    for package in root.findall("package"):
        # The root package holds the application class only.
        name = (package.get("name") + "/").replace("in/gov/fci/payroll/", "").split("/")[0] or "(application)"
        for counter in package.findall("counter"):
            t = counter.get("type")
            modules[name][t + "_missed"] += int(counter.get("missed"))
            modules[name][t + "_covered"] += int(counter.get("covered"))
    total = {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in root.findall("counter")}
    return {"modules": modules, "total": total}


def vitest():
    path = os.path.join(FRONTEND, "coverage", "coverage-summary.json")
    if not os.path.exists(path):
        return None
    data = json.load(io.open(path, encoding="utf-8"))
    total = data.pop("total")
    modules = collections.defaultdict(lambda: collections.Counter())
    for file, v in data.items():
        norm = file.replace("\\", "/")
        m = re.search(r"/src/(modules|shared)/([^/]+)/", norm)
        key = (m.group(1) + "/" + m.group(2)) if m else "other"
        for k in ("lines", "branches"):
            modules[key][k + "_total"] += v[k]["total"]
            modules[key][k + "_covered"] += v[k]["covered"]
    return {"total": total, "modules": modules}


def pitest():
    path = os.path.join(BACKEND, "reports", "pitest", "mutations.xml")
    if not os.path.exists(path):
        return None
    modules = collections.defaultdict(lambda: collections.Counter())
    survivors = []
    for m in ET.parse(path).getroot().findall("mutation"):
        cls = m.find("mutatedClass").text.replace("in.gov.fci.payroll.", "")
        modules[cls.split(".")[0]][m.get("status")] += 1
        if m.get("status") in ("SURVIVED", "NO_COVERAGE"):
            survivors.append((cls, m.find("mutatedMethod").text, m.find("lineNumber").text, m.get("status"), m.find("description").text))
    return {"modules": modules, "survivors": survivors}


def junit():
    suites = {}
    for suite in ("test", "integrationTest", "batchTest", "architectureTest"):
        folder = os.path.join(BACKEND, "test-results", suite)
        if not os.path.isdir(folder):
            continue
        c = collections.Counter()
        failed = []
        for name in os.listdir(folder):
            if name.startswith("TEST-") and name.endswith(".xml"):
                r = ET.parse(os.path.join(folder, name)).getroot()
                c["tests"] += int(r.get("tests"))
                c["failures"] += int(r.get("failures")) + int(r.get("errors"))
                c["skipped"] += int(r.get("skipped"))
                c["classes"] += 1
                for tc in r.findall("testcase"):
                    if tc.find("failure") is not None or tc.find("error") is not None:
                        failed.append(r.get("name").split(".")[-1] + "." + tc.get("name"))
        suites[suite] = (c, failed)
    return suites


def playwright():
    path = os.path.join(FRONTEND, "playwright-results.json")
    if not os.path.exists(path):
        return None
    raw = io.open(path, encoding="utf-8").read()
    data = json.loads(raw[raw.index("{"):])
    rows = []

    def walk(suite, folder):
        for spec in suite.get("specs", []):
            for test in spec.get("tests", []):
                results = test.get("results", [])
                status = results[-1]["status"] if results else test.get("status", "skipped")
                rows.append({
                    "file": spec["file"],
                    "module": spec["file"].replace(chr(92), "/").split("/")[0],
                    "title": spec["title"],
                    "tags": spec.get("tags", []),
                    "status": "skipped" if test.get("status") == "skipped" else status,
                    "ms": sum(r.get("duration", 0) for r in results),
                })
        for child in suite.get("suites", []):
            walk(child, folder)

    for s in data.get("suites", []):
        walk(s, "")
    return rows


def trace_summary():
    path = os.path.join(ROOT, "docs", "quality", "e2e-traceability.md")
    if not os.path.exists(path):
        return None, []
    text = io.open(path, encoding="utf-8").read()
    head = next((l for l in text.splitlines() if l.startswith("Tests:")), "")
    rows = [l for l in text.splitlines() if l.startswith("| SC-")]
    return head, rows


# ---------------------------------------------------------------- rendering


def bar(value):
    cls = "ok" if value >= TARGET else ("warn" if value >= 80 else "bad")
    return f'<div class="bar {cls}" role="img" aria-label="{value:.1f} %"><span style="width:{min(value, 100):.1f}%"></span></div><b>{value:.1f} %</b>'


def card(title, value, note=""):
    cls = "ok" if value is not None and value >= TARGET else "warn"
    v = "n/a" if value is None else f"{value:.1f} %"
    return f'<div class="card {cls}"><div class="lab">{html.escape(title)}</div><div class="val">{v}</div><div class="sub">{html.escape(note)}</div></div>'


def main():
    out = OUT
    if "--out" in sys.argv:
        out = sys.argv[sys.argv.index("--out") + 1]
    j, v, p, ju, pw = jacoco(), vitest(), pitest(), junit(), playwright()
    trace_head, trace_rows = trace_summary()
    parts = []

    # Headline cards
    jl = pct(j["total"]["LINE"][1], sum(j["total"]["LINE"])) if j else None
    jb = pct(j["total"]["BRANCH"][1], sum(j["total"]["BRANCH"])) if j else None
    vl = v["total"]["lines"]["pct"] if v else None
    vb = v["total"]["branches"]["pct"] if v else None
    pk = None
    if p:
        c = collections.Counter()
        for m in p["modules"].values():
            c.update(m)
        pk = pct(c["KILLED"] + c["TIMED_OUT"] + c["MEMORY_ERROR"], sum(c.values()))
    pw_pass = None
    if pw:
        ran = [r for r in pw if r["status"] != "skipped"]
        pw_pass = pct(sum(1 for r in ran if r["status"] in ("passed", "expected")), len(ran))
    parts.append('<section class="cards">' + "".join([
        card("Backend line coverage", jl, "JaCoCo: unit + integration + batch"),
        card("Backend branch coverage", jb, "JaCoCo"),
        card("Frontend line coverage", vl, "Vitest"),
        card("Frontend branch coverage", vb, "Vitest"),
        card("PIT mutation score", pk, "*.business.* killed / generated"),
        card("Playwright pass rate", pw_pass, f"{len(pw)} tests" if pw else "no result file"),
    ]) + "</section>")

    # Backend per module
    if j:
        rows = []
        for name, c in sorted(j["modules"].items()):
            lt = c["LINE_missed"] + c["LINE_covered"]
            bt = c["BRANCH_missed"] + c["BRANCH_covered"]
            rows.append(f"<tr><td>{html.escape(name)}</td><td class=num>{lt}</td><td>{bar(pct(c['LINE_covered'], lt))}</td><td class=num>{bt}</td><td>{bar(pct(c['BRANCH_covered'], bt))}</td></tr>")
        parts.append('<section><h2>Backend coverage by module (JaCoCo)</h2><table><thead><tr><th>Module</th><th class=num>Lines</th><th>Line coverage</th><th class=num>Branches</th><th>Branch coverage</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table></section>")

    # Frontend per module
    if v:
        rows = []
        for name, c in sorted(v["modules"].items()):
            rows.append(f"<tr><td>{html.escape(name)}</td><td class=num>{c['lines_total']}</td><td>{bar(pct(c['lines_covered'], c['lines_total']))}</td><td class=num>{c['branches_total']}</td><td>{bar(pct(c['branches_covered'], c['branches_total']))}</td></tr>")
        parts.append('<section><h2>Frontend coverage by module (Vitest)</h2><table><thead><tr><th>Module</th><th class=num>Lines</th><th>Line coverage</th><th class=num>Branches</th><th>Branch coverage</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table></section>")

    # PIT
    if p:
        rows = []
        for name, c in sorted(p["modules"].items()):
            total = sum(c.values())
            killed = c["KILLED"] + c["TIMED_OUT"] + c["MEMORY_ERROR"]
            rows.append(f"<tr><td>{html.escape(name)}</td><td class=num>{total}</td><td class=num>{killed}</td><td class=num>{c['SURVIVED']}</td><td class=num>{c['NO_COVERAGE']}</td><td>{bar(pct(killed, total))}</td></tr>")
        surv = "".join(
            f"<tr><td>{html.escape(cls)}</td><td>{html.escape(meth)}</td><td class=num>{line}</td><td>{st}</td><td>{html.escape(desc)}</td></tr>"
            for cls, meth, line, st, desc in sorted(p["survivors"])
        )
        parts.append('<section><h2>Mutation testing (PIT) by module</h2><table><thead><tr><th>Module</th><th class=num>Mutants</th><th class=num>Killed</th><th class=num>Survived</th><th class=num>No coverage</th><th>Score</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table>"
                     + f'<details><summary>{len(p["survivors"])} surviving or uncovered mutants</summary><table><thead><tr><th>Class</th><th>Method</th><th class=num>Line</th><th>Status</th><th>Mutation</th></tr></thead><tbody>{surv}</tbody></table></details></section>')

    # JUnit suites
    if ju:
        rows = []
        for suite, (c, failed) in ju.items():
            label = {"test": "Unit", "integrationTest": "Integration (API + DB)", "batchTest": "Spring Batch", "architectureTest": "Architecture"}[suite]
            status = '<span class="pill ok">green</span>' if c["failures"] == 0 else f'<span class="pill bad">{c["failures"]} failed</span>'
            rows.append(f"<tr><td>{label}</td><td class=num>{c['classes']}</td><td class=num>{c['tests']}</td><td class=num>{c['skipped']}</td><td>{status}</td></tr>")
        parts.append('<section><h2>Backend test suites</h2><table><thead><tr><th>Suite</th><th class=num>Classes</th><th class=num>Tests</th><th class=num>Skipped</th><th>Result</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table></section>")

    # Playwright
    if pw:
        by_mod = collections.defaultdict(collections.Counter)
        for r in pw:
            by_mod[r["module"]][r["status"]] += 1
            for k in ("@positive", "@negative", "@edge"):
                if k in r["tags"] or k[1:] in r["tags"]:
                    by_mod[r["module"]][k] += 1
        rows = "".join(
            f"<tr><td>{html.escape(m)}</td><td class=num>{sum(c[s] for s in ('passed','expected','failed','unexpected','flaky','timedOut','skipped','interrupted'))}</td>"
            f"<td class=num>{c['passed'] + c['expected']}</td><td class=num>{c['failed'] + c['unexpected'] + c['timedOut']}</td><td class=num>{c['skipped']}</td>"
            f"<td class=num>{c['@positive']}</td><td class=num>{c['@negative']}</td><td class=num>{c['@edge']}</td></tr>"
            for m, c in sorted(by_mod.items())
        )
        detail = "".join(
            f"<tr><td>{html.escape(r['file'])}</td><td>{html.escape(r['title'])}</td><td>{' '.join(html.escape(t) for t in r['tags'])}</td>"
            f"<td><span class=\"pill {'ok' if r['status'] in ('passed','expected') else ('gray' if r['status']=='skipped' else 'bad')}\">{r['status']}</span></td><td class=num>{r['ms'] / 1000:.1f}</td></tr>"
            for r in pw
        )
        parts.append('<section><h2>End-to-end tests (Playwright) by business module</h2><table><thead><tr><th>Module</th><th class=num>Tests</th><th class=num>Passed</th><th class=num>Failed</th><th class=num>Skipped</th><th class=num>Positive</th><th class=num>Negative</th><th class=num>Edge</th></tr></thead><tbody>'
                     + rows + f'</tbody></table><details><summary>All {len(pw)} tests with their requirement tags</summary><table><thead><tr><th>Spec</th><th>Test</th><th>Tags</th><th>Result</th><th class=num>s</th></tr></thead><tbody>{detail}</tbody></table></details></section>')

    # Traceability
    if trace_rows:
        body = "".join(
            "<tr>" + "".join(f"<td>{html.escape(c.strip()).replace('&lt;br&gt;', '<br>')}</td>" for c in r.strip("|").split("|")) + "</tr>"
            for r in trace_rows
        )
        parts.append(f'<section><h2>Requirement traceability (SRS / KB scenarios → E2E)</h2><p>{html.escape(trace_head)}</p><details><summary>Scenario matrix</summary><table><thead><tr><th>Scenario</th><th>Type</th><th>Evidence</th></tr></thead><tbody>{body}</tbody></table></details></section>')

    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    page = TEMPLATE.replace("{{STAMP}}", stamp).replace("{{TARGET}}", f"{TARGET:.0f}").replace("{{BODY}}", "\n".join(parts))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    io.open(out, "w", encoding="utf-8", newline="\n").write(page)
    print(f"quality report: {out}")


TEMPLATE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>FCI Payroll Quality Report</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--ink:#1d2430;--mute:#5b6576;--line:#e3e7ee;--ok:#1f7a4d;--warn:#a5650a;--bad:#b42318;--track:#e9edf3}
@media (prefers-color-scheme:dark){:root{--bg:#12161c;--card:#1a2029;--ink:#e6e9ef;--mute:#9aa4b3;--line:#2a323d;--ok:#4cc38a;--warn:#e0a03a;--bad:#f07167;--track:#2a323d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:1180px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:0 0 12px}
.meta{color:var(--mute);margin-bottom:20px}section{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:16px 0;overflow-x:auto}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;background:none;border:0;padding:0}
.card{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--warn);border-radius:10px;padding:12px}.card.ok{border-left-color:var(--ok)}
.lab{color:var(--mute);font-size:12px}.val{font-size:24px;font-weight:600;margin:4px 0}.sub{color:var(--mute);font-size:12px}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}th{color:var(--mute);font-weight:600;font-size:12px}
.num{text-align:right;font-variant-numeric:tabular-nums}.bar{display:inline-block;width:110px;height:8px;background:var(--track);border-radius:4px;margin-right:8px;vertical-align:middle;overflow:hidden}
.bar span{display:block;height:100%}.bar.ok span{background:var(--ok)}.bar.warn span{background:var(--warn)}.bar.bad span{background:var(--bad)}
.pill{display:inline-block;padding:1px 8px;border-radius:10px;font-size:12px;border:1px solid currentColor}.pill.ok{color:var(--ok)}.pill.bad{color:var(--bad)}.pill.gray{color:var(--mute)}
details{margin-top:12px}summary{cursor:pointer;color:var(--mute)}
</style></head><body><main>
<h1>FCI Payroll — quality report</h1>
<div class="meta">Generated {{STAMP}} by scripts/quality_report.py · target {{TARGET}} % line, branch and mutation score · green ≥ target, amber ≥ 80 %, red below</div>
{{BODY}}
</main></body></html>
"""

if __name__ == "__main__":
    main()
