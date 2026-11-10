package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * E-01 CPF statement (plan 14 phase 14e; SRS-17-17): only a member reads it, the own record only;
 * the statement is of the current year unless one is named, and a malformed year is refused.
 */
class CpfStatementServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CpfStatementService statements =
            new CpfStatementService(
                    jdbc,
                    mock(Authorization.class),
                    Clock.fixed(Instant.parse("2027-02-10T05:00:00Z"), ZoneOffset.UTC));

    private static AccessContext member(Long employeeId) {
        return new AccessContext(
                9L,
                "m",
                "M",
                "s",
                "P",
                5L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static void denied(Runnable call, Object code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_17_theMemberReadsTheOwnStatementOfTheYear() {
        denied(() -> statements.mine(member(null), null), ApiErrorCode.PERMISSION_DENIED);
        denied(() -> statements.mine(member(7L), "2026-2027"), CommonErrorCode.VALIDATION_FAILED);
        assertThat(statements.mine(member(7L), null).finYear()).isEqualTo("2026-27");
        assertThat(statements.mine(member(7L), " 2025-26 ").finYear()).isEqualTo("2025-26");
        verify(jdbc)
                .query(
                        contains("FROM employee_pf_detail"),
                        any(RowMapper.class),
                        eq(7L),
                        eq("2025-26"));
    }

    @Test
    void SRS_17_16_onlyAConsecutiveFinancialYearIsAccepted() {
        assertThat(CpfStatementService.consecutive("2025-26")).isTrue();
        assertThat(CpfStatementService.consecutive("2099-00")).as("the century turns").isTrue();
        assertThat(CpfStatementService.consecutive("2025-27")).isFalse();
        assertThat(CpfStatementService.consecutive("2025-30")).isFalse();
        assertThat(CpfStatementService.consecutive("2025-25")).isFalse();
        assertThat(CpfStatementService.consecutive("2025-2")).isFalse();
        assertThat(CpfStatementService.consecutive("")).isFalse();
        assertThat(CpfStatementService.consecutive(null)).isFalse();
    }

    /** SRS-17-17: a year sent blank is the current year (2026-27 on 10 February 2027). */
    @Test
    void SRS_17_17_aBlankYearIsTheCurrentYear() {
        assertThat(statements.mine(member(7L), "   ").finYear()).isEqualTo("2026-27");
    }

    /** SRS-17-17: the ledger and every posting of the year are mapped as the tables hold them. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_17_theStatementCarriesTheLedgerAndTheMonthlyPostingsOfTheYear()
            throws SQLException {
        ResultSet ledgerRow = mock(ResultSet.class);
        when(ledgerRow.getLong(1)).thenReturn(31L);
        when(ledgerRow.getString(2)).thenReturn("ACTIVE");
        for (int c = 3; c <= 9; c++) {
            when(ledgerRow.getBigDecimal(c)).thenReturn(new BigDecimal(c * 100 + ".25"));
        }
        ResultSet postingRow = mock(ResultSet.class);
        when(postingRow.getString(1)).thenReturn("202604");
        when(postingRow.getString(2)).thenReturn("SALARY");
        when(postingRow.getBigDecimal(3)).thenReturn(new BigDecimal("1200.00"));
        when(postingRow.getBigDecimal(4)).thenReturn(new BigDecimal("1200.00"));
        when(postingRow.getBigDecimal(5)).thenReturn(BigDecimal.ZERO);
        when(jdbc.query(
                        contains("FROM employee_pf_header"),
                        any(RowMapper.class),
                        eq(7L),
                        eq("2026-27")))
                .thenAnswer(
                        call ->
                                List.of(
                                        ((RowMapper<Object>) call.getArgument(1))
                                                .mapRow(ledgerRow, 0)));
        when(jdbc.query(
                        contains("FROM employee_pf_detail"),
                        any(RowMapper.class),
                        eq(7L),
                        eq("2026-27")))
                .thenAnswer(
                        call ->
                                List.of(
                                        ((RowMapper<Object>) call.getArgument(1))
                                                .mapRow(postingRow, 0)));

        CpfStatementService.Statement statement = statements.mine(member(7L), null);

        assertThat(statement.finYear()).isEqualTo("2026-27");
        assertThat(statement.ledgers()).hasSize(1);
        CpfStatementService.Ledger ledger = statement.ledgers().get(0);
        assertThat(ledger.ledgerId()).isEqualTo(31L);
        assertThat(ledger.memberStatus()).isEqualTo("ACTIVE");
        assertThat(ledger.openingEmployee()).isEqualByComparingTo("300.25");
        assertThat(ledger.openingVpf()).isEqualByComparingTo("500.25");
        assertThat(ledger.closingEmployee()).isEqualByComparingTo("600.25");
        assertThat(ledger.interestCredited()).isEqualByComparingTo("900.25");
        assertThat(statement.postings())
                .containsExactly(
                        new CpfStatementService.Posting(
                                "202604",
                                "SALARY",
                                new BigDecimal("1200.00"),
                                new BigDecimal("1200.00"),
                                BigDecimal.ZERO));
    }

    /** SRS-17-17: a member with nothing posted gets an empty, not a missing, statement. */
    @Test
    void SRS_17_17_aMemberWithNothingPostedGetsAnEmptyStatementOfTheYear() {
        CpfStatementService.Statement statement = statements.mine(member(7L), "2025-26");

        assertThat(statement.ledgers()).isEmpty();
        assertThat(statement.postings()).isEmpty();
    }

    /** SRS-17-16: a year on the century turn and one that is not consecutive are told apart. */
    @Test
    void SRS_17_16_aYearWithLettersOrASpaceInsideIsRefused() {
        denied(() -> statements.mine(member(7L), "20x5-26"), CommonErrorCode.VALIDATION_FAILED);
        denied(() -> statements.mine(member(7L), "2025 26"), CommonErrorCode.VALIDATION_FAILED);
        denied(() -> statements.mine(member(7L), "2025-27"), CommonErrorCode.VALIDATION_FAILED);
    }
}
