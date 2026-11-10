package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.settlement.business.SettlementRules;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * Opens the settlement case of an approved separation (plan 11 phase 11a; SRS-10-19, SRS-10-8,
 * SRS-10-27; D11-1, D11-4), in the decision's transaction: one case per member and separation date
 * (a second event for the same separation opens nothing), with its case reference, separation type
 * and date as HRMS sent them (the value of the I-21 SEPARATION task; the event type for an event
 * recorded without one), and every clearance raised at once (stage 2, Clearances raised).
 */
@Component
public class SettlementCaseOpener {

    static final String ENTITY = "settlement_case";
    static final String SCREEN = "O-05";

    private final JdbcTemplate jdbc;
    private final SettlementClearances clearances;
    private final AuditService audit;
    private final Clock clock;

    public SettlementCaseOpener(
            JdbcTemplate jdbc, SettlementClearances clearances, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.clearances = clearances;
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener
    public void on(EmployeeEvents.EmployeeSeparated event) {
        Long open =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_case WHERE employee_id = ? AND"
                                + " separation_date = ?",
                        Long.class,
                        event.employeeId(),
                        Date.valueOf(event.eventDate()));
        if (RowCounts.of(open) > 0) {
            return;
        }
        String type = separationType(event);
        // SRS-10-19: a labour separation is entered in Payroll (Labour Master); labour are not in
        // HRMS.
        String source = labour(event.employeeId()) ? "ENT" : "HRMS";
        Timestamp now = Timestamp.from(clock.instant());
        int year = now.toInstant().atZone(ZoneId.of("Asia/Kolkata")).getYear();
        // A provisional unique reference, replaced by the one built on the generated id (no
        // count-based number that two concurrent separations could both take).
        String provisional = "FNF-" + java.util.UUID.randomUUID().toString().substring(0, 24);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO settlement_case (case_reference, employee_id,"
                                        + " separation_type, separation_reason, separation_date,"
                                        + " separation_source, case_stage, opened_at) VALUES (?, ?,"
                                        + " ?, ?, ?, ?, ?, ?)",
                                    new String[] {"settlement_case_id"});
                    ps.setString(1, provisional);
                    ps.setLong(2, event.employeeId());
                    ps.setString(3, type);
                    ps.setString(4, trim(event.reason(), 40));
                    ps.setDate(5, Date.valueOf(event.eventDate()));
                    ps.setString(6, source);
                    ps.setInt(7, Stage.CLEARANCES_RAISED.number());
                    ps.setTimestamp(8, now);
                    return ps;
                },
                key);
        long caseId = GeneratedKeys.id(key);
        String reference = SettlementRules.caseReference(year, caseId);
        jdbc.update(
                "UPDATE settlement_case SET case_reference = ? WHERE settlement_case_id = ?",
                reference,
                caseId);
        for (String clearance : SettlementRules.CLEARANCES.stream().sorted().toList()) {
            jdbc.update(
                    "INSERT INTO settlement_clearance (settlement_case_id, clearance_type,"
                            + " raised_at, clearance_status) VALUES (?, ?, ?, 'RAISED')",
                    caseId,
                    clearance,
                    now);
        }
        // The settlement of the case (DDL final_settlement, INITIATED): the recoveries, ex-gratia
        // and amounts of the computation attach to it.
        jdbc.update(
                "INSERT INTO final_settlement (settlement_case_id, employee_id, separation_type,"
                        + " separation_date, separation_source) VALUES (?, ?, ?, ?, ?)",
                caseId,
                event.employeeId(),
                type,
                Date.valueOf(event.eventDate()),
                source);
        clearances.refresh(caseId, event.employeeId());
        audit.record(
                event.decidedBy(),
                AuditService.Entry.change(
                                ENTITY,
                                reference,
                                "case_stage",
                                null,
                                Stage.CLEARANCES_RAISED.name(),
                                "Settlement case opened on the separation of "
                                        + event.eventDate()
                                        + " ("
                                        + type
                                        + "); clearances raised")
                        .effective(event.eventDate())
                        .module(SCREEN));
    }

    /** SRS-10-19: a labour person (emp_lbr_flag Y), whose separation Payroll enters. */
    private boolean labour(long employeeId) {
        return "Y"
                .equals(
                        jdbc.queryForObject(
                                "SELECT emp_lbr_flag FROM employee WHERE employee_id = ?",
                                String.class,
                                employeeId));
    }

    /**
     * The separation type as HRMS sent it: the value of the I-21 SEPARATION task that applied the
     * event, else the event type (an I-17 dismissal recorded before I-21 carried events, or a
     * labour separation entered on M-02).
     */
    private String separationType(EmployeeEvents.EmployeeSeparated event) {
        if (event.eventId() == null) {
            return trim(event.eventType().strip().toUpperCase(Locale.ROOT), 20);
        }
        List<String> values =
                jdbc.queryForList(
                        "SELECT t.new_value FROM employee_lifecycle_event v JOIN hrms_change_task t"
                            + " ON t.hrms_change_task_id = v.hrms_change_task_id WHERE v.event_id ="
                            + " ?",
                        String.class,
                        event.eventId());
        String value = values.isEmpty() ? null : values.getFirst();
        String type = value == null || value.isBlank() ? event.eventType() : value;
        return trim(type.strip().toUpperCase(Locale.ROOT), 20);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.strip();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
