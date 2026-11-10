package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRunRules.Scope;
import in.gov.fci.payroll.cpf.business.model.CpfInterestFreeze;
import in.gov.fci.payroll.cpf.business.model.CpfInterestRun;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.PfInterestRate;
import in.gov.fci.payroll.cpf.orchestration.InterestRunService.FreezeProposal;
import in.gov.fci.payroll.cpf.orchestration.InterestRunService.RunRequest;
import in.gov.fci.payroll.cpf.orchestration.InterestRunService.RunView;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * C-12 CPF interest runs beyond the happy path (SRS-6-45, SRS-6-59): a run without its reason, a
 * member not on the ledger, the not-yet-calculated report of a member missing from the master, the
 * rate of record when only the RPFC series exists, a run row without its counts, and the freeze
 * approval that applies only to its own decision and refuses a year already frozen.
 */
class InterestRunServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final String YEAR = "2025-26";

    private final CpfRepositories.Headers headers = mock(CpfRepositories.Headers.class);
    private final CpfRepositories.InterestRates rates = mock(CpfRepositories.InterestRates.class);
    private final CpfRepositories.InterestRuns runs = mock(CpfRepositories.InterestRuns.class);
    private final CpfRepositories.InterestFreezes freezes =
            mock(CpfRepositories.InterestFreezes.class);
    private final CpfZones zones = mock(CpfZones.class);
    private final EmployeeDirectory directory = mock(EmployeeDirectory.class);
    private final InterestLedgers ledgers = mock(InterestLedgers.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private final InterestRunService service =
            new InterestRunService(
                    headers,
                    rates,
                    runs,
                    freezes,
                    zones,
                    directory,
                    ledgers,
                    mock(MakerChecker.class),
                    mock(ApprovalStatuses.class),
                    policy,
                    audit,
                    json,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext manager =
            new AccessContext(
                    3L,
                    "cpf.manager.north",
                    "Manager",
                    "s",
                    "P",
                    30L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(30L));

    InterestRunServiceEdgeTest() {
        when(zones.of(30L)).thenReturn(new CpfZones.Zone(1L, "NA11"));
        when(freezes.findByFinYearAndZoneId(YEAR, 1L)).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(directory.cpfIdentities(any())).thenReturn(Map.of());
        when(directory.members(any())).thenReturn(Map.of());
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static EmployeePfHeader ledger(long employeeId, String status) {
        EmployeePfHeader h = mock(EmployeePfHeader.class);
        when(h.getEmployeeId()).thenReturn(employeeId);
        when(h.getMemberStatus()).thenReturn(status);
        return h;
    }

    private void ledgers(EmployeePfHeader... rows) {
        when(headers.findByFinYearAndZoneIdAndFundTypeOrderByEmployeeIdAscPfHeaderIdAsc(
                        eq(YEAR), eq(1L), any()))
                .thenReturn(List.of(rows));
    }

    @Test
    void
            SRS_G_29_anIndividualRunOfAMemberOutsideTheScopeKeepsItsCountsAndHoldsItsMemberAndReason() {
        CpfInterestRun theirs = mock(CpfInterestRun.class);
        when(theirs.getScopeEmployeeId()).thenReturn(7L);
        when(theirs.getRunScope()).thenReturn(Scope.INDIVIDUAL.name());
        when(theirs.getReason()).thenReturn("Case of the member");
        when(theirs.getMembersCalculated()).thenReturn(1L);
        CpfInterestRun ours = mock(CpfInterestRun.class);
        when(ours.getScopeEmployeeId()).thenReturn(8L);
        when(ours.getRunScope()).thenReturn(Scope.INDIVIDUAL.name());
        when(ours.getReason()).thenReturn("Own member");
        CpfInterestRun all = mock(CpfInterestRun.class);
        when(all.getScopeEmployeeId()).thenReturn(null);
        when(all.getRunScope()).thenReturn(Scope.ALL_MEMBERS.name());
        when(all.getReason()).thenReturn("Year end");
        when(ledgers.counts(any(), any(), any(), any()))
                .thenReturn(new InterestLedgers.Counts(3, 1));
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L))
                .thenReturn(List.of(theirs, ours, all));
        when(directory.members(any()))
                .thenReturn(
                        Map.of(
                                7L, new EmployeeDirectory.Member(7L, "700007", "A", 99L, "ACTIVE"),
                                8L,
                                        new EmployeeDirectory.Member(
                                                8L, "800008", "B", 30L, "ACTIVE")));

        List<InterestRunService.RunView> views = service.summary(manager, YEAR).runs();

        assertThat(views.get(0).eis()).isNull();
        assertThat(views.get(0).reason()).isNull();
        assertThat(views.get(0).calculated()).isEqualTo(1);
        assertThat(views.get(1).eis()).isEqualTo("800008");
        assertThat(views.get(1).reason()).isEqualTo("Own member");
        assertThat(views.get(2).reason()).isEqualTo("Year end");
    }

    @Test
    void SRS_6_45_aRunNeedsARequestAndAReasonAndAnIndividualRunAMemberOnTheLedger() {
        refused(() -> service.run(manager, null), CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.run(manager, new RunRequest(YEAR, Scope.ALL_MEMBERS, null, null)),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.run(manager, new RunRequest(YEAR, Scope.ALL_MEMBERS, null, "  ")),
                CommonErrorCode.VALIDATION_FAILED);

        EmployeePfHeader other = ledger(8L, "ACTIVE");
        ledgers(other);
        when(directory.memberByEis("E100"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        9L, "E100", "Asha", 30L, "IN_SERVICE")));
        when(policy.canAt(manager, InterestRunService.SCREEN, "CREATE", 30L)).thenReturn(true);
        refused(
                () ->
                        service.run(
                                manager, new RunRequest(YEAR, Scope.INDIVIDUAL, "E100", "Re-run")),
                CpfErrorCode.NO_LEDGER);
    }

    @Test
    void SRS_6_45_anAllMembersRunTakesTheRpfcRateOfRecordWhenTheTrustHasNone() {
        EmployeePfHeader exMember = ledger(9L, "EX_MEMBER");
        ledgers(exMember);
        PfInterestRate rpfc = mock(PfInterestRate.class);
        when(rpfc.getRateSource()).thenReturn(PfInterestRate.Source.RPFC.name());
        when(rpfc.getInterestRateId()).thenReturn(55L);
        when(rpfc.isIsDeclared()).thenReturn(true);
        when(rates.findByFinYearAndFundTypeOrderByAppliesFromAsc(eq(YEAR), any()))
                .thenReturn(List.of(rpfc));
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L)).thenReturn(List.of());

        RunView view =
                service.run(manager, new RunRequest(YEAR, Scope.ALL_MEMBERS, null, " Year end "));

        assertThat(view.run()).isEqualTo(1);
        assertThat(view.members()).isEqualTo(1);
        // No member is calculated until the interest method is configured (Q-072, D-4).
        assertThat(view.calculated()).isZero();
        assertThat(view.eis()).isNull();
        ArgumentCaptor<CpfInterestRun> saved = ArgumentCaptor.captor();
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("Year end");
    }

    /**
     * SRS-6-45: a zone with no ledger for the year needs no rate; its run names no rate of record
     * (neither a Trust nor an RPFC rate exists) and counts no member.
     */
    @Test
    void SRS_6_45_aRunOverNoLedgerNeedsNoRateAndNamesNoRateOfRecord() {
        ledgers();
        when(rates.findByFinYearAndFundTypeOrderByAppliesFromAsc(eq(YEAR), any()))
                .thenReturn(List.of());
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L)).thenReturn(List.of());

        RunView view =
                service.run(manager, new RunRequest(YEAR, Scope.ALL_MEMBERS, null, "Year end"));

        assertThat(view.members()).isZero();
        ArgumentCaptor<CpfInterestRun> saved = ArgumentCaptor.captor();
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getInterestRateId()).isNull();
    }

    @Test
    void
            SRS_6_59_theReportNamesAMemberMissingFromTheMasterBlankAndARunWithoutCountsCountsNothing() {
        // The report itself is paged in the database (InterestLedgers): a member missing from the
        // master is named blank there (InterestLedgersTest, InterestRunIT).
        assertThat(InterestLedgers.name(null, null, null)).isNull();
        when(ledgers.counts(any(), any(), any(), any()))
                .thenReturn(new InterestLedgers.Counts(0, 0));
        CpfInterestRun bare = mock(CpfInterestRun.class);
        when(bare.getRunNumber()).thenReturn(1);
        // The counts are nullable (DDL cpf_interest_run); a mock would answer 0, not null.
        when(bare.getMembersCalculated()).thenReturn(null);
        when(bare.getMembersNotCalculated()).thenReturn(null);
        when(bare.getMembersInterestStopped()).thenReturn(null);
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L)).thenReturn(List.of(bare));
        RunView row = service.summary(manager, YEAR).runs().get(0);
        assertThat(row.members()).isZero();
        assertThat(row.stopped()).isZero();
    }

    @Test
    void SRS_6_45_aFreezeIsAppliedOnlyForItsOwnApprovedDecisionAndNeverTwice() {
        String payload = json.writeValueAsString(new FreezeProposal(YEAR, 1L, "NA11", 0, 1, 1));
        service.on(decided("C-13", ApprovalStatus.APPROVED, payload));
        service.on(decided("C-12", ApprovalStatus.REJECTED, payload));
        verify(zones, never()).lock(any());

        // A first freeze with no run at all records no run of record.
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L)).thenReturn(List.of());
        when(headers.findByFinYearAndZoneIdAndFundTypeOrderByEmployeeIdAscPfHeaderIdAsc(
                        eq(YEAR), eq(1L), any()))
                .thenReturn(List.of());
        service.on(decided("C-12", ApprovalStatus.APPROVED, payload));
        ArgumentCaptor<CpfInterestFreeze> frozen = ArgumentCaptor.captor();
        verify(freezes).save(frozen.capture());
        assertThat(frozen.getValue().getFrozenBy()).isEqualTo("agm.cpf.north");

        when(freezes.findByFinYearAndZoneId(YEAR, 1L)).thenReturn(Optional.of(frozen.getValue()));
        refused(
                () -> service.on(decided("C-12", ApprovalStatus.APPROVED, payload)),
                CpfErrorCode.INTEREST_FROZEN);
    }

    private static ApprovalDecided decided(String module, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                90L,
                module,
                InterestRunService.FREEZE_ENTITY,
                YEAR + "|NA11",
                outcome,
                null,
                payload,
                "agm.cpf.north",
                NOW,
                null);
    }

    private static PfInterestRate rate(PfInterestRate.Source source, long id) {
        PfInterestRate r = mock(PfInterestRate.class);
        when(r.getRateSource()).thenReturn(source.name());
        when(r.getInterestRateId()).thenReturn(id);
        when(r.isIsDeclared()).thenReturn(true);
        return r;
    }

    /**
     * SRS-6-45: the trust's declared rate is the rate of record when the year has one; members
     * without a CPF code (no identity, none, or blank) are found as such, not calculated (Q-072).
     */
    @Test
    void SRS_6_45_theTrustRateIsOfRecordAndMembersWithoutACpfCodeAreFound() {
        ledgers(
                ledger(1L, "ACTIVE"),
                ledger(2L, "ACTIVE"),
                ledger(3L, "ACTIVE"),
                ledger(4L, "EX_MEMBER"));
        Map<Long, EmployeeDirectory.CpfIdentity> identities = new java.util.HashMap<>();
        identities.put(
                2L,
                new EmployeeDirectory.CpfIdentity(2L, "E2", null, 1L, "CPF", "IN_SERVICE", 30L));
        identities.put(
                3L, new EmployeeDirectory.CpfIdentity(3L, "E3", " ", 1L, "CPF", "IN_SERVICE", 30L));
        identities.put(
                4L,
                new EmployeeDirectory.CpfIdentity(4L, "E4", "NA11/4", 1L, "CPF", "RETIRED", 30L));
        when(directory.cpfIdentities(any())).thenReturn(identities);
        PfInterestRate trust = rate(PfInterestRate.Source.FCI_TRUST, 61L);
        PfInterestRate rpfc = rate(PfInterestRate.Source.RPFC, 55L);
        when(rates.findByFinYearAndFundTypeOrderByAppliesFromAsc(eq(YEAR), any()))
                .thenReturn(List.of(trust, rpfc));
        when(runs.findByFinYearAndZoneIdOrderByRunNumberDesc(YEAR, 1L)).thenReturn(List.of());

        RunView view =
                service.run(manager, new RunRequest(YEAR, Scope.ALL_MEMBERS, null, "Year end"));

        assertThat(view.members()).isEqualTo(4);
        assertThat(view.calculated()).isZero();
        ArgumentCaptor<CpfInterestRun> saved = ArgumentCaptor.captor();
        verify(runs).save(saved.capture());
        assertThat(saved.getValue().getInterestRateId()).isEqualTo(61L);
    }
}
