package in.gov.fci.payroll.reporting.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M-12 report definitions on X-04 and the M-12 History button (SRS-20-29, SRS-20-3; SRS-18-9,
 * SRS-G-12): a report is its approved versions, its audit rows found under every version id (the
 * rows of a rejected version included, the version itself not); a report without a version has no
 * history; an unknown key is no record.
 */
class ReportVersionHistoryTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final ReportVersionHistory history = new ReportVersionHistory(db.named);

    private static Map<String, Object> version(
            long id, int no, LocalDate from, LocalDate to, String state, Long approval) {
        return row(
                "report_version_id",
                id,
                "version_no",
                no,
                "effective_from",
                Date.valueOf(from),
                "effective_to",
                to,
                "layout_format",
                "XLSX",
                "period_lock_required",
                "Y",
                "output_store_mode",
                "STORE",
                "approval_state",
                state,
                "is_blocked",
                "N",
                "blocked_reason",
                null,
                "approval_id",
                approval);
    }

    @Test
    void SRS_20_29_aReportIsItsApprovedVersions() {
        db.rows(
                "FROM report_definition d",
                version(
                        5L,
                        1,
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 9, 30),
                        "APPROVED",
                        null),
                version(6L, 2, LocalDate.of(2026, 10, 1), null, "APPROVED", 33L),
                version(7L, 3, LocalDate.of(2027, 4, 1), null, "REJECTED", 34L));
        assertThat(history.entity()).isEqualTo("report_catalogue_version");
        HistoryRecord record = history.find("PAY_REGISTER").orElseThrow();
        assertThat(record.isVersioned()).isTrue();
        assertThat(record.screen()).isEqualTo("M-12");
        assertThat(record.locationId()).isNull();
        assertThat(record.auditKeys()).containsExactly("5", "6", "7");
        assertThat(record.versions()).extracting(v -> v.versionNo()).containsExactly(1, 2);
        assertThat(record.versions().get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(record.versions().get(1).approvalId()).isEqualTo(33L);
        assertThat(record.versions().get(1).values())
                .containsEntry("layout_format", "XLSX")
                .containsEntry("is_blocked", "N")
                .containsEntry("blocked_reason", null);
    }

    @Test
    void SRS_20_29_aReportWithoutAVersionHasNoHistoryAndAnUnknownKeyIsNoRecord() {
        assertThat(history.find("NONE")).isEmpty();
        db.rows("FROM report_definition d", row("report_version_id", null));
        HistoryRecord record = history.find("NEW_REPORT").orElseThrow();
        assertThat(record.versions()).isEmpty();
        assertThat(record.auditKeys()).isEmpty();
    }
}
