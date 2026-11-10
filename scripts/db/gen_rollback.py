"""Generates db/rollback/{h2,oracle}/U<ver>__*.sql from the forward migrations (structural DDL)."""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
mig = root / "db" / "migration"
out = root / "db" / "rollback"

CREATE = re.compile(r"^\s*CREATE\s+(?:UNIQUE\s+)?(TABLE|SEQUENCE|TRIGGER|VIEW|INDEX)\s+([A-Za-z0-9_]+)", re.I | re.M)
INSERT = re.compile(r"^\s*INSERT\s+INTO\s+([A-Za-z0-9_]+)", re.I | re.M)


def structural(dialect, path):
    text = path.read_text(encoding="utf-8")
    objs = [(k.upper(), n) for k, n in CREATE.findall(text) if k.upper() in ("TABLE", "SEQUENCE", "TRIGGER", "VIEW")]
    lines = []
    for kind, name in reversed(objs):
        if kind == "TABLE":
            lines.append(f"DROP TABLE {name}" + (" CASCADE CONSTRAINTS PURGE;" if dialect == "oracle" else " CASCADE;"))
        else:
            lines.append(f"DROP {kind} {name};")
    return lines


def data(path):
    tables = []
    for t in INSERT.findall(path.read_text(encoding="utf-8")):
        if t not in tables:
            tables.append(t)
    return [f"DELETE FROM {t};" for t in reversed(tables) if t != "audit_event"]


for dialect in ("h2", "oracle"):
    target = out / dialect
    target.mkdir(parents=True, exist_ok=True)
    for f in sorted((mig / dialect).glob("V*.sql")):
        if f.name[:4] in ("V010", "V011", "V012", "V013"):
            continue  # hand-written (db/README.md)
        name = "U" + f.name[1:]
        body = structural(dialect, f)
        (target / name).write_text(
            f"-- Rollback of {dialect}/{f.name} (generated from it by scripts/db/gen_rollback.py; review"
            " before use).\n-- Run manually, newest version first, then delete the version's row from"
            " flyway_schema_history.\n\n" + "\n".join(body) + "\n",
            encoding="utf-8",
        )
    for f in [mig / "common" / "V006__reference_seed.sql", root / "db" / "seed" / "V008_1__demo_seed.sql"]:
        name = "U" + f.name[1:]
        (target / name).write_text(
            f"-- Rollback of {f.relative_to(root).as_posix()} (reference/demo data). Deletes every row of the"
            " seeded tables:\n-- run only after the later versions have been rolled back. audit_event is"
            " append-only and is never deleted.\n\n" + "\n".join(data(f)) + "\n",
            encoding="utf-8",
        )
print("ok")
