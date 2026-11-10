#!/usr/bin/env python3
"""PIT per backend module (shard), within one CPU budget, skipping the modules that did not change.

docs/quality/QUALITY_GATES.md "Mutation runs, sharded"; developer playbook, learnings log 2026-11-08.

PIT 1.30 has no free incremental history (moved to Arcmutate), so a full `./gradlew :backend:pitest`
redoes every module. This runner runs :backend:pitest once per module that has a `business` package,
mutating only `in.gov.fci.payroll.<module>.business.*` with that module's unit tests
(`in.gov.fci.payroll.<module>.*`), one module after the other (one Gradle build directory), with PIT's
threads from the CPU budget, Gradle at low priority and at most 4 Gradle workers. A module whose
inputs are unchanged since its last PASSING run is skipped with its recorded score (--force runs it).

Inputs hashed per module: its main and unit-test sources (backend/src/{main,test}/java/.../<module>),
the main sources its files import from other packages (transitively, whole packages, since a class
uses its package siblings without an import), and the tooling (backend/build.gradle.kts,
gradle.properties, gradle/libs.versions.toml, settings.gradle.kts, build-logic/src, this script).

Usage:
  python scripts/quality/pit_shards.py --dry-run          shards, budget, what would run or be skipped
  python scripts/quality/pit_shards.py                    run the changed modules, then the summary
  python scripts/quality/pit_shards.py --force            run every module
  python scripts/quality/pit_shards.py --only cpf,tax     only these modules (the others keep their record)
  python scripts/quality/pit_shards.py --budget 6         PIT threads (default 60 % of the logical cores;
                                                          also --budget 40%, or FCI_MUTATION_BUDGET)
  python scripts/quality/pit_shards.py --all-tests        every unit test for each module (slower; for a
                                                          rule killed only by another module's tests)

Threshold: fci.pitest.min-mutation of gradle.properties, per module and overall (never lowered here).
Output: backend/build/reports/pitest-shards/<module>/ (PIT's HTML + mutations.xml), state.json, summary.json.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
PKG = pathlib.Path("in/gov/fci/payroll")
MAIN = BACKEND / "src" / "main" / "java" / PKG
TEST = BACKEND / "src" / "test" / "java" / PKG
MAIN_ROOT = BACKEND / "src" / "main" / "java"
PIT_REPORT = BACKEND / "build" / "reports" / "pitest"
OUT = BACKEND / "build" / "reports" / "pitest-shards"
STATE = OUT / "state.json"
TOOLING = [
    BACKEND / "build.gradle.kts",
    ROOT / "gradle.properties",
    ROOT / "gradle" / "libs.versions.toml",
    ROOT / "settings.gradle.kts",
    ROOT / "build-logic" / "src",
    pathlib.Path(__file__).resolve(),
]
STATE_VERSION = 1
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?(in\.gov\.fci\.payroll\.[\w.]+?)(\.\*)?\s*;", re.M)


def threshold():
    text = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    m = re.search(r"^fci\.pitest\.min-mutation\s*=\s*(\d+)", text, re.M)
    if not m:
        sys.exit("fci.pitest.min-mutation not found in gradle.properties")
    return int(m.group(1))


def resolve_budget(raw):
    cores = os.cpu_count() or 1
    spec = str(raw if raw is not None else os.environ.get("FCI_MUTATION_BUDGET", "60%")).strip()
    try:
        n = int(cores * float(spec[:-1]) / 100) if spec.endswith("%") else int(spec)
    except ValueError:
        sys.exit(f'Budget "{spec}" is neither a number nor a percentage')
    return cores, spec, max(1, min(cores, n))


def files_under(path):
    if path.is_file():
        return [path]
    return sorted(p for p in path.rglob("*") if p.is_file()) if path.is_dir() else []


def package_files(java_file):
    """The .java files of a class's package: siblings are used without an import."""
    return sorted(java_file.parent.glob("*.java"))


