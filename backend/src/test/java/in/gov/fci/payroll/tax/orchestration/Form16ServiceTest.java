package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.PostingLedger;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.Form16Certificate;
import in.gov.fci.payroll.tax.business.model.Form16Certificate.Status;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-10 beyond the integration path (SRS-7-9, SRS-7-19, SRS-7-21; BR-TAX-010 … 012): an empty scope,
 * a status outside the vocabulary, the member without a session employee, a year not final, a Part
 * A refused, decisions of other screens or rejected, every refusal of a reissue and its success,
 * the Part A read by its member and by the desk, and the tolerance of the version.
 */
class Form16ServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final String YEAR = "2025-26";

    private final TaxRepositories.Form16s certificates = mock(TaxRepositories.Form16s.class);
    private final TaxRepositories.Returns returns = mock(TaxRepositories.Returns.class);
    private final TaxRepositories.Challans challans = mock(TaxRepositories.Challans.class);
    private final TaxComputationService computation = mock(TaxComputationService.class);
    private final OutputDefinitionService outputs = mock(OutputDefinitionService.class);
    private final PostingLedger ledger = mock(PostingLedger.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final Form16Service service =
            new Form16Service(
                    certificates,
                    returns,
                    challans,
                    computation,
                    outputs,
                    ledger,
                    employees,
                    org,
                    payroll,
                    store,
                    approvals,
                    statuses,
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(BatchScope.class));

    private final EmployeeDirectory.Member member =
            new EmployeeDirectory.Member(9L, "870001", "M", 1L, "IN_SERVICE");
    private final OrgLocation unit = mock(OrgLocation.class);
    private final OutputVersion version = mock(OutputVersion.class);

    Form16ServiceTest() {
        when(unit.getId()).thenReturn(50L);
        when(unit.getCode()).thenReturn("DH001");
        when(org.cycleUnitOf(1L)).thenReturn(Optional.of(unit));
        when(employees.member(9L)).thenReturn(Optional.of(member));
        when(employees.idsAt(any())).thenReturn(Set.of(9L));
        when(employees.members(any())).thenReturn(Map.of(9L, member));
        when(employees.panStatus(9L)).thenReturn(Optional.of(PanStatus.VALID));
        when(version.getReportVersionId()).thenReturn(7L);
        when(outputs.requireGoverning(anyString(), any()))
                .thenReturn(new OutputDefinitionService.Governing(version, List.of(), List.of()));
        when(statuses.pending(any())).thenReturn(List.of());
        when(computation.hasYear(any(), anyString())).thenReturn(true);
        when(payroll.cycle(eq(50L), anyString()))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 50L, "202504", "LOCKED")));
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

    private static Form16Certificate certificate(Status status) {
        Form16Certificate f =
                Form16Certificate.generated(
                        9L, YEAR, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        if (status == Status.MERGED || status == Status.RELEASED) {
            f.partAMerged(31L, NOW);
        }
        if (status == Status.RELEASED) {
            f.released(NOW);
        }
        return f;
    }

    private static TaxComputationService.Forecast forecast(
            String view, boolean result, List<String> reasons, BigDecimal difference) {
        TaxableEarnings.Year y =
                new TaxableEarnings.Year(
                        List.of(
                                new TaxableEarnings.Month(
                                        "202504",
                                        true,
                                        new BigDecimal("100"),
                                        new BigDecimal("10"),
                                        BigDecimal.ZERO,
                                        List.of())));
        return new TaxComputationService.Forecast(
                9L,
                "870001",
                "M",
                "IN_SERVICE",
                YEAR,
                view,
                new TaxComputationService.Inputs(
                        Regime.OLD, PanStatus.VALID, List.of(), BigDecimal.ZERO, List.of()),
                Optional.of(y),
                result
                        ? Optional.of(
                                new TaxCalculator.Result(
                                        new BigDecimal("100"),
                                        BigDecimal.TEN,
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO,
                                        BigDecimal.TEN,
                                        false,
                                        BigDecimal.ZERO))
                        : Optional.empty(),
                Optional.ofNullable(difference),
                reasons);
    }

    private static ApprovalDecided decided(
            String module, String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                90L,
                module,
                entity,
                "50|" + YEAR,
                outcome,
                reason,
                "{\"orgUnitId\":50,\"finYear\":\""
                        + YEAR
                        + "\",\"certificateIds\":[3],\"proposedBy\":\"maker\"}",
                "checker",
                NOW);
    }

    @Test
    void SRS_7_9_theRegisterIsEmptyWithoutScopeAndRefusesAnUnknownStatus() {
        assertThat(
                        service.register(context(null, Set.of()), YEAR, null, new PageSpec(0, 20))
                                .items())
                .isEmpty();
        verifyNoInteractions(certificates);
        assertThatThrownBy(
                        () ->
                                service.register(
                                        context(null, Set.of(1L)),
                                        YEAR,
                                        "SENT",
                                        new PageSpec(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void SRS_7_9_theMembersCertificatesNeedTheMembersSession() {
        refused(() -> service.mine(context(null, Set.of())), CommonErrorCode.NOT_FOUND);
        when(employees.member(77L)).thenReturn(Optional.empty());
        when(certificates.findByEmployeeIdAndReleaseStatusOrderByFinYearDesc(77L, "RELEASED"))
                .thenReturn(List.of(certificate(Status.RELEASED)));
        assertThat(service.mine(context(77L, Set.of())))
                .singleElement()
                .satisfies(v -> assertThat(v.eis()).isNull());
    }

    @Test
    void SRS_7_19_aYearNotFinalIsSkippedWithItsReasonAndTheChosenMembersMustBeInTheUnit() {
        when(computation.forecast(eq(member), eq(YEAR), any(), any()))
                .thenReturn(forecast("PROJECTED", false, List.of(), null));
        Form16Service.Generation g = service.generate(context(null, Set.of(1L)), YEAR);
        assertThat(g.generated()).isZero();
        assertThat(g.skipped())
                .singleElement()
                .satisfies(s -> assertThat(s.reason()).isEqualTo("the year is not final"));
        when(computation.forecast(eq(member), eq(YEAR), any(), any()))
                .thenReturn(forecast("PROJECTED", false, List.of("TAX-422-REGIME-REQUIRED"), null));
        assertThat(service.generate(context(null, Set.of(1L)), YEAR, Set.of("870001")).skipped())
                .singleElement()
                .satisfies(s -> assertThat(s.reason()).isEqualTo("TAX-422-REGIME-REQUIRED"));
        refused(
                () -> service.generate(context(null, Set.of(1L)), YEAR, Set.of("870001", "999999")),
                CommonErrorCode.NOT_FOUND);
        // An empty population is the whole unit.
        assertThat(service.generate(context(null, Set.of(1L)), YEAR, Set.of()).skipped())
                .hasSize(1);
    }

    @Test
    void SRS_7_19_aMismatchedYearIsRaisedAndNotCertified() {
        when(computation.forecast(eq(member), eq(YEAR), any(), any()))
                .thenReturn(forecast("ACTUAL", true, List.of(), new BigDecimal("12.40")));
        Form16Service.Generation g = service.generate(context(null, Set.of(1L)), YEAR);
        assertThat(g.generated()).isZero();
        assertThat(g.skipped().get(0).reason()).contains("Rs 12").contains("SRS-7-19");
        verify(computation)
                .raiseYearEndMismatch(eq("maker"), eq(member), eq(YEAR), any(), eq("C-10"));
        // Under half a rupee rounds to nothing (R-02): certified.
        when(computation.forecast(eq(member), eq(YEAR), any(), any()))
                .thenReturn(forecast("ACTUAL", true, List.of(), new BigDecimal("0.40")));
        when(certificates.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.generate(context(null, Set.of(1L)), YEAR).generated()).isEqualTo(1);
    }

    @Test
    void SRS_7_9_partAIsRefusedForAReleasedCertificateOrWithoutAFile() {
        when(certificates.lockById(3L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        refused(
                () -> service.uploadPartA(context(null, Set.of(1L)), 3L, null),
                TaxErrorCode.DECLARATION_STATE);
        when(certificates.lockById(4L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        refused(
                () -> service.uploadPartA(context(null, Set.of(1L)), 4L, null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.uploadPartA(
                                context(null, Set.of(1L)),
                                4L,
                                new Form16Service.PartA(
                                        "CERTIFICATE", null, null, "ITD", "a.pdf", null)),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.uploadPartA(context(null, Set.of(1L)), 99L, null),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void BR_TAX_012_onlyAnApprovedReleaseOfC10ReleasesWithItsReason() {
        service.on(decided("C-11", "form16", ApprovalStatus.APPROVED, null));
        service.on(decided("C-10", "statutory_return", ApprovalStatus.APPROVED, null));
        service.on(decided("C-10", "form16", ApprovalStatus.REJECTED, "no"));
        verifyNoInteractions(certificates);
        Form16Certificate merged = certificate(Status.MERGED);
        when(certificates.lockById(3L)).thenReturn(Optional.of(merged));
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.of(BigDecimal.ONE));
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR)).thenReturn(List.of());
        // The challans do not cover the TDS, but the version's release gate allows a rupee.
        when(outputs.controlsOf(7L))
                .thenReturn(
                        List.of(
                                OutputControlTotal.of(7L, "TDS", "CHALLAN", BigDecimal.ONE, true),
                                OutputControlTotal.of(
                                        7L, "Info", "CHALLAN", BigDecimal.ZERO, false)));
        service.on(decided("C-10", "form16", ApprovalStatus.APPROVED, null));
        assertThat(merged.status()).isEqualTo(Status.RELEASED);
        verify(audit).record(eq("checker"), any());
    }

    // SC-08-0051: an issued certificate is never changed; it is reissued against a correction.
    @Test
    void SRS_7_9_aReissueNeedsAReasonAReleasedCertificateAndACorrectionReturn() {
        AccessContext ctx = context(null, Set.of(1L));
        refused(() -> service.reissue(ctx, 3L, " "), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> service.reissue(ctx, 3L, null), CommonErrorCode.VALIDATION_FAILED);
        when(certificates.lockById(3L)).thenReturn(Optional.of(certificate(Status.MERGED)));
        refused(() -> service.reissue(ctx, 3L, "IT"), TaxErrorCode.DECLARATION_STATE);
        Form16Certificate released = certificate(Status.RELEASED);
        when(certificates.lockById(4L)).thenReturn(Optional.of(released));
        StatutoryReturn original =
                StatutoryReturn.generated("FORM_24Q", YEAR, "Q4", 50L, 7L, null, NOW);
        original.released("checker", NOW);
        StatutoryReturn draftCorrection =
                StatutoryReturn.generated("FORM_24Q_CORRECTION", YEAR, "Q4", 50L, 7L, 1L, NOW);
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(original, draftCorrection));
        refused(() -> service.reissue(ctx, 4L, "IT"), TaxErrorCode.DECLARATION_STATE);

        StatutoryReturn filedCorrection =
                StatutoryReturn.generated("FORM_24Q_CORRECTION", YEAR, "Q4", 50L, 7L, 1L, NOW);
        filedCorrection.released("checker", NOW);
        filedCorrection.filed();
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(original, filedCorrection));
        when(computation.forecast(eq(member), eq(YEAR), any()))
                .thenReturn(forecast("ACTUAL", false, List.of(), null));
        refused(() -> service.reissue(ctx, 4L, "IT"), TaxErrorCode.EARNINGS_NOT_AVAILABLE);
        when(computation.forecast(eq(member), eq(YEAR), any()))
                .thenReturn(forecast("ACTUAL", true, List.of(), BigDecimal.ZERO));
        Form16Service.CertificateView v = service.reissue(ctx, 4L, " Correction filed ");
        assertThat(v.status()).isEqualTo("MERGED");
        assertThat(v.taxDeducted()).isEqualByComparingTo("10");
        verify(audit).recordAll(eq("maker"), any());
        // A released correction counts as well.
        StatutoryReturn releasedCorrection =
                StatutoryReturn.generated("FORM_24Q_CORRECTION", YEAR, "Q4", 50L, 7L, 1L, NOW);
        releasedCorrection.released("checker", NOW);
        released.released(NOW);
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(releasedCorrection));
        assertThat(service.reissue(ctx, 4L, "again").status()).isEqualTo("MERGED");
    }

    @Test
    void SRS_7_9_partAIsReadByItsMemberOnceReleasedOrByTheDeskInScope() {
        Form16Certificate merged = certificate(Status.MERGED);
        when(certificates.findById(3L)).thenReturn(Optional.of(merged));
        // The member: not before release.
        refused(() -> service.partA(context(9L, Set.of()), "ip", 3L), CommonErrorCode.NOT_FOUND);
        Form16Certificate released = certificate(Status.RELEASED);
        when(certificates.findById(4L)).thenReturn(Optional.of(released));
        service.partA(context(9L, Set.of()), "ip", 4L);
        verify(store).read("maker", "ip", "form16", "null", 31L);
        // The desk, in scope: also before release; nothing without a Part A.
        service.partA(context(null, Set.of(1L)), "ip", 3L);
        verify(policy).requireRecordAt(any(), eq("C-10"), eq("VIEW"), eq(1L));
        when(certificates.findById(5L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        refused(
                () -> service.partA(context(null, Set.of(1L)), "ip", 5L),
                CommonErrorCode.NOT_FOUND);
        refused(
                () -> service.partA(context(null, Set.of(1L)), "ip", 6L),
                CommonErrorCode.NOT_FOUND);
        // A certificate of a member no longer in the directory.
        Form16Certificate orphan =
                Form16Certificate.generated(
                        70L, YEAR, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        when(certificates.findById(7L)).thenReturn(Optional.of(orphan));
        refused(
                () -> service.partA(context(null, Set.of(1L)), "ip", 7L),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void OD_11_anOfficeWithoutAPayingUnitHasNoCertificates() {
        when(org.cycleUnitOf(2L)).thenReturn(Optional.empty());
        AccessContext elsewhere =
                new AccessContext(
                        2L,
                        "maker",
                        "Maker",
                        "s",
                        "P",
                        2L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(2L));
        refused(() -> service.generate(elsewhere, YEAR), CommonErrorCode.NOT_FOUND);
        refused(() -> service.requestRelease(elsewhere, YEAR), CommonErrorCode.NOT_FOUND);
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS-7-19: a final year whose tax could not be computed is skipped with the reasons. */
    @Test
    void SRS_7_19_aFinalYearWithoutAComputationIsSkippedWithItsReasons() {
        when(computation.forecast(eq(member), eq(YEAR), any(), any()))
                .thenReturn(
                        forecast(
                                "ACTUAL",
                                false,
                                List.of("TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD"),
                                null));
        Form16Service.Generation g = service.generate(context(null, Set.of(1L)), YEAR);
        assertThat(g.generated()).isZero();
        assertThat(g.skipped())
                .singleElement()
                .satisfies(
                        s ->
                                assertThat(s.reason())
                                        .isEqualTo("TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD"));
    }

    /** SRS-7-9: a merged certificate takes a new Part A (a replacement), checked like the first. */
    @Test
    void SRS_7_9_aMergedCertificateTakesAReplacementPartA() {
        when(certificates.lockById(5L)).thenReturn(Optional.of(certificate(Status.MERGED)));
        refused(
                () -> service.uploadPartA(context(null, Set.of(1L)), 5L, null),
                CommonErrorCode.VALIDATION_FAILED);
        verify(store, never()).attach(anyString(), any());
    }

    /**
     * BR-TAX-012: an approved release releases only the certificates still merged; one already
     * released in the proposal is left as it is, and the Checker's own reason is audited.
     */
    @Test
    void BR_TAX_012_anApprovedReleaseReleasesOnlyTheMergedCertificatesOfTheProposal() {
        Form16Certificate merged = certificate(Status.MERGED);
        Form16Certificate released = certificate(Status.RELEASED);
        when(certificates.lockById(3L)).thenReturn(Optional.of(merged));
        when(certificates.lockById(4L)).thenReturn(Optional.of(released));
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.of(BigDecimal.ONE));
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR)).thenReturn(List.of());
        when(outputs.controlsOf(7L))
                .thenReturn(
                        List.of(OutputControlTotal.of(7L, "TDS", "CHALLAN", BigDecimal.ONE, true)));
        service.on(
                new ApprovalDecided(
                        91L,
                        "C-10",
                        "form16",
                        "50|" + YEAR,
                        ApprovalStatus.APPROVED,
                        "Reconciled with TRACES",
                        "{\"orgUnitId\":50,\"finYear\":\""
                                + YEAR
                                + "\",\"certificateIds\":[3,4],\"proposedBy\":\"maker\"}",
                        "checker",
                        NOW));
        assertThat(merged.status()).isEqualTo(Status.RELEASED);
        verify(certificates).save(merged);
        verify(certificates, never()).save(released);
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("Reconciled with TRACES");
        assertThat(entry.getValue().newValue()).isEqualTo("RELEASED (1)");
    }

    /** SRS-7-9: a reissue needs the year's earnings as well as its computation. */
    @Test
    void SRS_7_9_aReissueWithoutTheYearsEarningsIsRefused() {
        AccessContext ctx = context(null, Set.of(1L));
        when(certificates.lockById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        StatutoryReturn filedCorrection =
                StatutoryReturn.generated("FORM_24Q_CORRECTION", YEAR, "Q4", 50L, 7L, 1L, NOW);
        filedCorrection.released("checker", NOW);
        filedCorrection.filed();
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(filedCorrection));
        TaxComputationService.Forecast computedWithoutEarnings =
                new TaxComputationService.Forecast(
                        9L,
                        "870001",
                        "M",
                        "IN_SERVICE",
                        YEAR,
                        "ACTUAL",
                        new TaxComputationService.Inputs(
                                Regime.OLD, PanStatus.VALID, List.of(), BigDecimal.ZERO, List.of()),
                        Optional.empty(),
                        forecast("ACTUAL", true, List.of(), null).result(),
                        Optional.empty(),
                        List.of());
        when(computation.forecast(eq(member), eq(YEAR), any())).thenReturn(computedWithoutEarnings);
        refused(() -> service.reissue(ctx, 4L, "IT"), TaxErrorCode.EARNINGS_NOT_AVAILABLE);
        verify(certificates, never()).save(any());
    }

    /**
     * SRS-7-9 (C-10): every defined status is a valid filter and reaches the query as its name; the
     * status text is checked case-sensitively, so a lower-case name is refused before any read.
     */
    @Test
    void SRS_7_9_everyDefinedStatusIsAValidFilterAndALowerCaseOneIsRefusedBeforeTheRead() {
        Form16Certificate released = certificate(Status.RELEASED);
        for (Status status : Status.values()) {
            when(certificates.inScope(eq(YEAR), eq(Set.of(1L)), eq(status.name()), any()))
                    .thenReturn(new PageImpl<>(List.of(released)));

            var page =
                    service.register(
                            context(null, Set.of(1L)), YEAR, status.name(), new PageSpec(0, 20));

            assertThat(page.items()).hasSize(1);
            assertThat(page.items().get(0).status()).isEqualTo("RELEASED");
            assertThat(page.items().get(0).eis()).isEqualTo("870001");
            assertThat(page.items().get(0).partAStatus()).isEqualTo("RECEIVED");
        }
        assertThatThrownBy(
                        () ->
                                service.register(
                                        context(null, Set.of(1L)),
                                        YEAR,
                                        "released",
                                        new PageSpec(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.register(
                                        context(null, Set.of(1L)), YEAR, "", new PageSpec(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- Part B PDF (OD-144)

    private void computable() {
        when(computation.forecast(any(), eq(YEAR), any(), any()))
                .thenReturn(
                        Form16PartBTest.worked(
                                in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime.OLD));
        when(employees.maskedPans(any())).thenReturn(Map.of());
        when(employees.panForStatutoryOutput(9L)).thenReturn(Optional.of("ABCPS1234K"));
        when(unit.getName()).thenReturn("Regional Office Delhi");
    }

    @Test
    void SRS_7_9_theMemberDownloadsOnlyTheOwnReleasedCertificate() {
        computable();
        when(certificates.findById(3L)).thenReturn(Optional.of(certificate(Status.MERGED)));
        refused(() -> service.partB(context(9L, Set.of()), 3L), CommonErrorCode.NOT_FOUND);
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        Form16Service.PartBDocument own = service.partB(context(9L, Set.of()), 4L);
        assertThat(own.pan()).isEqualTo("ABCPS1234K");
        assertThat(own.employer()).isEqualTo("Regional Office Delhi");
        assertThat(own.sheet().totalTaxableIncome()).isEqualByComparingTo("683800");
        assertThat(own.toString()).doesNotContain("ABCPS1234K");
        verify(policy, Mockito.atLeastOnce()).require(any(), eq("E-01"), eq("SELF"));
    }

    @Test
    void SRS_7_9_anotherMembersCertificateNeedsC10ViewInScope() {
        computable();
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        Mockito.doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(any(), eq("C-10"), eq("VIEW"));
        // A member whose session employee is not the certificate's: the desk's rule, refused.
        refused(() -> service.partB(context(8L, Set.of()), 4L), CommonErrorCode.ACCESS_DENIED);
        Mockito.reset(policy);
        Mockito.doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(any(), eq("C-10"), eq("VIEW"), eq(1L));
        refused(() -> service.partB(context(null, Set.of(2L)), 4L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_7_5_theDeskPrintsAGeneratedPartBBeforeReleaseAndNothingUnknownOrUngenerated() {
        computable();
        when(certificates.findById(5L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        Form16Service.CertificatePdf pdf =
                service.certificatePdf(context(null, Set.of(1L)), "ip", 5L);
        assertThat(pdf.partAIncluded()).isFalse();
        assertThat(pdf.fileName()).isEqualTo("form16-" + YEAR);
        verify(store, Mockito.never())
                .read(anyString(), anyString(), anyString(), anyString(), any());
        refused(() -> service.partB(context(null, Set.of(1L)), 99L), CommonErrorCode.NOT_FOUND);
        Form16Certificate ungenerated = certificate(Status.PENDING_PART_A);
        ReflectionTestUtils.setField(ungenerated, "partBGeneratedAt", null);
        when(certificates.findById(6L)).thenReturn(Optional.of(ungenerated));
        refused(() -> service.partB(context(null, Set.of(1L)), 6L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_7_9_aMergedCertificatePrintsPartAFirstThenPartB() {
        computable();
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        byte[] partA =
                in.gov.fci.payroll.platform.export.PdfDocument.portrait("A", null)
                        .heading("PART A FROM TRACES")
                        .toBytes();
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(content.bytes()).thenReturn(partA);
        when(store.read(any(), any(), eq("form16"), any(), eq(31L))).thenReturn(content);
        Form16Service.CertificatePdf pdf = service.certificatePdf(context(9L, Set.of()), "ip", 4L);
        assertThat(pdf.partAIncluded()).isTrue();
        String text = in.gov.fci.payroll.platform.export.PdfText.text(pdf.pdf());
        assertThat(text.indexOf("PART A FROM TRACES"))
                .isLessThan(text.indexOf("PART B (Annexure)"));
        assertThat(pdf.pages()).isGreaterThan(1);
    }

    @Test
    void SRS_7_30_aTaxYearUnderTheIncomeTaxAct2025IsNotPrintedOnAGuessedForm() {
        Form16Certificate next =
                Form16Certificate.generated(
                        9L, "2026-27", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        when(certificates.findById(8L)).thenReturn(Optional.of(next));
        refused(
                () -> service.partB(context(null, Set.of(1L)), 8L),
                in.gov.fci.payroll.tax.business.TaxErrorCode.CERTIFICATE_FORMAT_NOT_NOTIFIED);
    }

    @Test
    void SRS_7_19_aYearThatCannotBeComputedIsNotPrinted() {
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        when(computation.forecast(any(), eq(YEAR), any(), any()))
                .thenReturn(forecast("PROJECTED", false, List.of("x"), null));
        refused(
                () -> service.partB(context(9L, Set.of()), 4L),
                in.gov.fci.payroll.tax.business.TaxErrorCode.EARNINGS_NOT_AVAILABLE);
    }

    private static byte[] partANaming(String... pans) {
        return in.gov.fci.payroll.platform.export.PdfDocument.portrait("A", null)
                .heading("FORM NO. 16 PART A")
                .note(
                        "PAN of the Deductor AAACF1234Q PAN of the Employee "
                                + String.join(" ", pans))
                .toBytes();
    }

    @Test
    void SRS_7_9_aPartANamingAnotherEmployeesPanIsNeverMergedOrSent() {
        computable();
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(content.bytes()).thenReturn(partANaming("ZZZPZ9999Z"));
        when(store.read(any(), any(), eq("form16"), any(), eq(31L))).thenReturn(content);
        refused(
                () -> service.certificatePdf(context(9L, Set.of()), "ip", 4L),
                TaxErrorCode.PART_A_MISMATCH);
        // At upload too: the Part A of someone else is refused before it is stored.
        when(certificates.lockById(5L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        refused(
                () ->
                        service.uploadPartA(
                                context(null, Set.of(1L)),
                                5L,
                                new Form16Service.PartA(
                                        "CERTIFICATE",
                                        null,
                                        null,
                                        "ITD",
                                        "a.pdf",
                                        partANaming("ZZZPZ9999Z"))),
                TaxErrorCode.PART_A_MISMATCH);
        verify(store, Mockito.never()).attach(any(), any());
    }

    @Test
    void SRS_7_9_aPartANamingTheEmployeesPanOrNoReadablePanIsMergedAsReceived() {
        computable();
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(store.read(any(), any(), eq("form16"), any(), eq(31L))).thenReturn(content);
        when(content.bytes()).thenReturn(partANaming("ABCPS1234K"));
        assertThat(service.certificatePdf(context(9L, Set.of()), "ip", 4L).partAIncluded())
                .isTrue();
        // A scan: no text, nothing to compare; an image is not a PDF and is not merged.
        when(content.bytes())
                .thenReturn(
                        in.gov.fci.payroll.platform.export.PdfDocument.portrait("s", null)
                                .toBytes());
        assertThat(service.certificatePdf(context(9L, Set.of()), "ip", 4L).partAIncluded())
                .isTrue();
        when(content.bytes()).thenReturn(new byte[] {(byte) 0xFF, (byte) 0xD8, 1});
        assertThat(service.certificatePdf(context(9L, Set.of()), "ip", 4L).partAIncluded())
                .isFalse();
        // An employee without a PAN on record: nothing to compare.
        when(employees.panForStatutoryOutput(9L)).thenReturn(Optional.empty());
        when(content.bytes()).thenReturn(partANaming("ZZZPZ9999Z"));
        assertThat(service.certificatePdf(context(9L, Set.of()), "ip", 4L).partAIncluded())
                .isTrue();
    }

    @Test
    void SRS_7_9_anExitedMembersReleasedCertificateIsStillTheirs() {
        computable();
        when(employees.member(9L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(9L, "870001", "M", 1L, "SEPARATED")));
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.RELEASED)));
        when(store.read(any(), any(), eq("form16"), any(), eq(31L))).thenReturn(null);
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(content.bytes()).thenReturn(partANaming("ABCPS1234K"));
        when(store.read(any(), any(), eq("form16"), any(), eq(31L))).thenReturn(content);
        assertThat(service.certificatePdf(context(9L, Set.of()), "ip", 4L).pdf()).isNotEmpty();
    }

    @Test
    void SRS_7_21_aReissuedCertificatePrintsAsANewDocument() {
        computable();
        Form16Certificate first = certificate(Status.PENDING_PART_A);
        when(certificates.findById(5L)).thenReturn(Optional.of(first));
        byte[] before = service.certificatePdf(context(null, Set.of(1L)), "ip", 5L).pdf();
        assertThat(service.certificatePdf(context(null, Set.of(1L)), "ip", 5L).pdf())
                .as("the same certificate gives the same bytes")
                .isEqualTo(before);
        ReflectionTestUtils.setField(first, "partBGeneratedAt", NOW.plusSeconds(86_400));
        assertThat(service.certificatePdf(context(null, Set.of(1L)), "ip", 5L).pdf())
                .as("a regenerated Part B is another document")
                .isNotEqualTo(before);
    }

    // ---------------------------------------------------------------- bulk (OD-149)

    private final in.gov.fci.payroll.jobs.api.BackgroundJobs bulkJobs =
            mock(in.gov.fci.payroll.jobs.api.BackgroundJobs.class);

    private in.gov.fci.payroll.jobs.api.BackgroundJobs.Job bulkJob(String status, String by) {
        return new in.gov.fci.payroll.jobs.api.BackgroundJobs.Job(
                77L,
                in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK,
                status,
                by,
                50L,
                Map.of("finYear", YEAR),
                2,
                1,
                null);
    }

    @Test
    void SRS_NF_2_theC10BulkIsQueuedForTheUsersUnitAndGoesToItsRequesterOnly() {
        service.setBackgroundJobs(bulkJobs);
        AccessContext desk = context(null, Set.of(1L));
        when(bulkJobs.enqueue(
                        eq(desk),
                        eq(in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK),
                        eq(50L),
                        eq(Map.of("locationId", "50", "finYear", YEAR))))
                .thenReturn(77L);
        assertThat(service.requestBulk(desk, YEAR).jobRequestId()).isEqualTo(77L);
        when(bulkJobs.latest(any(), eq("maker"), any()))
                .thenReturn(Optional.of(bulkJob("RUNNING", "maker")));
        assertThat(service.bulkStatus(desk, YEAR))
                .contains(new Form16Service.BulkFile(77L, "RUNNING", 2, 1));
        when(bulkJobs.find(in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK, 77L))
                .thenReturn(Optional.of(bulkJob("RUNNING", "maker")));
        refused(
                () -> service.bulkFile(desk, "ip", 77L),
                in.gov.fci.payroll.platform.error.ApiErrorCode.PDF_BULK_NOT_READY);
        when(bulkJobs.find(in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK, 77L))
                .thenReturn(Optional.of(bulkJob("COMPLETED", "someone.else")));
        refused(() -> service.bulkFile(desk, "ip", 77L), CommonErrorCode.NOT_FOUND);
        when(bulkJobs.find(in.gov.fci.payroll.jobs.api.BackgroundJobs.Kind.FORM16_BULK, 77L))
                .thenReturn(Optional.of(bulkJob("COMPLETED", "maker")));
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(view.id()).thenReturn(8L);
        when(store.active(Form16Service.OUTPUT_ENTITY, "77")).thenReturn(List.of(view));
        when(store.read("maker", "ip", Form16Service.OUTPUT_ENTITY, "77", 8L))
                .thenReturn(new DocumentStore.Content(view, new byte[] {'P', 'K', 3, 4}));
        Form16Service.BulkDownload d = service.bulkFile(desk, "ip", 77L);
        assertThat(d.name()).isEqualTo("form16-" + YEAR);
        assertThat(d.files()).isEqualTo(2);
    }

    @Test
    void OD_149_theBulkCertificatesAreTheGeneratedOnesInStaffNumberOrder() {
        when(org.subtree(50L)).thenReturn(Set.of(1L));
        when(employees.idsAt(Set.of(1L))).thenReturn(Set.of(9L, 10L));
        when(employees.members(Set.of(9L, 10L)))
                .thenReturn(
                        Map.of(
                                9L,
                                member,
                                10L,
                                new EmployeeDirectory.Member(
                                        10L, "870000", "N", 1L, "IN_SERVICE")));
        Form16Certificate a = certificate(Status.RELEASED);
        ReflectionTestUtils.setField(a, "form16Id", 1L);
        Form16Certificate b =
                Form16Certificate.generated(
                        10L, YEAR, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        ReflectionTestUtils.setField(b, "form16Id", 2L);
        Form16Certificate none = certificate(Status.DRAFT);
        ReflectionTestUtils.setField(none, "partBGeneratedAt", null);
        when(certificates.findByFinYearAndEmployeeIdIn(YEAR, Set.of(9L, 10L)))
                .thenReturn(List.of(a, b, none));
        assertThat(service.bulkCertificates(50L, YEAR))
                .containsExactly(
                        new Form16Service.BulkCertificate(2L, "870000"),
                        new Form16Service.BulkCertificate(1L, "870001"));
    }

    @Test
    void OD_149_aJobCertificateIsTheDesksDocumentAndANewActYearIsRefused() {
        computable();
        Form16Certificate draft = certificate(Status.PENDING_PART_A);
        when(certificates.findById(5L)).thenReturn(Optional.of(draft));
        String text =
                in.gov.fci.payroll.platform.export.PdfText.text(
                        service.certificatePdfForJob(5L, "maker"));
        assertThat(text).contains("PART B (Annexure)", "ABCPS1234K");
        Form16Certificate next =
                Form16Certificate.generated(
                        9L, "2026-27", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        when(certificates.findById(6L)).thenReturn(Optional.of(next));
        refused(
                () -> service.certificatePdfForJob(6L, "maker"),
                TaxErrorCode.CERTIFICATE_FORMAT_NOT_NOTIFIED);
        refused(() -> service.certificatePdfForJob(99L, "maker"), CommonErrorCode.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void OD_144_theDesksCertificateIsRenderedThroughTheRenderGateWhenOneIsSet() {
        computable();
        when(certificates.findById(5L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        in.gov.fci.payroll.platform.export.PdfRenderGate gate =
                mock(in.gov.fci.payroll.platform.export.PdfRenderGate.class);
        when(gate.render(eq("form16-" + YEAR), any()))
                .thenAnswer(
                        call -> ((java.util.function.Supplier<byte[]>) call.getArgument(1)).get());
        service.setRenderGate(gate);

        Form16Service.CertificatePdf pdf =
                service.certificatePdf(context(null, Set.of(1L)), "ip", 5L);

        verify(gate).render(eq("form16-" + YEAR), any());
        assertThat(pdf.partAIncluded()).isFalse();
        assertThat(in.gov.fci.payroll.platform.export.PdfText.text(pdf.pdf()))
                .contains("PART B (Annexure)");
    }

    @Test
    void OD_149_aJobCertificateOfAMergedYearPrintsPartAFirstAndNamesTheActor() {
        computable();
        when(certificates.findById(4L)).thenReturn(Optional.of(certificate(Status.MERGED)));
        byte[] partA =
                in.gov.fci.payroll.platform.export.PdfDocument.portrait("A", null)
                        .heading("PART A FROM TRACES")
                        .toBytes();
        DocumentStore.Content content = mock(DocumentStore.Content.class);
        when(content.bytes()).thenReturn(partA);
        when(store.read(eq("job.runner"), Mockito.isNull(), eq("form16"), any(), eq(31L)))
                .thenReturn(content);

        String text =
                in.gov.fci.payroll.platform.export.PdfText.text(
                        service.certificatePdfForJob(4L, "job.runner"));

        assertThat(text.indexOf("PART A FROM TRACES"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(text.indexOf("PART B (Annexure)"));
    }

    @Test
    void OD_149_aJobCertificateOfAMemberNoLongerOnRecordIsNotFound() {
        computable();
        when(certificates.findById(5L)).thenReturn(Optional.of(certificate(Status.PENDING_PART_A)));
        when(employees.member(9L)).thenReturn(Optional.empty());

        refused(() -> service.certificatePdfForJob(5L, "maker"), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void OD_149_aBulkCertificateOfAMemberNotInTheDirectoryIsNamedByItsId() {
        when(org.subtree(50L)).thenReturn(Set.of(1L));
        when(employees.idsAt(Set.of(1L))).thenReturn(Set.of(9L, 10L));
        when(employees.members(Set.of(9L, 10L))).thenReturn(Map.of(9L, member));
        Form16Certificate a = certificate(Status.RELEASED);
        ReflectionTestUtils.setField(a, "form16Id", 1L);
        Form16Certificate b =
                Form16Certificate.generated(
                        10L, YEAR, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 7L, NOW);
        ReflectionTestUtils.setField(b, "form16Id", 2L);
        when(certificates.findByFinYearAndEmployeeIdIn(YEAR, Set.of(9L, 10L)))
                .thenReturn(List.of(a, b));

        assertThat(service.bulkCertificates(50L, YEAR))
                .containsExactly(
                        new Form16Service.BulkCertificate(2L, "10"),
                        new Form16Service.BulkCertificate(1L, "870001"));
    }
}
