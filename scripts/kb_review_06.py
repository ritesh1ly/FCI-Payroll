"""KB review of Module 06 (Prompt 7): every scenario of kb/07-analysis/scenarios/06-rule-engine.md with its
status and evidence. Regenerate: python scripts/kb_review_06.py > docs/quality/kb-review-06.md

Status: COVERED (built and tested here), PARTIAL (built; the named part is open or elsewhere),
TODO (a Module 06 phase not built yet, named; outside the E2E gate until built),
OTHER (owned by another module per the KB's own index - audited there).
Module 06 is in progress (phases 06a schema, 06b engine core, 06c M-08 built): this review is its interim state.
"""

import io
import re
import sys

SCENARIOS = "docs/requirements/kb/07-analysis/scenarios/06-rule-engine.md"

E2E = "Playwright 03-masters/rule-catalogue"
IT = "RuleCatalogueIT"
ENGINE = "RuleEngineIT, RuleSetTest"
RULES = "RuleCatalogueRulesTest, RuleEngineRulesTest"

# (first, last, status, evidence / owner) - ranges of SC-06-nnnn in file order.
MAP = [
    (1, 1, "COVERED", "Seven rule types, each with its value shape; rounding a rule setting; statutory-output and report definitions listed read-only (RuleValueShape, " + RULES + ", " + IT + ".SRS_19_1_..., " + E2E + ")"),
    (2, 2, "TODO", "phase 06d (component_usage / field_source_registry filled from M-07) and module 15 (output definitions bound to them)"),
    (3, 3, "COVERED", "Effective-dated versions coexist; the version in force on the computation day is used (" + ENGINE + " SRS_19_3_..., snapshot BR_CYC_011_...)"),
    (4, 4, "PARTIAL", "Rule Administrator proposes, a different Rule Approver approves; the proposer holding both roles is refused (" + IT + ".SRS_19_4_..., " + E2E + "); the simulation before activation is phase 06e"),
    (5, 5, "COVERED", "Slab bands contiguous, not overlapping, per qualifier (SlabBands, " + RULES + ", " + IT + ".SRS_19_5_..., " + E2E + ")"),
    (6, 6, "COVERED", "An end needs an explicit date; an end without successor is approved and recorded as RULE_END_NO_SUCCESSOR (" + IT + ".SRS_19_6_..., " + E2E + ")"),
    (7, 7, "PARTIAL", "Site-scoped rules without hard-coded logic (RuleScope.covers, " + IT + ".RE_10_..., " + RULES + "); configuring LWF 348 at Visakhapatnam and Chennai is go-live seed data (module 24); pay code 340 not offered on I-19 is phase 05g"),
    (8, 8, "PARTIAL", "Post-go-live changes are effective-dated versions through Rule Administrator -> Rule Approver (" + IT + ", " + E2E + "); simulation before activation is phase 06e"),
    (9, 9, "COVERED", "Status derived from approval state and dates, never stored (RuleVersionStatus, v_rule_version_status, " + RULES + ", " + E2E + ")"),
    (10, 10, "COVERED", "Effective-from and an Active circular mandatory; a conflicting date refused naming the version (RuleProposals, RuleProposalChecks, " + IT + ".SRS_19_12_..., " + E2E + ")"),
    (11, 11, "COVERED", "Every resolution names its day; a closed period keeps the version effective then (" + ENGINE + ", " + IT + ".SRS_19_12_aSupersedingVersion...)"),
    (12, 12, "COVERED", "Payroll Checker and Finance read only; Payroll Maker and Establishment / HR no access (" + IT + ".SRS_19_14_..., RuleCatalogueServiceTest, " + E2E + ")"),
    (13, 13, "PARTIAL", "Bounded formula layer: permitted operators, functions, registered variables, no program code; a grammar row the engine cannot honour refused at submission (RuleFormula, " + RULES + "); covering every formula of the current system needs its formulas (Q-06-1, module 24)"),
    (14, 14, "TODO", "phase 06f: event rules (DA reaching 50 %); the RuleVersionChanged hook is built"),
    (15, 15, "COVERED", "Every version rests on an Active circular, linked on approval and listed under the circular's where used (" + IT + ".SRS_19_4_..., " + E2E + ")"),
    (16, 16, "OTHER", "module 24 (seeding every go-live rule from the current system, RE-12)"),
    (17, 17, "COVERED", "By design: no ex-gratia rule type and no seed; ex-gratia is paid at its sanction-letter amount (SRS-10-25, module 10)"),
    (18, 18, "PARTIAL", "Labour pay rules are dated rules of the engine (scope DEPT_LABOUR / DPS_LABOUR / NWNP_LABOUR, " + ENGINE + "); the labour rules themselves are configured and consumed in modules 20-22"),
    (19, 19, "PARTIAL", "Umbrella view: pending approvals across M-08, M-14, M-15 (" + IT + ".RE_14_..., " + E2E + "); the Rule Catalogue Summary and Pending Approval - Cross-Domain reports on demand are open (F-06-1)"),
    (20, 43, "TODO", "phase 06d: M-07 Component Catalogue (main flow, alternatives, fields)"),
    (44, 44, "PARTIAL", "Define with date and circular, submit, approve (" + IT + ", " + E2E + "); the impact preview before activation is phase 06e"),
    (45, 49, "COVERED", "Overlap refused; slab gap refused; proposer cannot approve; an end needs an explicit date; in force from the stated date with prior versions kept (" + IT + ", " + E2E + ")"),
    (50, 55, "COVERED", "M-08 fields: rule type, effective from (no overlap), effective to (not before from), values Numeric (12,2) and slab bands, linked Active circular, derived status (RuleCatalogue.tsx, rule-catalogue.test.tsx, " + IT + ", " + E2E + ")"),
]


