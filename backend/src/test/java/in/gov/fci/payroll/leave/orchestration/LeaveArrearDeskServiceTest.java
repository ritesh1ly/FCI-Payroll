package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** SRS-8-24, SRS-G-37, SRS-G-1, BR-AUDIT-009, FRS §6.4.3: what I-24 draws around its records. */
class LeaveArrearDeskServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final LeaveArrearDeskService service =
            new LeaveArrearDeskService(named, policy, approvals, audit, documents);

    private final AccessContext maker =
            new AccessContext(
                    1L, "maker@x", "M", "s", "P", 7L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    @BeforeEach
    void atAnOffice() {
        when(policy.isAtHeadquarters(maker)).thenReturn(false);
    }

    private void record(String status) {
        when(named.queryForList(anyString(), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LE_STATUS",
                                        status,
                                        "RECORD_NUMBER",
                                        "LEA-2026-0211",
                                        "ARREAR_AMOUNT",
                                        new BigDecimal("18720"))));
    }

    private void noRecord() {
        when(named.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
    }

    private void openCancellations(Long n) {
        when(named.queryForObject(
                        contains("FROM approval_queue"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(n);
    }

    private FciException refusal(Runnable call) {
        try {
            call.run();
        } catch (FciException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    private static ApprovalDecided decided(boolean approved) {
        ApprovalDecided d = mock(ApprovalDecided.class);
        when(d.concerns(LeaveArrearDeskService.ENTITY)).thenReturn(true);
        when(d.approved()).thenReturn(approved);
        when(d.entityKey()).thenReturn("11");
        when(d.approvalId()).thenReturn(5L);
        when(d.checkerLoginId()).thenReturn("checker@x");
        return d;
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_8_24_theTileReadsTheLatestOrderOfTheOffices() throws Exception {
        ArgumentCaptor<RowMapper<LeaveArrearDeskService.Summary>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(named.query(anyString(), any(SqlParameterSource.class), mapper.capture()))
                .thenReturn(List.of(new LeaveArrearDeskService.Summary("EP-01", 38)));
        assertThat(service.summary(maker))
                .isEqualTo(new LeaveArrearDeskService.Summary("EP-01", 38));
        verify(policy).require(maker, "I-24", "VIEW");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("EP-01");
        when(rs.getLong(2)).thenReturn(38L);
        assertThat(mapper.getValue().mapRow(rs, 0))
                .isEqualTo(new LeaveArrearDeskService.Summary("EP-01", 38));
    }

    @Test
    void SRS_8_24_noRecordIsAnEmptyTile() {
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenReturn(List.of());
        assertThat(service.summary(maker)).isEqualTo(new LeaveArrearDeskService.Summary(null, 0));
    }

    @Test
    void SRS_G_37_theMakerRaisesACancellationOfACalculatedRecordThroughTheRoute() {
        record("CALCULATED");
        openCancellations(0L);
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(item.id()).thenReturn(5L);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        when(approvals.submit(eq(maker), sent.capture(), (OtpProof) eq(null))).thenReturn(item);
        assertThat(service.cancel(maker, 11, " Wrong order ")).isSameAs(item);
        verify(policy).require(maker, "I-24", "SUBMIT");
        assertThat(sent.getValue().workflowAction()).isEqualTo("I24_CANCEL");
        assertThat(sent.getValue().entityName()).isEqualTo("leave_encashment_arrear");
        assertThat(sent.getValue().entityKey()).isEqualTo("11");
        assertThat(sent.getValue().reason()).isEqualTo("Wrong order");
        assertThat(sent.getValue().impactValue()).isEqualByComparingTo("18720");
        assertThat(sent.getValue().locationId()).isEqualTo(7L);
        verify(audit, times(1)).record(eq("maker@x"), any(AuditService.Entry.class));
    }

    @Test
    void SRS_G_37_anApprovedRecordMayStillBeCancelledBeforeItsInvoiceIsSent() {
        record("APPROVED");
        openCancellations(null);
        SubmittedApproval item = mock(SubmittedApproval.class);
        when(approvals.submit(eq(maker), any(Submission.class), (OtpProof) eq(null)))
                .thenReturn(item);
        assertThat(service.cancel(maker, 11, "x")).isSameAs(item);
    }

    @Test
    void SRS_G_37_aRecordSentWaitingOrPaidIsRefusedAndASecondCancellationToo() {
        for (String status :
                List.of("SUBMITTED", "INVOICED", "PAID", "CANCELLED", "NOT_ELIGIBLE")) {
            record(status);
            openCancellations(0L);
            assertThat(refusal(() -> service.cancel(maker, 11, "x")).getErrorCode())
                    .isEqualTo(LeaveErrorCode.ARREAR_STATE);
        }
        record("CALCULATED");
        openCancellations(1L);
        assertThat(refusal(() -> service.cancel(maker, 11, "x")).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_STATE);
        verify(approvals, never())
                .submit(any(AccessContext.class), any(Submission.class), any(OtpProof.class));
    }

    @Test
    void SRS_G_37_aCancellationNeedsAReason() {
        for (String reason : java.util.Arrays.asList(null, " ", "")) {
            assertThat(refusal(() -> service.cancel(maker, 11, reason)).getErrorCode())
                    .isEqualTo(LeaveErrorCode.ARREAR_SELECTION);
        }
    }

    @Test
    void R_RBAC_aRecordOutsideTheOfficesReadsAsNotFoundAndARefusedRightStopsFirst() {
        noRecord();
        assertThat(refusal(() -> service.cancel(maker, 11, "x")).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refusal(() -> service.orders(maker, 11)).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(refusal(() -> service.order(maker, "ip", 11, 2)).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(maker, "I-24", "SUBMIT");
        assertThatThrownBy(() -> service.attachOrder(maker, 11, order()))
                .isInstanceOf(FciException.class);
        verify(documents, never()).attach(anyString(), any(DocumentStore.NewAttachment.class));
    }

    @Test
    void SRS_G_37_theApprovalCancelsTheRecordNeverDeletingIt() {
        ApprovalDecided d = decided(true);
        service.on(d);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(sql.capture(), p.capture());
        assertThat(sql.getValue()).contains("le_status = 'CANCELLED'");
        assertThat(sql.getValue()).contains("le_status IN ('CALCULATED', 'APPROVED')");
        MapSqlParameterSource sent = (MapSqlParameterSource) p.getValue();
        assertThat(sent.getValue("id")).isEqualTo(11L);
        assertThat(sent.getValue("approval")).isEqualTo(5L);
        assertThat(sent.getValue("reason")).isEqualTo("Cancelled through approval 5");
        verify(audit).record(eq("checker@x"), any(AuditService.Entry.class));
    }

    @Test
    void SRS_G_37_aRefusedCancellationAndOtherEntitiesChangeNothing() {
        service.on(decided(false));
        ApprovalDecided other = mock(ApprovalDecided.class);
        when(other.concerns(LeaveArrearDeskService.ENTITY)).thenReturn(false);
        service.on(other);
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
    }

    private static LeaveArrearDeskService.NewOrder order() {
        return new LeaveArrearDeskService.NewOrder(
                "CIRCULAR", "EP-01", LocalDate.of(2026, 7, 1), "HQ", "order.pdf", new byte[] {1});
    }

    @Test
    void FRS_6_4_3_theMakerAttachesToARecordStillReady() {
        record("CALCULATED");
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        ArgumentCaptor<DocumentStore.NewAttachment> sent =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        when(documents.attach(eq("maker@x"), sent.capture())).thenReturn(view);
        assertThat(service.attachOrder(maker, 11, order())).isSameAs(view);
        assertThat(sent.getValue().entityName()).isEqualTo("leave_encashment_arrear");
        assertThat(sent.getValue().entityKey()).isEqualTo("11");
        assertThat(sent.getValue().documentTypeCode()).isEqualTo("CIRCULAR");
        assertThat(sent.getValue().referenceNumber()).isEqualTo("EP-01");
        assertThat(sent.getValue().orderDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(sent.getValue().issuingAuthority()).isEqualTo("HQ");
        assertThat(sent.getValue().fileName()).isEqualTo("order.pdf");
        assertThat(sent.getValue().containsPii()).isFalse();
    }

    @Test
    void FRS_6_4_3_aRecordAlreadySubmittedTakesNoOrder() {
        record("SUBMITTED");
        assertThat(refusal(() -> service.attachOrder(maker, 11, order())).getErrorCode())
                .isEqualTo(LeaveErrorCode.ARREAR_STATE);
    }

    @Test
    void FRS_6_4_3_theStoredOrdersAreReadWithinScope() {
        record("INVOICED");
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(documents.active("leave_encashment_arrear", "11")).thenReturn(List.of(view));
        when(documents.read("maker@x", "ip", "leave_encashment_arrear", "11", 2L))
                .thenReturn(content);
        assertThat(service.orders(maker, 11)).containsExactly(view);
        assertThat(service.order(maker, "ip", 11, 2)).isSameAs(content);
        verify(policy, times(2)).require(maker, "I-24", "VIEW");
    }
}
