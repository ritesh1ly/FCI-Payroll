package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclaration.Kind;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission.Status;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.business.model.ItSection;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kills the PIT survivors of {@link DeclarationService} (SRS-7-1, SRS-7-14, SRS-7-15, SRS-7-16,
 * SRS-7-24, SRS-17-2, BR-TAX-001, BR-TAX-014, FRS V0.5 section 14.3): the permission every entry
 * point asks, what a submission, a verification, a return and a decision write, and the fields of a
 * round as shown.
 */
class DeclarationServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2027-02-10T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2027, 2, 10);
    private static final String YEAR = "2026-27";

    private final TaxRepositories.Regimes regimes = mock(TaxRepositories.Regimes.class);
    private final TaxRepositories.Submissions submissions = mock(TaxRepositories.Submissions.class);
    private final TaxRepositories.Declarations lines = mock(TaxRepositories.Declarations.class);
    private final TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final DeclarationService service =
            new DeclarationService(
                    regimes,
                    submissions,
                    lines,
                    sections,
                    employees,
                    store,
                    rules,
                    approvals,
                    policy,
                    audit,
                    json,
                    events,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private long nextId = 1000;

    DeclarationServiceMutationGapsTest() {
        when(regimes.save(any()))
                .thenAnswer(
                        a -> {
                            ItRegimeDetail r = a.getArgument(0);
                            if (r.getRegimeDetailId() == null) {
                                ReflectionTestUtils.setField(r, "regimeDetailId", 3L);
                            }
                            return r;
                        });
        when(submissions.save(any()))
                .thenAnswer(
                        a -> {
                            ItDeclarationSubmission s = a.getArgument(0);
                            if (s.getSubmissionId() == null) {
                                ReflectionTestUtils.setField(s, "submissionId", 40L);
                            }
                            return s;
                        });
        when(lines.save(any()))
                .thenAnswer(
                        a -> {
                            ItDeclaration d = a.getArgument(0);
                            if (d.getDeclarationId() == null) {
                                ReflectionTestUtils.setField(d, "declarationId", nextId++);
                            }
                            return d;
                        });
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(900L, ApprovalStatus.PENDING_APPROVAL, null));
    }

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

    private void windowOpen(LocalDate proofsUntil) {
        when(rules.resolve(eq(DeclarationService.WINDOW_KEY), any(), any()))
                .thenReturn(Optional.of(version(null)));
        when(rules.condition(eq(DeclarationService.WINDOW_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(true));
        if (proofsUntil != null) {
            when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                    .thenReturn(Optional.of(version(proofsUntil)));
            when(rules.condition(eq(DeclarationService.PROOF_KEY), any(), any(), anyMap()))
                    .thenReturn(Optional.of(true));
        }
    }

    private static ItSection section(String code, String max, String regime) {
        ItSection s = mock(ItSection.class);
        when(s.getSectionCode()).thenReturn(code);
        when(s.getSectionDescription()).thenReturn("Description " + code);
        when(s.getMaxDeductionAmount()).thenReturn(max == null ? null : new BigDecimal(max));
        when(s.getRegimeApplicability()).thenReturn(regime);
        return s;
    }

    private void sectionsInForce(ItSection... inForce) {
        when(sections.inForceOn(TODAY)).thenReturn(List.of(inForce));
    }

    private static ItDeclarationSubmission round(long id, int no, Status status) {
        ItDeclarationSubmission s = ItDeclarationSubmission.submitted(9L, YEAR, no, 3L, NOW);
        ReflectionTestUtils.setField(s, "submissionId", id);
        ReflectionTestUtils.setField(s, "submissionStatus", status.name());
        return s;
    }

    private static ItDeclaration line(long id, Kind kind, String declared, String allowed) {
        ItDeclaration d =
                ItDeclaration.declared(
                        9L,
                        40L,
                        YEAR,
                        kind,
                        "80C",
                        new BigDecimal(declared),
                        new BigDecimal(allowed),
                        null,
                        NOW);
        ReflectionTestUtils.setField(d, "declarationId", id);
        return d;
    }

    private DocumentStore.AttachmentView attachment(long id) {
        DocumentStore.AttachmentView v = mock(DocumentStore.AttachmentView.class);
        when(v.id()).thenReturn(id);
        return v;
    }

    // ------------------------------------------------------------- mine

    @Test
    void SRS_17_2_theMembersViewAsksE01ViewAndShowsTheYearsRegimeSectionsAndRounds() {
        windowOpen(LocalDate.of(2027, 3, 31));
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.NEW, NOW);
        regime.approvedAndFrozen(5L);
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(regime));
        sectionsInForce(section("80C", "150000.00", "OLD"), section("80D", null, "BOTH"));
        ItDeclarationSubmission r1 = round(41L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(r1));
        ItDeclaration capped = line(70L, Kind.INVESTMENT, "200000.00", "150000.00");
        when(lines.findBySubmissionIdInOrderByDeclarationId(any())).thenReturn(List.of(capped));
        ReflectionTestUtils.setField(capped, "submissionId", 41L);
        when(employees.members(any()))
                .thenReturn(
                        Map.of(
                                9L,
                                new EmployeeDirectory.Member(9L, "900009", "Asha Rao", 1L, "IN")));
        when(regimes.findByRegimeDetailIdIn(any())).thenReturn(List.of(regime));
        ReflectionTestUtils.setField(regime, "regimeDetailId", 3L);

        AccessContext member = context(9L);
        DeclarationService.MyDeclarations mine = service.mine(member);

        verify(policy).require(member, "E-01", "SELF");
        assertThat(mine.finYear()).isEqualTo(YEAR);
        assertThat(mine.windowOpen()).isTrue();
        assertThat(mine.proofsUntil()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(mine.regime()).isEqualTo(Regime.NEW);
        assertThat(mine.regimeFrozen()).isTrue();
        assertThat(mine.sections())
                .containsExactly(
                        new DeclarationService.SectionOption(
                                "80C", "Description 80C", new BigDecimal("150000.00"), "OLD"),
                        new DeclarationService.SectionOption(
                                "80D", "Description 80D", null, "BOTH"));
        assertThat(mine.rounds()).hasSize(1);
        DeclarationService.SubmissionView v = mine.rounds().get(0);
        assertThat(v.id()).isEqualTo(41L);
        assertThat(v.employeeId()).isEqualTo(9L);
        assertThat(v.eis()).isEqualTo("900009");
        assertThat(v.name()).isEqualTo("Asha Rao");
        assertThat(v.finYear()).isEqualTo(YEAR);
        assertThat(v.number()).isEqualTo(1);
        assertThat(v.status()).isEqualTo("APPROVED");
        assertThat(v.submittedAt()).isEqualTo(NOW);
        assertThat(v.regime()).isEqualTo(Regime.NEW);
        assertThat(v.regimeFrozen()).isTrue();
        assertThat(v.proofDeadlineReversed()).isFalse();
        assertThat(v.lines()).hasSize(1);
        DeclarationService.LineView l = v.lines().get(0);
        assertThat(l.id()).isEqualTo(70L);
        assertThat(l.kind()).isEqualTo(Kind.INVESTMENT);
        assertThat(l.sectionCode()).isEqualTo("80C");
        assertThat(l.declared()).isEqualByComparingTo("200000.00");
        assertThat(l.allowed()).isEqualByComparingTo("150000.00");
        assertThat(l.capped()).isTrue();
        assertThat(l.proofStatus()).isEqualTo("DECLARED");
        assertThat(l.verified()).isNull();
        assertThat(l.frozen()).isFalse();
        assertThat(l.proofId()).isNull();
    }

    @Test
    void SRS_17_2_aMemberWithoutAnElectionSeesNoRegimeAndAClosedWindow() {
        when(rules.resolve(any(), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.VALIDATION_FAILED));
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.empty());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        DeclarationService.MyDeclarations mine = service.mine(context(9L));
        assertThat(mine.windowOpen()).isFalse();
        assertThat(mine.proofsUntil()).isNull();
        assertThat(mine.regime()).isNull();
        assertThat(mine.regimeFrozen()).isFalse();
        assertThat(mine.rounds()).isEmpty();
    }

    // ----------------------------------------------------------- submit

    @Test
    void SRS_7_24_aSubmissionReplacesTheOpenRoundSavesTheCappedLineWithItsProofAndIsAudited() {
        windowOpen(null);
        AccessContext member = context(9L);
        ItRegimeDetail existing =
                ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW.minusSeconds(900));
        ReflectionTestUtils.setField(existing, "regimeDetailId", 3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(existing));
        ItDeclarationSubmission open = round(39L, 2, Status.SUBMITTED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(open));
        sectionsInForce(section("80C", "150000.00", "BOTH"), section("80D", "25000.00", "BOTH"));
        DocumentStore.AttachmentView stored = attachment(88L);
        when(store.attach(eq("user"), any())).thenReturn(stored);

        DeclarationService.Proof proof =
                new DeclarationService.Proof(
                        "LIC_RECEIPT", "R-1", TODAY, "LIC", "p.pdf", new byte[] {1, 2});
        DeclarationService.SubmissionView view =
                service.submit(
                        member,
                        Regime.NEW,
                        List.of(
                                new DeclarationService.NewLine(
                                        Kind.INVESTMENT, "80C", new BigDecimal("200000.00"), proof),
                                new DeclarationService.NewLine(
                                        Kind.INVESTMENT, "80D", new BigDecimal("10000.00"), null)));

        verify(policy).require(member, "E-01", "CREATE");
        assertThat(existing.getRegime())
                .as("an unfrozen election is changed")
                .isEqualTo(Regime.NEW);
        assertThat(open.status())
                .as("the open round is replaced at once")
                .isEqualTo(Status.SUPERSEDED);
        verify(submissions).save(open);
        assertThat(view.number()).isEqualTo(3);

        ArgumentCaptor<ItDeclaration> saved = ArgumentCaptor.forClass(ItDeclaration.class);
        verify(lines, times(2)).save(saved.capture());
        ItDeclaration first = saved.getAllValues().get(0);
        assertThat(first.getSectionCode()).isEqualTo("80C");
        assertThat(first.getDeclaredAmount()).isEqualByComparingTo("200000.00");
        assertThat(first.getAllowedAmount())
                .as("capped at the section limit")
                .isEqualByComparingTo("150000.00");
        assertThat(first.getAttachmentId()).isEqualTo(88L);
        assertThat(first.getSubmissionId()).isEqualTo(40L);
        ItDeclaration second = saved.getAllValues().get(1);
        assertThat(second.getAllowedAmount()).isEqualByComparingTo("10000.00");
        assertThat(second.getAttachmentId()).isNull();

        ArgumentCaptor<DocumentStore.NewAttachment> attach =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(store, times(1)).attach(eq("user"), attach.capture());
        assertThat(attach.getValue().entityName()).isEqualTo(DeclarationService.SUBMISSION_ENTITY);
        assertThat(attach.getValue().entityKey()).isEqualTo("40");

        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("user"), entry.capture());
        assertThat(entry.getValue().entityName()).isEqualTo(DeclarationService.SUBMISSION_ENTITY);
        assertThat(entry.getValue().entityKey()).isEqualTo("40");
        assertThat(entry.getValue().fieldName()).isEqualTo("submission");
        assertThat(entry.getValue().oldValue()).isEqualTo("round 2");
        assertThat(entry.getValue().newValue()).isEqualTo("round 3 2026-27; regime NEW; lines 2");
        assertThat(entry.getValue().reason()).isEqualTo("eSeva declaration");
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-07");

        ArgumentCaptor<SelfServiceEvents.Submitted> event =
                ArgumentCaptor.forClass(SelfServiceEvents.Submitted.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().employeeId()).isEqualTo(9L);
        assertThat(event.getValue().type()).isEqualTo(SelfServiceEvents.Type.TAX_DECLARATION);
        assertThat(event.getValue().entityName()).isEqualTo(DeclarationService.SUBMISSION_ENTITY);
        assertThat(event.getValue().entityId()).isEqualTo(40L);
        assertThat(event.getValue().declarationId())
                .as("linked to the FIRST line")
                .isEqualTo(first.getDeclarationId());
        assertThat(event.getValue().value())
                .isEqualTo("Declarations 2026-27 round 3, regime NEW, 2 lines");
        assertThat(event.getValue().actor()).isEqualTo("user");
    }

    @Test
    void SRS_7_24_aFrozenRegimeIsNeverChangedByANewRound() {
        windowOpen(null);
        ItRegimeDetail frozen = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW.minusSeconds(900));
        frozen.approvedAndFrozen(5L);
        ReflectionTestUtils.setField(frozen, "regimeDetailId", 3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(frozen));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());
        service.submit(context(9L), Regime.OLD, List.of());
        assertThat(frozen.getRegime()).isEqualTo(Regime.OLD);
        assertThat(frozen.getVerifyStatus()).isEqualTo("APPROVED");
        refused(
                () -> service.submit(context(9L), Regime.NEW, List.of()),
                TaxErrorCode.REGIME_FROZEN);
    }

    @Test
    void SRS_7_24_anApprovedRoundStaysTheOneTdsReadsUntilItsSuccessorIsApproved() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        ItDeclarationSubmission approved = round(39L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(approved));
        when(lines.findBySubmissionIdOrderByDeclarationId(39L)).thenReturn(List.of());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());
        service.submit(context(9L), Regime.NEW, List.of());
        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        verify(submissions, never()).save(approved);
    }

    @Test
    void SRS_7_24_aRoundAtTheCheckerCannotBeReplaced() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(round(39L, 1, Status.VERIFIED)));
        refused(
                () -> service.submit(context(9L), Regime.NEW, List.of()),
                TaxErrorCode.DECLARATION_PENDING);
        verify(regimes, never()).save(any());
    }

    @Test
    void SRS_7_15_aSectionNotInForceOrNotForTheChosenRegimeIsRefusedBeforeAnythingIsSaved() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        sectionsInForce(section("80C", "150000", "OLD"));
        DeclarationService.NewLine oldOnly =
                new DeclarationService.NewLine(Kind.INVESTMENT, "80C", BigDecimal.TEN, null);
        refused(
                () -> service.submit(context(9L), Regime.NEW, List.of(oldOnly)),
                TaxErrorCode.DECLARATION_SECTION);
        DeclarationService.NewLine unknown =
                new DeclarationService.NewLine(Kind.INVESTMENT, "80Z", BigDecimal.TEN, null);
        refused(
                () -> service.submit(context(9L), Regime.OLD, List.of(unknown)),
                TaxErrorCode.DECLARATION_SECTION);
        verify(regimes, never()).save(any());
        verify(submissions, never()).save(any());
    }

    @Test
    void SRS_7_15_theSameKindAndSectionTwiceInOneRoundIsRefused() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        sectionsInForce(section("80C", "150000", "BOTH"));
        DeclarationService.NewLine l =
                new DeclarationService.NewLine(Kind.INVESTMENT, "80C", BigDecimal.TEN, null);
        refused(
                () -> service.submit(context(9L), Regime.OLD, List.of(l, l)),
                TaxErrorCode.DECLARATION_DUPLICATE);
        verify(submissions, never()).save(any());
    }

    @Test
    void SRS_17_18_aRegimeOnlyRoundThatCarriesTheFrozenRentIsLoggedAsARegimeChoice() {
        windowOpen(null);
        ItRegimeDetail frozen = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        frozen.approvedAndFrozen(5L);
        ReflectionTestUtils.setField(frozen, "regimeDetailId", 3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(frozen));
        ItDeclarationSubmission approved = round(39L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(approved));
        ItDeclaration rent = line(61L, Kind.HRA_RENT, "120000.00", "120000.00");
        rent.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(39L)).thenReturn(List.of(rent));
        when(sections.inForceOn(TODAY)).thenReturn(List.of());

        service.submit(context(9L), Regime.OLD, null);

        ArgumentCaptor<SelfServiceEvents.Submitted> event =
                ArgumentCaptor.forClass(SelfServiceEvents.Submitted.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo(SelfServiceEvents.Type.REGIME_CHOICE);
        assertThat(event.getValue().declarationId()).isNotNull();
        assertThat(event.getValue().value()).endsWith("regime OLD, 0 lines");
    }

    @Test
    void SRS_7_15_aNewRentLineIsRefusedWhileTheApprovedRentIsFrozen() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        ItDeclarationSubmission approved = round(39L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(approved));
        ItDeclaration rent = line(61L, Kind.HRA_RENT, "120000.00", "120000.00");
        rent.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(39L)).thenReturn(List.of(rent));
        DeclarationService.NewLine newRent =
                new DeclarationService.NewLine(Kind.HRA_RENT, "80GG", BigDecimal.TEN, null);
        assertThatThrownBy(() -> service.submit(context(9L), Regime.OLD, List.of(newRent)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.RENT_FROZEN);
                            assertThat(e.getMessageArgs()).containsExactly(YEAR);
                        });
    }

    // --------------------------------------------------- register / get

    @Test
    void SRS_7_24_theRegisterAndOneRoundAskI07ViewAndTheRoundsOffice() {
        AccessContext maker = context(1L);
        ItDeclarationSubmission r = round(41L, 1, Status.SUBMITTED);
        when(submissions.inScope(eq(YEAR), any(), eq("SUBMITTED"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(r)));
        when(employees.members(any()))
                .thenReturn(
                        Map.of(9L, new EmployeeDirectory.Member(9L, "900009", "Asha", 7L, "IN")));
        when(lines.findBySubmissionIdInOrderByDeclarationId(any())).thenReturn(List.of());
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        ReflectionTestUtils.setField(regime, "regimeDetailId", 3L);
        when(regimes.findByRegimeDetailIdIn(any())).thenReturn(List.of(regime));

        Paged<DeclarationService.SubmissionView> page =
                service.register(maker, YEAR, "SUBMITTED", new PageSpec(0, 20));

        verify(policy).require(maker, "I-07", "VIEW");
        assertThat(page.items())
                .extracting(DeclarationService.SubmissionView::id)
                .containsExactly(41L);
        assertThat(page.items().get(0).regime()).isEqualTo(Regime.OLD);
        assertThat(page.items().get(0).regimeFrozen()).isFalse();

        when(submissions.findById(41L)).thenReturn(Optional.of(r));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "900009", "Asha", 7L, "IN")));
        assertThat(service.get(maker, 41L).id()).isEqualTo(41L);
        verify(policy, times(2)).require(maker, "I-07", "VIEW");
        verify(policy).requireRecordAt(maker, "I-07", "VIEW", 7L);
    }

    @Test
    void SRS_7_24_theRegisterDefaultsToTheCurrentFinancialYear() {
        AccessContext maker = context(1L);
        when(submissions.inScope(eq(YEAR), any(), eq(null), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        assertThat(service.register(maker, null, null, new PageSpec(0, 20)).items()).isEmpty();
        verify(submissions).inScope(eq(YEAR), any(), eq(null), any());
    }

    // ----------------------------------------------------------- verify

    private ItDeclarationSubmission roundForVerification() {
        ItDeclarationSubmission s = round(41L, 1, Status.SUBMITTED);
        when(submissions.lockById(41L)).thenReturn(Optional.of(s));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "900009", "Asha", 7L, "IN")));
        return s;
    }

    @Test
    void SRS_7_24_aVerificationRecordsEachLineSendsTheRoundToTheCheckerAndAuditsIt() {
        AccessContext maker = context(1L);
        ItDeclarationSubmission s = roundForVerification();
        ItDeclaration a = line(70L, Kind.INVESTMENT, "50000.00", "50000.00");
        ItDeclaration b = line(71L, Kind.INVESTMENT, "30000.00", "25000.00");
        ItDeclaration frozen = line(72L, Kind.HRA_RENT, "10000.00", "10000.00");
        frozen.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(a, b, frozen));
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        when(regimes.findById(3L)).thenReturn(Optional.of(regime));

        service.verify(
                maker,
                41L,
                List.of(
                        new DeclarationService.LineVerification(70L, new BigDecimal("40000.00")),
                        new DeclarationService.LineVerification(71L, new BigDecimal("25000.00"))));

        verify(policy).require(maker, "I-07", "SUBMIT");
        verify(policy).requireRecordAt(maker, "I-07", "SUBMIT", 7L);
        assertThat(a.getVerifiedAmount()).isEqualByComparingTo("40000.00");
        assertThat(a.getProofStatus()).isEqualTo("VERIFIED");
        assertThat(b.getVerifiedAmount()).isEqualByComparingTo("25000.00");
        verify(lines).save(a);
        verify(lines).save(b);
        verify(lines, never()).save(frozen);

        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(maker), submission.capture(), eq(null));
        Submission sent = submission.getValue();
        assertThat(sent.workflowAction()).isEqualTo("I07_SUBMIT");
        assertThat(sent.moduleCode()).isEqualTo("I-07");
        assertThat(sent.actionCode()).isEqualTo("SUBMIT");
        assertThat(sent.entityName()).isEqualTo(DeclarationService.SUBMISSION_ENTITY);
        assertThat(sent.entityKey()).isEqualTo("41");
        assertThat(sent.actionType()).isEqualTo(ActionType.UPDATE);
        assertThat(sent.locationId()).isEqualTo(7L);
        assertThat(sent.effectiveDate()).isEqualTo(TODAY);
        assertThat(sent.reason()).isEqualTo("Declarations 2026-27 round 1");

        assertThat(s.status()).isEqualTo(Status.VERIFIED);
        assertThat(s.getVerifiedBy()).isEqualTo("user");
        assertThat(s.getApprovalId()).isEqualTo(900L);
        verify(submissions).save(s);
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                DeclarationService.SUBMISSION_ENTITY,
                                41L,
                                SelfServiceEvents.Status.ROUTED,
                                900L,
                                "user"));
        assertThat(regime.getVerifyStatus()).isEqualTo("VERIFIED");
        verify(regimes).save(regime);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("user"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("submission_status");
        assertThat(entry.getValue().oldValue()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().newValue()).isEqualTo("VERIFIED");
        assertThat(entry.getValue().reason()).isEqualTo("verified against the proofs; 2 lines");
        assertThat(entry.getValue().approvalId()).isEqualTo(900L);
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-07");
    }

    @Test
    void SRS_7_24_aFrozenRegimeIsNotMarkedVerifiedAgain() {
        roundForVerification();
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of());
        ItRegimeDetail locked = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        locked.approvedAndFrozen(5L);
        when(regimes.findById(3L)).thenReturn(Optional.of(locked));
        service.verify(context(1L), 41L, List.of());
        assertThat(locked.getVerifyStatus()).isEqualTo("APPROVED");
        verify(regimes, never()).save(locked);
    }

    @Test
    void SRS_7_24_aVerifiedAmountAboveTheAllowedOrNegativeIsRefusedAndNothingIsRouted() {
        roundForVerification();
        ItDeclaration a = line(70L, Kind.INVESTMENT, "50000.00", "25000.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(a));
        refused(
                () ->
                        service.verify(
                                context(1L),
                                41L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                70L, new BigDecimal("25000.01")))),
                TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(1L),
                                41L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                70L, new BigDecimal("-1")))),
                TaxErrorCode.VERIFIED_AMOUNT);
        verify(approvals, never()).submit(any(), any(), any());
        assertThat(a.getProofStatus()).isEqualTo("DECLARED");
    }

    @Test
    void SRS_7_24_aRoundNotAtTheMakerCannotBeVerifiedOrReturned() {
        ItDeclarationSubmission s = roundForVerification();
        ReflectionTestUtils.setField(s, "submissionStatus", Status.VERIFIED.name());
        assertThatThrownBy(() -> service.verify(context(1L), 41L, List.of()))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.DECLARATION_STATE);
                            assertThat(e.getMessageArgs()).containsExactly("VERIFIED");
                        });
        refused(
                () -> service.returnToMember(context(1L), 41L, "reason"),
                TaxErrorCode.DECLARATION_STATE);
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void BR_TAX_014_aReturnAsksI07SubmitInTheRoundsOfficeAndRejectsTheOpenLines() {
        AccessContext maker = context(1L);
        ItDeclarationSubmission s = roundForVerification();
        ItDeclaration open = line(70L, Kind.INVESTMENT, "50000.00", "50000.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(open));
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        when(regimes.findById(3L)).thenReturn(Optional.of(regime));

        service.returnToMember(maker, 41L, "  Proof unreadable ");

        verify(policy).require(maker, "I-07", "SUBMIT");
        verify(policy).requireRecordAt(maker, "I-07", "SUBMIT", 7L);
        assertThat(s.status()).isEqualTo(Status.RETURNED);
        assertThat(s.getReturnReason()).isEqualTo("Proof unreadable");
        assertThat(open.getProofStatus()).isEqualTo("REJECTED");
        assertThat(regime.getVerifyStatus()).isEqualTo("RETURNED");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                DeclarationService.SUBMISSION_ENTITY,
                                41L,
                                SelfServiceEvents.Status.REJECTED,
                                "user"));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("user"), entry.capture());
        assertThat(entry.getValue().oldValue()).isEqualTo("SUBMITTED");
        assertThat(entry.getValue().newValue()).isEqualTo("RETURNED");
        assertThat(entry.getValue().reason()).isEqualTo("Proof unreadable");
        assertThat(entry.getValue().approvalId()).isNull();
    }

    // ------------------------------------------------------------ reset

    @Test
    void FRS_V05_14_3_aResetIsProposedForAFrozenRegimeWithTheReasonAsThePayload() throws Exception {
        AccessContext maker = context(1L);
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "900009", "Asha", 7L, "IN")));
        ItRegimeDetail frozen = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        frozen.approvedAndFrozen(5L);
        ReflectionTestUtils.setField(frozen, "regimeDetailId", 3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(frozen));

        Long approval = service.proposeReset(maker, 9L, YEAR, "  Wrong regime chosen  ");

        assertThat(approval).isEqualTo(900L);
        verify(policy).require(maker, "I-07", "EDIT");
        verify(policy).requireRecordAt(maker, "I-07", "EDIT", 7L);
        ArgumentCaptor<Submission> submission = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(maker), submission.capture(), eq(null));
        Submission sent = submission.getValue();
        assertThat(sent.workflowAction()).isEqualTo("I07_RESET");
        assertThat(sent.moduleCode()).isEqualTo("I-07");
        assertThat(sent.actionCode()).isEqualTo("EDIT");
        assertThat(sent.entityName()).isEqualTo(DeclarationService.REGIME_ENTITY);
        assertThat(sent.entityKey()).isEqualTo("3");
        assertThat(sent.actionType()).isEqualTo(ActionType.UPDATE);
        assertThat(sent.locationId()).isEqualTo(7L);
        assertThat(sent.effectiveDate()).isEqualTo(TODAY);
        assertThat(sent.reason()).isEqualTo("Wrong regime chosen");
        DeclarationService.ResetProposal payload =
                json.readValue(sent.payload(), DeclarationService.ResetProposal.class);
        assertThat(payload)
                .isEqualTo(new DeclarationService.ResetProposal(3L, YEAR, "Wrong regime chosen"));
    }

    // -------------------------------------------------------- decisions

    private ApprovalDecided decided(
            String entity, String key, ApprovalStatus outcome, String reason, String payload) {
        return new ApprovalDecided(
                900L, "I-07", entity, key, outcome, reason, payload, "checker", NOW, null);
    }

    @Test
    void BR_TAX_001_anApprovedRoundSupersedesTheEarlierApprovedOneFreezesItsLinesAndTheRegime() {
        ItDeclarationSubmission mine = round(41L, 2, Status.VERIFIED);
        ItDeclarationSubmission earlier = round(39L, 1, Status.APPROVED);
        ItDeclarationSubmission stale = round(38L, 0, Status.RETURNED);
        when(submissions.lockById(41L)).thenReturn(Optional.of(mine));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(mine, earlier, stale));
        ItDeclaration rent = line(61L, Kind.HRA_RENT, "120000.00", "120000.00");
        ItDeclaration invest = line(62L, Kind.INVESTMENT, "1000.00", "1000.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(rent, invest));
        ItRegimeDetail regime = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        when(regimes.findById(3L)).thenReturn(Optional.of(regime));

        service.on(
                decided(
                        DeclarationService.SUBMISSION_ENTITY,
                        "41",
                        ApprovalStatus.APPROVED,
                        null,
                        null));

        assertThat(earlier.status()).isEqualTo(Status.SUPERSEDED);
        verify(submissions).save(earlier);
        assertThat(stale.status())
                .as("only an approved round is superseded")
                .isEqualTo(Status.RETURNED);
        verify(submissions, never()).save(stale);
        assertThat(mine.status()).isEqualTo(Status.APPROVED);
        verify(submissions).save(mine);
        assertThat(rent.getProofStatus()).isEqualTo("APPROVED");
        assertThat(rent.isFrozen()).isTrue();
        assertThat(invest.getProofStatus()).isEqualTo("APPROVED");
        assertThat(invest.isFrozen()).isFalse();
        verify(lines).save(rent);
        verify(lines).save(invest);
        assertThat(regime.isLocked()).isTrue();
        assertThat(regime.getVerifyStatus()).isEqualTo("APPROVED");
        verify(regimes).save(regime);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().oldValue()).isEqualTo("VERIFIED");
        assertThat(entry.getValue().newValue()).isEqualTo("APPROVED");
        assertThat(entry.getValue().reason()).isEqualTo("Approved; regime frozen");
        assertThat(entry.getValue().approvalId()).isEqualTo(900L);
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-07");
    }

    @Test
    void BR_TAX_001_theCheckersOwnWordsAreKeptInTheAudit() {
        ItDeclarationSubmission mine = round(41L, 1, Status.VERIFIED);
        when(submissions.lockById(41L)).thenReturn(Optional.of(mine));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(mine));
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of());
        when(regimes.findById(3L)).thenReturn(Optional.empty());
        service.on(
                decided(
                        DeclarationService.SUBMISSION_ENTITY,
                        "41",
                        ApprovalStatus.APPROVED,
                        "All proofs seen",
                        null));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("All proofs seen");
        verify(regimes, never()).save(any());
    }

    @Test
    void FRS_V05_14_3_anApprovedResetOpensTheRegimeAgainAndIsAuditedWithItsApproval()
            throws Exception {
        ItRegimeDetail frozen = ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW);
        frozen.approvedAndFrozen(5L);
        ReflectionTestUtils.setField(frozen, "regimeDetailId", 3L);
        when(regimes.findById(3L)).thenReturn(Optional.of(frozen));
        String payload =
                json.writeValueAsString(
                        new DeclarationService.ResetProposal(3L, YEAR, "Wrong regime"));

        service.on(
                decided(
                        DeclarationService.REGIME_ENTITY,
                        "3",
                        ApprovalStatus.APPROVED,
                        null,
                        payload));

        assertThat(frozen.isLocked()).isFalse();
        assertThat(frozen.getResetReason()).isEqualTo("Wrong regime");
        verify(regimes).save(frozen);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().entityName()).isEqualTo(DeclarationService.REGIME_ENTITY);
        assertThat(entry.getValue().entityKey()).isEqualTo("3");
        assertThat(entry.getValue().fieldName()).isEqualTo("locked_flag");
        assertThat(entry.getValue().oldValue()).isEqualTo("Y");
        assertThat(entry.getValue().newValue()).isEqualTo("N");
        assertThat(entry.getValue().reason()).isEqualTo("Wrong regime");
        assertThat(entry.getValue().approvalId()).isEqualTo(900L);
    }

    @Test
    void BR_TAX_014_aRejectedRoundIsReturnedWithTheCheckersReasonAndTheApprovalOnTheAudit() {
        ItDeclarationSubmission mine = round(41L, 1, Status.VERIFIED);
        when(submissions.lockById(41L)).thenReturn(Optional.of(mine));
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of());
        when(regimes.findById(3L)).thenReturn(Optional.empty());
        service.on(
                decided(
                        DeclarationService.SUBMISSION_ENTITY,
                        "41",
                        ApprovalStatus.REJECTED,
                        "Rent receipts missing",
                        null));
        assertThat(mine.status()).isEqualTo(Status.RETURNED);
        assertThat(mine.getReturnReason()).isEqualTo("Rent receipts missing");
        verify(events)
                .publishEvent(
                        new SelfServiceEvents.StatusChanged(
                                DeclarationService.SUBMISSION_ENTITY,
                                41L,
                                SelfServiceEvents.Status.REJECTED,
                                "checker"));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().approvalId()).isEqualTo(900L);
        assertThat(entry.getValue().oldValue()).isEqualTo("VERIFIED");
    }

    // --------------------------------------------- proof deadline / proofs

    @Test
    void SRS_7_14_linesWithoutProofAfterTheDeadlineAreReversedAndAuditedOncePerRound() {
        windowOpen(null);
        when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                .thenReturn(Optional.of(version(TODAY.minusDays(1))));
        when(rules.condition(eq(DeclarationService.PROOF_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(true));
        ItDeclarationSubmission approved = round(41L, 1, Status.APPROVED);
        when(submissions.findByFinYearAndSubmissionStatus(YEAR, "APPROVED"))
                .thenReturn(List.of(approved));
        ItDeclaration unproved = line(70L, Kind.INVESTMENT, "10.00", "10.00");
        unproved.approved(5L);
        ItDeclaration proved =
                ItDeclaration.declared(
                        9L,
                        41L,
                        YEAR,
                        Kind.INVESTMENT,
                        "80D",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        88L,
                        NOW);
        proved.approved(5L);
        ItDeclaration notApproved = line(72L, Kind.INVESTMENT, "10.00", "10.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L))
                .thenReturn(List.of(unproved, proved, notApproved));

        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isEqualTo(1);

        assertThat(unproved.getProofStatus()).isEqualTo("REVERSED");
        assertThat(proved.getProofStatus()).isEqualTo("APPROVED");
        assertThat(notApproved.getProofStatus()).isEqualTo("DECLARED");
        verify(lines).save(unproved);
        assertThat(approved.isProofDeadlineReversed()).isTrue();
        verify(submissions).save(approved);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("batch"), entry.capture());
        assertThat(entry.getValue().fieldName()).isEqualTo("proof_deadline_reversed");
        assertThat(entry.getValue().oldValue()).isEqualTo("N");
        assertThat(entry.getValue().newValue()).isEqualTo("Y");
        assertThat(entry.getValue().reason())
                .isEqualTo("1 lines without proof after " + TODAY.minusDays(1));
    }

    @Test
    void SRS_7_14_aLineIsNotReversedOnTheDeadlineDayItself() {
        windowOpen(null);
        when(rules.resolve(eq(DeclarationService.PROOF_KEY), any(), any()))
                .thenReturn(Optional.of(version(TODAY)));
        when(rules.condition(eq(DeclarationService.PROOF_KEY), any(), any(), anyMap()))
                .thenReturn(Optional.of(true));
        ItDeclarationSubmission approved = round(41L, 1, Status.APPROVED);
        when(submissions.findByFinYearAndSubmissionStatus(YEAR, "APPROVED"))
                .thenReturn(List.of(approved));
        ItDeclaration unproved = line(70L, Kind.INVESTMENT, "10.00", "10.00");
        unproved.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(unproved));
        assertThat(service.reverseUnprovedLines(YEAR, "batch")).isZero();
        assertThat(unproved.getProofStatus()).isEqualTo("APPROVED");
        verify(audit, never()).record(any(), any());
    }

    @Test
    void SRS_7_24_aMembersOwnProofAsksE01ViewAndIsReadFromTheRoundThatHoldsIt() {
        AccessContext member = context(9L);
        ItDeclarationSubmission mine = round(41L, 2, Status.APPROVED);
        ItDeclarationSubmission first = round(39L, 1, Status.SUPERSEDED);
        when(submissions.findById(41L)).thenReturn(Optional.of(mine));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(mine, first));
        ItDeclaration withProof =
                ItDeclaration.declared(
                        9L,
                        41L,
                        YEAR,
                        Kind.HRA_RENT,
                        "80GG",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        88L,
                        NOW);
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(withProof));
        DocumentStore.AttachmentView held = attachment(88L);
        DocumentStore.AttachmentView other = attachment(5L);
        when(store.active(DeclarationService.SUBMISSION_ENTITY, "41")).thenReturn(List.of(other));
        when(store.active(DeclarationService.SUBMISSION_ENTITY, "39")).thenReturn(List.of(held));
        DocumentStore.Content content = new DocumentStore.Content(held, new byte[] {7});
        when(store.read("user", "10.0.0.9", DeclarationService.SUBMISSION_ENTITY, "39", 88L))
                .thenReturn(content);

        assertThat(service.proof(member, "10.0.0.9", 41L, 88L)).isSameAs(content);
        verify(policy).require(member, "E-01", "SELF");
        verify(policy, never()).can(any(), any(), any());

        refused(() -> service.proof(member, "10.0.0.9", 41L, 99L), CommonErrorCode.NOT_FOUND);
        when(store.active(DeclarationService.SUBMISSION_ENTITY, "39")).thenReturn(List.of(other));
        refused(() -> service.proof(member, "10.0.0.9", 41L, 88L), CommonErrorCode.NOT_FOUND);
    }

    // ================================================== pass 2

    @Test
    void SRS_7_24_anUnfrozenElectionIsChangedInPlaceAndTheSameRowIsSaved() {
        windowOpen(null);
        ItRegimeDetail existing =
                ItRegimeDetail.elected(9L, YEAR, Regime.OLD, NOW.minusSeconds(900));
        ReflectionTestUtils.setField(existing, "regimeDetailId", 3L);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.of(existing));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of());
        when(sections.inForceOn(TODAY)).thenReturn(List.of());

        service.submit(context(9L), Regime.NEW, List.of());

        ArgumentCaptor<ItRegimeDetail> saved = ArgumentCaptor.forClass(ItRegimeDetail.class);
        verify(regimes).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(existing.getRegime()).isEqualTo(Regime.NEW);
        ArgumentCaptor<ItDeclarationSubmission> round =
                ArgumentCaptor.forClass(ItDeclarationSubmission.class);
        verify(submissions).save(round.capture());
        assertThat(round.getValue().getRegimeDetailId()).isEqualTo(3L);
    }

    @Test
    void SRS_7_15_aLineOfAnotherKindIsAcceptedWhileTheApprovedRentIsFrozenAndTheRentIsCarried() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        ItDeclarationSubmission approved = round(39L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(approved));
        ItDeclaration rent = line(61L, Kind.HRA_RENT, "120000.00", "120000.00");
        rent.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(39L)).thenReturn(List.of(rent));
        sectionsInForce(section("80C", "150000.00", "BOTH"));

        DeclarationService.SubmissionView view =
                service.submit(
                        context(9L),
                        Regime.OLD,
                        List.of(
                                new DeclarationService.NewLine(
                                        Kind.INVESTMENT, "80C", new BigDecimal("1000"), null)));

        assertThat(view.number()).isEqualTo(2);
        // the 80C line and the carried rent
        verify(lines, times(2)).save(any());
    }

    @Test
    void SRS_7_15_onlyTheApprovedRoundsRentIsFrozenNotTheOneOfALaterOpenRound() {
        windowOpen(null);
        when(regimes.lockFor(9L, YEAR)).thenReturn(Optional.empty());
        ItDeclarationSubmission returned = round(40L, 2, Status.RETURNED);
        ItDeclarationSubmission approved = round(39L, 1, Status.APPROVED);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(returned, approved));
        // the later (returned) round holds a frozen rent line; the approved one holds none
        ItDeclaration rent = line(61L, Kind.HRA_RENT, "120000.00", "120000.00");
        rent.approved(5L);
        when(lines.findBySubmissionIdOrderByDeclarationId(40L)).thenReturn(List.of(rent));
        when(lines.findBySubmissionIdOrderByDeclarationId(39L)).thenReturn(List.of());
        sectionsInForce(section("80GG", "60000.00", "OLD"));

        DeclarationService.SubmissionView view =
                service.submit(
                        context(9L),
                        Regime.OLD,
                        List.of(
                                new DeclarationService.NewLine(
                                        Kind.HRA_RENT, "80GG", new BigDecimal("1000"), null)));

        assertThat(view.number()).isEqualTo(3);
        assertThat(returned.status()).isEqualTo(Status.SUPERSEDED);
    }

    @Test
    void SRS_7_24_aVerificationNamingAnotherLineThanTheOpenOnesIsRefusedBeforeAnyLineIsRecorded() {
        roundForVerification();
        ItDeclaration a = line(70L, Kind.INVESTMENT, "50000.00", "50000.00");
        ItDeclaration b = line(71L, Kind.INVESTMENT, "30000.00", "25000.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(a, b));

        refused(
                () ->
                        service.verify(
                                context(1L),
                                41L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                70L, new BigDecimal("40000.00")),
                                        new DeclarationService.LineVerification(
                                                99L, new BigDecimal("1.00")))),
                TaxErrorCode.VERIFIED_AMOUNT);

        verify(lines, never()).save(any());
        verify(approvals, never()).submit(any(), any(), any());
        assertThat(a.getProofStatus()).isEqualTo("DECLARED");
    }

    @Test
    void SRS_7_24_aVerificationGivingALineTwiceOrNoLineIdIsRefused() {
        roundForVerification();
        ItDeclaration a = line(70L, Kind.INVESTMENT, "50000.00", "50000.00");
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(a));
        DeclarationService.LineVerification once =
                new DeclarationService.LineVerification(70L, new BigDecimal("1.00"));

        refused(
                () -> service.verify(context(1L), 41L, List.of(once, once)),
                TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(1L),
                                41L,
                                List.of(
                                        new DeclarationService.LineVerification(
                                                null, new BigDecimal("1.00")))),
                TaxErrorCode.VERIFIED_AMOUNT);
        refused(
                () ->
                        service.verify(
                                context(1L),
                                41L,
                                java.util.Arrays.asList(
                                        (DeclarationService.LineVerification) null)),
                TaxErrorCode.VERIFIED_AMOUNT);
        verify(lines, never()).save(any());
    }

    @Test
    void SRS_7_24_aVerificationReturnsTheRoundAsVerified() {
        roundForVerification();
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of());

        DeclarationService.SubmissionView view = service.verify(context(1L), 41L, List.of());

        assertThat(view).isNotNull();
        assertThat(view.id()).isEqualTo(41L);
        assertThat(view.status()).isEqualTo("VERIFIED");
    }

    @Test
    void SRS_7_24_aRoundWhoseMemberIsNotOnRecordIsNotFoundForTheMaker() {
        ItDeclarationSubmission s = round(41L, 1, Status.SUBMITTED);
        when(submissions.lockById(41L)).thenReturn(Optional.of(s));
        when(employees.member(9L)).thenReturn(Optional.empty());

        refused(() -> service.verify(context(1L), 41L, List.of()), CommonErrorCode.NOT_FOUND);

        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_7_24_aProofOfAnotherLineIsNotFoundEvenWhenTheRoundHoldsAnAttachmentOfThatId() {
        AccessContext member = context(9L);
        ItDeclarationSubmission mine = round(41L, 1, Status.SUBMITTED);
        when(submissions.findById(41L)).thenReturn(Optional.of(mine));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(mine));
        ItDeclaration withProof =
                ItDeclaration.declared(
                        9L,
                        41L,
                        YEAR,
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        88L,
                        NOW);
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(withProof));
        // the store holds attachment 99 in the round, but no line of the round cites it
        DocumentStore.AttachmentView stray = attachment(99L);
        when(store.active(DeclarationService.SUBMISSION_ENTITY, "41")).thenReturn(List.of(stray));

        refused(() -> service.proof(member, "10.0.0.9", 41L, 99L), CommonErrorCode.NOT_FOUND);

        verify(store, never()).read(any(), any(), any(), any(), any());
    }

    @Test
    void SRS_7_24_aStaffReaderInScopeReadsAMembersProofAndOneOutsideTheViewPermissionFindsNone() {
        AccessContext maker = context(1L);
        ItDeclarationSubmission theirs = round(41L, 1, Status.SUBMITTED);
        when(submissions.findById(41L)).thenReturn(Optional.of(theirs));
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(theirs));
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(new EmployeeDirectory.Member(9L, "900009", "Asha", 7L, "IN")));
        ItDeclaration withProof =
                ItDeclaration.declared(
                        9L,
                        41L,
                        YEAR,
                        Kind.INVESTMENT,
                        "80C",
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        88L,
                        NOW);
        when(lines.findBySubmissionIdOrderByDeclarationId(41L)).thenReturn(List.of(withProof));
        DocumentStore.AttachmentView held = attachment(88L);
        when(store.active(DeclarationService.SUBMISSION_ENTITY, "41")).thenReturn(List.of(held));
        DocumentStore.Content content = new DocumentStore.Content(held, new byte[] {7});
        when(store.read("user", "10.0.0.1", DeclarationService.SUBMISSION_ENTITY, "41", 88L))
                .thenReturn(content);

        // no I-07 view permission: the round does not exist for the caller
        refused(() -> service.proof(maker, "10.0.0.1", 41L, 88L), CommonErrorCode.NOT_FOUND);
        verify(store, never()).read(any(), any(), any(), any(), any());

        when(policy.can(maker, "I-07", "VIEW")).thenReturn(true);
        assertThat(service.proof(maker, "10.0.0.1", 41L, 88L)).isSameAs(content);
        verify(policy).requireRecordAt(maker, "I-07", "VIEW", 7L);
        verify(policy, never()).require(maker, "E-01", "SELF");

        // the member behind the round is not on record
        when(employees.member(9L)).thenReturn(Optional.empty());
        refused(() -> service.proof(maker, "10.0.0.1", 41L, 88L), CommonErrorCode.NOT_FOUND);
    }
}
