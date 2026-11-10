package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.UndoableTransaction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-21-6 (plan 13 D13-4): an arrear event approved but not yet posted may be undone under the
 * controlled undo of module 05 (Maker requests, Checker verifies, Administrator authorises with OTP
 * UNDO_TRANSACTION). The undo marks the event and its approved details REVERSED - never a delete
 * (R-DEL) - so nothing of it is paid; after posting a correction is a new arrear or recovery. The
 * event belongs to the cycle instance of its unit for the month it posts into.
 */
@Component
public class ArrearUndo implements UndoableTransaction {

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final AuditService audit;

    public ArrearUndo(
            JdbcTemplate jdbc, OrgDirectory org, PayrollRecords payroll, AuditService audit) {
        this.jdbc = jdbc;
        this.org = org;
        this.payroll = payroll;
        this.audit = audit;
    }

    @Override
    public String entity() {
        return ArrearRegisterService.ENTITY;
    }

    @Override
    public Optional<Target> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(key.strip());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.event_status, e.scope_location_id, e.posted_to_yymm,"
                            + " e.event_reference FROM arrear_event e WHERE e.arrear_event_id = ?",
                        id);
        if (rows.isEmpty() || !"APPROVED".equals(rows.getFirst().get("EVENT_STATUS"))) {
            return Optional.empty();
        }
        Map<String, Object> e = rows.getFirst();
        Object location = e.get("SCOPE_LOCATION_ID");
        if (location == null) {
            return Optional.empty();
        }
        return org.cycleUnitOf(((Number) location).longValue())
                .map(OrgLocation::getId)
                .flatMap(unit -> payroll.cycle(unit, (String) e.get("POSTED_TO_YYMM")))
                .filter(c -> !c.isLocked())
                .map(
                        c ->
                                new Target(
                                        c.cycleInstanceId(),
                                        "APPROVED",
                                        "Arrear event " + id + ": " + e.get("EVENT_REFERENCE")));
    }

    @Override
    public void undo(String key, String reason, String decidedBy, long approvalId) {
        long id = Long.parseLong(key.strip());
        int changed =
                jdbc.update(
                        "UPDATE arrear_event SET event_status = 'REVERSED' WHERE arrear_event_id ="
                                + " ? AND event_status = 'APPROVED'",
                        id);
        if (changed == 0) {
            throw new IllegalStateException("Arrear event " + id + " is no longer undoable");
        }
        jdbc.update(
                "UPDATE arrear_detail SET arrear_status = 'REVERSED' WHERE arrear_event_id = ? AND"
                        + " arrear_status = 'APPROVED'",
                id);
        audit.record(
                decidedBy,
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(id),
                                "event_status",
                                "APPROVED",
                                "REVERSED",
                                "Controlled undo before posting: " + reason)
                        .approval(approvalId)
                        .module(ArrearRegisterService.SCREEN));
    }
}
