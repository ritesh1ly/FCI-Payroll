package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When a staff cycle locks, the NPS records of its members for the month become final (plan 18
 * phase 18b; D18-6; DDL {@code apply_status}, {@code chk_nps_ded_as_received}): a flagged,
 * consistent record is APPLIED with the amount deducted equal to the employee amount received
 * (BR-PEN-002); a member separated before the month is STOPPED_SEPARATION (SRS-9-18). In the lock's
 * transaction; a mismatch cannot remain at this point (the pre-lock check holds the lock).
 */
@Component
public class NpsLockEffect {

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final AuditService audit;

    public NpsLockEffect(JdbcTemplate jdbc, OrgDirectory org, AuditService audit) {
        this.jdbc = jdbc;
        this.org = org;
        this.audit = audit;
    }

    @EventListener
    public void on(CycleLocked locked) {
        YearMonth month = NpsRules.month(locked.payrollYymm()).orElseThrow();
        Date period = Date.valueOf(month.atDay(1));
        List<Object[]> applied = new ArrayList<>();
        List<Object[]> stopped = new ArrayList<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT n.nps_deduction_id, e.location_id, e.separation_date FROM"
                                + " nps_deduction n JOIN employee e ON e.employee_id ="
                                + " n.employee_id WHERE n.payroll_yymm = ? AND n.record_type ="
                                + " 'MONTHLY' AND n.apply_status = 'RECEIVED' AND n.hrms_nps_flag"
                                + " = 'Y' AND n.mismatch_status IN ('CONSISTENT',"
                                + " 'RESOLVED_IN_HRMS')",
                        locked.payrollYymm())) {
            Object location = row.get("LOCATION_ID");
            if (location == null || !inUnit(((Number) location).longValue(), locked.orgUnitId())) {
                continue;
            }
            Object[] args = {locked.cycleInstanceId(), row.get("NPS_DEDUCTION_ID"), period};
            Object separation = row.get("SEPARATION_DATE");
            if (separation instanceof Date d && NpsRules.separatedBefore(d.toLocalDate(), month)) {
                stopped.add(args);
            } else {
                applied.add(args);
            }
        }
        if (applied.isEmpty() && stopped.isEmpty()) {
            return;
        }
        if (!applied.isEmpty()) {
            jdbc.batchUpdate(
                    "UPDATE nps_deduction SET apply_status = 'APPLIED', deducted_amount ="
                            + " employee_amount, cycle_instance_id = ? WHERE nps_deduction_id = ?"
                            + " AND period_date = ?",
                    applied);
        }
        if (!stopped.isEmpty()) {
            jdbc.batchUpdate(
                    "UPDATE nps_deduction SET apply_status = 'STOPPED_SEPARATION',"
                            + " cycle_instance_id = ? WHERE nps_deduction_id = ? AND period_date"
                            + " = ?",
                    stopped);
        }
        audit.record(
                locked.lockedBy(),
                AuditService.Entry.change(
                                NpsIntakeService.ENTITY,
                                locked.payrollYymm() + "|" + locked.orgUnitId(),
                                "apply_status",
                                "RECEIVED",
                                "APPLIED",
                                "Cycle locked: "
                                        + applied.size()
                                        + " NPS records applied, "
                                        + stopped.size()
                                        + " stopped from separation")
                        .module("I-14"));
    }

    private boolean inUnit(long location, long unit) {
        return org.cycleUnitOf(location)
                .map(OrgLocation::getId)
                .filter(id -> id == unit)
                .isPresent();
    }
}
