package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** SRS-8-16, SRS-10-13: arrears paid through the settlement are never paid again. */
class SettledArrearsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettledArrears arrears = new SettledArrears(jdbc, audit);

    @Test
    void SRS_8_16_theApprovedDifferentialsArePaidAndTheirEventClosed() {
        when(jdbc.update(contains("SET arrear_status = 'PAID'"), any(Object[].class)))
                .thenReturn(2);

        arrears.paid(7L, "checker@x", 41L);

        verify(jdbc).update(contains("event_status = 'POSTED'"), eq(7L));
        verify(audit).record(eq("checker@x"), any(AuditService.Entry.class));
    }

    @Test
    void SRS_8_16_aMemberWithNothingApprovedChangesNoEventAndWritesNoAudit() {
        when(jdbc.update(contains("SET arrear_status = 'PAID'"), any(Object[].class)))
                .thenReturn(0);

        arrears.paid(7L, "checker@x", 41L);

        verify(jdbc, never()).update(contains("event_status"), eq(7L));
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_8_16_theTotalIsTheApprovedAmountAndZeroWhenTheQueryGivesNothing() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(7L)))
                .thenReturn(new BigDecimal("1250.00"));
        assertThat(arrears.approvedTotal(7L)).isEqualByComparingTo("1250.00");
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(7L))).thenReturn(null);
        assertThat(arrears.approvedTotal(7L)).isEqualByComparingTo("0");
    }
}
