package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.SanctionRules;
import in.gov.fci.payroll.cpf.business.model.BorrowingOrganisation;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.Status;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-09 recording and the AGM (CPF)'s decision beyond the integration path (plan 07 phase 07g;
 * SRS-6-27 … SRS-6-30, BR-CPF-021): amounts and months the deputation does not allow, missing
 * evidence, a month already recorded (live or correctable), an advance without a sanctioned amount,
 * a decision of another screen and an approved excess refund.
 */
class DeputationServiceRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    private final CpfRepositories.BorrowingOrgs orgs = mock(CpfRepositories.BorrowingOrgs.class);
    private final CpfRepositories.DeputationEntries entries =
            mock(CpfRepositories.DeputationEntries.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);
    private final DeputationService service =
            new DeputationService(
                    orgs,
                    entries,
                    sanctions,
                    mock(CpfLedgerService.class),
                    mock(CpfMembers.class),
                    directory,
                    documents,
                    approvals,
                    policy,
                    mock(AuditService.class),
                    jdbc,
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static final DeputationService.Evidence EVIDENCE =
            new DeputationService.Evidence(
                    "REMITTANCE",
                    "UTR-1",
                    LocalDate.of(2026, 5, 2),
                    "PSU",
                    "utr.pdf",
                    new byte[] {1});

    @BeforeEach
    void wire() {
        when(directory.memberByEis("E1"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(9L, "E1", "M", 10L, "IN_SERVICE")));
        when(policy.canAt(any(), anyString(), anyString(), anyLong())).thenReturn(true);
        BorrowingOrganisation org = mock(BorrowingOrganisation.class);
        when(org.getId()).thenReturn(4L);
        when(org.getOrgName()).thenReturn("Central PSU");
        when(org.isActive()).thenReturn(true);
        when(orgs.findById(4L)).thenReturn(Optional.of(org));
        when(entries.save(any())).thenAnswer(a -> a.getArgument(0));
    }

    /** The HRMS deputation: from, the order's end date (or null), the return date (or null). */
    @SuppressWarnings("unchecked")
    private void deputed(LocalDate from, LocalDate end, LocalDate back) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(9L)))
                .thenAnswer(
                        a -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getDate(1)).thenReturn(Date.valueOf(from));
                            when(rs.getDate(2)).thenReturn(end == null ? null : Date.valueOf(end));
                            when(rs.getDate(3))
                                    .thenReturn(back == null ? null : Date.valueOf(back));
                            return List.of(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                        });
    }

    private static DeputationService.Entry entry(EntryType type, String yymm, BigDecimal amount) {
        return new DeputationService.Entry(
                "E1",
                4L,
                type,
                null,
                yymm,
                type == EntryType.CONTRIBUTION_RECEIPT ? null : amount,
                type == EntryType.CONTRIBUTION_RECEIPT ? amount : null,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static void entryDefect(ThrowingCallable call, String why) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.DEPUTATION_ENTRY);
                            assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(why);
                        });
    }

    private CpfDeputationRecovery row(
            long id, long org, String yymm, EntryType type, Long sanction, Status status) {
        CpfDeputationRecovery r = mock(CpfDeputationRecovery.class);
        when(r.getId()).thenReturn(id);
        when(r.getBorrowingOrgId()).thenReturn(org);
        when(r.getPayrollYymm()).thenReturn(yymm);
        when(r.type()).thenReturn(type);
        when(r.getSanctionId()).thenReturn(sanction);
        when(r.status()).thenReturn(status);
        when(r.getEmployeeId()).thenReturn(9L);
        return r;
    }

    @Test
    void SRS_6_29_anEntryNeedsValidAmountsAndAMonthOfTheDeputation() {
        // Ended the day before the member's return (no end date on the order).
        deputed(LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 7, 1));
        entryDefect(
                () ->
                        service.record(
                                context,
                                entry(EntryType.CONTRIBUTION_RECEIPT, "202603", BigDecimal.ZERO),
                                EVIDENCE,
                                null),
                "the amounts are not valid for the entry type");
        entryDefect(
                () ->
                        service.record(
                                context,
                                entry(EntryType.EXCESS_REFUND, "202607", BigDecimal.TEN),
                                EVIDENCE,
                                null),
                "the month is not a month of the deputation");
    }

    @Test
    void SRS_6_29_anEntryNeedsItsRemittanceEvidence() {
        deputed(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        DeputationService.Entry refund = entry(EntryType.EXCESS_REFUND, "202605", BigDecimal.TEN);
        refused(
                CpfErrorCode.REMITTANCE_EVIDENCE,
                () -> service.record(context, refund, null, null));
        refused(
                CpfErrorCode.REMITTANCE_EVIDENCE,
                () ->
                        service.record(
                                context,
                                refund,
                                new DeputationService.Evidence(
                                        "REMITTANCE", "UTR-1", null, null, "u.pdf", null),
                                null));
        refused(
                CpfErrorCode.REMITTANCE_EVIDENCE,
                () ->
                        service.record(
                                context,
                                refund,
                                new DeputationService.Evidence(
                                        "REMITTANCE", "UTR-1", null, null, "u.pdf", new byte[0]),
                                null));
    }

    @Test
    void SRS_6_27_aMonthAlreadyRecordedIsRefusedUnlessItsEntryMayBeCorrected() {
        deputed(LocalDate.of(2026, 1, 1), null, null);
        CpfDeputationRecovery otherOrg =
                row(1L, 5L, "202605", EntryType.EXCESS_REFUND, null, Status.APPROVED);
        CpfDeputationRecovery otherMonth =
                row(2L, 4L, "202604", EntryType.EXCESS_REFUND, null, Status.APPROVED);
        CpfDeputationRecovery otherType =
                row(3L, 4L, "202605", EntryType.CONTRIBUTION_RECEIPT, null, Status.APPROVED);
        CpfDeputationRecovery otherSanction =
                row(4L, 4L, "202605", EntryType.EXCESS_REFUND, 77L, Status.APPROVED);
        CpfDeputationRecovery live =
                row(5L, 4L, "202605", EntryType.EXCESS_REFUND, null, Status.PENDING_CHECKER);
        when(entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(9L))
                .thenReturn(List.of(otherOrg, otherMonth, otherType, otherSanction, live));
        DeputationService.Entry refund = entry(EntryType.EXCESS_REFUND, "202605", BigDecimal.TEN);
        refused(
                CpfErrorCode.DEPUTATION_DUPLICATE,
                () -> service.record(context, refund, EVIDENCE, null));

        CpfDeputationRecovery rejected =
                row(6L, 4L, "202605", EntryType.EXCESS_REFUND, null, Status.REJECTED);
        when(entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(9L))
                .thenReturn(List.of(otherOrg, rejected));
        DocumentStore.AttachmentView attached = mock(DocumentStore.AttachmentView.class);
        when(attached.id()).thenReturn(31L);
        when(documents.attach(any(), any())).thenReturn(attached);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(41L, ApprovalStatus.PENDING_APPROVAL, null));
        service.record(context, refund, EVIDENCE, null);
        verify(rejected)
                .corrected(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        verify(rejected).submitted(41L);
    }

    @Test
    void BR_CPF_021_anAdvanceWithoutASanctionedAmountIsNotRecovered() {
        deputed(LocalDate.of(2026, 1, 1), null, null);
        PfSanction advance = mock(PfSanction.class);
        when(advance.getEmployeeId()).thenReturn(9L);
        when(advance.getSanctionType()).thenReturn("ADVANCE");
        when(advance.stage()).thenReturn(SanctionRules.Stage.SANCTIONED);
        when(sanctions.findById(77L)).thenReturn(Optional.of(advance));
        refused(
                CpfErrorCode.DEPUTATION_ADVANCE,
                () ->
                        service.record(
                                context,
                                new DeputationService.Entry(
                                        "E1",
                                        4L,
                                        EntryType.RECOVERY_INSTALMENT,
                                        77L,
                                        "202605",
                                        BigDecimal.TEN,
                                        null,
                                        null,
                                        null),
                                EVIDENCE,
                                null));
    }

    @Test
    void SRS_6_28_aDecisionOfAnotherScreenIsIgnoredAndAnApprovedRefundIsClosed() {
        service.on(
                new ApprovalDecided(
                        41L,
                        "C-12",
                        DeputationService.ENTITY,
                        "6",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "agm",
                        NOW));
        verify(entries, never()).findByApprovalId(any());

        CpfDeputationRecovery refund =
                row(6L, 4L, "202605", EntryType.EXCESS_REFUND, null, Status.PENDING_CHECKER);
        when(entries.findByApprovalId(42L)).thenReturn(List.of(refund));
        service.on(
                new ApprovalDecided(
                        42L,
                        DeputationService.SCREEN,
                        DeputationService.ENTITY,
                        "6",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "agm",
                        NOW));
        verify(refund).approved(null, null);
    }

    // ---------------------------------------------------------------- evidence (SRS-G-31)

    private static DocumentStore.AttachmentView file(long id) {
        return new DocumentStore.AttachmentView(
                id,
                DeputationService.ENTITY,
                "8",
                "REMITTANCE",
                "Remittance",
                "UTR-1",
                LocalDate.of(2026, 5, 2),
                "PSU",
                "utr-" + id + ".pdf",
                "application/pdf",
                1,
                "x",
                "ACTIVE",
                "maker",
                NOW);
    }

    private CpfDeputationRecovery evidenced(long pointsAt, long... active) {
        CpfDeputationRecovery r =
                row(8L, 4L, "202605", EntryType.EXCESS_REFUND, null, Status.APPROVED);
        when(r.getEvidenceAttachmentId()).thenReturn(pointsAt);
        when(entries.findById(8L)).thenReturn(Optional.of(r));
        when(directory.locationOf(9L)).thenReturn(Optional.of(10L));
        when(context.dataScope()).thenReturn(java.util.Set.of(10L));
        List<DocumentStore.AttachmentView> files =
                java.util.Arrays.stream(active)
                        .mapToObj(DeputationServiceRecordTest::file)
                        .toList();
        when(documents.active(DeputationService.ENTITY, "8")).thenReturn(files);
        return r;
    }

    @Test
    void SRS_G_31_evidenceIsAddedUpToFiveFilesAndAnEmptyFileOrAnotherOfficesEntryIsRefused() {
        evidenced(1L, 1L, 2L, 3L, 4L);
        assertThat(service.addEvidence(context, 8L, List.of(EVIDENCE))).hasSize(4);
        verify(documents).attach(any(), any());
        // The fifth active file is the last one allowed.
        evidenced(1L, 1L, 2L, 3L, 4L, 5L);
        refused(
                CpfErrorCode.EVIDENCE_FILES,
                () -> service.addEvidence(context, 8L, List.of(EVIDENCE)));
        DeputationService.Evidence empty =
                new DeputationService.Evidence(
                        "REMITTANCE", null, LocalDate.of(2026, 5, 2), "PSU", "e.pdf", new byte[0]);
        DeputationService.Evidence none =
                new DeputationService.Evidence(
                        "REMITTANCE", null, LocalDate.of(2026, 5, 2), "PSU", "e.pdf", null);
        java.util.List<DeputationService.Evidence> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        for (List<DeputationService.Evidence> bad :
                List.of(
                        List.<DeputationService.Evidence>of(),
                        List.of(empty),
                        List.of(none),
                        withNull)) {
            refused(CpfErrorCode.REMITTANCE_EVIDENCE, () -> service.addEvidence(context, 8L, bad));
        }
        refused(CpfErrorCode.REMITTANCE_EVIDENCE, () -> service.addEvidence(context, 8L, null));
        when(context.dataScope()).thenReturn(java.util.Set.of(11L));
        refused(
                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND,
                () -> service.addEvidence(context, 8L, List.of(EVIDENCE)));
        when(directory.locationOf(9L)).thenReturn(Optional.empty());
        refused(
                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND,
                () -> service.cancelEvidence(context, 8L, 1L, "wrong"));
    }

    @Test
    void SRS_G_31_aRecordedEntryCarriesFiveFilesAtMostCountingThoseItKeeps() {
        java.util.List<DeputationService.Evidence> six = java.util.Collections.nCopies(6, EVIDENCE);
        deputed(LocalDate.of(2026, 1, 1), null, null);
        refused(
                CpfErrorCode.EVIDENCE_FILES,
                () ->
                        service.recordWithFiles(
                                context,
                                entry(EntryType.EXCESS_REFUND, "202605", BigDecimal.TEN),
                                six,
                                null));
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_G_31_aWrongFileIsCancelledWithItsReasonAndTheLastFileNever() {
        CpfDeputationRecovery r = evidenced(1L, 1L, 2L);
        List<DocumentStore.AttachmentView> before = List.of(file(1L), file(2L));
        List<DocumentStore.AttachmentView> after = List.of(file(2L));
        when(documents.active(DeputationService.ENTITY, "8")).thenReturn(before).thenReturn(after);
        assertThat(service.cancelEvidence(context, 8L, 1L, " wrong advice "))
                .extracting(DeputationService.EvidenceFile::id)
                .containsExactly(2L);
        verify(documents)
                .cancel(any(), eq(DeputationService.ENTITY), eq("8"), eq(1L), eq("wrong advice"));
        verify(r).evidence(2L);
        // A file the entry does not point at leaves the pointer alone.
        r = evidenced(1L, 1L, 2L);
        service.cancelEvidence(context, 8L, 2L, "duplicate");
        verify(r, never()).evidence(anyLong());
        // The reason: required, printable, at most 500 characters (exactly 500 is kept).
        evidenced(1L, 1L, 2L);
        for (String bad : new String[] {null, "  ", "x".repeat(501), "caf\u00e9"}) {
            refused(
                    in.gov.fci.payroll.platform.error.CommonErrorCode.VALIDATION_FAILED,
                    () -> service.cancelEvidence(context, 8L, 2L, bad));
        }
        service.cancelEvidence(context, 8L, 2L, "x".repeat(500));
        // Not a file of the entry; the only active file.
        refused(
                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND,
                () -> service.cancelEvidence(context, 8L, 9L, "wrong"));
        evidenced(1L, 1L);
        refused(
                CpfErrorCode.REMITTANCE_EVIDENCE,
                () -> service.cancelEvidence(context, 8L, 1L, "wrong"));
    }

    /**
     * SRS-6-29: the first and last month of the deputation are months of it; one outside is not.
     */
    @Test
    void SRS_6_29_theBoundaryMonthsOfTheDeputationAreAcceptedAndTheOnesOutsideRefused() {
        deputed(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        String notMonth = "the month is not a month of the deputation";
        for (String outside : new String[] {"202512", "202701", "202613", "2026-5", "", null}) {
            entryDefect(
                    () ->
                            service.record(
                                    context,
                                    entry(EntryType.EXCESS_REFUND, outside, BigDecimal.TEN),
                                    EVIDENCE,
                                    null),
                    notMonth);
        }
        DocumentStore.AttachmentView attached = mock(DocumentStore.AttachmentView.class);
        when(attached.id()).thenReturn(31L);
        when(documents.attach(any(), any())).thenReturn(attached);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(41L, ApprovalStatus.PENDING_APPROVAL, null));
        for (String inside : new String[] {"202601", "202612"}) {
            service.record(
                    context,
                    entry(EntryType.EXCESS_REFUND, inside, BigDecimal.TEN),
                    EVIDENCE,
                    null);
        }
        verify(approvals, org.mockito.Mockito.times(2)).submit(any(), any(), any());
    }

    /** SRS-6-29: an amount is rupees and paise; a third decimal or a negative amount is refused. */
    @Test
    void SRS_6_29_anAmountWithAThirdDecimalOrBelowZeroOrAZeroReceiptIsRefused() {
        deputed(LocalDate.of(2026, 1, 1), null, null);
        String amounts = "the amounts are not valid for the entry type";
        for (String bad : new String[] {"10.005", "-1", "0"}) {
            entryDefect(
                    () ->
                            service.record(
                                    context,
                                    entry(EntryType.EXCESS_REFUND, "202605", new BigDecimal(bad)),
                                    EVIDENCE,
                                    null),
                    amounts);
        }
        entryDefect(
                () ->
                        service.record(
                                context,
                                entry(
                                        EntryType.CONTRIBUTION_RECEIPT,
                                        "202605",
                                        new BigDecimal("1800.001")),
                                EVIDENCE,
                                null),
                amounts);
        verify(approvals, never()).submit(any(), any(), any());
    }
}
