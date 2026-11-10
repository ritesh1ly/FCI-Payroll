package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The bulk order's refusals (plan 16 phase 16b; SRS-1-67, SRS-G-31): fewer than two tasks, an order
 * defect, an unknown task, tasks of two paying units, a task not waiting for its order, a field not
 * applicable, a DOB change whose retirement date is past; a blank subject is stored as none and one
 * OTP task makes the whole order OTP-gated.
 */
class HrmsOrderServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final HrmsChangeService changes = mock(HrmsChangeService.class);
    private final HrmsChangeApplier applier = mock(HrmsChangeApplier.class);
    private final in.gov.fci.payroll.document.api.DocumentStore documents =
            mock(in.gov.fci.payroll.document.api.DocumentStore.class);
    private static final String KEY = "PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private final HrmsOrderService service =
            new HrmsOrderService(
                    jdbc,
                    policy,
                    approvals,
                    mock(AuditService.class),
                    changes,
                    applier,
                    CLOCK,
                    documents);

    HrmsOrderServiceTest() {
        when(documents.active(HrmsOrderService.ENTITY, KEY))
                .thenReturn(
                        List.of(
                                new in.gov.fci.payroll.document.api.DocumentStore.AttachmentView(
                                        5L,
                                        HrmsOrderService.ENTITY,
                                        KEY,
                                        "OTHER",
                                        "Other document",
                                        "TO/1/2026",
                                        TODAY,
                                        "ZM North",
                                        "order.pdf",
                                        "application/pdf",
                                        10L,
                                        "abc",
                                        "ACTIVE",
                                        "maker@x",
                                        java.time.Instant.EPOCH)));
    }

    private static AccessContext maker() {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", 300L, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    private static HrmsOrderService.NewOrder order(String subject, List<Long> ids) {
        return new HrmsOrderService.NewOrder("TO/1/2026", TODAY, "ZM North", subject, ids, 5L, KEY);
    }

    private void task(long id, long unit, String status, String field, String otp) {
        Map<String, Object> t = new HashMap<>();
        t.put("TASK_NO", "HCA-2026-" + id);
        t.put("TASK_STATUS", status);
        t.put("PAYING_UNIT_ID", unit);
        t.put("OTP_REQUIRED", otp);
        t.put("PAY_EFFECT_FIELD_ID", id);
        t.put("FIELD_CODE", field);
        when(jdbc.queryForList(anyString(), eq(id))).thenReturn(List.of(t));
        Map<String, Object> f = Map.of("FIELD_CODE", field);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(f);
    }

    private static void refused(Runnable call, ApiErrorCode code, String detail) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(String.valueOf(e.getMessageArgs()[0])).contains(detail);
                        });
    }

    @Test
    void SRS_1_67_anOrderNeedsTwoKnownTasksOfOneUnitWaitingForTheirOrder() {
        refused(() -> service.create(maker(), order(null, null)), ApiErrorCode.I21_ORDER, "FEWER");
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 1L))),
                ApiErrorCode.I21_ORDER,
                "FEWER");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 2L))),
                ApiErrorCode.I21_ORDER,
                "UNKNOWN_TASK 1");
        task(1L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        task(2L, 301L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        when(applier.applicable(any())).thenReturn(true);
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 2L))),
                ApiErrorCode.I21_ORDER,
                "MIXED_UNITS");
        task(2L, 300L, "PENDING_CHECKER", "BASIC_PAY", "N");
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 2L))),
                ApiErrorCode.I21_STATE,
                "PENDING_CHECKER");
    }

    @Test
    void SRS_G_31_anOrderDefectRefusesTheOrder() {
        HrmsOrderService.NewOrder future =
                new HrmsOrderService.NewOrder(
                        "TO/1", TODAY.plusDays(1), "ZM", null, List.of(1L, 2L));
        refused(() -> service.create(maker(), future), ApiErrorCode.I21_ORDER, "IN_FUTURE");
    }

    @Test
    void SRS_1_23_aFieldNotApplicableOrAPastRetirementDobIsRefused() {
        task(1L, 300L, "RETURNED_TO_MAKER", "CATEGORY", "N");
        task(2L, 300L, "PENDING_ORDER_DETAILS", "DATE_OF_BIRTH", "N");
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 2L))),
                ApiErrorCode.I21_NOT_APPLICABLE,
                "CATEGORY");
        when(applier.applicable(any())).thenReturn(true);
        HrmsChangeService.DobPreview past =
                new HrmsChangeService.DobPreview(null, null, null, null, null, true, false, true);
        when(changes.dobPreview(2L)).thenReturn(past);
        refused(
                () -> service.create(maker(), order(null, List.of(1L, 2L))),
                ApiErrorCode.I21_ORDER,
                "DOB_NEEDS_HQ");
    }

    @Test
    void SRS_1_67_oneOtpTaskGatesTheOrderAndABlankSubjectIsNone() {
        task(1L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "Y");
        task(2L, 300L, "PENDING_ORDER_DETAILS", "DATE_OF_BIRTH", "N");
        when(applier.applicable(any())).thenReturn(true);
        when(changes.dobPreview(2L))
                .thenReturn(
                        new HrmsChangeService.DobPreview(
                                null, null, null, null, null, false, false, true));
        doAnswer(
                        i -> {
                            ((GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("TASK_ORDER_ID", 5L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(approvals.submit(any(), any(Submission.class), (OtpProof) eq(null)))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        HrmsOrderService.Created created = service.create(maker(), order("  ", List.of(1L, 2L)));
        assertThat(created).isEqualTo(new HrmsOrderService.Created(5L, "HCO-2026-0005", 77L, 2));
        verify(policy).requireAt(any(), eq(HrmsChangeService.SCREEN), eq("ORDER_OTP"), eq(300L));
    }

    private static final HrmsChangeService.OrderCopy COPY =
            new HrmsChangeService.OrderCopy(
                    "OTHER", "TO/1/2026", TODAY, "ZM North", "order.pdf", new byte[] {1, 2});

    @Test
    void SRS_G_31_theMakerStoresTheOneCopyOfAnOrderUnderAProvisionalKey() {
        when(policy.can(any(), eq(HrmsChangeService.SCREEN), eq("ORDER"))).thenReturn(true);
        when(documents.attach(eq("maker@x"), any()))
                .thenAnswer(
                        i -> {
                            DocumentStore.NewAttachment a = i.getArgument(1);
                            assertThat(a.entityName()).isEqualTo(HrmsOrderService.ENTITY);
                            assertThat(a.entityKey()).startsWith(DocumentStore.PROVISIONAL);
                            assertThat(a.referenceNumber()).isEqualTo("TO/1/2026");
                            assertThat(a.fileName()).isEqualTo("order.pdf");
                            assertThat(a.containsPii()).isTrue();
                            return view(9L, a.entityKey(), "maker@x");
                        });
        HrmsOrderService.StoredCopy stored = service.storeCopy(maker(), COPY);
        assertThat(stored.attachmentId()).isEqualTo(9L);
        assertThat(stored.provisionalKey()).startsWith(DocumentStore.PROVISIONAL);
        verify(policy, never()).require(any(), anyString(), anyString());
    }

    @Test
    void SRS_1_67_aMakerOfOtpOrdersOnlyMayStoreACopyAndOneOfNeitherIsRefused() {
        when(policy.can(any(), eq(HrmsChangeService.SCREEN), eq("ORDER_OTP"))).thenReturn(true);
        when(documents.attach(anyString(), any())).thenReturn(view(9L, KEY, "maker@x"));
        assertThat(service.storeCopy(maker(), COPY).attachmentId()).isEqualTo(9L);
        verify(policy, never()).require(any(), anyString(), anyString());

        when(policy.can(any(), eq(HrmsChangeService.SCREEN), eq("ORDER_OTP"))).thenReturn(false);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(any(), eq(HrmsChangeService.SCREEN), eq("ORDER"));
        assertThatThrownBy(() -> service.storeCopy(maker(), COPY)).isInstanceOf(FciException.class);
        verify(documents, times(1)).attach(anyString(), any());
    }

    @Test
    void SRS_1_67_anOrderWhoseCopyIsMissingOrNotTheMakersOwnIsRefused() {
        task(1L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        task(2L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        when(applier.applicable(any())).thenReturn(true);
        List<Long> ids = List.of(1L, 2L);
        // No copy named at all.
        refused(
                () ->
                        service.create(
                                maker(),
                                new HrmsOrderService.NewOrder(
                                        "TO/1/2026", TODAY, "ZM North", null, ids)),
                ApiErrorCode.I21_ORDER,
                "COPY_MISSING");
        // An id without its key, and a key that is not a provisional one.
        refused(
                () ->
                        service.create(
                                maker(),
                                new HrmsOrderService.NewOrder(
                                        "TO/1/2026", TODAY, "ZM North", null, ids, 5L, null)),
                ApiErrorCode.I21_ORDER,
                "COPY_MISSING");
        refused(
                () ->
                        service.create(
                                maker(),
                                new HrmsOrderService.NewOrder(
                                        "TO/1/2026", TODAY, "ZM North", null, ids, 5L, "EMP:1")),
                ApiErrorCode.I21_ORDER,
                "COPY_MISSING");
        // Another stored file under the key, or the copy another user stored.
        refused(
                () ->
                        service.create(
                                maker(),
                                new HrmsOrderService.NewOrder(
                                        "TO/1/2026", TODAY, "ZM North", null, ids, 6L, KEY)),
                ApiErrorCode.I21_ORDER,
                "COPY_MISSING");
        AccessContext other =
                new AccessContext(
                        4L,
                        "maker2@x",
                        "Maker 2",
                        "s",
                        "P",
                        300L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        refused(
                () -> service.create(other, order(null, ids)),
                ApiErrorCode.I21_ORDER,
                "COPY_MISSING");
        verify(approvals, never()).submit(any(), any(Submission.class), (OtpProof) any());
    }

    private static DocumentStore.AttachmentView view(long id, String key, String by) {
        return new DocumentStore.AttachmentView(
                id,
                HrmsOrderService.ENTITY,
                key,
                "OTHER",
                "Other document",
                "TO/1/2026",
                TODAY,
                "ZM North",
                "order.pdf",
                "application/pdf",
                10L,
                "abc",
                "ACTIVE",
                by,
                java.time.Instant.EPOCH);
    }

    /**
     * SRS-1-67: an order without a subject stores none, a subject given is stored stripped (the
     * statement's fifth value as the creator binds it).
     */
    @Test
    void SRS_1_67_anOrderWithoutASubjectStoresNoneAndAGivenOneStripped() throws Exception {
        task(1L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        task(2L, 300L, "PENDING_ORDER_DETAILS", "BASIC_PAY", "N");
        when(applier.applicable(any())).thenReturn(true);
        List<Object> subjects = new java.util.ArrayList<>();
        doAnswer(
                        i -> {
                            java.sql.PreparedStatement ps =
                                    mock(
                                            java.sql.PreparedStatement.class,
                                            call -> {
                                                if (call.getMethod().getName().equals("setString")
                                                        && Integer.valueOf(5)
                                                                .equals(call.getArgument(0))) {
                                                    subjects.add(call.getArgument(1));
                                                }
                                                return null;
                                            });
                            java.sql.Connection connection = mock(java.sql.Connection.class);
                            when(connection.prepareStatement(anyString(), any(String[].class)))
                                    .thenReturn(ps);
                            ((PreparedStatementCreator) i.getArgument(0))
                                    .createPreparedStatement(connection);
                            ((GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("TASK_ORDER_ID", 5L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(approvals.submit(any(), any(Submission.class), (OtpProof) eq(null)))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        service.create(maker(), order(null, List.of(1L, 2L)));
        service.create(maker(), order("  Transfer orders  ", List.of(1L, 2L)));
        assertThat(subjects).containsExactly(null, "Transfer orders");
        verify(policy, times(2))
                .requireAt(any(), eq(HrmsChangeService.SCREEN), eq("ORDER"), eq(300L));
    }

    private static AccessContext makerWithLogin(String login) {
        return new AccessContext(
                3L, login, "Maker", "s", "P", 300L, null, List.of(), Map.of(), Set.of(), Set.of(),
                Set.of());
    }

    /** SRS-G-31: the copy's uploader is stored in at most 50 characters (50 kept, 51 cut). */
    @Test
    void SRS_G_31_theUploaderOfTheCopyIsCutToFiftyCharactersAtTheBoundary() {
        when(policy.can(any(), eq(HrmsChangeService.SCREEN), eq("ORDER"))).thenReturn(true);
        when(documents.attach(anyString(), any())).thenReturn(view(9L, KEY, "maker@x"));
        String fifty = "m".repeat(50);
        String fiftyOne = fifty + "x";

        service.storeCopy(makerWithLogin(fifty), COPY);
        service.storeCopy(makerWithLogin(fiftyOne), COPY);
        service.storeCopy(makerWithLogin("short@x"), COPY);

        verify(documents, times(2)).attach(eq(fifty), any());
        verify(documents).attach(eq("short@x"), any());
    }

    /** SRS-1-67: a user with neither order right is refused and no document is stored. */
    @Test
    void SRS_1_67_aUserWithNeitherOrderRightStoresNoCopy() {
        when(policy.can(any(), eq(HrmsChangeService.SCREEN), anyString())).thenReturn(false);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(any(), eq(HrmsChangeService.SCREEN), eq("ORDER"));

        assertThatThrownBy(() -> service.storeCopy(maker(), COPY))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(ApiErrorCode.PERMISSION_DENIED));
        verify(documents, never()).attach(anyString(), any());
    }
}
