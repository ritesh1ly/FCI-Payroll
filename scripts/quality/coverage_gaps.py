"""Uncovered lines and branches with their source, for the last coverage runs (owner target: 100 % line and branch).

  python scripts/quality/coverage_gaps.py backend  labour/orchestration/WorkSlipService.java [...]
  python scripts/quality/coverage_gaps.py frontend src/modules/labour/components/GangStructure.tsx [...]
  python scripts/quality/coverage_gaps.py backend --top 40      (files with the most gaps)

Backend: backend/build/reports/jacoco/test/jacocoTestReport.xml (all suites). Frontend: frontend/coverage/lcov.info.
Each gap line: L = line never run, B m/n = m of the line's n branches never taken.
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JACOCO = ROOT / "backend/build/reports/jacoco/test/jacocoTestReport.xml"
LCOV = ROOT / "frontend/coverage/lcov.info"


def backend():
    files = {}
    for pkg in ET.parse(JACOCO).getroot().iter("package"):
        for sf in pkg.findall("sourcefile"):
            gaps = []
            for ln in sf.findall("line"):
                mi, mb, cb = int(ln.get("mi")), int(ln.get("mb")), int(ln.get("cb"))
                if mi and not int(ln.get("ci")):
                    gaps.append((int(ln.get("nr")), "L"))
                elif mb:
                    gaps.append((int(ln.get("nr")), f"B {mb}/{mb + cb}"))
            rel = pkg.get("name").split("/", 4)[-1] + "/" + sf.get("name")
            files[rel] = (ROOT / "backend/src/main/java" / pkg.get("name") / sf.get("name"), gaps)
    return files


def frontend():
    files, cur, lines, branches = {}, None, {}, defaultdict(lambda: [0, 0])
    for raw in LCOV.read_text(encoding="utf-8").splitlines():
        if raw.startswith("SF:"):
            cur, lines, branches = raw[3:], {}, defaultdict(lambda: [0, 0])
        elif raw.startswith("DA:"):
            n, hits = raw[3:].split(",")[:2]
            lines[int(n)] = int(hits)
        elif raw.startswith("BRDA:"):
            n, _, _, taken = raw[5:].split(",")
            branches[int(n)][1] += 1
            if taken in ("-", "0"):
                branches[int(n)][0] += 1
        elif raw == "end_of_record" and cur:
            gaps = [(n, "L") for n, h in lines.items() if h == 0]
            gaps += [(n, f"B {m}/{t}") for n, (m, t) in branches.items() if m and lines.get(n, 1)]
            path = Path(cur) if Path(cur).is_absolute() else ROOT / "frontend" / cur
            rel = str(path.relative_to(ROOT / "frontend")).replace("\\", "/")
            files[rel] = (path, sorted(gaps))
    return files


def main():
    side, args = sys.argv[1], sys.argv[2:]
    files = backend() if side == "backend" else frontend()
    if args[:1] == ["--top"]:
        ranked = sorted(((len(g), k) for k, (_, g) in files.items() if g), reverse=True)
        for n, k in ranked[: int(args[1])]:
            print(f"{n:4}  {k}")
        return
    for want in args:
        for rel, (path, gaps) in files.items():
            if rel.endswith(want.replace("\\", "/")):
                print(f"== {rel} ({len(gaps)} gaps)")
                src = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
                for n, kind in sorted(gaps):
                    text = src[n - 1].strip() if 0 < n <= len(src) else ""
                    print(f"  {n:5} {kind:7} {text[:150]}")


if __name__ == "__main__":
    main()
