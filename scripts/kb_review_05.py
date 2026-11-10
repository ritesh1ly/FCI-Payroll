"""KB review of Module 05 (Prompt 7): every scenario of kb/07-analysis/scenarios/05-payroll-cycle-control.md with
its status and evidence. Regenerate: python scripts/kb_review_05.py > docs/quality/kb-review-05.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
TODO (a Module 05 phase not built yet, named; outside the E2E gate until built),
OTHER (owned by another module per the KB's own index - audited there).
Module 05 is in progress (phases 05a / 05b built): this review is its interim state.
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/05-payroll-cycle-control.md"

E2E = "Playwright 03-payroll-cycle"
GATES = "CycleGatesTest, CycleGateRulesTest, CycleControlIT"
CYCLE = "CycleIT"

# (first, last, status, evidence / owner) - ranges of SC-05-nnnn in file order.
MAP = [
    (1, 1, "PARTIAL", "The Maker of the instance initiates and freezes (OTP) on C-02 (" + CYCLE + ".BR_CYC_008_..., " + E2E + "); the configurable run order of payroll types is M-10 (phase 05f)"),
    (2, 2, "PARTIAL", "Lock after verification; locked results immutable (trigger, " + CYCLE + ", " + E2E + "); I-23 built (module 01); I-19 MDP is phase 05g"),
    (3, 3, "TODO", "phase 05e: controlled undo before EBS posting with the Gate Override & Undo / Reversal Register"),
    (4, 4, "COVERED", "One instance per (PAYROLL_YYMM, ORG_UNIT_ID) at HQ / ZO / RO / DO (" + CYCLE + ".aCycleOfOneOfficeIsInvisibleToAnother..., Playwright office-scope-switching 'C-02 follows the office')"),
    (5, 5, "PARTIAL", "The HQ consolidated view is read-only (RbacFlowIT.theConsolidatedHqViewIsReadOnlyOnRegionData); the cycle roll-up on D-01 is phase 05i"),
    (6, 6, "COVERED", "Lock authority only within the session's own scope; HQ never locks a unit's cycle (ApprovalEngineIT, " + CYCLE + ", threat model T7)"),
    (7, 7, "PARTIAL", "The C-13 dry run is a SIMULATION computation run that changes no cycle state (JobRequestIT, Playwright 02-batch-jobs); the C-09 screen and simulation access groups are phase 05i"),
    (8, 8, "COVERED", "Actual run only through initiate, freeze, compute, verify, lock (" + CYCLE + ".BR_CYC_008_..., " + E2E + ")"),
    (9, 9, "TODO", "phase 05i: the General Run marker on every screen and export"),
    (10, 10, "COVERED", "No un-freeze, reopen or rollback after lock: forward-only trigger (CycleControlIT.SRS_15_10_..., " + E2E + " 'a locked cycle offers no action')"),
    (11, 11, "COVERED", "Verified -> Locked only by the Checker of the instance after the gates (" + CYCLE + ", " + GATES + ", " + E2E + ")"),
    (12, 12, "PARTIAL", "Posted to EBS and Supplementary are independent flags on C-02 (payroll_cycle_instance, cycle.test.tsx SRS-15-12, added 2026-10-22); setting Posted to EBS is O-02 (modules 15 / 23)"),
    (13, 13, "COVERED", "Negative / zero / below-M-14-floor nets quarantined; G6 per member (CycleRunsGateSourcesTest, CycleControlIT.SRS_15_13_...)"),
    (14, 18, "COVERED", "Gates G0-G6 evaluated per instance from their sources, hard gates never accepted, G2 / G5 / G6 accepted with a reason, met / unmet on C-01 (" + GATES + ", " + E2E + "); the register screen and export are phase 05e"),
    (19, 19, "TODO", "phase 05g: I-19 Maintain Direct Payroll"),
    (20, 20, "COVERED", "As SC-05-0004 (instances at the four levels from go-live)"),
    (21, 21, "OTHER", "labour modules 20-22 and module 14 (labour pre-payroll pay slip from a General Run)"),
    (22, 22, "TODO", "phase 05g: an MDP entry by its nature (earning / deduction / recovery)"),
    (23, 23, "PARTIAL", "Initiation shows the period and refuses one ahead of the current period or outside the calendar (" + CYCLE + ".initiationIsRefusedAheadOfTheCurrentPeriodAndOutsideTheCalendar); the P1-P5 initiation checks and the unlocked-prior-period refusal are open (Q-05-2; P4 / P5 need module 23)"),
    (24, 24, "COVERED", "Each compute attempt is a computation_run with its ID, status, user, parameter_hash and rule_version_set_hash (BR_CYC_011 tests, " + CYCLE + ")"),
    (25, 25, "PARTIAL", "Committed work units are kept and a crashed run is failed for restart (CrashRecoveryIT); operator resume vs restart is open (plan 05, after 05c)"),
    (26, 26, "TODO", "plan 05 (after 05c): refuse a resume when the parameter or rule-version hash changed (BR-CYC-011 ... 017)"),
    (27, 27, "COVERED", "A failed or partial run cannot be verified, locked or posted: gate G0 (" + GATES + ")"),
    (28, 28, "TODO", "phase 05c: exclusive cycle lease (holder shown, action disabled, heartbeat, force-release with OTP)"),
    (29, 29, "PARTIAL", "Optimistic locking on the masters (@Version, COM-412-STALE); naming the conflicting change is open"),
    (30, 31, "TODO", "phase 05d: Checker return (scope, reason, withdraw) and un-freeze (Maker -> Checker -> X-01 with OTP)"),
    (32, 32, "COVERED", "OTP at freeze (acting user) and at the lock request / approval (Checker) (" + E2E + ", SupplementaryApprovalIT pattern, StepUpIT)"),
    (33, 33, "COVERED", "The Maker initiates only its own instance; roles keep to their steps (" + CYCLE + ".aCycleOfOneOfficeIsInvisibleToAnotherAndRolesKeepToTheirSteps, SRS_G_1_whoeverStartedTheComputationNeverVerifiesIt)"),
    (34, 34, "OTHER", "modules 15 / 23 (bank advice, EBS AP posting O-02, remittance O-11 after lock)"),
    (35, 35, "TODO", "phase 05h: emergency stop of payment release (suspend / resume via X-01)"),
    (36, 36, "TODO", "not in a phase yet: pull each feeder module's approved-but-unposted queue before freeze (feeders: modules 07-13); raise with the feeder modules"),
    (37, 37, "COVERED", "Supplementary payments only for a month-end-locked period on I-23 (SupplementaryIT BR_CYC_010_..., Playwright 02-supplementary-payment)"),
    (38, 38, "OTHER", "module 15 (Maker / Checker authorisation stamp on the payroll voucher and payment documents)"),
    (39, 39, "COVERED", "Pre-payroll checks are configured rows with severity; Block stops the lock, Warn is acknowledged (CycleControlIT.SRS_15_39_..., " + GATES + ")"),
    (40, 40, "PARTIAL", "HRMS_CHANGE_PENDING is a Block check, 'not available' (unmet) until I-21 feeds it (module 16; plan 05 D-2)"),
    (41, 41, "OTHER", "module 23 (APPS place-of-posting validation before invoice posting)"),
    (42, 42, "COVERED", "An enabled HRMS fallback upload not yet approved is a Block check (HRMS_FALLBACK_NOT_APPROVED evaluator, CycleControlIT)"),
    (43, 53, "TODO", "phase 05i: D-01 Cycle Status home (summary, KPIs, computation status by payroll type)"),
    (54, 55, "TODO", "phase 05f: M-10 maintain calendar and cycle setup under maker-checker; overlapping periods refused"),
    (56, 56, "COVERED", "As SC-05-0033"),
    (57, 57, "PARTIAL", "The calendar drives initiation (inCalendar), compute-by and lock-target dates on C-02 (" + CYCLE + "); its maintenance is phase 05f"),
    (58, 62, "TODO", "phase 05f: M-10 fields (period dates, supplementary / MDP window, targets, run order, cycle parameters)"),
    (63, 63, "PARTIAL", "C-08 is X-05 filtered to the cycle (ExceptionWorklistIT, Playwright 03-exceptions-worklist); computation exceptions have their producer in module 16"),
    (64, 64, "COVERED", "An HRMS-owned exception is shown as upstream and corrected in HRMS, never in Payroll (ExceptionWorklistIT.SRS_EH_4_...)"),
    (65, 66, "PARTIAL", "Quarantined members are reviewed on C-01 (CycleControlIT.SRS_15_13_...); cleared exceptions feed G2 (" + GATES + "); listing them on C-08 needs the computation producers (module 16)"),
    (67, 74, "PARTIAL", "X-05 shows count, severity, field, source, fix where and 'Blocks G2?' (general-components.test.tsx); Payroll Type is not shown (F-05-3); severity uses the DDL vocabulary BLOCKING / WARNING / INFORMATIONAL / SECURITY, not the SRS field's Critical / High / Medium / Review (CF-21)"),
    (75, 85, "OTHER", "modules 15 / 23 (O-02 bank advice and EBS posting)"),
    (86, 86, "PARTIAL", "Initiate, freeze, compute, verify and lock request built (" + CYCLE + ", " + E2E + "); lease, return and un-freeze are phases 05c / 05d"),
    (87, 87, "COVERED", "Outside the Checker's scope the instance is invisible (" + CYCLE + ", office-scope-switching E2E)"),
    (88, 88, "PARTIAL", "A member whose computation fails is skipped (MEMBER_DATA) and keeps G0 unmet; per-member exception routing to C-08 is module 16"),
    (89, 91, "COVERED", "Lock refused naming each unmet gate; a failed Block check refuses it; a failed run cannot be verified (" + GATES + ", " + E2E + ")"),
    (92, 94, "TODO", "phases 05c / 05d: lease held by another user, Checker return, un-freeze for late input"),
    (95, 95, "COVERED", "As SC-05-0010"),
    (96, 97, "PARTIAL", "Lock and immutable results built; posting is module 23; the consolidated MIS view is phase 05i"),
    (98, 98, "COVERED", "As SC-05-0033 / 0087"),
    (99, 99, "PARTIAL", "No period ahead of the current one and none outside the calendar; refusing an unlocked prior period is open (Q-05-2)"),
    (100, 100, "COVERED", "Initiated / Frozen / Computed / Verified / Locked (" + CYCLE + ", " + E2E + ")"),
    (101, 102, "PARTIAL", "As SC-05-0012 (flags shown on C-02 since 2026-10-22; set by O-02 / the supplementary window)"),
    (103, 103, "COVERED", "As SC-05-0014 ... 0018"),
    (104, 104, "TODO", "plan 05: pre-run checks P1-P5 panel (P4 / P5 need module 23)"),
    (105, 105, "PARTIAL", "Every acceptance is recorded for the register; the register screen and export are phase 05e"),
    (106, 106, "TODO", "phase 05d: un-freeze request"),
    (107, 107, "PARTIAL", "Current run ID, status and work-unit progress on C-02; the lease holder is phase 05c"),
    (108, 108, "PARTIAL", "Lock needs the Checker's OTP (" + E2E + "); the authorisation stamp on the voucher is module 15"),
    (109, 109, "PARTIAL", "The fallback state drives the HRMS_FALLBACK_NOT_APPROVED check (CycleGateService); its Not enabled / Enabled / Uploaded / Approved / Reconciled display on C-01 is open"),
    (110, 110, "COVERED", "As SC-05-0039"),
    (111, 111, "PARTIAL", "As SC-05-0007"),
    (112, 113, "TODO", "phase 05i: queue above the interactive limit; alert when masters or rules change after a run"),
    (114, 115, "PARTIAL", "A SIMULATION run is never posted or locked and is recorded (computation_run, job audit); never shown to a member needs the eSeva payslip (module 14)"),
    (116, 117, "TODO", "phase 05i: population selection and the simulation marker"),
    (118, 129, "TODO", "phase 05g: I-19 Maintain Direct Payroll (capture, approve, amend, cancel; fields)"),
]


def main() -> None:
    rows = [line for line in io.open(SCENARIOS, encoding="utf-8") if line.startswith("| SC-05-")]
    out = sys.stdout
    out.write("# KB review — Module 05 (Prompt 7), interim\n\n")
    out.write(
        "Generated by `scripts/kb_review_05.py` from `kb/07-analysis/scenarios/05-payroll-cycle-control.md` "
        f"({len(rows)} scenarios). Module 05 is in progress: phases 05a / 05b are built, 05c-05i are not. "
        "COVERED: built and tested. PARTIAL: built; the named part is open or belongs to another module. "
        "TODO: a Module 05 phase not built yet (outside the E2E gate until built). OTHER: owned by another module.\n\n"
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
        + f" (total {len(rows)}). No blocking gap for phases 05a / 05b; findings below.\n\n"
    )
    out.write("## Findings of this review\n\n")
    out.write("| ID | Finding | Action |\n|---|---|---|\n")
    out.write("| F-05-1 | Posted to EBS and Supplementary flags (SRS-15-12) were stored on payroll_cycle_instance but shown on no screen, although plan 05b listed them | FIXED 2026-10-22: CycleView carries both; C-02 shows them beside the status (cycle.test.tsx) |\n")
    out.write("| Q-05-2 | Initiation does not refuse a period whose prior period of the same instance is not locked (SRS-15-33, CYC-4, C-01 field 'no skipping an unlocked prior period'); the first cycle of an instance is the exception | Build with the P1-P5 initiation checks; enforcing it changes the demo data flows (E2E periods), so it needs its own tests |\n")
    out.write("| CF-21 | Exception severity: SRS C-08 field Critical / High / Medium / Review vs DDL BLOCKING / WARNING / INFORMATIONAL / SECURITY | as recorded in the KB conflict log; X-05 follows the DDL CHECK |\n")
    out.write("| F-05-3 | C-08 field Payroll Type (Employee / Departmental / DPS / NWNP, SC-05-0068) is not a column of X-05 | build with the computation exceptions (module 16); exempted from the E2E gate with this reason |\n")
    out.write("| F-05-2 | SRS-15-36 (pull the feeders' approved-but-unposted queues before freeze) is in no plan-05 phase | add to plan 05 with the feeder modules (07-13) |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
