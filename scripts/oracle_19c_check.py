#!/usr/bin/env python3
"""Oracle 19c compatibility check of the SQL the application runs on Oracle (feature/Oracle).

The local database is Oracle 26ai, which accepts syntax that 19c (the target of record, CLAUDE.md /
ADR-0019) refuses, and the test suites run on H2, which accepts even more. This check scans
  - the Oracle and vendor-neutral migrations, the seeds, the Oracle volume and screen demo data and the Oracle install
    kit db/install/oracle with its one-run setup sets db/oracle-setup (plain, go, payroll_continue; whole
    statements; docs/db/INSTALL_KIT.md);
  - the SQL inside the Java sources (string literals of backend/src/main/java);
for constructs that 19c does not support. It exits 1 on a finding.

    python scripts/oracle_19c_check.py            # report, exit 1 on a finding
    python scripts/oracle_19c_check.py --list     # also list the files scanned

Not checked (no reliable text pattern): GROUP BY a select-list alias, and SELECT without FROM inside
Java strings that are built from fragments. docs/db/ORACLE_LOCAL.md.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SQL_DIRS = ["db/migration/oracle", "db/migration/common", "db/seed", "db/volume/common", "db/volume/oracle",
            # The screen demo data set (profile screens, OD-141 D-screens): vendor-neutral and Oracle scripts.
            "db/demo-screens/common", "db/demo-screens/oracle",
            "db/install/oracle", "db/oracle-setup", "db/oracle-setup/go", "db/oracle-setup/payroll_continue"]
JAVA_DIR = "backend/src/main/java"
# The H2 ports of the Oracle triggers run only on H2.
JAVA_SKIP = re.compile(r"platform[\\/]persistence[\\/]h2[\\/]")

# (rule, regex, what 19c lacks). Patterns run on SQL with comments and string literals removed.
RULES = [
    ("IF-EXISTS", re.compile(r"\b(CREATE|DROP|ALTER)\b[^;]*?\bIF\s+(NOT\s+)?EXISTS\b", re.I | re.S),
     "IF [NOT] EXISTS in DDL is 23ai"),
    # A column type only: PL/SQL has had BOOLEAN variables all along.
    ("BOOLEAN-TYPE", re.compile(r"^\s*(CREATE|ALTER)\s+(GLOBAL\s+TEMPORARY\s+)?TABLE\b[^;]*\b\w+\s+BOOLEAN\b", re.I | re.S),
     "the SQL BOOLEAN type is 23ai"),
    ("BOOLEAN-LITERAL", re.compile(r"(=|<>|\bIS|\bTHEN|\bELSE|,|\()\s*(TRUE|FALSE)\b", re.I),
     "TRUE / FALSE literals in SQL are 23ai"),
    ("MULTI-ROW-VALUES", re.compile(r"\bVALUES\s*\((?:[^()']|\([^()]*\))*\)\s*,\s*\(", re.I),
     "a multi-row VALUES list is 23ai (use INSERT ... SELECT ... FROM dual UNION ALL ...)"),
    ("TABLE-ALIAS-AS", re.compile(r"\b(FROM|JOIN)\s+[\w.$]+\s+AS\s+\w+", re.I),
     "AS before a table alias is not Oracle SQL (any version)"),
    ("LIMIT", re.compile(r"\bLIMIT\s+(\d+|\?|:\w+)", re.I),
     "LIMIT is not Oracle SQL (use OFFSET ... FETCH NEXT ... ROWS ONLY)"),
    ("VECTOR", re.compile(r"\bVECTOR\s*\(", re.I), "the VECTOR type is 23ai"),
    ("DUALITY", re.compile(r"\bJSON\s+RELATIONAL\s+DUALITY\b", re.I), "duality views are 23ai"),
    ("DOMAIN", re.compile(r"\bCREATE\s+(USECASE\s+)?DOMAIN\b", re.I), "SQL domains are 23ai"),
    ("ANNOTATIONS", re.compile(r"\bANNOTATIONS\s*\(", re.I), "schema annotations are 23ai"),
    ("DEFAULT-ON-NULL-UPDATE", re.compile(r"\bDEFAULT\s+ON\s+NULL\s+FOR\s+INSERT\s+AND\s+UPDATE\b", re.I),
     "DEFAULT ON NULL FOR INSERT AND UPDATE is 23ai"),
    ("ILIKE", re.compile(r"\bILIKE\b", re.I), "ILIKE is not Oracle SQL"),
    ("PG-CAST", re.compile(r"\w\)?::\w", re.I), ":: casts are not Oracle SQL"),
]
SELECT_NO_FROM = re.compile(r"^\s*SELECT\b(?![\s\S]*\bFROM\b)", re.I)


def strip_sql(text: str) -> str:
    """Comments and string literals blanked out, line structure kept."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("--", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif c == "'":
            j = i + 1
            while j < n:
                if text[j] == "'" and j + 1 < n and text[j + 1] == "'":
                    j += 2
                elif text[j] == "'":
                    break
                else:
                    j += 1
            out.append("''" + re.sub(r"[^\n]", "", text[i + 1:j]))
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def statements(text: str):
    """(start line, statement) of a script; PL/SQL blocks end at a line holding only '/'."""
    line, buf, start, plsql = 1, [], 1, False
    for raw in text.splitlines(keepends=True):
        stripped = raw.strip()
        if not buf and stripped:
            start = line
            plsql = bool(re.match(r"(CREATE\s+(OR\s+REPLACE\s+)?(TRIGGER|PROCEDURE|FUNCTION|PACKAGE)|BEGIN|DECLARE)\b",
                                  stripped, re.I))
        if plsql:
            if stripped == "/":
                yield start, "".join(buf)
                buf = []
            else:
                buf.append(raw)
        else:
            buf.append(raw)
            if stripped.endswith(";"):
                yield start, "".join(buf)
                buf = []
        line += 1
    if "".join(buf).strip():
        yield start, "".join(buf)


