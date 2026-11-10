package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.api.SecondFactor;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C-02 freeze and lease release (SRS-15-28, R-OTP CYCLE_FREEZE_LOCK): a period without a cycle
 * instance is refused as state NONE; with the OTP required, a proof missing its challenge or its
 * code issues a new challenge and is refused (never verified), nothing frozen.
 */
class CycleServiceOtpTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SecondFactor secondFactor = mock(SecondFactor.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final CycleService service =
            new CycleService(
                    jdbc,
                    org,
                    mock(Authorization.class),
                    secondFactor,
                    mock(MakerChecker.class),
                    mock(ApprovalStatuses.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(PayrollPeriods.class),
                    mock(CycleGateService.class),
                    leases,
                    mock(CycleCorrections.class),
                    mock(PreRunChecks.class));

    private final AccessContext maker =
            new AccessContext(
                    3L,
                    "maker.delhi@x",
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
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_15_28_aPeriodWithoutACycleIsNeitherFrozenNorReleased() {
        refused(() -> service.freeze(maker, PERIOD, null), ApiErrorCode.CYCLE_STATE);
        refused(
                () -> service.requestLeaseRelease(maker, PERIOD, "Stuck run"),
                ApiErrorCode.CYCLE_STATE);
        verify(leases, never()).acquire(anyLong(), any(), any(), any());
    }

    @Test
    void R_OTP_aProofWithoutItsChallengeOrCodeIssuesANewChallengeAndFreezesNothing() {
        when(jdbc.queryForList(contains("FROM payroll_cycle_instance"), eq("202610"), eq(300L)))
                .thenReturn(List.of(Map.of("CYCLE_INSTANCE_ID", 41L, "CYCLE_STATUS", "INITIATED")));
        when(secondFactor.required()).thenReturn(true);
        when(secondFactor.issue(
                        eq(3L), eq(OtpGatedAction.CYCLE_FREEZE_LOCK), anyString(), eq("41")))
                .thenReturn(77L);
        refused(
                () -> service.freeze(maker, PERIOD, new OtpProof(5L, null)),
                ApiErrorCode.OTP_REQUIRED);
        refused(
                () -> service.freeze(maker, PERIOD, new OtpProof(null, "482913")),
                ApiErrorCode.OTP_REQUIRED);
        verify(secondFactor, times(2))
                .issue(eq(3L), eq(OtpGatedAction.CYCLE_FREEZE_LOCK), anyString(), eq("41"));
        verify(secondFactor, never()).verify(any(), any(), any(), any());
        verify(leases, never()).acquire(anyLong(), any(), any(), any());
    }
}