def main() -> None:
    rows = [line for line in io.open(SCENARIOS, encoding="utf-8") if line.startswith("| SC-06-")]
    out = sys.stdout
    out.write("# KB review — Module 06 (Prompt 7), interim\n\n")
    out.write(
        "Generated by `scripts/kb_review_06.py` from `kb/07-analysis/scenarios/06-rule-engine.md` "
        f"({len(rows)} scenarios). Module 06 is in progress: phases 06a (schema), 06b (engine core) and 06c (M-08) "
        "are built; 06d (M-07), 06e (simulation) and 06f (event and site rules) are not. "
        "COVERED: built and tested. PARTIAL: built; the named part is open or belongs to another module. "
        "TODO: a Module 06 phase not built yet (outside the E2E gate until built). OTHER: owned by another module.\n\n"
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
        + f" (total {len(rows)}). No blocking gap for phases 06a-06c; findings below.\n\n"
    )
    out.write("## Findings of this review\n\n")
    out.write("| ID | Finding | Action |\n|---|---|---|\n")
    out.write("| DB-06-1 | `uk_rule_version_eff` covers REJECTED rows, so a stored rejected proposal would hold its date for ever | M-08 writes a version only on approval (plan 06 D-7); raised with the DDL owners (PENDING) |\n")
    out.write("| F-06-1 | SRS-19-21 names two on-demand reports (Rule Catalogue Summary, Pending Approval - Cross-Domain) that M-08 does not export yet | build as M-08 exports (ExportStreaming) with 06e |\n")
    out.write("| F-06-2 | M-25 where used names a rule version by its id, not its rule key | show the rule key and version number in where used (06d, with the M-07 where used) |\n")
    out.write("| G16-LOG | SRS-G-16 asks to log every blocked order-gate submission; no module logs one yet | cross-cutting platform item (PENDING) |\n\n")
    out.write("## Scenarios\n\n| Scenario | Type | Topic | Status | Evidence / owner |\n|---|---|---|---|---|\n")
    out.write("\n".join(table) + "\n")


if __name__ == "__main__":
    main()
