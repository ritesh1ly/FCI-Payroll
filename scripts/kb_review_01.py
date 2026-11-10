"""KB review of Module 01 (Prompt 7): every scenario of kb/07-analysis/scenarios/01-platform-general.md
with its status and evidence. Regenerate: python scripts/kb_review_01.py > docs/quality/kb-review-01.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
OTHER (owned by another module per the KB's own index - audited there).
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/01-platform-general.md"

IT = "integration test"
E2E = "Playwright"

# (first, last, status, evidence / owner) - ranges of SC-01-nnnn in file order.
MAP = [
    (1, 1, "COVERED", "Every Module 01 change routes through approval_workflow_action rows (V025_4..6); self-approval and levels: workflow module (RbacFlowIT, Module 00); I-20/I-23/X-07 approvals: OffCycleIT, SupplementaryApprovalIT, FallbackUploadIT, " + E2E + " off-cycle-salary / hrms-fallback-upload"),
    (2, 2, "PARTIAL", "audit_event row per transition, same transaction (OffCycleIT, PayrollQueryIT, SupplementaryService); IP and order reference on the row not asserted for the new transactions - OPEN KB-01-1"),
    (3, 3, "COVERED", "OTP at the Checker's level for X-07 and I-23 (otp_at_level); 428 without, approval with OTP: FallbackUploadIT, SupplementaryApprovalIT, " + E2E + " hrms-fallback-upload; OTP engine: Module 00"),
    (4, 4, "PARTIAL", "Pending-Checker holds refuse edit/cancel with the status (OFC-409-STATUS, SUP-409-STATUS, HFB-409-STATUS; ITs + " + E2E + "); row_version @Version where the DDL has it; freeze hold: module 05"),
    (5, 8, "OTHER", "module 04 (masters, reference lists, GL mapping, ceilings)"),
    (9, 9, "COVERED", "X-05 worklist, tiles, owner, accept G2 with reason, HRMS routed upstream, CSV: ExceptionWorklistIT 9, ExceptionEntryTest, component tests, " + E2E + " exceptions-worklist; producers + C-08 view: module 05 (D-7)"),
    (10, 10, "PARTIAL", "I-20 complete (OffCycleIT 12, OffCycleExclusionTest, " + E2E + " off-cycle-salary); storing the I-23 reference on the case has no DDL column - DB-X2"),
    (11, 11, "COVERED", "X-06 lifecycle, chk_query_checker, eSeva own record, never computes: PayrollQueryIT 9, PayrollQueryTest, " + E2E + " payroll-queries"),
    (12, 12, "OTHER", "module 15 (report definitions)"),
    (13, 13, "PARTIAL", "CSV export on all five Module 01 lists (V025_10; export ITs, " + E2E + " exceptions-worklist); PDF / Excel and bulk actions: platform decision D-6"),
    (14, 14, "PARTIAL", "Separated member: I-20 only death in service, I-23 only separation-related types (OffCycleServiceTest, SupplementaryServiceTest); generic read-only guard: modules 04 / 16"),
    (15, 15, "OTHER", "module 07 (EPS rounding)"),
    (16, 16, "PARTIAL", "Order gate on I-20, I-23 (at insert) and X-07 (b): ITs + " + E2E + "; Sanction Order / Circular need the reference (AttachmentRulesTest); linking an Active M-25 circular: module 04; logging each blocked submission is only the API exception log - OPEN KB-01-2"),
    (17, 17, "PARTIAL", "I-20 / I-23 amounts are PayRules results (rounded per component); full rounding rules: module 16"),
    (18, 18, "OTHER", "module 06 (statutory output rounding)"),
    (19, 20, "OTHER", "modules 09 / 07 (recovery balance, EPS rounding)"),
    (21, 22, "COVERED", "OtpGatedAction holds exactly the 18 actions (Module 00 unit test); retry / cooling-off settings: Module 00"),
    (23, 23, "PARTIAL", "Labels of every new screen in English and Hindi (check-i18n parity; " + E2E + " Hindi screens); User Guide links and field tooltips are not built on any screen - OPEN KB-01-3 (platform)"),
    (24, 24, "OTHER", "module 04 (cost centres from FAP / APPS)"),
    (25, 25, "COVERED", "Disabled actions carry their reason (title) and look disabled (workspace.css): component tests, " + E2E + " off-cycle-salary / supplementary-payment / hrms-fallback-upload"),
    (26, 27, "COVERED", "Workflow version and inbox visibility: workflow module (Module 00); entity labels added for the Module 01 items in W-01"),
    (28, 30, "COVERED", "document module: types from document_type_master, particulars, PDF/JPG/PNG by magic bytes, clamd scan, supersede not delete, access log (AttachmentRulesTest 21, DocumentStoreIT 6, DocumentAttachmentTest); G-32 list holds the Module 01 types, others with their modules"),
    (31, 32, "PARTIAL", "I-23 post-lock only, excluded pay codes (DB + service), duplicate check x3, OTP approval (SupplementaryIT 11, SupplementaryApprovalIT 4, " + E2E + " supplementary-payment); statutory deductions not configured (D-5); APPS invoice and release: module 23"),
    (33, 34, "OTHER", "module 02 (login, deactivation)"),
    (35, 35, "PARTIAL", "Cancel with reason, never delete, cancel refused after the invoice is sent until APPS confirms (entity tests, ITs, " + E2E + "); APPS confirmation flow: module 23"),
    (36, 36, "OTHER", "module 04 (M-25)"),
    (37, 38, "PARTIAL", "Bilingual screens (en/hi bundles); Label Master M-26: module 04"),
    (39, 41, "OTHER", "modules 04 / 06 (M-27, pay codes, locations)"),
    (42, 42, "PARTIAL", "HRMS-sourced exceptions are routed upstream and not resolvable in Payroll (ExceptionWorklistIT, " + E2E + "); I-21 change tasks: module 16"),
    (43, 44, "PARTIAL", "Exactly two fallbacks, enablement under maker-checker, record validation, Checker OTP, order copy for (b), withdraw (FallbackUploadIT 9, FallbackRulesTest 12, " + E2E + " hrms-fallback-upload); reconciliation with HRMS: module 23 (D-4); real export layout: D-2"),
    (45, 47, "OTHER", "module 02 (sessions, passwords, login records)"),
    (48, 48, "OTHER", "module 15"),
    (49, 49, "OTHER", "module 23"),
    (50, 50, "OTHER", "modules 04 / 16 (transfer history)"),
    (51, 51, "PARTIAL", "Status colours on the new screens; accessibility audit: platform"),
    (52, 62, "OTHER", "module 02 (platform-wide maker-checker use case; workflow built in Module 00)"),
    (63, 65, "COVERED", "X-05 use case (see SC-01-0009)"),
    (66, 74, "OTHER", "module 04 (reference masters, accounting / bank mapping, ceilings - built in Module 00 as M-16, M-24, M-23)"),
    (75, 79, "COVERED", "I-20 use case: capture, order, duplicate check, approval, exclusion, cancel (see SC-01-0010)"),
    (80, 91, "PARTIAL", "I-20 fields: reference, member, case type, salary / processing month, computed amount, approval reference, order copy, duplicate check, status, invoice status; PAN / Aadhaar last 4 on the member not shown - OPEN KB-01-4; statuses Disbursed / Paid via I-23: module 23 / DB-X2"),
    (92, 99, "PARTIAL", "I-23 use case (see SC-01-0031); Unit-wise Supplementary Payment Report: module 15; cafeteria counted in arrears: module 13"),
    (100, 111, "PARTIAL", "I-23 fields: reference, member, payment type, period and its state, computed amount, duplicate check, order, status, invoice status; Statutory Deductions amount: D-5 / DB-X3"),
    (112, 121, "COVERED", "X-06 use case and fields (see SC-01-0011)"),
    (122, 139, "PARTIAL", "X-07 use case and fields (see SC-01-0043)"),
    (140, 154, "OTHER", "module 02 (sign-in, OTP, X-08 - login and OTP built in Module 00)"),
    (155, 195, "OTHER", "module 04 (M-25 Circular / Order Master, M-26 Label Master, M-27 Operation Master)"),
]


def main() -> None:
    rows = [
        line
        for line in io.open(SCENARIOS, encoding="utf-8")
        if line.startswith("| SC-01-")
    ]
    out = sys.stdout
    out.write("# KB review — Module 01 (Prompt 7)\n\n")
    out.write(
        "Generated by `scripts/kb_review_01.py` from `kb/07-analysis/scenarios/01-platform-general.md` "
        "(195 scenarios). COVERED: built and tested in Module 01. PARTIAL: built; the named part is open "
        "or belongs to another module. OTHER: owned by another module per the KB's own index (KB 01 §1), "
        "audited there.\n\n"
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
        + f" (total {len(rows)}). No blocking gap for the Module 01 transactions; open items below.\n\n"
    )
    out.write("## Open items raised by this review\n\n")
    out.write("| ID | Item | Proposal |\n|---|---|---|\n")
    out.write("| KB-01-1 | SRS-G-2: the audit row's IP address and order reference are not asserted for the Module 01 transactions | add the assertion with the audit module review (module 03) |\n")
    out.write("| KB-01-2 | SRS-G-16: \"log every blocked submission with the transaction it blocked\" - today only the API exception log | an audit event per refused order-gate submission (module 03 decision) |\n")
    out.write("| KB-01-3 | SRS-G-23: User Guide link and field tooltips on every screen | platform UI work item (Label Master M-26, module 04) |\n")
    out.write("| KB-01-4 | I-20 member field: PAN / Aadhaar last 4 | masked display from the employee API (module 04 / 16) |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
