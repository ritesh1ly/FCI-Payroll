"""KB review of Module 04 (Prompt 7): every scenario of kb/07-analysis/scenarios/04-masters-mdm.md with
its status and evidence. Regenerate: python scripts/kb_review_04.py > docs/quality/kb-review-04.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
OTHER (owned by another module per the KB's own index - audited there).
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/04-masters-mdm.md"

E2E = "Playwright mdm-masters"
IT = "MdmMastersIT"
M11 = "M-11 as built before this module (EmployeeEventIT.M_11_..., EventRulesTest.M_11_..., EmployeeModelTest.M_11_...)"

# (first, last, status, evidence / owner) - ranges of SC-04-nnnn in file order.
MAP = [
    (1, 1, "OTHER", "module 16 (M-01 Payroll-owned fields; HRMS fields read-only by R-SRC, built with M-01)"),
    (2, 2, "PARTIAL", "Staff rate tables and city classes on M-04 / M-22 / M-23 (MastersIT); pay scales M-03 (module 17); gang / shed structure, DPS / NWNP rates and norm sets (modules 20-22)"),
    (3, 3, "OTHER", "module 16 / 24 (PAN anchored on the HRMS employee record; duplicate check at migration)"),
    (4, 9, "PARTIAL", M11 + ": the approved transfer moves the employee and reissues the staff number; the origin-complete check and the block on computation at the origin need the payroll cycle (module 05) and I-21 (module 16)"),
    (10, 10, "OTHER", "module 24 (migration duplicate check)"),
    (11, 11, "PARTIAL", "pay_component.agency_id exists; the pay-code-to-agency mapping screen and the remittance refusal (BR-MIS-006) arrive with agency_master and remittances (modules 15 / 23); open item Q-04-6"),
    (12, 12, "COVERED", "M-14 floors by payroll type and exclusion codes, HQ maker and Payroll Checker (" + IT + ".SRS_16_13_..., MdmRulesTest, " + E2E + ")"),
    (13, 13, "PARTIAL", "M-15 norms maintained and approved (" + IT + ".SRS_16_14_..., MdmRulesTest, " + E2E + "); claim adjudication on I-03 (lesser of claimed and ceiling) is module 10"),
    (14, 14, "COVERED", "M-16 as the single source of lookup values; M-14 / M-15 / M-25 / M-26 read their lists from it (ReferenceListService, reference-lists.spec, " + IT + ")"),
    (15, 15, "OTHER", "module 06 / 24 (component catalogue seeded from the legacy extract)"),
    (16, 16, "COVERED", "Maker, approver and maintenance level of every Module 04 master are routes and grants (V030_2), never code (" + IT + ": wrong role, own approval, HQ-only, RO-level local names)"),
    (17, 17, "PARTIAL", M11 + "; the HRMS-only origin is I-21 (module 16)"),
    (18, 18, "OTHER", "modules 11 / 23 (APPS pre-payments, credit memos, IOGA)"),
    (19, 20, "OTHER", "module 23 (APPS location feed, X-03); org_location attributes are read-only here"),
    (21, 21, "COVERED", "Masters are end-dated, never deleted; versions never overlap (VersionChainTest, " + IT + ": floor, ceiling, operation end-date, superseded circular, withdrawn label)"),
    (22, 22, "COVERED", "M-25 Circular / Order Master: one PDF per circular, unique number, supersede in the approval transaction, withdraw, where used, search (" + IT + ".SRS_G_38_..., MdmRulesTest, " + E2E + ")"),
    (23, 23, "PARTIAL", "M-26 labels by key, language and scope, SRS-G-39 resolution order, local names approved at the RO and withdrawn by HQ, bulk download / upload, missing-Hindi report (" + IT + ".SRS_G_39_..., MdmRulesTest, MdmControllerTest, " + E2E + "); the screens' own captions still come from the message bundles, not label_master (Q-04-7)"),
    (24, 24, "COVERED", "M-27 operations: payroll types, bag-based needs a norm slab, Active circular linked on approval, fixed standard name, end-dated (" + IT + ".SRS_G_41_..., MdmRulesTest, " + E2E + "); work-slips pick from it in module 20"),
    (25, 42, "PARTIAL", M11 + "; staff-number history report, LPC reference, loans moved via IOGA and the bulk order arrive with modules 16 / 11 / 23"),
    (43, 43, "PARTIAL", "M-14 main flow maintained and approved (" + IT + ", " + E2E + "); the engines applying the floor and quarantining to C-08 are modules 05 / 16"),
    (44, 51, "COVERED", "Overlap refused, exclusion code must be an active M-07 entry, effective dated with prior versions, payroll type of the list, floor zero or more, reason required (" + IT + ".SRS_16_13_..., MdmRulesTest, " + E2E + ")"),
    (52, 52, "PARTIAL", "Excluded From is resolved by the rule engine (module 06); shown here as the supplementary run"),
    (53, 53, "PARTIAL", "M-15 main flow maintained and approved (" + IT + ", " + E2E + "); adjudication on I-03 is module 10"),
    (54, 54, "COVERED", "Overlapping ceiling version refused (VersionChainTest, " + IT + ")"),
    (55, 55, "OTHER", "module 10 (I-03 claim capped at the ceiling, Checker sees the cap)"),
    (56, 57, "COVERED", "Ceiling norms kept with prior versions; claim type from M-16 CLAIM_TYPE (" + IT + ".SRS_16_14_...)"),
    (58, 58, "PARTIAL", "Ceiling basis is rule-resolved (module 06); until then optional from CEILING_BASIS (Q-04-4)"),
    (59, 62, "COVERED", "Ceiling above zero, periodicity of the DDL list, eligibility category from M-16, no overlap per claim type (" + IT + ", MdmRulesTest, " + E2E + ")"),
    (63, 68, "COVERED", "M-16 as built (ReferenceListService, ReferenceAndParameterIT, reference-lists.spec)"),
]


def main() -> None:
    rows = [line for line in io.open(SCENARIOS, encoding="utf-8") if line.startswith("| SC-04-")]
    out = sys.stdout
    out.write("# KB review — Module 04 (Prompt 7)\n\n")
    out.write(
        "Generated by `scripts/kb_review_04.py` from `kb/07-analysis/scenarios/04-masters-mdm.md` "
        f"({len(rows)} scenarios). COVERED: built and tested in Module 04 (or the master built before it). "
        "PARTIAL: built; the named part is open or belongs to another module. OTHER: owned by another module, "
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
        + f" (total {len(rows)}). No blocking gap for the Module 04 masters; open items below.\n\n"
    )
    out.write("## Open items raised by this review\n\n")
    out.write("| ID | Item | Proposal |\n|---|---|---|\n")
    out.write("| Q-04-1 | SRS \"Administrator\" of M-14 / M-15 / M-27 built as RULE_ADMINISTRATOR, checker PAYROLL_CHECKER; M-25 maker PAYROLL_MAKER only (SRS also names the Administrator) | FCI to confirm; routes and grants are configuration (A-02, A-08) |\n")
    out.write("| Q-04-2 | net_pay_floor_rule.payroll_type allows NULL = all; SRS makes the type mandatory | built mandatory (SRS); the four DDL codes of circular_order_scope |\n")
    out.write("| Q-04-3 | SRS-G-41 bag-weight band \"filled from the master\" has no operation_master column | band and slab tables with the rule engine (modules 06 / 20) |\n")
    out.write("| Q-04-4 | M-15 ceiling basis is rule-resolved (SRS) | optional from CEILING_BASIS until the rule engine (module 06) |\n")
    out.write("| Q-04-5 | \"New\" circulars highlighted on the home page: no rule for \"new\" in the SRS | W-02 home panel (module 02) to decide; the list of current circulars is served |\n")
    out.write("| Q-04-6 | Pay-code-to-agency mapping (SRS-16-12) not built | with agency_master and remittances (modules 15 / 23) |\n")
    out.write("| Q-04-7 | Screens and outputs take their captions from the message bundles, not label_master (SRS-G-40) | load the bundles from label_master at start-up, or keep the bundles as the Global source; FCI to decide |\n")
    out.write("| DB-04-1 | uk_label_master (key, language, scope, location, effective_from) of DDL v4.0 is missing from V015 | the service enforces it (MST-409-LABEL); adding the index alters an existing table: owner to approve |\n")
    out.write("| CF-20 | Circular number unique per issuing authority (SRS) vs globally (DDL) | DDL constraint built (stricter) |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
