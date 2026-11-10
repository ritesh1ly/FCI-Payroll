package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.leave.api.LeaveEncashments;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plan 19 phase 19e (BR-FNF-009, SRS-LV-16, SRS-LV-17): the valuation at separation from the M-20
 * parameters, the HRMS balance and the pay of the latest computed month; every missing input
 * refuses with "not configured", never a default.
 */
class LeaveEncashmentServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeaveParameters parameters = mock(LeaveParameters.class);
    private final StaffEarnings earnings = mock(StaffEarnings.class);
    private final LeaveEncashmentService service =
            new LeaveEncashmentService(jdbc, parameters, earnings);

    private void configured() {
        when(parameters.number(eq(LeaveParameters.SEPARATION_AGGREGATE_CEILING), isNull(), eq(DAY)))
                .thenReturn(Optional.of(new BigDecimal("300")));
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), isNull(), eq(DAY)))
                .thenReturn(Optional.of(new BigDecimal("30")));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_ELEMENTS), isNull(), eq(DAY)))
                .thenReturn(Optional.of("101+111"));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_CODES), isNull(), eq(DAY)))
                .thenReturn(Optional.of("184,185"));
    }

    private void balances(BigDecimal... days) {
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), anyLong(), any()))
                .thenReturn(List.of(days));
    }

    private void pay() {
        when(earnings.months(eq(7L), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new StaffEarnings.Month(
                                        "202610",
                                        true,
                                        List.of(
                                                new StaffEarnings.Line(
                                                        101,
                                                        "Basic",
                                                        new BigDecimal("56100"),
                                                        true),
                                                new StaffEarnings.Line(
                                                        111, "DA", new BigDecimal("28050"), true),
                                                new StaffEarnings.Line(
                                                        121,
                                                        "HRA",
                                                        new BigDecimal("15147"),
                                                        true)))));
    }

    @Test
    void BR_FNF_009_theBalanceUpToTheCeilingIsValuedAtThePayElementsOverTheDivisor() {
        configured();
        balances(new BigDecimal("280"), new BigDecimal("40"));
        pay();
        LeaveEncashments.Valuation v = service.atSeparation(7L, DAY);
        assertThat(v.balanceDays()).isEqualByComparingTo("320");
        assertThat(v.encashedDays()).isEqualByComparingTo("300");
        assertThat(v.capped()).isTrue();
        assertThat(v.pay()).as("Basic + DA, not HRA").isEqualByComparingTo("84150");
        assertThat(v.amount()).isEqualByComparingTo("841500");
        assertThat(v.ratePerDay()).isEqualByComparingTo("2805");
        assertThat(v.payCode()).isEqualTo(184);
        assertThat(v.basis()).contains("(101+111) 84150 / 30 x 300 days").contains("capped");
    }

    @Test
    void SRS_10_28_everyMissingInputRefusesWithNotConfigured() {
        assertThatThrownBy(() -> service.atSeparation(7L, DAY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.NOT_CONFIGURED));
        configured();
        balances();
        assertThatThrownBy(() -> service.atSeparation(7L, DAY)).isInstanceOf(FciException.class);
        balances(new BigDecimal("10"));
        when(earnings.months(eq(7L), anyString(), anyString())).thenReturn(List.of());
        assertThatThrownBy(() -> service.atSeparation(7L, DAY)).isInstanceOf(FciException.class);
        when(earnings.months(eq(7L), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                new StaffEarnings.Month(
                                        "202610",
                                        true,
                                        List.of(
                                                new StaffEarnings.Line(
                                                        121, "HRA", BigDecimal.TEN, true)))));
        assertThatThrownBy(() -> service.atSeparation(7L, DAY)).isInstanceOf(FciException.class);
    }

    /**
     * SRS-8-23 / SRS-10-16 (US-GAP-251, CF-49): with every other input configured, an unseeded
     * aggregate ceiling alone refuses the valuation with "not configured" naming it - never valued
     * without a ceiling.
     */
    @Test
    void SRS_8_23_anUnseededAggregateCeilingAloneAnswersNotConfigured() {
        configured();
        when(parameters.number(eq(LeaveParameters.SEPARATION_AGGREGATE_CEILING), isNull(), eq(DAY)))
                .thenReturn(Optional.empty());
        balances(new BigDecimal("10"));
        pay();
        assertThatThrownBy(() -> service.atSeparation(7L, DAY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.NOT_CONFIGURED);
                            assertThat(e.getMessageArgs())
                                    .containsExactly("Aggregate ceiling at separation");
                        });
    }

    /** The statement of the last leave_encashment INSERT, to read what was recorded. */
    private final java.util.concurrent.atomic.AtomicReference<java.sql.PreparedStatement> inserted =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * BMAD TR-043: the days recorded, the capped flag and the amount (days x (Basic + DA) /
     * divisor, 84,150 / 30 = 2,805 a day) of the last INSERT.
     */
    private void recorded(String days, boolean capped, String amount) throws Exception {
        java.sql.PreparedStatement ps = inserted.get();
        org.mockito.Mockito.verify(ps)
                .setBigDecimal(
                        org.mockito.ArgumentMatchers.eq(5),
                        org.mockito.ArgumentMatchers.argThat(
                                v -> v.compareTo(new BigDecimal(days)) == 0));
        org.mockito.Mockito.verify(ps).setString(9, capped ? "Y" : "N");
        org.mockito.Mockito.verify(ps)
                .setBigDecimal(
                        org.mockito.ArgumentMatchers.eq(8),
                        org.mockito.ArgumentMatchers.argThat(
                                v -> v.compareTo(new BigDecimal(amount)) == 0));
    }

    private void inService(LocalDate day, String used) {
        when(parameters.number(eq(LeaveParameters.INSERVICE_PER_OCCASION_LIMIT), eq("EL"), eq(day)))
                .thenReturn(Optional.of(new BigDecimal("10")));
        when(parameters.number(eq(LeaveParameters.INSERVICE_PER_YEAR_LIMIT), eq("EL"), eq(day)))
                .thenReturn(Optional.of(new BigDecimal("15")));
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), isNull(), eq(day)))
                .thenReturn(Optional.of(new BigDecimal("30")));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_ELEMENTS), isNull(), eq(day)))
                .thenReturn(Optional.of("101+111"));
        when(parameters.text(eq(LeaveParameters.ENCASH_PAY_CODES), isNull(), eq(day)))
                .thenReturn(Optional.of("184"));
        when(jdbc.queryForObject(
                        anyString(), eq(BigDecimal.class), anyLong(), anyString(), any(), any()))
                .thenReturn(used == null ? null : new BigDecimal(used));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(184))).thenReturn(List.of(55L));
        org.mockito.Mockito.doAnswer(
                        i -> {
                            org.springframework.jdbc.core.PreparedStatementCreator creator =
                                    i.getArgument(0);
                            java.sql.Connection c = mock(java.sql.Connection.class);
                            java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                            inserted.set(ps);
                            when(c.prepareStatement(anyString(), any(String[].class)))
                                    .thenReturn(ps);
                            creator.createPreparedStatement(c);
                            ((org.springframework.jdbc.support.GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(java.util.Map.of("ENCASHMENT_ID", 9L));
                            return 1;
                        })
                .when(jdbc)
                .update(
                        any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                        any(org.springframework.jdbc.support.KeyHolder.class));
    }

    @Test
    void SRS_LV_17_anInServiceEncashmentIsCappedByTheOccasionAndTheYearAndRecorded()
            throws Exception {
        inService(DAY, null);
        pay();
        assertThat(service.recordInService(7L, "EL", new BigDecimal("12"), DAY, 41L)).isEqualTo(9L);
        recorded("10", true, "28050");
        inService(DAY, "8");
        assertThat(service.recordInService(7L, "EL", new BigDecimal("12"), DAY, 41L))
                .as("7 days left in the year")
                .isEqualTo(9L);
        recorded("7", true, "19635");
    }

    @Test
    void SRS_LV_17_aYearUsedUpOrAMissingLimitRefusesTheEncashment() {
        LocalDate february = LocalDate.of(2027, 2, 10);
        inService(february, "15");
        pay();
        assertThatThrownBy(() -> service.recordInService(7L, "EL", BigDecimal.ONE, february, 41L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.EVENT));
        when(parameters.number(eq(LeaveParameters.INSERVICE_PER_OCCASION_LIMIT), eq("EL"), eq(DAY)))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordInService(7L, "EL", BigDecimal.ONE, DAY, 41L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.NOT_CONFIGURED));
    }

    /** SRS-10-28 / SRS-LV-17: a divisor configured as zero is not configured, never a division. */
    @Test
    void SRS_10_28_aZeroDivisorIsNotConfigured() {
        configured();
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), isNull(), eq(DAY)))
                .thenReturn(Optional.of(BigDecimal.ZERO));
        assertThatThrownBy(() -> service.atSeparation(7L, DAY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.NOT_CONFIGURED));
        inService(DAY, null);
        when(parameters.number(eq(LeaveParameters.ENCASH_DIVISOR), isNull(), eq(DAY)))
                .thenReturn(Optional.of(BigDecimal.ZERO));
        assertThatThrownBy(() -> service.recordInService(7L, "EL", BigDecimal.ONE, DAY, 41L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(LeaveErrorCode.NOT_CONFIGURED));
    }

    /** SRS-LV-17: an encashment within both limits is recorded as asked, not capped. */
    @Test
    void SRS_LV_17_anEncashmentWithinTheLimitsIsNotCapped() throws Exception {
        inService(DAY, null);
        pay();
        assertThat(service.recordInService(7L, "EL", new BigDecimal("5"), DAY, 41L)).isEqualTo(9L);
        recorded("5", false, "14025");
        // Exactly the occasion limit: recorded as asked, not capped (boundary).
        inService(DAY, null);
        assertThat(service.recordInService(7L, "EL", new BigDecimal("10"), DAY, 41L)).isEqualTo(9L);
        recorded("10", false, "28050");
    }
}
