package in.gov.fci.payroll.configuration.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.business.RuleFormula.Grammar;
import in.gov.fci.payroll.configuration.business.SlabBands.Band;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rule engine's pure rules (Module 06): the bounded formula layer (SRS-19-15), slab bands
 * (SRS-19-5, BR-RULE-003) and the derived version status (SRS-19-11), each with the spec's negative
 * cases.
 */
class RuleEngineRulesTest {

    /** The grammar seed of V032_2, plus three registered variables. */
    private static final Grammar GRAMMAR =
            new Grammar(
                    Set.of("+", "-", "*", "/", "<", "<=", ">", ">=", "=", "<>", "AND", "OR", "NOT"),
                    nullable(Map.of("ROUND", 2, "IF", 3), "MIN", "MAX"),
                    Set.of("BASIC", "DA", "CITY"));

    private static Map<String, Integer> nullable(Map<String, Integer> fixed, String... variadic) {
        Map<String, Integer> out = new HashMap<>(fixed);
        for (String f : variadic) {
            out.put(f, null);
        }
        return out;
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal eval(String formula, Map<String, BigDecimal> values) {
        return RuleFormula.parse(formula, GRAMMAR).number(values);
    }

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static final Map<String, BigDecimal> STAFF =
            Map.of("BASIC", n("40000"), "DA", n("53"), "CITY", n("1"));

    // ---------------------------------------------------------------- SRS-19-15 formula layer

    @Test
    void SRS_19_15_aFormulaIsEvaluatedWithItsPrecedenceExactlyAndWithoutRounding() {
        assertThat(eval("BASIC * DA / 100 + 5", STAFF)).isEqualByComparingTo("21205");
        // Left-associative.
        assertThat(eval("100 - 20 - 10", STAFF)).isEqualByComparingTo("70");
        assertThat(eval("100 / 10 / 2", STAFF)).isEqualByComparingTo("5");
        assertThat(eval("(100 - 20) * 2", STAFF)).isEqualByComparingTo("160");
        assertThat(eval("-BASIC + 10", STAFF)).isEqualByComparingTo("-39990");
        // Exact: no rounding until the computation's defined point (rounding-money R-01 ... R-03).
        assertThat(eval("10 / 4", STAFF)).isEqualByComparingTo("2.5");
        assertThat(eval("BASIC * 0.0825", STAFF)).isEqualByComparingTo("3300.0000");
        assertThat(eval(".5 + 0.25", STAFF)).isEqualByComparingTo("0.75");
    }

    @Test
    void SRS_19_15_conditionsAndTheGenericFunctionsOfTheGrammar() {
        assertThat(eval("IF(BASIC >= 30000 AND NOT (CITY = 2), 1000, 500)", STAFF))
                .isEqualByComparingTo("1000");
        assertThat(eval("IF(BASIC < 30000 OR CITY <> 1, 1000, 500)", STAFF))
                .isEqualByComparingTo("500");
        assertThat(eval("MIN(BASIC, 18000, 25000)", STAFF)).isEqualByComparingTo("18000");
        assertThat(eval("MAX(1, DA, 7)", STAFF)).isEqualByComparingTo("53");
        assertThat(eval("MIN(5)", STAFF)).isEqualByComparingTo("5");
        // ROUND is half-up, as the component rounding (R-02).
        assertThat(eval("ROUND(2.5, 0)", STAFF)).isEqualByComparingTo("3");
        assertThat(eval("ROUND(1.005, 2)", STAFF)).isEqualByComparingTo("1.01");
        assertThat(eval("ROUND(-2.5, 0)", STAFF)).isEqualByComparingTo("-3");
        RuleFormula eligibility = RuleFormula.parse("BASIC <= 50000 AND CITY >= 1", GRAMMAR);
        assertThat(eligibility.condition(STAFF)).isTrue();
        assertThat(eligibility.variables()).containsExactly("BASIC", "CITY");
    }

    @Test
    void SRS_19_15_nothingOutsideTheGrammarIsEverEvaluated() {
        for (String refused :
                List.of(
                        // no free program code
                        "BASIC; DROP TABLE rule_version",
                        "System.exit(0)",
                        "Runtime.getRuntime()",
                        "BASIC & 1",
                        "'text'",
                        "BASIC ^ 2",
                        // not in the grammar
                        "SQRT(BASIC)",
                        "UNKNOWN_VAR * 2",
                        "basic * 2",
                        // malformed
                        "",
                        "   ",
                        "1.2.3",
                        "(BASIC + 1",
                        "BASIC + 1)",
                        "BASIC +",
                        "* BASIC",
                        "BASIC DA",
                        "ROUND(1)",
                        "IF(BASIC > 1, 2)",
                        "MIN()",
                        "MAX(1,)")) {
            assertThatThrownBy(() -> RuleFormula.parse(refused, GRAMMAR))
                    .as(refused)
                    .satisfies(
                            e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        }
        // An operator the grammar does not permit (is_permitted = 'N' or absent) is refused.
        Grammar noDivision = new Grammar(Set.of("+", "*"), Map.of(), Set.of("BASIC"));
        assertThatThrownBy(() -> RuleFormula.parse("BASIC / 2", noDivision))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .asString()
                                        .contains("'/'"));
    }

    @Test
    void SRS_19_15_aFormulaTooLongOrTooDeepIsRefusedBeforeItIsParsed() {
        String tooLong = "1" + " + 1".repeat(1000);
        assertThat(tooLong.length()).isGreaterThan(RuleFormula.MAX_LENGTH);
        assertThatThrownBy(() -> RuleFormula.parse(tooLong, GRAMMAR))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        String deep =
                "(".repeat(RuleFormula.MAX_DEPTH + 1) + "1" + ")".repeat(RuleFormula.MAX_DEPTH + 1);
        assertThatThrownBy(() -> RuleFormula.parse(deep, GRAMMAR))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .asString()
                                        .contains("nested"));
        String negations = "-".repeat(RuleFormula.MAX_DEPTH + 1) + "1";
        assertThatThrownBy(() -> RuleFormula.parse(negations, GRAMMAR))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        // Within the limit, nesting is fine.
        String ok = "(".repeat(10) + "1" + ")".repeat(10);
        assertThat(eval(ok, STAFF)).isEqualByComparingTo("1");
    }

