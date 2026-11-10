package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleEngine.Context;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The rule engine against the database (Module 06; SRS-19-3, SRS-19-5, SRS-19-13, SRS-19-15; KB
 * RE-7, RE-9, RE-10): point-in-time resolution, "not configured" instead of a default, payroll-type
 * and office scope, formulas through the grammar, slab bands and the pinned version set of an
 * Actual Run.
 */
class RuleEngineIT extends ApiTestSupport {

    @Autowired private RuleEngine engine;
    @Autowired private OrgDirectory org;

    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate SEP = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    private static String key(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private long rule(
            String key, String type, String domain, String types, Long office, boolean active) {
        jdbc.update(
                "INSERT INTO rule_definition (rule_key, rule_type_code, rule_domain, rule_name,"
                    + " payroll_type_scope, org_scope_location_id, owning_module, order_required,"
                    + " active_flag) VALUES (?, ?, ?, ?, ?, ?, 'RULES', 'N', ?)",
                key,
                type,
                domain,
                "IT " + key,
                types,
                office,
                active ? "Y" : "N");
        rulesChanged();
        return jdbc.queryForObject(
                "SELECT rule_id FROM rule_definition WHERE rule_key = ?", Long.class, key);
    }

    private long version(
            long ruleId,
            int no,
            LocalDate from,
            LocalDate to,
            String scalar,
            String formula,
            String state) {
        jdbc.update(
                "INSERT INTO rule_version (rule_id, version_no, effective_from, effective_to,"
                    + " scalar_value, formula_expression, approval_state, is_blocked, created_by)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 'N', 'it')",
                ruleId,
                no,
                from,
                to,
                scalar == null ? null : new BigDecimal(scalar),
                formula,
                state);
        rulesChanged();
        return jdbc.queryForObject(
                "SELECT rule_version_id FROM rule_version WHERE rule_id = ? AND version_no = ?",
                Long.class,
                ruleId,
                no);
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    @Test
    void SRS_19_3_theVersionInForceOnTheDayIsUsedAndAClosedPeriodKeepsItsOwn() {
        String k = key("IT.DA.RATE");
        long id = rule(k, "RATE", "COMPUTATION", null, null, true);
        version(id, 1, APR, SEP, "50", null, "APPROVED");
        version(id, 2, OCT, null, "53", null, "APPROVED");
        version(id, 3, LocalDate.of(2027, 1, 1), null, "55", null, "PENDING_APPROVAL");
        assertThat(
                        engine.resolve(k, LocalDate.of(2026, 6, 15), Context.unscoped())
                                .orElseThrow()
                                .scalar())
                .isEqualByComparingTo("50");
        // SRS-19-13: a recomputation of a closed period names its day and gets the version then in
        // force.
        assertThat(engine.resolve(k, SEP, Context.unscoped()).orElseThrow().versionNo())
                .isEqualTo(1);
        assertThat(engine.resolve(k, OCT, Context.unscoped()).orElseThrow().scalar())
                .isEqualByComparingTo("53");
        // A version not yet approved is never used.
        assertThat(
                        engine.resolve(k, LocalDate.of(2027, 2, 1), Context.unscoped())
                                .orElseThrow()
                                .versionNo())
                .isEqualTo(2);
    }

    @Test
    void R_RULE_aRuleWithoutAnApprovedVersionInForceIsNotConfiguredNeverDefaulted() {
        String future = key("IT.FUTURE");
        version(
                rule(future, "RATE", "COMPUTATION", null, null, true),
                1,
                LocalDate.of(2030, 1, 1),
                null,
                "1",
                null,
                "APPROVED");
        String ended = key("IT.ENDED");
        version(
                rule(ended, "RATE", "COMPUTATION", null, null, true),
                1,
                APR,
                SEP,
                "1",
                null,
                "APPROVED");
        String inactive = key("IT.INACTIVE");
        version(
                rule(inactive, "RATE", "COMPUTATION", null, null, false),
                1,
                APR,
                null,
                "1",
                null,
                "APPROVED");
        String security = key("IT.SECURITY");
        version(
                rule(security, "RATE", "SECURITY", null, null, true),
                1,
                APR,
                null,
                "1",
                null,
                "APPROVED");
        String draft = key("IT.DRAFT");
        version(
                rule(draft, "RATE", "COMPUTATION", null, null, true),
                1,
                APR,
                null,
                "1",
                null,
                "DRAFT");
        for (String k : List.of(future, ended, inactive, security, draft, "IT.NO.SUCH.RULE")) {
            assertThatThrownBy(() -> engine.resolve(k, OCT, Context.unscoped()))
                    .as(k)
                    .satisfies(
                            e -> {
                                assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_NOT_CONFIGURED);
                                assertThat(((FciException) e).getMessageArgs())
                                        .containsExactly(k, OCT);
                            });
        }
    }

    @Test
    void RE_10_aScopedRuleAppliesOnlyToItsPayrollTypesAndToItsOfficeAndTheOfficesBelow() {
        String labour = key("IT.LABOUR.NORM");
        version(
                rule(labour, "RATE", "COMPUTATION", "DEPT_LABOUR, DPS_LABOUR", null, true),
                1,
                APR,
                null,
                "7",
                null,
                "APPROVED");
        assertThat(engine.resolve(labour, OCT, new Context("DPS_LABOUR", null))).isPresent();
        assertThat(engine.resolve(labour, OCT, new Context("STAFF", null))).isEmpty();
        assertThat(engine.resolve(labour, OCT, Context.unscoped())).isEmpty();

        long region = org.byCode("RO-DL").orElseThrow().getId();
        long inside = org.byCode("DH001").orElseThrow().getId();
        long outside = org.byCode("MR001").orElseThrow().getId();
        assertThat(org.subtree(region)).contains(inside).doesNotContain(outside);
        String site = key("IT.LWF.SITE");
        version(
                rule(site, "RATE", "COMPUTATION", null, region, true),
                1,
                APR,
                null,
                "20",
                null,
                "APPROVED");
        assertThat(engine.resolve(site, OCT, new Context("STAFF", region))).isPresent();
        assertThat(engine.resolve(site, OCT, new Context("STAFF", inside))).isPresent();
        assertThat(engine.resolve(site, OCT, new Context("STAFF", outside))).isEmpty();
        assertThat(engine.resolve(site, OCT, Context.unscoped())).isEmpty();
    }

    @Test
    void SRS_19_15_aFormulaRuleIsEvaluatedThroughTheGrammarItsVariablesRegistered() {
        String basic = "IT_BASIC_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update(
                "INSERT INTO rule_expression_grammar (token_type, token_value, return_type,"
                        + " binds_to_entity, is_permitted) VALUES ('VARIABLE', ?, 'NUMBER',"
                        + " 'pay_component:IT', 'Y')",
                basic);
        rulesChanged();
        String k = key("IT.HRA.FORMULA");
        version(
                rule(k, "FORMULA", "COMPUTATION", null, null, true),
                1,
                APR,
                null,
                null,
                "ROUND(" + basic + " * 0.24, 2)",
                "APPROVED");
        assertThat(
                        engine.evaluate(
                                        k,
                                        OCT,
                                        Context.unscoped(),
                                        Map.of(basic, new BigDecimal("40000.55")))
                                .orElseThrow())
                .isEqualByComparingTo("9600.13");
        assertThat(engine.checkFormula(basic + " + 1", false)).containsExactly(basic);

        String unregistered = key("IT.BAD.FORMULA");
        version(
                rule(unregistered, "FORMULA", "COMPUTATION", null, null, true),
                1,
                APR,
                null,
                null,
                "NOT_A_VARIABLE * 2",
                "APPROVED");
        assertThatThrownBy(() -> engine.evaluate(unregistered, OCT, Context.unscoped(), Map.of()))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        String noFormula = key("IT.NO.FORMULA");
        version(
                rule(noFormula, "FORMULA", "COMPUTATION", null, null, true),
                1,
                APR,
                null,
                "1",
                null,
                "APPROVED");
        assertThatThrownBy(() -> engine.evaluate(noFormula, OCT, Context.unscoped(), Map.of()))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        assertThatThrownBy(() -> engine.checkFormula("SQRT(1)", false))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
    }

