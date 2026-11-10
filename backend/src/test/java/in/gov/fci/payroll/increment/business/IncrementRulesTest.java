package in.gov.fci.payroll.increment.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 17: increment and pay fixation rules (SRS-13-1, SRS-13-5, SRS-13-23, SRS-13-26). */
class IncrementRulesTest {

    private static final List<BigDecimal> STAGES =
            List.of(new BigDecimal("60000"), new BigDecimal("62400"), new BigDecimal("64300"));

    @Test
    void SRS_13_1_theNextIncrementFallsDueAYearOnAndTheDuePeriodIsTheFinancialYear() {
        // BR-EMPR-003: the increment is applied from its due date and the next falls due a year on.
        assertThat(IncrementRules.nextDue(LocalDate.of(2026, 7, 1)))
                .isEqualTo(LocalDate.of(2027, 7, 1));
        assertThat(IncrementRules.duePeriod(LocalDate.of(2026, 7, 1))).isEqualTo("2026-27");
        assertThat(IncrementRules.duePeriod(LocalDate.of(2027, 1, 1))).isEqualTo("2026-27");
        assertThat(IncrementRules.duePeriod(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
    }

    // BR-INCR-001, BR-EMPM-007: no increment or Basic above the scale maximum except a stagnation
    // increment.
    // SC-17-0039: a revised Basic above the scale maximum without stagnation is flagged.
    @Test
    void SRS_13_5_aboveTheScaleMaximumIsFlaggedUnlessStagnation() {
        BigDecimal max = new BigDecimal("64300");
        assertThat(IncrementRules.scale(new BigDecimal("64300"), max, false)).isEmpty();
        assertThat(IncrementRules.scale(new BigDecimal("64400"), max, false))
                .contains(Flag.ABOVE_MAXIMUM);
        assertThat(IncrementRules.scale(new BigDecimal("64400"), max, true)).isEmpty();
        assertThat(IncrementRules.scale(new BigDecimal("64400"), null, false)).isEmpty();
    }

    @Test
    void SRS_13_23_BR_INCR_008_theBasicMustBeAStageAndWithoutStagesTheCheckSaysSo() {
        assertThat(IncrementRules.stage(new BigDecimal("62400"), STAGES)).isEmpty();
        assertThat(IncrementRules.stage(new BigDecimal("62500"), STAGES))
                .contains(Flag.NOT_A_STAGE);
        assertThat(IncrementRules.stage(new BigDecimal("62500"), List.of()))
                .contains(Flag.STAGES_NOT_CONFIGURED);
        assertThat(IncrementRules.stage(new BigDecimal("62500"), null))
                .contains(Flag.STAGES_NOT_CONFIGURED);
        assertThat(IncrementRules.nextStageAbove(new BigDecimal("62400"), STAGES))
                .hasValueSatisfying(s -> assertThat(s).isEqualByComparingTo("64300"));
        assertThat(IncrementRules.nextStageAbove(new BigDecimal("61000"), STAGES))
                .as("off-stage: the next stage above")
                .hasValueSatisfying(s -> assertThat(s).isEqualByComparingTo("62400"));
        assertThat(IncrementRules.nextStageAbove(new BigDecimal("64300"), STAGES)).isEmpty();
    }

    @Test
    void SRS_13_26_theEventNameIsSystemGenerated() {
        assertThat(IncrementRules.eventName("PAY_REVISION", LocalDate.of(2026, 7, 1)))
                .isEqualTo("PAY REVISION w.e.f. 2026-07-01");
    }

    @Test
    void SRS_13_1_theFinancialYearTurnsOnTheFirstOfApril() {
        assertThat(IncrementRules.duePeriod(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(IncrementRules.duePeriod(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(IncrementRules.duePeriod(LocalDate.of(2099, 12, 1))).isEqualTo("2099-00");
        assertThat(IncrementRules.nextDue(LocalDate.of(2028, 2, 29)))
                .isEqualTo(LocalDate.of(2029, 2, 28));
    }

    @Test
    void SRS_13_19_aStagnationIncrementKeepsTheEligibilityTestThatFiredIt() {
        assertThat(IncrementRules.reason("ANNUAL", "2026-27", ""))
                .isEqualTo("ANNUAL increment, due period 2026-27");
        assertThat(IncrementRules.reason("ANNUAL", "2026-27", null))
                .isEqualTo("ANNUAL increment, due period 2026-27");
        assertThat(
                        IncrementRules.reason(
                                "STAGNATION", "2026-27", " At maximum 64300 since 2024-10-01 "))
                .isEqualTo(
                        "STAGNATION increment, due period 2026-27; eligibility: At maximum 64300"
                                + " since 2024-10-01");
        assertThat(IncrementRules.reason("STAGNATION", "2026-27", "x".repeat(600)))
                .hasSize(500)
                .endsWith("...");
    }

    @Test
    void SRS_13_19_aReasonOfExactly500CharactersIsKeptWholeAnd501IsCut() {
        String prefix = "STAGNATION increment, due period 2026-27; eligibility: ";
        String exact = "x".repeat(500 - prefix.length());
        assertThat(IncrementRules.reason("STAGNATION", "2026-27", exact))
                .isEqualTo(prefix + exact)
                .hasSize(500)
                .doesNotEndWith("...");
        assertThat(IncrementRules.reason("STAGNATION", "2026-27", exact + "y"))
                .isEqualTo(prefix + exact.substring(0, exact.length() - 3) + "...")
                .hasSize(500);
    }

    /**
     * SRS-13-24: the old entry of a correction stays, marked as corrected. A Basic correction marks
     * the row in force before it on its date (a same-date row of a lower sequence, or the row
     * covering the date), never a row written after it or dated later; a date correction marks the
     * increment it re-dated; a correction without a row, or naming a row not in the list, marks
     * nothing.
     */
    @Test
    void SRS_13_24_theOldEntryOfACorrectionIsMarkedCorrected() {
        LocalDate jan = LocalDate.of(2026, 1, 1);
        LocalDate jul = LocalDate.of(2026, 7, 1);
        IncrementRules.Entry opening = new IncrementRules.Entry(1, LocalDate.of(2010, 4, 1), 1);
        IncrementRules.Entry july = new IncrementRules.Entry(2, jul, 1);
        IncrementRules.Entry fixJuly = new IncrementRules.Entry(3, jul, 2);
        IncrementRules.Entry fixJan = new IncrementRules.Entry(4, jan, 1);
        IncrementRules.Entry later = new IncrementRules.Entry(5, jan, 2);
        List<IncrementRules.Entry> rows = List.of(opening, july, fixJuly, fixJan, later);
        assertThat(
                        IncrementRules.corrected(
                                rows,
                                List.of(new IncrementRules.Correction("BASIC_PAY", 3L, null))))
                .as("same date: the lower sequence")
                .containsExactly(2L);
        assertThat(
                        IncrementRules.corrected(
                                rows,
                                List.of(new IncrementRules.Correction("BASIC_PAY", 4L, null))))
                .as("the row covering the date, not the later-dated July one")
                .containsExactly(1L);
        assertThat(
                        IncrementRules.corrected(
                                List.of(fixJan, later),
                                List.of(new IncrementRules.Correction("BASIC_PAY", 4L, null))))
                .as("nothing before it: a row written after it is not the old entry")
                .isEmpty();
        assertThat(
                        IncrementRules.corrected(
                                rows,
                                List.of(
                                        new IncrementRules.Correction("INCREMENT_DATE", 4L, 2L),
                                        new IncrementRules.Correction("INCREMENT_DATE", 4L, 99L),
                                        new IncrementRules.Correction("INCREMENT_DATE", null, null),
                                        new IncrementRules.Correction("BASIC_PAY", null, null),
                                        new IncrementRules.Correction("BASIC_PAY", 99L, null))))
                .containsExactly(2L);
        assertThat(IncrementRules.corrected(rows, List.of())).isEmpty();
    }
}
