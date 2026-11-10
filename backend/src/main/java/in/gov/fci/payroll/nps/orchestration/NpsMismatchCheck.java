package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The pre-lock check NPS_FLAG_AMOUNT_MISMATCH (plan 18 phase 18a; SRS-9-1a, SRS-15-39; DDL {@code
 * pre_payroll_check}, STAFF, BLOCK): the instance cannot lock while a member of its cycle unit has
 * an NPS flag / amount mismatch for the month. Mismatches are few, so the rows of the month are
 * read and each member's cycle unit is resolved as everywhere ({@code OrgDirectory.cycleUnitOf},
 * OD-11).
 */
@Component
public class NpsMismatchCheck implements PreLockCheck {

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;

    public NpsMismatchCheck(JdbcTemplate jdbc, OrgDirectory org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    @Override
    public String ruleKey() {
        return NpsIntakeService.EXCEPTION;
    }

    @Override
    public Outcome evaluate(Cycle cycle) {
        long failures =
                jdbc
                        .queryForList(
                                "SELECT e.location_id FROM nps_deduction n JOIN employee e ON"
                                        + " e.employee_id = n.employee_id WHERE n.payroll_yymm = ?"
                                        + " AND n.record_type = 'MONTHLY' AND n.mismatch_status IN"
                                        + " ('FLAG_WITHOUT_AMOUNT', 'AMOUNT_WITHOUT_FLAG')",
                                Long.class,
                                cycle.payrollYymm())
                        .stream()
                        .filter(
                                location ->
                                        location != null
                                                && org.cycleUnitOf(location)
                                                        .map(OrgLocation::getId)
                                                        .filter(id -> id == cycle.orgUnitId())
                                                        .isPresent())
                        .count();
        return new Outcome(
                failures,
                failures + " NPS flag / amount mismatches to be corrected in HRMS (I-14)");
    }
}
