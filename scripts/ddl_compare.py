"""Compares the tables the Oracle migrations create with the Target DDL v4.0 (schema of record):
for every table present in both, the columns missing from the migration, the columns the migration
adds, and the columns whose data type differs. Tables the migrations create that v4.0 does not
define are listed as extensions (expected: platform tables, listed in docs/db/DDL_V4_ALIGNMENT.md).

Usage: python -I scripts/ddl_compare.py [--all]   (default: only tables with differences)
"""

import glob
import re
import sys

DDL = "docs/requirements/src/FCI_Payroll_Target_DDL_v4.0.sql"
MIGRATIONS = "db/migration/oracle/*.sql"

CREATE = re.compile(r"CREATE\s+TABLE\s+(?:FCIPAY\.)?([A-Za-z0-9_]+)\s*\((.*?)\)\s*(?:TABLESPACE|;)", re.S | re.I)
COLUMN = re.compile(r"^\s*([A-Za-z][A-Za-z0-9_]*)\s+([A-Za-z0-9_]+(?:\s*\([^)]*\))?)", re.M)
SKIP = {"CONSTRAINT", "PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "KEY", "COLUMN"}


def strip_comments(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--[^\n]*", "", sql)


def tables(sql: str) -> dict[str, dict[str, str]]:
    out: dict[str, dict[str, str]] = {}
    for name, body in CREATE.findall(strip_comments(sql)):
        cols: dict[str, str] = {}
        # split on commas at depth 0
        depth, cur, parts = 0, [], []
        for ch in body:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if ch == "," and depth == 0:
                parts.append("".join(cur))
                cur = []
            else:
                cur.append(ch)
        parts.append("".join(cur))
        for part in parts:
            m = COLUMN.match(part)
            if not m or m.group(1).upper() in SKIP:
                continue
            cols[m.group(1).upper()] = re.sub(r"\s+", "", m.group(2).upper())
        out[name.upper()] = cols
    return out


def main() -> int:
    show_all = "--all" in sys.argv
    target = tables(open(DDL, encoding="utf-8", errors="replace").read())
    built: dict[str, dict[str, str]] = {}
    origin: dict[str, str] = {}
    for path in sorted(glob.glob(MIGRATIONS)):
        for name, cols in tables(open(path, encoding="utf-8", errors="replace").read()).items():
            built[name] = cols  # a later migration that re-creates a table wins
            origin[name] = path.split("/")[-1].split("\\")[-1]
    # ALTER TABLE ... ADD (a type, b type) / ADD a type / DROP COLUMN a / MODIFY (a type) in later
    # migrations change the built definition.
    alter = re.compile(
        r"ALTER\s+TABLE\s+(?:FCIPAY\.)?([A-Za-z0-9_]+)\s+(ADD|DROP\s+COLUMN|DROP|MODIFY)\s*(\((?:[^()]|\([^()]*\))*\)|[^;]+);",
        re.I | re.S,
    )
    for path in sorted(glob.glob(MIGRATIONS)):
        for name, verb, rest in alter.findall(strip_comments(open(path, encoding="utf-8", errors="replace").read())):
            cols = built.setdefault(name.upper(), {})
            body = rest.strip()
            if body.startswith("("):
                body = body[1:-1]
            verb = verb.upper().split()[0]
            depth, cur, parts = 0, [], []
            for ch in body:
                depth += ch == "("
                depth -= ch == ")"
                if ch == "," and depth == 0:
                    parts.append("".join(cur))
                    cur = []
                else:
                    cur.append(ch)
            parts.append("".join(cur))
            for part in parts:
                m = COLUMN.match(part)
                if not m or m.group(1).upper() in SKIP:
                    continue
                if verb == "DROP":
                    cols.pop(m.group(1).upper(), None)
                else:
                    cols[m.group(1).upper()] = re.sub(r"\s+", "", m.group(2).upper())
    print(f"v4.0 tables: {len(target)}; tables created by the Oracle migrations: {len(built)}")
    shared = sorted(set(target) & set(built))
    extensions = sorted(set(built) - set(target))
    differing = 0
    for name in shared:
        t, b = target[name], built[name]
        missing = sorted(set(t) - set(b))
        extra = sorted(set(b) - set(t))
        typed = sorted(c for c in set(t) & set(b) if t[c] != b[c])
        if missing or extra or typed or show_all:
            differing += 1 if (missing or extra or typed) else 0
            print(f"\n== {name} ({origin[name]})")
            if missing:
                print("   missing in migration:", ", ".join(missing))
            if extra:
                print("   added by migration:  ", ", ".join(extra))
            for c in typed:
                print(f"   type differs: {c}: v4.0 {t[c]} / migration {b[c]}")
    print(f"\n{len(shared)} v4.0 tables built, {differing} with differences; {len(extensions)} extension tables:")
    print("  " + ", ".join(extensions))
    return 0


if __name__ == "__main__":
    sys.exit(main())
