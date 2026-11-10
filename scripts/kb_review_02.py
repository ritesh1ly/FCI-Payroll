"""KB review of Module 02 (Prompt 7): every scenario of kb/07-analysis/scenarios/02-security-rbac-approvals.md
with its status and evidence. Regenerate: python scripts/kb_review_02.py > docs/quality/kb-review-02.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
OTHER (owned by another module per the KB's own index - audited there).
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/02-security-rbac-approvals.md"

IT = "integration test"
E2E = "Playwright"

# (first, last, status, evidence / owner) - ranges of SC-02-nnnn in file order.
MAP = [
    (1, 1, "PARTIAL", "Role catalogue on A-02 as configuration, maker-checker with OTP (RbacEscalationIT, AdministrationIT, " + E2E + " step-up-and-session); module approver roles (Manager / AGM CPF, IR-L, Depot Manager ...) PROVISIONAL without permissions until their modules - D-8"),
    (2, 2, "COVERED", "Access groups pair a role with an office scope; a user holds several; visibility follows the active group (ScopeResolver, RbacFlowIT, AccessAdminIT, " + E2E + " office-scope-switching / access-control)"),
    (3, 3, "COVERED", "HQ consolidated view read-only (READ_ONLY_CONSOLIDATED), only on HQ / national groups and only for a role with read-only MIS (AccessAdminIT.SRS_22_3_..., RbacFlowIT)"),
    (4, 4, "COVERED", "Inbox shows raising user, value / impact and age against the target (InboxTargetIT, OffCycleIT, approval-inbox.test, " + E2E + " workflow-master)"),
    (5, 5, "COVERED", "Separation of duties by identity on every maker-checker item (WF-403-SOD; ApprovalEngineIT, " + E2E + " access-control)"),
    (6, 6, "COVERED", "Access groups effective-dated, end-dated never deleted, audited (AccessAdminIT.SRS_G_37_..., AdministrationIT)"),
    (7, 7, "PARTIAL", "Finance holds VIEW / EXPORT on the built screens (permission seed, RbacEscalationIT); the computed and posted result screens arrive with modules 05 / 16"),
    (8, 8, "COVERED", "Roles and permissions as configuration; every A-02 change maker-checker with OTP (RoleAdminService, AdministrationIT, " + E2E + " step-up-and-session)"),
    (9, 9, "PARTIAL", "Approval authority by office level per approval action (approval_workflow_action.office_level, ApprovalEngineIT); the per-module table is seeded by each module with its actions - D-11"),
    (10, 10, "PARTIAL", "Designation-to-role mapping on X-01: not built (no DDL table) - D-9"),
    (11, 11, "PARTIAL", "Cycle units and the Maker / separate Checker readiness of each (UserReportIT, " + E2E + " administration); the DO minimum staff is an unseeded A-07 setting (DO_MINIMUM_STAFF)"),
    (12, 12, "COVERED", "Maker-Checker-Approver matrix as workflow master rows, maintained on A-08 (WorkflowMasterIT, " + E2E + " workflow-master); per-module seed - D-11"),
    (13, 13, "COVERED", "Password, OTP, session and user governance (Module 01 sign-in; step-up, session hardening, F-2 session administration: StepUpIT, SessionSecurityIT, SessionAdminIT)"),
    (14, 16, "COVERED", "Every controlled change routes by an approval action; action fields; 2 or 3 levels, no amount (WorkflowRulesTest, WorkflowMasterIT, ApprovalEngineIT, " + E2E + " workflow-master)"),
    (17, 17, "COVERED", "Workflow master at HQ only, approved by a different Payroll Checker (WorkflowMasterIT, " + E2E + " workflow-master)"),
    (18, 18, "OTHER", "module 07 (CPF approvals by role)"),
    (19, 20, "COVERED", "Approve / Return to the approving roles in scope; group definitions and assignments approved with OTP (ApprovalEngineIT, AccessAdminIT, " + E2E + " maker-checker / step-up-and-session)"),
    (21, 21, "COVERED", "Auditor read-only: persona AUDITOR, A-05 and R-* view / export only (V027_1, RbacEscalationIT)"),
    (22, 22, "PARTIAL", "A-08 names any active role at a level; the IR-L Operating Division role is not in the catalogue yet - D-8"),
    (23, 23, "PARTIAL", "Report-only access groups (access_mode REPORTS_ONLY); the report download log (report_export_log) comes with module 15"),
    (24, 28, "COVERED", "Configure roles and groups: main flow, consolidated-view rule, outside scope / unapproved refused, menu follows the groups, assignment audited (AccessAdminIT, RbacEscalationIT, RbacFlowIT, " + E2E + " step-up-and-session / access-control)"),
    (29, 31, "COVERED", "User active, group name unique and approved before assignment, role from the catalogue (AccessAdminIT, AdministrationIT)"),
    (32, 32, "PARTIAL", "Scopes HQ / zone / region / division (AccessAdminIT); depot / unit scoping for the Labour Dealing Hand arrives with modules 20-22"),
    (33, 36, "COVERED", "Consolidated view only on HQ groups; one version per action and date; office levels; 2 or 3 levels (AccessAdminIT, WorkflowRulesTest, WorkflowMasterIT)"),
    (37, 37, "PARTIAL", "Role and office level per level (WorkflowRulesTest); IR-L as a level - D-8"),
    (38, 41, "COVERED", "OTP / attachment / target, dates, versions, status, in-progress items keep their version, main flow, inline refusal (WorkflowRulesTest, WorkflowMasterIT, ApprovalEngineIT, " + E2E + " workflow-master)"),
    (42, 42, "PARTIAL", "Versions and changes audited (WorkflowMasterIT); the HQ / ZO / RO / DO delegation seed comes per module - D-11"),
    (43, 46, "COVERED", "End sessions, suspend a group, unlock with a reason; OTP self-service unlock; outside scope refused; audited (SessionAdminIT, AuthenticationFlowIT.forgotPassword..., IamModelTest.SRS_G_49_aLockedUserUnlocksTheOwnAccountByAnOtpReset, " + E2E + " administration / sign-in)"),
    (47, 56, "COVERED", "Inbox use case and fields: scope, raising user, SoD, three levels with OTP, no approver flagged to HQ, delegation as an A-08 change, audit, past-target flag (ApprovalEngineIT, InboxTargetIT, WorkflowMasterIT, approval-inbox.test, " + E2E + " workflow-master / access-control)"),
]


def main() -> None:
    rows = [line for line in io.open(SCENARIOS, encoding="utf-8") if line.startswith("| SC-02-")]
    out = sys.stdout
    out.write("# KB review — Module 02 (Prompt 7)\n\n")
    out.write(
        "Generated by `scripts/kb_review_02.py` from `kb/07-analysis/scenarios/02-security-rbac-approvals.md` "
        f"({len(rows)} scenarios). COVERED: built and tested in Module 02. PARTIAL: built; the named part is open "
        "or belongs to another module. OTHER: owned by another module, audited there.\n\n"
    )
    counts: dict[str, int] = {}
    table = []
    for line in rows:
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        sid, typ, scen = cells[0], cells[1], cells[2]
        n = int(sid.split("-")[-1])
        status, evidence = next((s, e) for a, b, s, e in MAP if a <= n <= b)
        counts[status] = counts.get(status, 0) + 1
        topic = re.sub(r"\s+", " ", scen)[:70].replace("|", "/")
        table.append(f"| {sid} | {typ} | {topic} | {status} | {evidence} |")
    out.write(
        "Summary: "
        + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))
        + f" (total {len(rows)}). No blocking gap for the Module 02 functions; open items below.\n\n"
    )
    out.write("## Open items raised by this review\n\n")
    out.write("| ID | Item | Proposal |\n|---|---|---|\n")
    out.write("| D-8 | Module approver roles (Manager / AGM CPF, Unit CPF Administrator, CPF sanctioning, HQ Establishment, Depot Manager, IR-L) | created with their modules once FCI confirms base roles |\n")
    out.write("| D-9 | Designation-to-role mapping (SRS-22-10): no DDL table | extension table as a suggestion when assigning, or FCI's answer |\n")
    out.write("| D-10 | Target turnaround unit: SRS working days vs DDL hours | stored and shown in hours until a working-day calendar exists |\n")
    out.write("| D-11 | Workflow master seed per module (FRS 6.6.4 matrix file not in the repository) | each module seeds its actions; third-level restriction of SRS-22-12 once the list is confirmed |\n")
    out.write("| KB-02-1 | SRS-22-3 \"role permits read-only MIS access\" read as VIEW on R-01 Consolidated MIS | FCI to confirm |\n")
    out.write("| KB-02-2 | SRS-22-29 report download log | module 15 (report_export_log) |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
