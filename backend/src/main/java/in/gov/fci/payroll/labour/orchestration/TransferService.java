package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.TransferRules;
import in.gov.fci.payroll.labour.business.WorkSlipRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labour transfer on M-02 (plan 20 phase 20e; SRS-2-6, SRS-2-31; BR-LABM-007 to BR-LABM-009;
 * D20-12, D20-13). Establishment / HR of the sending office moves a handling labour to another gang
 * of the same payroll type - in the depot, another depot or another Divisional Office - from a date
 * (future allowed), once the sending gang's attendance is complete before that date, no work-slip
 * of the labour exists from that date and the receiving site's payroll for the month is not locked;
 * the Payroll Checker approves (route M02_TRANSFER). Approved, the gang membership ends the day
 * before the transfer date, the receiving gang's starts on it, and the labour's depot and office
 * follow; the history stays with the Labour Master record. A Sardar or Mandal is moved with the
 * gang's leadership (M-09), not by transfer (D20-13).
 */
@Service
@Loggable
public class TransferService {

    static final String SCREEN = "M-02";
    static final String ENTITY = "labour_transfer";
    static final String ROUTE = "M02_TRANSFER";

    /**
     * A transfer as entered, with its order (SRS l.4429 "with the order attached"; NWNP l.4937):
     * reference, date and issuing authority, carried to the Checker in the approval payload.
     */
    public record Request(
            Long toGangId,
            LocalDate transferDate,
            String reason,
            String orderReference,
            LocalDate orderDate,
            String issuingAuthority) {

        /** A transfer without its order (refused: ORDER). */
        public Request(Long toGangId, LocalDate transferDate, String reason) {
            this(toGangId, transferDate, reason, null, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final LabourGangs gangs;
    private final Authorization policy;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final LabourPersons persons;
    private final SystemParameters parameters;
    private final AuditService audit;

    public TransferService(
            JdbcTemplate jdbc,
            LabourGangs gangs,
            Authorization policy,
            OrgDirectory org,
            MakerChecker approvals,
            LabourPersons persons,
            SystemParameters parameters,
            AuditService audit) {
        this.jdbc = jdbc;
        this.gangs = gangs;
        this.policy = policy;
        this.org = org;
        this.approvals = approvals;
        this.persons = persons;
        this.parameters = parameters;
        this.audit = audit;
    }

    /** M-02: a transfer of the labour for the Payroll Checker (SRS-2-6). */
    @Transactional
    public SubmittedApproval propose(AccessContext context, long employeeId, Request r) {
        if (r == null || r.toGangId() == null || r.transferDate() == null) {
            throw invalid("ENTRY");
        }
        if (r.reason() == null || r.reason().isBlank() || r.reason().strip().length() > 500) {
            throw invalid("REASON");
        }
        // SRS l.4429 / l.4937: the transfer order; its date is not after today.
        if (r.orderReference() == null
                || r.orderReference().isBlank()
                || r.orderReference().strip().length() > 100
                || r.orderDate() == null
                || r.issuingAuthority() == null
                || r.issuingAuthority().isBlank()
                || r.issuingAuthority().strip().length() > 150) {
            throw invalid("ORDER");
        }
        if (r.orderDate().isAfter(parameters.today())) {
            throw invalid("ORDER_DATE");
        }
        LocalDate date = r.transferDate();
        Map<String, Object> member = membership(employeeId, date);
        LabourGangs.Gang from = gangs.gang(number(member.get("GANG_ID")));
        OrgLocation office = officeOf(from.depotId());
        policy.requireRecordAt(context, SCREEN, "EDIT", office.getId());
        if (!"HANDLING".equals(member.get("GANG_ROLE"))) {
            throw invalid("LEADER");
        }
        LabourGangs.Gang to = gangs.gang(r.toGangId());
        if (!to.activeOn(date) || !to.payrollType().equals(from.payrollType())) {
            throw invalid("TO_GANG");
        }
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_transfer t JOIN approval_queue q ON"
                            + " q.approval_id = t.approval_id WHERE t.employee_id = ? AND q.status"
                            + " = 'PENDING_APPROVAL'",
                        Integer.class,
                        employeeId);
        if (pending != null && pending > 0) {
            throw invalid("PENDING");
        }
        LocalDate today = parameters.today();
        TransferRules.defect(
                        new TransferRules.Facts(
                                date,
                                today,
                                from.id(),
                                to.id(),
                                // SRS-5-14 / D22-1 (OD-87): NWNP pay rests on the work-slip and
                                // NWNP marks no attendance, so its completeness is not asked; the
                                // slip check of the transfer waits for FCI (CLR-112).
                                WorkSlipRules.NWNP.equals(from.payrollType())
                                        || attendanceComplete(
                                                employeeId, from.id(), member, date, today),
                                workSlipAfter(employeeId, from.id(), date),
                                receivingCycle(to.depotId(), date)))
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        // SRS-4-8 (DPS): the work-slip entry is complete before the transfer: no booking-slip of
        // the labour before the date still a draft, with the Depot Manager or returned.
        if (WorkSlipRules.DPS.equals(from.payrollType())) {
            Integer open =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM work_slip s JOIN work_slip_participant p ON"
                                    + " p.work_slip_id = s.work_slip_id AND p.work_slip_date ="
                                    + " s.work_slip_date WHERE p.employee_id = ? AND s.gang_id = ?"
                                    + " AND s.work_slip_date < ? AND s.slip_status IN ('DRAFT',"
                                    + " 'SUBMITTED', 'REJECTED')",
                            Integer.class,
                            employeeId,
                            from.id(),
                            Date.valueOf(date));
            if (open != null && open > 0) {
                throw invalid("WORK_SLIPS_INCOMPLETE");
            }
        }
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO labour_transfer (employee_id, from_gang_id, to_gang_id,"
                                + " from_depot_id, to_depot_id, transfer_date,"
                                + " attendance_complete_flag, target_stage_ok_flag, reason) VALUES"
                                + " (?, ?, ?, ?, ?, ?, 'Y', 'Y', ?)",
                        "labour_transfer_id",
                        employeeId,
                        from.id(),
                        to.id(),
                        from.depotId(),
                        to.depotId(),
                        Date.valueOf(date),
                        r.reason().strip());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "EDIT",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                office.getId(),
                                order(r),
                                date,
                                r.reason().strip()),
                        null);
        jdbc.update(
                "UPDATE labour_transfer SET approval_id = ? WHERE labour_transfer_id = ?",
                item.id(),
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "to_gang",
                                from.code(),
                                to.code(),
                                r.reason().strip())
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(date));
        return item;
    }

    /**
     * The Payroll Checker's decision: approved, the membership moves at the transfer date and the
     * labour's depot and office follow (BR-LABM-009); rejected, nothing changes.
     */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long id = Long.parseLong(decision.entityKey());
        Map<String, Object> t =
                jdbc.queryForMap(
                        "SELECT employee_id, from_gang_id, to_gang_id, to_depot_id, transfer_date"
                                + " FROM labour_transfer WHERE labour_transfer_id = ?",
                        id);
        String outcome = decision.approved() ? "APPROVED" : "REJECTED";
        if (decision.approved()) {
            long employee = number(t.get("EMPLOYEE_ID"));
            LocalDate date =
                    new java.sql.Date(((java.util.Date) t.get("TRANSFER_DATE")).getTime())
                            .toLocalDate();
            jdbc.update(
                    "UPDATE labour_gang_membership SET effective_to = ?, end_reason = 'TRANSFER'"
                            + " WHERE employee_id = ? AND gang_id = ? AND effective_from < ? AND"
                            + " (effective_to IS NULL OR effective_to >= ?)",
                    Date.valueOf(date.minusDays(1)),
                    employee,
                    t.get("FROM_GANG_ID"),
                    Date.valueOf(date),
                    Date.valueOf(date));
            jdbc.update(
                    "INSERT INTO labour_gang_membership (gang_id, employee_id, gang_role,"
                            + " effective_from, approval_id) VALUES (?, ?, 'HANDLING', ?, ?)",
                    t.get("TO_GANG_ID"),
                    employee,
                    Date.valueOf(date),
                    decision.approvalId());
            long depot = number(t.get("TO_DEPOT_ID"));
            OrgLocation office = officeOf(depot);
            jdbc.update(
                    "UPDATE labour_profile SET depot_location_id = ? WHERE employee_id = ?",
                    depot,
                    employee);
            persons.move(employee, depot, depot, office.getZoneId(), null);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "approval_state",
                                "PENDING_APPROVAL",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    /** The labour's gang membership covering the date, or the latest open one. */
    private Map<String, Object> membership(long employeeId, LocalDate date) {
        return jdbc
                .queryForList(
                        "SELECT m.gang_id, m.gang_role, m.effective_from FROM"
                            + " labour_gang_membership m JOIN labour_profile p ON p.employee_id ="
                            + " m.employee_id WHERE m.employee_id = ? AND p.labour_status ="
                            + " 'ACTIVE' AND m.effective_from <= ? AND (m.effective_to IS NULL OR"
                            + " m.effective_to >= ?) ORDER BY m.effective_from DESC",
                        employeeId,
                        Date.valueOf(date),
                        Date.valueOf(date))
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * BR-LABM-008: every day of the month before the transfer date (up to today) is marked in the
     * sending gang.
     */
    private boolean attendanceComplete(
            long employeeId,
            long gangId,
            Map<String, Object> member,
            LocalDate date,
            LocalDate today) {
        LocalDate joined =
                new java.sql.Date(((java.util.Date) member.get("EFFECTIVE_FROM")).getTime())
                        .toLocalDate();
        LocalDate end = date.minusDays(1).isBefore(today) ? date.minusDays(1) : today;
        // The month of the last day checked, not of the transfer date: a transfer on the 1st of
        // next month still needs this month marked up to today (BR-LABM-007; found 2026-10-30).
        LocalDate first = YearMonth.from(end).atDay(1);
        LocalDate start = joined.isAfter(first) ? joined : first;
        if (end.isBefore(start)) {
            return true;
        }
        Integer marked =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM labour_attendance WHERE employee_id = ? AND gang_id ="
                                + " ? AND attendance_date BETWEEN ? AND ?",
                        Integer.class,
                        employeeId,
                        gangId,
                        Date.valueOf(start),
                        Date.valueOf(end));
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        return marked != null && marked >= days;
    }

    /** BR-LABM-008: a live work-slip of the labour in the sending gang on or after the date. */
    private boolean workSlipAfter(long employeeId, long gangId, LocalDate date) {
        Integer slips =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip s JOIN work_slip_participant p ON"
                                + " p.work_slip_id = s.work_slip_id AND p.work_slip_date ="
                                + " s.work_slip_date WHERE p.employee_id = ? AND s.gang_id = ? AND"
                                + " s.work_slip_date >= ? AND s.slip_status NOT IN ('AMENDED',"
                                + " 'CANCELLED')",
                        Integer.class,
                        employeeId,
                        gangId,
                        Date.valueOf(date));
        return slips != null && slips > 0;
    }

    /**
     * BR-LABM-007: the receiving office's cycle state for the month: null when none is started, a
     * state past the open one when any instance of the month has moved on (OD-86), else INITIATED.
     */
    private String receivingCycle(long depotId, LocalDate date) {
        List<String> states =
                jdbc.queryForList(
                        "SELECT cycle_status FROM payroll_cycle_instance WHERE org_unit_id = ? AND"
                                + " payroll_yymm = ?",
                        String.class,
                        officeOf(depotId).getId(),
                        String.format("%d%02d", date.getYear(), date.getMonthValue()));
        return states.stream()
                .filter(s -> !TransferRules.OPEN_CYCLE.contains(s))
                .findFirst()
                .orElse(states.isEmpty() ? null : "INITIATED");
    }

    /** The order of the transfer, as the Checker reads it in the approval payload. */
    private static String order(Request r) {
        return "{\"orderReference\":\""
                + jsonText(r.orderReference().strip())
                + "\",\"orderDate\":\""
                + r.orderDate()
                + "\",\"issuingAuthority\":\""
                + jsonText(r.issuingAuthority().strip())
                + "\"}";
    }

    private static String jsonText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private OrgLocation officeOf(long depotId) {
        return org.cycleUnitOf(depotId).orElseThrow(() -> invalid("DEPOT_OFFICE"));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.TRANSFER, new Object[] {defect}, List.of(), null);
    }
}