    @Test
    void SRS_19_15_aFormulaThatCannotBeEvaluatedIsRefusedNeverDefaulted() {
        for (Map.Entry<String, Map<String, BigDecimal>> bad :
                Map.of(
                                "BASIC / (DA - 53)", STAFF,
                                "BASIC * 2", Map.<String, BigDecimal>of(),
                                "BASIC AND DA", STAFF,
                                "NOT BASIC", STAFF,
                                "IF(BASIC, 1, 2)", STAFF,
                                "ROUND(BASIC, -1)", STAFF,
                                "ROUND(BASIC, 1.5)", STAFF,
                                "ROUND(BASIC, 11)", STAFF,
                                "MIN(BASIC, BASIC > 1)", STAFF)
                        .entrySet()) {
            assertThatThrownBy(() -> eval(bad.getKey(), bad.getValue()))
                    .as(bad.getKey())
                    .satisfies(
                            e ->
                                    assertThat(code(e))
                                            .isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
        }
        assertThatThrownBy(() -> eval("BASIC > 1", STAFF))
                .satisfies(
                        e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
        assertThatThrownBy(() -> RuleFormula.parse("BASIC + 1", GRAMMAR).condition(STAFF))
                .satisfies(
                        e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
    }

    @Test
    void SRS_19_15_theLimitsHoldExactlyAtTheirBoundary() {
        // Exactly MAX_LENGTH characters is accepted; one more is refused.
        String atLimit = "1" + " ".repeat(RuleFormula.MAX_LENGTH - 1);
        assertThat(atLimit).hasSize(RuleFormula.MAX_LENGTH);
        assertThat(eval(atLimit, STAFF)).isEqualByComparingTo("1");
        assertThatThrownBy(() -> RuleFormula.parse(atLimit + " ", GRAMMAR))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        // The outermost expression is level 1: MAX_DEPTH - 1 parentheses fit, MAX_DEPTH do not.
        int fits = RuleFormula.MAX_DEPTH - 1;
        assertThat(eval("(".repeat(fits) + "1" + ")".repeat(fits), STAFF))
                .isEqualByComparingTo("1");
        int over = RuleFormula.MAX_DEPTH;
        assertThatThrownBy(
                        () -> RuleFormula.parse("(".repeat(over) + "1" + ")".repeat(over), GRAMMAR))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID));
        // ROUND takes up to ten places.
        assertThat(eval("ROUND(1.123456789049, 10)", STAFF)).isEqualByComparingTo("1.1234567890");
    }

    @Test
    void SRS_19_15_unaryOperatorsFunctionsAndShortCircuitsAreChecked() {
        // A unary minus is an operator the grammar must permit too.
        Grammar noMinus = new Grammar(Set.of("+"), Map.of(), Set.of("BASIC"));
        assertThatThrownBy(() -> RuleFormula.parse("-BASIC", noMinus))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .asString()
                                        .contains("'-'"));
        Grammar noNot = new Grammar(Set.of(">"), Map.of(), Set.of("BASIC"));
        assertThatThrownBy(() -> RuleFormula.parse("NOT (BASIC > 1)", noNot))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .asString()
                                        .contains("'NOT'"));
        // A minus on a condition is a type mismatch, found at evaluation.
        assertThatThrownBy(() -> eval("-(BASIC > 1)", STAFF))
                .satisfies(
                        e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
        // Every variable is found, under a unary operator, in a call and in a nested expression.
        assertThat(RuleFormula.parse("-BASIC + MAX(DA, 2 * CITY)", GRAMMAR).variables())
                .containsExactly("BASIC", "CITY", "DA");
        assertThat(RuleFormula.parse("NOT (BASIC > 1)", GRAMMAR).variables())
                .containsExactly("BASIC");
        assertThat(RuleFormula.parse("42", GRAMMAR).variables()).isEmpty();
        // AND / OR stop at the first decisive side: a missing value on the other side is never
        // read.
        Map<String, BigDecimal> onlyBasic = Map.of("BASIC", n("1"));
        assertThat(RuleFormula.parse("BASIC > 5 AND DA > 1", GRAMMAR).condition(onlyBasic))
                .isFalse();
        assertThat(RuleFormula.parse("BASIC < 5 OR DA > 1", GRAMMAR).condition(onlyBasic)).isTrue();
        assertThat(RuleFormula.parse("BASIC < 5 AND BASIC > 0", GRAMMAR).condition(onlyBasic))
                .isTrue();
        assertThat(RuleFormula.parse("BASIC > 5 OR BASIC < 0", GRAMMAR).condition(onlyBasic))
                .isFalse();
        // MIN / MAX keep the first value when a later one does not beat it; an equal value changes
        // nothing.
        assertThat(eval("MIN(3, 7, 5)", STAFF)).isEqualByComparingTo("3");
        assertThat(eval("MAX(9, 7, 8)", STAFF)).isEqualByComparingTo("9");
        assertThat(eval("MIN(4, 4)", STAFF)).isEqualByComparingTo("4");
        // IF evaluates only the branch it takes: the other one may even divide by zero.
        assertThat(eval("IF(BASIC > 1, 1, 1 / 0)", STAFF)).isEqualByComparingTo("1");
        assertThat(eval("IF(BASIC < 1, 1 / 0, 2)", STAFF)).isEqualByComparingTo("2");
    }

