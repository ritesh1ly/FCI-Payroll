package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.business.CycleCorrectionRules.ReturnScope;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

/**
 * C-02 Checker's return and its withdrawal (SRS-15-30): a computed cycle without a run of record is
 * not returnable; an office outside every cycle unit is not beneath the unit; a return is withdrawn
 * while the cycle was not frozen again after it (a cycle never re-frozen, or frozen before the
 * return, may be restored; one frozen after it may not); a status moved meanwhile refuses the
 * withdrawal rather than restoring over it.
 */
class CycleCorrectionsBranchGapsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);
    private static final Instant RETURNED = Instant.parse("2026-10-20T06:00:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final CycleCorrections corrections =
            new CycleCorrections(
                    db.jdbc,
                    db.named,
                    org,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(ApprovalStatuses.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    leases,
                    mock(ApplicationEventPublisher.class),
                    JsonMapper.builder().build());

    private final AccessContext checker =
            new AccessContext(
                    4L,
                    "checker@x",
                    "Checker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("PAYROLL_CHECKER"),
                    Set.of(300L));

    CycleCorrectionsBranchGapsTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(unit.getCode()).thenReturn("DO-DEL");
        when(org.cycleUnitOf(300L)).thenReturn(Optional.of(unit));
    }

    private void cycle(String status, Long currentRun, Instant frozenAt) {
        db.rows(
                "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm = ?",
                row(
                        "cycle_instance_id",
                        41L,
                        "cycle_status",
                        status,
                        "current_run_id",
                        currentRun,
                        "frozen_at",
                        frozenAt == null ? null : Timestamp.from(frozenAt)));
    }

    private void openReturn() {
        db.rows(
                "FROM cycle_return WHERE return_id = ?",
                row(
                        "run_id",
                        9L,
                        "from_status",
                        "COMPUTED",
                        "return_status",
                        "OPEN",
                        "returned_at",
                        Timestamp.from(RETURNED)));
    }

    private static void refused(Runnable call, ApiErrorCode code, Object arg) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            assertThat(e.getMessageArgs()).containsExactly(arg);
                        });
    }

    @Test
    void SRS_15_30_aComputedCycleWithoutARunOfRecordIsNotReturnable() {
        cycle("COMPUTED", null, null);
        refused(
                () ->
                        corrections.returnCycle(
                                checker,
                                PERIOD,
                                new CycleCorrections.ReturnRequest(
                                        ReturnScope.INSTANCE, null, null, "Recheck")),
                ApiErrorCode.CYCLE_STATE,
                "COMPUTED");
    }

    @Test
    void SRS_15_30_anOfficeOutsideEveryCycleUnitIsNotBeneathTheUnit() {
        cycle("COMPUTED", 9L, null);
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(500L);
        when(org.byCode("DO-SUB")).thenReturn(Optional.of(office));
        when(org.cycleUnitOf(500L)).thenReturn(Optional.empty());
        refused(
                () ->
                        corrections.returnCycle(
                                checker,
                                PERIOD,
                                new CycleCorrections.ReturnRequest(
                                        ReturnScope.ORG_UNIT, " DO-SUB ", null, "Recheck")),
                ApiErrorCode.CYCLE_RETURN_SCOPE,
                "ORG_UNIT_NOT_BENEATH");
    }

    @Test
    void SRS_15_30_aCycleFrozenAgainAfterTheReturnKeepsIt() {
        cycle("FROZEN", null, RETURNED.plusSeconds(60));
        openReturn();
        refused(
                () -> corrections.withdrawReturn(checker, PERIOD, 7L, "Raised in error"),
                ApiErrorCode.CYCLE_RETURN_CLOSED,
                "UNFROZEN");
    }

    @Test
    void SRS_15_30_aCycleFrozenBeforeTheReturnIsRestored() {
        cycle("FROZEN", null, RETURNED.minusSeconds(3600));
        openReturn();
        assertThatCode(() -> corrections.withdrawReturn(checker, PERIOD, 7L, "Raised in error"))
                .doesNotThrowAnyException();
        assertThat(db.updates("UPDATE payroll_cycle_instance SET cycle_status").getLast().args())
                .containsExactly("COMPUTED", 9L, 41L, "FROZEN");
    }

    @Test
    void SRS_15_30_aStatusMovedMeanwhileRefusesTheWithdrawal() {
        cycle("FROZEN", null, null);
        openReturn();
        db.value("UPDATE payroll_cycle_instance SET cycle_status", 0);
        refused(
                () -> corrections.withdrawReturn(checker, PERIOD, 7L, "Raised in error"),
                ApiErrorCode.CYCLE_STATE,
                "FROZEN");
    }
}
