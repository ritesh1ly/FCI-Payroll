package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-31 / BR-CYC-017, SRS-G-21 (plan 05 D-8): the approved un-freeze on an in-memory copy of
 * the tables it touches (without their keys and triggers): the decision's state check, the lease,
 * the {@code cycle_unfreeze} row with the level-2 checker, the run and its results superseded and
 * the cycle back to INITIATED; any other decision does nothing.
 */
class CycleUnfreezeEffectTest {

    private static final long CYCLE = 51L;

    private final CycleLeases leases = mock(CycleLeases.class);
    private final AuditService audit = mock(AuditService.class);
    private JdbcTemplate jdbc;
    private CycleUnfreezeEffect effect;

    @BeforeEach
    void schema() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:cycleunfreeze;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        for (String table :
                new String[] {
                    "payroll_cycle_instance",
                    "approval_queue",
                    "approval_decision",
                    "cycle_unfreeze",
                    "computation_run",
                    "payroll_result"
                }) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute(
                "CREATE TABLE payroll_cycle_instance (cycle_instance_id BIGINT, cycle_status"
                        + " VARCHAR(15), current_run_id BIGINT, org_unit_id BIGINT, payroll_yymm"
                        + " VARCHAR(6), frozen_by VARCHAR(50), frozen_at TIMESTAMP,"
                        + " freeze_otp_challenge_id BIGINT, verified_by VARCHAR(50), verified_at"
                        + " TIMESTAMP, row_version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE approval_queue (approval_id BIGINT, submitted_by VARCHAR(50))");
        jdbc.execute(
                "CREATE TABLE approval_decision (approval_id BIGINT, level_no INT, decided_by"
                        + " VARCHAR(50), decision VARCHAR(10), decided_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE cycle_unfreeze (cycle_instance_id BIGINT, from_status VARCHAR(15),"
                        + " reason VARCHAR(2000), requested_by VARCHAR(50), checked_by VARCHAR(50),"
                        + " authorised_by VARCHAR(50), approval_id BIGINT, otp_challenge_id BIGINT,"
                        + " invalidated_run_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE computation_run (run_id BIGINT, run_status VARCHAR(15), row_version"
                        + " BIGINT DEFAULT 0)");
        jdbc.execute(
                "CREATE TABLE payroll_result (run_id BIGINT, result_status VARCHAR(12),"
                        + " is_locked CHAR(1))");
        jdbc.update("INSERT INTO approval_queue VALUES (90, 'maker.chennai@fci-demo.test')");
        effect = new CycleUnfreezeEffect(jdbc, leases, audit, JsonMapper.builder().build());
    }

    private void cycle(String status, Long run) {
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (cycle_instance_id, cycle_status,"
                        + " current_run_id, org_unit_id, payroll_yymm, frozen_by, verified_by)"
                        + " VALUES (?, ?, ?, 7, '202609', 'maker.chennai', 'ddo.chennai')",
                CYCLE,
                status,
                run);
    }

    private static ApprovalDecided decided(
            ApprovalStatus outcome, String entity, String checker, Long otp) {
        return new ApprovalDecided(
                90L,
                "C-02",
                entity,
                String.valueOf(CYCLE),
                outcome,
                "",
                "{\"cycleInstanceId\":51,\"period\":\"2026-09\",\"unit\":\"CH001\","
                        + "\"fromStatus\":\"COMPUTED\",\"reason\":\"Late order\"}",
                checker,
                Instant.parse("2026-10-25T06:30:00Z"),
                otp);
    }

    @Test
    void SRS_15_31_theApprovalCancelsTheRunsResultsAndTheCycleNeedsAFreshFreeze() {
        cycle("COMPUTED", 31L);
        jdbc.update("INSERT INTO computation_run (run_id, run_status) VALUES (31, 'COMPLETE')");
        jdbc.update(
                "INSERT INTO payroll_result VALUES (31, 'CURRENT', 'N'), (31, 'QUARANTINED', 'N')");
        jdbc.update(
                "INSERT INTO approval_decision VALUES (90, 2, 'ddo.chennai@fci-demo.test',"
                        + " 'APPROVE', CURRENT_TIMESTAMP)");

        effect.on(
                decided(
                        ApprovalStatus.APPROVED,
                        CycleCorrections.UNFREEZE_ENTITY,
                        "pc.rotn@fci-demo.test",
                        5L));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM cycle_unfreeze");
        assertThat(row)
                .containsEntry("FROM_STATUS", "COMPUTED")
                .containsEntry("REASON", "Late order")
                .containsEntry("REQUESTED_BY", "maker.chennai@fci-demo.test")
                .containsEntry("CHECKED_BY", "ddo.chennai@fci-demo.test")
                .containsEntry("AUTHORISED_BY", "pc.rotn@fci-demo.test")
                .containsEntry("APPROVAL_ID", 90L)
                .containsEntry("OTP_CHALLENGE_ID", 5L)
                .containsEntry("INVALIDATED_RUN_ID", 31L);
        assertThat(jdbc.queryForObject("SELECT run_status FROM computation_run", String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT result_status FROM payroll_result ORDER BY result_status",
                                String.class))
                .containsExactly("QUARANTINED", "SUPERSEDED");
        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM payroll_cycle_instance");
        assertThat(after.get("CYCLE_STATUS")).isEqualTo("INITIATED");
        assertThat(after.get("FROZEN_BY")).isNull();
        assertThat(after.get("VERIFIED_BY")).isNull();
        assertThat(after.get("CURRENT_RUN_ID")).isNull();
        verify(leases).acquire(CYCLE, CycleLeases.Action.UNFREEZE, "pc.rotn@fci-demo.test", null);
        verify(leases).release(CYCLE, "pc.rotn@fci-demo.test");
        verify(audit).record(eq("pc.rotn@fci-demo.test"), any(AuditService.Entry.class));
    }

    /**
     * SRS-15-31 (BMAD TR-008): the Payroll Checker checks the un-freeze before the authority
     * approves it. A route with no Checker level (no level-2 approval, or the level-2 decider is
     * the approving authority itself: a two-level route) is refused at the decision; nothing is
     * written and the cycle keeps its status.
     */
    @Test
    void SRS_15_31_anUnfreezeWithoutThePayrollCheckersCheckIsRefused() {
        cycle("FROZEN", null);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "pc.rotn@fci-demo.test",
                                                6L)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.CYCLE_UNFREEZE_UNCHECKED));
        jdbc.update(
                "INSERT INTO approval_decision VALUES (90, 2, 'pc.rotn@fci-demo.test', 'APPROVE',"
                        + " CURRENT_TIMESTAMP)");
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "pc.rotn@fci-demo.test",
                                                6L)))
                .as("the level-2 decider is the authority: a two-level route")
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.CYCLE_UNFREEZE_UNCHECKED));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_unfreeze", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance", String.class))
                .isEqualTo("FROZEN");
    }

    /** A frozen cycle without a run, checked and approved: CHECKED_BY is never empty. */
    @Test
    void SRS_15_31_aCheckedFrozenCycleWithoutARunIsUnfrozenWithItsChecker() {
        cycle("FROZEN", null);
        jdbc.update(
                "INSERT INTO approval_decision VALUES (90, 2, 'ddo.chennai@fci-demo.test',"
                        + " 'APPROVE', CURRENT_TIMESTAMP)");
        String longLogin = "a".repeat(60);
        effect.on(
                decided(ApprovalStatus.APPROVED, CycleCorrections.UNFREEZE_ENTITY, longLogin, 6L));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM cycle_unfreeze");
        assertThat(row.get("CHECKED_BY")).isEqualTo("ddo.chennai@fci-demo.test");
        assertThat(row.get("INVALIDATED_RUN_ID")).isNull();
        assertThat(row.get("AUTHORISED_BY")).isEqualTo("a".repeat(50));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cycle_status FROM payroll_cycle_instance", String.class))
                .isEqualTo("INITIATED");
    }

    @Test
    void SRS_15_10_aLockedOrInitiatedCycleIsNeverUnfrozenAtTheDecision() {
        cycle("LOCKED", 31L);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "pc.rotn",
                                                5L)))
                .isInstanceOf(FciException.class)
                .satisfies(
                        e ->
                                assertThat(((FciException) e).getErrorCode())
                                        .isEqualTo(ApiErrorCode.CYCLE_STATE));
        verifyNoInteractions(leases, audit);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_unfreeze", Integer.class))
                .isZero();
    }

    @Test
    void SRS_15_28_aRunHoldingTheCycleFailsTheDecision() {
        cycle("COMPUTED", 31L);
        doThrow(new FciException(ApiErrorCode.CYCLE_LEASE_HELD))
                .when(leases)
                .acquire(CYCLE, CycleLeases.Action.UNFREEZE, "pc.rotn", null);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "pc.rotn",
                                                5L)))
                .isInstanceOf(FciException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cycle_unfreeze", Integer.class))
                .isZero();
    }

    @Test
    void SRS_15_31_aCycleMovedMeanwhileFailsTheDecision() {
        cycle("COMPUTED", null);
        jdbc.update(
                "INSERT INTO approval_decision VALUES (90, 2, 'ddo.chennai@fci-demo.test',"
                        + " 'APPROVE', CURRENT_TIMESTAMP)");
        // Another transaction moved it between the read and the update (simulated: the row's
        // status changes when the lease is taken).
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            jdbc.update(
                                    "UPDATE payroll_cycle_instance SET cycle_status = 'VERIFIED'");
                            return null;
                        })
                .when(leases)
                .acquire(CYCLE, CycleLeases.Action.UNFREEZE, "pc.rotn", null);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "pc.rotn",
                                                5L)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.CYCLE_STATE));
    }

    @Test
    void SRS_G_21_aRouteWithoutTheDecidersOtpAndOtherDecisionsDoNothingOrFail() {
        cycle("COMPUTED", null);
        effect.on(decided(ApprovalStatus.REJECTED, CycleCorrections.UNFREEZE_ENTITY, "x", 5L));
        effect.on(decided(ApprovalStatus.APPROVED, "cycle_lease", "x", 5L));
        verifyNoInteractions(leases, audit);
        assertThatThrownBy(
                        () ->
                                effect.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                CycleCorrections.UNFREEZE_ENTITY,
                                                "x",
                                                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C02_UNFREEZE");
    }
}
