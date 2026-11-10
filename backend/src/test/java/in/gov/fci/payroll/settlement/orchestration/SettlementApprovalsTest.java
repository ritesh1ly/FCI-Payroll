package in.gov.fci.payroll.settlement.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.loans.api.LoanSettlement;
import in.gov.fci.payroll.payroll.api.ReleaseSuspensions;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementErrorCode;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O-05 approvals (plan 11; SRS-10-6, SRS-10-14, SRS-10-19, SRS-10-21, SRS-10-22, SRS-10-25,
 * SRS-10-30; BR-FNF-011, BR-FNF-015): what each submission needs - the stage, the settled
 * clearances, the decided gratuity and ex-gratia approvals, the letter or order copy, a named
 * reason - and what each decision does, a return undoing the computation.
 */
class SettlementApprovalsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final SettlementWork work = mock(SettlementWork.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final LoanSettlement loans = mock(LoanSettlement.class);
    private final ReleaseSuspensions suspensions = mock(ReleaseSuspensions.class);
    private final in.gov.fci.payroll.organisation.api.OrgDirectory directory =
            mock(in.gov.fci.payroll.organisation.api.OrgDirectory.class);
    private final in.gov.fci.payroll.arrear.api.ArrearSettlement arrears =
            mock(in.gov.fci.payroll.arrear.api.ArrearSettlement.class);

    {
        when(arrears.approvedTotal(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(BigDecimal.ZERO);
    }

    private final SettlementApprovals service =
            new SettlementApprovals(
                    jdbc,
                    policy,
                    approvals,
                    statuses,
                    work,
                    documents,
                    audit,
                    loans,
                    suspensions,
                    directory,
                    arrears);

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));
    private static final LocalDate SEPARATION = LocalDate.of(2026, 9, 30);
    private static final byte[] PDF = {'%', 'P', 'D', 'F'};

    private void caseIn(Stage stage, String status) {
        when(work.load(5L))
                .thenReturn(
                        new SettlementWork.Case(
                                5L,
                                "FNF-2026-00005",
                                7L,
                                10L,
                                SEPARATION,
                                stage,
                                9L,
                                status,
                                "RETIRED"));
    }

    private ErrorCode refusal(Runnable call) {
        return ((FciException) catchThrowable(call::run)).getErrorCode();
    }

    private SubmittedApproval approval(long id) {
        SubmittedApproval a = mock(SubmittedApproval.class);
        when(a.id()).thenReturn(id);
        return a;
    }

    @Test
    void SRS_10_19_onlyAComputedCaseAtTheComputedStageIsSubmitted() {
        when(work.load(5L))
                .thenAnswer(
                        call ->
                                new SettlementWork.Case(
                                        5L,
                                        "R",
                                        7L,
                                        10L,
                                        SEPARATION,
                                        Stage.COMPUTED,
                                        9L,
                                        "INITIATED",
                                        "RETIRED"));
        assertThat(refusal(() -> service.submit(context, 5L))).isEqualTo(SettlementErrorCode.STAGE);
        caseIn(Stage.CPF_LINKED, "COMPUTED");
        assertThat(refusal(() -> service.submit(context, 5L))).isEqualTo(SettlementErrorCode.STAGE);
        verify(approvals, never()).submit(any(), any(), any());
    }

    /**
     * SRS-10-14 (US-GAP-218): an outstanding clearance does not hold the submission (it blocks only
     * the move to Approved); an undecided gratuity-recovery approval does.
     */
    @Test
    void SRS_10_14_anOutstandingClearanceDoesNotHoldTheSubmissionAnUndecidedApprovalDoes() {
        caseIn(Stage.COMPUTED, "COMPUTED");
        when(jdbc.queryForList(contains("FROM settlement_clearance WHERE"), eq(5L)))
                .thenReturn(
                        List.of(Map.of("CLEARANCE_TYPE", "LOANS", "CLEARANCE_STATUS", "RAISED")));
        when(jdbc.queryForList(contains("hq_approval_id"), eq(Long.class), eq(9L)))
                .thenReturn(List.of(41L));
        when(statuses.of(List.of(41L))).thenReturn(Map.of(41L, ApprovalStatus.PENDING_APPROVAL));
        assertThat(refusal(() -> service.submit(context, 5L)))
                .isEqualTo(SettlementErrorCode.APPROVAL_PENDING);
        when(statuses.of(List.of(41L))).thenReturn(Map.of(41L, ApprovalStatus.APPROVED));
        when(jdbc.queryForObject(contains("SELECT net_payable"), eq(BigDecimal.class), eq(9L)))
                .thenReturn(null);
        service.submit(context, 5L);
        verify(approvals).submit(eq(context), any(), any());
    }

    @Test
    void
            SRS_10_6_SRS_10_7_BR_FNF_016_aNetRecoveryFirstRaisesItsFurtherApprovalThenWaitsForTheCreditMemo() {
        caseIn(Stage.CPF_LINKED, "COMPUTED");
        assertThat(refusal(() -> service.release(context, 5L)))
                .as("not approved")
                .isEqualTo(SettlementErrorCode.STAGE);
        caseIn(Stage.APPROVED, "APPROVED");
        when(jdbc.queryForObject(contains("SELECT net_payable"), eq(BigDecimal.class), eq(9L)))
                .thenReturn(new BigDecimal("-500"));
        when(jdbc.queryForObject(contains("net_recovery_approval_id"), eq(Long.class), eq(9L)))
                .thenReturn(null);
        service.release(context, 5L);
        when(jdbc.queryForObject(contains("net_recovery_approval_id"), eq(Long.class), eq(9L)))
                .thenReturn(77L);
        assertThat(refusal(() -> service.release(context, 5L)))
                .isEqualTo(SettlementErrorCode.APPROVAL_PENDING);
        when(jdbc.queryForObject(contains("SELECT net_payable"), eq(BigDecimal.class), eq(9L)))
                .thenReturn(new BigDecimal("1000"));
        service.release(context, 5L);
        verify(approvals, org.mockito.Mockito.times(2)).submit(eq(context), any(), any());
    }

    /** SRS-15-35, SC-05-0077: an emergency stop refuses the F&F release, the net recovery too. */
    @Test
    void SRS_15_35_aStopRefusesTheReleaseAtInitiationBeforeAnyApprovalIsRaised() {
        caseIn(Stage.APPROVED, "APPROVED");
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.RELEASE_SUSPENDED))
                .when(suspensions)
                .requireNoPaymentSuspended(10L);
        assertThat(refusal(() -> service.release(context, 5L)))
                .isEqualTo(ApiErrorCode.RELEASE_SUSPENDED);
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS-15-35: a stop names the paying unit, which a depot member's own office is not. */
    @Test
    void SRS_15_35_aStopOfThePayingUnitRefusesTheReleaseOfAMemberAtADepot() {
        caseIn(Stage.APPROVED, "APPROVED");
        in.gov.fci.payroll.organisation.business.model.OrgLocation unit =
                mock(in.gov.fci.payroll.organisation.business.model.OrgLocation.class);
        when(unit.getId()).thenReturn(77L);
        when(directory.cycleUnitOf(10L)).thenReturn(java.util.Optional.of(unit));
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.RELEASE_SUSPENDED))
                .when(suspensions)
                .requireNoPaymentSuspended(77L);
        assertThat(refusal(() -> service.release(context, 5L)))
                .isEqualTo(ApiErrorCode.RELEASE_SUSPENDED);
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS-15-35: a stop placed while the release waited refuses the approval, nothing is paid. */
    @Test
    void SRS_15_35_aStopPlacedAfterInitiationRefusesTheApprovalAndPaysNothing() {
        caseIn(Stage.APPROVED, "APPROVED");
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.RELEASE_SUSPENDED))
                .when(suspensions)
                .requireNoPaymentSuspended(10L);
        assertThat(
                        refusal(
                                () ->
                                        service.on(
                                                decided(
                                                        SettlementApprovals.RELEASE,
                                                        "FNF-1",
                                                        ApprovalStatus.APPROVED,
                                                        null))))
                .isEqualTo(ApiErrorCode.RELEASE_SUSPENDED);
        verify(jdbc, never())
                .update(contains("SET settlement_status = 'PAID'"), any(Object[].class));
        verifyNoInteractions(loans);
    }

    // SRS-19-19: ex-gratia is paid at its sanction letter's amount, no Rule Engine item.
    // SC-06-0017: ex-gratia is paid at the amount of its sanction letter, no Rule Engine item.
    @Test
    void BR_FNF_015_anExGratiaNeedsTheLetterNumberDateAmountAndTheLetterItself() {
        caseIn(Stage.CLEARANCES_RECEIVED, "INITIATED");
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (Object[] bad :
                new Object[][] {
                    {null, day, BigDecimal.TEN, PDF},
                    {" ", day, BigDecimal.TEN, PDF},
                    {"L/1", null, BigDecimal.TEN, PDF},
                    {"L/1", day, null, PDF},
                    {"L/1", day, BigDecimal.ZERO, PDF},
                    {"L/1", day, BigDecimal.TEN, null},
                    {"L/1", day, BigDecimal.TEN, new byte[0]}
                }) {
            assertThat(
                            refusal(
                                    () ->
                                            service.exGratia(
                                                    context,
                                                    5L,
                                                    (String) bad[0],
                                                    (LocalDate) bad[1],
                                                    (BigDecimal) bad[2],
                                                    "GM",
                                                    "l.pdf",
                                                    (byte[]) bad[3])))
                    .isEqualTo(SettlementErrorCode.ENTRY);
        }
        caseIn(Stage.APPROVED, "APPROVED");
        assertThat(
                        refusal(
                                () ->
                                        service.exGratia(
                                                context,
                                                5L,
                                                "L/1",
                                                day,
                                                BigDecimal.TEN,
                                                "GM",
                                                "l.pdf",
                                                PDF)))
                .as("past computation")
                .isEqualTo(SettlementErrorCode.STAGE);
        caseIn(Stage.COMPUTED, "COMPUTED");
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(anyString(), any())).thenReturn(view);
        SubmittedApproval a = approval(31L);
        when(approvals.submit(any(), any(), any())).thenReturn(a);
        assertThat(service.exGratia(context, 5L, " L/1 ", day, BigDecimal.TEN, "GM", "l.pdf", PDF))
                .isSameAs(a);
        verify(jdbc).update(contains("SET entry_status = 'SUBMITTED'"), eq(31L), eq(9L), eq("L/1"));
    }

    /**
     * SRS-10-25 (US-11-11 AC5): the same ex-gratia entry serves every payroll - a labour case
     * (separation entered on M-02, member SEPARATED) takes the letter, the entry and the Checker's
     * approval exactly as an employee case; nothing branches on the payroll type.
     */
    @Test
    void SRS_10_25_aLabourCaseTakesTheSameExGratiaEntryAndApproval() {
        when(work.load(5L))
                .thenReturn(
                        new SettlementWork.Case(
                                5L,
                                "FNF-LBR-00005",
                                7L,
                                10L,
                                SEPARATION,
                                Stage.COMPUTED,
                                9L,
                                "COMPUTED",
                                "SEPARATED"));
        when(documents.attach(anyString(), any()))
                .thenReturn(mock(DocumentStore.AttachmentView.class));
        SubmittedApproval a = approval(32L);
        when(approvals.submit(any(), any(), any())).thenReturn(a);
        assertThat(
                        service.exGratia(
                                context,
                                5L,
                                "LBR/EXG/1",
                                LocalDate.of(2026, 10, 1),
                                new BigDecimal("5000"),
                                "GM (Region)",
                                "letter.pdf",
                                PDF))
                .isSameAs(a);
        verify(jdbc)
                .update(
                        contains("SET entry_status = 'SUBMITTED'"),
                        eq(32L),
                        eq(9L),
                        eq("LBR/EXG/1"));
    }

    private final AccessContext checker =
            new AccessContext(
                    2L,
                    "ddo@x",
                    "D",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private void submittedBy(String maker) {
        when(statuses.pending(SettlementWork.ENTITY, "FNF-2026-00005"))
                .thenReturn(
                        maker == null
                                ? List.of()
                                : List.of(
                                        new ApprovalStatuses.Pending(
                                                61L,
                                                "FNF-2026-00005",
                                                null,
                                                maker,
                                                Instant.parse("2026-10-27T04:00:00Z"))));
    }

    // SC-11-0038: an outstanding clearance blocks approval unless the Checker records a named,
    // reasoned exclusion.
    @Test
    void SRS_10_14_anExclusionNeedsANamedReasonAndAnOutstandingClearance() {
        caseIn(Stage.COMPUTED, "COMPUTED");
        submittedBy("maker@x");
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", null)))
                .isEqualTo(SettlementErrorCode.ENTRY);
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", "é")))
                .isEqualTo(SettlementErrorCode.ENTRY);
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", " ")))
                .isEqualTo(SettlementErrorCode.ENTRY);
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", "x".repeat(501))))
                .as("at most 500")
                .isEqualTo(SettlementErrorCode.ENTRY);
        when(jdbc.queryForList(
                        contains("SELECT clearance_status FROM settlement_clearance"),
                        eq(String.class),
                        eq(5L),
                        eq("LOANS")))
                .thenReturn(List.of());
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", "Waived")))
                .as("no such clearance")
                .isEqualTo(SettlementErrorCode.ENTRY);
        when(jdbc.queryForList(
                        contains("SELECT clearance_status FROM settlement_clearance"),
                        eq(String.class),
                        eq(5L),
                        eq("LOANS")))
                .thenReturn(List.of("RECEIVED"));
        assertThat(refusal(() -> service.exclude(checker, 5L, "LOANS", "Waived")))
                .as("not outstanding")
                .isEqualTo(SettlementErrorCode.ENTRY);
        when(jdbc.queryForList(
                        contains("SELECT clearance_status FROM settlement_clearance"),
                        eq(String.class),
                        eq(5L),
                        eq("LOANS")))
                .thenReturn(List.of("PENDING"));
        verify(jdbc, never())
                .update(contains("clearance_status = 'EXCLUDED'"), any(Object[].class));
        verify(approvals, never()).submit(any(), any(), any());
    }

    /**
     * SRS-10-14 (US-GAP-219): the Checker records the exclusion against the settlement approval
     * they are deciding - never before the submission, never the Maker who submitted it (R-MC) -
     * and it is audited with that approval. No approval route of its own is raised.
     */
    @Test
    void SRS_10_14_theCheckerRecordsTheExclusionAgainstTheSubmittedSettlementNeverItsMaker() {
        when(jdbc.queryForList(
                        contains("SELECT clearance_status FROM settlement_clearance"),
                        eq(String.class),
                        eq(5L),
                        eq("ESTABLISHMENT_NO_DUES")))
                .thenReturn(List.of("RAISED"));
        caseIn(Stage.CLEARANCES_RAISED, "INITIATED");
        submittedBy("maker@x");
        assertThat(refusal(() -> service.exclude(checker, 5L, "ESTABLISHMENT_NO_DUES", "Nil")))
                .as("not computed")
                .isEqualTo(SettlementErrorCode.STAGE);
        caseIn(Stage.COMPUTED, "COMPUTED");
        submittedBy(null);
        assertThat(refusal(() -> service.exclude(checker, 5L, "ESTABLISHMENT_NO_DUES", "Nil")))
                .as("not submitted to the Checker")
                .isEqualTo(SettlementErrorCode.STAGE);
        submittedBy("DDO@X");
        assertThat(refusal(() -> service.exclude(checker, 5L, "ESTABLISHMENT_NO_DUES", "Nil")))
                .as("the Maker of the submission")
                .isEqualTo(in.gov.fci.payroll.platform.error.ApiErrorCode.MAKER_CHECKER);
        verify(jdbc, never())
                .update(contains("clearance_status = 'EXCLUDED'"), any(Object[].class));

        submittedBy("maker@x");
        service.exclude(checker, 5L, "ESTABLISHMENT_NO_DUES", " Quarters never allotted ");
        verify(policy, org.mockito.Mockito.atLeastOnce())
                .requireEmployeeRecord(checker, SettlementCaseOpener.SCREEN, "APPROVE", 7L, 10L);
        verify(jdbc)
                .update(
                        contains("SET clearance_status = 'EXCLUDED'"),
                        eq("Excluded: Quarters never allotted"),
                        eq(61L),
                        eq(5L),
                        eq("ESTABLISHMENT_NO_DUES"));
        org.mockito.ArgumentCaptor<AuditService.Entry> entry =
                org.mockito.ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("ddo@x"), entry.capture());
        assertThat(entry.getValue().approvalId()).isEqualTo(61L);
        assertThat(entry.getValue().oldValue()).isEqualTo("RAISED");
        assertThat(entry.getValue().newValue()).isEqualTo("EXCLUDED");
        assertThat(entry.getValue().reason()).contains("Quarters never allotted");
        verify(approvals, never()).submit(any(), any(), any());
    }

    /**
     * SRS-10-24 (BMAD TR-029): confirming a recovery's source restates the computed settlement -
     * against gratuity it leaves the payroll net payable whole, against payroll dues it reduces it;
     * the change is audited; a settlement not computed is left for its computation.
     */
    @Test
    void SRS_10_24_aConfirmedSourceRestatesTheNetPayableOrTheRecoveredGratuity() {
        caseIn(Stage.COMPUTED, "COMPUTED");
        when(jdbc.queryForObject(
                        contains("FROM fs_recovery_detail WHERE recovery_detail_id"),
                        eq(Integer.class),
                        eq(2L),
                        eq(9L)))
                .thenReturn(1);
        Map<String, Object> computed = new java.util.HashMap<>();
        computed.put("PENDING_SALARY_WAGE", new BigDecimal("1000"));
        computed.put("LEAVE_ENCASHMENT_AMOUNT", new BigDecimal("75000"));
        computed.put("TOTAL_ARREARS", null);
        computed.put("EX_GRATIA_AMOUNT", BigDecimal.ZERO);
        computed.put("NET_PAYABLE", new BigDecimal("75500"));
        computed.put("SETTLEMENT_STATUS", "COMPUTED");
        when(jdbc.queryForMap(contains("FROM final_settlement WHERE settlement_id"), eq(9L)))
                .thenReturn(computed);
        when(jdbc.queryForObject(
                        contains("recovery_source = ?"),
                        eq(BigDecimal.class),
                        eq(9L),
                        eq("PAYROLL_DUES")))
                .thenReturn(new BigDecimal("500"));
        when(jdbc.queryForObject(
                        contains("recovery_source = ?"),
                        eq(BigDecimal.class),
                        eq(9L),
                        eq("GRATUITY")))
                .thenReturn(null);
        service.source(context, 5L, 2L, "PAYROLL_DUES", null, null, null, null, null, null);
        verify(jdbc)
                .update(
                        contains("SET total_recoveries = ?, recovered_from_gratuity = ?"),
                        argThat((BigDecimal v) -> v.compareTo(new BigDecimal("500")) == 0),
                        argThat((BigDecimal v) -> v.signum() == 0),
                        argThat((BigDecimal v) -> v.compareTo(new BigDecimal("75500")) == 0),
                        eq(9L));
        verify(audit).record(eq("maker@x"), any());

        computed.put("SETTLEMENT_STATUS", "INITIATED");
        org.mockito.Mockito.clearInvocations(jdbc);
        service.source(context, 5L, 2L, "PAYROLL_DUES", null, null, null, null, null, null);
        verify(jdbc, never()).update(contains("SET total_recoveries"), any(Object[].class));
    }

    private void settlementIs(String status, Integer stage) {
        settlementIs(status, stage, null);
    }

    private void settlementIs(String status, Integer stage, BigDecimal net) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("SETTLEMENT_STATUS", status);
        row.put("CASE_STAGE", stage);
        row.put("NET_PAYABLE", net);
        when(jdbc.queryForList(contains("SELECT s.settlement_status, c.case_stage"), eq(5L)))
                .thenReturn(status == null && stage == null ? List.of() : List.of(row));
    }

    /**
     * SRS-10-14 (US-GAP-218): the Checker's approval is refused while any clearance is outstanding
     * and not excluded (PENDING or RAISED); received, not applicable and excluded ones let it
     * through; a settlement sent back for recomputation after its submission is not approved.
     */
    @Test
    void SRS_10_14_theCheckersApprovalIsRefusedWhileAClearanceIsOutstanding() {
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        settlementIs("COMPUTED", 4);
        for (String outstanding : List.of("PENDING", "RAISED")) {
            when(jdbc.queryForList(contains("FROM settlement_clearance WHERE"), eq(5L)))
                    .thenReturn(
                            List.of(
                                    Map.of(
                                            "CLEARANCE_TYPE",
                                            "ALLOWANCES",
                                            "CLEARANCE_STATUS",
                                            "RECEIVED"),
                                    Map.of(
                                            "CLEARANCE_TYPE",
                                            "ESTABLISHMENT_NO_DUES",
                                            "CLEARANCE_STATUS",
                                            outstanding)));
            FciException refused =
                    (FciException)
                            catchThrowable(
                                    () ->
                                            service.on(
                                                    decided(
                                                            SettlementWork.ENTITY,
                                                            "FNF-1",
                                                            ApprovalStatus.APPROVED,
                                                            null)));
            assertThat(refused.getErrorCode())
                    .as(outstanding)
                    .isEqualTo(SettlementErrorCode.CLEARANCE);
            assertThat(refused.getMessageArgs()).containsExactly("ESTABLISHMENT_NO_DUES");
        }
        settlementIs("INITIATED", 2);
        FciException stale =
                (FciException)
                        catchThrowable(
                                () ->
                                        service.on(
                                                decided(
                                                        SettlementWork.ENTITY,
                                                        "FNF-1",
                                                        ApprovalStatus.APPROVED,
                                                        null)));
        assertThat(stale.getErrorCode()).isEqualTo(SettlementErrorCode.STAGE);
        assertThat(stale.getMessageArgs()).containsExactly("CLEARANCES_RAISED", "COMPUTED");
        settlementIs(null, null);
        assertThat(
                        ((FciException)
                                        catchThrowable(
                                                () ->
                                                        service.on(
                                                                decided(
                                                                        SettlementWork.ENTITY,
                                                                        "FNF-1",
                                                                        ApprovalStatus.APPROVED,
                                                                        null))))
                                .getMessageArgs())
                .containsExactly("-", "COMPUTED");
        verify(jdbc, never())
                .update(contains("SET settlement_status = 'APPROVED'"), any(Object[].class));

        settlementIs("COMPUTED", 4);
        when(jdbc.queryForList(contains("FROM settlement_clearance WHERE"), eq(5L)))
                .thenReturn(
                        List.of(
                                Map.of("CLEARANCE_TYPE", "A", "CLEARANCE_STATUS", "RECEIVED"),
                                Map.of("CLEARANCE_TYPE", "B", "CLEARANCE_STATUS", "NOT_APPLICABLE"),
                                Map.of("CLEARANCE_TYPE", "C", "CLEARANCE_STATUS", "EXCLUDED")));
        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("SET settlement_status = 'APPROVED'"), eq(4L), eq(8L), eq(5L));
    }

    // SC-11-0057: a recovery against gratuity waits for the HQ approval; the case cannot move on
    // while pending.
    @Test
    void SRS_10_21_aRecoveryComesFromPayrollDuesOrFromGratuityWithItsReasonOrderAndHqApproval() {
        caseIn(Stage.APPROVED, "APPROVED");
        assertThat(
                        refusal(
                                () ->
                                        service.source(
                                                context,
                                                5L,
                                                2L,
                                                "PAYROLL_DUES",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)))
                .isEqualTo(SettlementErrorCode.STAGE);
        caseIn(Stage.COMPUTED, "COMPUTED");
        when(jdbc.queryForObject(
                        contains("FROM fs_recovery_detail WHERE recovery_detail_id"),
                        eq(Integer.class),
                        eq(2L),
                        eq(9L)))
                .thenReturn(null)
                .thenReturn(0)
                .thenReturn(1);
        assertThat(
                        refusal(
                                () ->
                                        service.source(
                                                context,
                                                5L,
                                                2L,
                                                "PAYROLL_DUES",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(
                        refusal(
                                () ->
                                        service.source(
                                                context,
                                                5L,
                                                2L,
                                                "PAYROLL_DUES",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null)))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        service.source(context, 5L, 2L, "PAYROLL_DUES", null, null, null, null, null, null);
        verify(jdbc).update(contains("SET recovery_source = 'PAYROLL_DUES'"), eq(2L));

        when(jdbc.queryForObject(
                        contains("FROM fs_recovery_detail WHERE recovery_detail_id"),
                        eq(Integer.class),
                        eq(2L),
                        eq(9L)))
                .thenReturn(1);
        assertThat(
                        refusal(
                                () ->
                                        service.source(
                                                context,
                                                5L,
                                                2L,
                                                "CPF_FINAL",
                                                "r",
                                                "o",
                                                null,
                                                null,
                                                null,
                                                PDF)))
                .as("BR-FNF-011: never the CPF final payment")
                .isEqualTo(SettlementErrorCode.ENTRY);
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (Object[] bad :
                new Object[][] {
                    {null, "O/1", PDF},
                    {"é", "O/1", PDF},
                    {"Dues", null, PDF},
                    {"Dues", "é", PDF},
                    {"Dues", "O/1", null},
                    {"Dues", "O/1", new byte[0]}
                }) {
            assertThat(
                            refusal(
                                    () ->
                                            service.source(
                                                    context,
                                                    5L,
                                                    2L,
                                                    "GRATUITY",
                                                    (String) bad[0],
                                                    (String) bad[1],
                                                    day,
                                                    "GM",
                                                    "o.pdf",
                                                    (byte[]) bad[2])))
                    .isEqualTo(SettlementErrorCode.ENTRY);
        }
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(view.id()).thenReturn(12L);
        when(documents.attach(anyString(), any())).thenReturn(view);
        SubmittedApproval hq = approval(44L);
        when(approvals.submit(any(), any(), any())).thenReturn(hq);
        // DR-3: nothing is recovered from a gratuity not computed (0 or none) or beyond it.
        java.math.BigDecimal[][] refusedGratuity = {
            {null, null},
            {java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO},
            {new java.math.BigDecimal("1000"), new java.math.BigDecimal("1000.01")}
        };
        for (java.math.BigDecimal[] g : refusedGratuity) {
            when(jdbc.queryForObject(
                            contains("SELECT gratuity_amount FROM final_settlement"),
                            eq(java.math.BigDecimal.class),
                            any(Object[].class)))
                    .thenReturn(g[0]);
            when(jdbc.queryForObject(
                            contains("SUM(recovery_amount)"),
                            eq(java.math.BigDecimal.class),
                            any(Object[].class)))
                    .thenReturn(g[1]);
            assertThat(
                            refusal(
                                    () ->
                                            service.source(
                                                    context,
                                                    5L,
                                                    2L,
                                                    "GRATUITY",
                                                    "Licence fee",
                                                    "O/1",
                                                    day,
                                                    "GM",
                                                    "o.pdf",
                                                    PDF)))
                    .as(String.valueOf(g[0]))
                    .isEqualTo(SettlementErrorCode.ENTRY);
        }
        verify(approvals, never()).submit(any(), any(), any());
        when(jdbc.queryForObject(
                        contains("SUM(recovery_amount)"),
                        eq(java.math.BigDecimal.class),
                        any(Object[].class)))
                .thenReturn(new java.math.BigDecimal("1000"));
        Map<String, Object> computed = new java.util.HashMap<>();
        computed.put("PENDING_SALARY_WAGE", new BigDecimal("1000"));
        computed.put("LEAVE_ENCASHMENT_AMOUNT", new BigDecimal("75000"));
        computed.put("TOTAL_ARREARS", BigDecimal.ZERO);
        computed.put("EX_GRATIA_AMOUNT", BigDecimal.ZERO);
        computed.put("NET_PAYABLE", new BigDecimal("76000"));
        computed.put("SETTLEMENT_STATUS", "COMPUTED");
        when(jdbc.queryForMap(contains("FROM final_settlement WHERE settlement_id"), eq(9L)))
                .thenReturn(computed);
        service.source(
                context, 5L, 2L, "GRATUITY", " Licence fee ", " O/1 ", day, "GM", "o.pdf", PDF);
        verify(jdbc)
                .update(
                        contains("SET recovery_source = 'GRATUITY'"),
                        eq("Licence fee"),
                        eq("O/1"),
                        eq(12L),
                        eq(44L),
                        eq(2L));
        // SRS-10-24 (BMAD TR-029): the gratuity source restates the computed settlement at once.
        verify(jdbc)
                .update(
                        contains("SET total_recoveries = ?, recovered_from_gratuity = ?"),
                        argThat((BigDecimal v) -> v.compareTo(new BigDecimal("2000")) == 0),
                        argThat((BigDecimal v) -> v.compareTo(new BigDecimal("1000")) == 0),
                        argThat((BigDecimal v) -> v.compareTo(new BigDecimal("75000")) == 0),
                        eq(9L));
    }

    private ApprovalDecided decided(
            String entity, String key, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                4L, "O-05", entity, key, outcome, "recheck", payload, "ddo@x", Instant.now(), 8L);
    }

    // SC-11-0040: a return before finalisation undoes the computation with its reason.
    @Test
    void SRS_10_30_theCheckerApprovesTheSettlementOrReturnsItAndTheComputationIsUndone() {
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        settlementIs("COMPUTED", 4);
        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("SET settlement_status = 'APPROVED'"), eq(4L), eq(8L), eq(5L));
        // The return: back to Clearances raised, then on to received once every one is settled.
        when(jdbc.queryForList(
                        contains(
                                "SELECT clearance_status FROM settlement_clearance WHERE"
                                        + " settlement_case_id"),
                        eq(String.class),
                        eq(5L)))
                .thenReturn(List.of("RECEIVED", "EXCLUDED"));
        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.RETURNED, null));
        verify(jdbc)
                .update(
                        contains("SET settlement_status = 'INITIATED' WHERE settlement_case_id"),
                        eq(5L));
        verify(jdbc)
                .update(contains("SET case_stage = ? WHERE settlement_case_id = ?"), eq(2), eq(5L));
        verify(jdbc)
                .update(
                        contains("WHERE settlement_case_id = ? AND case_stage = ?"),
                        eq(3),
                        eq(5L),
                        eq(2));
        verify(audit, org.mockito.Mockito.times(2)).record(eq("ddo@x"), any());
    }

    @Test
    void SRS_10_19_anApprovedReleasePostsAndClosesTheCaseARejectedOneChangesNothing() {
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.REJECTED, null));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        caseIn(Stage.APPROVED, "APPROVED");
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("SET settlement_status = 'PAID'"), eq(8L), any(), eq(5L));
    }

    /** SRS-8-16: arrears paid in the settlement leave APPROVED, so no cycle pays them again. */
    @Test
    void SRS_8_16_anApprovedReleaseMarksTheArrearsPaidARejectedOneDoesNot() {
        caseIn(Stage.APPROVED, "APPROVED");
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.REJECTED, null));
        verify(arrears, never()).paid(anyLong(), any(), anyLong());
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(arrears).paid(anyLong(), any(), anyLong());
    }

    /** SRS-10-28: an arrear approved after the computation is not marked paid unpaid. */
    @Test
    void SRS_10_28_anArrearChangedAfterTheComputationRefusesTheRelease() {
        caseIn(Stage.APPROVED, "APPROVED");
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        when(arrears.approvedTotal(anyLong())).thenReturn(new BigDecimal("900.00"));
        when(jdbc.queryForObject(contains("total_arrears"), eq(BigDecimal.class), anyLong()))
                .thenReturn(new BigDecimal("500.00"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                SettlementApprovals.RELEASE,
                                                "FNF-1",
                                                ApprovalStatus.APPROVED,
                                                null)))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        verify(arrears, never()).paid(anyLong(), any(), anyLong());
    }

    /** SRS-10-28: the amount in the statement is the amount open: the release goes through. */
    @Test
    void SRS_10_28_theSameAmountReleases() {
        caseIn(Stage.APPROVED, "APPROVED");
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        when(arrears.approvedTotal(anyLong())).thenReturn(new BigDecimal("500.00"));
        when(jdbc.queryForObject(contains("total_arrears"), eq(BigDecimal.class), anyLong()))
                .thenReturn(new BigDecimal("500.0"));

        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.APPROVED, null));

        verify(arrears).paid(anyLong(), any(), anyLong());
    }

    /**
     * SRS-10-7, UC-06 postcondition (l.2917): posting hands every itemised loan and advance to the
     * loan ledger to be closed as recovered at settlement, with the release's approval; a rejected
     * release closes nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_10_7_postingClosesEveryItemisedLoanAndAdvanceOnTheLedger() {
        caseIn(Stage.APPROVED, "APPROVED");
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.REJECTED, null));
        verify(loans, never()).settle(any(), any(), any(), any(), anyLong());
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        when(jdbc.query(
                        contains("r.recovery_type IN ('LOAN', 'ADVANCE')"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq(5L)))
                .thenAnswer(
                        inv -> {
                            org.springframework.jdbc.core.RowMapper<LoanSettlement.Recovered> m =
                                    inv.getArgument(1);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getString(1)).thenReturn("41 LN/41");
                            when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("1200.50"));
                            return List.of(m.mapRow(rs, 0));
                        });
        service.on(decided(SettlementApprovals.RELEASE, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(loans)
                .settle(
                        eq(List.of(new LoanSettlement.Recovered(41L, new BigDecimal("1200.50")))),
                        any(),
                        eq("FNF-1"),
                        eq("ddo@x"),
                        eq(4L));
    }

    @Test
    void SRS_10_25_exGratiaExclusionAndNetRecoveryDecisions() {
        service.on(
                decided(SettlementApprovals.EX_GRATIA, "FNF-1|L/1", ApprovalStatus.RETURNED, null));
        verify(jdbc)
                .update(
                        contains("UPDATE fs_ex_gratia_entry SET entry_status"),
                        eq("RETURNED"),
                        eq("ddo@x"),
                        any(),
                        eq(4L));
        service.on(
                decided(SettlementApprovals.EX_GRATIA, "FNF-1|L/1", ApprovalStatus.APPROVED, null));
        verify(jdbc)
                .update(
                        contains("UPDATE fs_ex_gratia_entry SET entry_status"),
                        eq("APPROVED"),
                        eq("ddo@x"),
                        any(),
                        eq(4L));

        service.on(
                decided(
                        SettlementApprovals.CLEARANCE,
                        "FNF-1|LOANS",
                        ApprovalStatus.REJECTED,
                        null));
        verify(jdbc, never())
                .update(contains("clearance_status = 'EXCLUDED'"), any(Object[].class));
        when(jdbc.queryForList(
                        contains("SELECT settlement_case_id FROM settlement_case"),
                        eq(Long.class),
                        eq("FNF-1")))
                .thenReturn(List.of(5L));
        when(jdbc.queryForList(
                        contains(
                                "SELECT clearance_status FROM settlement_clearance WHERE"
                                        + " settlement_case_id"),
                        eq(String.class),
                        eq(5L)))
                .thenReturn(List.of("RECEIVED", "EXCLUDED"))
                .thenReturn(List.of("RAISED"));
        service.on(
                decided(
                        SettlementApprovals.CLEARANCE,
                        "FNF-1|LOANS",
                        ApprovalStatus.APPROVED,
                        "Waived"));
        service.on(
                decided(
                        SettlementApprovals.CLEARANCE,
                        "FNF-1|LOANS",
                        ApprovalStatus.APPROVED,
                        "Waived"));
        verify(jdbc)
                .update(
                        contains("WHERE settlement_case_id = ? AND case_stage = ?"),
                        eq(3),
                        eq(5L),
                        eq(2));

        service.on(
                decided(SettlementApprovals.NET_RECOVERY, "FNF-1", ApprovalStatus.REJECTED, null));
        verify(jdbc, never()).update(contains("is_net_recovery = 'Y'"), any(Object[].class));
        service.on(
                decided(SettlementApprovals.NET_RECOVERY, "FNF-1", ApprovalStatus.APPROVED, null));
        verify(jdbc).update(contains("is_net_recovery = 'Y'"), eq(4L), eq("FNF-1"));
        service.on(decided("other", "x", ApprovalStatus.APPROVED, null));
        verify(audit, never()).record(anyString(), any());
    }

    /**
     * SRS-10-19 / SRS-10-14 (Wave A review): the submission carries the net payable the Checker is
     * asked to approve; it is read back from the item's payload (an item without it reads null).
     */
    @Test
    void SRS_10_19_theSubmissionCarriesTheNetPayableSubmitted() {
        caseIn(Stage.COMPUTED, "COMPUTED");
        when(jdbc.queryForObject(contains("SELECT net_payable"), eq(BigDecimal.class), eq(9L)))
                .thenReturn(new BigDecimal("125000.50"));
        service.submit(context, 5L);
        org.mockito.ArgumentCaptor<in.gov.fci.payroll.platform.approval.Submission> sent =
                org.mockito.ArgumentCaptor.forClass(
                        in.gov.fci.payroll.platform.approval.Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), any());
        assertThat(sent.getValue().payload()).isEqualTo("{\"netPayable\":\"125000.5\"}");
        assertThat(SettlementApprovals.submittedNet(sent.getValue().payload()))
                .isEqualByComparingTo("125000.50");
        assertThat(SettlementApprovals.submittedNet(SettlementApprovals.submittedPayload(null)))
                .isEqualByComparingTo("0");
        assertThat(SettlementApprovals.submittedNet("{\"netPayable\":\"-40\"}"))
                .isEqualByComparingTo("-40");
        assertThat(SettlementApprovals.submittedNet(null)).isNull();
        assertThat(SettlementApprovals.submittedNet("{}")).isNull();
        assertThat(SettlementApprovals.submittedNet("{\"netPayable\":\"x\"}")).isNull();
        assertThat(SettlementApprovals.submittedNet("{\"netPayable\":\"12"))
                .as("unterminated")
                .isNull();
    }

    /**
     * SRS-10-14 / SRS-10-30 (Wave A review): submit, dues itemised, recomputed at a new net - the
     * Checker's approval of the item submitted at the old net is refused (FNF-409-CHANGED), before
     * the OTP step (ApprovalDeciding) and again in the decision; at the submitted net it passes. An
     * older item without the net in its payload is checked on the status and clearances only.
     */
    @Test
    void SRS_10_14_anApprovalNeverStandsForASettlementTheCheckerDidNotSee() {
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        when(jdbc.queryForList(contains("FROM settlement_clearance WHERE"), eq(5L)))
                .thenReturn(List.of(Map.of("CLEARANCE_TYPE", "A", "CLEARANCE_STATUS", "RECEIVED")));
        String submitted = SettlementApprovals.submittedPayload(new BigDecimal("1000"));
        settlementIs("COMPUTED", 4, new BigDecimal("900"));
        in.gov.fci.payroll.platform.approval.ApprovalDeciding deciding =
                new in.gov.fci.payroll.platform.approval.ApprovalDeciding(
                        4L, "O-05", SettlementWork.ENTITY, "FNF-1", submitted, "ddo@x");
        FciException early = (FciException) catchThrowable(() -> service.on(deciding));
        assertThat(early.getErrorCode()).isEqualTo(SettlementErrorCode.CHANGED);
        assertThat(early.getMessageArgs()).containsExactly("1000", "900");
        FciException late =
                (FciException)
                        catchThrowable(
                                () ->
                                        service.on(
                                                decided(
                                                        SettlementWork.ENTITY,
                                                        "FNF-1",
                                                        ApprovalStatus.APPROVED,
                                                        submitted)));
        assertThat(late.getErrorCode()).isEqualTo(SettlementErrorCode.CHANGED);
        settlementIs("COMPUTED", 4, null);
        assertThat(refusal(() -> service.on(deciding)))
                .as("no net computed")
                .isEqualTo(SettlementErrorCode.CHANGED);
        verify(jdbc, never())
                .update(contains("SET settlement_status = 'APPROVED'"), any(Object[].class));

        // Sent back to Initiated by the later dues: refused on the status, before the OTP.
        settlementIs("INITIATED", 2, new BigDecimal("1000"));
        assertThat(refusal(() -> service.on(deciding))).isEqualTo(SettlementErrorCode.STAGE);

        settlementIs("COMPUTED", 4, new BigDecimal("1000.00"));
        service.on(deciding);
        service.on(
                new in.gov.fci.payroll.platform.approval.ApprovalDeciding(
                        4L, "O-05", SettlementWork.ENTITY, "FNF-1", null, "ddo@x"));
        service.on(
                new in.gov.fci.payroll.platform.approval.ApprovalDeciding(
                        4L, "O-05", "other", "FNF-1", submitted, "ddo@x"));
        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.APPROVED, submitted));
        verify(jdbc).update(contains("SET settlement_status = 'APPROVED'"), eq(4L), eq(8L), eq(5L));
    }

    /**
     * SRS-10-14 (l.2871, Wave A review): the Checker's exclusion accompanies the approval it was
     * recorded against; a return or a rejection of that approval puts each excluded clearance back
     * to outstanding - Raised once raised, else Pending - audited with the decision.
     */
    @Test
    void SRS_10_14_aReturnOrRejectionUndoesTheExclusionsRecordedAgainstIt() {
        when(jdbc.queryForMap(contains("FROM settlement_case WHERE case_reference"), eq("FNF-1")))
                .thenReturn(Map.of("SETTLEMENT_CASE_ID", 5L));
        Map<String, Object> raised = new java.util.HashMap<>();
        raised.put("CLEARANCE_TYPE", "ESTABLISHMENT_NO_DUES");
        raised.put("RAISED_AT", java.sql.Timestamp.from(Instant.parse("2026-10-01T05:00:00Z")));
        Map<String, Object> unraised = new java.util.HashMap<>();
        unraised.put("CLEARANCE_TYPE", "LOANS");
        unraised.put("RAISED_AT", null);
        // Wave C review: answered while excluded - back Received with the answer's remark.
        Map<String, Object> answered = new java.util.HashMap<>();
        answered.put("CLEARANCE_TYPE", "ALLOWANCES");
        answered.put("RAISED_AT", java.sql.Timestamp.from(Instant.parse("2026-10-01T05:00:00Z")));
        answered.put("RECEIVED_AT", java.sql.Timestamp.from(Instant.parse("2026-10-02T05:00:00Z")));
        answered.put("ANSWER_REMARK", "Nil");
        when(jdbc.queryForList(contains("SELECT clearance_type, raised_at"), eq(5L), eq(4L)))
                .thenReturn(List.of(raised, unraised, answered));
        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.RETURNED, null));
        String restore = "SET clearance_status = ?, remark = ?, answer_remark = NULL";
        verify(jdbc)
                .update(
                        contains(restore),
                        eq("RECEIVED"),
                        eq("Nil"),
                        eq(5L),
                        eq("ALLOWANCES"),
                        eq(4L));
        verify(jdbc)
                .update(
                        contains(restore),
                        eq("RAISED"),
                        isNull(),
                        eq(5L),
                        eq("ESTABLISHMENT_NO_DUES"),
                        eq(4L));
        verify(jdbc)
                .update(contains(restore), eq("PENDING"), isNull(), eq(5L), eq("LOANS"), eq(4L));
        org.mockito.ArgumentCaptor<AuditService.Entry> entries =
                org.mockito.ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, org.mockito.Mockito.times(4)).record(eq("ddo@x"), entries.capture());
        assertThat(entries.getAllValues().get(2).newValue()).isEqualTo("RECEIVED");
        assertThat(entries.getAllValues().subList(0, 3))
                .allSatisfy(
                        e -> {
                            assertThat(e.oldValue()).isEqualTo("EXCLUDED");
                            assertThat(e.approvalId()).isEqualTo(4L);
                            assertThat(e.reason())
                                    .isEqualTo("Exclusion undone: the settlement was returned");
                        });
        assertThat(entries.getAllValues().get(0).entityKey())
                .isEqualTo("FNF-1|ESTABLISHMENT_NO_DUES");

        service.on(decided(SettlementWork.ENTITY, "FNF-1", ApprovalStatus.REJECTED, null));
        verify(audit, org.mockito.Mockito.atLeastOnce())
                .record(
                        eq("ddo@x"),
                        org.mockito.ArgumentMatchers.argThat(
                                e ->
                                        "Exclusion undone: the settlement was rejected"
                                                .equals(e.reason())));
    }
}
