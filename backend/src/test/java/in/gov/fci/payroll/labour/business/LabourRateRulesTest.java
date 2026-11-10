package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.LabourRateRules.Rate;
import in.gov.fci.payroll.labour.business.LabourRateRules.Settings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M-05 Labour Rate Tables (plan 21 phase 21c; SRS-4-11, SRS-4-2, M-05 field table): every
 * designation's day rate is mandatory, the settings optional and well formed, the set dated after
 * the latest one.
 */
class LabourRateRulesTest {

    private static final Set<Long> DESIGNATIONS = Set.of(31L, 32L);
    private static final Settings NONE = new Settings(null, null, null, null);

    private static List<Rate> rates(String... mgw) {
        return Arrays.asList(
                new Rate(31L, mgw.length > 0 ? new BigDecimal(mgw[0]) : null),
                new Rate(32L, mgw.length > 1 ? new BigDecimal(mgw[1]) : null));
    }

    // SC-21-0011
    @Test
    void SRS_4_11_everyDesignationOfThePayrollHasItsDayRate() {
        assertThat(LabourRateRules.defect(rates("799", "781"), DESIGNATIONS, NONE, "Seed"))
                .isEmpty();
        assertThat(LabourRateRules.defect(null, DESIGNATIONS, NONE, "r")).contains("RATES");
        assertThat(LabourRateRules.defect(List.of(), DESIGNATIONS, NONE, "r")).contains("RATES");
        assertThat(LabourRateRules.defect(rates("799", "781"), Set.of(), NONE, "r"))
                .contains("RATES");
        assertThat(
                        LabourRateRules.defect(
                                List.of(new Rate(31L, BigDecimal.ONE)), DESIGNATIONS, NONE, "r"))
                .as("a designation without its rate")
                .contains("RATES");
        assertThat(
                        LabourRateRules.defect(
                                List.of(
                                        new Rate(31L, BigDecimal.ONE),
                                        new Rate(99L, BigDecimal.ONE)),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.defect(
                                List.of(
                                        new Rate(31L, BigDecimal.ONE),
                                        new Rate(31L, BigDecimal.ONE)),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.defect(
                                Arrays.asList(new Rate(null, BigDecimal.ONE), null),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.defect(
                                Arrays.asList(new Rate(31L, BigDecimal.ONE), null),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(LabourRateRules.defect(rates("0", "1"), DESIGNATIONS, NONE, "r"))
                .contains("MGW");
        assertThat(LabourRateRules.defect(rates("1"), DESIGNATIONS, NONE, "r")).contains("MGW");
        assertThat(LabourRateRules.defect(rates("1.005", "1"), DESIGNATIONS, NONE, "r"))
                .contains("MGW");
        assertThat(LabourRateRules.defect(rates("10000000000", "1"), DESIGNATIONS, NONE, "r"))
                .contains("MGW");
        assertThat(LabourRateRules.defect(rates("9999999999.99", "0.01"), DESIGNATIONS, NONE, "r"))
                .as("the bounds of NUMBER(12,2)")
                .isEmpty();
        assertThat(LabourRateRules.defect(rates("1", "1"), DESIGNATIONS, null, "r"))
                .contains("SETTINGS");
    }

    // SC-21-0024 ... SC-21-0026: Interim Relief and the GoI rate are effective-dated settings.
    @Test
    void SRS_4_2_theInterimReliefTheGoiRateWithItsSourceAndTheGrainAreWellFormed() {
        List<Rate> ok = rates("799", "781");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(
                                        BigDecimal.ZERO,
                                        new BigDecimal("900"),
                                        " GoI CLC ",
                                        "PER_DAY"),
                                "r"))
                .isEmpty();
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(new BigDecimal("-1"), null, null, null),
                                "r"))
                .contains("INTERIM_RELIEF");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, BigDecimal.ZERO, "GoI", null),
                                "r"))
                .contains("GOI_RATE");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, BigDecimal.TEN, null, null),
                                "r"))
                .as("a GoI rate needs its source")
                .contains("GOI_SOURCE");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, BigDecimal.TEN, " ", null),
                                "r"))
                .contains("GOI_SOURCE");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, null, "s".repeat(201), null),
                                "r"))
                .contains("GOI_SOURCE");
        assertThat(
                        LabourRateRules.defect(
                                ok, DESIGNATIONS, new Settings(null, null, "दर", null), "r"))
                .contains("GOI_SOURCE");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, null, "s".repeat(200), null),
                                "r"))
                .as("a source of 200 characters")
                .isEmpty();
        assertThat(
                        LabourRateRules.defect(
                                ok, DESIGNATIONS, new Settings(null, null, " ", null), "r"))
                .as("a blank source without a rate is no source")
                .isEmpty();
        assertThat(
                        LabourRateRules.defect(
                                ok, DESIGNATIONS, new Settings(null, null, null, "WEEKLY"), "r"))
                .contains("GRAIN");
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, " ")).contains("REASON");
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, null)).contains("REASON");
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, "r".repeat(501)))
                .contains("REASON");
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, "r".repeat(500))).isEmpty();
    }

    @Test
    void SRS_4_11_aNewSetStartsAfterTheLatestOneAndMayBeRetrospective() {
        LocalDate latest = LocalDate.of(2023, 10, 1);
        assertThat(LabourRateRules.effectiveDefect(null, latest)).contains("EFFECTIVE_FROM");
        // BMAD TR-057: SRS-5-2 / SRS-4-11 refuse an earlier date; a set dated the same day as the
        // latest is not stated (CLR-143). As built it is refused; kept until FCI answers.
        assertThat(LabourRateRules.effectiveDefect(latest, latest))
                .as("same day as the latest, as built (CLR-143)")
                .contains("EFFECTIVE_BEFORE_LATEST");
        assertThat(LabourRateRules.effectiveDefect(latest.minusDays(1), latest))
                .contains("EFFECTIVE_BEFORE_LATEST");
        assertThat(LabourRateRules.effectiveDefect(latest.plusDays(1), latest)).isEmpty();
        assertThat(LabourRateRules.effectiveDefect(LocalDate.of(2020, 1, 1), null)).isEmpty();
    }

    private static Rate dept(long id, String basic, String da) {
        return new Rate(
                id,
                null,
                basic == null ? null : new BigDecimal(basic),
                da == null ? null : new BigDecimal(da));
    }

    /** SRS-3-13 (US-GAP-61): every Departmental designation's Basic wage and DA, nothing else. */
    @Test
    void SRS_3_13_everyDepartmentalDesignationHasItsBasicWageAndDa() {
        List<Rate> ok = List.of(dept(31L, "520.50", "0"), dept(32L, "500", "110.25"));
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, NONE, "Revision")).isEmpty();
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, null, "Revision")).isEmpty();
        assertThat(LabourRateRules.deptDefect(null, DESIGNATIONS, NONE, "r")).contains("RATES");
        assertThat(LabourRateRules.deptDefect(List.of(), DESIGNATIONS, NONE, "r"))
                .contains("RATES");
        assertThat(LabourRateRules.deptDefect(ok, Set.of(), NONE, "r")).contains("RATES");
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(dept(31L, "1", "1")), DESIGNATIONS, NONE, "r"))
                .contains("RATES");
        assertThat(
                        LabourRateRules.deptDefect(
                                Arrays.asList(dept(31L, "1", "1"), null), DESIGNATIONS, NONE, "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(
                                        dept(31L, "1", "1"),
                                        new Rate(null, null, BigDecimal.ONE, BigDecimal.ONE)),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(dept(31L, "1", "1"), dept(99L, "1", "1")),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(dept(31L, "1", "1"), dept(31L, "1", "1")),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("DESIGNATION");
    }

    @Test
    void SRS_3_13_aBasicWageIsPositiveAndTheDaZeroOrMoreWithinTheColumn() {
        for (String bad : new String[] {null, "0", "-1", "1.005", "10000000000.00"}) {
            assertThat(
                            LabourRateRules.deptDefect(
                                    List.of(dept(31L, bad, "1"), dept(32L, "1", "1")),
                                    DESIGNATIONS,
                                    NONE,
                                    "r"))
                    .as("basic %s", bad)
                    .contains("BASIC_WAGE");
        }
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(dept(31L, "9999999999.99", "0"), dept(32L, "0.01", "0")),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .isEmpty();
        for (String bad : new String[] {null, "-0.01", "1.001"}) {
            assertThat(
                            LabourRateRules.deptDefect(
                                    List.of(dept(31L, "1", bad), dept(32L, "1", "1")),
                                    DESIGNATIONS,
                                    NONE,
                                    "r"))
                    .as("da %s", bad)
                    .contains("DA");
        }
    }

    @Test
    void SRS_3_13_theDpsSettingsAreNotPartOfADepartmentalSet() {
        List<Rate> ok = List.of(dept(31L, "1", "1"), dept(32L, "1", "1"));
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(
                                        new Rate(
                                                31L,
                                                BigDecimal.ONE,
                                                BigDecimal.ONE,
                                                BigDecimal.ONE),
                                        dept(32L, "1", "1")),
                                DESIGNATIONS,
                                NONE,
                                "r"))
                .contains("SETTINGS");
        for (Settings s :
                List.of(
                        new Settings(BigDecimal.ZERO, null, null, null),
                        new Settings(null, BigDecimal.ONE, null, null),
                        new Settings(null, null, "GoI", null),
                        new Settings(null, null, null, "PER_DAY"))) {
            assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, s, "r")).contains("SETTINGS");
        }
        assertThat(
                        LabourRateRules.deptDefect(
                                ok, DESIGNATIONS, new Settings(null, null, " ", null), "r"))
                .isEmpty();
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, NONE, " ")).contains("REASON");
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, NONE, null)).contains("REASON");
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, NONE, "x".repeat(500))).isEmpty();
        assertThat(LabourRateRules.deptDefect(ok, DESIGNATIONS, NONE, "x".repeat(501)))
                .contains("REASON");
    }

    /** R-ASCII: the M-05 reason and GoI rate source are text areas: line breaks kept. */
    @Test
    void R_ASCII_theReasonAndTheGoiSourceOfATextAreaKeepTheirLineBreaks() {
        List<Rate> ok = rates("799", "781");
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, "Revision\r\nper order"))
                .isEmpty();
        assertThat(LabourRateRules.defect(ok, DESIGNATIONS, NONE, "Revision\u0007"))
                .contains("REASON");
        assertThat(
                        LabourRateRules.defect(
                                ok,
                                DESIGNATIONS,
                                new Settings(null, new BigDecimal("900"), "Gazette\nS.O. 12", null),
                                "r"))
                .isEmpty();
        assertThat(
                        LabourRateRules.deptDefect(
                                List.of(dept(31L, "1", "1"), dept(32L, "1", "1")),
                                DESIGNATIONS,
                                NONE,
                                "a\nb"))
                .isEmpty();
    }
}