    @Test
    void SRS_19_15_onATieMinAndMaxKeepTheEarlierValueAsWritten() {
        // Equal by value, different in scale: the first one written is the one returned.
        assertThat(eval("MIN(4.0, 4, 5)", STAFF)).isEqualTo(new BigDecimal("4.0"));
        assertThat(eval("MIN(4, 4.0)", STAFF)).isEqualTo(new BigDecimal("4"));
        assertThat(eval("MAX(2.50, 2.5, 1)", STAFF)).isEqualTo(new BigDecimal("2.50"));
        assertThat(eval("MAX(1, 2.5, 2.50)", STAFF)).isEqualTo(new BigDecimal("2.5"));
        assertThat(eval("MIN(7)", STAFF)).isEqualTo(new BigDecimal("7"));
    }

    @Test
    void SRS_19_15_aLongFlatFormulaIsBreadthNotDepth() {
        // 40 terms side by side, and 40 negated terms: never nested deeper than two levels.
        String sum = "1" + " + 1".repeat(39);
        assertThat(eval(sum, STAFF)).isEqualByComparingTo("40");
        String negated = "-1" + " + -1".repeat(39);
        assertThat(eval(negated, STAFF)).isEqualByComparingTo("-40");
        String conditions = "BASIC > 0" + " AND BASIC > 0".repeat(39);
        assertThat(RuleFormula.parse(conditions, GRAMMAR).condition(STAFF)).isTrue();
    }

