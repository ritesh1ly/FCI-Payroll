package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * M-10 records on X-04 and the M-10 History button (SRS-20-29, SRS-20-3; SRS-15-33, SRS-15-1): a
 * calendar period and a run-order step are one row each, their current values named as the audit
 * rows of an approved change name them, found under their own key and the key their proposals were
 * audited under; an unknown period or step is no record.
 */
class PayrollCalendarHistoryTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final PayrollCalendarHistory config = new PayrollCalendarHistory();

    @Test
    void SRS_20_29_aPeriodIsOneRowFoundUnderItsMonthAndItsFinancialYear() {
        RecordHistorySource source = config.calendarPeriodHistory(db.named);
        assertThat(source.entity()).isEqualTo("payroll_calendar");
        assertThat(source.find("202604")).isEmpty();
        db.rows(
                "FROM payroll_calendar",
                row(
                        "fin_year",
                        "2026-27",
                        "payroll_yymm",
                        "202604",
                        "period_start_date",
                        Date.valueOf(LocalDate.of(2026, 4, 1)),
                        "period_end_date",
                        Date.valueOf(LocalDate.of(2026, 4, 30)),
                        "mdp_window_to",
                        null));
        HistoryRecord record = source.find("202604").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-10");
        assertThat(record.locationId()).isNull();
        assertThat(record.auditKeys()).containsExactly("202604", "2026-27");
        assertThat(record.current())
                .hasSize(PayrollCalendarEffect.COLUMNS.length)
                .containsEntry("period_start_date", "2026-04-01")
                .containsEntry("period_end_date", "2026-04-30")
                .containsEntry("mdp_window_to", null)
                .containsEntry("compute_by_date", null);
    }

    @Test
    void SRS_20_29_aRunOrderStepIsOneRowFoundUnderItsCodeAndTheRunOrderKey() {
        RecordHistorySource source = config.runOrderHistory(db.named);
        assertThat(source.entity()).isEqualTo("cycle_run_order");
        assertThat(source.find("COMPUTE")).isEmpty();
        db.rows(
                "FROM cycle_run_order",
                row(
                        "sequence_no", 20,
                        "step_name", "Compute",
                        "depends_on_step", null,
                        "owner_role_code", "PAYROLL_MAKER",
                        "payroll_type", "STAFF",
                        "is_active", "Y"));
        HistoryRecord record = source.find("COMPUTE").orElseThrow();
        assertThat(record.auditKeys()).containsExactly("COMPUTE", "RUN_ORDER");
        assertThat(record.current())
                .containsEntry("sequence_no", "20")
                .containsEntry("depends_on_step", null)
                .containsEntry("is_active", "Y");
    }
}
