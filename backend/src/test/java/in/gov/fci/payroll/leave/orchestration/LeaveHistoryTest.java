package in.gov.fci.payroll.leave.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The leave masters on X-04 and their History buttons (SRS-20-29, SRS-20-3): an M-13 leave type is
 * one row with the fields its HRMS receipts audit (SRS-LV-13); an M-20 parameter of a leave type or
 * of all types is its confirmed versions, found under every {@code key|effective-from} its
 * proposals were audited under (SRS-LV-14); an unknown key is no record.
 */
class LeaveHistoryTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final LeaveHistory config = new LeaveHistory();

    @Test
    void SRS_20_29_aLeaveTypeIsOneRowWithItsAuditedFields() {
        RecordHistorySource source = config.leaveTypeHistory(db.named);
        assertThat(source.entity()).isEqualTo("leave_type_reference");
        assertThat(source.find("EL")).isEmpty();
        db.rows(
                "FROM leave_type_reference",
                row(
                        "leave_type_name", "Earned Leave",
                        "is_encashable", "Y",
                        "supports_half_day", null));
        HistoryRecord record = source.find("EL").orElseThrow();
        assertThat(record.isVersioned()).isFalse();
        assertThat(record.screen()).isEqualTo("M-13");
        assertThat(record.locationId()).isNull();
        assertThat(record.auditKeys()).containsExactly("EL");
        assertThat(record.current())
                .containsEntry("leave_type_name", "Earned Leave")
                .containsEntry("is_encashable", "Y")
                .containsEntry("supports_half_day", null);
    }

    @Test
    void SRS_LV_14_aParameterIsItsConfirmedVersionsOfItsLeaveType() {
        RecordHistorySource source = config.leaveParameterHistory(db.named);
        assertThat(source.entity()).isEqualTo("leave_pay_parameter");
        assertThat(source.find("ENCASH_MAX_DAYS / EL")).isEmpty();
        db.rows(
                "FROM leave_pay_parameter",
                row(
                        "version_no",
                        1,
                        "numeric_value",
                        new BigDecimal("300.00"),
                        "text_value",
                        null,
                        "effective_from",
                        Date.valueOf(LocalDate.of(2026, 4, 1)),
                        "effective_to",
                        Timestamp.valueOf("2026-09-30 00:00:00"),
                        "is_confirmed",
                        "Y",
                        "approval_id",
                        51L),
                row(
                        "version_no",
                        2,
                        "numeric_value",
                        null,
                        "text_value",
                        "101,102",
                        "effective_from",
                        Date.valueOf(LocalDate.of(2026, 10, 1)),
                        "effective_to",
                        null,
                        "is_confirmed",
                        "N",
                        "approval_id",
                        null));
        HistoryRecord typed = source.find("ENCASH_MAX_DAYS / EL").orElseThrow();
        assertThat(typed.isVersioned()).isTrue();
        assertThat(typed.screen()).isEqualTo("M-20");
        assertThat(typed.auditKeys())
                .containsExactly(
                        "ENCASH_MAX_DAYS / EL|2026-04-01", "ENCASH_MAX_DAYS / EL|2026-10-01");
        assertThat(typed.versions()).hasSize(1);
        assertThat(typed.versions().get(0).effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(typed.versions().get(0).approvalId()).isEqualTo(51L);
        assertThat(typed.versions().get(0).values())
                .containsEntry("numeric_value", "300.00")
                .containsEntry("text_value", null);
        assertThat(source.find("ENCASH_PAY_CODES").orElseThrow().auditKeys())
                .as("a parameter of all leave types")
                .containsExactly("ENCASH_PAY_CODES|2026-04-01", "ENCASH_PAY_CODES|2026-10-01");
    }

    /**
     * SRS-LV-14 (branch gap): a confirmed version without a number (a text parameter) and without
     * an approval (a seeded one) shows both as absent.
     */
    @Test
    void SRS_LV_14_aConfirmedVersionWithoutANumberOrApprovalShowsThemAbsent() {
        RecordHistorySource source = config.leaveParameterHistory(db.named);
        db.rows(
                "FROM leave_pay_parameter",
                row(
                        "version_no",
                        1,
                        "numeric_value",
                        null,
                        "text_value",
                        "CALENDAR_DAYS",
                        "effective_from",
                        Date.valueOf(LocalDate.of(2026, 4, 1)),
                        "effective_to",
                        null,
                        "is_confirmed",
                        "Y",
                        "approval_id",
                        null));
        HistoryRecord record = source.find("LWP_DIVISOR").orElseThrow();
        assertThat(record.versions()).hasSize(1);
        assertThat(record.versions().get(0).approvalId()).isNull();
        assertThat(record.versions().get(0).values())
                .containsEntry("numeric_value", null)
                .containsEntry("text_value", "CALENDAR_DAYS");
    }
}
