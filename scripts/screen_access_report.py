"""Who can open which screen: for every screen of the built modules, the roles holding its permissions (in
force today) with their actions, and the demo users holding an active, approved assignment to a group of
that role. Read from the running database, so it is what the application enforces (R-RBAC: access is
configuration). Planned modules are listed from their KB spec.

Usage (a local stack running, read-only session with the application's full URL):
  python -I scripts/screen_access_report.py <runtime dir> [h2 port] > docs/rbac/SCREEN_ACCESS.md
  e.g. python -I scripts/screen_access_report.py scripts/local/runtime-b 9392
"""

import glob
import re
import os
import subprocess
import sys
from collections import defaultdict

MODULES = [
    ("01", "Platform general", ["X-05", "X-06", "X-07", "I-20", "I-23"]),
    ("02", "Security, RBAC and approvals", ["A-01", "A-02", "A-03", "A-04", "A-06", "A-07", "A-08", "W-01", "W-02"]),
    ("03", "Audit trail", ["A-05"]),
    ("04", "Masters and MDM (with the masters built before it)",
     ["M-14", "M-15", "M-25", "M-26", "M-27", "M-16", "M-01", "M-04", "M-11", "M-22", "M-23", "M-24", "I-17"]),
    ("05", "Payroll cycle control (with C-02 / C-13, R-01 and E-01)", ["C-01", "C-02", "C-13", "R-01", "E-01"]),
    ("06", "Rule engine (M-08 built; the M-07 screen is phase 06d, its grants exist)", ["M-08", "M-07"]),
    ("07", "CPF / PF / VPF (with M-18 shared by Module 08)", ["M-18", "M-19", "I-22", "C-12", "I-04"]),
    ("08", "Income tax, TDS & PT (08a-08c built: M-18 tax cards, I-07, E-01 declarations)", ["I-07"]),
]

PLANNED = """
## 08 — Income tax, TDS & PT, phases not yet built (plan `docs/plan/08-income-tax.md`)

| Screen | Roles (SRS screen catalogue) | Demo user |
|---|---|---|
| I-18 Savings & Tax Rebate Declarations (08d) | Payroll Maker enters, Payroll Checker approves | `tax.maker.delhi@fci-demo.test`, `tax.checker.delhi@fci-demo.test` |
| C-07 tax computation (08e) | computed in the payroll cycle; Payroll Checker reviews | `tax.checker.delhi@fci-demo.test` |
| M-17 statutory output definitions (08f) | Rule Administrator proposes, Rule Approver approves | `su.hq@fci-demo.test`, `rule.approver@fci-demo.test` |
| C-10 Form 16, C-11 Form 24Q (08g, 08h) | Payroll Maker generates / uploads / imports, Payroll Checker releases (SRS-7-21) | `tax.maker.delhi@fci-demo.test`, `tax.checker.delhi@fci-demo.test` |
"""


def query(runtime: str, port: str, sql: str) -> list[str]:
    jar = sorted(glob.glob(os.path.join(runtime, "h2", "h2-*.jar")))[-1]
    password = open(os.path.join(runtime, "config", "secrets", "backend", "spring.datasource.password"), encoding="utf-8").read().strip()
    url = f"jdbc:h2:tcp://127.0.0.1:{port}/mem:fcilekha;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    out = subprocess.run(
        ["java", "-cp", jar, "org.h2.tools.Shell", "-url", url, "-user", "fci_payroll", "-password", password, "-sql", sql],
        capture_output=True, text=True, check=True, encoding="utf-8",
    ).stdout
    return [line.strip() for line in out.splitlines() if "~" in line]


