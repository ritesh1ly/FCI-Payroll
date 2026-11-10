package in.gov.fci.payroll.employee.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * I-17 life-cycle events and M-11 transfers (target DDL v4.0 S03; SRS-G-36): status effects, the
 * events that fit a status, and the date / range rules with their boundaries.
 */
class EventRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static void event(String type, LocalDate date, String status) {
        EventRules.validateEvent(type, date, null, null, null, null, status, TODAY);
    }

    /**
     * I-17 Effective Date (SRS l.3877, US-GAP-277): "Cannot precede the joining date" - the day
     * before is refused naming both dates, the joining date itself and later pass, and a missing
     * date has nothing to compare.
     */
    @Test
    void I_17_anEventCannotPrecedeTheJoiningDate() {
        LocalDate joined = LocalDate.of(2020, 7, 1);
        assertThatThrownBy(() -> EventRules.requireNotBeforeJoining(joined.minusDays(1), joined))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(
                                            in.gov.fci.payroll.platform.error.ApiErrorCode
                                                    .EMPLOYEE_EVENT_BEFORE_JOINING);
                            assertThat(e.getMessageArgs())
                                    .containsExactly("2020-06-30", "2020-07-01");
                        });
        assertThatCode(() -> EventRules.requireNotBeforeJoining(joined, joined))
                .doesNotThrowAnyException();
        assertThatCode(() -> EventRules.requireNotBeforeJoining(joined.plusDays(1), joined))
                .doesNotThrowAnyException();
        assertThatCode(() -> EventRules.requireNotBeforeJoining(null, joined))
                .doesNotThrowAnyException();
        assertThatCode(() -> EventRules.requireNotBeforeJoining(joined, null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- status effects

    @ParameterizedTest
    @CsvSource({
        "SUSPENSION,SUSPENDED",
        "REVOCATION,IN_SERVICE",
        "REINSTATEMENT,IN_SERVICE",
        "DISMISSAL,DISMISSED",
        "ABSCONDING,ABSCONDING",
        "DEPUTATION_OUT,ON_DEPUTATION",
        "DEPUTATION_IN,IN_SERVICE",
        "SEPARATION,SEPARATED"
    })
    void I_17_aStatusChangingEventSetsItsEmploymentStatus(String type, String status) {
        assertThat(EventRules.statusAfter(type)).contains(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALARY_RELEASE", "SALARY_STOP", "HOLD", "UNKNOWN"})
    void I_17_payOnlyEventsSetNoStatus(String type) {
        assertThat(EventRules.statusAfter(type)).isEmpty();
        assertThat(EventRules.statusAfter(null)).isEmpty();
    }

    @Test
    void SRS_G_36_onlySeparationAndDismissalEndService() {
        assertThat(EventRules.ENDS_SERVICE).containsExactlyInAnyOrder("SEPARATION", "DISMISSAL");
        assertThat(EventRules.EVENT_TYPES).hasSize(11).contains("HOLD", "SALARY_RELEASE");
        assertThat(EventRules.SEPARATED_STATUSES)
                .containsExactlyInAnyOrder("SEPARATED", "DISMISSED");
    }

    // ---------------------------------------------------------------- events: basic validation

    @Test
    void I_17_aKnownTypeAndADateAreRequired() {
        refused(() -> event(null, TODAY, "IN_SERVICE"), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> event("PROMOTION", TODAY, "IN_SERVICE"), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> event("HOLD", null, "IN_SERVICE"), CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void I_17_anEndDateMayEqualButNotPrecedeTheEventDate() {
        assertThatCode(
                        () ->
                                EventRules.validateEvent(
                                        "HOLD",
                                        TODAY,
                                        TODAY,
                                        null,
                                        null,
                                        null,
                                        "IN_SERVICE",
                                        TODAY))
                .doesNotThrowAnyException();
        refused(
                () ->
                        EventRules.validateEvent(
                                "HOLD",
                                TODAY,
                                TODAY.minusDays(1),
                                null,
                                null,
                                null,
                                "IN_SERVICE",
                                TODAY),
                CommonErrorCode.VALIDATION_FAILED);
    }

    // SC-16-0186: the subsistence percentage as stated in the order.
    @ParameterizedTest
    @ValueSource(strings = {"0", "50", "100", "75.5"})
    void I_17_subsistenceFrom0To100PercentIsAccepted(String pct) {
        assertThatCode(
                        () ->
                                EventRules.validateEvent(
                                        "SUSPENSION",
                                        TODAY,
                                        null,
                                        new BigDecimal(pct),
                                        null,
                                        null,
                                        "IN_SERVICE",
                                        TODAY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "100.01", "-100"})
    void I_17_subsistenceOutside0To100IsRefused(String pct) {
        refused(
                () ->
                        EventRules.validateEvent(
                                "SUSPENSION",
                                TODAY,
                                null,
                                new BigDecimal(pct),
                                null,
                                null,
                                "IN_SERVICE",
                                TODAY),
                CommonErrorCode.VALIDATION_FAILED);
    }

    // SC-16-0187: the salary release year-month range.
    @ParameterizedTest
    @CsvSource(
            value = {
                "202601,202612",
                "202601,202601",
                "NULL,202605",
                "202605,NULL",
                "'',''",
                "' ',202605",
                "202605,' '"
            },
            nullValues = "NULL")
    void I_17_aSalaryReleaseRangeIsYyyymmFromNotAfterTo(String from, String to) {
        assertThatCode(
                        () ->
                                EventRules.validateEvent(
                                        "SALARY_RELEASE",
                                        TODAY,
                                        null,
                                        null,
                                        from,
                                        to,
                                        "IN_SERVICE",
                                        TODAY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "202612,202601",
                "202613,NULL",
                "202600,NULL",
                "NULL,199912",
                "NULL,2026-01",
                "NULL,20261",
                "21000101,NULL"
            },
            nullValues = "NULL")
    void I_17_aMalformedOrInvertedReleaseRangeIsRefused(String from, String to) {
        refused(
                () ->
                        EventRules.validateEvent(
                                "SALARY_RELEASE", TODAY, null, null, from, to, "IN_SERVICE", TODAY),
                CommonErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- events: dates

    @Test
    void I_17_aStatusChangingEventMayNotBeFutureDated() {
        assertThatCode(() -> event("SUSPENSION", TODAY, "IN_SERVICE")).doesNotThrowAnyException();
        refused(
                () -> event("SUSPENSION", TODAY.plusDays(1), "IN_SERVICE"),
                ApiErrorCode.EMPLOYEE_FUTURE_DATE);
        refused(
                () -> event("SEPARATION", TODAY.plusDays(30), "IN_SERVICE"),
                ApiErrorCode.EMPLOYEE_FUTURE_DATE);
    }

    @Test
    void I_17_aPayOnlyEventMayBeFutureDated() {
        assertThatCode(() -> event("HOLD", TODAY.plusDays(10), "IN_SERVICE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> event("SALARY_STOP", TODAY.plusYears(1), "IN_SERVICE"))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- events: fit the status

    @Test
    void I_17_aRevocationOnlyEndsASuspension() {
        assertThatCode(() -> event("REVOCATION", TODAY, "SUSPENDED")).doesNotThrowAnyException();
        refused(
                () -> event("REVOCATION", TODAY, "IN_SERVICE"),
                ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED);
        refused(() -> event("REVOCATION", TODAY, null), ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED);
    }

    @Test
    void I_17_aDeputationInOnlyEndsADeputationOut() {
        assertThatCode(() -> event("DEPUTATION_IN", TODAY, "ON_DEPUTATION"))
                .doesNotThrowAnyException();
        refused(
                () -> event("DEPUTATION_IN", TODAY, "IN_SERVICE"),
                ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEPARATED", "DISMISSED", "IN_SERVICE", "SUSPENDED"})
    void I_17_aReinstatementFitsAnyStatus(String status) {
        assertThatCode(() -> event("REINSTATEMENT", TODAY, status)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEPARATED", "DISMISSED"})
    void I_17_afterSeparationOnlyAReinstatementMayBeRecorded(String status) {
        for (String type :
                new String[] {"SUSPENSION", "HOLD", "DISMISSAL", "SEPARATION", "SALARY_STOP"}) {
            assertThatThrownBy(() -> event(type, TODAY, status))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED);
                                assertThat(e.getMessageArgs()).containsExactly(status);
                            });
        }
    }

    @Test
    void I_17_anyOtherEventFitsAServingEmployee() {
        for (String type :
                new String[] {"SUSPENSION", "HOLD", "ABSCONDING", "DEPUTATION_OUT", "DISMISSAL"}) {
            assertThatCode(() -> event(type, TODAY, "IN_SERVICE")).doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------- transfers (M-11)

    private static void transfer(
            LocalDate date,
            LocalDate relieving,
            LocalDate joining,
            String time,
            boolean split,
            Integer from,
            Integer to) {
        EventRules.validateTransfer(date, relieving, joining, time, split, from, to, TODAY);
    }

    @Test
    void M_11_aTransferDatedTodayWithOrderedDatesIsValid() {
        assertThatCode(() -> transfer(TODAY, TODAY.minusDays(2), TODAY, "09:30", false, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> transfer(TODAY, TODAY, TODAY, null, false, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> transfer(TODAY, null, TODAY, "", false, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> transfer(TODAY, TODAY, null, "  ", false, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void M_11_aTransferNeedsADateAndJoiningNotBeforeRelieving() {
        refused(
                () -> transfer(null, null, null, null, false, null, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> transfer(TODAY, TODAY, TODAY.minusDays(1), null, false, null, null),
                CommonErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "23:59", "19:05", "09:00"})
    void M_11_aJoiningTimeIsHhMm(String time) {
        assertThatCode(() -> transfer(TODAY, null, null, time, false, null, null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"24:00", "9:00", "12:60", "12:5", "noon", "12-30"})
    void M_11_aMalformedJoiningTimeIsRefused(String time) {
        refused(
                () -> transfer(TODAY, null, null, time, false, null, null),
                CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void M_11_splitWageDaysAreGivenNotNegativeAndAtMost31() {
        assertThatCode(() -> transfer(TODAY, null, null, null, true, 10, 21))
                .doesNotThrowAnyException();
        assertThatCode(() -> transfer(TODAY, null, null, null, true, 0, 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> transfer(TODAY, null, null, null, true, 31, 0))
                .doesNotThrowAnyException();
        refused(
                () -> transfer(TODAY, null, null, null, true, null, 5),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> transfer(TODAY, null, null, null, true, 5, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> transfer(TODAY, null, null, null, true, -1, 5),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> transfer(TODAY, null, null, null, true, 5, -1),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> transfer(TODAY, null, null, null, true, 16, 16),
                CommonErrorCode.VALIDATION_FAILED);
        // Without split wages the day counts are not looked at.
        assertThatCode(() -> transfer(TODAY, null, null, null, false, -1, null))
                .doesNotThrowAnyException();
    }

    @Test
    void M_11_aFutureTransferComesFromHrmsNotFromPayroll() {
        refused(
                () -> transfer(TODAY.plusDays(1), null, null, null, false, null, null),
                ApiErrorCode.EMPLOYEE_FUTURE_DATE);
    }
}
