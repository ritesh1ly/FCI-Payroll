#!/usr/bin/env python3
"""Adds a profiling pass to docs/perf/profiling-tracker.json from a run's outputs (load + io json).

Usage: python scripts/perf/record_pass.py --module 09 --kind deep --commit cb8af07 [--worktree] [--tracker-module 02]
       [--finding "text" ...] [--replace-date]
--tracker-module: the tracker entry the run belongs to (a mix such as 02a is part of module 02).
--replace-date: a pass of the same kind and date on that module is replaced (a re-measure the same day).
"""
import argparse
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
PERF = ROOT / "docs" / "perf"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--module", required=True)
    ap.add_argument("--tracker-module")
    ap.add_argument("--kind", default="deep", choices=["shallow", "deep"])
    ap.add_argument("--commit", required=True)
    ap.add_argument("--worktree", action="store_true")
    ap.add_argument("--finding", action="append", default=[])
    ap.add_argument("--replace-date", action="store_true")
    a = ap.parse_args()
    load = json.loads((PERF / f"module-{a.module}-load.json").read_text(encoding="utf-8"))
    io_path = PERF / f"module-{a.module}-io.json"
    io = json.loads(io_path.read_text(encoding="utf-8")) if io_path.exists() else {}
    worst = max(load["rows"], key=lambda r: r["p99"])
    entry = {
        "kind": a.kind, "date": load["at"][:10], "commit": a.commit, "worktree": a.worktree,
        "mix": a.module, "users": load["concurrency"], "requests": sum(r["requests"] for r in load["rows"]),
        "errors": sum(r["errors"] for r in load["rows"]), "throughputPerSec": load["throughput"],
        "worstEndpoint": worst["endpoint"], "worstP99Ms": worst["p99"],
        "dbRoundTripsPerRequest": io.get("dbRoundTripsPerRequest"), "dbReadMsPerRequest": io.get("dbReadMsPerRequest"),
        "reports": [f"docs/perf/module-{a.module}-{x}" for x in ("load.json", "io.json", "jfr.txt", "deep.md")],
        "findings": a.finding,
    }
    tracker_path = PERF / "profiling-tracker.json"
    tracker = json.loads(tracker_path.read_text(encoding="utf-8"))
    target = a.tracker_module or a.module
    module = next(m for m in tracker["modules"] if m["module"] == target)
    if a.replace_date:
        module["passes"] = [p for p in module["passes"]
                            if not (p["kind"] == a.kind and p["date"] == entry["date"] and p.get("mix", target) == a.module)]
    module["passes"].append(entry)
    tracker_path.write_text(json.dumps(tracker, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"{target} <- {a.kind} pass {entry['date']} ({a.module}): {entry['requests']} req, p99 {entry['worstP99Ms']} ms")


if __name__ == "__main__":
    main()
