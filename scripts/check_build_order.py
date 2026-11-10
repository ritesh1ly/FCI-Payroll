#!/usr/bin/env python3
"""Warns when a module is started before the modules it depends on (docs/tracker/BUILD_ORDER.md).

    python scripts/check_build_order.py 11

Reads the dependency table of BUILD_ORDER.md (column "Hard": module numbers, bold or plain) and the
module statuses of PROGRESS.md. A hard dependency whose status is TODO is a WARN (exit code 1):
stop and ask the project owner, as BUILD_ORDER.md says. A hard dependency that is PARTIAL or IN
PROGRESS is a NOTE (exit code 0): check that the part you need is built. Port dependencies are
listed for information. Owner requirement 2026-10-25: "warn at least and ask the developer if the
wrong module is started".
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ORDER = ROOT / "docs" / "tracker" / "BUILD_ORDER.md"
PROGRESS = ROOT / "docs" / "tracker" / "PROGRESS.md"
ROW = re.compile(r"^\|\s*(\d{2})\s*\|")


def modules_in(cell: str) -> list[str]:
    """Module numbers named in a table cell: 05, **09**, 20 - 22 (a range), not screen codes."""
    if re.search(r"\*\*all\*\*", cell):
        return [f"{n:02d}" for n in range(0, 24)]
    # Requirement IDs (SRS-14-7, BR-LOAN-002) and screen codes (I-21) are not module numbers.
    cell = re.sub(r"\b[A-Z]+(?:-[A-Z0-9]+)+\b", "", cell)
    found: list[str] = []
    for a, b in re.findall(r"\b(\d{2})\s*-\s*(\d{2})\b", cell):
        found += [f"{n:02d}" for n in range(int(a), int(b) + 1)]
    cell = re.sub(r"\b\d{2}\s*-\s*\d{2}\b", "", cell)
    found += re.findall(r"\b(\d{2})\b", cell)
    return list(dict.fromkeys(found))


def dependency_table() -> dict[str, dict[str, object]]:
    table: dict[str, dict[str, object]] = {}
    for line in ORDER.read_text(encoding="utf8").splitlines():
        m = ROW.match(line)
        if not m:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 6:
            continue
        table[m.group(1)] = {
            "name": cells[1],
            "hard": [d for d in modules_in(cells[2]) if d != m.group(1)],
            "port": [d for d in modules_in(cells[3]) if d != m.group(1)],
        }
    return table


def statuses() -> dict[str, tuple[str, str]]:
    result: dict[str, tuple[str, str]] = {}
    for line in PROGRESS.read_text(encoding="utf8").splitlines():
        m = ROW.match(line)
        if not m:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 6 and m.group(1) not in result:
            result[m.group(1)] = (cells[1], cells[5])
    return result


def state(status: str) -> str:
    s = status.upper()
    if s.startswith("DONE"):
        return "built"
    if s.startswith("TODO"):
        return "missing"
    return "partial"  # PARTIAL, IN PROGRESS, BLOCKED


def main(argv: list[str]) -> int:
    if len(argv) != 2 or not re.fullmatch(r"\d{1,2}", argv[1]):
        print(__doc__.strip().splitlines()[0])
        print("usage: python scripts/check_build_order.py <module number>")
        return 2
    module = f"{int(argv[1]):02d}"
    table, status = dependency_table(), statuses()
    if module not in table:
        print(f"Module {module} is not in docs/tracker/BUILD_ORDER.md: add it before starting.")
        return 1
    row = table[module]
    print(f"Module {module} - {row['name']}")
    warn = False
    for dep in row["hard"]:
        name, st = status.get(dep, ("?", "TODO"))
        kind = state(st)
        if kind == "missing":
            warn = True
            print(f"  WARN  hard dependency {dep} {name} is not built ({st}).")
        elif kind == "partial":
            print(f"  NOTE  hard dependency {dep} {name} is {st}: check the part this module needs is built.")
        else:
            print(f"  ok    hard dependency {dep} {name} ({st})")
    for dep in row["port"]:
        name, st = status.get(dep, ("?", "TODO"))
        if state(st) != "built":
            print(f"  port  {dep} {name} ({st}): reached through a port that answers 'not configured'.")
    if warn:
        print(
            "\nSTOP: ask the project owner before starting - build the dependency first, go on with a"
            " port, or scope the feature out (docs/tracker/BUILD_ORDER.md, 'When a hard dependency is"
            " missing'). Record the answer in the module's plan."
        )
        return 1
    print("\nNo missing hard dependency: the module may start (plan first, CLAUDE.md §7).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
