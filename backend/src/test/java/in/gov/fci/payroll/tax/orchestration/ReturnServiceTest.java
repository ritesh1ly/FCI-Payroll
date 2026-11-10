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
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
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
import in.gov.fci.payroll.tax.business.PostingLedger;
import in.gov.fci.payroll.tax.business.ReturnValidator;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.FilingRecord;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.business.model.ReturnDeductee;
import in.gov.fci.payroll.tax.business.model.StatutoryReturn;
import in.gov.fci.payroll.tax.business.model.TaxChallan;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-11 beyond the integration path (SRS-7-7, SRS-7-8, SRS-7-21, SRS-7-31): an empty or header-only
 * challan file, every refusal of a correction, the regeneration of a validated return, months not
 * locked or of another quarter, a challan not remitted, a utility that rejects, decisions of other
 * screens or rejected, every refusal of a filing, evidence not of the return, and a return unknown.
 */
class ReturnServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final String YEAR = "2025-26";

    private final TaxRepositories.Returns returns = mock(TaxRepositories.Returns.class);
    private final TaxRepositories.Deductees deductees = mock(TaxRepositories.Deductees.class);
    private final BatchScope batch = mock(BatchScope.class);
    private final TaxRepositories.Challans challans = mock(TaxRepositories.Challans.class);
    private final TaxRepositories.Filings filings = mock(TaxRepositories.Filings.class);
    private final Form16Service units = mock(Form16Service.class);
    private final TaxComputationService computation = mock(TaxComputationService.class);
    private final OutputDefinitionService outputs = mock(OutputDefinitionService.class);
    private final TaxableEarnings earnings = mock(TaxableEarnings.class);
    private final PostingLedger ledger = mock(PostingLedger.class);
    private final ReturnValidator validator = mock(ReturnValidator.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final DocumentStore store = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final ReturnService service =
            new ReturnService(
                    returns,
                    deductees,
                    challans,
                    filings,
                    units,
                    computation,
                    outputs,
                    earnings,
                    ledger,
                    validator,
                    employees,
                    payroll,
                    worklist,
                    store,
                    approvals,
                    statuses,
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    batch);

    private final EmployeeDirectory.Member member =
            new EmployeeDirectory.Member(9L, "870001", "M", 1L, "IN_SERVICE");
    private final OrgLocation unit = mock(OrgLocation.class);
    private final OutputVersion version = mock(OutputVersion.class);
    private final AccessContext ctx =
            new AccessContext(
                    2L,
                    "maker",
                    "Maker",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    ReturnServiceTest() {
        when(unit.getId()).thenReturn(50L);
        when(unit.getCode()).thenReturn("DH001");
        when(units.unitOf(any())).thenReturn(unit);
        when(units.membersOf(any(), any())).thenReturn(Set.of(9L));
        when(employees.members(any())).thenReturn(Map.of(9L, member));
        when(employees.panStatus(9L)).thenReturn(Optional.of(PanStatus.VALID));
        when(version.getReportVersionId()).thenReturn(7L);
        when(outputs.requireGoverning(anyString(), any()))
                .thenReturn(new OutputDefinitionService.Governing(version, List.of(), List.of()));
        when(statuses.pending(any())).thenReturn(List.of());
        when(payroll.cycle(eq(50L), anyString()))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 50L, "202504", "LOCKED")));
        when(computation.forecast(any(), anyString(), any(), any()))
                .thenReturn(
                        new TaxComputationService.Forecast(
                                9L,
                                "870001",
                                "M",
                                "IN_SERVICE",
                                YEAR,
                                "ACTUAL",
                                new TaxComputationService.Inputs(
                                        null,
                                        PanStatus.VALID,
                                        List.of(),
                                        BigDecimal.ZERO,
                                        List.of()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                List.of()));
        when(returns.save(any())).thenAnswer(i -> i.getArgument(0));
        when(computation.frozenRegimes(any(), anyString()))
                .thenAnswer(i -> new java.util.HashSet<Long>(i.getArgument(0)));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static StatutoryReturn ret(String type, String quarter, Long corrects, String state) {
        StatutoryReturn r = StatutoryReturn.generated(type, YEAR, quarter, 50L, 7L, corrects, NOW);
        r.totals(1, new BigDecimal("100"));
        if (!"GENERATED".equals(state)) r.validated(true, "FVU", "PASS");
        if ("RELEASED".equals(state) || "FILED".equals(state)) r.released("checker", NOW);
        if ("FILED".equals(state)) r.filed();
        return r;
    }

    private static TaxableEarnings.Year year(String tds, boolean actual, String yymm) {
        return new TaxableEarnings.Year(
                List.of(
                        new TaxableEarnings.Month(
                                yymm,
                                actual,
                                new BigDecimal("1000"),
                                new BigDecimal(tds),
                                BigDecimal.ZERO,
                                List.of())));
    }

    private static ReturnService.Filing filing(
            LocalDate date, String name, String designation, String token, byte[] content) {
        return new ReturnService.Filing(
                date, name, designation, token, "CERTIFICATE", date, "TIN", "ack.pdf", content);
    }

    @Test
    void SRS_7_21_anEmptyOrHeaderOnlyChallanFileImportsNothing() {
        refused(() -> service.importChallans(ctx, YEAR, null), CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.importChallans(ctx, YEAR, new byte[0]),
                CommonErrorCode.VALIDATION_FAILED);
        ReturnService.Import none =
                service.importChallans(
                        ctx,
                        YEAR,
                        " CIN , BSR,date,amount,reference,PERIOD \n\n"
                                .getBytes(StandardCharsets.US_ASCII));
        assertThat(none.imported()).isZero();
        verify(challans, never()).save(any());
    }

    @Test
    void SRS_7_7_aCorrectionIsOfAReleasedOrFiledOriginalOfTheSameYearAndQuarter() {
        StatutoryReturn correction = ret("FORM_24Q_CORRECTION", "Q1", 1L, "RELEASED");
        StatutoryReturn generated = ret("FORM_24Q", "Q1", null, "GENERATED");
        StatutoryReturn released = ret("FORM_24Q", "Q1", null, "RELEASED");
        when(returns.findById(11L)).thenReturn(Optional.of(correction));
        when(returns.findById(12L)).thenReturn(Optional.of(generated));
        when(returns.findById(13L)).thenReturn(Optional.of(released));
        refused(() -> service.generate(ctx, YEAR, "Q1", 11L), TaxErrorCode.DECLARATION_STATE);
        refused(() -> service.generate(ctx, YEAR, "Q1", 12L), TaxErrorCode.DECLARATION_STATE);
        refused(() -> service.generate(ctx, YEAR, "Q2", 13L), TaxErrorCode.DECLARATION_STATE);
        refused(() -> service.generate(ctx, "2024-25", "Q1", 13L), TaxErrorCode.DECLARATION_STATE);
        refused(() -> service.generate(ctx, YEAR, "Q1", 14L), CommonErrorCode.NOT_FOUND);
        // A return of another paying unit reads as unknown.
        StatutoryReturn elsewhere =
                StatutoryReturn.generated("FORM_24Q", YEAR, "Q1", 60L, 7L, null, NOW);
        when(returns.findById(15L)).thenReturn(Optional.of(elsewhere));
        refused(() -> service.generate(ctx, YEAR, "Q1", 15L), CommonErrorCode.NOT_FOUND);
        // A filed correction already exists: no second one (DDL uk_statutory_return).
        StatutoryReturn filedCorrection = ret("FORM_24Q_CORRECTION", "Q1", 13L, "FILED");
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(released, filedCorrection));
        assertThatThrownBy(() -> service.generate(ctx, YEAR, "Q1", 13L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .contains("already corrects return 13"));
    }

    @Test
    void SRS_7_7_aValidatedOriginalIsRegeneratedInPlaceOnlyFromLockedMonthsOfTheQuarter() {
        StatutoryReturn validated = ret("FORM_24Q", "Q1", null, "VALIDATED");
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(validated));
        when(returns.lockById(any())).thenReturn(Optional.of(validated));
        ReturnDeductee existing =
                ReturnDeductee.of(
                        null, 9L, null, false, false, BigDecimal.ONE, BigDecimal.ONE, null);
        // The member's row of the earlier generation is refigured in place, not added again.
        when(deductees.findByReturnIdAndEmployeeId(any(), eq(9L)))
                .thenReturn(Optional.of(existing));
        // The totals are read back from the rows the generation wrote.
        when(deductees.countInReturn(any())).thenReturn(1L);
        when(deductees.totalInReturn(any())).thenReturn(new BigDecimal("80"));
        // A projected month and a month of another quarter count nothing; the challan of the
        // quarter's month (YYYYMM) maps.
        when(earnings.year(9L, YEAR))
                .thenReturn(
                        Optional.of(
                                new TaxableEarnings.Year(
                                        List.of(
                                                new TaxableEarnings.Month(
                                                        "202504",
                                                        true,
                                                        new BigDecimal("1000"),
                                                        new BigDecimal("80"),
                                                        BigDecimal.ZERO,
                                                        List.of()),
                                                new TaxableEarnings.Month(
                                                        "202505",
                                                        false,
                                                        new BigDecimal("1000"),
                                                        new BigDecimal("80"),
                                                        BigDecimal.ZERO,
                                                        List.of()),
                                                new TaxableEarnings.Month(
                                                        "202507",
                                                        true,
                                                        new BigDecimal("1000"),
                                                        new BigDecimal("80"),
                                                        BigDecimal.ZERO,
                                                        List.of())))));
        TaxChallan byMonth =
                TaxChallan.imported(
                        "CIN1",
                        "0510308",
                        LocalDate.of(2025, 5, 7),
                        BigDecimal.TEN,
                        null,
                        YEAR,
                        "202505",
                        50L);
        TaxChallan otherQuarter =
                TaxChallan.imported(
                        "CIN2",
                        "0510308",
                        LocalDate.of(2025, 8, 7),
                        BigDecimal.TEN,
                        null,
                        YEAR,
                        "Q2",
                        50L);
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR))
                .thenReturn(List.of(otherQuarter, byMonth));
        when(employees.panForStatutoryOutput(9L)).thenReturn(Optional.of("KLKPK7001A"));
        // Imported challans have their database ids.
        org.springframework.test.util.ReflectionTestUtils.setField(byMonth, "challanId", 21L);
        org.springframework.test.util.ReflectionTestUtils.setField(otherQuarter, "challanId", 22L);
        ReturnService.ReturnView v = service.generate(ctx, YEAR, "Q1", null);
        assertThat(v.status()).isEqualTo("GENERATED");
        assertThat(v.validated()).isFalse();
        assertThat(v.totalTds()).isEqualByComparingTo("80");
        // The rows refigured in place are written before the totals are read back.
        verify(batch).flush();
        assertThat(existing.getTaxDeducted()).isEqualByComparingTo("80");
        assertThat(existing.isPanValid()).isTrue();
        assertThat(existing.getChallanId()).isEqualTo(21L);
        // An unlocked month of the quarter: no return.
        when(payroll.cycle(50L, "202506"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(2L, 50L, "202506", "INITIATED")));
        refused(() -> service.generate(ctx, YEAR, "Q1", null), TaxErrorCode.PERIOD_NOT_LOCKED);
    }

    // SC-08-0057: each rejection of the validation utility is listed.
    @Test
    void SRS_7_8_onlyAGeneratedReturnIsValidatedAndEachFailedCheckIsListed() {
        StatutoryReturn validated = ret("FORM_24Q", "Q2", null, "VALIDATED");
        when(returns.lockById(1L)).thenReturn(Optional.of(validated));
        refused(() -> service.validate(ctx, 1L), TaxErrorCode.DECLARATION_STATE);
        refused(() -> service.validate(ctx, 2L), CommonErrorCode.NOT_FOUND);
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(3L)).thenReturn(Optional.of(generated));
        TaxChallan unremitted =
                TaxChallan.imported(
                        "CIN9",
                        "0510308",
                        LocalDate.of(2025, 8, 7),
                        BigDecimal.TEN,
                        null,
                        YEAR,
                        "Q2",
                        50L);
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR))
                .thenReturn(List.of(unremitted));
        when(ledger.remitted(any(), any(), any())).thenReturn(Optional.of(false));
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.of(new BigDecimal("100")));
        when(validator.validate(any()))
                .thenReturn(
                        Optional.of(
                                new ReturnValidator.Verdict(
                                        "FVU", false, List.of("T-FV-1001 bad PAN"))));
        ReturnService.ReturnView v = service.validate(ctx, 3L);
        assertThat(v.validationResult())
                .anyMatch(r -> r.contains("maps to no posted remittance"))
                .contains("T-FV-1001 bad PAN");
        assertThat(v.validationUtility()).isEqualTo("FVU");
    }

    @Test
    void SRS_7_21_onlyAnApprovedReleaseOfC11ReleasesAValidatedReturn() {
        service.on(
                new ApprovalDecided(
                        90L,
                        "C-10",
                        "statutory_return",
                        "1",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker",
                        NOW));
        service.on(
                new ApprovalDecided(
                        90L,
                        "C-11",
                        "form16",
                        "1",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker",
                        NOW));
        service.on(
                new ApprovalDecided(
                        90L,
                        "C-11",
                        "statutory_return",
                        "1",
                        ApprovalStatus.REJECTED,
                        "no",
                        null,
                        "checker",
                        NOW));
        verifyNoInteractions(returns);
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(1L)).thenReturn(Optional.of(generated));
        refused(
                () ->
                        service.on(
                                new ApprovalDecided(
                                        90L,
                                        "C-11",
                                        "statutory_return",
                                        "1",
                                        ApprovalStatus.APPROVED,
                                        null,
                                        null,
                                        "checker",
                                        NOW)),
                TaxErrorCode.DECLARATION_STATE);
        StatutoryReturn validated = ret("FORM_24Q", "Q2", null, "VALIDATED");
        when(returns.lockById(2L)).thenReturn(Optional.of(validated));
        service.on(
                new ApprovalDecided(
                        91L,
                        "C-11",
                        "statutory_return",
                        "2",
                        ApprovalStatus.APPROVED,
                        "ok",
                        null,
                        "checker",
                        NOW));
        assertThat(validated.status()).isEqualTo(StatutoryReturn.Status.RELEASED);
        // The release request: only of a validated return.
        refused(() -> service.requestRelease(ctx, 1L), TaxErrorCode.DECLARATION_STATE);
    }

    @Test
    void SRS_7_31_aFilingIsOfAReleasedReturnDatedNoLaterThanTodayWithItsSignatory() {
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(1L)).thenReturn(Optional.of(generated));
        refused(
                () ->
                        service.recordFiling(
                                ctx, 1L, filing(LocalDate.of(2026, 10, 1), "S", "D", null, null)),
                TaxErrorCode.DECLARATION_STATE);
        StatutoryReturn released = ret("FORM_24Q", "Q2", null, "RELEASED");
        when(returns.lockById(2L)).thenReturn(Optional.of(released));
        LocalDate today = LocalDate.of(2026, 10, 25);
        for (ReturnService.Filing bad :
                java.util.Arrays.asList(
                        null,
                        filing(null, "S", "D", null, null),
                        filing(today.plusDays(1), "S", "D", null, null),
                        filing(today, " ", "D", null, null),
                        filing(today, "S", null, null, null))) {
            refused(() -> service.recordFiling(ctx, 2L, bad), CommonErrorCode.VALIDATION_FAILED);
        }
        // Without evidence and with a blank token: neither stored.
        when(filings.save(any())).thenAnswer(i -> i.getArgument(0));
        service.recordFiling(ctx, 2L, filing(today, " S ", " D ", " ", null));
        assertThat(released.status()).isEqualTo(StatutoryReturn.Status.FILED);
        verify(store, never()).attach(any(), any());
    }

    @Test
    void SRS_7_31_evidenceIsReadOnlyForItsReturn() {
        StatutoryReturn filed = ret("FORM_24Q", "Q2", null, "FILED");
        when(returns.findById(1L)).thenReturn(Optional.of(filed));
        when(filings.findByReturnIdOrderByFilingId(1L))
                .thenReturn(
                        List.of(
                                FilingRecord.of(
                                        1L,
                                        LocalDate.of(2026, 7, 20),
                                        "S",
                                        "D",
                                        "T",
                                        5L,
                                        "maker",
                                        NOW)));
        refused(() -> service.evidence(ctx, "ip", 1L, 6L), CommonErrorCode.NOT_FOUND);
        service.evidence(ctx, "ip", 1L, 5L);
        verify(store).read("maker", "ip", "statutory_return", "1", 5L);
        refused(() -> service.evidence(ctx, "ip", 9L, 5L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_7_23_aDeducteeOfAMemberNoLongerInTheDirectoryShowsNoName() {
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.findById(1L)).thenReturn(Optional.of(generated));
        ReturnDeductee d =
                ReturnDeductee.of(
                        1L, 70L, "KLKPK7001A", true, false, BigDecimal.TEN, BigDecimal.ONE, null);
        when(deductees.findByReturnIdAndTaxDeductedGreaterThanOrderByDeducteeId(
                        any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(d)));
        when(employees.members(List.of(70L))).thenReturn(Map.of());
        ReturnService.DeducteeView v =
                service.deductees(ctx, 1L, new PageSpec(0, 20)).items().get(0);
        assertThat(v.eis()).isNull();
        assertThat(v.name()).isNull();
        assertThat(v.panMasked()).isNotEqualTo("KLKPK7001A");
    }

    @Test
    void SRS_G_29_theDeducteesOfAUnitAreCheckedAgainstTheDataScopeOfTheSession() {
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.findById(1L)).thenReturn(Optional.of(generated));
        org.mockito.Mockito.doThrow(new FciException(ApiErrorCode.OUT_OF_SCOPE))
                .when(policy)
                .requireAt(eq(ctx), eq("C-11"), eq("VIEW"), any());

        assertThatThrownBy(() -> service.deductees(ctx, 1L, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(deductees);
    }

    @Test
    void SRS_7_21_aFileWithoutAHeaderImportsItsFirstLine() {
        when(challans.findByCinNumberAndBsrCodeAndChallanDate(any(), any(), any()))
                .thenReturn(Optional.empty());
        ReturnService.Import one =
                service.importChallans(
                        ctx,
                        YEAR,
                        "CIN41,0510308,2025-05-07,10.00,,202505"
                                .getBytes(StandardCharsets.US_ASCII));
        assertThat(one.imported()).isEqualTo(1);
        verify(challans).save(any());
    }

    /**
     * SRS C-11 field table (l.2625): a challan keyed by the Maker takes the checks of a file line,
     * is refused when already held (CIN, BSR, date) and is listed with its CIN, BSR and date.
     */
    @Test
    void SRS_l2625_aKeyedChallanTakesTheFileChecksAndIsRefusedWhenAlreadyHeld() {
        TaxChallan held =
                TaxChallan.imported(
                        "CIN42",
                        "0510308",
                        LocalDate.of(2025, 5, 7),
                        new BigDecimal("10.00"),
                        null,
                        YEAR,
                        "202505",
                        50L);
        when(challans.findByCinNumberAndBsrCodeAndChallanDate(any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(held));
        ReturnService.ChallanView v =
                service.enterChallan(
                        ctx,
                        YEAR,
                        new ReturnService.ChallanEntry(
                                " CIN42 ",
                                "0510308",
                                LocalDate.of(2025, 5, 7),
                                new BigDecimal("10.00"),
                                null,
                                "202505"));
        assertThat(v.cin()).isEqualTo("CIN42");
        assertThat(v.bsr()).isEqualTo("0510308");
        verify(challans).save(any());
        // Held already: refused, nothing saved again.
        when(challans.findByCinNumberAndBsrCodeAndChallanDate(any(), any(), any()))
                .thenReturn(Optional.of(held));
        refused(
                () ->
                        service.enterChallan(
                                ctx,
                                YEAR,
                                new ReturnService.ChallanEntry(
                                        "CIN42",
                                        "0510308",
                                        LocalDate.of(2025, 5, 7),
                                        new BigDecimal("10.00"),
                                        null,
                                        "202505")),
                TaxErrorCode.CHALLAN_FILE);
        // A date outside the year, a zero amount or a missing field: the file's own refusals.
        for (ReturnService.ChallanEntry bad :
                List.of(
                        new ReturnService.ChallanEntry(
                                "CIN43",
                                "0510308",
                                LocalDate.of(2024, 5, 7),
                                BigDecimal.TEN,
                                null,
                                "202505"),
                        new ReturnService.ChallanEntry(
                                "CIN43",
                                "0510308",
                                LocalDate.of(2025, 5, 7),
                                BigDecimal.ZERO,
                                null,
                                "202505"),
                        new ReturnService.ChallanEntry(null, null, null, null, null, null))) {
            refused(() -> service.enterChallan(ctx, YEAR, bad), TaxErrorCode.CHALLAN_FILE);
        }
    }

    /** SRS-7-31 (l.2609): the filing evidence keeps its own reference, else the token. */
    @Test
    void SRS_7_31_theFilingEvidenceKeepsItsOwnReference() {
        StatutoryReturn released = ret("FORM_24Q", "Q2", null, "RELEASED");
        when(returns.lockById(2L)).thenReturn(Optional.of(released));
        when(filings.save(any())).thenAnswer(i -> i.getArgument(0));
        DocumentStore.AttachmentView proof = mock(DocumentStore.AttachmentView.class);
        when(proof.id()).thenReturn(5L);
        when(store.attach(any(), any())).thenReturn(proof);
        LocalDate today = LocalDate.of(2026, 10, 25);
        service.recordFiling(
                ctx,
                2L,
                new ReturnService.Filing(
                        today,
                        "S",
                        "D",
                        "TOKEN1",
                        "CERTIFICATE",
                        today,
                        "TIN",
                        "ack.pdf",
                        new byte[] {1},
                        " ACK/2026/9 "));
        org.mockito.ArgumentCaptor<DocumentStore.NewAttachment> sent =
                org.mockito.ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(store).attach(any(), sent.capture());
        assertThat(sent.getValue().referenceNumber()).isEqualTo("ACK/2026/9");
    }

    /**
     * SRS-7-7 through the C-13 job, which opens the return without the screen's unit check: a
     * correction of another paying unit's return is unknown; an original ignores the corrections of
     * its quarter.
     */
    @Test
    void SRS_7_7_theJobOpensNoCorrectionOfAnotherUnitAndAnOriginalIgnoresCorrections() {
        StatutoryReturn elsewhere =
                StatutoryReturn.generated("FORM_24Q", YEAR, "Q1", 60L, 7L, null, NOW);
        when(returns.findById(15L)).thenReturn(Optional.of(elsewhere));
        refused(
                () -> service.openReturn("job", 50L, YEAR, "Q1", 15L, true),
                CommonErrorCode.NOT_FOUND);

        StatutoryReturn correction = ret("FORM_24Q_CORRECTION", "Q1", 13L, "RELEASED");
        when(returns.findByOrgUnitIdAndFinYearOrderByReturnIdDesc(50L, YEAR))
                .thenReturn(List.of(correction));
        service.openReturn("job", 50L, YEAR, "Q1", null, true);
        ArgumentCaptor<StatutoryReturn> opened = ArgumentCaptor.captor();
        verify(returns, org.mockito.Mockito.atLeastOnce()).save(opened.capture());
        assertThat(opened.getValue()).isNotSameAs(correction);
        assertThat(opened.getValue().isCorrection()).isFalse();
    }

    /**
     * SRS-7-8 / SRS-7-21: a challan of a month of the quarter is validated with the quarter's;
     * without the posting interface its remittance is not available, and the validation fails.
     */
    @Test
    void SRS_7_8_aMonthChallanIsCheckedAndAnUnavailableRemittanceFailsTheValidation() {
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(3L)).thenReturn(Optional.of(generated));
        TaxChallan byMonth =
                TaxChallan.imported(
                        "CIN8",
                        "0510308",
                        LocalDate.of(2025, 8, 7),
                        BigDecimal.TEN,
                        null,
                        YEAR,
                        "202508",
                        50L);
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR))
                .thenReturn(List.of(byMonth));
        when(ledger.remitted(any(), any(), any())).thenReturn(Optional.empty());
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.empty());
        when(validator.validate(any())).thenReturn(Optional.empty());
        assertThat(service.validate(ctx, 3L).validationResult())
                .anyMatch(r -> r.startsWith("challan CIN8: remittances are not available"));
    }

    /**
     * US-GAP-196 (FRS V0.5 §14 Preconditions l.5545, BR-TAX-001): a deductee whose regime for the
     * year is not frozen (reset and not yet approved again) fails the validation of any quarter.
     */
    @Test
    void BR_TAX_001_aDeducteeWithoutAFrozenRegimeFailsTheValidation() {
        StatutoryReturn generated = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(3L)).thenReturn(Optional.of(generated));
        when(deductees.findByReturnIdOrderByDeducteeId(any()))
                .thenReturn(
                        List.of(
                                ReturnDeductee.of(
                                        3L,
                                        9L,
                                        "KLKPK7001A",
                                        true,
                                        false,
                                        new BigDecimal("100000"),
                                        new BigDecimal("100"),
                                        21L)));
        when(employees.panForStatutoryOutput(9L)).thenReturn(Optional.of("KLKPK7001A"));
        when(employees.eisOf(9L)).thenReturn(Optional.of("870001"));
        when(challans.findByOrgUnitIdAndFinYearOrderByChallanDate(50L, YEAR)).thenReturn(List.of());
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.empty());
        when(validator.validate(any())).thenReturn(Optional.empty());
        when(computation.frozenRegimes(any(), eq(YEAR))).thenReturn(java.util.Set.of());
        assertThat(service.validate(ctx, 3L).validationResult())
                .contains("no frozen tax regime for " + YEAR + " for member 870001");

        StatutoryReturn again = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.lockById(5L)).thenReturn(Optional.of(again));
        when(computation.frozenRegimes(any(), eq(YEAR))).thenReturn(java.util.Set.of(9L));
        assertThat(service.validate(ctx, 5L).validationResult())
                .noneMatch(r -> r.startsWith("no frozen tax regime"));
    }

    /** SRS-7-19: in the fourth quarter a member's year-end difference fails the validation. */
    @Test
    void SRS_7_19_aYearEndDifferenceFailsTheFourthQuarterAndIsRaised() {
        StatutoryReturn q4 = ret("FORM_24Q", "Q4", null, "GENERATED");
        when(returns.lockById(4L)).thenReturn(Optional.of(q4));
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("80", true, "202603")));
        when(computation.forecast(any(), anyString(), any(), any()))
                .thenReturn(
                        new TaxComputationService.Forecast(
                                9L,
                                "870001",
                                "M",
                                "IN_SERVICE",
                                YEAR,
                                "ACTUAL",
                                null,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(new BigDecimal("12.40")),
                                List.of()));
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.empty());
        when(validator.validate(any())).thenReturn(Optional.empty());
        assertThat(service.validate(ctx, 4L).validationResult())
                .anyMatch(r -> r.startsWith("year-end difference of Rs 12 for member 870001"));
        verify(computation)
                .raiseYearEndMismatch(
                        eq("maker"), eq(member), eq(YEAR), eq(new BigDecimal("12")), anyString());
    }

    @Test
    void SRS_7_21_aReleaseApprovedWithoutAReasonIsRecordedAsReleased() {
        StatutoryReturn validated = ret("FORM_24Q", "Q2", null, "VALIDATED");
        when(returns.lockById(2L)).thenReturn(Optional.of(validated));
        service.on(
                new ApprovalDecided(
                        91L,
                        "C-11",
                        "statutory_return",
                        "2",
                        ApprovalStatus.APPROVED,
                        null,
                        null,
                        "checker",
                        NOW));
        assertThat(validated.status()).isEqualTo(StatutoryReturn.Status.RELEASED);
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("checker"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("released");
    }

    /** SRS-7-23: a return opened by the job and not yet totalled lists no deductees. */
    @Test
    void SRS_7_23_aReturnNotYetTotalledShowsNoDeductees() {
        StatutoryReturn draft =
                StatutoryReturn.generated("FORM_24Q", YEAR, "Q3", 50L, 7L, null, NOW);
        when(returns.findByOrgUnitIdInAndFinYearOrderByReturnIdDesc(any(), eq(YEAR), any()))
                .thenReturn(new PageImpl<>(List.of(draft)));
        assertThat(service.register(ctx, YEAR, new PageSpec(0, 20)).items().get(0).deductees())
                .isZero();
    }

    /**
     * SRS-7-23: a return row whose deductee count is NULL (DDL statutory_return.deductee_count is
     * nullable) lists no deductees; a totalled one lists its count.
     */
    @Test
    void SRS_7_23_aReturnRowWithoutADeducteeCountShowsNone() {
        StatutoryReturn unknown =
                StatutoryReturn.generated("FORM_24Q", YEAR, "Q3", 50L, 7L, null, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(unknown, "deducteeCount", null);
        StatutoryReturn totalled = ret("FORM_24Q", "Q2", null, "GENERATED");
        when(returns.findByOrgUnitIdInAndFinYearOrderByReturnIdDesc(any(), eq(YEAR), any()))
                .thenReturn(new PageImpl<>(List.of(unknown, totalled)));
        List<ReturnService.ReturnView> items =
                service.register(ctx, YEAR, new PageSpec(0, 20)).items();
        assertThat(items.get(0).deductees()).isZero();
        assertThat(items.get(1).deductees()).isEqualTo(1);
    }

    /** SRS-7-21: every challan line of a file is imported, a blank one skipped. */
    @Test
    void SRS_7_21_eachLineAfterTheFirstIsImportedToo() {
        when(challans.findByCinNumberAndBsrCodeAndChallanDate(any(), any(), any()))
                .thenReturn(Optional.empty());
        ReturnService.Import both =
                service.importChallans(
                        ctx,
                        YEAR,
                        ("CIN41,0510308,2025-05-07,10.00,,202505\n"
                                        + "   \n"
                                        + "CIN42,0510308,2025-06-07,20.00,,202506\n")
                                .getBytes(StandardCharsets.US_ASCII));
        assertThat(both.imported()).isEqualTo(2);
        verify(challans, org.mockito.Mockito.times(2)).save(any());
    }

    /** SRS-7-19: in the fourth quarter a member whose actual tax equals the TDS passes. */
    @Test
    void SRS_7_19_aFourthQuarterWithoutAYearEndDifferenceIsNotRaised() {
        StatutoryReturn q4 = ret("FORM_24Q", "Q4", null, "GENERATED");
        when(returns.lockById(4L)).thenReturn(Optional.of(q4));
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("80", true, "202603")));
        when(ledger.postedTds(eq(50L), any())).thenReturn(Optional.empty());
        when(validator.validate(any())).thenReturn(Optional.empty());
        assertThat(service.validate(ctx, 4L).validationResult())
                .noneMatch(r -> r.startsWith("year-end difference"));
        verify(computation, never()).raiseYearEndMismatch(any(), any(), any(), any(), any());
    }
}
