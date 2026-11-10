package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
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

/**
 * C-02 cycle control (SRS-15-11, SRS-15-28, SRS-15-33; SRS l.1734): the prior period is locked or
 * this is the unit's first cycle (no earlier period counted, whether the count comes back as zero
 * or as no value); a verification that changes no row (a run started meanwhile) is refused; a lock
 * request whose approval item has no status any more is shown without one.
 */
class CycleServiceBranchGapsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final PreRunChecks preRun = mock(PreRunChecks.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final CycleService service =
            new CycleService(
                    db.jdbc,
                    org,
                    mock(Authorization.class),
                    mock(SecondFactor.class),
                    mock(MakerChecker.class),
                    statuses,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    periods,
                    mock(CycleGateService.class),
                    mock(CycleLeases.class),
                    mock(CycleCorrections.class),
                    preRun);

    private static AccessContext user(String login) {
        return new AccessContext(
                3L,
                login,
                "User",
                "s",
                "P",
                300L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of("PAYROLL_MAKER"),
                Set.of(300L));
    }

    CycleServiceBranchGapsTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(unit.getCode()).thenReturn("DO-DEL");
        when(unit.getType()).thenReturn(LocationType.DO);
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
        when(periods.current()).thenReturn(PERIOD);
        when(preRun.evaluate(300L, PERIOD)).thenReturn(List.of());
        db.rows("SELECT calendar_id FROM payroll_calendar", cols(5L));
    }

    private static final String EARLIER = "SELECT COUNT(*) FROM payroll_cycle_instance";

    @Test
    void SRS_15_33_aUnitsFirstCycleNeedsNoPriorLock() {
        db.value(EARLIER, ScriptedJdbc.NULL);
        service.initiate(user("maker@x"), PERIOD);
        db.value(EARLIER, 0);
        service.initiate(user("maker@x"), PERIOD);
        assertThat(db.updates("INSERT INTO payroll_cycle_instance")).hasSize(2);
    }

    @Test
    void SRS_15_33_aSkippedPriorPeriodAfterEarlierOnesRefusesTheInitiation() {
        db.value(EARLIER, 3);
        assertThatThrownBy(() -> service.initiate(user("maker@x"), PERIOD))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.CYCLE_PRIOR_NOT_LOCKED);
                            assertThat(e.getMessageArgs()).containsExactly("2026-09");
                        });
        assertThat(db.updates("INSERT INTO payroll_cycle_instance")).isEmpty();
    }

    @Test
    void SRS_15_28_aVerificationThatChangesNoRowIsRefused() {
        db.rows(
                "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = ?",
                row(
                        "cycle_instance_id",
                        41L,
                        "cycle_status",
                        "COMPUTED",
                        "current_run_id",
                        null,
                        "frozen_by",
                        "maker@x"));
        db.value("SET cycle_status = 'VERIFIED'", 0);
        assertThatThrownBy(() -> service.verify(user("checker@x"), PERIOD))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_STATE);
                            assertThat(e.getMessageArgs()).containsExactly("VERIFIED");
                        });
    }

    @Test
    void SRS_15_11_aLockRequestWhoseItemHasNoStatusIsShownWithoutOne() {
        db.rows(
                        "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = ?",
                        row("cycle_instance_id", 41L, "cycle_status", "VERIFIED"))
                .rows("SELECT approval_id FROM approval_queue", cols(61L));
        when(statuses.of(any())).thenReturn(Map.of());
        CycleService.CycleView view = service.cycle(user("maker@x"), PERIOD);
        assertThat(view.lockApprovalId()).isEqualTo(61L);
        assertThat(view.lockApprovalStatus()).isNull();
    }
}