def update_from(stmt: str) -> bool:
    """UPDATE t SET ... FROM ... (a direct join, 23ai): a FROM at parenthesis depth 0 after SET."""
    m = re.search(r"\bUPDATE\s+[\w.$]+(\s+\w+)?\s+SET\b", stmt, re.I)
    if not m:
        return False
    depth = 0
    for t in re.finditer(r"\(|\)|\bFROM\b|\bWHERE\b|;", stmt[m.end():], re.I):
        tok = t.group(0).upper()
        if tok == "(":
            depth += 1
        elif tok == ")":
            depth -= 1
        elif depth == 0:
            return tok == "FROM"
    return False


def check_sql(stmt: str):
    for rule, rx, why in RULES:
        if rx.search(stmt):
            yield rule, why
    if update_from(stmt):
        yield "UPDATE-FROM", "UPDATE ... SET ... FROM (direct join) is 23ai (use a correlated subquery or MERGE)"
    if SELECT_NO_FROM.search(stmt):
        yield "SELECT-NO-FROM", "SELECT without FROM is 23ai (add FROM dual)"


JAVA_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
SQL_START = re.compile(r"^\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH)\b", re.I)


def java_sql(path: pathlib.Path):
    """Concatenated string literals of one Java statement that form SQL: (line, sql)."""
    text = path.read_text(encoding="utf8")
    # Text blocks.
    for m in re.finditer(r'"""(.*?)"""', text, re.S):
        body = m.group(1)
        if re.search(r"\b(SELECT|INSERT|UPDATE|DELETE|MERGE)\b", body):
            yield text.count("\n", 0, m.start()) + 1, body
    text = re.sub(r'"""(.*?)"""', lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    # Runs of string literals joined with '+' across lines.
    for m in re.finditer(r'"(?:[^"\\\n]|\\.)*"(?:\s*\+\s*"(?:[^"\\\n]|\\.)*")*', text):
        # JPQL (@Query without nativeQuery = true): Hibernate writes the SQL of the dialect.
        # The annotation is open when no ';' lies between its @Query and the literal.
        q = text.rfind("@Query", 0, m.start())
        if q >= 0 and ";" not in text[q:m.start()]:
            end = text.find(";", m.end())
            if "nativeQuery" not in text[q:end if end >= 0 else len(text)]:
                continue
        parts = JAVA_STRING.findall(m.group(0))
        sql = "".join(parts)
        if SQL_START.search(sql) and re.search(r"\b(FROM|INTO|SET|VALUES)\b", sql, re.I):
            yield text.count("\n", 0, m.start()) + 1, sql


def main() -> int:
    findings, scanned = [], []
    for d in SQL_DIRS:
        for f in sorted((ROOT / d).glob("*.sql")):
            scanned.append(f)
            for line, stmt in statements(strip_sql(f.read_text(encoding="utf8"))):
                for rule, why in check_sql(stmt):
                    findings.append((f, line, rule, why))
    for f in sorted((ROOT / JAVA_DIR).rglob("*.java")):
        if JAVA_SKIP.search(str(f)):
            continue
        scanned.append(f)
        for line, sql in java_sql(f):
            for rule, why in check_sql(strip_sql(sql)):
                if rule == "SELECT-NO-FROM":
                    continue  # fragments are joined at run time
                findings.append((f, line, rule, why))
    if "--list" in sys.argv:
        for f in scanned:
            print("scanned", f.relative_to(ROOT))
    for f, line, rule, why in findings:
        print(f"{f.relative_to(ROOT)}:{line}: {rule}: {why}")
    print(f"oracle 19c check: {len(scanned)} files, {len(findings)} findings")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
