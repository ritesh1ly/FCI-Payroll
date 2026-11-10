package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The I-21 Basic Pay and increment-date fields (plan 17 phase 17a; SRS-13-20, SRS-1-33, SRS-13-24;
 * D17-1, D17-7): a value HRMS sends is checked at intake - Basic Pay a positive amount in rupees
 * and paise, the increment date a date; once approved the Basic is a history event from its
 * effective date (the decision day when HRMS gives none) and the date becomes the next increment
 * due date.
 */
class IncrementHrmsEffectsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BasicPayService basic = mock(BasicPayService.class);
    private final Scales scales = mock(Scales.class);
    private final IncrementHrmsEffects effects = new IncrementHrmsEffects(jdbc, basic, scales);
    private static final Instant DECIDED = Instant.parse("2026-10-27T20:00:00Z");

    @Test
    void SRS_1_33_aBasicPayIsAPositiveAmountInPaiseAndADateIsADate() {
        assertThat(effects.fields()).containsExactlyInAnyOrder("BASIC_PAY", "INCREMENT_DATE");
        assertThatCode(() -> effects.check("BASIC_PAY", 7L, " 62400.50 "))
                .doesNotThrowAnyException();
        for (String bad : new String[] {null, "", "abc", "0", "-5", "62400.505"}) {
            assertThatThrownBy(() -> effects.check("BASIC_PAY", 7L, bad))
                    .as(String.valueOf(bad))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(IncrementErrorCode.ENTRY);
        }
        assertThatCode(() -> effects.check("INCREMENT_DATE", 7L, " 2027-01-01 "))
                .doesNotThrowAnyException();
        for (String bad : new String[] {null, "01-01-2027", "2027-13-01"}) {
            assertThatThrownBy(() -> effects.check("INCREMENT_DATE", 7L, bad))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(IncrementErrorCode.ENTRY);
        }
    }

    @Test
    void SRS_13_20_anApprovedBasicIsAHistoryEventFromItsDateOrTheDecisionDayInIst() {
        effects.apply(
                new HrmsTaskEffects.Task(3L, "HCA-1", 7L, "BASIC_PAY", "64300", null, "HR-9"),
                "ddo@x",
                55L,
                DECIDED);
        effects.apply(
                new HrmsTaskEffects.Task(
                        4L, "HCA-2", 7L, "BASIC_PAY", "66300", LocalDate.of(2026, 7, 1), null),
                "ddo@x",
                56L,
                DECIDED);
        ArgumentCaptor<BasicPayService.Change> changes =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic, org.mockito.Mockito.times(2)).apply(changes.capture());
        assertThat(changes.getAllValues().get(0).effectiveFrom())
                .as("20:00 UTC is the next day in IST")
                .isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(changes.getAllValues().get(1).effectiveFrom())
                .isEqualTo(LocalDate.of(2026, 7, 1));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void
            SRS_13_24_BR_INCR_009_anApprovedIncrementDateBecomesTheNextDueDateInsertedWhenTheMemberHasNone() {
        when(jdbc.update(contains("UPDATE employee_increment_due"), any(Object[].class)))
                .thenReturn(1)
                .thenReturn(0);
        HrmsTaskEffects.Task task =
                new HrmsTaskEffects.Task(
                        5L, "HCA-3", 7L, "INCREMENT_DATE", "2027-01-01", null, null);
        effects.apply(task, "ddo@x", 57L, DECIDED);
        verify(jdbc, never())
                .update(contains("INSERT INTO employee_increment_due"), any(Object[].class));
        effects.apply(task, "ddo@x", 58L, DECIDED);
        verify(jdbc).update(contains("INSERT INTO employee_increment_due"), any(Object[].class));
        verify(basic, never()).apply(any());
    }

    private static Scales.Scale scale(java.util.List<java.math.BigDecimal> stages) {
        return new Scales.Scale(
                4L,
                "E-4",
                40L,
                new java.math.BigDecimal("60000"),
                new java.math.BigDecimal("178800"),
                null,
                null,
                stages);
    }

    @Test
    void SRS_13_23_aBasicThatIsNotAStageOfTheMembersScaleIsRefusedAtIntakeAndApproval() {
        java.util.List<java.math.BigDecimal> stages =
                java.util.List.of(
                        new java.math.BigDecimal("62400"), new java.math.BigDecimal("64300"));
        when(scales.ofMember(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(java.util.Optional.of(scale(stages)));
        assertThatCode(() -> effects.check("BASIC_PAY", 7L, "64300")).doesNotThrowAnyException();
        assertThatThrownBy(() -> effects.check("BASIC_PAY", 7L, "63000"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(IncrementErrorCode.ENTRY);
        assertThatThrownBy(
                        () ->
                                effects.apply(
                                        new HrmsTaskEffects.Task(
                                                5L, "HCA-5", 7L, "BASIC_PAY", "63000", null, null),
                                        "ddo@x",
                                        57L,
                                        DECIDED))
                .isInstanceOf(FciException.class);
        verify(basic, never()).apply(any());

        // A scale with no stages configured is not checked here (US-GAP-9, owner decision).
        when(scales.ofMember(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(java.util.Optional.of(scale(java.util.List.of())));
        assertThatCode(() -> effects.check("BASIC_PAY", 7L, "63000")).doesNotThrowAnyException();
    }
}
