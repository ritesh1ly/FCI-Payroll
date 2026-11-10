package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.employee.business.FlagRules.Version;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M-01 allowance flag rules (plan 16 phase 16d-2; SRS-1-11, BR-EMPM-004 / 005 / 006). */
class FlagRulesTest {

    private static final LocalDate JOINED = LocalDate.of(2010, 6, 1);
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);

    // SC-16-0025 (a Payroll-owned field changed by a request with its value, reason and date),
    // SC-16-0140 (allowance flags with effective date and reason).
    @Test
    void BR_EMPM_005_aProposalNamesAKnownFlagYOrNADateFromJoiningAndAReason() {
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, "Doctor posted")).isEmpty();
        assertThat(FlagRules.defect(true, "N", JOINED, JOINED, "x"))
                .as("on the joining day")
                .isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, null, "x")).isEmpty();
        assertThat(FlagRules.defect(false, "Y", APR, JOINED, "x")).contains("UNKNOWN_FLAG");
        assertThat(FlagRules.defect(true, "y", APR, JOINED, "x")).contains("VALUE");
        assertThat(FlagRules.defect(true, null, APR, JOINED, "x")).contains("VALUE");
        assertThat(FlagRules.defect(true, "Y", null, JOINED, "x")).contains("NO_DATE");
        assertThat(FlagRules.defect(true, "Y", JOINED.minusDays(1), JOINED, "x"))
                .contains("BEFORE_JOINING");
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, " ")).contains("NO_REASON");
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, null)).contains("NO_REASON");
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, "r".repeat(500))).isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, " " + "r".repeat(500) + " ")).isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, JOINED, "r".repeat(501)))
                .contains("REASON_TOO_LONG");
    }

    // US-GAP-39 (M-01 Payroll Flags row): an end date, when given, is not before the effective
    // date.
    @Test
    void BR_EMPM_004_anEndDateIsOptionalAndNeverBeforeTheEffectiveDate() {
        assertThat(FlagRules.defect(true, "Y", APR, null, JOINED, "x")).isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, APR, JOINED, "x")).as("one day").isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, APR.plusMonths(3), JOINED, "x")).isEmpty();
        assertThat(FlagRules.defect(true, "Y", APR, APR.minusDays(1), JOINED, "x"))
                .contains("END_BEFORE_START");
        assertThat(FlagRules.defect(true, "Y", null, APR, JOINED, "x"))
                .as("no start date is reported first")
                .contains("NO_DATE");
    }

    @Test
    void BR_EMPM_004_theValueOnADayIsTheLatestVersionEffectiveThen() {
        List<Version> versions =
                List.of(
                        new Version("NPA", "Y", APR, null),
                        new Version("NPA", "N", APR.plusMonths(3), null),
                        new Version("CCA", "Y", APR, APR.plusMonths(1).minusDays(1)),
                        new Version("TRANSPORT", "Y", APR.plusMonths(6), null));
        assertThat(FlagRules.valueOn(versions, "NPA", APR.minusDays(1))).isEqualTo("N");
        assertThat(FlagRules.valueOn(versions, "NPA", APR)).isEqualTo("Y");
        assertThat(FlagRules.valueOn(versions, "NPA", APR.plusMonths(3))).isEqualTo("N");
        assertThat(FlagRules.valueOn(versions, "CCA", APR.plusDays(10))).isEqualTo("Y");
        assertThat(FlagRules.valueOn(versions, "CCA", APR.plusMonths(1)))
                .as("ended")
                .isEqualTo("N");
        assertThat(FlagRules.valueOn(versions, "CCA", APR.plusMonths(1).minusDays(1)))
                .isEqualTo("Y");
        assertThat(FlagRules.valueOn(versions, "TRANSPORT", APR)).as("not yet").isEqualTo("N");
        assertThat(FlagRules.valueOn(versions, "WASHING", APR)).isEqualTo("N");
        assertThat(FlagRules.changes(versions, "NPA", APR.plusMonths(1), "Y")).isFalse();
        assertThat(FlagRules.changes(versions, "NPA", APR.plusMonths(1), "N")).isTrue();
        assertThat(FlagRules.changes(List.of(), "PLI", APR, "N")).as("absent is N").isFalse();
    }
}
