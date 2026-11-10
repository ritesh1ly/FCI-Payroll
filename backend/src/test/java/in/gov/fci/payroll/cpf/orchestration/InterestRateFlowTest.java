package in.gov.fci.payroll.cpf.orchestration;

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
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-19 (SRS-6-11, BR-CPF-033): the refusals before anything is read, and the Rule Approver's
 * decision applied again under the state it finds (the series or the rate may have moved since the
 * proposal).
 */
class InterestRateFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final String KEY = "2026-27|CPF|FCI_TRUST";

    private final CpfRepositories.InterestRates rates = mock(CpfRepositories.InterestRates.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private InterestRateService service;

    @BeforeEach
    void wire() {
        service =
                new InterestRateService(
                        rates,
                        statuses,
                        approvals,
                        policy,
                        audit,
                        json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(rates.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static String code(Throwable t) {
        return ((FciException) t).getErrorCode().code();
    }

    private static PfInterestRate rate(LocalDate from, LocalDate to, boolean declared) {
        return PfInterestRate.approved(
                "2026-27",
                "CPF",
                PfInterestRate.Source.FCI_TRUST,
                "ACTIVE_AND_DEPUTED",
                new BigDecimal("8.10"),
                from,
                to,
                null,
                null,
                declared,
                1L);
    }

    private ApprovalDecided decision(
            String module, String entity, ApprovalStatus outcome, String payload, String reason) {
        return new ApprovalDecided(
                42L, module, entity, KEY, outcome, reason, payload, "rule.approver", NOW);
    }

    private String proposal(InterestRateService.Kind kind, Long rateId, LocalDate from) {
        return json.writeValueAsString(
                new InterestRateService.RateProposal(
                        kind,
                        rateId,
                        "2026-27",
                        "CPF",
                        "FCI_TRUST",
                        new BigDecimal("8.25"),
                        from,
                        null,
                        null,
                        null,
                        true,
                        "su.hq",
                        NOW));
    }

    @Test
    void SRS_6_11_onlyHeadquartersRecordsOrDeclaresARate() {
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        assertThatThrownBy(() -> service.recordRate(context, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("AUTHZ-403-HQ-ONLY"));
        assertThatThrownBy(() -> service.declare(context, 1L, BigDecimal.TEN, "declared", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("AUTHZ-403-HQ-ONLY"));
        verifyNoInteractions(rates, approvals);
    }

    @Test
    void SRS_6_11_aRequestWithoutABodyIsRefused() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        assertThatThrownBy(() -> service.recordRate(context, null, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("COM-400-VALIDATION"));
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_6_11_anotherScreensDecisionOrAnotherTablesIsNotApplied() {
        String payload = proposal(InterestRateService.Kind.RECORD, null, APRIL);
        service.on(
                decision(
                        "M-08",
                        InterestRateService.ENTITY,
                        ApprovalStatus.APPROVED,
                        payload,
                        null));
        service.on(decision("M-19", "rule_version", ApprovalStatus.APPROVED, payload, null));
        service.on(
                decision(
                        "M-19",
                        InterestRateService.ENTITY,
                        ApprovalStatus.REJECTED,
                        payload,
                        "no"));
        service.on(
                decision(
                        "M-19",
                        InterestRateService.ENTITY,
                        ApprovalStatus.RETURNED,
                        payload,
                        "fix"));
        verifyNoInteractions(rates, audit);
    }

    @Test
    void BR_CPF_033_anApprovedRateEndsOnlyTheOpenRateItReplacesAndIsAuditedWithItsReason() {
        PfInterestRate closed = rate(APRIL.minusYears(1), APRIL.minusDays(1), true);
        PfInterestRate open = rate(APRIL.minusMonths(1), null, true);
        PfInterestRate laterEnd = rate(APRIL.minusMonths(2), APRIL.plusMonths(3), true);
        when(rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                        "2026-27", "CPF", "FCI_TRUST"))
                .thenReturn(List.of(open, laterEnd, closed))
                .thenReturn(List.of(open, laterEnd, closed));
        // Dated after every approved rate of the series.
        String payload = proposal(InterestRateService.Kind.RECORD, null, APRIL.plusMonths(1));
        service.on(
                decision(
                        "M-19", InterestRateService.ENTITY, ApprovalStatus.APPROVED, payload, " "));
        assertThat(open.getAppliesTo()).isEqualTo(APRIL.plusMonths(1).minusDays(1));
        assertThat(laterEnd.getAppliesTo()).isEqualTo(APRIL.plusMonths(1).minusDays(1));
        assertThat(closed.getAppliesTo()).isEqualTo(APRIL.minusDays(1));
        ArgumentCaptor<PfInterestRate> written = ArgumentCaptor.forClass(PfInterestRate.class);
        verify(rates, org.mockito.Mockito.times(3)).save(written.capture());
        assertThat(written.getAllValues().get(2).getApprovalId()).isEqualTo(42L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditService.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(audit).recordAll(eq("rule.approver"), entries.capture());
        // A blank checker reason is recorded as "Approved".
        assertThat(entries.getValue()).isNotEmpty();
        assertThat(entries.getValue().toString()).contains("Approved");
    }

    @Test
    void BR_CPF_033_aRateApprovedAfterTheSeriesMovedIsRefusedNotWritten() {
        when(rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                        "2026-27", "CPF", "FCI_TRUST"))
                .thenReturn(List.of(rate(APRIL.plusMonths(2), null, true)));
        String payload = proposal(InterestRateService.Kind.RECORD, null, APRIL.plusMonths(1));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decision(
                                                "M-19",
                                                InterestRateService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                payload,
                                                "ok")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-409-RATE-ORDER"));
        verify(rates, never()).save(any());
    }

    @Test
    void SRS_6_11_aDeclarationIsAppliedOnceToARateThatStillExists() {
        String payload = proposal(InterestRateService.Kind.DECLARE, 7L, APRIL);
        when(rates.lockById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decision(
                                                "M-19",
                                                InterestRateService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                payload,
                                                null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("COM-404-NOT-FOUND"));
        // Declared by another approval in between: refused, nothing written.
        when(rates.lockById(7L)).thenReturn(Optional.of(rate(APRIL, null, true)));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decision(
                                                "M-19",
                                                InterestRateService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                payload,
                                                null)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-409-DECLARED"));
        verify(rates, never()).save(any());
        // Still undeclared: declared at the declared rate.
        PfInterestRate undeclared = rate(APRIL, null, false);
        when(rates.lockById(7L)).thenReturn(Optional.of(undeclared));
        service.on(
                decision(
                        "M-19",
                        InterestRateService.ENTITY,
                        ApprovalStatus.APPROVED,
                        payload,
                        "ok"));
        assertThat(undeclared.isIsDeclared()).isTrue();
        assertThat(undeclared.getRatePercent()).isEqualByComparingTo("8.25");
    }

    @Test
    void SRS_6_11_aDeclarationOfAnUnknownOrDeclaredRateIsRefusedBeforeItIsSubmitted() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(rates.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.declare(context, 5L, BigDecimal.TEN, "x", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("COM-404-NOT-FOUND"));
        when(rates.findById(6L)).thenReturn(Optional.of(rate(APRIL, null, true)));
        assertThatThrownBy(() -> service.declare(context, 6L, BigDecimal.TEN, "x", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-409-DECLARED"));
        when(rates.findById(8L)).thenReturn(Optional.of(rate(APRIL, null, false)));
        assertThatThrownBy(() -> service.declare(context, 8L, new BigDecimal("100.01"), "x", null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CPF-422-RATE"));
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_20_29_aSeriesKeyResolvesOnlyInItsThreePartForm() {
        when(rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                        anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        InterestRateHistory history = new InterestRateHistory(service);
        assertThat(history.entity()).isEqualTo("pf_interest_rate");
        assertThat(service.seriesOf(null)).isEmpty();
        assertThat(service.seriesOf("2026-27|CPF")).isEmpty();
        assertThat(service.seriesOf("2026-27|CPF|FCI_TRUST|X")).isEmpty();
        assertThat(history.find(KEY)).isEmpty();
        PfInterestRate first = rate(APRIL, APRIL.plusMonths(6).minusDays(1), true);
        PfInterestRate second = rate(APRIL.plusMonths(6), null, true);
        when(rates.findByFinYearAndFundTypeAndRateSourceOrderByAppliesFromDesc(
                        "2026-27", "CPF", "FCI_TRUST"))
                .thenReturn(List.of(second, first));
        var record = history.find(KEY).orElseThrow();
        assertThat(record.screen()).isEqualTo("M-19");
        assertThat(record.versions()).hasSize(2);
        assertThat(record.versions().get(0).effectiveFrom()).isEqualTo(APRIL);
        assertThat(record.versions().get(1).versionNo()).isEqualTo(2);
    }
}
