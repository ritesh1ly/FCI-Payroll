package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.notification.NotificationSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The member e-mail queue (plan 14 phase 14e; SRS-17-12, SRS-17-1; D14-8): each queued e-mail goes
 * out in FCI's wording (a payslip filled from its locked result) and is marked SENT; a failure is
 * marked FAILED with the reason and the others still go (never blocking).
 */
class NotificationDispatchTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final NotificationDispatch dispatch = new NotificationDispatch(jdbc, sender, 50);

    private static Map<String, Object> queued(long id, String type, String last) {
        Map<String, Object> n = new HashMap<>();
        n.put("NOTIFICATION_ID", id);
        n.put("NOTIFICATION_TYPE", type);
        n.put("RELATED_ENTITY_KEY", "77");
        n.put("MESSAGE", "Your request #3 is ACTIONED");
        n.put("SUBJECT_EN", "FCI payroll - {name}");
        n.put("BODY_EN", "Dear {name}, payslip {month}: net {net}");
        n.put("EMAIL_ADDRESS", "m@fci-demo.test");
        n.put("FIRST_NAME", "Rakesh");
        n.put("LAST_NAME", last);
        return n;
    }

    @Test
    void SRS_17_12_eachQueuedEmailIsSentAndAFailureNeverStopsTheOthers() {
        assertThat(dispatch.name()).isEqualTo("memberEmails");
        when(jdbc.queryForList(contains("delivery_status = 'QUEUED'"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                queued(1L, "ESEVA_ACTIONED", "Sharma"),
                                queued(2L, "PAYSLIP", null),
                                queued(3L, "ESEVA_REJECTED", " ")));
        when(jdbc.queryForMap(contains("FROM payroll_result"), any(Object[].class)))
                .thenReturn(
                        Map.of(
                                "PAYROLL_YYMM", "202610",
                                "GROSS_EARNINGS", new BigDecimal("50000"),
                                "TOTAL_DEDUCTIONS", new BigDecimal("8000"),
                                "NET_PAY", new BigDecimal("42000")));
        doThrow(new IllegalStateException("smtp down"))
                .when(sender)
                .sendMessage(
                        anyString(), eq("Rakesh"), anyString(), eq("Your request #3 is ACTIONED"));
        int sent = dispatch.run(Instant.parse("2026-10-28T05:00:00Z"));
        assertThat(sent).isEqualTo(2);
        verify(sender)
                .sendMessage(
                        "m@fci-demo.test",
                        "Rakesh Sharma",
                        "FCI payroll - Rakesh Sharma",
                        "Your request #3 is ACTIONED");
        verify(sender)
                .sendMessage(
                        "m@fci-demo.test",
                        "Rakesh",
                        "FCI payroll - Rakesh",
                        "Dear Rakesh, payslip 10/2026: net 42000");
        verify(jdbc).update(contains("'SENT'"), any(), eq(1L));
        verify(jdbc).update(contains("'SENT'"), any(), eq(2L));
        verify(jdbc).update(contains("'FAILED'"), eq("IllegalStateException: smtp down"), eq(3L));
    }

    /** SRS-17-12: a failure without a message is recorded by its kind alone. */
    @Test
    void SRS_17_12_aFailureWithoutAMessageIsRecordedByItsKind() {
        when(jdbc.queryForList(contains("delivery_status = 'QUEUED'"), any(Object[].class)))
                .thenReturn(List.of(queued(4L, "ESEVA_ACTIONED", "Sharma")));
        doThrow(new IllegalStateException())
                .when(sender)
                .sendMessage(anyString(), anyString(), anyString(), anyString());
        assertThat(dispatch.run(Instant.parse("2026-10-28T05:00:00Z"))).isZero();
        verify(jdbc).update(contains("'FAILED'"), eq("IllegalStateException"), eq(4L));
    }
}
