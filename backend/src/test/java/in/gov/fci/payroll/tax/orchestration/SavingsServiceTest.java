package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-18 beyond the integration path (SRS-7-25, BR-TAX-015): an empty scope, a missing document or
 * kind, the own record, an entry not at the step, decisions of other screens or no longer pending,
 * a rejected entry, a rebate approved meanwhile and a document of another entry.
 */
class SavingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");

    private final TaxRepositories.Entries entries = mock(TaxRepositories.Entries.class);
    private final TaxRepositories.Savings savings = mock(TaxRepositories.Savings.class);
    private final TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final SavingsService service =
            new SavingsService(
                    entries,
                    savings,
                    sections,
                    employees,
                    store,
                    approvals,
                    statuses,
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final EmployeeDirectory.Member member =
            new EmployeeDirectory.Member(9L, "870003", "M", 1L, "IN_SERVICE");

    SavingsServiceTest() {
        when(employees.memberByEis("870003")).thenReturn(Optional.of(member));
        when(employees.member(9L)).thenReturn(Optional.of(member));
        when(statuses.pending(any())).thenReturn(List.of());
    }

    private static AccessContext context(Long employeeId, Set<Long> scope) {
        return new AccessContext(
                2L,
                "maker",
                "Maker",
                "s",
                "P",
                1L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                scope);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static ExternalIncomeDeclaration entry(Type type) {
        return ExternalIncomeDeclaration.saved(
                9L,
                "2026-27",
                type,
                type == Type.EXTERNAL_SAVINGS ? "80C" : null,
                type == Type.OTHER_INCOME ? "Honorarium" : null,
                null,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                31L,
                "maker",
                NOW);
    }

    private static ApprovalDecided decided(
            String module, String entity, String key, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                90L, module, entity, key, outcome, null, payload, "checker", NOW);
    }

    @Test
    void SRS_7_25_anEmptyScopeListsNothing() {
        assertThat(
                        service.register(context(null, Set.of()), null, null, new PageSpec(0, 20))
                                .items())
                .isEmpty();
        verifyNoInteractions(entries);
    }

    @Test
    void SRS_7_25_anEntryNeedsItsKindAndDocumentAndIsNeverTheMakersOwn() {
        SavingsService.Document doc =
                new SavingsService.Document(
                        "CERTIFICATE", null, null, "I", "p.pdf", new byte[] {1});
        SavingsService.NewEntry in =
                new SavingsService.NewEntry(
                        Type.OTHER_INCOME, null, "Honorarium", null, BigDecimal.TEN, null);
        refused(
                () -> service.save(context(9L, Set.of(1L)), "870003", in, doc),
                TaxErrorCode.OWN_DECLARATION);
        refused(
                () -> service.save(context(2L, Set.of(1L)), "870003", null, doc),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.save(
                                context(2L, Set.of(1L)),
                                "870003",
                                new SavingsService.NewEntry(
                                        null, null, "H", null, BigDecimal.TEN, null),
                                doc),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.save(context(2L, Set.of(1L)), "870003", in, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.save(
                                context(2L, Set.of(1L)),
                                "870003",
                                in,
                                new SavingsService.Document("C", null, null, "I", "p", null)),
                CommonErrorCode.VALIDATION_FAILED);
        when(employees.memberByEis("1")).thenReturn(Optional.empty());
        refused(
                () -> service.save(context(2L, Set.of(1L)), "1", in, doc),
                CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(store);
    }

    @Test
    void SRS_7_25_onlyASavedEntryIsSubmittedOrCancelledAndACancelNeedsAReason() {
        ExternalIncomeDeclaration pending = entry(Type.OTHER_INCOME);
        pending.submitted(5L);
        when(entries.lockById(4L)).thenReturn(Optional.of(pending));
        refused(() -> service.submit(context(2L, Set.of(1L)), 4L), TaxErrorCode.DECLARATION_STATE);
        refused(
                () -> service.cancel(context(2L, Set.of(1L)), 4L, "r"),
                TaxErrorCode.DECLARATION_STATE);
        refused(
                () -> service.cancel(context(2L, Set.of(1L)), 4L, " "),
                CommonErrorCode.VALIDATION_FAILED);
        refused(() -> service.submit(context(9L, Set.of(1L)), 4L), TaxErrorCode.OWN_DECLARATION);
        when(entries.lockById(5L)).thenReturn(Optional.empty());
        refused(() -> service.submit(context(2L, Set.of(1L)), 5L), CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(approvals);
    }

    @Test
    void
            BR_TAX_015_decisionsOfOtherScreensOrNoLongerPendingChangeNothingAndARejectionWritesNoSavings() {
        service.on(
                decided("I-07", SavingsService.ENTRY_ENTITY, "4", ApprovalStatus.APPROVED, null));
        ExternalIncomeDeclaration saved = entry(Type.EXTERNAL_SAVINGS);
        when(entries.lockById(4L)).thenReturn(Optional.of(saved));
        service.on(
                decided("I-18", SavingsService.ENTRY_ENTITY, "4", ApprovalStatus.APPROVED, null));
        verify(entries, never()).save(any());

        ExternalIncomeDeclaration pending = entry(Type.EXTERNAL_SAVINGS);
        pending.submitted(5L);
        when(entries.lockById(6L)).thenReturn(Optional.of(pending));
        service.on(
                decided("I-18", SavingsService.ENTRY_ENTITY, "6", ApprovalStatus.REJECTED, null));
        assertThat(pending.status()).isEqualTo(ExternalIncomeDeclaration.Status.REJECTED);
        verify(savings, never()).save(any());

        // An approved other-income entry counts without a savings row; a rejected rebate writes
        // nothing.
        ExternalIncomeDeclaration income = entry(Type.OTHER_INCOME);
        income.submitted(7L);
        when(entries.lockById(8L)).thenReturn(Optional.of(income));
        service.on(
                decided("I-18", SavingsService.ENTRY_ENTITY, "8", ApprovalStatus.APPROVED, null));
        assertThat(income.status()).isEqualTo(ExternalIncomeDeclaration.Status.APPROVED);
        service.on(
                decided(
                        "I-18",
                        SavingsService.REBATE_ENTITY,
                        "9|2026-27",
                        ApprovalStatus.REJECTED,
                        null));
        verify(savings, never()).save(any());
    }

    @Test
    void BR_TAX_015_aRebateApprovedMeanwhileIsRefusedAndAnOpenOneBlocksASecond() {
        when(savings.findByEmployeeIdAndFinYearOrderBySavingsId(9L, "2026-27"))
                .thenReturn(
                        List.of(
                                SavingsDeclaration.transportRebate(
                                        9L, "2026-27", "202604", BigDecimal.TEN, 1L)));
        String payload =
                json.writeValueAsString(
                        new SavingsService.RebateProposal(
                                9L, "2026-27", Map.of("202604", BigDecimal.TEN), "maker", NOW));
        refused(
                () ->
                        service.on(
                                decided(
                                        "I-18",
                                        SavingsService.REBATE_ENTITY,
                                        "9|2026-27",
                                        ApprovalStatus.APPROVED,
                                        payload)),
                TaxErrorCode.REBATE_MONTHS);
        when(statuses.pending(SavingsService.REBATE_ENTITY))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(
                                        3L, "9|2026-27", payload, "maker", NOW)));
        refused(
                () ->
                        service.proposeRebate(
                                context(2L, Set.of(1L)),
                                "870003",
                                Map.of("202605", BigDecimal.ONE),
                                "r"),
                ApiErrorCode.REFERENCE_CHANGE_PENDING);
        refused(
                () ->
                        service.proposeRebate(
                                context(2L, Set.of(1L)),
                                "870003",
                                Map.of("202605", BigDecimal.ONE),
                                " "),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.proposeRebate(
                                context(9L, Set.of(1L)),
                                "870003",
                                Map.of("202605", BigDecimal.ONE),
                                "r"),
                TaxErrorCode.OWN_DECLARATION);
    }

    @Test
    void SRS_7_25_aDocumentOfAnotherEntryIsNotFound() {
        when(entries.findById(4L)).thenReturn(Optional.of(entry(Type.OTHER_INCOME)));
        refused(
                () -> service.document(context(2L, Set.of(1L)), "ip", 4L, 99L),
                CommonErrorCode.NOT_FOUND);
        when(entries.findById(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.document(context(2L, Set.of(1L)), "ip", 5L, 31L),
                CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(store);
    }

    /**
     * SRS-7-25: a cancellation and a rebate proposal carry a reason; a rejected rebate records
     * nothing.
     */
    @Test
    void SRS_7_25_aCancelOrRebateWithoutAReasonIsRefusedAndARejectedRebateRecordsNothing() {
        for (String reason : new String[] {null, " "}) {
            refused(
                    () -> service.cancel(context(null, Set.of(1L)), 5L, reason),
                    CommonErrorCode.VALIDATION_FAILED);
        }
        service.on(
                decided(
                        "I-18",
                        SavingsService.REBATE_ENTITY,
                        "9|2026-27",
                        ApprovalStatus.REJECTED,
                        "{}"));
        service.on(decided("I-18", "other_entity", "9", ApprovalStatus.APPROVED, "{}"));
        verifyNoInteractions(savings);
    }

    /** SRS-7-25: an entry without TDS at source counts none, and blank section text is none. */
    @Test
    void SRS_7_25_anEntryWithoutTdsAtSourceCountsNone() {
        DocumentStore.AttachmentView proof =
                org.mockito.Mockito.mock(DocumentStore.AttachmentView.class);
        when(proof.id()).thenReturn(31L);
        when(store.attach(any(), any())).thenReturn(proof);
        when(entries.save(any())).thenAnswer(call -> call.getArgument(0));
        SavingsService.EntryView v =
                service.save(
                        context(2L, Set.of(1L)),
                        "870003",
                        new SavingsService.NewEntry(
                                Type.OTHER_INCOME, " ", "Honorarium", " ", BigDecimal.TEN, null),
                        new SavingsService.Document(
                                "CERTIFICATE", null, null, "I", "p.pdf", new byte[] {1}));
        assertThat(v.tdsOtherSource()).isEqualByComparingTo("0");
        assertThat(v.sectionCode()).isNull();
        assertThat(v.description()).isNull();
        assertThat(v.eis()).isEqualTo("870003");
    }

    /** SRS-7-25: an entry of a member no longer in the directory lists without a name. */
    @Test
    void SRS_7_25_anEntryOfAMemberNoLongerInTheDirectoryShowsNoName() {
        when(entries.inScope(any(), any(), any(), any()))
                .thenReturn(
                        new org.springframework.data.domain.PageImpl<>(
                                List.of(entry(Type.OTHER_INCOME))));
        when(employees.members(any())).thenReturn(Map.of());
        SavingsService.EntryView v =
                service.register(context(2L, Set.of(1L)), "2026-27", null, new PageSpec(0, 20))
                        .items()
                        .get(0);
        assertThat(v.eis()).isNull();
        assertThat(v.name()).isNull();
    }

    @Test
    void BR_TAX_015_aRebateWithoutAReasonIsRefused() {
        refused(
                () ->
                        service.proposeRebate(
                                context(2L, Set.of(1L)),
                                "870003",
                                Map.of("202605", BigDecimal.ONE),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
    }

    /**
     * BR-TAX-015: an approved rebate saves each month with the Checker's reason; only earlier
     * rebate months (not other savings) count as already approved.
     */
    @Test
    void BR_TAX_015_anApprovedRebateSavesItsMonthsWithTheCheckersReason() {
        when(savings.findByEmployeeIdAndFinYearOrderBySavingsId(9L, "2026-27"))
                .thenReturn(List.of(SavingsDeclaration.external(entry(Type.EXTERNAL_SAVINGS), 2L)));
        String payload =
                json.writeValueAsString(
                        new SavingsService.RebateProposal(
                                9L, "2026-27", Map.of("202604", BigDecimal.TEN), "maker", NOW));
        service.on(
                new ApprovalDecided(
                        91L,
                        "I-18",
                        SavingsService.REBATE_ENTITY,
                        "9|2026-27",
                        ApprovalStatus.APPROVED,
                        "Bus pass seen",
                        payload,
                        "checker",
                        NOW));
        verify(savings).save(any());
        org.mockito.ArgumentCaptor<AuditService.Entry> entry = org.mockito.ArgumentCaptor.captor();
        verify(audit).record(org.mockito.ArgumentMatchers.eq("checker"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("Bus pass seen");
    }
}
