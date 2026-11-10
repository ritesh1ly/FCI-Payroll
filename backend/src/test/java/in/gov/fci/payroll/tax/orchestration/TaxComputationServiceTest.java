package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.configuration.api.RuleSet;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.api.TaxDeductions;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.TaxableEarnings;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclaration;
import in.gov.fci.payroll.tax.business.model.ItDeclarationSubmission;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.PtState;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * C-07 and the engines' port beyond the integration path (SRS-7-1 … SRS-7-4, SRS-7-12, SRS-7-20,
 * SRS-7-26; BR-TAX-006, BR-TAX-014, BR-TAX-015): which approved inputs count, the PT band of the
 * office's state, a PT or slab rule not configured, the rate date of a year, and a member unknown.
 */
class TaxComputationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final String YEAR = "2026-27";

    private final TaxRepositories.Regimes regimes = mock(TaxRepositories.Regimes.class);
    private final TaxRepositories.Submissions submissions = mock(TaxRepositories.Submissions.class);
    private final TaxRepositories.Declarations lines = mock(TaxRepositories.Declarations.class);
    private final TaxRepositories.Entries entries = mock(TaxRepositories.Entries.class);
    private final TaxRepositories.Savings savings = mock(TaxRepositories.Savings.class);
    private final TaxRepositories.Sections sections = mock(TaxRepositories.Sections.class);
    private final TaxRepositories.PtStates states = mock(TaxRepositories.PtStates.class);
    private final TaxableEarnings earnings = mock(TaxableEarnings.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final DeclarationService declarations = mock(DeclarationService.class);
    private final Authorization policy = mock(Authorization.class);
    private final RuleSet set = mock(RuleSet.class);

    /** The PT state cache over the mocked master: one version, loaded on first use. */
    private static PtStates ptStates(TaxRepositories.PtStates master) {
        CacheRegions regions = mock(CacheRegions.class);
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        return new PtStates(master, regions);
    }

    private final TaxComputationService service =
            new TaxComputationService(
                    regimes,
                    submissions,
                    lines,
                    entries,
                    savings,
                    sections,
                    ptStates(states),
                    earnings,
                    employees,
                    org,
                    rules,
                    worklist,
                    declarations,
                    policy,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final EmployeeDirectory.Member member =
            new EmployeeDirectory.Member(9L, "870001", "M", 1L, "IN_SERVICE");
    private final OrgLocation office = mock(OrgLocation.class);

    TaxComputationServiceTest() {
        when(employees.member(9L)).thenReturn(Optional.of(member));
        when(employees.memberByEis("870001")).thenReturn(Optional.of(member));
        when(employees.panStatus(9L)).thenReturn(Optional.of(PanStatus.VALID));
        when(org.location(1L)).thenReturn(Optional.of(office));
        when(rules.snapshot(any())).thenReturn(set);
        when(set.resolve(anyString(), any()))
                .thenThrow(new FciException(ApiErrorCode.RULE_NOT_CONFIGURED));
    }

    private static AccessContext context() {
        return new AccessContext(
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
    }

    private static RuleEngine.Band band(
            int order, String from, String to, String rate, String qualifier) {
        return new RuleEngine.Band(
                order,
                new BigDecimal(from),
                to == null ? null : new BigDecimal(to),
                new BigDecimal(rate),
                null,
                qualifier);
    }

    private static Optional<RuleEngine.Resolved> resolved(
            String key, BigDecimal scalar, List<RuleEngine.Band> bands) {
        return Optional.of(
                new RuleEngine.Resolved(
                        key,
                        "SLAB",
                        1L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        null,
                        scalar,
                        bands,
                        null,
                        null,
                        false));
    }

    private void approvedRegime(Regime regime) {
        ItRegimeDetail r = mock(ItRegimeDetail.class);
        when(r.getVerifyStatus()).thenReturn("APPROVED");
        when(r.getRegime()).thenReturn(regime);
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(r));
    }

    private static TaxableEarnings.Year year(String monthlyGross) {
        return new TaxableEarnings.Year(
                List.of(
                        new TaxableEarnings.Month(
                                "202610",
                                false,
                                new BigDecimal(monthlyGross),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                List.of())));
    }

    @Test
    void BR_TAX_014_onlyTheApprovedRoundTheApprovedLinesAndSectionsOfTheRegimeCount() {
        approvedRegime(Regime.OLD);
        ItDeclarationSubmission waiting = mock(ItDeclarationSubmission.class);
        when(waiting.status()).thenReturn(ItDeclarationSubmission.Status.SUBMITTED);
        ItDeclarationSubmission approved = mock(ItDeclarationSubmission.class);
        when(approved.status()).thenReturn(ItDeclarationSubmission.Status.APPROVED);
        when(approved.getSubmissionId()).thenReturn(5L);
        when(submissions.findByEmployeeIdAndFinYearOrderBySubmissionNoDesc(9L, YEAR))
                .thenReturn(List.of(waiting, approved));
        ItDeclaration verified = line("80C", "APPROVED", "120000", "100000");
        ItDeclaration unverified = line("80D", "APPROVED", null, "30000");
        ItDeclaration reversed = line("80C", "REVERSED", "50000", "50000");
        ItDeclaration newOnly = line("NEWX", "APPROVED", "1000", "1000");
        ItDeclaration unknown = line("ZZZ", "APPROVED", "1000", "1000");
        when(lines.findBySubmissionIdOrderByDeclarationId(5L))
                .thenReturn(List.of(verified, unverified, reversed, newOnly, unknown));
        when(sections.inForceOn(any()))
                .thenReturn(
                        List.of(
                                ItSection.approved(
                                        "80C",
                                        "LIC",
                                        new BigDecimal("150000"),
                                        "OLD",
                                        LocalDate.of(2026, 4, 1)),
                                ItSection.approved(
                                        "80D",
                                        "Health",
                                        new BigDecimal("25000"),
                                        "BOTH",
                                        LocalDate.of(2026, 4, 1)),
                                ItSection.approved(
                                        "NEWX",
                                        "New only",
                                        null,
                                        "NEW",
                                        LocalDate.of(2026, 4, 1))));
        SavingsDeclaration external =
                savingsRow(SavingsDeclaration.Type.EXTERNAL, "80C", "40000", null);
        SavingsDeclaration noSection =
                savingsRow(SavingsDeclaration.Type.EXTERNAL, null, "40000", null);
        SavingsDeclaration rebate =
                savingsRow(SavingsDeclaration.Type.TRANSPORT_REBATE, null, null, "1600");
        SavingsDeclaration emptyRebate =
                savingsRow(SavingsDeclaration.Type.TRANSPORT_REBATE, null, null, null);
        when(savings.findByEmployeeIdAndFinYearOrderBySavingsId(9L, YEAR))
                .thenReturn(List.of(external, noSection, rebate, emptyRebate));
        ExternalIncomeDeclaration honorarium =
                income(
                        ExternalIncomeDeclaration.Status.APPROVED,
                        ExternalIncomeDeclaration.Type.OTHER_INCOME);
        ExternalIncomeDeclaration saved =
                income(
                        ExternalIncomeDeclaration.Status.MAKER_SAVED,
                        ExternalIncomeDeclaration.Type.OTHER_INCOME);
        ExternalIncomeDeclaration externalEntry =
                income(
                        ExternalIncomeDeclaration.Status.APPROVED,
                        ExternalIncomeDeclaration.Type.EXTERNAL_SAVINGS);
        when(entries.findByEmployeeIdAndFinYearOrderByExtIncomeIdDesc(9L, YEAR))
                .thenReturn(List.of(honorarium, saved, externalEntry));

        TaxComputationService.Inputs in = service.inputs(9L, YEAR, LocalDate.of(2026, 10, 25));
        assertThat(in.regime()).isEqualTo(Regime.OLD);
        // 80C: the verified 1,20,000 (not the allowed 1,00,000) + external 40,000, capped at
        // 1,50,000; 80D: the
        // allowed 30,000 (not verified) capped at 25,000; a NEW-only or unknown section never.
        assertThat(in.deductions())
                .extracting(TaxComputationService.DeductionView::sectionCode)
                .containsExactly("80C", "80D");
        assertThat(in.deductions().get(0).allowed()).isEqualByComparingTo("150000");
        assertThat(in.deductions().get(1).allowed()).isEqualByComparingTo("25000");
        assertThat(in.transportRebate()).isEqualByComparingTo("1600");
        assertThat(in.otherIncome()).hasSize(1);
        assertThat(in.totalDeductions()).isEqualByComparingTo("176600");
        assertThat(in.totalOtherIncome()).isEqualByComparingTo("5000");
        assertThat(in.otherSourceTds()).isEqualByComparingTo("500");
    }

    @Test
    void BR_TAX_014_aRegimeNotApprovedAndARoundNotApprovedCountNothing() {
        ItRegimeDetail submitted = mock(ItRegimeDetail.class);
        when(submitted.getVerifyStatus()).thenReturn("SUBMITTED");
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(submitted));
        when(employees.panStatus(9L)).thenReturn(Optional.empty());
        TaxComputationService.Inputs in = service.inputs(9L, YEAR, LocalDate.of(2026, 10, 25));
        assertThat(in.regime()).isNull();
        assertThat(in.pan()).isEqualTo(PanStatus.MISSING);
        assertThat(in.deductions()).isEmpty();
    }

    @Test
    void SRS_7_4_thePtIsTheBandOfTheOfficesStateForTheMonthsGross() {
        approvedRegime(Regime.NEW);
        when(office.getStateCode()).thenReturn("MH");
        PtState mh = PtState.of("MH");
        mh.apply("Maharashtra", true, "R1", "PT.SLAB", "MONTHLY", LocalDate.of(2026, 4, 1), null);
        when(states.findAllByOrderByStateCodeAsc()).thenReturn(List.of(mh));
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("20000")));
        doResolve(
                "PT.SLAB",
                resolved(
                        "PT.SLAB",
                        null,
                        List.of(
                                band(1, "0", "15000", "150", "MH"),
                                band(2, "15000", null, "200", "MH"),
                                band(3, "0", null, "999", "WB"))));
        TaxDeductions.Monthly m = service.monthly(9L, YearMonth.of(2026, 10), "it");
        assertThat(m.professionalTax())
                .hasValueSatisfying(pt -> assertThat(pt).isEqualByComparingTo("200"));
        verify(declarations).reverseUnprovedLines(YEAR, "it");
        // The slab tax is not configured: TDS is not computed and the reason is named.
        assertThat(m.tds()).isEmpty();
        assertThat(m.reasons()).contains(TaxErrorCode.RATE_NOT_CONFIGURED.code() + ":TAX.SLAB.NEW");
    }

    @Test
    void SRS_7_4_ptNotConfiguredNoStateOrNoEarningsForTheMonthAreNamedNeverAssumed() {
        approvedRegime(Regime.OLD);
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("20000")));
        // No state on the office.
        assertThat(service.monthly(9L, YearMonth.of(2026, 10), "it").reasons())
                .contains(
                        TaxErrorCode.RATE_NOT_CONFIGURED.code()
                                + ":pt_state_master (no state on the office)");
        // A state whose PT slab has no band for it.
        when(office.getStateCode()).thenReturn("MH");
        PtState mh = PtState.of("MH");
        mh.apply("Maharashtra", true, "R1", "PT.SLAB", "MONTHLY", LocalDate.of(2026, 4, 1), null);
        when(states.findAllByOrderByStateCodeAsc()).thenReturn(List.of(mh));
        doResolve("PT.SLAB", resolved("PT.SLAB", null, List.of(band(1, "0", null, "999", "WB"))));
        assertThat(service.monthly(9L, YearMonth.of(2026, 10), "it").reasons())
                .contains(TaxErrorCode.RATE_NOT_CONFIGURED.code() + ":PT.SLAB");
        // No earnings for the month asked.
        assertThat(service.monthly(9L, YearMonth.of(2026, 11), "it").reasons())
                .contains(TaxErrorCode.EARNINGS_NOT_AVAILABLE.code());
        // An unknown member.
        assertThatThrownBy(() -> service.monthly(77L, YearMonth.of(2026, 10), "it"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_7_12_theRatesOfAYearAreTodaysWhileItRunsElseItsFirstOrLastDay() {
        // An approved regime and earnings in every year, so each forecast reads its rates.
        ItRegimeDetail r = mock(ItRegimeDetail.class);
        when(r.getVerifyStatus()).thenReturn("APPROVED");
        when(r.getRegime()).thenReturn(Regime.OLD);
        when(regimes.findByEmployeeIdAndFinYear(eq(9L), anyString())).thenReturn(Optional.of(r));
        when(earnings.year(eq(9L), anyString())).thenReturn(Optional.of(year("10000")));
        service.forecast(context(), "870001", null);
        verify(rules).snapshot(LocalDate.of(2026, 10, 25));
        service.forecast(context(), "870001", "2020-21");
        verify(rules).snapshot(LocalDate.of(2021, 3, 31));
        service.forecast(context(), "870001", "2030-31");
        verify(rules).snapshot(LocalDate.of(2030, 4, 1));
        assertThatThrownBy(() -> service.forecast(context(), "999999", YEAR))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_7_26_aSlabWithoutABandForTheRegimeIsNotConfigured() {
        approvedRegime(Regime.OLD);
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("10000")));
        doResolve(
                "TAX.SLAB.OLD",
                resolved("TAX.SLAB.OLD", null, List.of(band(1, "0", null, "5", "QUALIFIED"))));
        assertThat(service.forecast(member, YEAR, LocalDate.of(2026, 10, 25)).reasons())
                .containsExactly(TaxErrorCode.RATE_NOT_CONFIGURED.code() + ":TAX.SLAB.OLD");
    }

    /**
     * SRS-7-13 (l.2471, "no valid Permanent Account Number", BMAD TR-020): a PAN on record but of
     * the wrong structure (INVALID) is taxed like a missing one: the higher prescribed rate in the
     * forecast and the month, payroll goes on, and the PAN is raised on X-05 naming it invalid.
     */
    @Test
    void SRS_7_13_anInvalidPanIsTaxedAtTheHigherRateAndRaisedOnX05() {
        approvedRegime(Regime.OLD);
        when(employees.panStatus(9L)).thenReturn(Optional.of(PanStatus.INVALID));
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("50000")));
        doResolve(
                "TAX.SLAB.OLD",
                resolved("TAX.SLAB.OLD", null, List.of(band(1, "0", null, "10", null))));
        doResolve(
                "TAX.SURCHARGE",
                resolved("TAX.SURCHARGE", null, List.of(band(1, "0", null, "0", null))));
        doResolve("TAX.CESS", resolved("TAX.CESS", BigDecimal.ZERO, List.of()));
        doResolve("TAX.NOPAN.RATE", resolved("TAX.NOPAN.RATE", new BigDecimal("20"), List.of()));

        TaxComputationService.Forecast f = service.forecast(context(), "870001", YEAR);
        assertThat(f.inputs().pan()).isEqualTo(PanStatus.INVALID);
        // The higher of 10 % slab tax (5,000) and the 20 % no-PAN rate on 50,000.
        assertThat(f.result().orElseThrow().higherRateApplied()).isTrue();
        assertThat(f.result().orElseThrow().annualTax()).isEqualByComparingTo("10000");

        TaxDeductions.Monthly m = service.monthly(9L, YearMonth.of(2026, 10), "it");
        assertThat(m.higherRateApplied()).isTrue();
        assertThat(m.tds()).isPresent();
        ArgumentCaptor<ExceptionWorklist.NewEntry> raised = ArgumentCaptor.captor();
        verify(worklist, atLeastOnce()).raise(eq("it"), raised.capture());
        assertThat(raised.getAllValues())
                .filteredOn(e -> "PAN_MISSING".equals(e.exceptionCode()))
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.employeeId()).isEqualTo(9L);
                            assertThat(e.fieldName()).isEqualTo("pan_number");
                            assertThat(e.fieldSource()).isEqualTo("HRMS");
                            assertThat(e.detail()).startsWith("PAN invalid:");
                        });
    }

    // OPEN-QUESTION: CLR-137 (BMAD TR-021): SRS-7-19 flags "any mismatch"; the source does not say
    // whether the year-end difference is first rounded to the rupee (a difference below 0.50 is
    // then no mismatch). Built behaviour pinned until FCI answers; no boundary is added.
    @Test
    void SRS_7_19_aMismatchIsTheDifferenceRoundedToTheRupee() {
        TaxComputationService.Forecast f =
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
                        Optional.empty(),
                        Optional.of(new BigDecimal("-0.60")),
                        List.of());
        assertThat(TaxComputationService.mismatchOf(f))
                .hasValueSatisfying(d -> assertThat(d).isEqualByComparingTo("-1"));
        service.raiseYearEndMismatch("it", member, YEAR, BigDecimal.ONE, "C-11");
        verify(worklist).raise(eq("it"), any());
    }

    // US-GAP-184 (SRS-7-27 "update the forecast as soon as a transaction is approved"): the
    // forecast is computed on every read from what is approved at that moment, never stored, so an
    // I-18 entry counts from the read after its approval; US-GAP-188: its TDS from other sources
    // counts against the tax still due. SRS-7-11 (l.2469, BMAD TR-019): the approved honorarium is
    // other income, taxed in the computation.
    @Test
    void SRS_7_11_SRS_7_27_theForecastReflectsAnEntryFromTheReadAfterItsApproval() {
        approvedRegime(Regime.OLD);
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("50000")));
        doResolve(
                "TAX.SLAB.OLD",
                resolved("TAX.SLAB.OLD", null, List.of(band(1, "0", null, "10", null))));
        doResolve(
                "TAX.SURCHARGE",
                resolved("TAX.SURCHARGE", null, List.of(band(1, "0", null, "0", null))));
        doResolve("TAX.CESS", resolved("TAX.CESS", BigDecimal.ZERO, List.of()));
        ExternalIncomeDeclaration honorarium =
                income(
                        ExternalIncomeDeclaration.Status.PENDING_CHECKER,
                        ExternalIncomeDeclaration.Type.OTHER_INCOME);
        when(entries.findByEmployeeIdAndFinYearOrderByExtIncomeIdDesc(9L, YEAR))
                .thenReturn(List.of(honorarium));

        TaxComputationService.Forecast before = service.forecast(context(), "870001", YEAR);
        assertThat(before.result().orElseThrow().annualTax()).isEqualByComparingTo("5000");
        assertThat(before.result().orElseThrow().monthlyTds()).isEqualByComparingTo("5000");

        when(honorarium.status()).thenReturn(ExternalIncomeDeclaration.Status.APPROVED);
        TaxComputationService.Forecast after = service.forecast(context(), "870001", YEAR);
        // 10 % of (50,000 + 5,000 honorarium); the 500 deducted at source is no longer due.
        assertThat(after.result().orElseThrow().annualTax()).isEqualByComparingTo("5500");
        assertThat(after.result().orElseThrow().monthlyTds()).isEqualByComparingTo("5000");
        assertThat(after.inputs().otherSourceTds()).isEqualByComparingTo("500");
    }

    // US-GAP-196 (FRS V0.5 §14 Preconditions l.5545): frozen is approved and locked; a reset one
    // is not, nor is a year without a regime.
    @Test
    void BR_TAX_001_aRegimeIsFrozenOnlyWhenApprovedAndLocked() {
        assertThat(service.regimeFrozen(9L, YEAR)).isFalse();
        ItRegimeDetail r = ItRegimeDetail.elected(9L, YEAR, Regime.NEW, NOW);
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(r));
        assertThat(service.regimeFrozen(9L, YEAR)).isFalse();
        r.approvedAndFrozen(4L);
        assertThat(service.regimeFrozen(9L, YEAR)).isTrue();
        r.reset(5L, "wrong regime");
        assertThat(service.regimeFrozen(9L, YEAR)).isFalse();
        ItRegimeDetail approvedOpen = mock(ItRegimeDetail.class);
        when(approvedOpen.getVerifyStatus()).thenReturn("APPROVED");
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(approvedOpen));
        assertThat(service.regimeFrozen(9L, YEAR)).as("approved but not locked").isFalse();
    }

    // Wave C review (finding 10): the regimes of a return's members are read in chunks of at most
    // 1,000 (Oracle's IN-list limit), never one query per member, with the same frozen rule.
    @Test
    void BR_TAX_001_theFrozenRegimesOfManyMembersAreReadInChunksOfAtMostAThousand() {
        java.util.List<Long> members =
                java.util.stream.LongStream.rangeClosed(1, 2_001).boxed().toList();
        ItRegimeDetail frozen = ItRegimeDetail.elected(7L, YEAR, Regime.NEW, NOW);
        frozen.approvedAndFrozen(4L);
        ItRegimeDetail open = ItRegimeDetail.elected(8L, YEAR, Regime.NEW, NOW);
        when(regimes.findByFinYearAndEmployeeIdIn(eq(YEAR), any()))
                .thenAnswer(
                        i -> {
                            java.util.Collection<Long> chunk = i.getArgument(1);
                            assertThat(chunk).hasSizeLessThanOrEqualTo(1_000);
                            return chunk.contains(7L) ? List.of(frozen, open) : List.of();
                        });
        assertThat(service.frozenRegimes(members, YEAR)).containsExactly(7L);
        verify(regimes, times(3)).findByFinYearAndEmployeeIdIn(eq(YEAR), any());
        assertThat(service.frozenRegimes(List.of(), YEAR)).isEmpty();
    }

    /** BR-TAX-001: in the chunked read too, an approved regime not yet locked is not frozen. */
    @Test
    void BR_TAX_001_anApprovedRegimeNotLockedIsNotFrozenInTheChunkedRead() {
        ItRegimeDetail approvedOpen = mock(ItRegimeDetail.class);
        when(approvedOpen.getVerifyStatus()).thenReturn("APPROVED");
        when(approvedOpen.isLocked()).thenReturn(false);
        when(approvedOpen.getEmployeeId()).thenReturn(6L);
        ItRegimeDetail frozen = ItRegimeDetail.elected(7L, YEAR, Regime.OLD, NOW);
        frozen.approvedAndFrozen(4L);
        when(regimes.findByFinYearAndEmployeeIdIn(eq(YEAR), any()))
                .thenReturn(List.of(approvedOpen, frozen));
        assertThat(service.frozenRegimes(List.of(6L, 7L), YEAR)).containsExactly(7L);
    }

    /** The year asked for, or the current financial year (India's date) when none is given. */
    @Test
    void SRS_7_12_theYearAskedForOrTheCurrentOne() {
        assertThat(service.yearOr("2025-26")).isEqualTo("2025-26");
        assertThat(service.yearOr(null)).isEqualTo(YEAR);
    }

    /**
     * SRS-7-28: a what-if may change only a section in force on the year's rate day (M-18); one in
     * force is varied on the same rules as the approved forecast, which keeps its own inputs.
     */
    @Test
    void SRS_7_28_aWhatIfClaimNeedsASectionInForceAndVariesOnlyTheCopy() {
        when(sections.inForceOn(any()))
                .thenReturn(
                        List.of(
                                ItSection.approved(
                                        "80C",
                                        "LIC",
                                        new BigDecimal("150000"),
                                        "OLD",
                                        LocalDate.of(2026, 4, 1))));
        assertThatThrownBy(
                        () ->
                                service.whatIf(
                                        member,
                                        YEAR,
                                        Regime.OLD,
                                        Map.of("80Z", BigDecimal.TEN),
                                        List.of()))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.WHAT_IF);
                            assertThat(e.getMessageArgs()[0].toString()).startsWith("80Z ");
                        });
        TaxComputationService.Forecast[] both =
                service.whatIf(
                        member,
                        YEAR,
                        Regime.OLD,
                        Map.of("80C", new BigDecimal("200000")),
                        List.of());
        assertThat(both).hasSize(2);
        assertThat(both[0].inputs().regime()).as("no approved regime").isNull();
        assertThat(both[0].inputs().deductions()).isEmpty();
        assertThat(both[1].inputs().regime()).isEqualTo(Regime.OLD);
        assertThat(both[1].inputs().deductions())
                .singleElement()
                .satisfies(d -> assertThat(d.allowed()).isEqualByComparingTo("150000"));
    }

    /** BR-TAX-014: an external saving without its annual amount counts nothing for its section. */
    @Test
    void BR_TAX_014_anExternalSavingWithoutAnAmountCountsNothing() {
        approvedRegime(Regime.OLD);
        when(sections.inForceOn(any()))
                .thenReturn(
                        List.of(
                                ItSection.approved(
                                        "80C",
                                        "LIC",
                                        new BigDecimal("150000"),
                                        "OLD",
                                        LocalDate.of(2026, 4, 1))));
        SavingsDeclaration noAmount =
                savingsRow(SavingsDeclaration.Type.EXTERNAL, "80C", null, null);
        when(savings.findByEmployeeIdAndFinYearOrderBySavingsId(9L, YEAR))
                .thenReturn(List.of(noAmount));
        TaxComputationService.Inputs in = service.inputs(9L, YEAR, LocalDate.of(2026, 10, 25));
        assertThat(in.deductions()).isEmpty();
        assertThat(in.transportRebate()).isEqualByComparingTo("0");
    }

    /**
     * SRS-7-20: without an approved regime nothing is deductible, even for a section that is in the
     * master and claimed; and a regime named by a what-if brings the section back.
     */
    @Test
    void SRS_7_20_aClaimedSectionOfTheMasterCountsNothingWhileNoRegimeIsApproved() {
        ItRegimeDetail submitted = mock(ItRegimeDetail.class);
        when(submitted.getVerifyStatus()).thenReturn("SUBMITTED");
        when(regimes.findByEmployeeIdAndFinYear(9L, YEAR)).thenReturn(Optional.of(submitted));
        Map<String, ItSection> master =
                Map.of(
                        "80C",
                        ItSection.approved(
                                "80C",
                                "LIC",
                                new BigDecimal("150000"),
                                "OLD",
                                LocalDate.of(2026, 4, 1)));
        Map<String, BigDecimal> claim = Map.of("80C", new BigDecimal("40000"));

        TaxComputationService.Inputs none =
                service.inputs(9L, YEAR, master, null, claim, List.of());
        TaxComputationService.Inputs whatIf =
                service.inputs(9L, YEAR, master, Regime.OLD, claim, List.of());
        TaxComputationService.Inputs wrongRegime =
                service.inputs(9L, YEAR, master, Regime.NEW, claim, List.of());

        assertThat(none.regime()).isNull();
        assertThat(none.deductions()).isEmpty();
        assertThat(whatIf.deductions())
                .extracting(TaxComputationService.DeductionView::sectionCode)
                .containsExactly("80C");
        assertThat(whatIf.totalDeductions()).isEqualByComparingTo("40000");
        assertThat(wrongRegime.deductions()).as("80C is an OLD-regime section").isEmpty();
    }

    /** SRS-7-4: an office state that the PT state master does not hold is named, never assumed. */
    @Test
    void SRS_7_4_anOfficeStateMissingFromTheMasterIsNamedAsNotConfigured() {
        approvedRegime(Regime.OLD);
        when(earnings.year(9L, YEAR)).thenReturn(Optional.of(year("20000")));
        when(office.getStateCode()).thenReturn("GA");
        assertThat(service.monthly(9L, YearMonth.of(2026, 10), "it").reasons())
                .contains(TaxErrorCode.RATE_NOT_CONFIGURED.code() + ":pt_state_master GA");
    }

    private void doResolve(String key, Optional<RuleEngine.Resolved> answer) {
        doReturn(answer).when(set).resolve(eq(key), any());
    }

    private static ItDeclaration line(
            String section, String proof, String verified, String allowed) {
        ItDeclaration l = mock(ItDeclaration.class);
        when(l.getSectionCode()).thenReturn(section);
        when(l.getProofStatus()).thenReturn(proof);
        when(l.getVerifiedAmount()).thenReturn(verified == null ? null : new BigDecimal(verified));
        when(l.getAllowedAmount()).thenReturn(new BigDecimal(allowed));
        return l;
    }

    private static SavingsDeclaration savingsRow(
            SavingsDeclaration.Type type, String section, String annual, String monthly) {
        SavingsDeclaration s = mock(SavingsDeclaration.class);
        when(s.type()).thenReturn(type);
        when(s.getSectionCode()).thenReturn(section);
        when(s.getAnnualAmount()).thenReturn(annual == null ? null : new BigDecimal(annual));
        when(s.getMonthlyAmount()).thenReturn(monthly == null ? null : new BigDecimal(monthly));
        return s;
    }

    private static ExternalIncomeDeclaration income(
            ExternalIncomeDeclaration.Status status, ExternalIncomeDeclaration.Type type) {
        ExternalIncomeDeclaration e = mock(ExternalIncomeDeclaration.class);
        when(e.status()).thenReturn(status);
        when(e.type()).thenReturn(type);
        when(e.getIncomeHead()).thenReturn("Honorarium");
        when(e.getAmount()).thenReturn(new BigDecimal("5000"));
        when(e.getTdsOtherSource()).thenReturn(new BigDecimal("500"));
        return e;
    }
}
