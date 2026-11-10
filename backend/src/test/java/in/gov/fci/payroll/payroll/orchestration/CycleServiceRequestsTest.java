package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-02 lock request and lease release (SRS-15-11, SRS-15-28): a lock asked without a reason is
 * audited as "Lock requested" under the maker's login cut to its 50-character column; a lease
 * release asked without a reason carries an empty reason in its payload.
 */
class CycleServiceRequestsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);
    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final CycleService service =
            new CycleService(
                    jdbc,
                    org,
                    mock(Authorization.class),
                    mock(SecondFactor.class),
                    approvals,
                    mock(ApprovalStatuses.class),
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(PayrollPeriods.class),
                    mock(CycleGateService.class),
                    leases,
                    mock(CycleCorrections.class),
                    mock(PreRunChecks.class));

    private final AccessContext maker =
            new AccessContext(
                    3L,
                    "m".repeat(60),
                    "Maker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_MAKER"),
                    Set.of(300L));

    @BeforeEach
    void unit() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(unit.getCode()).thenReturn("DO-DEL");
        when(unit.getType()).thenReturn(LocationType.DO);
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
        when(jdbc.queryForList(contains("FROM payroll_cycle_instance"), eq("202610"), eq(300L)))
                .thenReturn(List.of(Map.of("CYCLE_INSTANCE_ID", 41L, "CYCLE_STATUS", "VERIFIED")));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    @Test
    void SRS_15_11_aLockWithoutAReasonIsAuditedAsRequestedUnderTheCutLogin() {
        service.requestLock(maker, PERIOD, null, null);
        verify(audit).record(eq("m".repeat(50)), argThat(e -> "Lock requested".equals(e.reason())));
    }

    @Test
    void SRS_15_28_aLeaseReleaseWithoutAReasonCarriesAnEmptyReason() {
        when(leases.current(41L))
                .thenReturn(
                        Optional.of(
                                new CycleLeases.Lease(
                                        41L, "COMPUTE", "maker@x", NOW, NOW, 9L, true)));
        service.requestLeaseRelease(maker, PERIOD, null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(maker), submitted.capture(), any());
        assertThat(submitted.getValue().payload()).endsWith("\"reason\":\"\"}");
    }
}
