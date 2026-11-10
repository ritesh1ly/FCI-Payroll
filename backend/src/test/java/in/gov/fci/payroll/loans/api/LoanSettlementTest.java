package in.gov.fci.payroll.loans.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS-10-7, UC-06 postcondition (SRS l.2917), BR-FNF-006, BR-LOAN-006: on posting, each itemised
 * open loan or advance is closed as settled in the F&amp;F with an FNF_SETTLEMENT event and an
 * audit entry; a closed, foreclosed, already settled or unknown loan is left alone.
 */
class LoanSettlementTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final LoanSettlement settlement = new LoanSettlement(jdbc, audit);
    private final LocalDate posted = LocalDate.of(2026, 10, 31);

    private void loan(long id, String status, String number) {
        Map<String, Object> row = new HashMap<>();
        row.put("LOAN_STATUS", status);
        row.put("LOAN_NUMBER", number);
        when(jdbc.queryForList(contains("FROM loan_header WHERE loan_id"), eq(id)))
                .thenReturn(List.of(row));
    }

    @Test
    void SRS_10_7_eachItemisedLoanIsClosedAsSettledInFnfOnPosting() {
        loan(41L, "ACTIVE", "LN/41");
        loan(42L, "STOPPED", null);
        loan(43L, "RESCHEDULED", "LN/43");
        int closed =
                settlement.settle(
                        List.of(
                                new LoanSettlement.Recovered(41L, new BigDecimal("1200.50")),
                                new LoanSettlement.Recovered(42L, BigDecimal.ONE),
                                new LoanSettlement.Recovered(43L, BigDecimal.TEN)),
                        posted,
                        "FNF-1",
                        "ddo@x",
                        9L);
        assertThat(closed).isEqualTo(3);
        verify(jdbc)
                .update(
                        contains("SET loan_status = 'SETTLED_IN_FNF', principal_outstanding = 0"),
                        eq(41L));
        verify(jdbc).update(contains("row_version = row_version + 1"), eq(42L));
        verify(jdbc)
                .update(
                        contains("'FNF_SETTLEMENT'"),
                        eq(41L),
                        eq(Date.valueOf(posted)),
                        eq(new BigDecimal("1200.50")),
                        eq("Recovered at settlement FNF-1"),
                        eq(9L));
        verify(audit, times(3)).record(eq("ddo@x"), any(AuditService.Entry.class));
    }

    @Test
    void SRS_10_7_aLoanNotOpenOrNotFoundIsLeftAlone() {
        loan(51L, "CLOSED", "LN/51");
        loan(52L, "FORECLOSED", "LN/52");
        loan(53L, "SETTLED_IN_FNF", "LN/53");
        when(jdbc.queryForList(contains("FROM loan_header WHERE loan_id"), eq(54L)))
                .thenReturn(List.of());
        int closed =
                settlement.settle(
                        List.of(
                                new LoanSettlement.Recovered(51L, BigDecimal.ONE),
                                new LoanSettlement.Recovered(52L, BigDecimal.ONE),
                                new LoanSettlement.Recovered(53L, BigDecimal.ONE),
                                new LoanSettlement.Recovered(54L, BigDecimal.ONE)),
                        posted,
                        "FNF-2",
                        "ddo@x",
                        9L);
        assertThat(closed).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
        assertThat(settlement.settle(List.of(), posted, "FNF-3", "ddo@x", 9L)).isZero();
    }
}