    @Test
    void SRS_19_15_everyComparisonGivesBothOutcomes() {
        Map<String, Boolean> cases = new java.util.LinkedHashMap<>();
        cases.put("1 < 2", true);
        cases.put("2 < 2", false);
        cases.put("2 <= 2", true);
        cases.put("3 <= 2", false);
        cases.put("3 > 2", true);
        cases.put("2 > 2", false);
        cases.put("2 >= 2", true);
        cases.put("1 >= 2", false);
        cases.put("2 = 2.00", true);
        cases.put("1 = 2", false);
        cases.put("1 <> 2", true);
        cases.put("2 <> 2.0", false);
        cases.forEach(
                (formula, expected) ->
                        assertThat(RuleFormula.parse(formula, GRAMMAR).condition(Map.of()))
                                .as(formula)
                                .isEqualTo(expected));
    }

    @Test
    void SRS_19_15_theTokensAtTheEdgesOfTheSyntaxAreReadOrRefusedNamingThem() {
        // A number may start with its point; a name may start with an underscore.
        assertThat(eval(".5 + 1", STAFF)).isEqualByComparingTo("1.5");
        Grammar underscore = new Grammar(Set.of("*"), Map.of(), Set.of("_SPECIAL"));
        assertThat(RuleFormula.parse("_SPECIAL * 2", underscore).number(Map.of("_SPECIAL", n("4"))))
                .isEqualByComparingTo("8");
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put(null, "empty");
        refused.put("1 + .", "character '.' is not permitted");
        refused.put("1 2", "unexpected '2'");
        refused.put("BASIC NOT 1", "unexpected 'NOT'");
        refused.put("(1", "')' expected");
        refused.put("MIN(1", "')' expected");
        refused.put("MIN(", "ends too early");
        refused.put("MIN()", "MIN needs arguments");
        refused.forEach(
                (formula, why) ->
                        assertThatThrownBy(() -> RuleFormula.parse(formula, GRAMMAR))
                                .as(String.valueOf(formula))
                                .satisfies(
                                        e -> {
                                            assertThat(code(e))
                                                    .isEqualTo(ApiErrorCode.RULE_FORMULA_INVALID);
                                            assertThat(((FciException) e).getMessageArgs()[0])
                                                    .isEqualTo(why);
                                        }));
    }

