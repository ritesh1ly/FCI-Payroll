package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.RuleEngine.Band;
import in.gov.fci.payroll.configuration.api.RuleEngine.Context;
import in.gov.fci.payroll.configuration.api.RuleEngine.Pinned;
import in.gov.fci.payroll.configuration.api.RuleEngine.Resolved;
import in.gov.fci.payroll.configuration.business.RuleFormula;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The rule set a run computes with (BR-CYC-011 … 017; SRS-19-3, SRS-19-13; KB RE-7, RE-10): each
 * rule answered as {@link RuleEngine#resolve} answers it, the hash a function of the versions
 * alone, and a formula parsed once against the grammar taken with the set.
 */
class RuleSetTest {

    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);
    private static final Function<Long, Set<Long>> TREE =
            office -> office == 5L ? Set.of(5L, 6L) : Set.of(office);

    private final AtomicInteger grammarReads = new AtomicInteger();
    private final Supplier<RuleFormula.Grammar> grammar =
            () -> {
                grammarReads.incrementAndGet();
                return new RuleFormula.Grammar(Set.of("*", ">="), Map.of(), Set.of("BASIC"));
            };

    private static Resolved version(
            String key,
            String type,
            long id,
            int no,
            String scalar,
            List<Band> bands,
            String formula) {
        return new Resolved(
                key,
                type,
                id,
                no,
                OCT.minusMonths(6),
                null,
                scalar == null ? null : new BigDecimal(scalar),
                bands,
                formula,
                null,
                false);
    }

    private static final Band LOW =
            new Band(1, BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ONE, null, null);
    private static final Band HIGH =
            new Band(2, new BigDecimal("100"), null, BigDecimal.TEN, null, null);

    private RuleSet set() {
        return new RuleSet(
                OCT,
                List.of(
                        new RuleSet.Entry(
                                "A.RATE",
                                null,
                                null,
                                version("A.RATE", "RATE", 11, 3, "12.5", null, null)),
                        new RuleSet.Entry(
                                "B.SLAB",
                                "STAFF",
                                null,
                                version("B.SLAB", "SLAB", 12, 1, null, List.of(LOW, HIGH), null)),
                        new RuleSet.Entry(
                                "C.FORMULA",
                                null,
                                5L,
                                version("C.FORMULA", "FORMULA", 13, 2, null, null, "BASIC * 2")),
                        new RuleSet.Entry(
                                "D.ELIGIBLE",
                                null,
                                null,
                                version(
                                        "D.ELIGIBLE",
                                        "ELIGIBILITY",
                                        14,
                                        1,
                                        null,
                                        null,
                                        "BASIC >= 18000")),
                        new RuleSet.Entry("E.NONE", null, null, null),
                        new RuleSet.Entry(
                                "F.EMPTY",
                                null,
                                null,
                                version("F.EMPTY", "FORMULA", 15, 1, null, null, " "))),
                grammar,
                TREE);
    }

    private static void refused(Runnable call, ApiErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FciException.class)
                .satisfies(t -> assertThat(((FciException) t).getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_19_3_aRuleIsAnsweredWithTheVersionTheSetHolds() {
        RuleSet set = set();
        assertThat(set.asOf()).isEqualTo(OCT);
        assertThat(set.resolve("A.RATE", Context.unscoped()))
                .hasValueSatisfying(r -> assertThat(r.scalar()).isEqualByComparingTo("12.5"));
        // R-RULE: unknown, or in scope without a version: refused, never a default.
        assertThatThrownBy(() -> set.resolve("Z.UNKNOWN", Context.unscoped()))
                .satisfies(
                        t -> {
                            assertThat(((FciException) t).getErrorCode())
                                    .isEqualTo(ApiErrorCode.RULE_NOT_CONFIGURED);
                            assertThat(((FciException) t).getMessageArgs())
                                    .containsExactly("Z.UNKNOWN", OCT);
                        });
        refused(() -> set.resolve("E.NONE", Context.unscoped()), ApiErrorCode.RULE_NOT_CONFIGURED);
        assertThatThrownBy(() -> set.resolve("A.RATE", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void RE_10_outsideItsScopeARuleAnswersEmptyEvenWithoutAVersion() {
        RuleSet set = set();
        assertThat(set.band("B.SLAB", new Context("STAFF", null), null, BigDecimal.ONE))
                .contains(LOW);
        assertThat(set.band("B.SLAB", new Context("DPS_LABOUR", null), null, BigDecimal.ONE))
                .isEmpty();
        assertThat(set.band("B.SLAB", Context.unscoped(), null, BigDecimal.ONE)).isEmpty();
        assertThat(
                        set.evaluate(
                                "C.FORMULA",
                                new Context(null, 6L),
                                Map.of("BASIC", BigDecimal.TEN)))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("20"));
        assertThat(
                        set.evaluate(
                                "C.FORMULA",
                                new Context(null, 7L),
                                Map.of("BASIC", BigDecimal.TEN)))
                .isEmpty();
        assertThat(set.condition("C.FORMULA", Context.unscoped(), Map.of())).isEmpty();
    }

    // OPEN-QUESTION CLR-128 (BMAD TR-014): boundary ownership as built until FCI answers.
    @Test
    void SRS_19_5_aSlabAnswersTheBandCoveringTheAmountWithTheBoundaryInTheUpperBand() {
        RuleSet set = set();
        Context staff = new Context("STAFF", null);
        assertThat(set.band("B.SLAB", staff, null, new BigDecimal("99.99"))).contains(LOW);
        assertThat(set.band("B.SLAB", staff, null, new BigDecimal("100"))).contains(HIGH);
        assertThat(set.band("B.SLAB", staff, null, new BigDecimal("-1"))).isEmpty();
        assertThat(set.band("B.SLAB", staff, "CITY-X", BigDecimal.ONE)).isEmpty();
    }

    @Test
    void SRS_19_15_aFormulaIsParsedOnceAgainstTheGrammarTakenWithTheSet() {
        RuleSet set = set();
        assertThat(grammarReads).hasValue(0);
        assertThat(
                        set.condition(
                                "D.ELIGIBLE",
                                Context.unscoped(),
                                Map.of("BASIC", new BigDecimal("18000"))))
                .contains(true);
        assertThat(
                        set.condition(
                                "D.ELIGIBLE",
                                Context.unscoped(),
                                Map.of("BASIC", new BigDecimal("17999.99"))))
                .contains(false);
        assertThat(grammarReads).hasValue(1);
        set.evaluate("C.FORMULA", new Context(null, 5L), Map.of("BASIC", BigDecimal.ONE));
        set.evaluate("C.FORMULA", new Context(null, 5L), Map.of("BASIC", BigDecimal.ONE));
        assertThat(grammarReads).hasValue(2);
        // A version without a formula, a number where a condition is needed: refused.
        refused(
                () -> set.evaluate("F.EMPTY", Context.unscoped(), Map.of()),
                ApiErrorCode.RULE_FORMULA_INVALID);
        refused(
                () -> set.evaluate("A.RATE", Context.unscoped(), Map.of()),
                ApiErrorCode.RULE_FORMULA_INVALID);
        refused(
                () ->
                        set.condition(
                                "C.FORMULA",
                                new Context(null, 5L),
                                Map.of("BASIC", BigDecimal.ONE)),
                ApiErrorCode.RULE_FORMULA_EVALUATION);
    }

    @Test
    void BR_CYC_011_theHashIsAFunctionOfTheVersionsAloneInKeyOrder() {
        RuleSet set = set();
        // E.NONE has no version: it is not pinned.
        assertThat(set.versions())
                .containsExactly(
                        new Pinned("A.RATE", 11L, 3),
                        new Pinned("B.SLAB", 12L, 1),
                        new Pinned("C.FORMULA", 13L, 2),
                        new Pinned("D.ELIGIBLE", 14L, 1),
                        new Pinned("F.EMPTY", 15L, 1));
        assertThat(set.hash())
                .isEqualTo(
                        SecureTokens.sha256Hex(
                                "A.RATE|11|3\n"
                                        + "B.SLAB|12|1\n"
                                        + "C.FORMULA|13|2\n"
                                        + "D.ELIGIBLE|14|1\n"
                                        + "F.EMPTY|15|1"));
        assertThat(set.versionSet())
                .isEqualTo(new RuleEngine.VersionSet(OCT, set.versions(), set.hash()));
        // The same versions in another order: the same hash; another version: another hash.
        RuleSet reordered =
                new RuleSet(
                        OCT,
                        List.of(
                                new RuleSet.Entry(
                                        "B.SLAB",
                                        null,
                                        null,
                                        version("B.SLAB", "SLAB", 12, 1, null, null, null)),
                                new RuleSet.Entry(
                                        "A.RATE",
                                        null,
                                        null,
                                        version("A.RATE", "RATE", 11, 3, "1", null, null))),
                        grammar,
                        TREE);
        RuleSet same =
                new RuleSet(
                        OCT,
                        List.of(
                                new RuleSet.Entry(
                                        "A.RATE",
                                        null,
                                        null,
                                        version("A.RATE", "RATE", 11, 3, "1", null, null)),
                                new RuleSet.Entry(
                                        "B.SLAB",
                                        null,
                                        null,
                                        version("B.SLAB", "SLAB", 12, 1, null, null, null))),
                        grammar,
                        TREE);
        RuleSet other =
                new RuleSet(
                        OCT,
                        List.of(
                                new RuleSet.Entry(
                                        "A.RATE",
                                        null,
                                        null,
                                        version("A.RATE", "RATE", 16, 4, "1", null, null)),
                                new RuleSet.Entry(
                                        "B.SLAB",
                                        null,
                                        null,
                                        version("B.SLAB", "SLAB", 12, 1, null, null, null))),
                        grammar,
                        TREE);
        assertThat(reordered.hash()).isEqualTo(same.hash()).isNotEqualTo(other.hash());
        // No versions at all: the hash of the empty text.
        assertThat(new RuleSet(OCT, List.of(), grammar, TREE).hash())
                .isEqualTo(SecureTokens.sha256Hex(""));
        assertThatThrownBy(() -> new RuleSet(null, List.of(), grammar, TREE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_19_16_onlyAnApprovalStartingBeforeTheDecisionDayIsRetrospective() {
        LocalDate decided = OCT;
        RuleVersionChanged backDated =
                new RuleVersionChanged(
                        RuleVersionChanged.Kind.APPROVED,
                        "K",
                        "RATE",
                        1L,
                        2,
                        OCT.minusDays(1),
                        null,
                        decided);
        RuleVersionChanged sameDay =
                new RuleVersionChanged(
                        RuleVersionChanged.Kind.APPROVED, "K", "RATE", 1L, 2, OCT, null, decided);
        RuleVersionChanged ended =
                new RuleVersionChanged(
                        RuleVersionChanged.Kind.ENDED,
                        "K",
                        "RATE",
                        1L,
                        2,
                        OCT.minusMonths(3),
                        OCT,
                        decided);
        assertThat(backDated.retrospective()).isTrue();
        assertThat(sameDay.retrospective()).isFalse();
        // An end is never "retrospective": no new version applies to the periods already computed.
        assertThat(ended.retrospective()).isFalse();
        assertThatThrownBy(
                        () -> new RuleVersionChanged(null, "K", "RATE", 1L, 1, OCT, null, decided))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                new RuleVersionChanged(
                                        RuleVersionChanged.Kind.ENDED,
                                        "K",
                                        "RATE",
                                        1L,
                                        1,
                                        OCT,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void BR_CYC_011_theResolvedVersionCannotBeChangedByItsReader() {
        Resolved r = set().resolve("B.SLAB", new Context("STAFF", null)).orElseThrow();
        assertThatThrownBy(() -> r.bands().add(LOW))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(version("X", "RATE", 1, 1, "1", null, null).bands()).isEmpty();
    }
}
