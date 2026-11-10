package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-10: once the Checker approves an off-cycle (I-20) case, the regular run of the salary month
 * does not pay the member again ("exclude the disbursed amount from that cycle"). Only an ACTIVE
 * case, APPROVED, for exactly that month excludes; a pending, cancelled or other-month case does
 * not.
 */
class OffCycleExclusionTest extends BatchTestSupport {

    private static final String MONTH = "202607";

    private long readable(Long employeeId) {
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        employeeId);
        return named.queryForObject(
                "SELECT COUNT(*) FROM employee e "
                        + MemberReader.WHERE
                        + " AND e.employee_id = :employee",
                Map.of(
                        "locations",
                        java.util.List.of(location),
                        "yymm",
                        MONTH,
                        "employee",
                        employeeId),
                Long.class);
    }

    private Long employee(String eis) {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE emp_num = ?", Long.class, eis);
    }

    /** An approval item in {@code status} (the fixture stands in for the Checker's decision). */
    private Long approval(String status, String key) {
        jdbc.update(
                "INSERT INTO approval_queue (entity_name, entity_key, action_type, payload,"
                        + " submitted_by, status) VALUES ('off_cycle_payment', ?, 'CREATE', '{}',"
                        + " 'test.maker', ?)",
                key,
                status);
        return jdbc.queryForObject(
                "SELECT MAX(approval_id) FROM approval_queue WHERE entity_key = ?",
                Long.class,
                key);
    }

    private void offCycle(
            Long employeeId, String recordStatus, String excludedFrom, Long approvalId) {
        jdbc.update(
                "INSERT INTO off_cycle_payment (employee_id, case_type, salary_yymm,"
                    + " processing_yymm, amount, duplicate_check_run, duplicate_check_at,"
                    + " duplicate_check_result, excluded_from_yymm, record_status, invoice_status,"
                    + " cancel_reason, approval_id) VALUES (?, 'LATE_JOINER', ?, '202610', 1000,"
                    + " 'Y', SYSTIMESTAMP, 'PASS', ?, ?, 'NOT_RAISED', ?, ?)",
                employeeId,
                MONTH,
                excludedFrom,
                recordStatus,
                "CANCELLED".equals(recordStatus) ? "fixture" : null,
                approvalId);
    }

    @Test
    void SRS_G_10_anApprovedActiveCaseExcludesTheMemberFromThatMonthsRun() {
        Long member = employee("105220");
        assertThat(readable(member)).isEqualTo(1);
        offCycle(member, "ACTIVE", MONTH, approval("APPROVED", "excl-approved"));
        assertThat(readable(member)).isZero();
    }

    @Test
    void SRS_G_10_pendingCancelledOrOtherMonthCasesDoNotExclude() {
        Long member = employee("104882");
        offCycle(member, "ACTIVE", MONTH, approval("PENDING_APPROVAL", "excl-pending"));
        offCycle(member, "CANCELLED", MONTH, approval("APPROVED", "excl-cancelled"));
        offCycle(member, "ACTIVE", "202606", approval("APPROVED", "excl-other-month"));
        assertThat(readable(member)).isEqualTo(1);
    }

    @Test
    void SRS_G_55_theStaffEngineNeverReadsLabourMembers() {
        Long labour =
                jdbc.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE employee_type <> 'STAFF' AND"
                                + " record_status = 'ACTIVE' AND employment_status = 'IN_SERVICE'",
                        Long.class);
        assertThat(labour).as("a labour member in the test data").isNotNull();
        assertThat(readable(labour)).as("labour have their own engines").isZero();
        Long staff =
                jdbc.queryForObject(
                        "SELECT MIN(e.employee_id) FROM employee e "
                                + MemberReader.WHERE
                                        .replace(":locations", "e.location_id")
                                        .replace(":yymm", "'" + MONTH + "'"),
                        Long.class);
        assertThat(staff).as("an eligible staff member in the test data").isNotNull();
        assertThat(readable(staff)).as("staff are read").isEqualTo(1);
    }
}