def imported_files(java_file):
    """Main-source files named by the imports of one file (whole package for a wildcard)."""
    found = set()
    for name, wildcard in IMPORT_RE.findall(java_file.read_text(encoding="utf-8", errors="replace")):
        parts = name.split(".")
        if wildcard:
            d = MAIN_ROOT.joinpath(*parts)
            found.update(d.glob("*.java"))
            continue
        # Longest prefix that is a file: a.b.C, a.b.C.Inner, or a static a.b.C.member
        for cut in range(len(parts), 0, -1):
            f = MAIN_ROOT.joinpath(*parts[:cut]).with_suffix(".java")
            if f.is_file():
                found.add(f)
                break
    return found


def closure(start_files):
    seen, stack = set(), list(start_files)
    while stack:
        f = stack.pop()
        if f in seen or f.suffix != ".java":
            seen.add(f)
            continue
        seen.add(f)
        for g in imported_files(f):
            for h in package_files(g):
                if h not in seen:
                    stack.append(h)
    return seen


def digest(files):
    h = hashlib.sha256()
    for f in sorted(set(files)):
        h.update(f.relative_to(ROOT).as_posix().encode())
        h.update(b"\0")
        h.update(f.read_bytes())
        h.update(b"\0")
    return h.hexdigest()[:24]


def build_shards():
    tooling = [f for t in TOOLING for f in files_under(t)]
    shards = []
    for d in sorted(p for p in MAIN.iterdir() if p.is_dir()):
        business = d / "business"
        if not business.is_dir() or not any(business.rglob("*.java")):
            continue
        own = files_under(d) + files_under(TEST / d.name)
        support = closure(own) | set(tooling)
        shards.append({
            "module": d.name,
            "business": len(list(business.rglob("*.java"))),
            "tests": len(list((TEST / d.name).rglob("*Test.java"))) if (TEST / d.name).is_dir() else 0,
            "inputs": len(support),
            "hash": digest(support),
        })
    return shards


def decide(shard, previous, args):
    if args.only and shard["module"] not in args.only:
        return ("skip", "not in --only (recorded result kept)") if previous else None
    if args.force:
        return ("run", "--force")
    if not previous:
        return ("run", "no recorded run")
    if previous.get("tests") != ("all" if args.all_tests else "module"):
        return ("run", "test scope changed")
    if previous["hash"] != shard["hash"]:
        return ("run", "sources, imported code or tooling changed")
    if previous["status"] != "passed":
        return ("run", "last run did not pass")
    return ("skip", f"unchanged since {previous['finishedAt']}")


def read_mutations(xml_file, module):
    """(detected, total) of the module's mutants; PIT's `detected` is its own kill flag."""
    prefix = f"in.gov.fci.payroll.{module}."
    detected = total = 0
    for m in ET.parse(xml_file).getroot().iter("mutation"):
        cls = m.findtext("mutatedClass") or ""
        if not cls.startswith(prefix):
            continue
        total += 1
        detected += m.get("detected") == "true"
    return detected, total


def pit_score(detected, total):
    # PIT's threshold check uses the rounded whole percentage.
    return 100 if total == 0 else int(100 * detected / total + 0.5)


def gradle_command(module, threads, args):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    tests = "in.gov.fci.payroll.*" if args.all_tests else f"in.gov.fci.payroll.{module}.*"
    return [
        str(wrapper),
        ":backend:pitest",
        f"-Pfci.pitest.targetClasses=in.gov.fci.payroll.{module}.business.*",
        f"-Pfci.pitest.targetTests={tests}",
        f"-Pfci.pitest.threads={threads}",
        f"--max-workers={min(4, threads)}",
        "--priority=low",
    ]


