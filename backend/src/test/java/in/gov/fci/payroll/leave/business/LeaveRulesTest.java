package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.leave.api.LeaveReceipts.Event;
import in.gov.fci.payroll.leave.api.LeaveReceipts.EventType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Plan 19 phase 19a: the checks on leave data received from HRMS. */
class LeaveRulesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate TO = LocalDate.of(2026, 9, 9);

    private static Event event(BigDecimal days, boolean halfDay, String ref, EventType type) {
        return new Event(
                "104882", "EL", FROM, TO, days, halfDay, ref, "REQ-1", "DGM (P)", type, null);
    }

    private static Event event(BigDecimal days) {
        return event(days, false, "SAN/2026/7", EventType.ADDITION);
    }

    @Test
    void SRS_LV_25_aMatchedSanctionedEventWithinItsPeriodIsApplied() {
        assertThat(LeaveRules.eventDefect(event(new BigDecimal("3")), true, true, false)).isEmpty();
        assertThat(LeaveRules.spanDays(FROM, TO)).isEqualTo(3);
    }

    // SC-13-0076: an unmatched leave event or one without its sanction is held, never applied.
    @Test
    void SRS_LV_13_anEventWithoutASanctionOrAMatchIsHeldNeverApplied() {
        // BR-LEAVE-004: only sanctioned, matched leave is applied to pay; the rest is held.
        assertThat(LeaveRules.eventDefect(event(BigDecimal.ONE), false, true, false))
                .contains("MEMBER");
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, false, " ", EventType.ADDITION),
                                true,
                                true,
                                false))
                .contains("NO_SANCTION_REF");
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, false, null, EventType.ADDITION),
                                true,
                                true,
                                false))
                .contains("NO_SANCTION_REF");
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, false, "Sअ", EventType.ADDITION),
                                true,
                                true,
                                false))
                .contains("SANCTION_REF");
        assertThat(LeaveRules.eventDefect(event(BigDecimal.ONE), true, false, false))
                .contains("LEAVE_TYPE");
    }

    @Test
    void SRS_LV_25_thePeriodAndDaysMustFit() {
        Event backwards =
                new Event(
                        "104882",
                        "EL",
                        TO,
                        FROM,
                        BigDecimal.ONE,
                        false,
                        "SAN/1",
                        null,
                        null,
                        EventType.ADDITION,
                        null);
        assertThat(LeaveRules.eventDefect(backwards, true, true, false)).contains("PERIOD");
        Event open =
                new Event(
                        "104882",
                        "EL",
                        FROM,
                        null,
                        BigDecimal.ONE,
                        false,
                        "SAN/1",
                        null,
                        null,
                        EventType.ADDITION,
                        null);
        assertThat(LeaveRules.eventDefect(open, true, true, false)).contains("PERIOD");
        assertThat(LeaveRules.eventDefect(event(new BigDecimal("4")), true, true, false))
                .as("more days than the period")
                .contains("DAYS");
        assertThat(LeaveRules.eventDefect(event(BigDecimal.ZERO), true, true, false))
                .contains("DAYS");
        assertThat(LeaveRules.eventDefect(event(null), true, true, false)).contains("DAYS");
        assertThat(LeaveRules.eventDefect(event(new BigDecimal("1.25")), true, true, true))
                .contains("DAYS");
    }

    @Test
    void SRS_LV_25_aHalfDayOnlyForATypeSanctionedByTheHalfDay() {
        assertThat(LeaveRules.eventDefect(event(new BigDecimal("2.5")), true, true, false))
                .contains("HALF_DAY");
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, true, "SAN/1", EventType.ADDITION),
                                true,
                                true,
                                false))
                .contains("HALF_DAY");
        assertThat(LeaveRules.eventDefect(event(new BigDecimal("2.5")), true, true, true))
                .isEmpty();
    }

    @Test
    void SRS_LV_18_aCancellationOrConversionNamesTheSanctionItActsOn() {
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, false, "SAN/2", EventType.CANCELLATION),
                                true,
                                true,
                                false))
                .contains("EARLIER_SANCTION");
        Event conversion =
                new Event(
                        "104882",
                        "LWP",
                        FROM,
                        TO,
                        BigDecimal.ONE,
                        false,
                        "SAN/2",
                        null,
                        null,
                        EventType.CONVERSION,
                        "SAN/1");
        assertThat(LeaveRules.eventDefect(conversion, true, true, false)).isEmpty();
        // The earlier sanction is a reference like any other: printable, at most 50 characters.
        Event overlongEarlier =
                new Event(
                        "104882",
                        "LWP",
                        FROM,
                        TO,
                        BigDecimal.ONE,
                        false,
                        "SAN/2",
                        null,
                        null,
                        EventType.CONVERSION,
                        "S".repeat(51));
        assertThat(LeaveRules.eventDefect(overlongEarlier, true, true, false))
                .contains("EARLIER_SANCTION");
        Event noStart =
                new Event(
                        "104882",
                        "LWP",
                        null,
                        TO,
                        BigDecimal.ONE,
                        false,
                        "SAN/2",
                        null,
                        null,
                        EventType.ADDITION,
                        null);
        assertThat(LeaveRules.eventDefect(noStart, true, true, false)).contains("PERIOD");
        assertThat(
                        LeaveRules.eventDefect(
                                event(BigDecimal.ONE, false, "SAN/2", null), true, true, false))
                .contains("EVENT_TYPE");
        Event longRequest =
                new Event(
                        "104882",
                        "EL",
                        FROM,
                        TO,
                        BigDecimal.ONE,
                        false,
                        "SAN/2",
                        "R".repeat(61),
                        null,
                        EventType.ADDITION,
                        null);
        assertThat(LeaveRules.eventDefect(longRequest, true, true, false)).contains("REQUEST_ID");
    }

    @Test
    void SRS_LV_21_aBalanceIsNeverNegativeAndHasItsDay() {
        // BR-LEAVE-013: no balance is ever negative.
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThat(LeaveRules.balanceDefect(true, new BigDecimal("12.5"), day, "2026-27"))
                .isEmpty();
        assertThat(LeaveRules.balanceDefect(true, BigDecimal.ZERO, day, null)).isEmpty();
        assertThat(LeaveRules.balanceDefect(false, BigDecimal.ONE, day, null))
                .contains("LEAVE_TYPE");
        assertThat(LeaveRules.balanceDefect(true, new BigDecimal("-1"), day, null))
                .contains("BALANCE");
        assertThat(LeaveRules.balanceDefect(true, null, day, null)).contains("BALANCE");
        assertThat(LeaveRules.balanceDefect(true, new BigDecimal("1000"), day, null))
                .contains("BALANCE");
        assertThat(LeaveRules.balanceDefect(true, BigDecimal.ONE, null, null))
                .contains("AS_OF_DATE");
        assertThat(LeaveRules.balanceDefect(true, BigDecimal.ONE, day, "2026"))
                .contains("FIN_YEAR");
    }

    @Test
    void SRS_LV_24_aLeaveTypeHasAnHrmsCodeAndAPrintableName() {
        assertThat(LeaveRules.typeDefect("EL", "Earned Leave")).isEmpty();
        assertThat(LeaveRules.typeDefect("el", "Earned Leave")).contains("LEAVE_TYPE_CODE");
        assertThat(LeaveRules.typeDefect(null, "Earned Leave")).contains("LEAVE_TYPE_CODE");
        assertThat(LeaveRules.typeDefect("EL", " ")).contains("LEAVE_TYPE_NAME");
        assertThat(LeaveRules.typeDefect("EL", null)).contains("LEAVE_TYPE_NAME");
        assertThat(LeaveRules.typeDefect("EL", "अ")).contains("LEAVE_TYPE_NAME");
    }

    @Test
    void SRS_LV_21_aBalanceOfNoDaysAndOfTheLargestCountIsAccepted() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThat(LeaveRules.balanceDefect(true, BigDecimal.ZERO, day, "2026-27")).isEmpty();
        assertThat(LeaveRules.balanceDefect(true, new BigDecimal("999.99"), day, "2026-27"))
                .isEmpty();
        assertThat(LeaveRules.balanceDefect(true, new BigDecimal("1000"), day, "2026-27"))
                .contains("BALANCE");
    }
}
