package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * I-08 Headquarters exception (SRS-8-6, BR-EMPR-007; deep review DR-2): only a RAISED event beyond
 * the back-date limit without an exception yet is asked for, with a reason and an Active circular;
 * the request goes to the I08_HQ_EXCEPTION route; the approval at Headquarters records the
 * exception and the order on the event and computes it; a refusal leaves it RAISED; other decisions
 * are not this service's.
 */
class ArrearExceptionServiceTest {

    private final ArrearRegisterService register = mock(ArrearRegisterService.class);
    private final RetroEventService retro = mock(RetroEventService.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Circulars circulars = mock(Circulars.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ArrearExceptionService service =
            new ArrearExceptionService(register, retro, approvals, circulars, jdbc, audit);

    private static Map<String, Object> event(String status, String beyond, Long exception) {
        Map<String, Object> e = new HashMap<>();
        e.put("EVENT_STATUS", status);
        e.put("BEYOND_WINDOW_FLAG", beyond);
        e.put("HQ_EXCEPTION_APPROVAL_ID", exception);
        e.put("SCOPE_LOCATION_ID", 300L);
        e.put("EFFECTIVE_FROM", Date.valueOf(LocalDate.of(2020, 1, 1)));
        return e;
    }

    private static void refused(ThrowingCallable call, ArrearErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_8_6_onlyAHeldEventBeyondTheLimitIsAskedForWithAReasonAndAnActiveOrder() {
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(event("RAISED", "N", null));
        refused(() -> service.request(context, 5L, "Old order", 9L), ArrearErrorCode.STATE);
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(event("RAISED", "Y", 70L));
        refused(() -> service.request(context, 5L, "Old order", 9L), ArrearErrorCode.STATE);
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(event("REJECTED", "Y", null));
        refused(() -> service.request(context, 5L, "Old order", 9L), ArrearErrorCode.STATE);
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(event("RAISED", "Y", null));
        refused(() -> service.request(context, 5L, " ", 9L), ArrearErrorCode.REMARK);
        refused(() -> service.request(context, 5L, null, 9L), ArrearErrorCode.REMARK);
        FciException inactive = new FciException(ArrearErrorCode.NOT_CONFIGURED);
        doThrow(inactive).when(circulars).requireActive(8L);
        assertThatThrownBy(() -> service.request(context, 5L, "Old order", 8L)).isSameAs(inactive);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_8_6_theRequestGoesToTheHeadquartersRouteWithItsOrder() {
        Map<String, Object> e = event("RAISED", "Y", null);
        when(register.requireEvent(context, 5L, "SUBMIT")).thenReturn(e);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        assertThat(service.request(context, 5L, " Order of 2019 ", 9L).id()).isEqualTo(61L);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), any());
        Submission s = sent.getValue();
        assertThat(s.workflowAction()).isEqualTo(ArrearExceptionService.ACTION);
        assertThat(s.entityName()).isEqualTo("arrear_event_hq_exception");
        assertThat(s.entityKey()).isEqualTo("5");
        assertThat(s.locationId()).isEqualTo(300L);
        assertThat(s.payload()).isEqualTo("{\"circularId\":9}");
        assertThat(s.reason()).isEqualTo("Order of 2019");
        assertThat(s.effectiveDate()).isEqualTo(LocalDate.of(2020, 1, 1));

        e.put("SCOPE_LOCATION_ID", null);
        service.request(context, 5L, "Order", 9L);
        verify(approvals)
                .submit(eq(context), argThat(x -> x != null && x.locationId() == null), any());
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                71L,
                "I-08",
                entity,
                "5",
                outcome,
                outcome == ApprovalStatus.APPROVED ? null : "Order not valid",
                payload,
                "checker.hq@x",
                Instant.parse("2026-10-29T06:00:00Z"));
    }

    @Test
    void SRS_8_6_anApprovalRecordsTheExceptionAndOrderAndComputesTheEvent() {
        service.on(
                decided(
                        "arrear_event_hq_exception",
                        ApprovalStatus.APPROVED,
                        "{\"circularId\":9}"));
        verify(jdbc).update(contains("SET hq_exception_approval_id"), eq(71L), eq(9L), eq(5L));
        verify(circulars)
                .link(
                        "checker.hq@x",
                        9L,
                        Circulars.LinkType.TRANSACTION,
                        "arrear_event",
                        "5",
                        "I-08");
        verify(retro).releaseException(5L, "checker.hq@x");
        verify(audit)
                .record(
                        eq("checker.hq@x"),
                        argThat(a -> a != null && "APPROVED".equals(a.newValue())));

        service.on(decided("arrear_event_hq_exception", ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("SET hq_exception_approval_id"), eq(71L), isNull(), eq(5L));
    }

    @Test
    void SRS_8_6_aRefusalLeavesTheEventRaisedAndOtherDecisionsAreIgnored() {
        service.on(decided("arrear_event_hq_exception", ApprovalStatus.REJECTED, null));
        verify(retro, never()).releaseException(anyLong(), any());
        verifyNoInteractions(jdbc, circulars);
        verify(audit)
                .record(
                        eq("checker.hq@x"),
                        argThat(
                                a ->
                                        a != null
                                                && "REJECTED".equals(a.newValue())
                                                && a.reason().contains("Order not valid")));
        service.on(decided("arrear_event", ApprovalStatus.APPROVED, null));
        verify(retro, never()).releaseException(anyLong(), any());
    }
}
