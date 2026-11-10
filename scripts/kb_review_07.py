"""KB review of Module 07 (Prompt 7): every scenario of kb/07-analysis/scenarios/07-cpf.md with its
status and evidence. Regenerate: python scripts/kb_review_07.py > docs/quality/kb-review-07.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
TODO (a Module 07 phase not built yet, named; outside the E2E gate until built),
OTHER (owned by another module per the KB's own index - audited there).
Module 07 is in progress (phases 07a schema and roles, 07b M-19 / M-18, 07c member ledger, 07d I-22, 07e C-12 built):
this review is its interim state.
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/07-cpf.md"

E2E = "Playwright 03-masters/cpf-rates"
IT = "InterestRateIT"
SCHEMA = "CpfSchemaIT"
E2E_I22 = "Playwright 06-exceptions-and-adjustments/06-opening-balance-correction"
IT_I22 = "OpeningBalanceIT"
E2E_C12 = "Playwright 05-payroll-processing/04-cpf-interest-run"
E2E_PURPOSES = "Playwright 03-masters/cpf-purposes"
LEDGER = "CpfLedgerIT"
IT_I04 = "CpfSanctionIT, SanctionRulesTest"
E2E_I04 = "Playwright 06-exceptions-and-adjustments/07-cpf-sanction-chain"

# (first, last, status, evidence / owner) - ranges of SC-07-nnnn in file order.
MAP = [
    (1, 1, "TODO", "phase 07i (monthly contribution; rate keys CPF.RATE.EMPLOYEE / EMPLOYER defined on M-18, unseeded)"),
    (2, 2, "PARTIAL", "the SRS-6-2 limits as M-19 rule versions, the ceiling worked out at tier 2 with the advance cap (" + IT_I04 + ", " + E2E_I04 + "); a limit on the MGW basis answers not configured until OQ-3 (D-15)"),
    (3, 3, "TODO", "phase 07i (no CPF on a Departmental Labour NPH; retiring-member recomputation)"),
    (4, 4, "PARTIAL", "07f-2: member request on E-01, tier 1 Unit CPF Administrator, tier 2 CPF Division, tier 3 CPF Sanctioning Authority with OTP through route I04_SUBMIT, sanction number, returns and withdrawal (" + IT_I04 + ", " + E2E_I04 + ", " + SCHEMA + "); final settlement is 07f-3, the auto-invoice and payment release module 23 (D-13)"),
    (5, 5, "PARTIAL", "purposes and checklist on M-19; the 5-year membership and 6 part-final conditions checked on the request date at request and at tier 2 (" + IT_I04 + ", SanctionRulesTest); the 90 % withdrawal reading is Q-07-7"),
    (6, 6, "TODO", "phase 07i (EPS / higher-pension split rounded once on the combined monthly total); rates on M-18, unseeded (OQ-24)"),
    (7, 7, "TODO", "phase 07j (CPF Slip)"),
    (8, 8, "PARTIAL", "interest end date set at three years from separation and cleared on reinstatement, never for a deputed member (InterestStop, LedgerRulesTest, " + LEDGER + "; rule CPF.INTEREST.EXMEMBER_YEARS on M-19; the database refuses a stop for a deputed member, " + SCHEMA + "); applied by the interest run in phase 07e"),
    (9, 9, "OTHER", "module 23 (CPF SSP interface); balances are never deleted (R-DEL)"),
    (10, 10, "PARTIAL", "M-19: FCI-declared rate for active and deputed members, EPFO-declared for inactive, per financial year, maker-checker at HQ, undeclared rates marked (InterestRateRules, " + IT + ", " + E2E + "); the C-12 run takes the rate of each member's state and refuses an undeclared or missing one (InterestRunRules, InterestRunIT, " + E2E_C12 + "); the amount waits for Q-072 (D-4), the CPF Slip is 07j"),
    (11, 11, "TODO", "phase 07f (final payment; retirement-benefit hold with module 11)"),
    (12, 12, "OTHER", "module 24 (opening balances migrated from the legacy ledger and reconciled)"),
    (13, 14, "TODO", "phase 07f (interest to the sanction date, intervening period)"),
    (15, 15, "PARTIAL", "every limit and condition an effective-dated rule parameter (V035_3) read on the request date at tier 2 (" + IT_I04 + "); MGW basis OQ-3"),
    (16, 16, "PARTIAL", "Renovation least of 12 x pay and (employee + VPF) (SanctionRules.ceiling, SanctionRulesTest); the pay comes from the payroll engines, not yet built, so the limit answers not configured (D-15)"),
    (17, 17, "COVERED", "above the ceiling the amount is capped at it, never refused, and the capped amount is shown (" + IT_I04 + ", " + E2E_I04 + ", cpf-sanctions tests); the database keeps sanctioned <= ceiling (" + SCHEMA + ")"),
    (18, 21, "TODO", "phase 07g (I-09 deputation)"),
    (22, 22, "TODO", "phase 07f (deputed member in the same chain)"),
    (23, 23, "PARTIAL", "a correction applied after interest was credited raises CPF_INTEREST_RECOMPUTE on X-05 from the effective date (" + IT_I22 + ", OpeningBalanceService); the interest amount itself is not configured until Q-072 is answered (decision D-4)"),
    (24, 24, "COVERED", "Covid-19 never offered: refused as a purpose code (PurposeRules, PurposeRulesTest, WithdrawalPurposeIT, " + E2E_PURPOSES + ")"),
    (25, 25, "PARTIAL", "eligibility from the ledger balance (employee, employer, VPF) (" + IT_I04 + "); the pay part waits for the engines (D-15)"),
    (26, 26, "COVERED", "no new advance while another is in the chain or sanctioned and unpaid (Q-07-4 reading) (" + IT_I04 + ", " + E2E_I04 + ")"),
    (27, 27, "PARTIAL", "month + year + zone + running sequence, issued at tier 3 under the zone lock (" + IT_I04 + ", " + E2E_I04 + "); the layout values are FCI's (Q-07-1, demo seed only) and the sequence reset is Q-07-6"),
    (28, 28, "COVERED", "the part-final purposes offered; a request without the property details of house, construction, plot or renovation is refused (" + IT_I04 + ", cpf-sanction tests, " + E2E_PURPOSES + ")"),
    (29, 29, "TODO", "phase 07j (CPF Slip view and download)"),
    (30, 30, "OTHER", "module 16 / HRMS (member e-mail received from HRMS)"),
    (31, 31, "OTHER", "the CPF SSP portal itself is a change request (BR-CPF-035); its interface is module 23"),
    (32, 33, "COVERED", "I-22: permitted reasons (M-16 list), evidence through the order-copy control, rows per part, Manager (CPF) -> AGM (CPF) with OTP -> HQ CPF approver with OTP; negative, settled, self-approval, pending and sanction-blocked refusals; SSP sync queued (CorrectionRules, " + IT_I22 + ", " + E2E_I22 + ", " + SCHEMA + ")"),
    (34, 34, "PARTIAL", "C-12: runs and re-runs for all members or one before the freeze, each audited (scope, initiator, time, members, reason), the not-yet-calculated report with the reason per member (InterestRunRules, InterestRunIT, " + E2E_C12 + "); the interest amount waits for Q-072 (D-4)"),
    (35, 35, "COVERED", "C-12 freeze requested by Manager (CPF), decided by the A-08 approving role AGM (CPF) without OTP (route C12_SUBMIT); afterwards no run (CPF-409-INTEREST-FROZEN); never removed (InterestRunIT, " + E2E_C12 + ", " + SCHEMA + ")"),
    (36, 36, "PARTIAL", "M-18: the EPS, higher-pension and contribution rates as rule versions, created by the Rule Administrator and activated by a different Rule Approver at HQ through the M-08 routes, each on its governing circular (" + E2E + ", RuleCatalogueIT); seed values OQ-24; the PT slabs come with module 08"),
    (37, 37, "TODO", "phase 07f (final payment, CPF Final Statement)"),
    (38, 38, "TODO", "phase 07k (I-13); the database keeps a change rejected in HRMS without a new ID and never deleted (" + SCHEMA + ".BR_CPF_020_...)"),
    (39, 39, "PARTIAL", "CPF code from HRMS required before any posting, else an X-05 exception routed upstream (CpfMembers, CpfMembersTest, " + LEDGER + "); the read-only display of the HRMS CPF identity is phase 07j"),
    (40, 40, "OTHER", "notifications (alerts and letters; the events are published by each phase)"),
    (41, 41, "TODO", "phases 07f and 07j (Aadhaar last four, PAN / bank masked on CPF screens and exports)"),
    (42, 42, "PARTIAL", "the checklist per purpose on M-19; submission blocked naming each missing mandatory document, verified again at tier 1 (" + IT_I04 + ", " + E2E_I04 + "); the documents of each purpose are Q-074"),
    (43, 43, "TODO", "phase 07i with module 08 (statutory deductions of a supplementary payment, Q-071)"),
    (44, 44, "TODO", "phase 07k (Category-II to Category-I promotion as a parent-zone change)"),
    (45, 45, "TODO", "phase 07j (Control Register, E-Seva reports)"),
    (46, 47, "TODO", "phase 07k (Member ID applied, IOCPF)"),
    (48, 48, "TODO", "OQ-13 (EPS / CPF / EDLI wages by separation type: rules once answered)"),
    (49, 49, "COVERED", "the withdrawal register on I-04: every request in scope with its stage and number, paged, filtered by stage; counts read from it before sanction (" + IT_I04 + ", " + E2E_I04 + ")"),
    (50, 50, "TODO", "phase 07j (New Joinees report; data source OQ-29)"),
    (51, 59, "TODO", "phase 07g (I-09 use case and fields)"),
    (60, 66, "TODO", "phase 07j (O-07 use case and fields)"),
    (67, 73, "COVERED", "I-22 use case: main flow, refusals, return / rejection, three users, ledger and audit (" + IT_I22 + ", " + E2E_I22 + ")"),
    (74, 74, "PARTIAL", "the member is selected by EIS and shown with the CPF code; search by CPF code / UAN is open (PENDING API-07-9)"),
    (75, 82, "COVERED", "I-22 fields: as-at date, current and corrected parts with the difference, reason, evidence, interest date, remarks, route (" + E2E_I22 + ", cpf-opening-balance tests)"),
    (83, 83, "PARTIAL", "main flow built: request, three tiers, OTP sanction, number (" + IT_I04 + ", " + E2E_I04 + "); payment release, debit, letters and SSP sync are module 23 (D-13)"),
    (84, 84, "COVERED", "above the purpose limit the sanction is capped and the capped amount shown (" + IT_I04 + ", " + E2E_I04 + ")"),
    (85, 85, "TODO", "deputed member in the same chain: the chain treats a deputed member as active; no test yet"),
    (86, 86, "COVERED", "the Unit CPF Administrator returns the request to the member with the reason; the member corrects and resubmits (" + IT_I04 + ", " + E2E_I04 + ")"),
    (87, 87, "COVERED", "the CPF Division returns the request to the unit with the reason, a new pass (" + IT_I04 + ", " + E2E_I04 + ")"),
    (88, 88, "TODO", "phase 07f-3 (final payment: interest to the sanction date, Q-072)"),
    (89, 89, "TODO", "phase 07f-3 (ex-member moved to an active unit; sanctioning office in A-08)"),
    (90, 90, "PARTIAL", "PAYMENT_BLOCKED at tier 3 when the balance cannot carry the amount (SanctionRules.paymentBlocked, SanctionRulesTest, cpf-sanctions tests); the bank-rejection reopening is module 23"),
    (91, 91, "OTHER", "module 23 (ledger debit on the bank acknowledgement, SSP sync)"),
    (92, 92, "TODO", "phase 07j (FCI CPF Sanction / Payment Report)"),
    (93, 93, "COVERED", "purpose from the purposes in force; Covid-19 never offered (" + IT_I04 + ", " + E2E_I04 + ", " + E2E_PURPOSES + ")"),
    (94, 94, "COVERED", "property details required by the purpose, else refused (" + IT_I04 + ", cpf-sanction tests)"),
    (95, 95, "COVERED", "more than zero, two decimals; capped above the limit, not refused (" + IT_I04 + ", " + E2E_I04 + ", cpf-sanction-rules tests)"),
    (96, 96, "PARTIAL", "capped at the limit (" + IT_I04 + ", " + E2E_I04 + "); the Renovation least-of needs the pay (D-15)"),
    (97, 97, "PARTIAL", "documents per purpose from the checklist, submission blocked naming the missing ones; at most 5 files, PDF / JPG / PNG by content (CPF-422-DOCUMENT-COUNT; " + IT_I04 + ", " + E2E_I04 + "); the checklists themselves are Q-074"),
    (98, 98, "PARTIAL", "the sanction number on sanction (" + IT_I04 + ", " + E2E_I04 + "); the sanction-order document and its layout are module 23 / Q-07-1"),
    (99, 99, "COVERED", "UAN and KYC on record (from HRMS) verified at tier 1, else refused naming the missing item; I-04 shows the UAN read-only and the Aadhaar as its last four digits (SRS-6-51; " + IT_I04 + ", " + E2E_I04 + ", EmployeeModelTest)"),
    (100, 100, "COVERED", "the stage of each request on I-04 and E-01 (" + IT_I04 + ", " + E2E_I04 + "); Payment Released is module 23"),
    (101, 101, "PARTIAL", "earlier withdrawals counted from the register before sanction, the member's requests still in the chain included, under a member lock (" + IT_I04 + " BR_CPF_010, SanctionRulesTest); a member with six part-finals is not in the demo data"),
    (102, 103, "OTHER", "module 23 (APPS auto-invoice status and date)"),
    (104, 105, "PARTIAL", "EPS 8.33 % and higher-pension 1.16 % rates read-only from the rule engine (M-18 keys CPF.EPS.RATE, CPF.HIGHERPENSION.RATE; " + E2E + "); not configured until FCI supplies the go-live versions (OQ-24)"),
    (106, 106, "PARTIAL", "Interest rate basis: FCI-declared for active and deputed members, EPFO-declared for inactive ones, shown per rate on M-19 (" + E2E + "); the C-12 run selects it by member state (" + E2E_C12 + "); the amount waits for Q-072"),
    (107, 107, "TODO", "phase 07j (CPF Slip Member ID history)"),
    (108, 108, "COVERED", "one posting path refuses any part going negative, naming the part (LedgerBalances, LedgerRulesTest, " + LEDGER + "; I-22: " + IT_I22 + ", " + E2E_I22 + "); the database refuses it too (" + SCHEMA + ")"),
    (109, 109, "TODO", "phases 07f / 07k (Member ID as received, sanction number unique); the database refuses a TRANSFER supersede and an HRMS ID without its request (" + SCHEMA + ".SRS_6_58_...)"),
]


def main() -> None:
    rows = [line for line in io.open(SCENARIOS, encoding="utf-8") if line.startswith("| SC-07-")]
    out = sys.stdout
    out.write("# KB review — Module 07 (Prompt 7), interim\n\n")
    out.write(
        "Generated by `scripts/kb_review_07.py` from `kb/07-analysis/scenarios/07-cpf.md` "
        f"({len(rows)} scenarios). Module 07 is in progress: phases 07a (schema, roles), 07b (M-19 interest rates, "
        "M-18 / M-19 rule registers), 07c (member ledger), 07d (I-22 opening-balance correction) and 07e (C-12 interest "
        "run and freeze) are built; 07f-07k are not. "
        "COVERED: built and tested. PARTIAL: built; the named part is open or belongs to another module. "
        "TODO: a Module 07 phase not built yet (outside the E2E gate until built). OTHER: owned by another module.\n\n"
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
        + f" (total {len(rows)}). No blocking gap for phases 07a-07f-2; findings below.\n\n"
    )
    out.write("## Findings of this review\n\n")
    out.write("| ID | Finding | Action |\n|---|---|---|\n")
    out.write("| Q-072 | No source states how CPF interest is computed | the interest run (07e) records members as not calculated; owner's decision D-4 of 2026-10-23 |\n")
    out.write("| OQ-24 | EPS / higher-pension seed values and the EPF Scheme 2026 text not supplied | M-18 keys defined without a version: not configured |\n")
    out.write("| D-8 | The CPF approver roles are provisional | five module roles created (V034_2); FCI to confirm the catalogue |\n")
    out.write("| SC-07-0074 | I-22 selects the member by EIS, not by CPF code / UAN as its field table says | the CPF code is shown read-only; the search is open as PENDING API-07-9 |\n")
    out.write("| Q-07-2 / Q-07-3 | I-22 blocked correction outcome; C-12 freeze with members not calculated | built conservatively, listed in PROGRESS |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
