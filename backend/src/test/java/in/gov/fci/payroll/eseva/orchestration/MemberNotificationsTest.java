package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Member notifications (plan 14 phase 14e; SRS-17-12, SRS-17-1, CF-12; D14-8): every status is an
 * in-app notice; the e-mail is queued in FCI's wording, or SUPPRESSED with the reason without the
 * template or a registered address; a locked month queues the payslip e-mails in one statement, all
 * suppressed without the template; a member reads and marks only the own notices.
 */
class MemberNotificationsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MemberNotifications notifications =
            new MemberNotifications(
                    jdbc,
                    mock(Authorization.class),
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    private void template(boolean configured) {
        when(jdbc.queryForList(contains("FROM notification_template"), any(Object[].class)))
                .thenReturn(
                        configured
                                ? List.of(
                                        Map.of(
                                                "TEMPLATE_ID",
                                                5L,
                                                "SUBJECT_EN",
                                                "Request {reference}",
                                                "BODY_EN",
                                                "Dear {name}, your {type} is {status}: {summary}"))
                                : List.of());
    }

    private void email(String address) {
        when(jdbc.queryForList(
                        contains("SELECT email_address"), eq(String.class), any(Object[].class)))
                .thenReturn(address == null ? List.of() : List.of(address));
    }

    @Test
    void SRS_17_12_eachStatusIsANoticeAndAQueuedEmailInFcisWording() {
        template(true);
        email("m@fci-demo.test");
        notifications.statusChanged(7L, 3L, "VPF_SUBSCRIPTION", "ROUTED", "VPF 3000");
        verify(jdbc)
                .update(
                        contains("'IN_APP'"),
                        eq(7L),
                        eq("ESEVA_ROUTED"),
                        eq("ess_submission"),
                        eq("3"),
                        eq("VPF_SUBSCRIPTION: VPF 3000"),
                        any(),
                        any());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("'EMAIL'"), args.capture());
        assertThat(args.getValue())
                .contains(5L, "Dear {name}, your VPF_SUBSCRIPTION is ROUTED: VPF 3000", "QUEUED");
    }

    @Test
    void SRS_17_12_withoutTheTemplateOrAnAddressTheEmailIsSuppressedWithTheReason() {
        template(false);
        email("m@fci-demo.test");
        notifications.statusChanged(7L, 3L, "VPF_SUBSCRIPTION", "ACTIONED", null);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("'EMAIL'"), args.capture());
        assertThat(args.getValue()).contains("SUPPRESSED", "Template ESEVA_STATUS not configured");

        template(true);
        email(null);
        notifications.statusChanged(7L, 4L, "VPF_SUBSCRIPTION", "ACTIONED", null);
        verify(jdbc, org.mockito.Mockito.times(2)).update(contains("'EMAIL'"), args.capture());
        assertThat(args.getValue()).contains("SUPPRESSED", "No registered e-mail");
    }

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

    @Test
    void SRS_17_12_aMemberReadsAndMarksOnlyTheOwnNotices() {
        assertThatThrownBy(() -> notifications.read(member(null), 1L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
        when(jdbc.update(contains("SET is_read_flag"), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> notifications.read(member(7L), 1L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        when(jdbc.update(contains("SET is_read_flag"), any(Object[].class))).thenReturn(1);
        notifications.read(member(7L), 1L);
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(null);
        assertThat(
                        notifications
                                .mine(
                                        member(7L),
                                        new in.gov.fci.payroll.platform.paging.PageSpec(0, 20))
                                .total())
                .isZero();
        verify(jdbc, never()).update(anyString(), eq("PAYSLIP"));
    }

    @Test
    void SRS_12_10_aChangedLoanInstalmentIsToldToTheMember() {
        template(false);
        email("m@fci-demo.test");
        notifications.on(
                new in.gov.fci.payroll.loans.api.LoanInstalmentChanged(
                        7L, "LN-1", "RESCHEDULE", new java.math.BigDecimal("1500"), "202611"));
        verify(jdbc)
                .update(
                        contains("'IN_APP'"),
                        eq(7L),
                        eq("LOAN_INSTALMENT"),
                        eq("loan_header"),
                        eq("LN-1"),
                        eq("LN-1 RESCHEDULE: instalment 1500 from 202611"),
                        any(),
                        any());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("'EMAIL'"), args.capture());
        assertThat(args.getValue())
                .contains("SUPPRESSED", "Template LOAN_INSTALMENT not configured");
    }
}
