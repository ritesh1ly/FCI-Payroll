package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The lock's CPF booking edges (plan 07 phase 07i part 2; SRS-6-1, SRS-6-7): no CPF pay code, a VPF
 * line without a CPF line, and the VPF code falling back when unset. The full posting is CpfLockIT.
 * Rates are fixtures.
 */
class CpfLockEffectTest {

    private static final LocalDate MAY = LocalDate.of(2031, 5, 1);
    private static final CycleLocked LOCKED =
            new CycleLocked(9L, 3L, "203105", "checker", Instant.parse("2031-05-30T05:00:00Z"));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final RuleSet set = mock(RuleSet.class);
    private final CpfLedgerService ledger = mock(CpfLedgerService.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final CpfLockEffect effect = new CpfLockEffect(jdbc, rules, ledger, parameters);

    CpfLockEffectTest() {
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.of(58));
    }

    private void codes(Integer cpf, Integer vpf) {
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("CPF"), any(), any()))
                .thenReturn(cpf == null ? List.of() : List.of(BigDecimal.valueOf(cpf)));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("VPF"), any(), any()))
                .thenReturn(vpf == null ? List.of() : List.of(BigDecimal.valueOf(vpf)));
    }

    /** The cycle's lines: {employee, pay code, amount, basis[, birth date]}. */
    private void lines(Object[]... rows) {
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            for (Object[] r : rows) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getLong(1)).thenReturn((Long) r[0]);
                                when(rs.getInt(2)).thenReturn((Integer) r[1]);
                                when(rs.getBigDecimal(3)).thenReturn(new BigDecimal((String) r[2]));
                                when(rs.getBigDecimal(4))
                                        .thenReturn(
                                                r[3] == null
                                                        ? null
                                                        : new BigDecimal((String) r[3]));
                                when(rs.getLong(5)).thenReturn(3L);
                                when(rs.getDate(6))
                                        .thenReturn(
                                                r.length > 4
                                                        ? java.sql.Date.valueOf((String) r[4])
                                                        : null);
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    private static RuleEngine.Resolved scalar(String v) {
        return new RuleEngine.Resolved(
                "k", "RATE", 1L, 1, MAY, null, new BigDecimal(v), List.of(), null, null, false);
    }

    @Test
    void SRS_6_1_withoutACpfPayCodeNothingIsBooked() {
        codes(null, null);
        effect.on(LOCKED);
        verifyNoInteractions(rules, ledger);
    }

    @Test
    void SRS_6_1_BR_CPF_018_aCycleWithoutCpfLinesOrWithVpfAloneBooksNothing() {
        codes(904, 905);
        lines();
        effect.on(LOCKED);
        verifyNoInteractions(rules, ledger);

        when(rules.snapshot(MAY)).thenReturn(set);
        lines(new Object[] {7L, 905, "-2500", null});
        effect.on(LOCKED);
        verify(ledger, never()).post(any());
    }

    @Test
    void SRS_6_7_eachMemberIsPostedWithTheSignedAmountsMadePositive() {
        codes(904, null);
        when(rules.snapshot(MAY)).thenReturn(set);
        when(set.resolve(eq("CPF.RATE.EMPLOYER"), any())).thenReturn(Optional.of(scalar("12")));
        when(set.resolve(eq("CPF.EPS.RATE"), any())).thenReturn(Optional.of(scalar("8.33")));
        when(set.resolve(eq("CPF.HIGHERPENSION.RATE"), any()))
                .thenReturn(Optional.of(scalar("1.16")));
        lines(new Object[] {7L, 904, "-3600", "30000"});
        effect.on(LOCKED);
        ArgumentCaptor<CpfLedgerService.Posting> p =
                ArgumentCaptor.forClass(CpfLedgerService.Posting.class);
        verify(ledger).post(p.capture());
        assertThat(p.getValue().employeeId()).isEqualTo(7L);
        assertThat(p.getValue().day()).isEqualTo(MAY);
        assertThat(p.getValue().change().employee()).isEqualByComparingTo("3600");
        assertThat(p.getValue().change().employer()).isEqualByComparingTo("3600");
        assertThat(p.getValue().change().vpf()).isEqualByComparingTo("0");
        assertThat(p.getValue().wageBase()).isEqualByComparingTo("30000");
        assertThat(p.getValue().eps()).isEqualByComparingTo("2499.00");
        assertThat(p.getValue().pohw()).isEqualByComparingTo("348.00");
        assertThat(p.getValue().rounding()).isEqualByComparingTo("0");
        assertThat(p.getValue().actor()).isEqualTo("checker");
        assertThat(p.getValue().reason()).contains("203105");
    }

    private CpfLedgerService.Posting postedFor(String birthDate) {
        codes(904, null);
        when(rules.snapshot(MAY)).thenReturn(set);
        when(set.resolve(eq("CPF.RATE.EMPLOYER"), any())).thenReturn(Optional.of(scalar("12")));
        when(set.resolve(eq("CPF.EPS.RATE"), any())).thenReturn(Optional.of(scalar("8.33")));
        when(set.resolve(eq("CPF.HIGHERPENSION.RATE"), any()))
                .thenReturn(Optional.of(scalar("1.16")));
        lines(new Object[] {7L, 904, "-3600", "30000", birthDate});
        effect.on(LOCKED);
        ArgumentCaptor<CpfLedgerService.Posting> p =
                ArgumentCaptor.forClass(CpfLedgerService.Posting.class);
        verify(ledger).post(p.capture());
        return p.getValue();
    }

    @Test
    void SRS_1_76_epsStopsFromTheMonthTheMemberReaches58AndTheWholeShareStaysInCpf() {
        // 58 on 31 May 2031: no EPS from May, the employer contribution unchanged.
        CpfLedgerService.Posting p = postedFor("1973-05-31");
        assertThat(p.change().employer()).isEqualByComparingTo("3600");
        assertThat(p.eps()).isEqualByComparingTo("0");
        assertThat(p.pohw()).isEqualByComparingTo("0");
        assertThat(p.rounding()).isEqualByComparingTo("0");
    }

    @Test
    void SRS_1_76_epsIsStillBookedInTheMonthBeforeTheMemberReaches58() {
        // 58 on 1 June 2031: May is the last month with EPS.
        CpfLedgerService.Posting p = postedFor("1973-06-01");
        assertThat(p.eps()).isEqualByComparingTo("2499.00");
        assertThat(p.pohw()).isEqualByComparingTo("348.00");
    }

    @Test
    void SRS_1_76_aMemberPast58BooksNoEps() {
        assertThat(postedFor("1960-01-15").eps()).isEqualByComparingTo("0");
    }

    @Test
    void SRS_1_22_withoutTheEpsCutOffAgeConfiguredTheLockRefusesToBookCpf() {
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.empty());
        codes(904, null);
        when(rules.snapshot(MAY)).thenReturn(set);
        lines(new Object[] {7L, 904, "-3600", "30000", "1980-01-01"});
        assertThatThrownBy(() -> effect.on(LOCKED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EPS_CUTOFF_AGE");
        verify(ledger, never()).post(any());
    }
}
