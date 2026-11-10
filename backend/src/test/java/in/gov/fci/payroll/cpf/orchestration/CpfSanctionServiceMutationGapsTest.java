package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.SanctionRules.ChecklistItem;
import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.WithdrawalLimit;
import in.gov.fci.payroll.cpf.business.model.CpfClaim;
import in.gov.fci.payroll.cpf.business.model.CpfClaimHistory;
import in.gov.fci.payroll.cpf.business.model.CpfPurposeDocument;
import in.gov.fci.payroll.cpf.business.model.CpfSanctionTier;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Decision;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Document;
import in.gov.fci.payroll.cpf.orchestration.CpfSanctionService.Request;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kills the PIT survivors of {@link CpfSanctionService} (SRS-6-4, SRS-6-5, SRS-6-14, SRS-6-26,
 * SRS-6-55, SRS-12-9, BR-CPF-009 to 011, BR-CPF-039; screens E-01 and I-04): the permission every
 * entry point asks, the checks that precede a write, and what each tier action records (tier row,
 * claim status, audit, eSeva log, CPF SSP queue).
 */
class CpfSanctionServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final EmployeeDirectory.Member MEMBER =
            new EmployeeDirectory.Member(9L, "104882", "Rakesh Sharma", 300L, "IN_SERVICE");
    private static final BigDecimal FIFTY_THOUSAND = new BigDecimal("50000");

    private final Authorization policy = mock(Authorization.class);
    private final CpfRepositories.Sanctions sanctions = mock(CpfRepositories.Sanctions.class);
    private final CpfRepositories.SanctionTiers tiers = mock(CpfRepositories.SanctionTiers.class);
    private final CpfRepositories.Claims claims = mock(CpfRepositories.Claims.class);
    private final CpfRepositories.ClaimHistory claimHistory =
            mock(CpfRepositories.ClaimHistory.class);
    private final CpfRepositories.Purposes purposes = mock(CpfRepositories.Purposes.class);
    private final CpfRepositories.PurposeDocuments checklists =
            mock(CpfRepositories.PurposeDocuments.class);
    private final CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final CpfZones zones = mock(CpfZones.class);
    private final RuleEngine engine = mock(RuleEngine.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CpfSspSync ssp = mock(CpfSspSync.class);
    private final ApprovalWithdrawals withdrawals = mock(ApprovalWithdrawals.class);

    private final CpfSanctionService service =
            new CpfSanctionService(
                    policy,
                    sanctions,
                    tiers,
                    claims,
                    claimHistory,
                    purposes,
                    checklists,
                    headers,
                    directory,
                    mock(CpfMembers.class),
                    zones,
                    engine,
                    references,
                    approvals,
                    documents,
                    audit,
                    JsonMapper.builder().build(),
                    events,
                    ssp,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    withdrawals);

    private final AccessContext unit =
            new AccessContext(
                    3L,
                    "cpf.unit.delhi",
                    "Unit",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    private final AccessContext member = memberContext(9L);

    CpfSanctionServiceMutationGapsTest() {
        when(directory.member(9L)).thenReturn(Optional.of(MEMBER));
        when(tiers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sanctions.save(any()))
                .thenAnswer(
                        invocation -> {
                            PfSanction saved = invocation.getArgument(0);
                            if (saved.getSanctionId() == null) {
                                ReflectionTestUtils.setField(saved, "sanctionId", 55L);
                            }
                            return saved;
                        });
        when(claims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(zones.of(300L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(directory.cpfEligibility(9L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.CpfEligibility(
                                        9L,
                                        LocalDate.of(2015, 1, 1),
                                        LocalDate.of(2040, 1, 1),
                                        null,
                                        true,
                                        true)));
    }

    private static AccessContext memberContext(Long employeeId) {
        return new AccessContext(
                2L,
                "member@x",
                "Member",
                "s",
                "E",
                300L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private CpfWithdrawalPurpose purpose(
            long id, String code, String category, boolean property, boolean noCaste) {
        CpfWithdrawalPurpose p = mock(CpfWithdrawalPurpose.class);
        when(p.getPurposeId()).thenReturn(id);
        when(p.getPurposeCode()).thenReturn(code);
        when(p.getPurposeName()).thenReturn(code + " purpose");
        when(p.getWithdrawalCategory()).thenReturn(category);
        when(p.isRequiresPropertyDetails()).thenReturn(property);
        when(p.isRequiresNoCasteDispute()).thenReturn(noCaste);
        when(p.getMaxOccurrences()).thenReturn((Integer) null);
        when(p.inForceOn(TODAY)).thenReturn(true);
        when(purposes.findById(id)).thenReturn(Optional.of(p));
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc(code)).thenReturn(List.of(p));
        return p;
    }

    private PfSanction sanction(long id, Stage stage) {
        PfSanction s = PfSanction.requested(9L, "FINAL", 7L, FIFTY_THOUSAND, 1L);
        ReflectionTestUtils.setField(s, "sanctionId", id);
        if (stage == Stage.ZONAL_HQ_VALIDATION) {
            s.forwarded();
        } else if (stage == Stage.COMPETENT_AUTHORITY) {
            s.forwarded();
            s.validated(new BigDecimal("100000"), FIFTY_THOUSAND, false, null, 900L);
        } else if (stage == Stage.RETURNED) {
            s.returnedToMember();
        }
        when(sanctions.lockById(id)).thenReturn(Optional.of(s));
        when(sanctions.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private CpfClaim claim(long sanctionId) {
        CpfClaim c = mock(CpfClaim.class);
        when(c.getClaimId()).thenReturn(sanctionId * 10);
        when(c.status()).thenReturn(CpfClaim.Status.SUBMITTED);
        when(c.getSubmittedAt()).thenReturn(NOW);
        when(claims.findBySanctionId(sanctionId)).thenReturn(Optional.of(c));
        return c;
    }

    private void rule(String key, String value) {
        RuleEngine.Resolved resolved = mock(RuleEngine.Resolved.class);
        when(resolved.scalar()).thenReturn(new BigDecimal(value));
        when(engine.resolve(eq(key), eq(TODAY), any())).thenReturn(Optional.of(resolved));
    }

    private static Document file(String type) {
        return new Document(type, "REF-1", TODAY, "Tehsildar", "doc.pdf", new byte[] {1});
    }

    private AuditService.Entry stageAudit() {
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(any(), entry.capture());
        return entry.getValue();
    }

    // ---------------------------------------------------------------- the member

    @Test
    void SRS_6_4_aRequestIsSavedFlushedBeforeItsClaimDocumentsAuditAndLog() {
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);

        CpfSanctionService.SanctionView view =
                service.request(
                        member,
                        new Request("FINAL_SETTLEMENT", FIFTY_THOUSAND, null, null),
                        List.of(file("RETIREMENT_ORDER")));

        verify(policy).requireEmployeeRecord(member, "E-01", "CREATE", 9L, 300L);
        verify(directory).lockMember(9L);
        var order = inOrder(sanctions, claims);
        order.verify(sanctions).save(any());
        order.verify(sanctions).flush();
        order.verify(claims).save(any());
        ArgumentCaptor<DocumentStore.NewAttachment> attached =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(eq("member@x"), attached.capture());
        assertThat(attached.getValue().entityName()).isEqualTo("pf_sanction_document");
        assertThat(attached.getValue().entityKey()).isEqualTo("55");
        assertThat(attached.getValue().documentTypeCode()).isEqualTo("RETIREMENT_ORDER");
        AuditService.Entry entry = stageAudit();
        assertThat(entry.entityName()).isEqualTo(CpfSanctionService.ENTITY);
        assertThat(entry.entityKey()).isEqualTo("55");
        assertThat(entry.fieldName()).isEqualTo("workflow_stage");
        assertThat(entry.oldValue()).isNull();
        assertThat(entry.newValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.reason()).isEqualTo("CPF FINAL request raised in eSeva");
        assertThat(entry.moduleCode()).isEqualTo("E-01");
        ArgumentCaptor<SelfServiceEvents.Submitted> event =
                ArgumentCaptor.forClass(SelfServiceEvents.Submitted.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().type())
                .isEqualTo(SelfServiceEvents.Type.CPF_FINAL_PAYMENT_REQUEST);
        assertThat(event.getValue().entityId()).isEqualTo(55L);
        assertThat(event.getValue().value()).isEqualTo("CPF FINAL FINAL_SETTLEMENT of 50000");
        assertThat(view.id()).isEqualTo(55L);
        assertThat(view.stage()).isEqualTo("UNIT_VERIFICATION");
        verify(claimHistory).save(any());
    }

    @Test
    void SRS_6_4_theAmountIsCheckedBeforeAnythingIsSaved() {
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);
        for (BigDecimal bad :
                new BigDecimal[] {
                    null, BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("10.001")
                }) {
            refused(
                    () ->
                            service.request(
                                    member,
                                    new Request("FINAL_SETTLEMENT", bad, null, null),
                                    List.of()),
                    CpfErrorCode.REQUEST_AMOUNT);
        }
        verify(sanctions, never()).save(any());
        // two decimals are fine
        service.request(
                member,
                new Request("FINAL_SETTLEMENT", new BigDecimal("10.10"), null, null),
                List.of());
        verify(sanctions, times(1)).save(any());
    }

    @Test
    void BR_CPF_039_aPurposeThatNeedsThePropertyIsRefusedWithoutItsAddressAndDescription() {
        purpose(8L, "HOUSE", "FINAL", true, false);
        refused(
                () ->
                        service.request(
                                member,
                                new Request("HOUSE", FIFTY_THOUSAND, null, "Flat"),
                                List.of()),
                CpfErrorCode.PROPERTY_DETAILS);
        refused(
                () ->
                        service.request(
                                member,
                                new Request("HOUSE", FIFTY_THOUSAND, "Plot 4", " "),
                                List.of()),
                CpfErrorCode.PROPERTY_DETAILS);
        verify(sanctions, never()).save(any());
    }

    @Test
    void SRS_6_14_moreThanFiveDocumentsAreRefused() {
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);
        List<Document> six =
                List.of(file("A"), file("B"), file("C"), file("D"), file("E"), file("F"));
        refused(
                () ->
                        service.request(
                                member,
                                new Request("FINAL_SETTLEMENT", FIFTY_THOUSAND, null, null),
                                six),
                CpfErrorCode.DOCUMENT_COUNT);
        verify(sanctions, never()).save(any());
        service.request(
                member,
                new Request("FINAL_SETTLEMENT", FIFTY_THOUSAND, null, null),
                six.subList(0, 5));
        verify(documents, times(5)).attach(any(), any());
    }

    @Test
    void BR_CPF_030_anAdvanceIsRefusedWhileAnotherAdvanceIsActive() {
        purpose(9L, "ADVANCE_X", "ADVANCE", false, false);
        PfSanction active = PfSanction.requested(9L, "ADVANCE", 9L, FIFTY_THOUSAND, 1L);
        ReflectionTestUtils.setField(active, "sanctionId", 40L);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(active));
        refused(
                () ->
                        service.request(
                                member,
                                new Request("ADVANCE_X", FIFTY_THOUSAND, null, null),
                                List.of()),
                CpfErrorCode.ACTIVE_ADVANCE);
        // a returned advance is no longer active
        active.returnedToMember();
        service.request(member, new Request("ADVANCE_X", FIFTY_THOUSAND, null, null), List.of());
        verify(sanctions, times(1)).save(any());
    }

    @Test
    void BR_CPF_011_aPartFinalNeedsItsYearsOfMembershipAndIsLimitedInCount() {
        purpose(10L, "HOUSE_PF", "PART_FINAL", false, false);
        rule("CPF.CONDITION.PARTFINAL.MIN_YEARS", "20");
        rule("CPF.CONDITION.PARTFINAL.MAX_COUNT", "1");
        refused(
                () ->
                        service.request(
                                member,
                                new Request("HOUSE_PF", FIFTY_THOUSAND, null, null),
                                List.of()),
                CpfErrorCode.MEMBERSHIP_YEARS);

        rule("CPF.CONDITION.PARTFINAL.MIN_YEARS", "5");
        PfSanction earlier = PfSanction.requested(9L, "PART_FINAL", 10L, FIFTY_THOUSAND, 1L);
        ReflectionTestUtils.setField(earlier, "sanctionId", 40L);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(earlier));
        refused(
                () ->
                        service.request(
                                member,
                                new Request("HOUSE_PF", FIFTY_THOUSAND, null, null),
                                List.of()),
                CpfErrorCode.WITHDRAWAL_COUNT);

        rule("CPF.CONDITION.PARTFINAL.MAX_COUNT", "2");
        CpfSanctionService.SanctionView ok =
                service.request(
                        member, new Request("HOUSE_PF", FIFTY_THOUSAND, null, null), List.of());
        assertThat(ok.type()).isEqualTo("PART_FINAL");
        ArgumentCaptor<SelfServiceEvents.Submitted> event =
                ArgumentCaptor.forClass(SelfServiceEvents.Submitted.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().type())
                .isEqualTo(SelfServiceEvents.Type.CPF_WITHDRAWAL_REQUEST);
    }

    @Test
    void SRS_6_4_theMembersListAsksE01ViewForTheOwnRecord() {
        PfSanction mine = sanction(61L, Stage.UNIT_VERIFICATION);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(mine));
        List<CpfSanctionService.SanctionView> rows = service.mine(member);
        verify(policy).requireEmployeeRecord(member, "E-01", "SELF", 9L, 300L);
        assertThat(rows).extracting(CpfSanctionService.SanctionView::id).containsExactly(61L);
    }

    @Test
    void UC_05_aWithdrawalIsAuditedLoggedAndLeavesTheCompetentAuthoritysInbox() {
        sanction(61L, Stage.UNIT_VERIFICATION);
        claim(61L);

        CpfSanctionService.SanctionView view = service.withdraw(member, 61L, " No longer needed ");

        verify(policy).requireEmployeeRecord(member, "E-01", "CREATE", 9L, 300L);
        assertThat(view.stage()).isEqualTo("CANCELLED");
        assertThat(view.cancelReason()).isEqualTo("No longer needed");
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.newValue()).isEqualTo("CANCELLED");
        assertThat(entry.reason()).isEqualTo("Withdrawn by the member: No longer needed");
        assertThat(entry.moduleCode()).isEqualTo("E-01");
        verify(withdrawals)
                .withdraw(
                        CpfSanctionService.ENTITY, "61", "member@x", "Withdrawn: No longer needed");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                61L,
                                SelfServiceEvents.Status.REJECTED,
                                "member@x"));
        verify(audit).recordAll(eq("member@x"), any());
    }

    @Test
    void SRS_12_9_aResubmissionIsCheckedForItsAmountAndAuditedFromTheReturnedStage() {
        PfSanction returned = sanction(62L, Stage.RETURNED);
        CpfClaim claim = claim(62L);
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);

        refused(
                () ->
                        service.resubmit(
                                member,
                                62L,
                                new Request("FINAL_SETTLEMENT", BigDecimal.ZERO, null, null)),
                CpfErrorCode.REQUEST_AMOUNT);
        assertThat(returned.stage()).isEqualTo(Stage.RETURNED);

        CpfSanctionService.SanctionView view =
                service.resubmit(
                        member,
                        62L,
                        new Request("FINAL_SETTLEMENT", new BigDecimal("45000"), " Plot 4 ", "  "));

        assertThat(view.stage()).isEqualTo("UNIT_VERIFICATION");
        assertThat(view.requested()).isEqualByComparingTo("45000");
        verify(claim).resubmitted(new BigDecimal("45000"), "Plot 4", null);
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("RETURNED");
        assertThat(entry.newValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.reason()).isEqualTo("Resubmitted by the member");
    }

    @Test
    void BR_CPF_039_aResubmissionForAPurposeThatNeedsThePropertyNeedsItToo() {
        sanction(62L, Stage.RETURNED);
        claim(62L);
        purpose(7L, "HOUSE", "FINAL", true, false);
        refused(
                () ->
                        service.resubmit(
                                member, 62L, new Request("HOUSE", FIFTY_THOUSAND, "Plot 4", null)),
                CpfErrorCode.PROPERTY_DETAILS);
    }

    // ------------------------------------------------------ the register

    @Test
    void SRS_6_55_theRegisterAndOneRequestAskI04ViewAndAnOutOfScopeRequestIsNotFound() {
        PfSanction s = sanction(63L, Stage.UNIT_VERIFICATION);
        when(sanctions.inScope(any(), any(), any())).thenReturn(new PageImpl<>(List.of(s)));
        when(directory.members(any())).thenReturn(Map.of(9L, MEMBER));
        assertThat(service.register(unit, "UNIT_VERIFICATION", new PageSpec(0, 20)).items())
                .hasSize(1);
        verify(policy).require(unit, "I-04", "VIEW");

        assertThat(service.get(unit, 63L).id()).isEqualTo(63L);
        verify(policy, times(2)).require(unit, "I-04", "VIEW");

        AccessContext elsewhere =
                new AccessContext(
                        4L,
                        "other",
                        "O",
                        "s",
                        "P",
                        999L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(999L));
        refused(() -> service.get(elsewhere, 63L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_6_55_aStaffReaderOutsideTheScopeFindsNoDocumentOfAnotherMembersRequest() {
        sanction(64L, Stage.UNIT_VERIFICATION);
        AccessContext elsewhere =
                new AccessContext(
                        4L,
                        "other",
                        "O",
                        "s",
                        "P",
                        999L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(999L));
        when(policy.can(elsewhere, "I-04", "VIEW")).thenReturn(true);
        refused(() -> service.document(elsewhere, "10.0.0.1", 64L, 5L), CommonErrorCode.NOT_FOUND);
        verify(documents, never()).read(any(), any(), any(), any(), any());
        // in scope: read from the request's own folder
        when(policy.can(unit, "I-04", "VIEW")).thenReturn(true);
        service.document(unit, "10.0.0.1", 64L, 5L);
        verify(documents).read("cpf.unit.delhi", "10.0.0.1", "pf_sanction_document", "64", 5L);
    }

    // ------------------------------------------------------------ tier 1

    @Test
    void SRS_6_14_theCertificateIsAttachedAtTheUnitWithItsAuditAndTheUnitsPermissions() {
        sanction(65L, Stage.UNIT_VERIFICATION);
        purpose(7L, "WIDOW", "FINAL", false, true);

        service.attachAtUnit(unit, 65L, file(CpfSanctionService.NO_CASTE_DISPUTE));

        verify(policy).require(unit, "I-04", "EDIT");
        verify(policy).requireRecordAt(unit, "I-04", "EDIT", 300L);
        verify(documents).attach(eq("cpf.unit.delhi"), any());
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.reason()).isEqualTo("No Caste Dispute Certificate attached at the unit");
        assertThat(entry.moduleCode()).isEqualTo("I-04");
    }

    @Test
    void SRS_6_4_theUnitForwardsAndTheForwardIsRecordedOnTheTierTheClaimAndTheAudit() {
        PfSanction s = sanction(66L, Stage.UNIT_VERIFICATION);
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);
        claim(66L);

        service.verify(unit, 66L, Decision.FORWARD, " all documents seen ");

        verify(policy).require(unit, "I-04", "EDIT");
        verify(policy).requireRecordAt(unit, "I-04", "EDIT", 300L);
        assertThat(s.stage()).isEqualTo(Stage.ZONAL_HQ_VALIDATION);
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("FORWARDED");
        assertThat(tier.getValue().getTierNo()).isEqualTo(1);
        assertThat(tier.getValue().getPassNo()).isEqualTo(1);
        assertThat(tier.getValue().getActedBy()).isEqualTo("cpf.unit.delhi");
        assertThat(tier.getValue().getRemark()).isEqualTo("all documents seen");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                66L,
                                SelfServiceEvents.Status.ROUTED,
                                "cpf.unit.delhi"));
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.newValue()).isEqualTo("ZONAL_HQ_VALIDATION");
        assertThat(entry.reason()).isEqualTo("Forwarded to the CPF Division");
        verify(audit).recordAll(eq("cpf.unit.delhi"), any());
    }

    @Test
    void SRS_6_4_aReturnToTheMemberIsRecordedQueuedForTheSspAndNeedsAReason() {
        PfSanction s = sanction(67L, Stage.UNIT_VERIFICATION);
        claim(67L);
        refused(
                () -> service.verify(unit, 67L, Decision.RETURN, " "),
                ApiErrorCode.REASON_REQUIRED);
        verify(tiers, never()).save(any());

        service.verify(unit, 67L, Decision.RETURN, "UAN missing");

        assertThat(s.stage()).isEqualTo(Stage.RETURNED);
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.newValue()).isEqualTo("RETURNED");
        assertThat(entry.reason()).isEqualTo("Returned to the member: UAN missing");
        ArgumentCaptor<SspGateway.Change> change = ArgumentCaptor.forClass(SspGateway.Change.class);
        verify(ssp).queue(change.capture());
        assertThat(change.getValue().employeeId()).isEqualTo(9L);
        assertThat(change.getValue().fields()).containsExactly("workflow_stage", "reason");
    }

    @Test
    void SRS_G_1_theSameUserNeverActsTwiceInOnePassAtTheUnit() {
        sanction(68L, Stage.UNIT_VERIFICATION);
        CpfSanctionTier done = mock(CpfSanctionTier.class);
        when(done.getPassNo()).thenReturn(1);
        when(done.getActedBy()).thenReturn("cpf.unit.delhi");
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(68L)).thenReturn(List.of(done));
        refused(
                () -> service.verify(unit, 68L, Decision.RETURN, "again"),
                CpfErrorCode.TIER_SAME_USER);
        refused(
                () -> service.verify(unit, 68L, Decision.FORWARD, "again"),
                CpfErrorCode.TIER_SAME_USER);
        verify(tiers, never()).save(any());
    }

    // ------------------------------------------------------------ tier 2

    @Test
    void SRS_6_4_theDivisionReturnsToTheUnitForANewPassWithItsAuditAndClaimStatus() {
        PfSanction s = sanction(70L, Stage.ZONAL_HQ_VALIDATION);
        claim(70L);

        service.recommend(unit, 70L, Decision.RETURN, "Amount unclear", null);

        verify(policy).require(unit, "I-04", "SUBMIT");
        verify(policy).requireRecordAt(unit, "I-04", "SUBMIT", 300L);
        assertThat(s.stage()).isEqualTo(Stage.UNIT_VERIFICATION);
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("RETURNED");
        assertThat(tier.getValue().getTierNo()).isEqualTo(2);
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                70L,
                                SelfServiceEvents.Status.ROUTED,
                                "cpf.unit.delhi"));
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("ZONAL_HQ_VALIDATION");
        assertThat(entry.newValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.reason()).isEqualTo("Returned to the unit: Amount unclear");
        verify(ssp, never()).queue(any());
    }

    @Test
    void SRS_6_4_theDivisionRejectsWithTheReasonAndQueuesItForTheSsp() {
        PfSanction s = sanction(71L, Stage.ZONAL_HQ_VALIDATION);
        claim(71L);
        service.recommend(unit, 71L, Decision.REJECT, "Purpose not eligible", null);
        assertThat(s.stage()).isEqualTo(Stage.REJECTED);
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                71L,
                                SelfServiceEvents.Status.REJECTED,
                                "cpf.unit.delhi"));
        AuditService.Entry entry = stageAudit();
        assertThat(entry.newValue()).isEqualTo("REJECTED");
        assertThat(entry.reason()).isEqualTo("Rejected by the CPF Division: Purpose not eligible");
        verify(ssp).queue(any());
    }

    @Test
    void SRS_G_1_theSameUserNeverActsTwiceInOnePassAtTheDivision() {
        sanction(72L, Stage.ZONAL_HQ_VALIDATION);
        CpfSanctionTier done = mock(CpfSanctionTier.class);
        when(done.getPassNo()).thenReturn(1);
        when(done.getActedBy()).thenReturn("cpf.unit.delhi");
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(72L)).thenReturn(List.of(done));
        refused(
                () -> service.recommend(unit, 72L, Decision.RETURN, "again", null),
                CpfErrorCode.TIER_SAME_USER);
        // a RETURNED row of an earlier pass opens pass 2: the same user may act again
        when(done.getTierAction()).thenReturn("RETURNED");
        CpfSanctionTier second = mock(CpfSanctionTier.class);
        when(second.getPassNo()).thenReturn(2);
        when(second.getActedBy()).thenReturn("someone.else");
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(72L)).thenReturn(List.of(done, second));
        claim(72L);
        service.recommend(unit, 72L, Decision.RETURN, "again", null);
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getPassNo()).isEqualTo(2);
    }

    // ------------------------------------------------------------ tier 3

    private ApprovalDecided decided(ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                900L,
                "I-04",
                CpfSanctionService.ENTITY,
                "73",
                outcome,
                reason,
                null,
                "cpf.authority",
                NOW,
                77L);
    }

    @Test
    void SRS_6_26_theAuthoritysRejectionEndsTheRequestAndIsRecordedOnTierThree() {
        PfSanction s = sanction(73L, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        claim(73L);

        service.on(decided(ApprovalStatus.REJECTED, "Not within limits"));

        assertThat(s.stage()).isEqualTo(Stage.REJECTED);
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("REJECTED");
        assertThat(tier.getValue().getTierNo()).isEqualTo(3);
        assertThat(tier.getValue().getActedBy()).isEqualTo("cpf.authority");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                73L,
                                SelfServiceEvents.Status.REJECTED,
                                "cpf.authority"));
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("COMPETENT_AUTHORITY");
        assertThat(entry.newValue()).isEqualTo("REJECTED");
        assertThat(entry.reason()).isEqualTo("Not within limits");
        assertThat(entry.approvalId()).isEqualTo(900L);
        assertThat(entry.moduleCode()).isEqualTo("I-04");
        verify(audit).recordAll(eq("cpf.authority"), any());
    }

    @Test
    void SRS_12_9_theAuthoritysReturnSendsTheRequestBackToTheUnitAndAnEmptyReasonReadsDecided() {
        PfSanction s = sanction(73L, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        claim(73L);

        service.on(decided(ApprovalStatus.RETURNED, null));

        assertThat(s.stage()).isEqualTo(Stage.UNIT_VERIFICATION);
        AuditService.Entry entry = stageAudit();
        assertThat(entry.newValue()).isEqualTo("UNIT_VERIFICATION");
        assertThat(entry.reason()).isEqualTo("Decided");
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("RETURNED");
    }

    @Test
    void SRS_G_1_theAuthorityWhoActedEarlierInThePassMayNotDecideIt() {
        PfSanction s = sanction(73L, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        CpfSanctionTier done = mock(CpfSanctionTier.class);
        when(done.getPassNo()).thenReturn(1);
        when(done.getActedBy()).thenReturn("cpf.authority");
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(73L)).thenReturn(List.of(done));
        refused(
                () -> service.on(decided(ApprovalStatus.REJECTED, "x")),
                CpfErrorCode.TIER_SAME_USER);
        assertThat(s.stage()).isEqualTo(Stage.COMPETENT_AUTHORITY);
    }

    @Test
    void SRS_6_26_aDecisionOfAnotherScreenOrOfAnUnknownOrSettledRequestChangesNothing() {
        PfSanction settled = sanction(74L, Stage.UNIT_VERIFICATION);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(settled));
        service.on(decided(ApprovalStatus.REJECTED, "x"));
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.empty());
        service.on(decided(ApprovalStatus.REJECTED, "x"));
        service.on(
                new ApprovalDecided(
                        900L,
                        "I-05",
                        CpfSanctionService.ENTITY,
                        "74",
                        ApprovalStatus.REJECTED,
                        "x",
                        null,
                        "c",
                        NOW,
                        null));
        service.on(
                new ApprovalDecided(
                        900L,
                        "I-04",
                        "other_entity",
                        "74",
                        ApprovalStatus.REJECTED,
                        "x",
                        null,
                        "c",
                        NOW,
                        null));
        verify(tiers, never()).save(any());
        verify(audit, never()).record(any(), any());
    }

    // ================================================== pass 2: tier 2 and tier 3 in full

    /** The day the member's claim was submitted, four days before "today" (the request date). */
    private static final Instant REQUEST_INSTANT = NOW.minus(4, ChronoUnit.DAYS);

    private static final LocalDate REQUEST_DAY = TODAY.minusDays(4);

    private PfSanction sanctionOf(long id, String type, long purposeId, Stage stage) {
        PfSanction s = PfSanction.requested(9L, type, purposeId, new BigDecimal("50000"), 1L);
        ReflectionTestUtils.setField(s, "sanctionId", id);
        if (stage == Stage.ZONAL_HQ_VALIDATION) {
            s.forwarded();
        } else if (stage == Stage.SANCTIONED) {
            s.sanctioned("01/2026/NA11/0001", TODAY, 300L, false);
        } else if (stage == Stage.REJECTED) {
            s.rejected();
        } else if (stage == Stage.CANCELLED) {
            s.cancelled("not needed", NOW);
        }
        return s;
    }

    /** A request at the CPF Division (tier 2), locked and readable by id. */
    private PfSanction atDivision(long id, String type, long purposeId) {
        PfSanction s = sanctionOf(id, type, purposeId, Stage.ZONAL_HQ_VALIDATION);
        when(sanctions.lockById(id)).thenReturn(Optional.of(s));
        when(sanctions.findById(id)).thenReturn(Optional.of(s));
        CpfClaim c = claim(id);
        when(c.getSubmittedAt()).thenReturn(REQUEST_INSTANT);
        return s;
    }

    private void ruleOn(String key, LocalDate day, String value) {
        RuleEngine.Resolved resolved = mock(RuleEngine.Resolved.class);
        when(resolved.scalar()).thenReturn(new BigDecimal(value));
        when(engine.resolve(eq(key), eq(day), any())).thenReturn(Optional.of(resolved));
    }

    private void ledger(String employee, String employer, String vpf) {
        EmployeePfHeader header = mock(EmployeePfHeader.class);
        when(header.closing())
                .thenReturn(
                        new LedgerBalances(
                                new BigDecimal(employee),
                                new BigDecimal(employer),
                                new BigDecimal(vpf)));
        when(headers.findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(eq(9L), any()))
                .thenReturn(List.of(header));
    }

    private void numberFormat(String separator, String width) {
        when(references.list(CpfSanctionService.NUMBER_FORMAT))
                .thenReturn(
                        Optional.of(
                                new ReferenceData.ListView(
                                        CpfSanctionService.NUMBER_FORMAT,
                                        "Sanction number",
                                        "M-16",
                                        true,
                                        List.of(
                                                new ReferenceData.Value(
                                                        "SEPARATOR",
                                                        separator,
                                                        null,
                                                        1,
                                                        null,
                                                        null,
                                                        true),
                                                new ReferenceData.Value(
                                                        "SEQUENCE_WIDTH",
                                                        "width",
                                                        null,
                                                        2,
                                                        new BigDecimal(width),
                                                        null,
                                                        true)))));
    }

    private void approvalIssued() {
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(900L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static CpfSanctionTier tierRow(long sanctionId, int pass, int tier, String action) {
        CpfSanctionTier t = mock(CpfSanctionTier.class);
        when(t.getSanctionId()).thenReturn(sanctionId);
        when(t.getPassNo()).thenReturn(pass);
        when(t.getTierNo()).thenReturn(tier);
        when(t.getTierCode()).thenReturn("T" + tier);
        when(t.getTierAction()).thenReturn(action);
        when(t.getActedBy()).thenReturn("someone.else");
        return t;
    }

    @Test
    void SRS_6_26_SRS_6_2_aCappedAdvanceIsRecommendedAtTheCeilingOfTheRequestDate() {
        PfSanction s = atDivision(80L, "ADVANCE", 5L);
        CpfWithdrawalPurpose p = purpose(5L, "MARRIAGE", "ADVANCE", false, false);
        when(p.getSanctionLimitRuleKey()).thenReturn(WithdrawalLimit.MARRIAGE.ruleKey());
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(s));
        ruleOn(WithdrawalLimit.MARRIAGE.ruleKey(), REQUEST_DAY, "6");
        ruleOn(CpfSanctionService.MGW_RULE, REQUEST_DAY, "15000");
        ruleOn(CpfSanctionService.ADVANCE_CAP_RULE, REQUEST_DAY, "0.5");
        // 6 x 15,000 = 90,000; the advance cap is half of (employee 60,000 + VPF 0) = 30,000
        ledger("60000", "40000", "0");
        numberFormat("/", "4");
        approvalIssued();

        CpfSanctionService.SanctionView view =
                service.recommend(unit, 80L, Decision.FORWARD, " within the cap ", null);

        assertThat(view).isNotNull();
        assertThat(view.stage()).isEqualTo("COMPETENT_AUTHORITY");
        assertThat(s.stage()).isEqualTo(Stage.COMPETENT_AUTHORITY);
        assertThat(s.getComputedCeilingAmount()).isEqualByComparingTo("30000");
        assertThat(s.getSanctionedAmount()).isEqualByComparingTo("30000");
        assertThat(s.isWasCapped()).isTrue();
        assertThat(s.getApprovalId()).isEqualTo(900L);
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(unit), submission.capture(), any());
        assertThat(submission.getValue().entityName()).isEqualTo(CpfSanctionService.ENTITY);
        assertThat(submission.getValue().entityKey()).isEqualTo("80");
        assertThat(submission.getValue().actionType()).isEqualTo(ActionType.CREATE);
        assertThat(submission.getValue().locationId()).isEqualTo(300L);
        assertThat(submission.getValue().effectiveDate()).isEqualTo(REQUEST_DAY);
        assertThat(submission.getValue().reason()).isEqualTo("within the cap");
        assertThat(submission.getValue().impactValue()).isEqualByComparingTo("30000");
        assertThat(submission.getValue().payload())
                .contains("\"eis\":\"104882\"", "\"capped\":true");
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("RECOMMENDED");
        assertThat(tier.getValue().getTierNo()).isEqualTo(2);
        assertThat(tier.getValue().getCeilingAmount()).isEqualByComparingTo("30000");
        assertThat(tier.getValue().getCappedAmount()).isEqualByComparingTo("30000");
        AuditService.Entry entry = stageAudit();
        assertThat(entry.oldValue()).isEqualTo("ZONAL_HQ_VALIDATION");
        assertThat(entry.newValue()).isEqualTo("COMPETENT_AUTHORITY");
        assertThat(entry.reason())
                .isEqualTo("Recommended at " + s.getSanctionedAmount() + " (capped)");
        assertThat(entry.moduleCode()).isEqualTo("I-04");
        verify(audit).recordAll(eq("cpf.unit.delhi"), any());
    }

    @Test
    void SRS_6_26_aPartFinalWithinItsCeilingIsRecommendedAtTheRequestedAmountNotCapped() {
        PfSanction s = atDivision(81L, "PART_FINAL", 10L);
        CpfWithdrawalPurpose p = purpose(10L, "HOUSE_PF", "PART_FINAL", false, false);
        when(p.getSanctionLimitRuleKey()).thenReturn(WithdrawalLimit.HOUSE.ruleKey());
        when(p.getMaxOccurrences()).thenReturn(1);
        // earlier requests of the member: this one, a rejected part-final of the same purpose, a
        // live advance of another purpose and a cancelled part-final - none counts against the
        // limits of this one
        PfSanction rejected = sanctionOf(71L, "PART_FINAL", 10L, Stage.REJECTED);
        PfSanction advance = sanctionOf(72L, "ADVANCE", 99L, Stage.SANCTIONED);
        PfSanction cancelled = sanctionOf(73L, "PART_FINAL", 10L, Stage.CANCELLED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L))
                .thenReturn(List.of(s, advance, rejected, cancelled));
        ruleOn(WithdrawalLimit.HOUSE.ruleKey(), REQUEST_DAY, "10");
        ruleOn(CpfSanctionService.MGW_RULE, REQUEST_DAY, "15000");
        ruleOn(CpfSanctionService.MIN_YEARS_RULE, REQUEST_DAY, "5");
        ruleOn(CpfSanctionService.MAX_COUNT_RULE, REQUEST_DAY, "1");
        numberFormat("/", "4");
        approvalIssued();

        service.recommend(unit, 81L, Decision.FORWARD, null, null);

        assertThat(s.stage()).isEqualTo(Stage.COMPETENT_AUTHORITY);
        assertThat(s.getComputedCeilingAmount()).isEqualByComparingTo("150000");
        assertThat(s.getSanctionedAmount()).isEqualByComparingTo("50000");
        assertThat(s.isWasCapped()).isFalse();
        AuditService.Entry entry = stageAudit();
        assertThat(entry.reason()).isEqualTo("Recommended at " + s.getSanctionedAmount());
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(unit), submission.capture(), any());
        assertThat(submission.getValue().reason()).isNull();
    }

    @Test
    void BR_CPF_010_anotherLivePartFinalCountsAgainstTheMaximumAndBlocksTheRecommendation() {
        PfSanction s = atDivision(83L, "PART_FINAL", 10L);
        CpfWithdrawalPurpose p = purpose(10L, "HOUSE_PF", "PART_FINAL", false, false);
        when(p.getSanctionLimitRuleKey()).thenReturn(WithdrawalLimit.HOUSE.ruleKey());
        PfSanction other = sanctionOf(74L, "PART_FINAL", 99L, Stage.SANCTIONED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(s, other));
        ruleOn(CpfSanctionService.MIN_YEARS_RULE, REQUEST_DAY, "5");
        ruleOn(CpfSanctionService.MAX_COUNT_RULE, REQUEST_DAY, "1");

        refused(
                () -> service.recommend(unit, 83L, Decision.FORWARD, "ok", null),
                CpfErrorCode.WITHDRAWAL_COUNT);

        // one more allowed: the same request now passes the count and reaches the ceiling
        ruleOn(CpfSanctionService.MAX_COUNT_RULE, REQUEST_DAY, "2");
        refused(
                () -> service.recommend(unit, 83L, Decision.FORWARD, "ok", null),
                CpfErrorCode.LIMIT_NOT_CONFIGURED);
        verify(approvals, never()).submit(any(), any(), any());
        verify(tiers, never()).save(any());
    }

    @Test
    void BR_CPF_030_theRecommendationOfAnAdvanceRechecksThatNoOtherAdvanceIsActive() {
        PfSanction s = atDivision(85L, "ADVANCE", 5L);
        CpfWithdrawalPurpose p = purpose(5L, "MARRIAGE", "ADVANCE", false, false);
        when(p.getSanctionLimitRuleKey()).thenReturn(WithdrawalLimit.MARRIAGE.ruleKey());
        PfSanction other = sanctionOf(86L, "ADVANCE", 5L, Stage.SANCTIONED);
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(s, other));

        refused(
                () -> service.recommend(unit, 85L, Decision.FORWARD, "ok", null),
                CpfErrorCode.ACTIVE_ADVANCE);

        verify(approvals, never()).submit(any(), any(), any());
        assertThat(s.stage()).isEqualTo(Stage.ZONAL_HQ_VALIDATION);
    }

    @Test
    void SRS_6_24_aPurposeWhoseLimitIsNotOneOfTheStatedOnesIsNotConfigured() {
        PfSanction s = atDivision(87L, "ADVANCE", 5L);
        CpfWithdrawalPurpose p = purpose(5L, "ILLNESS", "ADVANCE", false, false);
        when(p.getSanctionLimitRuleKey()).thenReturn("CPF.LIMIT.UNKNOWN");
        when(sanctions.findByEmployeeIdOrderBySanctionIdDesc(9L)).thenReturn(List.of(s));

        assertThatThrownBy(() -> service.recommend(unit, 87L, Decision.FORWARD, "ok", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(CpfErrorCode.LIMIT_NOT_CONFIGURED);
                            assertThat(e.getMessageArgs()).containsExactly("CPF.LIMIT.UNKNOWN");
                        });
    }

    @Test
    void SRS_12_9_aReturnOfPassOneOpensPassTwoAndEveryTierOneToTwoActionReturnsItsView() {
        PfSanction s = sanction(88L, Stage.ZONAL_HQ_VALIDATION);
        claim(88L);
        List<CpfSanctionTier> passOne = List.of(tierRow(88L, 1, 1, "RETURNED"));
        when(tiers.findBySanctionIdOrderByTierActionIdAsc(88L)).thenReturn(passOne);

        CpfSanctionService.SanctionView returned =
                service.recommend(unit, 88L, Decision.RETURN, "Amount unclear", null);

        assertThat(returned.stage()).isEqualTo("UNIT_VERIFICATION");
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getPassNo()).isEqualTo(2);
        assertThat(s.stage()).isEqualTo(Stage.UNIT_VERIFICATION);

        sanction(89L, Stage.ZONAL_HQ_VALIDATION);
        claim(89L);
        CpfSanctionService.SanctionView rejected =
                service.recommend(unit, 89L, Decision.REJECT, "Not eligible", null);
        assertThat(rejected.stage()).isEqualTo("REJECTED");

        sanction(90L, Stage.UNIT_VERIFICATION);
        claim(90L);
        CpfSanctionService.SanctionView unitReturn =
                service.verify(unit, 90L, Decision.RETURN, "UAN missing");
        assertThat(unitReturn.stage()).isEqualTo("RETURNED");
    }

    @Test
    void SRS_6_14_theUnitForwardsWithTheCertificateAttachedAndNamesItWhenItIsMissing() {
        PfSanction s = sanction(91L, Stage.UNIT_VERIFICATION);
        purpose(7L, "WIDOW", "FINAL", false, true);
        claim(91L);

        assertThatThrownBy(() -> service.verify(unit, 91L, Decision.FORWARD, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(CpfErrorCode.DOCUMENTS_MISSING);
                            assertThat(e.getMessageArgs())
                                    .containsExactly(CpfSanctionService.NO_CASTE_DISPUTE);
                        });
        assertThat(s.stage()).isEqualTo(Stage.UNIT_VERIFICATION);

        DocumentStore.AttachmentView certificate = mock(DocumentStore.AttachmentView.class);
        when(certificate.documentTypeCode()).thenReturn(CpfSanctionService.NO_CASTE_DISPUTE);
        when(documents.active("pf_sanction_document", "91")).thenReturn(List.of(certificate));

        CpfSanctionService.SanctionView view =
                service.verify(unit, 91L, Decision.FORWARD, " all seen ");

        assertThat(view.stage()).isEqualTo("ZONAL_HQ_VALIDATION");
        assertThat(s.stage()).isEqualTo(Stage.ZONAL_HQ_VALIDATION);
    }

    private void approve(long id, String balance, String reason) {
        PfSanction s = sanction(id, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        claim(id);
        ledger(balance, "0", "0");
        service.on(decided(ApprovalStatus.APPROVED, reason));
    }

    @Test
    void SRS_6_38_SRS_6_4_theApprovalIssuesTheNumberLocksTheZoneAndRecordsTheClaimAndTierThree() {
        PfSanction s = sanction(75L, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        CpfClaim claim = claim(75L);
        ledger("60000", "0", "0");
        numberFormat("-", "4");
        when(sanctions.numberedInZone(1L)).thenReturn(42L);

        service.on(decided(ApprovalStatus.APPROVED, "Within the limit"));

        assertThat(s.stage()).isEqualTo(Stage.SANCTIONED);
        assertThat(s.getSystemSanctionNumber()).isEqualTo("10-2026-NA11-0043");
        assertThat(s.getSanctionDate()).isEqualTo(TODAY);
        verify(zones).lock(1L);
        verify(claim).status(CpfClaim.Status.APPROVED, NOW);
        ArgumentCaptor<CpfClaimHistory> history = ArgumentCaptor.forClass(CpfClaimHistory.class);
        verify(claimHistory).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo("SUBMITTED");
        assertThat(history.getValue().getToStatus()).isEqualTo("APPROVED");
        assertThat(history.getValue().getRemark()).isEqualTo("10-2026-NA11-0043");
        assertThat(history.getValue().getChangedBy()).isEqualTo("cpf.authority");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                CpfSanctionService.ENTITY,
                                75L,
                                SelfServiceEvents.Status.ACTIONED,
                                "cpf.authority"));
        ArgumentCaptor<CpfSanctionTier> tier = ArgumentCaptor.forClass(CpfSanctionTier.class);
        verify(tiers).save(tier.capture());
        assertThat(tier.getValue().getTierAction()).isEqualTo("SANCTIONED");
        assertThat(tier.getValue().getTierNo()).isEqualTo(3);
        assertThat(tier.getValue().getOtpChallengeId()).isEqualTo(77L);
        assertThat(tier.getValue().getRemark()).isEqualTo("Within the limit");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditService.Entry>> facts = ArgumentCaptor.forClass(List.class);
        verify(audit).recordAll(eq("cpf.authority"), facts.capture());
        assertThat(facts.getValue())
                .extracting(AuditService.Entry::reason)
                .containsOnly("Within the limit");
    }

    @Test
    void SRS_6_4_aSanctionIsBlockedOnlyWhenTheBalanceIsBelowTheSanctionedAmount() {
        numberFormat("/", "4");
        approve(76L, "50000", "ok");
        assertThat(sanctions.lockById(76L).orElseThrow().stage()).isEqualTo(Stage.SANCTIONED);
        approve(77L, "49999.99", "ok");
        assertThat(sanctions.lockById(77L).orElseThrow().stage()).isEqualTo(Stage.PAYMENT_BLOCKED);
    }

    @Test
    void SRS_6_26_aDecisionWithoutAReasonAuditsThePurposeAndDocumentsFactsAsDecided() {
        PfSanction s = sanction(78L, Stage.COMPETENT_AUTHORITY);
        when(sanctions.findByApprovalId(900L)).thenReturn(Optional.of(s));
        claim(78L);

        service.on(decided(ApprovalStatus.REJECTED, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditService.Entry>> facts = ArgumentCaptor.forClass(List.class);
        verify(audit).recordAll(eq("cpf.authority"), facts.capture());
        assertThat(facts.getValue()).extracting(AuditService.Entry::reason).containsOnly("Decided");
    }

    @Test
    void SRS_6_14_theMemberSeesOnlyThePurposesInForceTodayWithTheirChecklists() {
        CpfWithdrawalPurpose house = purpose(8L, "HOUSE", "PART_FINAL", true, false);
        CpfWithdrawalPurpose old = mock(CpfWithdrawalPurpose.class);
        when(old.inForceOn(TODAY)).thenReturn(false);
        CpfWithdrawalPurpose marriage = purpose(5L, "MARRIAGE", "ADVANCE", false, false);
        when(purposes.findAllByOrderByPurposeCodeAscEffectiveFromDesc())
                .thenReturn(List.of(house, old, marriage));
        CpfPurposeDocument doc = mock(CpfPurposeDocument.class);
        when(doc.getDocumentTypeCode()).thenReturn("SALE_DEED");
        when(doc.isMandatory()).thenReturn(true);
        when(checklists.findByPurposeIdOrderByPurposeDocId(8L)).thenReturn(List.of(doc));

        List<CpfSanctionService.PurposeOption> options = service.purposes(member);

        assertThat(options).hasSize(2);
        assertThat(options.get(0).code()).isEqualTo("HOUSE");
        assertThat(options.get(0).name()).isEqualTo("HOUSE purpose");
        assertThat(options.get(0).category()).isEqualTo("PART_FINAL");
        assertThat(options.get(0).propertyDetails()).isTrue();
        assertThat(options.get(0).documents())
                .containsExactly(new ChecklistItem("SALE_DEED", true));
        assertThat(options.get(1).code()).isEqualTo("MARRIAGE");
        assertThat(options.get(1).propertyDetails()).isFalse();
        assertThat(options.get(1).documents()).isEmpty();
    }

    @Test
    void SRS_6_55_theMemberReadsTheOwnDocumentWithoutTheStaffPermissionAndGetsItsContent() {
        sanction(92L, Stage.UNIT_VERIFICATION);
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(documents.read("member@x", "10.0.0.2", "pf_sanction_document", "92", 6L))
                .thenReturn(content);

        assertThat(service.document(member, "10.0.0.2", 92L, 6L)).isSameAs(content);

        verify(policy, never()).can(any(), any(), any());
    }

    @Test
    void SRS_6_55_aStaffReaderInScopeGetsTheContentOfAnotherMembersDocument() {
        sanction(93L, Stage.UNIT_VERIFICATION);
        when(policy.can(unit, "I-04", "VIEW")).thenReturn(true);
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(documents.read("cpf.unit.delhi", "10.0.0.1", "pf_sanction_document", "93", 6L))
                .thenReturn(content);

        assertThat(service.document(unit, "10.0.0.1", 93L, 6L)).isSameAs(content);
    }

    @Test
    void SRS_6_14_aPurposeNoLongerInForceIsSkippedForTheOneThatIs() {
        purpose(8L, "HOUSE", "FINAL", false, false);
        CpfWithdrawalPurpose current = purposes.findById(8L).orElseThrow();
        CpfWithdrawalPurpose old = mock(CpfWithdrawalPurpose.class);
        when(old.getPurposeId()).thenReturn(99L);
        when(old.inForceOn(TODAY)).thenReturn(false);
        when(purposes.findByPurposeCodeOrderByEffectiveFromDesc("HOUSE"))
                .thenReturn(List.of(old, current));

        CpfSanctionService.SanctionView view =
                service.request(
                        member, new Request("HOUSE", FIFTY_THOUSAND, null, null), List.of());

        assertThat(view.purposeCode()).isEqualTo("HOUSE");
        verify(purposes, never()).findById(99L);
    }

    @Test
    void BR_CPF_039_aMemberWithoutEligibilityDataOnRecordIsNotFound() {
        purpose(7L, "FINAL_SETTLEMENT", "FINAL", false, false);
        when(directory.cpfEligibility(9L)).thenReturn(Optional.empty());

        refused(
                () ->
                        service.request(
                                member,
                                new Request("FINAL_SETTLEMENT", FIFTY_THOUSAND, null, null),
                                List.of()),
                CommonErrorCode.NOT_FOUND);

        verify(sanctions, never()).save(any());
    }

    @Test
    void SRS_6_55_theRegisterShowsEachRowsPurposeClaimAndTiersInPassAndTierOrder() {
        CpfWithdrawalPurpose widow = purpose(7L, "WIDOW", "FINAL", false, true);
        PfSanction first = sanctionOf(94L, "FINAL", 7L, Stage.UNIT_VERIFICATION);
        PfSanction second = sanctionOf(95L, "FINAL", 7L, Stage.UNIT_VERIFICATION);
        ReflectionTestUtils.setField(second, "purposeId", null);
        when(sanctions.inScope(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(directory.members(any())).thenReturn(Map.of(9L, MEMBER));
        CpfClaim claim = mock(CpfClaim.class);
        when(claim.getSanctionId()).thenReturn(94L);
        when(claim.getPropertyAddress()).thenReturn("Plot 4, Sector 9");
        when(claims.findBySanctionIdIn(any())).thenReturn(List.of(claim));
        List<CpfSanctionTier> unsorted =
                List.of(
                        tierRow(94L, 2, 1, "FORWARDED"),
                        tierRow(94L, 1, 2, "RECOMMENDED"),
                        tierRow(94L, 1, 1, "FORWARDED"));
        when(tiers.findBySanctionIdInOrderByTierActionIdAsc(any())).thenReturn(unsorted);

        List<CpfSanctionService.SanctionView> rows =
                service.register(unit, null, new PageSpec(0, 20)).items();

        assertThat(rows).hasSize(2);
        CpfSanctionService.SanctionView a = rows.get(0);
        assertThat(a.purposeCode()).isEqualTo(widow.getPurposeCode());
        assertThat(a.purposeName()).isEqualTo("WIDOW purpose");
        assertThat(a.noCasteDisputeRequired()).isTrue();
        assertThat(a.propertyAddress()).isEqualTo("Plot 4, Sector 9");
        assertThat(a.tiers())
                .extracting(CpfSanctionService.TierView::pass, CpfSanctionService.TierView::tier)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(1, 2),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
        CpfSanctionService.SanctionView b = rows.get(1);
        assertThat(b.purposeCode()).isNull();
        assertThat(b.noCasteDisputeRequired()).isFalse();
        assertThat(b.propertyAddress()).isNull();
        assertThat(b.tiers()).isEmpty();
    }

    @Test
    void SRS_6_55_anEmptyRegisterPageLooksUpNoClaimsTiersOrDocuments() {
        when(sanctions.inScope(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.register(unit, null, new PageSpec(0, 20)).items()).isEmpty();

        verify(claims, never()).findBySanctionIdIn(any());
        verify(tiers, never()).findBySanctionIdInOrderByTierActionIdAsc(any());
        verify(documents, never()).activeByKey(any(), any());
    }
}
