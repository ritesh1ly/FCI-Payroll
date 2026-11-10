package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-01 Hold and Stop-Payment flags (SRS-1-7, SRS-1-11, SRS-G-30): the proposal's refusals (an
 * unknown member or flag, a separated record, a change already waiting, no order copy) and where
 * the change goes to the Checker (the member's office, or Headquarters for a member without one).
 */
class EmployeeControlFlagServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeControlFlagService service =
            new EmployeeControlFlagService(
                    jdbc,
                    policy,
                    approvals,
                    documents,
                    audit,
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext hr =
            new AccessContext(
                    3L,
                    "est.hr",
                    "Establishment",
                    "s",
                    "P",
                    100L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(100L, 300L));

    private static final EmployeeFlagService.Document ORDER =
            new EmployeeFlagService.Document(
                    "OTHER", "EST/HOLD/7", TODAY, "DGM (P)", "order.pdf", new byte[] {1});

    private Map<String, Object> member(Long location, String status) {
        Map<String, Object> e = new HashMap<>();
        e.put("EMPLOYEE_ID", 7L);
        e.put("EMP_NUM", "100041");
        e.put("LOCATION_ID", location);
        e.put("COMP_JOINING_DATE", java.sql.Date.valueOf(LocalDate.of(2010, 4, 1)));
        e.put("HOLD_FLAG", "N");
        e.put("STOP_PAYMENT_FLAG", "N");
        e.put("EMPLOYMENT_STATUS", status);
        when(jdbc.queryForList(anyString(), eq("100041"))).thenReturn(List.of(e));
        return e;
    }

    private static EmployeeControlFlagService.Proposal hold(String code) {
        return new EmployeeControlFlagService.Proposal(code, "Y", TODAY, " Order EST/HOLD/7 ");
    }

    private static void refused(ThrowingCallable call, ErrorCode code, String detail) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            if (detail != null) {
                                assertThat(e.getMessageArgs()).containsExactly(detail);
                            }
                        });
    }

    private void attaches() {
        when(documents.attach(eq("est.hr"), any()))
                .thenAnswer(
                        i ->
                                new DocumentStore.AttachmentView(
                                        55L,
                                        EmployeeControlFlagService.ENTITY,
                                        ((DocumentStore.NewAttachment) i.getArgument(1))
                                                .entityKey(),
                                        "OTHER",
                                        "Other document",
                                        "EST/HOLD/7",
                                        TODAY,
                                        "DGM (P)",
                                        "order.pdf",
                                        "application/pdf",
                                        1L,
                                        "abc",
                                        "ACTIVE",
                                        "est.hr",
                                        NOW));
        when(approvals.submit(any(), any(Submission.class), (OtpProof) eq(null)))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    @Test
    void SRS_1_7_anUnknownMemberOrNoMemberNumberIsNotFound() {
        refused(
                () -> service.propose(hr, null, hold("HOLD"), ORDER),
                CommonErrorCode.NOT_FOUND,
                null);
        refused(() -> service.flags(hr, "999999"), CommonErrorCode.NOT_FOUND, null);
        verify(jdbc).queryForList(anyString(), eq(""));
    }

    @Test
    void SRS_1_7_aFlagThatIsNotAPayrollControlFlagIsRefused() {
        member(300L, "IN_SERVICE");
        refused(
                () -> service.propose(hr, "100041", hold(null), ORDER),
                ApiErrorCode.M01_CONTROL_FLAG,
                "UNKNOWN_FLAG");
        // Absconding is HRMS-owned (I-21), never set here.
        refused(
                () -> service.propose(hr, "100041", hold(" ABSCONDING "), ORDER),
                ApiErrorCode.M01_CONTROL_FLAG,
                "UNKNOWN_FLAG");
        verify(approvals, never()).submit(any(), any(Submission.class), (OtpProof) any());
    }

    @Test
    void SRS_1_77_aSeparatedRecordIsReadOnly() {
        member(300L, "SEPARATED");
        refused(
                () -> service.propose(hr, "100041", hold("HOLD"), ORDER),
                ApiErrorCode.M01_CONTROL_FLAG,
                "SEPARATED");
    }

    @Test
    void SRS_G_1_aSecondChangeOfTheSameFlagWaitsForTheFirst() {
        member(300L, "IN_SERVICE");
        when(jdbc.queryForList(
                        anyString(),
                        eq(String.class),
                        eq(EmployeeControlFlagService.ENTITY),
                        eq("100041|%")))
                .thenReturn(List.of("100041|HOLD"));
        refused(
                () -> service.propose(hr, "100041", hold("HOLD"), ORDER),
                ApiErrorCode.M01_CONTROL_PENDING,
                "HOLD");
        assertThat(service.flags(hr, "100041").flags())
                .extracting(EmployeeControlFlagService.FlagState::pending)
                .containsExactly(true, false);
    }

    @Test
    void SRS_G_30_aFlagChangeWithoutItsOrderCopyIsAnAuditedRefusal() {
        member(300L, "IN_SERVICE");
        EmployeeFlagService.Document noBytes =
                new EmployeeFlagService.Document(
                        "OTHER", "EST/HOLD/7", TODAY, "DGM (P)", "order.pdf", null);
        EmployeeFlagService.Document empty =
                new EmployeeFlagService.Document(
                        "OTHER", "EST/HOLD/7", TODAY, "DGM (P)", "order.pdf", new byte[0]);
        for (EmployeeFlagService.Document d : java.util.Arrays.asList(null, noBytes, empty)) {
            refused(
                    () -> service.propose(hr, "100041", hold("HOLD"), d),
                    ApiErrorCode.M01_CONTROL_FLAG,
                    "DOCUMENT_MISSING");
        }
        verify(audit, org.mockito.Mockito.times(3))
                .submissionBlocked(
                        "est.hr",
                        EmployeeControlFlagService.ENTITY,
                        "100041|HOLD",
                        EmployeeFlagService.SCREEN,
                        "order copy");
        verify(documents, never()).attach(anyString(), any());
    }

    @Test
    void SRS_1_11_aFlagChangeGoesToTheCheckerAtTheMembersOffice() {
        member(300L, "IN_SERVICE");
        attaches();
        assertThat(service.propose(hr, "100041", hold("STOP_PAYMENT"), ORDER).id()).isEqualTo(77L);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(hr), sent.capture(), (OtpProof) eq(null));
        assertThat(sent.getValue().locationId()).isEqualTo(300L);
        assertThat(sent.getValue().entityKey()).isEqualTo("100041|STOP_PAYMENT");
        assertThat(sent.getValue().reason())
                .isEqualTo("Set STOP_PAYMENT (N -> Y) from 2026-10-26: Order EST/HOLD/7");
        assertThat(sent.getValue().payload())
                .contains("\"attachmentId\":55")
                .contains("\"reason\":\"Order EST/HOLD/7\"");
        verify(documents)
                .adopt(
                        eq("est.hr"),
                        eq(EmployeeControlFlagService.ENTITY),
                        anyString(),
                        eq(55L),
                        eq("100041|STOP_PAYMENT"));
    }

    @Test
    void SRS_1_7_aMemberWithoutAnOfficeIsReachedAtHeadquarters() {
        member(null, "IN_SERVICE");
        attaches();
        service.propose(
                hr,
                " 100041 ",
                new EmployeeControlFlagService.Proposal("HOLD", "Y", TODAY, "Order"),
                ORDER);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(hr), sent.capture(), (OtpProof) eq(null));
        assertThat(sent.getValue().locationId()).as("the user's own office").isEqualTo(100L);
        verify(policy).requireEmployeeRecord(hr, EmployeeFlagService.SCREEN, "FLAG", 7L, null);
    }
}
