#!/usr/bin/env python3
"""Generate the employee rows the HRMS mock server must hold so that it agrees with Payroll's demo data.

Payroll's demo employees (db/seed/*.sql, table employee) are the people the screens and the
Playwright suite use. HRMS identifies an employee by emp_num; Payroll holds the same number as its
employee number (assumption API-23-15). A call to the mock for a Payroll demo employee therefore
needs that employee in the mock, with the same name, sex, birth and joining date, office, basic pay
and status. This script reads Payroll's seed files and writes a SQL file for the mock's `employee`
table (MERGE ... KEY(emp_num) would overwrite; the file uses INSERT for numbers the mock does not
hold and SKIPS the numbers it already holds, so the mock's own edge-case employees (150001 ...)
keep working; the clashes are listed in the header).

Usage: python scripts/integration/gen_mock_employees.py [mock project folder] [mock employee numbers file]
  default folder: C:/Users/user/workspace/fcimockserver
Output: <folder>/src/main/resources/db/data/common/05_payroll_employees.sql
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SEED = ROOT / "db/seed"
STATUS = {
    "IN_SERVICE": "ACTIVE",
    "SUSPENDED": "SUSPENDED",
    "RETIRED": "RETIRED",
    "SUPERANNUATED": "RETIRED",
    "RESIGNED": "RESIGNED",
    "DECEASED": "DECEASED",
    "DEAD": "DECEASED",
}
INSERT = re.compile(r"INSERT INTO employee \(([^)]*)\) VALUES \((.*)\);\s*$")


def split_top(values: str):
    """Split a VALUES list at top-level commas (quotes and parentheses nest)."""
    out, depth, quote, cur = [], 0, False, []
    i = 0
    while i < len(values):
        ch = values[i]
        if quote:
            cur.append(ch)
            if ch == "'":
                if i + 1 < len(values) and values[i + 1] == "'":
                    cur.append("'")
                    i += 1
                else:
                    quote = False
        elif ch == "'":
            quote = True
            cur.append(ch)
        elif ch == "(":
            depth += 1
            cur.append(ch)
        elif ch == ")":
            depth -= 1
            cur.append(ch)
        elif ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur).strip())
    return out


def lit(token: str):
    """The plain value of a literal or of `(SELECT ... WHERE x_code = 'V')`; None for NULL."""
    t = token.strip()
    if t.upper() == "NULL":
        return None
    m = re.match(r"^DATE '([^']+)'$", t)
    if m:
        return m.group(1)
    m = re.match(r"^'((?:[^']|'')*)'$", t)
    if m:
        return m.group(1).replace("''", "'")
    m = re.search(r"WHERE \w+ = '([^']+)'", t)
    if m:
        return m.group(1)
    if re.match(r"^-?\d+(\.\d+)?$", t):
        return t
    return None


def q(value):
    return "NULL" if value is None else "'" + str(value).replace("'", "''") + "'"


def main() -> None:
    mock = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("C:/Users/user/workspace/fcimockserver")
    held_text = (mock / "src/main/resources/db/data/common/02_employee.sql").read_text(encoding="utf8")
    held = {int(n) for n in re.findall(r"^\((\d{5,6}),", held_text, re.M)}
    rows, clashes, skipped = {}, [], []
    for f in sorted(SEED.glob("*.sql")):
        for line in f.read_text(encoding="utf8").splitlines():
            m = INSERT.match(line.strip())
            if not m:
                continue
            cols = [c.strip() for c in m.group(1).split(",")]
            vals = split_top(m.group(2))
            if len(cols) != len(vals):
                skipped.append(f"{f.name}: column count differs")
                continue
            r = {c: lit(v) for c, v in zip(cols, vals)}
            num = r.get("emp_num")
            if not num or not num.isdigit():
                skipped.append(f"{f.name}: emp_num {num!r} is not numeric")
                continue
            n = int(num)
            if n in held:
                clashes.append(n)
                continue
            status = STATUS.get(r.get("employment_status") or "", None)
            if status is None:
                skipped.append(f"{f.name}: {num} has employment_status {r.get('employment_status')!r}")
                continue
            name = " ".join(x for x in (r.get("first_name"), r.get("last_name")) if x) or f"Employee {n}"
            rows[n] = (
                n,
                name,
                r.get("designation_id"),
                r.get("grade_id"),
                r.get("department_id"),
                r.get("parent_zone_id"),
                r.get("location_id"),
                r.get("gender"),
                r.get("birth_date"),
                r.get("comp_joining_date"),
                status,
                r.get("current_basic") or "0",
                r.get("pay_pattern") or "IDA",
                r.get("pan_number"),
                r.get("bank_account_number"),
                r.get("cpf_code"),
            )
    header = [
        "-- =============================================================================",
        "-- Shared: the employees of FCI Payroll's demo data (generated, do not edit by hand).",
        "--   Source : FCI-Payroll-Full/db/seed/*.sql (table employee), emp_num = the Payroll employee number",
        "--   Tool   : scripts/integration/gen_mock_employees.py in the Payroll repository",
        f"--   Rows   : {len(rows)}; numbers the mock already holds are NOT touched ({len(set(clashes))}: "
        + ", ".join(str(c) for c in sorted(set(clashes))[:20])
        + ")",
        "--   Mapping: designation/grade/department/zone/office = the Payroll CODES; dor and category are not held by",
        "--            Payroll and stay NULL; basic_pay 0 where Payroll has no basic yet (setup pending).",
        "-- =============================================================================",
        "",
    ]
    out = list(header)
    for n in sorted(rows):
        (num, name, des, grade, dept, zone, loc, sex, dob, doj, status, basic, pat, pan, bank, cpf) = rows[n]
        out.append(
            "INSERT INTO employee (emp_num, emp_name, designation, grade, department, region, office, gender, dob, doj,"
            " emp_status, basic_pay, pay_scale_type, pan, bank_account, cpf_account_no) VALUES "
            f"({num}, {q(name)}, {q(des)}, {q(grade)}, {q(dept)}, {q(zone)}, {q(loc)}, {q(sex)}, "
            f"{'DATE ' + q(dob) if dob else 'NULL'}, {'DATE ' + q(doj) if doj else 'NULL'}, {q(status)}, {basic}, "
            f"{q((pat or 'IDA')[:5])}, {q(pan)}, {q(bank)}, {q(cpf)});"
        )
    target = mock / "src/main/resources/db/data/common/05_payroll_employees.sql"
    target.write_text("\n".join(out) + "\n", encoding="utf8", newline="\n")
    print(f"{len(rows)} employees -> {target}")
    print(f"clashes with the mock's own employees: {sorted(set(clashes))}")
    for s in skipped[:15]:
        print("skipped:", s)
    print(f"skipped in total: {len(skipped)}")


if __name__ == "__main__":
    main()