    @Test
    void SRS_19_5_aSlabRuleAnswersTheBandOfAnAmountAndAGapIsRefusedAtSubmit() {
        String k = key("IT.PT.SLAB");
        long v =
                version(
                        rule(k, "SLAB", "COMPUTATION", null, null, true),
                        1,
                        APR,
                        null,
                        null,
                        null,
                        "APPROVED");
        jdbc.update(
                "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                        + " rate_or_amount) VALUES (?, 1, 0, 10000, 0)",
                v);
        rulesChanged();
        jdbc.update(
                "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                        + " rate_or_amount) VALUES (?, 2, 10000, 15000, 150)",
                v);
        rulesChanged();
        jdbc.update(
                "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                        + " rate_or_amount) VALUES (?, 3, 15000, NULL, 200)",
                v);
        rulesChanged();
        assertThat(engine.resolve(k, OCT, Context.unscoped()).orElseThrow().bands()).hasSize(3);
        assertThat(
                        engine.band(k, OCT, Context.unscoped(), null, new BigDecimal("9999.99"))
                                .orElseThrow()
                                .rateOrAmount())
                .isEqualByComparingTo("0");
        assertThat(
                        engine.band(k, OCT, Context.unscoped(), null, new BigDecimal("15000"))
                                .orElseThrow()
                                .order())
                .isEqualTo(3);
        assertThat(engine.band(k, OCT, Context.unscoped(), null, new BigDecimal("-1"))).isEmpty();
    }

    @Test
    void BR_CYC_011_thePinnedVersionSetHashIsStableAndChangesWithANewVersion() {
        RuleEngine.VersionSet before = engine.pin(OCT);
        assertThat(engine.pin(OCT).hash()).isEqualTo(before.hash());
        String k = key("IT.PIN");
        long id = rule(k, "RATE", "COMPUTATION", null, null, true);
        version(id, 1, APR, null, "1", null, "APPROVED");
        RuleEngine.VersionSet after = engine.pin(OCT);
        assertThat(after.hash()).isNotEqualTo(before.hash()).hasSize(64);
        assertThat(after.versions()).anyMatch(p -> p.ruleKey().equals(k) && p.versionNo() == 1);
        // A SECURITY setting (A-07) is not part of a run's rule set; a version not yet in force
        // neither.
        String security = key("IT.PIN.SECURITY");
        version(
                rule(security, "RATE", "SECURITY", null, null, true),
                1,
                APR,
                null,
                "1",
                null,
                "APPROVED");
        String future = key("IT.PIN.FUTURE");
        version(
                rule(future, "RATE", "COMPUTATION", null, null, true),
                1,
                LocalDate.of(2030, 1, 1),
                null,
                "1",
                null,
                "APPROVED");
        assertThat(engine.pin(OCT).hash()).isEqualTo(after.hash());
        // The pinned set of a past day is unchanged by later versions (re-runnable, BR-CYC-011).
        assertThat(engine.pin(LocalDate.of(2026, 3, 31)).versions())
                .noneMatch(p -> p.ruleKey().equals(k));
    }

    @Test
    void SRS_19_15_anEligibilityConditionABlankScopeAndOutOfScopeBandsAreHandled() {
        String var = "IT_EL_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update(
                "INSERT INTO rule_expression_grammar (token_type, token_value, return_type,"
                        + " binds_to_entity, is_permitted) VALUES ('VARIABLE', ?, 'NUMBER',"
                        + " 'pay_component:IT', 'Y')",
                var);
        rulesChanged();
        String eligibility = key("IT.ELIGIBLE");
        version(
                rule(eligibility, "ELIGIBILITY", "COMPUTATION", " ", null, true),
                1,
                APR,
                null,
                null,
                var + " >= 18000",
                "APPROVED");
        // A blank payroll-type scope means every engine.
        assertThat(
                        engine.condition(
                                eligibility,
                                OCT,
                                new Context("STAFF", null),
                                Map.of(var, new BigDecimal("18000"))))
                .contains(true);
        assertThat(
                        engine.condition(
                                eligibility,
                                OCT,
                                Context.unscoped(),
                                Map.of(var, new BigDecimal("17999.99"))))
                .contains(false);
        // A slab rule scoped to labour answers no band for a staff member, whatever the amount.
        String labourSlab = key("IT.LABOUR.SLAB");
        long v =
                version(
                        rule(labourSlab, "SLAB", "COMPUTATION", "DPS_LABOUR", null, true),
                        1,
                        APR,
                        null,
                        null,
                        null,
                        "APPROVED");
        jdbc.update(
                "INSERT INTO rule_slab_band (rule_version_id, band_order, range_from, range_to,"
                        + " rate_or_amount) VALUES (?, 1, 0, NULL, 5)",
                v);
        rulesChanged();
        assertThat(engine.band(labourSlab, OCT, new Context("STAFF", null), null, BigDecimal.ONE))
                .isEmpty();
        assertThat(
                        engine.band(
                                labourSlab,
                                OCT,
                                new Context("DPS_LABOUR", null),
                                null,
                                BigDecimal.ONE))
                .isPresent();
        assertThat(engine.condition(labourSlab, OCT, new Context("STAFF", null), Map.of()))
                .isEmpty();
    }

    @Test
    void RE_16_aBlockedStatutoryOutputDefinitionIsResolvedWithItsBlock() {
        String k = key("IT.FORM24Q");
        long v =
                version(
                        rule(k, "RATE", "STATUTORY_OUTPUT", null, null, true),
                        1,
                        APR,
                        null,
                        "1",
                        null,
                        "APPROVED");
        jdbc.update(
                "UPDATE rule_version SET is_blocked = 'Y', blocked_reason = 'layout not loaded'"
                        + " WHERE rule_version_id = ?",
                v);
        rulesChanged();
        assertThat(engine.resolve(k, OCT, Context.unscoped()).orElseThrow().blocked()).isTrue();
    }

    @Test
    void BR_CYC_011_aRunKeepsItsSnapshotWhileANewVersionIsApprovedAndANewSnapshotSeesIt() {
        String k = key("IT.SNAP.RATE");
        long id = rule(k, "RATE", "COMPUTATION", null, null, true);
        long v1 = version(id, 1, APR, null, "10", null, "APPROVED");
        RuleSet run = engine.snapshot(OCT);
        assertThat(run.resolve(k, Context.unscoped()).orElseThrow().versionNo()).isEqualTo(1);
        assertThat(run.versionSet()).isEqualTo(engine.pin(OCT));
        // A successor approved during the run: version 1 ends, version 2 starts.
        jdbc.update("UPDATE rule_version SET effective_to = ? WHERE rule_version_id = ?", SEP, v1);
        rulesChanged();
        version(id, 2, OCT, null, "11", null, "APPROVED");
        // The run's set does not change: same version, same hash, no database read.
        assertThat(run.resolve(k, Context.unscoped()).orElseThrow().scalar())
                .isEqualByComparingTo("10");
        assertThat(engine.pin(OCT).hash()).isNotEqualTo(run.hash());
        // A new run sees the new version; a closed period still resolves to the old one.
        assertThat(engine.snapshot(OCT).resolve(k, Context.unscoped()).orElseThrow().scalar())
                .isEqualByComparingTo("11");
        assertThat(engine.snapshot(SEP).resolve(k, Context.unscoped()).orElseThrow().versionNo())
                .isEqualTo(1);
        // A formula of the run is evaluated with the grammar taken with the set.
        String formula = key("IT.SNAP.FORMULA");
        version(
                rule(formula, "FORMULA", "COMPUTATION", null, null, true),
                1,
                APR,
                null,
                null,
                "2 * 3",
                "APPROVED");
        assertThat(engine.snapshot(OCT).evaluate(formula, Context.unscoped(), Map.of()))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("6"));
        // A rule outside the COMPUTATION domain is not in a run's set.
        String output = key("IT.SNAP.OUTPUT");
        version(
                rule(output, "RATE", "STATUTORY_OUTPUT", null, null, true),
                1,
                APR,
                null,
                "1",
                null,
                "APPROVED");
        assertThatThrownBy(() -> engine.snapshot(OCT).resolve(output, Context.unscoped()))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_NOT_CONFIGURED));
    }
}