def run_module(module, threads, args):
    out_dir = OUT / module
    out_dir.mkdir(parents=True, exist_ok=True)
    log = out_dir / "gradle.log"
    started = time.time()
    kwargs = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.BELOW_NORMAL_PRIORITY_CLASS
    else:
        kwargs["preexec_fn"] = lambda: os.nice(10)
    with log.open("w", encoding="utf-8") as fh:
        code = subprocess.call(gradle_command(module, threads, args), cwd=ROOT, stdout=fh, stderr=subprocess.STDOUT,
                               **kwargs)
    xml = PIT_REPORT / "mutations.xml"
    fresh = xml.is_file() and xml.stat().st_mtime >= started
    if fresh:
        shutil.rmtree(out_dir / "report", ignore_errors=True)
        shutil.copytree(PIT_REPORT, out_dir / "report")
    return code, fresh, round((time.time() - started) / 60, 1)


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--force", action="store_true")
    p.add_argument("--only", type=lambda s: [x.strip() for x in s.split(",") if x.strip()])
    p.add_argument("--budget")
    p.add_argument("--all-tests", action="store_true")
    args = p.parse_args()

    cores, spec, budget = resolve_budget(args.budget)
    gate = threshold()
    shards = build_shards()
    names = [s["module"] for s in shards]
    if args.only and set(args.only) - set(names):
        sys.exit(f"--only: no module named {', '.join(sorted(set(args.only) - set(names)))}")
    try:
        state = json.loads(STATE.read_text(encoding="utf-8"))
        if state.get("version") != STATE_VERSION:
            raise ValueError
    except (OSError, ValueError):
        state = {"version": STATE_VERSION, "shards": {}}

    plans = [(s, decide(s, state["shards"].get(s["module"]), args)) for s in shards]
    print(f"CPU budget: {budget} PIT thread(s) of {cores} logical cores ({spec}); Gradle workers "
          f"{min(4, budget)}, low priority; modules one at a time; tests: "
          f"{'all unit tests' if args.all_tests else 'the module own'}; gate {gate} %.")
    print(f"{'module':15}{'business':>9}{'tests':>7}{'inputs':>8}  action  reason")
    for s, plan in plans:
        action, reason = plan or ("-", "not in --only, no recorded result")
        print(f"{s['module']:15}{s['business']:>9}{s['tests']:>7}{s['inputs']:>8}  {action:6}  {reason}")
    queue = [s for s, plan in plans if plan and plan[0] == "run"]
    print(f"{len(queue)} module(s) to run, {len(plans) - len(queue)} skipped or not run.")
    if args.dry_run:
        if queue:
            print("First command: " + " ".join(gradle_command(queue[0]["module"], budget, args)))
        return 0

    OUT.mkdir(parents=True, exist_ok=True)
    for s in queue:
        print(f"[{s['module']}] start", flush=True)
        code, fresh, minutes = run_module(s["module"], budget, args)
        detected, total = read_mutations(OUT / s["module"] / "report" / "mutations.xml", s["module"]) if fresh \
            else (0, 0)
        score = pit_score(detected, total) if fresh else 0
        passed = fresh and score >= gate
        state["shards"][s["module"]] = {
            "hash": s["hash"], "tests": "all" if args.all_tests else "module",
            "status": "passed" if passed else "failed", "score": score, "detected": detected, "total": total,
            "exitCode": code, "finishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
        STATE.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        note = f"{score} % ({detected}/{total})" if fresh else f"no report (exit {code}), see {OUT / s['module'] / 'gradle.log'}"
        print(f"[{s['module']}] {note} {'pass' if passed else 'FAIL'} in {minutes} min", flush=True)

    failures, det_sum, tot_sum = [], 0, 0
    print(f"\n{'module':15}{'score':>7}{'detected':>10}{'total':>7}  status")
    for s in shards:
        rec = state["shards"].get(s["module"])
        if not rec:
            print(f"{s['module']:15}{'-':>7}  not run yet")
            failures.append(s["module"])
            continue
        det_sum += rec["detected"]
        tot_sum += rec["total"]
        print(f"{s['module']:15}{rec['score']:>6} %{rec['detected']:>9}{rec['total']:>7}  {rec['status']}")
        if rec["status"] != "passed":
            failures.append(s["module"])
    overall = pit_score(det_sum, tot_sum)
    (OUT / "summary.json").write_text(json.dumps({
        "gate": gate, "overall": overall, "detected": det_sum, "total": tot_sum, "failures": failures,
        "modules": {m: state["shards"].get(m) for m in names},
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Overall {overall} % ({det_sum}/{tot_sum}), gate {gate} %; reports in {OUT}")
    if failures or overall < gate:
        print(f"Mutation gate FAILED: {', '.join(failures) or 'overall score'}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