def main() -> int:
    runtime, port = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "9092")
    grants = query(runtime, port,
        "SELECT s.screen_code||'~'||s.screen_name||'~'||r.role_code||'~'||r.role_name||'~'||p.action_code FROM app_screen s"
        " JOIN app_permission p ON p.screen_code = s.screen_code JOIN app_role_permission rp ON rp.permission_id = p.permission_id"
        " AND rp.effective_from <= CURRENT_DATE AND (rp.effective_to IS NULL OR rp.effective_to >= CURRENT_DATE)"
        " JOIN app_role r ON r.role_id = rp.role_id")
    users = query(runtime, port,
        "SELECT r.role_code||'~'||u.login_id||'~'||g.scope_type||'~'||COALESCE(l.location_code, z.zone_code, '') FROM app_user_access_group a"
        " JOIN app_user u ON u.user_id = a.user_id JOIN access_group g ON g.access_group_id = a.access_group_id"
        " JOIN app_role r ON r.role_id = g.role_id LEFT JOIN access_group_scope sc ON sc.access_group_id = g.access_group_id"
        " LEFT JOIN org_location l ON l.location_id = sc.location_id LEFT JOIN org_zone z ON z.zone_id = sc.zone_id"
        " JOIN approval_queue aq ON aq.approval_id = a.approval_id AND aq.status = 'APPROVED'"
        " WHERE a.revoked_at IS NULL AND a.effective_from <= CURRENT_DATE"
        " AND (a.effective_to IS NULL OR a.effective_to >= CURRENT_DATE) AND u.user_status = 'ACTIVE'"
        " AND g.effective_from <= CURRENT_DATE AND (g.effective_to IS NULL OR g.effective_to >= CURRENT_DATE)"
        # eSeva members act through the eSeva persona on their own record, not through an access group.
        " UNION SELECT 'EMPLOYEE_ESEVA'||'~'||u.login_id||'~'||'own record'||'~'||'' FROM app_user u"
        " WHERE u.user_category = 'ESEVA' AND u.user_status = 'ACTIVE'")
    curated = set(re.findall(r"`([a-z.]+@fci-demo\.test)`", open(os.path.join(os.path.dirname(__file__), "..", "docs", "rbac", "SEED_USERS.md"), encoding="utf-8").read()))
    screens: dict[str, str] = {}
    actions: dict[str, dict[tuple[str, str], set[str]]] = defaultdict(lambda: defaultdict(set))
    for row in grants:
        code, name, role, role_name, action = row.split("~")
        screens[code] = name
        actions[code][(role, role_name)].add(action)
    holders: dict[str, set[str]] = defaultdict(set)
    others: dict[str, set[str]] = defaultdict(set)
    for row in users:
        role, login, scope_type, where = row.split("~")
        if login in curated:
            holders[role].add(f"`{login}` ({scope_type.lower()}{' ' + where if where else ''})")
        else:
            others[role].add(login)
    order = ["VIEW", "CREATE", "EDIT", "SUBMIT", "APPROVE", "DELETE", "EXPORT"]
    w = sys.stdout.write
    w("# Screen access — roles and demo users (Modules 01–08)\n\n")
    w("Generated by `scripts/screen_access_report.py` from the running database: the roles holding each screen's\n")
    w("permissions today, and the demo users (password `Fci@Demo2026`, `docs/rbac/SEED_USERS.md`) with an active,\n")
    w("approved assignment to a group in force of that role, with the group's scope. Listed: the curated users of\n")
    w("SEED_USERS.md; the RBAC test-scenario users are only counted; eSeva members act on their own record (persona,\n")
    w("no group). Roles without a curated user are named so a tester knows to use a scenario user. A user acts in one role and office at a\n")
    w("time (top bar); sensitive screens ask the OTP step-up. Access is configuration (A-02 / A-03 / A-04): regenerate\n")
    w("after a grant changes. The Module 08 phases not yet built are listed from the plan.\n")
    for number, title, codes in MODULES:
        w(f"\n## {number} — {title}\n\n| Screen | Role | Actions | Demo users (scope) |\n|---|---|---|---|\n")
        for code in codes:
            if code not in actions:
                w(f"| {code} | — | no permission granted | — |\n")
                continue
            for (role, role_name), acts in sorted(actions[code].items()):
                act = ", ".join(a for a in order if a in acts) + "".join(f", {a}" for a in sorted(acts - set(order)))
                who = "<br>".join(sorted(holders.get(role, set()))) or "— (no curated demo user)"
                if others.get(role):
                    who += f"<br>+{len(others[role])} RBAC test-scenario users"
                w(f"| {code} {screens[code]} | {role_name} (`{role}`) | {act} | {who} |\n")
    w(PLANNED)
    return 0


if __name__ == "__main__":
    sys.exit(main())
