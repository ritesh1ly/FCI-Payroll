package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The checker's approval of a C-02 lock, in the decision's transaction: refused while a gate holds
 * the lock (SRS-15-11, {@link CycleGateService}); otherwise the VERIFIED cycle becomes LOCKED
 * (never reopened) and every current ACTUAL result of it, with its component lines, becomes
 * immutable (is_locked; trg_payroll_result_immutable, trg_prc_immutable: BR-AUDIT-005). A rejected
 * or returned request changes nothing. The lock takes the instance's lease for its transaction
 * (SRS-15-28): approving while a computation or another action holds it is refused, naming it.
 */
@Component
public class CycleLockEffect {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CycleGateService gates;
    private final CycleLeases leases;
    private final ApplicationEventPublisher events;

    public CycleLockEffect(
            JdbcTemplate jdbc,
            AuditService audit,
            CycleGateService gates,
            CycleLeases leases,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.gates = gates;
        this.leases = leases;
        this.events = events;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || !decision.concerns(CycleService.ENTITY)) {
            return;
        }
        long cycleId = Long.parseLong(decision.entityKey());
        // SRS-15-11: the gates are evaluated again at the checker's approval; an unmet gate refuses
        // the decision (it rolls back with it).
        gates.requireLockable(cycleId);
        String checker = CycleKeys.truncate(decision.checkerLoginId());
        leases.acquire(cycleId, CycleLeases.Action.LOCK, checker, null);
        int changed =
                jdbc.update(
                        "UPDATE payroll_cycle_instance SET cycle_status = 'LOCKED', locked_by = ?,"
                                + " locked_at = ?, row_version = row_version + 1 WHERE"
                                + " cycle_instance_id = ? AND cycle_status = 'VERIFIED'",
                        checker,
                        Timestamp.from(decision.decidedAt()),
                        cycleId);
        if (changed == 0) {
            throw new IllegalStateException(
                    "Cycle " + cycleId + " is no longer VERIFIED; the lock cannot be applied");
        }
        Map<String, Object> locked =
                jdbc.queryForMap(
                        "SELECT org_unit_id, payroll_yymm FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?",
                        cycleId);
        long unit = ((Number) locked.get("ORG_UNIT_ID")).longValue();
        String yymm = (String) locked.get("PAYROLL_YYMM");
        // Oracle review F6: the partition keys of the instance's results (period_date = the first
        // day of the month, org_unit_id = the instance's unit, as ResultWriter writes them) are
        // bound, so the lock touches one month's partition and one hash subpartition, not every
        // partition of the history (payroll_result has no cycle_instance_id index).
        Date period = Date.valueOf(YearMonth.parse(yymm, YYMM).atDay(1));
        int results =
                jdbc.update(
                        "UPDATE payroll_result SET is_locked = 'Y' WHERE period_date = ? AND"
                                + " org_unit_id = ? AND cycle_instance_id = ? AND run_type ="
                                + " 'ACTUAL' AND result_status = 'CURRENT' AND is_locked = 'N'",
                        period,
                        unit,
                        cycleId);
        jdbc.update(
                "UPDATE payroll_result_component SET is_locked = 'Y' WHERE cycle_instance_id = ?"
                    + " AND period_date = ? AND org_unit_id = ? AND is_locked = 'N' AND"
                    + " payroll_result_id IN (SELECT payroll_result_id FROM payroll_result WHERE"
                    + " period_date = ? AND org_unit_id = ? AND cycle_instance_id = ? AND is_locked"
                    + " = 'Y')",
                cycleId,
                period,
                unit,
                period,
                unit,
                cycleId);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                CycleService.ENTITY,
                                String.valueOf(cycleId),
                                "cycle_status",
                                "VERIFIED",
                                "LOCKED",
                                "Lock approved; " + results + " result(s) locked")
                        .approval(decision.approvalId())
                        .module("C-02"));
        // Plan 18 phase 18b: the modules whose records of the month become final (NPS, D18-6).
        events.publishEvent(new CycleLocked(cycleId, unit, yymm, checker, decision.decidedAt()));
        leases.release(cycleId, checker);
    }
}
