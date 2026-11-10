package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-07 / E-01 declarations beyond the integration path (SRS-7-1, SRS-7-14, SRS-7-24; BR-TAX-014): a
 * window that is closed, not configured or whose condition fails; a session without a member
 * record; the proof deadline on a fixed clock; the Checker's rejection and the reset refusals.
 */
class DeclarationServiceTest {

    private static final Instant NOW = Instant.parse("2027-02-10T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 10);
    private static final String YEAR = "2026-27";

    private final TaxRepositories.Regimes regimes = mock(TaxRepositories.Regimes.class);
    private final TaxRepositories.Submissions submissions = mock(TaxRepositories.Submissions.class);
    private final TaxRepositories.Declarations lines = mock(TaxRepositories.Declarations.class);
    private final TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final DeclarationService service =
            new DeclarationService(
                    regimes,
                    submissions,
                    lines,
                    sections,
                    employees,
                    mock(DocumentStore.class),
                    rules,
                    approvals,
                    policy,
                    audit,
                    json,
                    mock(org.springframework.context.ApplicationEventPublisher.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static AccessContext context(Long employeeId) {
        return new AccessContext(
                2L,
                "user",
                "User",
                "s",
                "P",
                1L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(1L));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static RuleEngine.Resolved version(LocalDate to) {
        return new RuleEngine.Resolved(
                "K",
                "APPLICABILITY",
                1L,
                1,
                TODAY.minusDays(60),
                to,
                null,
                List.of(),
                "1 > 0",
                null,
                false);
    }

    private void window(boolean open) {
        when(rules.resolve(eq(DeclarationService.WINDOW_KEY), any(), any()))
                .thenReturn(Optional.of(version(null)));
        when(rules.condition(eq(DeclarationService.WINDOW_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(open));
    }

    @Test
    void SRS_17_2_aSessionWithoutAMemberRecordHasNoDeclarations() {
        refused(() -> service.mine(context(null)), CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.submit(context(null), Regime.OLD, List.of()),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_7_24_aWindowNotConfiguredClosedOrWhoseConditionFailsRefusesTheSubmission() {
        when(rules.resolve(eq(DeclarationService.WINDOW_KEY), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.VALIDATION_FAILED));
        refused(
                () -> service.submit(context(9L), Regime.OLD, List.of()),
                TaxErrorCode.DECLARATION_WINDOW);
        window(false);
        refused(
                () -> service.submit(context(9L), Regime.OLD, List.of()),
                TaxErrorCode.DECLARATION_WINDOW);
        // Out of the rule's scope: the engine answers nothing, so no window either.
        when(rules.resolve(eq(DeclarationService.WINDOW_KEY), any(), any()))
                .thenReturn(Optional.empty());
        refused(
                () -> service.submit(context(9L), Regime.OLD, List.of()),
                TaxErrorCode.DECLARATION_WINDOW);
        verifyNoInteractions(regimes, approvals);
    }

    @Test
    void SRS_7_24_aLineWithoutKindOrSectionIsRefused() {
        window(true);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());
        java.util.ArrayList<DeclarationService.NewLine> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        refused(
                () -> service.submit(context(9L), Regime.OLD, withNull),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.submit(
                                context(9L),
                                Regime.OLD,
                                List.of(
                                        new DeclarationService.NewLine(
                                                null, "80C", BigDecimal.ONE, null))),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.submit(
                                context(9L),
                                Regime.OLD,
                                List.of(
                                        new DeclarationService.NewLine(
                                                Kind.INVESTMENT, null, BigDecimal.ONE, null))),
                CommonErrorCode.VALIDATION_FAILED);
    }

    // US-GAP-195 (SRS-7-15, I-07 Rent Paid "Numeric 12,2"): a rent line of zero or less is
    // refused like any declared amount (the months of the rent wait for a DDL column, Q-08-4).
    @Test
    void SRS_7_15_aRentLineOfZeroOrLessIsRefused() {
        window(true);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());
        for (String rent : new String[] {"0", "-1", "-0.01"}) {
            refused(
                    () ->
                            service.submit(
                                    context(9L),
                                    Regime.OLD,
                                    List.of(
                                            new DeclarationService.NewLine(
                                                    Kind.HRA_RENT,
                                                    "10(13A)",
                                                    new BigDecimal(rent),
                                                    null))),
                    TaxErrorCode.DECLARATION_AMOUNT);
        }
        verifyNoInteractions(approvals);
    }

    // US-GAP-193 (SRS-7-14): a line approved without proof only after the deadline has passed
    // loses its exemption at the next run all the same, and a reversed line is never restored by a
    // later run (a proof comes with a new round, which replaces the approved one).
    @Test
    void SRS_7_14_aLineApprovedAfterTheDeadlineIsReversedAndAReversedLineStaysReversed() {
        ItDeclarationSubmission late =
                ItDeclarationSubmission.submitted(
                        9L, YEAR, 2, 3L, Instant.parse("2027-01-20T06:00:00Z"));
        late.approved();
        when(submissions.findByFinYearAndSubmissionStatus(YEAR, "APPROVED"))
                .thenReturn(List.of(late));
        ItDeclaration approvedLate =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        Instant.parse("2027-02-05T06:00:00Z"));
        approvedLate.approved(6L);
        ItDeclaration reversedBefore =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80D",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        NOW);
        reversedBefore.approved(6L);
        reversedBefore.reversed();
        when(lines.findBySubmissionIdOrderByDeclarationId(any()))
                .thenReturn(List.of(approvedLate, reversedBefore));
        when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                .thenReturn(Optional.of(version(LocalDate.of(2027, 1, 31))));
        when(rules.condition(eq(DeclarationService.PROOF_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(true));

        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isEqualTo(1);
        assertThat(approvedLate.getProofStatus()).isEqualTo("REVERSED");
        assertThat(reversedBefore.getProofStatus()).isEqualTo("REVERSED");
        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isZero();
        assertThat(reversedBefore.getProofStatus()).as("never restored").isEqualTo("REVERSED");
    }

    // SC-08-0041: proofs not furnished by the deadline reverse the declared lines.
    @Test
    void SRS_7_14_afterTheDeadlineTheApprovedLinesWithoutProofAreReversedOnce() {
        ItDeclarationSubmission approved =
                ItDeclarationSubmission.submitted(
                        9L, YEAR, 1, 3L, Instant.parse("2026-06-01T06:00:00Z"));
        approved.approved();
        when(submissions.findByFinYearAndSubmissionStatus(YEAR, "APPROVED"))
                .thenReturn(List.of(approved));
        ItDeclaration unproved =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        NOW);
        unproved.approved(5L);
        ItDeclaration proved =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80D",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        77L,
                        NOW);
        proved.approved(5L);
        ItDeclaration returned =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80E",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        NOW);
        returned.rejected();
        when(lines.findBySubmissionIdOrderByDeclarationId(any()))
                .thenReturn(List.of(unproved, proved, returned));
        // The proof window in force on the submission date ended on 31 January.
        when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                .thenReturn(Optional.of(version(LocalDate.of(2027, 1, 31))));
        when(rules.condition(eq(DeclarationService.PROOF_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(true));

        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isEqualTo(1);
        assertThat(unproved.getProofStatus()).isEqualTo("REVERSED");
        assertThat(proved.getProofStatus()).isEqualTo("APPROVED");
        assertThat(returned.getProofStatus()).isEqualTo("REJECTED");
        assertThat(approved.isProofDeadlineReversed()).isTrue();
        verify(audit).record(eq("batch"), any());
        // A second run finds nothing more to reverse.
        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isZero();
    }

    @Test
    void SRS_7_14_noDeadlineConfiguredReversesNothing() {
        ItDeclarationSubmission approved = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        approved.approved();
        when(submissions.findByFinYearAndSubmissionStatus(YEAR, "APPROVED"))
                .thenReturn(List.of(approved));
        ItDeclaration unproved =
                ItDeclaration.declared(
                        9L,
                        null,
                        YEAR,
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        NOW);
        unproved.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of(unproved));
        when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.VALIDATION_FAILED));
        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isZero();
        assertThat(unproved.getProofStatus()).isEqualTo("APPROVED");
        verifyNoInteractions(audit);
    }

    @Test
    void
            BR_TAX_014_aRejectionWithoutAReasonReturnsTheRoundWithTheDefaultAndOtherScreensAreIgnored() {
        ItDeclarationSubmission round = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        round.verified("maker", NOW, 5L);
        when(submissions.lockById(4L)).thenReturn(Optional.of(round));
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of());
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        when(regimes.findById(3L)).thenReturn(Optional.of(regime));
        service.on(
                new ApprovalDecided(
                        5L,
                        "I-07",
                        DeclarationService.SUBMISSION_ENTITY,
                        "4",
                        ApprovalStatus.REJECTED,
                        null,
                        null,
                        "checker",
                        NOW));
        assertThat(round.status()).isEqualTo(ItDeclarationSubmission.Status.RETURNED);
        assertThat(round.getReturnReason()).isEqualTo("Rejected by the Payroll Checker");
        assertThat(regime.getVerifyStatus()).isEqualTo("RETURNED");

        service.on(
                new ApprovalDecided(
                        6L,
                        "I-04",
                        DeclarationService.SUBMISSION_ENTITY,
                        "4",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "c",
                        NOW));
        // A rejected reset changes nothing.
        service.on(
                new ApprovalDecided(
                        7L,
                        "I-07",
                        DeclarationService.REGIME_ENTITY,
                        "3",
                        ApprovalStatus.REJECTED,
                        null,
                        null,
                        "c",
                        NOW));
        // Only the first decision (the rejection of the I-07 round) wrote the regime.
        verify(regimes, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void FRS_V05_14_3_aResetNeedsAReasonAFrozenRegimeAnotherMemberAndAnOfficeInScope() {
        AccessContext maker = context(1L);
        refused(
                () -> service.proposeReset(maker, 9L, YEAR, " "),
                CommonErrorCode.VALIDATION_FAILED);
        when(employees.member(9L)).thenReturn(Optional.empty());
        refused(() -> service.proposeReset(maker, 9L, YEAR, "wrong"), CommonErrorCode.NOT_FOUND);
        when(employees.member(1L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(1L, "1", "Self", 1L, "IN_SERVICE")));
        refused(() -> service.proposeReset(maker, 1L, YEAR, "wrong"), TaxErrorCode.OWN_DECLARATION);
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        ItRegimeDetail open = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(open));
        refused(
                () -> service.proposeReset(maker, 9L, YEAR, "wrong"),
                TaxErrorCode.DECLARATION_STATE);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_7_24_nobodyVerifiesOrReturnsTheOwnRound() {
        ItDeclarationSubmission own = ItDeclarationSubmission.submitted(1L, YEAR, 1, 3L, NOW);
        when(submissions.lockById(4L)).thenReturn(Optional.of(own));
        when(employees.member(1L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(1L, "1", "Self", 1L, "IN_SERVICE")));
        refused(() -> service.verify(context(1L), 4L, List.of()), TaxErrorCode.OWN_DECLARATION);
        refused(() -> service.returnToMember(context(1L), 4L, "r"), TaxErrorCode.OWN_DECLARATION);
        refused(
                () -> service.returnToMember(context(2L), 4L, " "),
                CommonErrorCode.VALIDATION_FAILED);
        when(submissions.lockById(5L)).thenReturn(Optional.empty());
        refused(() -> service.verify(context(2L), 5L, List.of()), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_7_24_aVerificationNamesEachOpenLineOnce() {
        ItDeclarationSubmission round = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        when(submissions.lockById(4L)).thenReturn(Optional.of(round));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of());
        refused(
                () ->
                        service.verify(
                                context(2L),
                                4L,
                                java.util.Arrays.asList(
                                        (DeclarationService.LineVerification) null)),
                TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(2L),
                                4L,
                                List.of(
                                        new DeclarationService.LineVerification(1L, BigDecimal.ONE),
                                        new DeclarationService.LineVerification(
                                                1L, BigDecimal.ONE))),
                TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(2L),
                                4L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                null, BigDecimal.ONE))),
                TaxErrorCode.VERIFIED_AMOUNT);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_7_24_aUserWithoutOfficesSeesAnEmptyRegisterAndAnUnknownStatusIsRefused() {
        AccessContext nowhere =
                new AccessContext(
                        2L, "user", "User", "s", "P", 1L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThat(service.register(nowhere, null, null, new PageSpec(0, 20)).items()).isEmpty();
        assertThat(service.register(nowhere, YEAR, "SUBMITTED", new PageSpec(0, 20)).total())
                .isZero();
        assertThatThrownBy(() -> service.register(nowhere, YEAR, "LOST", new PageSpec(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_7_24_aProofNotListedOnTheRoundIsNotFound() {
        when(submissions.findById(5L)).thenReturn(Optional.empty());
        refused(() -> service.proof(context(9L), "10.0.0.1", 5L, 7L), CommonErrorCode.NOT_FOUND);
        ItDeclarationSubmission own = mock(ItDeclarationSubmission.class);
        when(own.getEmployeeId()).thenReturn(9L);
        when(own.getSubmissionId()).thenReturn(6L);
        when(submissions.findById(6L)).thenReturn(Optional.of(own));
        ItDeclaration other = mock(ItDeclaration.class);
        when(other.getAttachmentId()).thenReturn(8L);
        when(lines.findBySubmissionIdOrderByDeclarationId(6L)).thenReturn(List.of(other));
        refused(() -> service.proof(context(9L), "10.0.0.1", 6L, 7L), CommonErrorCode.NOT_FOUND);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, null))
                .thenReturn(List.of(own));
        refused(() -> service.proof(context(9L), "10.0.0.1", 6L, 8L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void BR_TAX_014_theCheckersOwnReasonIsKeptWhenGivenEitherWay() {
        ItDeclarationSubmission round = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        round.verified("maker", NOW, 5L);
        when(submissions.lockById(4L)).thenReturn(Optional.of(round));
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of());
        when(regimes.findById(3L)).thenReturn(Optional.empty());
        service.on(
                new ApprovalDecided(
                        5L,
                        "I-07",
                        DeclarationService.SUBMISSION_ENTITY,
                        "4",
                        ApprovalStatus.REJECTED,
                        "Rent receipts missing",
                        null,
                        "checker",
                        NOW));
        assertThat(round.getReturnReason()).isEqualTo("Rent receipts missing");

        ItDeclarationSubmission next = ItDeclarationSubmission.submitted(9L, YEAR, 2, 3L, NOW);
        next.verified("maker", NOW, 6L);
        when(submissions.lockById(8L)).thenReturn(Optional.of(next));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(next));
        service.on(
                new ApprovalDecided(
                        6L,
                        "I-07",
                        DeclarationService.SUBMISSION_ENTITY,
                        "8",
                        ApprovalStatus.APPROVED,
                        "Proofs seen",
                        null,
                        "checker",
                        NOW));
        assertThat(next.status()).isEqualTo(ItDeclarationSubmission.Status.APPROVED);
        verify(audit)
                .record(
                        eq("checker"),
                        org.mockito.ArgumentMatchers.argThat(
                                e -> "Proofs seen".equals(e.reason())));
    }

    @Test
    void FRS_V05_14_3_aResetWithoutAnyReasonIsRefused() {
        refused(
                () -> service.proposeReset(context(1L), 9L, YEAR, null),
                CommonErrorCode.VALIDATION_FAILED);
    }

    /**
     * SRS-7-24 / SRS-17-18: a regime-only round (no lines) after an approved round with a frozen
     * rent line carries that line into the new round and is logged as a regime choice; an open rent
     * line and a frozen non-rent line are not carried.
     */
    @Test
    void SRS_7_24_aRegimeOnlyRoundCarriesTheFrozenRentLine() {
        window(true);
        ItRegimeDetail locked = mock(ItRegimeDetail.class);
        when(locked.isLocked()).thenReturn(true);
        when(locked.getRegime()).thenReturn(Regime.OLD);
        when(locked.getRegimeDetailId()).thenReturn(3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(locked));
        when(regimes.save(any())).thenAnswer(a -> a.getArgument(0));
        ItDeclarationSubmission approved = mock(ItDeclarationSubmission.class);
        when(approved.status()).thenReturn(ItDeclarationSubmission.Status.APPROVED);
        when(approved.replaceable()).thenReturn(true);
        when(approved.getSubmissionId()).thenReturn(1L);
        when(approved.getSubmissionNo()).thenReturn(1);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(approved));
        when(submissions.save(any())).thenAnswer(a -> a.getArgument(0));
        ItDeclaration rent = mock(ItDeclaration.class);
        when(rent.kind()).thenReturn(Kind.HRA_RENT);
        when(rent.isFrozen()).thenReturn(true);
        ItDeclaration openRent = mock(ItDeclaration.class);
        when(openRent.kind()).thenReturn(Kind.HRA_RENT);
        ItDeclaration frozenInvestment = mock(ItDeclaration.class);
        when(frozenInvestment.kind()).thenReturn(Kind.INVESTMENT);
        when(frozenInvestment.isFrozen()).thenReturn(true);
        when(lines.findBySubmissionIdOrderByDeclarationId(1L))
                .thenReturn(List.of(rent, openRent, frozenInvestment));
        ItDeclaration carried = mock(ItDeclaration.class);
        when(carried.getDeclarationId()).thenReturn(77L);
        when(rent.carriedInto(any(), any())).thenReturn(carried);
        when(lines.save(carried)).thenReturn(carried);
        when(sections.inForceOn(TODAY)).thenReturn(List.of());

        service.submit(context(9L), Regime.OLD, null);

        verify(lines).save(carried);
        verify(openRent, never()).carriedInto(any(), any());
        verify(frozenInvestment, never()).carriedInto(any(), any());
    }

    /**
     * BR-TAX-014: a return needs a reason; it rejects the open lines only (a frozen rent line
     * stays) and leaves a locked regime as it is. A member missing from the directory shows without
     * number and name.
     */
    @Test
    void BR_TAX_014_aReturnNeedsAReasonAndKeepsFrozenLinesAndALockedRegime() {
        ItDeclarationSubmission round = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        when(submissions.lockById(4L)).thenReturn(Optional.of(round));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        refused(
                () -> service.returnToMember(context(2L), 4L, null),
                CommonErrorCode.VALIDATION_FAILED);
        ItDeclaration frozen = mock(ItDeclaration.class);
        when(frozen.isFrozen()).thenReturn(true);
        ItDeclaration open = mock(ItDeclaration.class);
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of(frozen, open));
        ItRegimeDetail locked = mock(ItRegimeDetail.class);
        when(locked.isLocked()).thenReturn(true);
        when(regimes.findById(3L)).thenReturn(Optional.of(locked));

        DeclarationService.SubmissionView view =
                service.returnToMember(context(2L), 4L, " Rent receipt unclear ");

        verify(open).rejected();
        verify(frozen, never()).rejected();
        verify(locked, never()).returned();
        assertThat(round.getReturnReason()).isEqualTo("Rent receipt unclear");
        assertThat(view.eis()).isNull();
        assertThat(view.name()).isNull();
    }

    /**
     * US-GAP-194 (SRS-7-1, FRS V0.5 §14.3): the authorised, audited reset is the one way to reopen
     * a frozen regime, for a year already ended and locked as for the current one; approved, the
     * regime is open again (so no certificate or return is made on it until it is frozen again,
     * US-GAP-196) and the reset is audited with its approval.
     */
    @Test
    void SRS_7_1_aFrozenRegimeOfAYearAlreadyLockedIsReopenedOnlyByAnApprovedReset() {
        String ended = "2025-26";
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        ItRegimeDetail frozen = ItRegimeDetail.elected(9L, ended, Regime.OLD, NOW);
        frozen.approvedAndFrozen(4L);
        when(regimes.lockFor(9L, ended)).thenReturn(Optional.of(frozen));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(
                        new in.gov.fci.payroll.platform.approval.SubmittedApproval(
                                11L, ApprovalStatus.PENDING_APPROVAL, null));
        assertThat(service.proposeReset(context(1L), 9L, ended, "Form 16 corrected"))
                .isEqualTo(11L);
        assertThat(frozen.isLocked()).as("frozen until the Checker approves").isTrue();

        when(regimes.findById(3L)).thenReturn(Optional.of(frozen));
        service.on(
                new ApprovalDecided(
                        11L,
                        "I-07",
                        DeclarationService.REGIME_ENTITY,
                        "3",
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"regimeDetailId\":3,\"finYear\":\"2025-26\",\"reason\":\"Form 16"
                                + " corrected\"}",
                        "checker",
                        NOW));
        assertThat(frozen.isLocked()).isFalse();
        assertThat(frozen.getVerifyStatus()).isEqualTo("SUBMITTED");
        assertThat(frozen.getResetReason()).isEqualTo("Form 16 corrected");
        verify(audit).record(eq("checker"), any());
    }

    /** FRS V0.5 §14.3: a rejected reset proposal changes nothing. */
    @Test
    void FRS_V05_14_3_aRejectedResetLeavesTheRegimeAlone() {
        service.on(
                new ApprovalDecided(
                        7L,
                        "I-07",
                        DeclarationService.REGIME_ENTITY,
                        "3",
                        ApprovalStatus.REJECTED,
                        "Not justified",
                        null,
                        "checker",
                        NOW));
        verifyNoInteractions(regimes);
    }

    /**
     * SRS-7-24: I-07 staff read a member's proof within their offices; a proof the document store
     * does not hold for any of the member's rounds is not found.
     */
    @Test
    void SRS_7_24_staffReadAProofOnlyInScopeAndOnlyIfItIsHeld() {
        ItDeclarationSubmission theirs = mock(ItDeclarationSubmission.class);
        when(theirs.getEmployeeId()).thenReturn(9L);
        when(theirs.getSubmissionId()).thenReturn(6L);
        when(theirs.getFinYear()).thenReturn(YEAR);
        when(submissions.findById(6L)).thenReturn(Optional.of(theirs));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        ItDeclaration line = mock(ItDeclaration.class);
        when(line.getAttachmentId()).thenReturn(8L);
        when(lines.findBySubmissionIdOrderByDeclarationId(6L)).thenReturn(List.of(line));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(theirs));
        // W-5 (security review 2026-11-04): without I-07 another member's round does not exist;
        // no scope check is even made.
        refused(() -> service.proof(context(2L), "10.0.0.1", 6L, 8L), CommonErrorCode.NOT_FOUND);
        verify(policy, never())
                .requireRecordAt(any(), eq(DeclarationService.SCREEN), eq("VIEW"), eq(1L));
        when(policy.can(any(), eq(DeclarationService.SCREEN), eq("VIEW"))).thenReturn(true);
        refused(() -> service.proof(context(2L), "10.0.0.1", 6L, 8L), CommonErrorCode.NOT_FOUND);
        verify(policy).requireRecordAt(any(), eq(DeclarationService.SCREEN), eq("VIEW"), eq(1L));
    }

    /**
     * SRS-7-24 / SRS-17-18: a member's first round with a regime and no line at all (nothing to
     * carry either) is saved and audited on I-07; no declaration line is written.
     */
    @Test
    void SRS_7_24_aFirstRegimeOnlyRoundWritesNoLine() {
        window(true);
        when(regimes.save(any())).thenAnswer(a -> a.getArgument(0));
        when(submissions.save(any())).thenAnswer(a -> a.getArgument(0));
        when(sections.inForceOn(TODAY)).thenReturn(List.of());

        DeclarationService.SubmissionView v = service.submit(context(9L), Regime.NEW, List.of());

        assertThat(v).isNotNull();
        verify(lines, never()).save(any());
        verify(audit).record(eq("user"), any());
    }

    /**
     * SRS-7-24: the verification names each open line; none sent for an open line, or one naming
     * another line instead, is refused before anything is verified.
     */
    @Test
    void SRS_7_24_aVerificationMustNameTheOpenLinesThemselves() {
        ItDeclarationSubmission round = ItDeclarationSubmission.submitted(9L, YEAR, 1, 3L, NOW);
        when(submissions.lockById(4L)).thenReturn(Optional.of(round));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "9", "M", 1L, "IN_SERVICE")));
        ItDeclaration open = mock(ItDeclaration.class);
        when(open.isFrozen()).thenReturn(false);
        when(open.getDeclarationId()).thenReturn(10L);
        when(lines.findBySubmissionIdOrderByDeclarationId(any())).thenReturn(List.of(open));
        refused(() -> service.verify(context(2L), 4L, null), TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(2L),
                                4L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                11L, BigDecimal.ONE))),
                TaxErrorCode.VERIFIED_AMOUNT);
        verify(open, never()).verified(any(), any(), any());
        verifyNoInteractions(approvals);
    }

    /** BR-TAX-014: an I-07 decision on another entity changes neither a round nor a regime. */
    @Test
    void BR_TAX_014_anApprovedDecisionOnAnotherEntityChangesNothing() {
        service.on(
                new ApprovalDecided(
                        8L,
                        "I-07",
                        "it_other_entity",
                        "3",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker",
                        NOW));
        verifyNoInteractions(regimes, submissions, audit);
    }
}