    @Test
    void SRS_19_15_aGrammarRowTheEngineCannotHonourIsRefusedAtSubmissionNotInARun() {
        // The grammar is data: it may permit a function the engine has no evaluator for.
        Map<String, Integer> functions = new HashMap<>();
        functions.put("ABS", 1);
        functions.put("ROUND", null);
        functions.put("IF", null);
        Grammar data = new Grammar(Set.of(">"), functions, Set.of("BASIC"));
        assertThatThrownBy(() -> RuleFormula.parse("ABS(BASIC)", data))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .isEqualTo(
                                                "function 'ABS' is not supported by the rule"
                                                        + " engine"));
        // An arity the grammar leaves open is still held to what the engine evaluates.
        assertThatThrownBy(() -> RuleFormula.parse("ROUND(BASIC)", data))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .isEqualTo("ROUND cannot take 1 arguments"));
        assertThatThrownBy(() -> RuleFormula.parse("IF(BASIC > 1, 1, 2, 3)", data))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .isEqualTo("IF cannot take 4 arguments"));
        assertThat(RuleFormula.parse("IF(BASIC > 1, 1, 2)", data).number(Map.of("BASIC", n("5"))))
                .isEqualByComparingTo("1");
    }

    @Test
    void SRS_19_15_aConditionWhereANumberIsNeededOrTheReverseIsRefusedAtEvaluation() {
        Map<String, BigDecimal> one = Map.of("BASIC", n("1"));
        for (String bad : List.of("NOT 5", "1 AND BASIC > 0", "BASIC > 5 OR 1", "-(BASIC > 0)")) {
            assertThatThrownBy(() -> RuleFormula.parse(bad, GRAMMAR).condition(one))
                    .as(bad)
                    .satisfies(
                            e ->
                                    assertThat(code(e))
                                            .isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
        }
        // OR stops at a true left side: the right side is never read.
        assertThat(RuleFormula.parse("BASIC > 0 OR 1", GRAMMAR).condition(one)).isTrue();
        // The full truth tables of AND, OR and NOT.
        Map<String, Boolean> table = new java.util.LinkedHashMap<>();
        table.put("BASIC > 0 AND BASIC > 0", true);
        table.put("BASIC > 0 AND BASIC > 5", false);
        table.put("BASIC > 5 AND BASIC > 0", false);
        table.put("BASIC > 5 OR BASIC > 0", true);
        table.put("BASIC > 5 OR BASIC > 6", false);
        table.put("NOT (BASIC > 5)", true);
        table.put("NOT (BASIC > 0)", false);
        table.forEach(
                (formula, expected) ->
                        assertThat(RuleFormula.parse(formula, GRAMMAR).condition(one))
                                .as(formula)
                                .isEqualTo(expected));
        // A point that does not start a number is not a token.
        assertThatThrownBy(() -> RuleFormula.parse("1 + .x", GRAMMAR))
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getMessageArgs()[0])
                                        .isEqualTo("character '.' is not permitted"));
        // ROUND's places are checked as a number, however large, never truncated to an int.
        assertThatThrownBy(() -> eval("ROUND(BASIC, 99999999999)", STAFF))
                .satisfies(
                        e -> assertThat(code(e)).isEqualTo(ApiErrorCode.RULE_FORMULA_EVALUATION));
        assertThat(eval("ROUND(2.5, 0)", STAFF)).isEqualByComparingTo("3");
        assertThat(eval("ROUND(2.45, 1)", STAFF)).isEqualByComparingTo("2.5");
    }

    // ---------------------------------------------------------------- SRS-19-5 slab bands

    private static Band band(int order, String from, String to, String rate, String qualifier) {
        return new Band(order, n(from), to == null ? null : n(to), n(rate), null, qualifier);
    }

    // OPEN-QUESTION CLR-128 (BMAD TR-014): which band owns a boundary amount is not in the source;
    // the upper-band ownership and "next starts at the previous end" are as built. No new test may
    // depend on them until FCI answers.
    @Test
    void SRS_19_5_contiguousBandsAreAcceptedPerQualifierAndTheBoundaryBelongsToTheBandItStarts() {
        List<Band> bands =
                List.of(
                        band(2, "250000", "500000", "5", null),
                        band(1, "0", "250000", "0", null),
                        band(3, "500000", null, "20", null),
                        // Numbers are unique in the version across qualifiers (uk_rule_slab_band).
                        band(4, "0", "10000", "100", "X"),
                        band(5, "10000", null, "200", "X"));
        SlabBands.validate(bands);
        assertThat(SlabBands.bandFor(bands, null, n("249999.99")).orElseThrow().order())
                .isEqualTo(1);
        assertThat(SlabBands.bandFor(bands, null, n("250000")).orElseThrow().order()).isEqualTo(2);
        assertThat(SlabBands.bandFor(bands, null, n("99999999")).orElseThrow().order())
                .isEqualTo(3);
        assertThat(SlabBands.bandFor(bands, "X", n("10000")).orElseThrow().rateOrAmount())
                .isEqualByComparingTo("200");
        assertThat(SlabBands.bandFor(bands, null, n("-1"))).isEmpty();
        assertThat(SlabBands.bandFor(bands, "Y", n("1"))).isEmpty();
    }

    // SC-08-0018: a tax slab whose bands are not contiguous and non-overlapping is rejected.
    @Test
    void SRS_19_5_SRS_7_18_BR_TAX_003_aGapAnOverlapOrAnOpenBandBeforeTheLastIsRefused() {
        Map<String, List<Band>> refused =
                Map.of(
                        "gap",
                                List.of(
                                        band(1, "0", "100", "1", null),
                                        band(2, "101", null, "2", null)),
                        "overlap",
                                List.of(
                                        band(1, "0", "100", "1", null),
                                        band(2, "99", null, "2", null)),
                        "open",
                                List.of(
                                        band(1, "0", null, "1", null),
                                        band(2, "100", null, "2", null)),
                        "reversed", List.of(band(1, "100", "50", "1", null)),
                        "zero width", List.of(band(1, "100", "100", "1", null)),
                        "gap in one qualifier",
                                List.of(
                                        band(1, "0", "100", "1", "A"),
                                        band(2, "100", null, "2", "A"),
                                        band(1, "0", "100", "1", "B"),
                                        band(2, "150", null, "2", "B")));
        for (Map.Entry<String, List<Band>> e : refused.entrySet()) {
            assertThatThrownBy(() -> SlabBands.validate(e.getValue()))
                    .as(e.getKey())
                    .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        }
        assertThatThrownBy(() -> SlabBands.validate(List.of()))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        assertThatThrownBy(() -> SlabBands.validate(null))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        assertThatThrownBy(
                        () ->
                                SlabBands.validate(
                                        List.of(new Band(1, n("0"), null, null, null, null))))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        assertThatThrownBy(
                        () ->
                                SlabBands.validate(
                                        List.of(new Band(1, null, null, n("1"), null, null))))
                .satisfies(t -> assertThat(code(t)).isEqualTo(ApiErrorCode.RULE_SLAB_INVALID));
        // The fault names the bands and their qualifier.
        assertThatThrownBy(
                        () ->
                                SlabBands.validate(
                                        List.of(
                                                band(1, "0", "100", "1", "CITY-X"),
                                                band(2, "120", null, "2", "CITY-X"))))
                .satisfies(
                        t ->
                                assertThat(((FciException) t).getMessageArgs()[0])
                                        .asString()
                                        .contains("CITY-X"));
        // A single open-ended band is a valid set.
        SlabBands.validate(List.of(band(1, "0", null, "1", null)));
    }

    @Test
    void SRS_19_5_bandNumbersAreUniqueInTheVersionAcrossQualifiersAndFitTheColumn() {
        // uk_rule_slab_band (rule_version_id, band_order): numbering each qualifier from 1
        // collides.
        assertThatThrownBy(
                        () ->
                                SlabBands.validate(
                                        List.of(
                                                band(1, "0", null, "1", "CITY-X"),
                                                band(1, "0", null, "2", "CITY-Y"))))
                .satisfies(
                        t ->
                                assertThat(((FciException) t).getMessageArgs()[0])
                                        .isEqualTo("band order 1 is used twice"));
        // band_order NUMBER(4): 1 to 9999, both ends included.
        SlabBands.validate(
                List.of(
                        band(1, "0", "10", "1", null),
                        band(SlabBands.MAX_ORDER, "10", null, "2", null)));
        for (int outside : List.of(0, -1, SlabBands.MAX_ORDER + 1)) {
            assertThatThrownBy(
                            () -> SlabBands.validate(List.of(band(outside, "0", null, "1", null))))
                    .as(String.valueOf(outside))
                    .satisfies(
                            t ->
                                    assertThat(((FciException) t).getMessageArgs()[0])
                                            .isEqualTo(
                                                    "band order "
                                                            + outside
                                                            + " is outside 1 to "
                                                            + SlabBands.MAX_ORDER));
        }
        // Distinct numbers across qualifiers are a valid pair of sets.
        SlabBands.validate(
                List.of(band(1, "0", null, "1", "CITY-X"), band(2, "0", null, "2", "CITY-Y")));
    }

    // ---------------------------------------------------------------- SRS-19-11 derived status

    // OPEN-QUESTION CLR-127 (BMAD TR-013, KB CF-28): SRS-19-11 gives four statuses; REJECTED and
    // APPROVED_FUTURE stay as built until FCI gives their display within the four.
    @Test
    void SRS_19_11_theStatusIsDerivedFromTheApprovalStateAndTheDatesNeverStored() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        LocalDate from = LocalDate.of(2026, 4, 1);
        assertThat(RuleVersionStatus.of("DRAFT", from, null, day))
                .isEqualTo(RuleVersionStatus.DRAFT);
        assertThat(RuleVersionStatus.of("PENDING_APPROVAL", from, null, day))
                .isEqualTo(RuleVersionStatus.PENDING_APPROVAL);
        assertThat(RuleVersionStatus.of("REJECTED", from, null, day))
                .isEqualTo(RuleVersionStatus.REJECTED);
        assertThat(RuleVersionStatus.of("APPROVED", from, null, day))
                .isEqualTo(RuleVersionStatus.ACTIVE);
        assertThat(RuleVersionStatus.of("APPROVED", day, null, day))
                .isEqualTo(RuleVersionStatus.ACTIVE);
        assertThat(RuleVersionStatus.of("APPROVED", from, day, day))
                .isEqualTo(RuleVersionStatus.ACTIVE);
        assertThat(RuleVersionStatus.of("APPROVED", from, day.minusDays(1), day))
                .isEqualTo(RuleVersionStatus.SUPERSEDED);
        assertThat(RuleVersionStatus.of("APPROVED", day.plusDays(1), null, day))
                .isEqualTo(RuleVersionStatus.APPROVED_FUTURE);
        assertThat(RuleVersionStatus.inForce("APPROVED", from, null, day)).isTrue();
        assertThat(RuleVersionStatus.inForce("APPROVED", day.plusDays(1), null, day)).isFalse();
        assertThat(RuleVersionStatus.inForce("PENDING_APPROVAL", from, null, day)).isFalse();
        assertThatThrownBy(() -> RuleVersionStatus.of("ACTIVE", from, null, day))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
