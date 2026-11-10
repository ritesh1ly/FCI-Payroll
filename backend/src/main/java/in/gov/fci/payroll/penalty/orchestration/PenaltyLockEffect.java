package in.gov.fci.payroll.penalty.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.penalty.business.PenaltyRules;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.penalty.business.model.PenaltyRecoveryLine;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When a staff cycle locks, the month's penalty instalments of its unit become final (plan 12 phase
 * 12d; SRS-14-11, D12-8): a member computed in the cycle has the month RECOVERED; a member
 * separated before the month has the order PAUSED_FNF and every instalment still due DEFERRED to
 * Full &amp; Final (module 11 reads the balance); a member not computed in the cycle has the month
 * DEFERRED with that reason, so the balance stays visible. An order with nothing left to recover is
 * COMPLETED (auto-close). In the lock's transaction; audited with the Penalty ID.
 */
@Component
public class PenaltyLockEffect {

    static final String FNF =
            "Separated: the balance passes to Full & Final Settlement (SRS-14-11)";
    static final String NOT_COMPUTED = "Not computed in the month's cycle";

    private static final Set<PenaltyRecoveryLine.Status> DONE =
            EnumSet.of(PenaltyRecoveryLine.Status.RECOVERED, PenaltyRecoveryLine.Status.WAIVED);

    private final PenaltyRepositories.Orders orders;
    private final PenaltyRepositories.Lines lines;
    private final OrgDirectory org;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public PenaltyLockEffect(
            PenaltyRepositories.Orders orders,
            PenaltyRepositories.Lines lines,
            OrgDirectory org,
            AuditService audit,
            JdbcTemplate jdbc) {
        this.orders = orders;
        this.lines = lines;
        this.org = org;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @EventListener
    public void on(CycleLocked locked) {
        YearMonth month = PenaltyRules.month(locked.payrollYymm()).orElseThrow();
        List<Map<String, Object>> due =
                jdbc.queryForList(
                        "SELECT o.order_id, e.location_id, e.separation_date, (SELECT COUNT(*) FROM"
                            + " payroll_result r WHERE r.employee_id = o.employee_id AND"
                            + " r.cycle_instance_id = ? AND r.run_type = 'ACTUAL' AND"
                            + " r.result_status = 'CURRENT') AS computed FROM penalty_order o JOIN"
                            + " employee e ON e.employee_id = o.employee_id WHERE o.order_status IN"
                            + " ('ACTIVE', 'PARTLY_REVOKED') AND EXISTS (SELECT 1 FROM"
                            + " penalty_recovery_schedule s WHERE s.order_id = o.order_id AND"
                            + " s.payroll_yymm = ? AND s.schedule_status = 'PENDING')",
                        locked.cycleInstanceId(),
                        locked.payrollYymm());
        List<String> changed = new ArrayList<>();
        for (Map<String, Object> row : due) {
            Object location = row.get("LOCATION_ID");
            if (location == null || !inUnit(((Number) location).longValue(), locked.orgUnitId())) {
                continue;
            }
            PenaltyOrder order =
                    orders.findById(((Number) row.get("ORDER_ID")).longValue()).orElseThrow();
            List<PenaltyRecoveryLine> schedule =
                    lines.findByOrderIdOrderByPayrollYymm(order.getId());
            if (separatedBefore(row.get("SEPARATION_DATE"), month)) {
                schedule.stream()
                        .filter(l -> l.status() == PenaltyRecoveryLine.Status.PENDING)
                        .forEach(
                                l -> {
                                    l.deferred(FNF);
                                    lines.save(l);
                                });
                order.status(PenaltyOrder.Status.PAUSED_FNF);
            } else {
                boolean computed = ((Number) row.get("COMPUTED")).longValue() > 0;
                for (PenaltyRecoveryLine l : schedule) {
                    if (l.getPayrollYymm().equals(locked.payrollYymm())
                            && l.status() == PenaltyRecoveryLine.Status.PENDING) {
                        if (computed) {
                            l.recovered(l.getScheduledAmount());
                        } else {
                            l.deferred(NOT_COMPUTED);
                        }
                        lines.save(l);
                    }
                }
                if (schedule.stream().allMatch(l -> DONE.contains(l.status()))) {
                    order.status(PenaltyOrder.Status.COMPLETED);
                }
            }
            orders.save(order);
            changed.add(order.getPenaltyRef() + "=" + order.status().name());
        }
        if (!changed.isEmpty()) {
            audit.record(
                    locked.lockedBy(),
                    AuditService.Entry.change(
                                    PenaltyOrderEffect.ORDER_ENTITY,
                                    locked.payrollYymm() + "|" + locked.orgUnitId(),
                                    "recovery",
                                    null,
                                    String.join(", ", changed),
                                    "Cycle locked: penalty instalments of the month recorded")
                            .module(PenaltyOrderService.SCREEN));
        }
    }

    private static boolean separatedBefore(Object separation, YearMonth month) {
        if (separation == null) {
            return false;
        }
        java.util.Date d = (java.util.Date) separation;
        return new Date(d.getTime()).toLocalDate().isBefore(month.atDay(1));
    }

    private boolean inUnit(long location, long unit) {
        return org.cycleUnitOf(location)
                .map(OrgLocation::getId)
                .filter(id -> id == unit)
                .isPresent();
    }
}
