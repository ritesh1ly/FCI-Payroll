package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-6-44: an opening-balance correction is blocked once the member's final settlement is
 * sanctioned, or a withdrawal was sanctioned on or after the balance date.
 */
class SanctionRegisterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 4, 1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SanctionRegister register = new SanctionRegister(jdbc);

    @Test
    void SRS_6_44_aSanctionedFinalSettlementCountsOnlyWhenOneExists() {
        when(jdbc.queryForObject(contains("'FINAL'"), eq(Integer.class), eq(7L))).thenReturn(1);
        when(jdbc.queryForObject(contains("'FINAL'"), eq(Integer.class), eq(8L))).thenReturn(0);
        assertThat(register.finallySettled(7L)).isTrue();
        assertThat(register.finallySettled(8L)).isFalse();
    }

    @Test
    void SRS_6_44_aWithdrawalSanctionedOnOrAfterTheDayCountsOnlyWhenOneExists() {
        when(jdbc.queryForObject(
                        contains("sanction_date >= ?"), eq(Integer.class), eq(7L), eq(DAY)))
                .thenReturn(2);
        when(jdbc.queryForObject(
                        contains("sanction_date >= ?"), eq(Integer.class), eq(8L), eq(DAY)))
                .thenReturn(0);
        assertThat(register.sanctionedSince(7L, DAY)).isTrue();
        assertThat(register.sanctionedSince(8L, DAY)).isFalse();
        // No count read back (a driver answering null) is no sanction.
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(9L), eq(DAY))).thenReturn(null);
        assertThat(register.sanctionedSince(9L, DAY)).isFalse();
    }
}
