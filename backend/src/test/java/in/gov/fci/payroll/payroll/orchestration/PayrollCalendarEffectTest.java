package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-33, SRS M-10 (plan 05 phase 05f): an approved calendar proposal inserts a new period or
 * updates an existing one, auditing each changed date only; an approved run order renumbers the
 * steps in tens, auditing only the steps that moved; anything else does nothing.
 */
class PayrollCalendarEffectTest {

    private final AuditService audit = mock(AuditService.class);
    private final PayrollCalendarService calendar = mock(PayrollCalendarService.class);
    private JdbcTemplate jdbc;
    private PayrollCalendarEffect effect;

    @BeforeEach
    void schema() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:calendareffect;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        jdbc.execute("DROP TABLE IF EXISTS payroll_calendar");
        jdbc.execute("DROP TABLE IF EXISTS cycle_run_order");
        jdbc.execute(
                "CREATE TABLE payroll_calendar (fin_year VARCHAR(9), payroll_yymm VARCHAR(6),"
                        + " period_start_date DATE, period_end_date DATE, input_cutoff_date DATE,"
                        + " compute_by_date DATE, verify_by_date DATE, lock_target_date DATE,"
                        + " disbursement_date DATE, supplementary_from DATE, supplementary_to"
                        + " DATE, mdp_window_from DATE, mdp_window_to DATE, approval_id BIGINT)");
        jdbc.execute(
                "CREATE TABLE cycle_run_order (engine_step_code VARCHAR(40), sequence_no INT)");
        jdbc.update("INSERT INTO cycle_run_order VALUES ('STAFF_PAYROLL', 10)");
        jdbc.update("INSERT INTO cycle_run_order VALUES ('DEPT_LABOUR_WAGES', 20)");
        jdbc.update("INSERT INTO cycle_run_order VALUES ('MONTH_END', NULL)");
        effect =
                new PayrollCalendarEffect(
                        jdbc,
                        calendar,
                        audit,
                        JsonMapper.builder().build(),
                        org.mockito.Mockito.mock(
                                in.gov.fci.payroll.configuration.api.PayrollPeriods.class));
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                71L,
                "M-10",
                entity,
                "k",
                outcome,
                "",
                payload,
                "checker.hq",
                Instant.parse("2026-10-26T06:00:00Z"));
    }

    private static String period(String lockTarget) {
        return "{\"finYear\":\"2027-28\",\"reason\":\"set up\",\"periods\":[{\"month\":\"2027-06\","
                + "\"start\":\"2027-06-01\",\"end\":\"2027-06-30\",\"freezeTarget\":\"2027-06-20\","
                + "\"computeBy\":null,\"lockTarget\":\""
                + lockTarget
                + "\"}]}";
    }

    @Test
    void SRS_15_33_aPeriodIsInsertedThenUpdatedAuditingOnlyTheDatesThatChanged() {
        effect.on(
                decided(
                        PayrollCalendarService.CALENDAR_ENTITY,
                        ApprovalStatus.APPROVED,
                        period("2027-06-28")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_calendar", Integer.class))
                .isOne();
        // start, end, freeze target, lock target: four dates set; the others stay empty.
        verify(audit, times(4)).record(anyString(), any(AuditService.Entry.class));

        effect.on(
                decided(
                        PayrollCalendarService.CALENDAR_ENTITY,
                        ApprovalStatus.APPROVED,
                        period("2027-06-29")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_calendar", Integer.class))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT lock_target_date FROM payroll_calendar",
                                        java.sql.Date.class)
                                .toString())
                .isEqualTo("2027-06-29");
        // Only the lock target changed.
        verify(audit, times(5)).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_15_1_theRunOrderIsRenumberedInTensAuditingOnlyTheStepsThatMoved() {
        effect.on(
                decided(
                        PayrollCalendarService.RUN_ORDER_ENTITY,
                        ApprovalStatus.APPROVED,
                        "{\"reason\":\"reorder\",\"order\":[\"STAFF_PAYROLL\",\"MONTH_END\",\"DEPT_LABOUR_WAGES\"]}"));
        assertThat(
                        jdbc.queryForList(
                                "SELECT engine_step_code FROM cycle_run_order ORDER BY sequence_no",
                                String.class))
                .containsExactly("STAFF_PAYROLL", "MONTH_END", "DEPT_LABOUR_WAGES");
        // STAFF_PAYROLL kept 10; MONTH_END (none) and DEPT_LABOUR_WAGES (20 -> 30) moved.
        verify(audit, times(2)).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_15_33_aRejectionOrAnotherEntityChangesNothing() {
        effect.on(
                decided(
                        PayrollCalendarService.CALENDAR_ENTITY,
                        ApprovalStatus.REJECTED,
                        period("2027-06-28")));
        effect.on(decided("mdp_transaction", ApprovalStatus.APPROVED, "{}"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payroll_calendar", Integer.class))
                .isZero();
        verifyNoInteractions(audit);
    }
}
