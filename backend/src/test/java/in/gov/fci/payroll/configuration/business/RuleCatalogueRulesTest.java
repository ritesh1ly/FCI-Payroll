package in.gov.fci.payroll.configuration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.business.RuleFormula.Grammar;
import in.gov.fci.payroll.configuration.business.RuleProposals.Dated;
import in.gov.fci.payroll.configuration.business.RuleProposals.Value;
import in.gov.fci.payroll.configuration.business.SlabBands.Band;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The M-08 submission rules (Module 06): the value shape of each rule type (SRS-19-1, SRS-19-5),
 * Numeric (12,2), the date checks (SRS-19-12, SRS-19-6; KB RE-1, RE-2, RE-4), the rule scope (KB
 * RE-10, SRS-19-8), the kind of result a formula yields (SRS-19-15) and the audit image of a rule
 * version (SRS-20-2), each with the spec's negative cases and its boundaries.
 */
class RuleCatalogueRulesTest {

    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 1);
    private static final LocalDate JUN = LocalDate.of(2026, 6, 1);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static Object arg(Throwable t, int i) {
        return ((FciException) t).getMessageArgs()[i];
    }

    private static Band band(int order, String from, String to, String rate) {
        return new Band(order, n(from), to == null ? null : n(to), n(rate), null, null);
    }

    // ---------------------------------------------------------------- SRS-19-1 value shape

    @Test
    void SRS_19_1_eachOfTheSevenRuleTypesHasOneValueShapeAndNoOtherTypeHasOne() {
        assertThat(RuleValueShape.of("RATE")).contains(RuleValueShape.SCALAR);
        assertThat(RuleValueShape.of("CEILING")).contains(RuleValueShape.SCALAR);
        assertThat(RuleValueShape.of("FLOOR")).contains(RuleValueShape.SCALAR);
        assertThat(RuleValueShape.of("SLAB")).contains(RuleValueShape.SLAB);
        assertThat(RuleValueShape.of("FORMULA")).contains(RuleValueShape.NUMBER_FORMULA);
        assertThat(RuleValueShape.of("ELIGIBILITY")).contains(RuleValueShape.CONDITION_FORMULA);
        assertThat(RuleValueShape.of("APPLICABILITY")).contains(RuleValueShape.CONDITION_FORMULA);
        // Rounding is a rule setting; definitions belong to the report engine; settings to A-07.
        for (String other : List.of("ROUNDING", "STATUTORY_OUTPUT", "REPORT", "SETTING", "rate")) {
            assertThat(RuleValueShape.of(other)).as(other).isEmpty();
        }
        assertThat(RuleValueShape.of(null)).isEmpty();
        assertThat(RuleValueShape.SCALAR.isFormula()).isFalse();
        assertThat(RuleValueShape.SLAB.isFormula()).isFalse();
        assertThat(RuleValueShape.NUMBER_FORMULA.isFormula()).isTrue();
        assertThat(RuleValueShape.CONDITION_FORMULA.isFormula()).isTrue();
    }

    @Test
    void SRS_19_1_aRefusalNamesThePartTheShapeTakes() {
        // "this rule takes one value and nothing else": the words the M-08 refusal shows.
        assertThat(RuleValueShape.SCALAR.part().described()).isEqualTo("one value");
        assertThat(RuleValueShape.SLAB.part().described()).isEqualTo("its bands");
        assertThat(RuleValueShape.NUMBER_FORMULA.part().described()).isEqualTo("a formula");
        assertThat(RuleValueShape.CONDITION_FORMULA.part().described()).isEqualTo("a formula");
    }

    @Test
    void SRS_19_1_aRuleTakesExactlyTheValueItsShapeNeedsAndNothingElse() {
        List<Band> bands = List.of(band(1, "0", null, "5"));
        Value scalar = new Value(n("12.5"), null, null);
        Value formula = new Value(null, "BASIC * 2", null);
        Value slab = new Value(null, null, bands);
        assertThatCode(() -> RuleProposals.requireValue(RuleValueShape.SCALAR, scalar))
                .doesNotThrowAnyException();
        assertThatCode(() -> RuleProposals.requireValue(RuleValueShape.SLAB, slab))
                .doesNotThrowAnyException();
        assertThatCode(() -> RuleProposals.requireValue(RuleValueShape.NUMBER_FORMULA, formula))
                .doesNotThrowAnyException();
        assertThatCode(() -> RuleProposals.requireValue(RuleValueShape.CONDITION_FORMULA, formula))
                .doesNotThrowAnyException();
        // Missing, or carrying a second kind of value the engine would silently ignore.
        Map<String, Value> refusedScalar =
                Map.of(
                        "none", new Value(null, null, null),
                        "with formula", new Value(n("1"), "BASIC", null),
                        "with bands", new Value(n("1"), null, bands),
                        "formula only", formula);
        refusedScalar.forEach(
                (why, v) ->
                        assertThatThrownBy(
                                        () -> RuleProposals.requireValue(RuleValueShape.SCALAR, v))
                                .as(why)
                                .satisfies(
                                        t ->
                                                assertThat(code(t))
                                                        .isEqualTo(
                                                                ApiErrorCode.RULE_VALUE_INVALID)));
        Map<String, Value> refusedSlab =
                Map.of(
                        "none", new Value(null, null, List.of()),
                        "with scalar", new Value(n("1"), null, bands),
                        "with formula", new Value(null, "BASIC", bands),
                        "scalar only", scalar);
        refusedSlab.forEach(
                (why, v) ->
                        assertThatThrownBy(() -> RuleProposals.requireValue(RuleValueShape.SLAB, v))
                                .as(why)
                                .satisfies(
                                        t ->
                                                assertThat(code(t))
                                                        .isEqualTo(
                                                                ApiErrorCode.RULE_VALUE_INVALID)));
        Map<String, Value> refusedFormula =
                Map.of(
                        "none", new Value(null, null, null),
                        "blank", new Value(null, "   ", null),
                        "with scalar", new Value(n("1"), "BASIC", null),
                        "with bands", new Value(null, "BASIC", bands));
        for (RuleValueShape shape :
                List.of(RuleValueShape.NUMBER_FORMULA, RuleValueShape.CONDITION_FORMULA)) {
            refusedFormula.forEach(
                    (why, v) ->
                            assertThatThrownBy(() -> RuleProposals.requireValue(shape, v))
                                    .as(shape + " " + why)
                                    .satisfies(
                                            t ->
                                                    assertThat(code(t))
                                                            .isEqualTo(
                                                                    ApiErrorCode
                                                                            .RULE_VALUE_INVALID)));
        }
        // No value object at all is "none".
        assertThatThrownBy(() -> RuleProposals.requireValue(RuleValueShape.SCALAR, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID));
        assertThatThrownBy(() -> RuleProposals.requireValue(null, scalar))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_19_5_aSlabIsCheckedAsASetAndEachBandNumberFitsTheField() {
        assertThatThrownBy(
                        () ->
                                RuleProposals.requireValue(
                                        RuleValueShape.SLAB,
                                        new Value(
                                                null,
                                                null,
                                                List.of(
                                                        band(1, "0", "100", "1"),
                                                        band(2, "150", null, "2")))))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        // Every number of a band is held to Numeric (12,2), each named in the refusal.
        Map<String, Band> tooFine =
                Map.of(
                        "band 1 from", new Band(1, n("0.001"), null, n("1"), null, null),
                        "band 1 to", new Band(1, n("0"), n("10.001"), n("1"), null, null),
                        "band 1 rate or amount", new Band(1, n("0"), null, n("1.005"), null, null),
                        "band 1 step", new Band(1, n("0"), null, n("1"), n("0.125"), null));
        tooFine.forEach(
                (what, b) ->
                        assertThatThrownBy(
                                        () ->
                                                RuleProposals.requireValue(
                                                        RuleValueShape.SLAB,
                                                        new Value(null, null, List.of(b))))
                                .as(what)
                                .satisfies(
                                        t -> {
                                            assertThat(code(t))
                                                    .isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                                            assertThat(arg(t, 0)).asString().startsWith(what);
                                        }));
    }

    @Test
    void SRS_19_1_aScalarValueIsHeldToNumeric12Comma2ByTheShapeCheckItself() {
        assertThatThrownBy(
                        () ->
                                RuleProposals.requireValue(
                                        RuleValueShape.SCALAR, new Value(n("12.345"), null, null)))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0))
                                    .isEqualTo("the value exceeds 10 digits and 2 decimals");
                        });
    }

    @Test
    void SRS_G_17_aSlabRateTheM08FieldCannotHoldExactlyIsRefusedNeverRounded() {
        // M-08 Rule Value(s) / Slab Bands is Numeric (12,2) (SRS l.1966): 7.125 % from an order
        // is refused naming the band, never stored as 7.13 (SRS-G-17; field length: CLR-42).
        List<SlabBands.Band> bands =
                List.of(
                        new SlabBands.Band(1, n("0"), n("1000"), n("5"), null, null),
                        new SlabBands.Band(2, n("1000"), null, n("7.125"), null, null));
        assertThatThrownBy(
                        () ->
                                RuleProposals.requireValue(
                                        RuleValueShape.SLAB, new Value(null, null, bands)))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0))
                                    .isEqualTo(
                                            "band 2 rate or amount exceeds 10 digits and 2"
                                                    + " decimals");
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "0.01",
                "12.5",
                "12.500",
                "9999999999.99",
                "-9999999999.99",
                "1E+9",
                "100.10"
            })
    void SRS_19_1_aValueOfAtMostTenDigitsAndTwoDecimalsFitsTheField(String value) {
        assertThatCode(() -> RuleProposals.requireAmount("the value", n(value)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "12.345", "10000000000", "1E+10", "-10000000000.5"})
    void SRS_19_1_aValueBeyondTenDigitsOrTwoDecimalsIsRefusedNamingIt(String value) {
        assertThatThrownBy(() -> RuleProposals.requireAmount("the value", n(value)))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0))
                                    .isEqualTo("the value exceeds 10 digits and 2 decimals");
                        });
    }

    @Test
    void SRS_19_1_aMissingAmountIsLeftToTheShapeCheck() {
        assertThatCode(() -> RuleProposals.requireAmount("the value", null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- SRS-19-12 dates

    @Test
    void RE_1_theEffectiveFromDateIsMandatoryAndTheEffectiveToIsNotEarlier() {
        // BR-RULE-001: a version without its effective-from date cannot be submitted.
        assertThatThrownBy(() -> RuleProposals.requireFreeDates(null, null, List.of()))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0)).isEqualTo("the effective-from date is mandatory");
                        });
        assertThatThrownBy(() -> RuleProposals.requireFreeDates(MAY, MAY.minusDays(1), List.of()))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID));
        // A one-day version (to = from) and an open one are both valid.
        assertThatCode(() -> RuleProposals.requireFreeDates(MAY, MAY, List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> RuleProposals.requireFreeDates(MAY, null, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_19_12_aNewVersionStartsAfterEveryApprovedVersionAndTheConflictIsNamed() {
        List<Dated> history =
                List.of(
                        new Dated(3L, 3, MAY, null, true),
                        new Dated(2L, 2, APR, MAY.minusDays(1), true),
                        new Dated(4L, 4, JUN.plusDays(5), null, false));
        // On or before the latest approved start: refused naming that version (3, from MAY).
        for (LocalDate from : List.of(MAY, MAY.minusDays(1), APR)) {
            assertThatThrownBy(() -> RuleProposals.requireFreeDates(from, null, history))
                    .as(from.toString())
                    .satisfies(
                            t -> {
                                assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VERSION_CONFLICT);
                                assertThat(((FciException) t).getMessageArgs())
                                        .containsExactly(3, MAY);
                            });
        }
        // The day after: free (the version in force is ended the day before on approval).
        assertThatCode(() -> RuleProposals.requireFreeDates(MAY.plusDays(1), null, history))
                .doesNotThrowAnyException();
        // A day another (unapproved) version holds is taken: uk_rule_version_eff.
        assertThatThrownBy(() -> RuleProposals.requireFreeDates(JUN.plusDays(5), null, history))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.MASTER_DATE_TAKEN);
                            assertThat(arg(t, 0)).isEqualTo(JUN.plusDays(5));
                        });
        // Only approved versions bound the start: an unapproved later version does not.
        assertThatCode(
                        () ->
                                RuleProposals.requireFreeDates(
                                        JUN,
                                        null,
                                        List.of(new Dated(1L, 1, JUN.plusDays(9), null, false))))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_19_12_theLatestApprovedVersionIsTheOneWithTheLatestStartNotTheHighestNumber() {
        // Version numbers follow submission order, starts may not: the start decides.
        List<Dated> history =
                List.of(new Dated(9L, 9, APR, null, true), new Dated(2L, 2, JUN, null, true));
        assertThatThrownBy(() -> RuleProposals.requireFreeDates(MAY, null, history))
                .satisfies(
                        t ->
                                assertThat(((FciException) t).getMessageArgs())
                                        .containsExactly(2, JUN));
        assertThat(RuleProposals.requireEnd(JUN.plusDays(1), history).versionNo()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- SRS-19-6 end date

    @Test
    void SRS_19_6_anEndDateEndsTheLatestApprovedVersionOnOrAfterItsStart() {
        // BR-RULE-004: a rule is deactivated only by an explicit end date (here) or a successor
        // version (VersionChainTest).
        List<Dated> history =
                List.of(
                        new Dated(2L, 2, MAY, null, true),
                        new Dated(1L, 1, APR, MAY.minusDays(1), true));
        assertThat(RuleProposals.requireEnd(MAY, history)).isEqualTo(history.get(0));
        assertThat(RuleProposals.requireEnd(JUN, history).versionNo()).isEqualTo(2);
        assertThatThrownBy(() -> RuleProposals.requireEnd(MAY.minusDays(1), history))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_END_INVALID);
                            assertThat(arg(t, 0)).isEqualTo(MAY);
                        });
        assertThatThrownBy(() -> RuleProposals.requireEnd(null, history))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0)).isEqualTo("the end date is mandatory");
                        });
        // Nothing approved: there is nothing to end (an unapproved version does not count).
        assertThatThrownBy(
                        () ->
                                RuleProposals.requireEnd(
                                        JUN, List.of(new Dated(1L, 1, APR, null, false))))
                .satisfies(
                        t -> {
                            assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_VALUE_INVALID);
                            assertThat(arg(t, 0))
                                    .isEqualTo("the rule has no approved version to end");
                        });
    }

    // ---------------------------------------------------------------- RE-10 scope

    private static final Function<Long, Set<Long>> TREE =
            office -> office == 10L ? Set.of(10L, 11L, 12L) : Set.of(office);

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                // scope types, scope office, asking type, asking office, covered
                "NULL, NULL, NULL, NULL, true",
                "'', NULL, NULL, NULL, true",
                "'  ', NULL, STAFF, NULL, true",
                "STAFF, NULL, STAFF, NULL, true",
                "'STAFF, DPS_LABOUR', NULL, DPS_LABOUR, NULL, true",
                "'STAFF,DPS_LABOUR', NULL, NWNP_LABOUR, NULL, false",
                "STAFF, NULL, NULL, NULL, false",
                "STAFF, NULL, staff, NULL, false",
                "NULL, 10, NULL, 11, true",
                "NULL, 10, NULL, 10, true",
                "NULL, 10, NULL, 99, false",
                "NULL, 10, NULL, NULL, false",
                "STAFF, 10, STAFF, 12, true",
                "STAFF, 10, DPS_LABOUR, 12, false",
                "STAFF, 10, STAFF, 99, false"
            })
    void RE_10_aRuleAppliesOnlyToTheEnginesAndOfficesItIsScopedTo(
            String types, Long office, String type, Long location, boolean covered) {
        assertThat(RuleScope.covers(types, office, type, location, TREE)).isEqualTo(covered);
    }

    // ---------------------------------------------------------------- SRS-19-15 result kind

    private static final Grammar GRAMMAR =
            new Grammar(
                    Set.of("+", "-", "*", "/", "<", "<=", ">", ">=", "=", "<>", "AND", "OR", "NOT"),
                    functions(),
                    Set.of("BASIC"));

    /** The seeded functions: ROUND and IF with a fixed arity, MIN and MAX with any (null). */
    private static Map<String, Integer> functions() {
        Map<String, Integer> out = new java.util.HashMap<>(Map.of("ROUND", 2, "IF", 3));
        out.put("MIN", null);
        out.put("MAX", null);
        return out;
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "BASIC + 1 | false",
                "BASIC - 1 | false",
                "BASIC * 2 | false",
                "BASIC / 2 | false",
                "-BASIC | false",
                "42 | false",
                "BASIC | false",
                "MIN(BASIC, 2) | false",
                "MAX(BASIC, 2) | false",
                "ROUND(BASIC, 0) | false",
                "IF(BASIC > 1, 2, 3) | false",
                "BASIC < 1 | true",
                "BASIC <= 1 | true",
                "BASIC > 1 | true",
                "BASIC >= 1 | true",
                "BASIC = 1 | true",
                "BASIC <> 1 | true",
                "BASIC > 1 AND BASIC < 9 | true",
                "BASIC < 1 OR BASIC > 9 | true",
                "NOT (BASIC > 1) | true",
                "(BASIC > 1) | true",
                "IF(BASIC > 1, BASIC > 2, BASIC > 3) | true"
            })
    void SRS_19_15_aFormulaIsKnownToYieldANumberOrAConditionBeforeItIsEvaluated(
            String formula, boolean condition) {
        assertThat(RuleFormula.parse(formula, GRAMMAR).yieldsCondition()).isEqualTo(condition);
    }

    // ---------------------------------------------------------------- SRS-20-2 audit image

    @Test
    void SRS_20_2_theAuditImageOfARuleVersionNamesEveryValueItHolds() {
        RuleVersion scalar =
                RuleVersion.approved(
                        7L,
                        1,
                        APR,
                        MAY,
                        n("12.50"),
                        null,
                        "ROUND.RUPEE",
                        42L,
                        "admin",
                        9L,
                        Instant.EPOCH);
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("scalar_value", "12.5");
        expected.put("formula_expression", null);
        expected.put("rounding_rule_key", "ROUND.RUPEE");
        expected.put("circular_order_id", "42");
        expected.put("effective_to", "2026-05-01");
        expected.put("slab_bands", null);
        assertThat(MasterImages.ruleVersion(scalar, List.of())).containsExactlyEntriesOf(expected);
        RuleVersion slab =
                RuleVersion.approved(
                        7L, 2, JUN, null, null, null, null, 42L, "admin", 10L, Instant.EPOCH);
        // Bands in order, each "qualifier from-to: rate [step s]"; an open last band has no "to".
        Map<String, String> image =
                MasterImages.ruleVersion(
                        slab,
                        List.of(
                                new Band(2, n("100"), null, n("2.50"), n("10"), "CITY-X"),
                                new Band(1, n("0"), n("100"), n("1"), null, null)));
        assertThat(image.get("slab_bands")).isEqualTo("0-100: 1; CITY-X 100-: 2.5 step 10");
        assertThat(image.get("effective_to")).isNull();
        assertThat(image.get("scalar_value")).isNull();
        RuleVersion formula =
                RuleVersion.approved(
                        7L,
                        3,
                        JUN,
                        null,
                        null,
                        "BASIC * 2",
                        null,
                        42L,
                        "admin",
                        11L,
                        Instant.EPOCH);
        assertThat(MasterImages.ruleVersion(formula, List.of()).get("formula_expression"))
                .isEqualTo("BASIC * 2");
    }

    // ---------------------------------------------------------------- the entity's own guards

    @Test
    void SRS_19_6_aVersionNeverEndsBeforeItStartsAndOnlyAnApprovedVersionIsEnded() {
        assertThatThrownBy(
                        () ->
                                RuleVersion.approved(
                                        7L,
                                        1,
                                        MAY,
                                        APR,
                                        n("1"),
                                        null,
                                        null,
                                        42L,
                                        "admin",
                                        9L,
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RuleVersion.approved(
                                        7L,
                                        1,
                                        MAY,
                                        null,
                                        n("1"),
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        9L,
                                        Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        RuleVersion v =
                RuleVersion.approved(
                        7L, 1, MAY, null, n("1"), null, null, 42L, "admin", 9L, Instant.EPOCH);
        assertThat(v.getState()).isEqualTo(RuleVersion.State.APPROVED);
        assertThat(v.getCreatedBy()).isEqualTo("admin");
        assertThat(v.getApprovalId()).isEqualTo(9L);
        assertThatThrownBy(() -> v.endOn(MAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        v.endOn(MAY);
        assertThat(v.getEffectiveTo()).isEqualTo(MAY);
        RuleVersion pending = RuleVersion.propose(7L, 2, JUN, n("2"), "admin", 10L, Instant.EPOCH);
        assertThatThrownBy(() -> pending.endOn(JUN.plusDays(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BR_RULE_002_onlyAnApprovedVersionIsInForceAndOnlyAPendingOneIsDecided() {
        RuleVersion pending = RuleVersion.propose(7L, 2, MAY, n("2"), "admin", 10L, Instant.EPOCH);
        // Pending: never in force, whatever the day.
        assertThat(pending.isInForceOn(MAY)).isFalse();
        // A-07 dating: approved later than its proposed start, it starts on the approval day.
        pending.approve(JUN);
        assertThat(pending.getEffectiveFrom()).isEqualTo(JUN);
        assertThat(pending.isInForceOn(JUN)).isTrue();
        assertThat(pending.isInForceOn(JUN.minusDays(1))).isFalse();
        // Decided once: a second approval is refused, a rejection of an approved version is a
        // no-op.
        assertThatThrownBy(() -> pending.approve(JUN)).isInstanceOf(IllegalStateException.class);
        pending.reject();
        assertThat(pending.getState()).isEqualTo(RuleVersion.State.APPROVED);
        // Approved before its proposed start, it keeps that start.
        RuleVersion early = RuleVersion.propose(7L, 3, JUN, n("3"), "admin", 11L, Instant.EPOCH);
        early.approve(MAY);
        assertThat(early.getEffectiveFrom()).isEqualTo(JUN);
        RuleVersion rejected = RuleVersion.propose(7L, 4, JUN, n("4"), "admin", 12L, Instant.EPOCH);
        rejected.reject();
        assertThat(rejected.getState()).isEqualTo(RuleVersion.State.REJECTED);
        assertThat(rejected.isInForceOn(JUN)).isFalse();
    }

    @Test
    void SRS_19_12_aSupersededVersionEndsTheDayBeforeItsSuccessorWhichCannotStartFirst() {
        RuleVersion v =
                RuleVersion.approved(
                        7L, 1, APR, null, n("1"), null, null, 42L, "admin", 9L, Instant.EPOCH);
        v.endBefore(MAY);
        assertThat(v.getEffectiveTo()).isEqualTo(MAY.minusDays(1));
        assertThat(v.isInForceOn(MAY.minusDays(1))).isTrue();
        assertThat(v.isInForceOn(MAY)).isFalse();
        // A one-day version: the successor starts the next day.
        v.endBefore(APR.plusDays(1));
        assertThat(v.getEffectiveTo()).isEqualTo(APR);
        assertThatThrownBy(() -> v.endBefore(APR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_20_2_theProposerIsRecordedWithinTheColumnWidth() {
        String long60 = "a".repeat(60);
        assertThat(
                        RuleVersion.propose(7L, 1, APR, n("1"), long60, 1L, Instant.EPOCH)
                                .getCreatedBy())
                .hasSize(50);
        String exact50 = "b".repeat(50);
        assertThat(
                        RuleVersion.propose(7L, 1, APR, n("1"), exact50, 1L, Instant.EPOCH)
                                .getCreatedBy())
                .isEqualTo(exact50);
    }
}
