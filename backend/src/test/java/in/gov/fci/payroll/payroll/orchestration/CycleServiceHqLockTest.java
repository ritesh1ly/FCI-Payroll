package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SRS §6.7 / BR-CYC-004 (review 2026-10-24, A3): Headquarters never locks a unit's payroll. The
 * refusal comes first, before the cycle's state or its gates are read, so it names the rule and
 * touches nothing. No admitted persona holds C-02 LOCK at Headquarters today (no Payroll Maker at
 * HQ, `docs/rbac/ROLE_COVERAGE.md`); the rule guards the day one does.
 */
class CycleServiceHqLockTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final CycleGateService gates = mock(CycleGateService.class);
    private final CycleService service =
            new CycleService(
                    jdbc,
                    org,
                    policy,
                    mock(SecondFactor.class),
                    approvals,
                    mock(ApprovalStatuses.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-24T06:00:00Z"), ZoneOffset.UTC),
                    mock(PayrollPeriods.class),
                    gates,
                    mock(CycleLeases.class),
                    mock(CycleCorrections.class),
                    mock(PreRunChecks.class));

    private final AccessContext hqMaker =
            new AccessContext(
                    1L,
                    "maker.hq@x",
                    "Maker",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_MAKER"),
                    Set.of(1L));

    @Test
    void BR_CYC_004_headquartersNeverLocksAUnitsPayrollAndNothingIsReadFirst() {
        OrgLocation hq = mock(OrgLocation.class);
        when(org.cycleUnitOf(1L)).thenReturn(Optional.of(hq));
        when(policy.isAtHeadquarters(hqMaker)).thenReturn(true);

        assertThatThrownBy(
                        () -> service.requestLock(hqMaker, YearMonth.of(2026, 10), "October", null))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.HQ_MAY_NOT_LOCK));
        verifyNoInteractions(jdbc, approvals, gates);
    }
}
