package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.labour.business.AttendanceRules;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.WorkSlipRules;
import in.gov.fci.payroll.labour.business.WorkSlipRules.DuplicateKey;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-02 Work-Slip Capture, Departmental (plan 20 phase 20c; SRS-2-3, SRS-2-4, SRS-2-5, SRS-2-15,
 * SRS-2-29, SRS-2-42, SRS-3-9; BR-AUDIT-006; D20-9). The Labour Dealing Hand of the depot saves a
 * gang's work-slip for a date once the gang's attendance of the date is marked: rows of work by
 * operation (M-27, never free text) with bag type, bags and stack height for bag-based operations
 * (the height band Above-20 / Up-to-20 is the system's), the participating members of the gang
 * (present as their attendance says) and the Labour Strength. A slip that repeats a saved one
 * (operation, norm, slab and time frame) is refused. Saving freezes the gang's attendance of the
 * date (SRS-2-3). A saved slip is never deleted: it is amended (a new slip replacing it, with the
 * reason) or cancelled while its month batch is open. The month's slips go to the Payroll Checker
 * of the Divisional Office as one batch (route I02_WORK_SLIP).
 */
@Service
@Loggable
public class WorkSlipService {

    static final String SCREEN = "I-02";
    static final String ENTITY = "labour_work_slip_batch";
    static final String ROUTE = "I02_WORK_SLIP";

    /** A DPS booking-slip to the Depot Manager (SRS-2-34, D21-3): entity and route. */
    static final String BOOKING_ENTITY = "work_slip";

    static final String BOOKING_ROUTE = "I02_BOOKING_SLIP";
    private static final String DEPT = "DEPT_LABOUR";
    private static final String DPS = WorkSlipRules.DPS;
    private static final String NWNP = WorkSlipRules.NWNP;
    private static final int MAX_ROWS = 20;

    /** One row of work as entered. */
    public record RowEntry(
            Long operationId,
            Long bagTypeId,
            Integer bags,
            Integer stackHeight,
            String start,
            String end,
            String pbnwReason,
            String pbnwText,
            String rakeReference) {}

    /** A work-slip as entered: the date, the rows and the participating members. */
    public record Entry(
            LocalDate date, boolean continueWork, List<RowEntry> rows, List<Long> participants) {}

    /** An operation of the payroll type (M-27). */
    public record Operation(
            long id, String code, String name, boolean bagBased, String normType, boolean pbnw) {}

    /** A bag type. */
    public record BagType(long id, String code, String name, BigDecimal weightKg, String band) {}

    /**
     * One row of a saved slip; the ids and texts let an amendment open with the saved rows
     * (SRS-2-15, SRS-2-4 "never clears data already entered").
     */
    public record SlipRow(
            int order,
            String operation,
            String bagType,
            Integer bags,
            Integer stackHeight,
            String heightBand,
            String start,
            String end,
            String pbnwReason,
            long operationId,
            Long bagTypeId,
            String pbnwText,
            String rakeReference) {}

    /** One participant of a saved slip. */
    public record Participant(
            long employeeId, String labourCode, String name, String role, boolean present) {}

    /**
     * A saved slip. The DPS booking-slip register (l.4669 "Entered By / Approved By", l.4644 "the
     * approver with the date and time", l.4650 a returned slip with its reason) reads {@code
     * enteredBy}, {@code decidedBy} / {@code decidedAt} of the slip's decision and the Depot
     * Manager's {@code returnReason} of a returned one.
     */
    public record Slip(
            long id,
            String number,
            LocalDate date,
            String status,
            boolean continueWork,
            boolean holiday,
            Integer labourStrength,
            Long amendmentOf,
            List<SlipRow> rows,
            List<Participant> participants,
            String enteredBy,
            String decidedBy,
            java.time.Instant decidedAt,
            String returnReason) {}

    /**
     * The I-02 view of a gang's month; {@code returnReason} is the Payroll Checker's reason of a
     * RETURNED month batch (SRS-2-29 l.4381), else null.
     */
    public record Month(
            long gangId,
            String gangName,
            String depot,
            String payrollType,
            String yymm,
            String batchState,
            Long approvalId,
            String returnReason,
            List<Operation> operations,
            List<BagType> bagTypes,
            List<Slip> slips) {}

    private final JdbcTemplate jdbc;
    private final LabourGangs gangs;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final SystemParameters parameters;
    private final AuditService audit;
    private final Clock clock;
    private final ExceptionWorklist worklist;

    public WorkSlipService(
            JdbcTemplate jdbc,
            LabourGangs gangs,
            Authorization policy,
            MakerChecker approvals,
            SystemParameters parameters,
            AuditService audit,
            Clock clock,
            ExceptionWorklist worklist) {
        this.jdbc = jdbc;
        this.gangs = gangs;
        this.policy = policy;
        this.approvals = approvals;
        this.parameters = parameters;
        this.audit = audit;
        this.clock = clock;
        this.worklist = worklist;
    }

    /** I-02: the gang's slips of the month with the operations and bag types to enter more. */
    @Transactional(readOnly = true)
    public Month month(AccessContext context, long gangId, YearMonth month) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "VIEW", g.depotId());
        LocalDate day = AttendanceRules.lastMarkable(month, parameters.today());
        LabourGangs.Batch batch = gangs.batch(ENTITY, gangId, month);
        List<Slip> slips =
                jdbc
                        .queryForList(
                                "SELECT work_slip_id FROM work_slip WHERE gang_id = ? AND"
                                        + " work_slip_date BETWEEN ? AND ? ORDER BY work_slip_date,"
                                        + " work_slip_id",
                                Long.class,
                                gangId,
                                Date.valueOf(month.atDay(1)),
                                Date.valueOf(month.atEndOfMonth()))
                        .stream()
                        .map(this::slip)
                        .toList();
        return new Month(
                gangId,
                g.name(),
                g.depot(),
                g.payrollType(),
                AttendanceRules.yymm(month),
                batch.state().name(),
                batch.approvalId(),
                batch.state() == AttendanceRules.BatchState.RETURNED ? batch.returnReason() : null,
                List.copyOf(
                        operations(
                                        g.payrollType(),
                                        day.isBefore(month.atDay(1)) ? month.atDay(1) : day)
                                .values()),
                bagTypes(day.isBefore(month.atDay(1)) ? month.atDay(1) : day),
                slips);
    }

    /** I-02: a new work-slip of the gang (SRS-2-4). */
    @Transactional
    public Slip save(AccessContext context, long gangId, Entry e) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "CREATE", g.depotId());
        return slip(write(context, g, e, null, null));
    }

    /** SRS-2-15: a saved slip is corrected by a new slip that replaces it, with the reason. */
    @Transactional
    public Slip amend(AccessContext context, long slipId, Entry e, String reason) {
        Map<String, Object> old = saved(slipId);
        LabourGangs.Gang g = gangs.workGang(number(old.get("GANG_ID")));
        policy.requireRecordAt(context, SCREEN, "EDIT", g.depotId());
        requireReason(reason);
        requireEditable(old, g);
        LocalDate date = date(old.get("WORK_SLIP_DATE"));
        if (e == null || !date.equals(e.date())) {
            throw invalid("DATE_CHANGED");
        }
        long replacement = write(context, g, e, slipId, reason.strip());
        jdbc.update("UPDATE work_slip SET slip_status = 'AMENDED' WHERE work_slip_id = ?", slipId);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "work_slip",
                                (String) old.get("WORK_SLIP_NUMBER"),
                                "slip_status",
                                (String) old.get("SLIP_STATUS"),
                                "AMENDED",
                                reason.strip())
                        .module(SCREEN)
                        .effective(date));
        return slip(replacement);
    }

    /** A saved slip is withdrawn, never deleted (BR-AUDIT-006, BR-AUDIT-009). */
    @Transactional
    public Slip cancel(AccessContext context, long slipId, String reason) {
        Map<String, Object> old = saved(slipId);
        LabourGangs.Gang g = gangs.workGang(number(old.get("GANG_ID")));
        policy.requireRecordAt(context, SCREEN, "EDIT", g.depotId());
        requireReason(reason);
        LocalDate date = date(old.get("WORK_SLIP_DATE"));
        requireEditable(old, g);
        jdbc.update(
                "UPDATE work_slip SET slip_status = 'CANCELLED' WHERE work_slip_id = ?", slipId);
        Integer others =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip WHERE gang_id = ? AND work_slip_date = ?"
                                + " AND slip_status NOT IN ('AMENDED', 'CANCELLED')",
                        Integer.class,
                        g.id(),
                        Date.valueOf(date));
        if (Objects.requireNonNullElse(others, 0) == 0) {
            // No slip left for the day: the attendance of the day may change again (SRS-2-3).
            jdbc.update(
                    "UPDATE labour_attendance SET work_slip_filed_flag = 'N' WHERE gang_id = ? AND"
                            + " attendance_date = ?",
                    g.id(),
                    Date.valueOf(date));
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "work_slip",
                                (String) old.get("WORK_SLIP_NUMBER"),
                                "slip_status",
                                (String) old.get("SLIP_STATUS"),
                                "CANCELLED",
                                reason.strip())
                        .module(SCREEN)
                        .effective(date));
        return slip(slipId);
    }

    /** SRS-2-29: the gang's slips of a month that is over, as one batch to the Payroll Checker. */
    @Transactional
    public SubmittedApproval submit(AccessContext context, long gangId, YearMonth month) {
        LabourGangs.Gang g = gangs.workGang(gangId);
        policy.requireRecordAt(context, SCREEN, "SUBMIT", g.depotId());
        if (month == null || !month.atEndOfMonth().isBefore(parameters.today())) {
            throw invalid("MONTH_NOT_OVER");
        }
        BatchState state = gangs.batch(ENTITY, gangId, month).state();
        if (!state.editable()) {
            throw state(AttendanceRules.batchKey(gangId, month), state.name());
        }
        Date first = Date.valueOf(month.atDay(1));
        Date last = Date.valueOf(month.atEndOfMonth());
        boolean dps = DPS.equals(g.payrollType());
        if (dps) {
            // SRS-4-13: the month goes on only with every booking-slip approved by the Depot
            // Manager.
            List<String> pending =
                    jdbc.queryForList(
                            "SELECT work_slip_number FROM work_slip WHERE gang_id = ? AND"
                                    + " work_slip_date BETWEEN ? AND ? AND slip_status IN ('DRAFT',"
                                    + " 'SUBMITTED', 'REJECTED') ORDER BY work_slip_date",
                            String.class,
                            gangId,
                            first,
                            last);
            if (!pending.isEmpty()) {
                throw new FciException(
                        LabourErrorCode.BOOKING_PENDING,
                        new Object[] {pending.size(), String.join(", ", pending)},
                        List.of(),
                        null);
            }
        }
        Integer drafts =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip WHERE gang_id = ? AND work_slip_date"
                                + " BETWEEN ? AND ? AND slip_status = ?",
                        Integer.class,
                        gangId,
                        first,
                        last,
                        dps ? "APPROVED" : "DRAFT");
        if (Objects.requireNonNullElse(drafts, 0) == 0) {
            throw invalid("NO_SLIPS");
        }
        String key = AttendanceRules.batchKey(gangId, month);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "SUBMIT",
                                ENTITY,
                                key,
                                ActionType.UPDATE,
                                g.depotId(),
                                "{\"gangId\":"
                                        + gangId
                                        + ",\"yymm\":\""
                                        + AttendanceRules.yymm(month)
                                        + "\"}",
                                month.atDay(1),
                                "Work-slips of "
                                        + g.name()
                                        + " for "
                                        + AttendanceRules.yymm(month)),
                        null);
        if (!dps) {
            jdbc.update(
                    "UPDATE work_slip SET slip_status = 'SUBMITTED', approval_id = ? WHERE gang_id"
                            + " = ? AND work_slip_date BETWEEN ? AND ? AND slip_status = 'DRAFT'",
                    item.id(),
                    gangId,
                    first,
                    last);
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                key,
                                "batch_state",
                                state.name(),
                                "SUBMITTED",
                                "Batch submitted")
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(month.atDay(1)));
        return item;
    }

    /** The Payroll Checker's decision: approved, the slips are APPROVED; else open again. */
    @EventListener
    @Transactional
    public void on(ApprovalDecided decision) {
        if (decision.concerns(BOOKING_ENTITY) && SCREEN.equals(decision.moduleCode())) {
            onBookingSlip(decision);
            return;
        }
        if (!decision.concerns(ENTITY)) {
            return;
        }
        String outcome = decision.approved() ? "APPROVED" : "DRAFT";
        jdbc.update(
                "UPDATE work_slip SET slip_status = ? WHERE approval_id = ? AND slip_status ="
                        + " 'SUBMITTED'",
                outcome,
                decision.approvalId());
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                decision.entityKey(),
                                "batch_state",
                                "SUBMITTED",
                                decision.approved() ? "APPROVED" : "RETURNED",
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    /**
     * SRS-2-34 (DPS): the Labour Dealing Hand sends a booking-slip to the Depot Manager of the
     * depot, who approves or returns it; the enterer never approves (SRS-G-1, the approval engine).
     */
    @Transactional
    public SubmittedApproval submitSlip(AccessContext context, long slipId) {
        Map<String, Object> old = saved(slipId);
        LabourGangs.Gang g = gangs.workGang(number(old.get("GANG_ID")));
        policy.requireRecordAt(context, SCREEN, "SUBMIT", g.depotId());
        if (!DPS.equals(g.payrollType())) {
            throw invalid("PAYROLL_TYPE");
        }
        requireEditable(old, g);
        String slipNo = (String) old.get("WORK_SLIP_NUMBER");
        LocalDate date = date(old.get("WORK_SLIP_DATE"));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                BOOKING_ROUTE,
                                SCREEN,
                                "SUBMIT",
                                BOOKING_ENTITY,
                                String.valueOf(slipId),
                                ActionType.UPDATE,
                                g.depotId(),
                                "{\"slipId\":" + slipId + "}",
                                date,
                                "Booking-slip " + slipNo + " of " + g.name()),
                        null);
        jdbc.update(
                "UPDATE work_slip SET slip_status = 'SUBMITTED', approval_id = ? WHERE"
                        + " work_slip_id = ?",
                item.id(),
                slipId);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "work_slip",
                                slipNo,
                                "slip_status",
                                (String) old.get("SLIP_STATUS"),
                                "SUBMITTED",
                                "Booking-slip to the Depot Manager")
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(date));
        return item;
    }

    /** The Depot Manager's decision: approved, the slip feeds C-04 / C-06; else it is returned. */
    private void onBookingSlip(ApprovalDecided decision) {
        String outcome = decision.approved() ? "APPROVED" : "REJECTED";
        int changed =
                jdbc.update(
                        "UPDATE work_slip SET slip_status = ? WHERE approval_id = ? AND slip_status"
                                + " = 'SUBMITTED'",
                        outcome,
                        decision.approvalId());
        if (changed == 0) {
            return;
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                "work_slip",
                                decision.entityKey(),
                                "slip_status",
                                "SUBMITTED",
                                outcome,
                                decision.reason() == null ? outcome : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    /** SRS-5-4: a labour left out of an NWNP slip as its membership ends that day, for review. */
    private void flagEnded(
            AccessContext context, LabourGangs.Gang g, long employeeId, LocalDate day) {
        worklist.raise(
                context.loginId(),
                new ExceptionWorklist.NewEntry(
                        null,
                        employeeId,
                        SCREEN,
                        "NWNP_MEMBERSHIP_ENDS",
                        "WARNING",
                        null,
                        null,
                        "labour_gang_membership",
                        "effective_to",
                        "ENT",
                        "M-09",
                        "Left out of the work-slip of "
                                + g.name()
                                + " on "
                                + day
                                + ": the gang membership ends that day (SRS-5-4)"));
    }

    /** A Departmental slip is changed while DRAFT; a DPS booking-slip also when returned. */
    private static void requireEditable(Map<String, Object> slip, LabourGangs.Gang g) {
        String status = (String) slip.get("SLIP_STATUS");
        if (!"DRAFT".equals(status)
                && !(DPS.equals(g.payrollType()) && "REJECTED".equals(status))) {
            throw state((String) slip.get("WORK_SLIP_NUMBER"), status);
        }
    }

    // ---------------------------------------------------------------- the write

    /** Checks the entry whole, then writes the slip, its rows and participants; answers its id. */
    private long write(
            AccessContext context, LabourGangs.Gang g, Entry e, Long replaces, String reason) {
        if (e == null || e.date() == null || e.rows() == null || e.participants() == null) {
            throw invalid("ENTRY");
        }
        LocalDate day = e.date();
        if (day.isAfter(parameters.today())) {
            throw invalid("FUTURE");
        }
        if (!g.activeOn(day)) {
            throw invalid("GANG_NOT_ACTIVE");
        }
        if (!DEPT.equals(g.payrollType())
                && !DPS.equals(g.payrollType())
                && !NWNP.equals(g.payrollType())) {
            throw invalid("PAYROLL_TYPE");
        }
        YearMonth month = YearMonth.from(day);
        BatchState state = gangs.batch(ENTITY, g.id(), month).state();
        if (!state.editable()) {
            throw state(AttendanceRules.batchKey(g.id(), month), state.name());
        }
        if (e.rows().isEmpty() || e.rows().size() > MAX_ROWS) {
            throw invalid("ROWS");
        }
        Map<Long, Operation> operations = operations(g.payrollType(), day);
        Set<Long> bagTypes = new HashSet<>(bagTypes(day).stream().map(BagType::id).toList());
        List<DuplicateKey> keys = new ArrayList<>();
        for (RowEntry r : e.rows()) {
            Operation op = r.operationId() == null ? null : operations.get(r.operationId());
            if (op == null) {
                throw invalid("OPERATION " + r.operationId());
            }
            // SRS-2-46 / I-02 l.4621: the Ancillary Pool has no bag fields.
            if (g.pool()
                    && (op.bagBased()
                            || r.bagTypeId() != null
                            || r.bags() != null
                            || r.stackHeight() != null)) {
                throw invalid("POOL_NO_BAGS");
            }
            if (r.bagTypeId() != null && !bagTypes.contains(r.bagTypeId())) {
                throw invalid("BAG_TYPE " + r.bagTypeId());
            }
            WorkSlipRules.rowDefect(
                            new WorkSlipRules.Row(
                                    op.bagBased(),
                                    op.pbnw(),
                                    r.bagTypeId(),
                                    r.bags(),
                                    r.stackHeight(),
                                    r.start(),
                                    r.end(),
                                    r.pbnwReason(),
                                    r.pbnwText()),
                            e.continueWork())
                    .ifPresent(
                            d -> {
                                throw invalid(d);
                            });
            keys.add(
                    new DuplicateKey(
                            op.id(),
                            op.normType(),
                            WorkSlipRules.heightBand(r.stackHeight()),
                            r.start(),
                            r.end()));
        }
        // SRS-2-4: the participants are members of the gang that day, marked in its attendance.
        Map<Long, Map<String, Object>> members = members(g.id(), day);
        if (e.participants().isEmpty()
                || new HashSet<>(e.participants()).size() != e.participants().size()) {
            throw invalid("PARTICIPANTS");
        }
        for (Long p : e.participants()) {
            if (!members.containsKey(p)) {
                throw invalid("NOT_A_MEMBER " + p);
            }
        }
        boolean nwnp = NWNP.equals(g.payrollType());
        // SRS-5-14: NWNP pay rests on the gang's work-slip, not on attendance codes.
        if (!nwnp && members.values().stream().anyMatch(m -> m.get("ATTENDANCE_CODE") == null)) {
            throw invalid("ATTENDANCE_MISSING");
        }
        duplicateOf(g, day, keys, replaces);
        List<Long> taking = new ArrayList<>(e.participants());
        if (nwnp) {
            // SRS-5-4: a labour is on the slip only while its membership ends after the date; one
            // ending that day is left out and flagged for review.
            for (Long p : e.participants()) {
                Object to = members.get(p).get("EFFECTIVE_TO");
                if (to != null && !date(to).isAfter(day)) {
                    taking.remove(p);
                    flagEnded(context, g, p, day);
                }
            }
            if (taking.isEmpty()) {
                throw invalid("PARTICIPANTS");
            }
        }
        int present = 0;
        boolean holiday = false;
        for (Long p : taking) {
            Map<String, Object> m = members.get(p);
            if (nwnp || ((BigDecimal) m.get("PRESENT_DAY_WEIGHT")).signum() > 0) {
                present++;
            }
            holiday |= "K".equals(m.get("ATTENDANCE_CODE"));
        }
        Integer sequence =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM work_slip WHERE gang_id = ? AND work_slip_date = ?",
                        Integer.class,
                        g.id(),
                        Date.valueOf(day));
        String number =
                WorkSlipRules.number(g.id(), day, Objects.requireNonNullElse(sequence, 0) + 1);
        String first = e.rows().stream().map(RowEntry::start).min(String::compareTo).orElseThrow();
        String last = e.rows().stream().map(RowEntry::end).max(String::compareTo).orElseThrow();
        jdbc.update(
                "INSERT INTO work_slip (work_slip_number, engine_code, gang_id, depot_location_id,"
                    + " work_slip_date, shift_start_time, shift_end_time, continue_work_flag,"
                    + " ota_flag, holiday_flag, slip_status, labour_strength, source_code,"
                    + " amendment_of_slip_id, amendment_reason, created_by, created_at) VALUES (?,"
                    + " ?, ?, ?, ?, ?, ?, ?, 'N', ?, 'DRAFT', ?, 'ENT', ?, ?, ?, ?)",
                number,
                g.payrollType(),
                g.id(),
                g.depotId(),
                Date.valueOf(day),
                first,
                last,
                e.continueWork() ? "Y" : "N",
                holiday ? "Y" : "N",
                present,
                replaces,
                reason,
                context.loginId(),
                Timestamp.from(clock.instant()));
        long id =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                                "SELECT work_slip_id FROM work_slip WHERE work_slip_number = ? AND"
                                        + " work_slip_date = ?",
                                Long.class,
                                number,
                                Date.valueOf(day)),
                        number);
        int order = 1;
        for (RowEntry r : e.rows()) {
            Operation op = operations.get(r.operationId());
            jdbc.update(
                    "INSERT INTO work_slip_row (work_slip_id, work_slip_date, row_order,"
                        + " operation_id, bag_type_id, number_of_bags, start_time, end_time,"
                        + " stack_height, stack_height_unit, height_band, norm_type, pbnw_reason,"
                        + " pbnw_reason_text, rake_reference) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?)",
                    id,
                    Date.valueOf(day),
                    order++,
                    op.id(),
                    r.bagTypeId(),
                    r.bags(),
                    r.start(),
                    r.end(),
                    r.stackHeight(),
                    r.stackHeight() == null ? null : "LEVEL",
                    WorkSlipRules.heightBand(r.stackHeight()),
                    op.normType(),
                    r.pbnwReason(),
                    blank(r.pbnwText()),
                    blank(r.rakeReference()));
        }
        for (Long p : taking) {
            Map<String, Object> m = members.get(p);
            jdbc.update(
                    "INSERT INTO work_slip_participant (work_slip_id, work_slip_date, employee_id,"
                            + " gang_role, present_flag) VALUES (?, ?, ?, ?, ?)",
                    id,
                    Date.valueOf(day),
                    p,
                    m.get("GANG_ROLE"),
                    nwnp || ((BigDecimal) m.get("PRESENT_DAY_WEIGHT")).signum() > 0 ? "Y" : "N");
        }
        // SRS-2-3 / BR-LABM-005: the gang's attendance of the day is frozen.
        jdbc.update(
                "UPDATE labour_attendance SET work_slip_filed_flag = 'Y' WHERE gang_id = ? AND"
                        + " attendance_date = ?",
                g.id(),
                Date.valueOf(day));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "work_slip",
                                number,
                                "slip_status",
                                null,
                                "DRAFT",
                                replaces == null ? "Work-slip saved" : "Amendment: " + reason)
                        .type(ChangeType.INITIAL_VALUE)
                        .module(SCREEN)
                        .effective(day));
        return id;
    }

    /**
     * SRS-3-9 / SRS-2-34: a row repeating a row of a live slip of the gang that day, by the test of
     * its payroll, is refused at once with the slip it repeats.
     */
    private void duplicateOf(
            LabourGangs.Gang g, LocalDate day, List<DuplicateKey> keys, Long replaces) {
        long gangId = g.id();
        String payrollType = g.payrollType();
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT s.work_slip_id, s.work_slip_number, r.operation_id, r.norm_type,"
                                + " r.height_band, r.start_time, r.end_time FROM work_slip s JOIN"
                                + " work_slip_row r ON r.work_slip_id = s.work_slip_id AND"
                                + " r.work_slip_date = s.work_slip_date WHERE s.gang_id = ? AND"
                                + " s.work_slip_date = ? AND s.slip_status NOT IN ('AMENDED',"
                                + " 'CANCELLED')",
                        gangId,
                        Date.valueOf(day));
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (WorkSlipRules.duplicate(payrollType, keys.get(i), keys.get(j))) {
                    throw invalid("ROW_TWICE");
                }
            }
        }
        for (Map<String, Object> r : rows) {
            if (replaces != null && number(r.get("WORK_SLIP_ID")) == replaces) {
                continue;
            }
            DuplicateKey saved =
                    new DuplicateKey(
                            number(r.get("OPERATION_ID")),
                            (String) r.get("NORM_TYPE"),
                            (String) r.get("HEIGHT_BAND"),
                            (String) r.get("START_TIME"),
                            (String) r.get("END_TIME"));
            if (keys.stream().anyMatch(k -> WorkSlipRules.duplicate(payrollType, k, saved))) {
                throw new FciException(
                        LabourErrorCode.DUPLICATE_SLIP,
                        new Object[] {r.get("WORK_SLIP_NUMBER")},
                        List.of(),
                        null);
            }
        }
    }

    // ---------------------------------------------------------------- reads

    private Map<Long, Operation> operations(String payrollType, LocalDate day) {
        Map<Long, Operation> operations = new LinkedHashMap<>();
        jdbc.query(
                "SELECT o.operation_id, o.operation_code, o.operation_name, o.is_bag_based,"
                        + " o.norm_type, o.is_present_but_no_work FROM operation_master o LEFT JOIN"
                        + " approval_queue q ON q.approval_id = o.approval_id WHERE ((? ="
                        + " 'DEPT_LABOUR' AND o.applies_dept_labour = 'Y') OR (? = 'DPS_LABOUR' AND"
                        + " o.applies_dps_labour = 'Y') OR (? = 'NWNP_LABOUR' AND"
                        + " o.applies_nwnp_labour = 'Y')) AND o.effective_from <= ? AND"
                        + " (o.effective_to IS NULL OR o.effective_to >= ?) AND (o.approval_id IS"
                        + " NULL OR q.status = 'APPROVED') ORDER BY o.operation_code",
                rs -> {
                    operations.put(
                            rs.getLong("operation_id"),
                            new Operation(
                                    rs.getLong("operation_id"),
                                    rs.getString("operation_code"),
                                    rs.getString("operation_name"),
                                    "Y".equals(rs.getString("is_bag_based")),
                                    rs.getString("norm_type"),
                                    "Y".equals(rs.getString("is_present_but_no_work"))));
                },
                payrollType,
                payrollType,
                payrollType,
                Date.valueOf(day),
                Date.valueOf(day));
        return operations;
    }

    private List<BagType> bagTypes(LocalDate day) {
        return jdbc.query(
                "SELECT bag_type_id, bag_type_code, bag_type_name, bag_weight_kg, weight_band FROM"
                        + " bag_type WHERE effective_from <= ? AND (effective_to IS NULL OR"
                        + " effective_to >= ?) ORDER BY bag_type_code",
                (rs, i) ->
                        new BagType(
                                rs.getLong("bag_type_id"),
                                rs.getString("bag_type_code"),
                                rs.getString("bag_type_name"),
                                rs.getBigDecimal("bag_weight_kg"),
                                rs.getString("weight_band")),
                Date.valueOf(day),
                Date.valueOf(day));
    }

    /** The gang's members that day (retired members excluded) with their attendance mark. */
    private Map<Long, Map<String, Object>> members(long gangId, LocalDate day) {
        Map<Long, Map<String, Object>> members = new LinkedHashMap<>();
        jdbc.queryForList(
                        "SELECT m.employee_id, m.gang_role, m.effective_to, a.attendance_code,"
                                + " c.present_day_weight"
                                + " FROM labour_gang_membership m JOIN employee e ON e.employee_id"
                                + " = m.employee_id LEFT JOIN labour_attendance a ON a.employee_id"
                                + " = m.employee_id AND a.attendance_date = ? AND a.gang_id ="
                                + " m.gang_id LEFT JOIN attendance_code_master c ON"
                                + " c.attendance_code_id = a.attendance_code_id WHERE m.gang_id = ?"
                                + " AND m.effective_from <= ? AND (m.effective_to IS NULL OR"
                                + " m.effective_to >= ?) AND (e.retirement_date IS NULL OR"
                                + " e.retirement_date >= ?)",
                        Date.valueOf(day),
                        gangId,
                        Date.valueOf(day),
                        Date.valueOf(day),
                        Date.valueOf(day))
                .forEach(r -> members.put(number(r.get("EMPLOYEE_ID")), r));
        return members;
    }

    private Map<String, Object> saved(long slipId) {
        return jdbc
                .queryForList(
                        "SELECT work_slip_id, work_slip_number, gang_id, work_slip_date,"
                                + " slip_status FROM work_slip WHERE work_slip_id = ?",
                        slipId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private Slip slip(long id) {
        Map<String, Object> s =
                jdbc.queryForMap(
                        "SELECT s.work_slip_id, s.work_slip_number, s.work_slip_date,"
                                + " s.slip_status, s.continue_work_flag, s.holiday_flag,"
                                + " s.labour_strength, s.amendment_of_slip_id, s.created_by,"
                                + " q.status AS decision, q.decided_by, q.decided_at,"
                                + " q.return_reason FROM work_slip s LEFT JOIN approval_queue q ON"
                                + " q.approval_id = s.approval_id WHERE s.work_slip_id = ?",
                        id);
        List<SlipRow> rows =
                jdbc.query(
                        "SELECT r.row_order, o.operation_code, o.operation_name, b.bag_type_code,"
                                + " r.number_of_bags, r.stack_height, r.height_band, r.start_time,"
                                + " r.end_time, r.pbnw_reason, r.operation_id, r.bag_type_id,"
                                + " r.pbnw_reason_text, r.rake_reference FROM work_slip_row r JOIN"
                                + " operation_master o ON o.operation_id = r.operation_id LEFT JOIN"
                                + " bag_type b ON b.bag_type_id = r.bag_type_id WHERE"
                                + " r.work_slip_id = ? ORDER BY r.row_order",
                        (rs, i) ->
                                new SlipRow(
                                        rs.getInt("row_order"),
                                        rs.getString("operation_code")
                                                + " - "
                                                + rs.getString("operation_name"),
                                        rs.getString("bag_type_code"),
                                        rs.getObject("number_of_bags", Integer.class),
                                        rs.getObject("stack_height", Integer.class),
                                        rs.getString("height_band"),
                                        rs.getString("start_time"),
                                        rs.getString("end_time"),
                                        rs.getString("pbnw_reason"),
                                        rs.getLong("operation_id"),
                                        rs.getObject("bag_type_id", Long.class),
                                        rs.getString("pbnw_reason_text"),
                                        rs.getString("rake_reference")),
                        id);
        List<Participant> participants =
                jdbc.query(
                        "SELECT p.employee_id, l.labour_number, e.first_name, e.last_name,"
                                + " p.gang_role, p.present_flag FROM work_slip_participant p JOIN"
                                + " employee e ON e.employee_id = p.employee_id JOIN labour_profile"
                                + " l ON l.employee_id = p.employee_id WHERE p.work_slip_id = ?"
                                + " ORDER BY l.labour_number",
                        (rs, i) -> {
                            String lastName = rs.getString("last_name");
                            return new Participant(
                                    rs.getLong("employee_id"),
                                    rs.getString("labour_number"),
                                    lastName == null || lastName.isBlank()
                                            ? rs.getString("first_name")
                                            : rs.getString("first_name") + " " + lastName,
                                    rs.getString("gang_role"),
                                    "Y".equals(rs.getString("present_flag")));
                        },
                        id);
        Object amendmentOf = s.get("AMENDMENT_OF_SLIP_ID");
        Object strength = s.get("LABOUR_STRENGTH");
        return new Slip(
                id,
                (String) s.get("WORK_SLIP_NUMBER"),
                date(s.get("WORK_SLIP_DATE")),
                (String) s.get("SLIP_STATUS"),
                "Y".equals(s.get("CONTINUE_WORK_FLAG")),
                "Y".equals(s.get("HOLIDAY_FLAG")),
                strength == null ? null : ((Number) strength).intValue(),
                amendmentOf == null ? null : number(amendmentOf),
                rows,
                participants,
                (String) s.get("CREATED_BY"),
                (String) s.get("DECIDED_BY"),
                s.get("DECIDED_AT") instanceof java.sql.Timestamp at ? at.toInstant() : null,
                "RETURNED".equals(s.get("DECISION")) ? (String) s.get("RETURN_REASON") : null);
    }

    // ---------------------------------------------------------------- helpers

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 1000) {
            throw invalid("REASON");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDate date(Object value) {
        return value instanceof java.sql.Date d
                ? d.toLocalDate()
                : new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException state(String what, String status) {
        return new FciException(
                LabourErrorCode.STATE,
                new Object[] {what, status, "DRAFT or OPEN"},
                List.of(),
                null);
    }

    private static FciException invalid(String defect) {
        return new FciException(LabourErrorCode.WORK_SLIP, new Object[] {defect}, List.of(), null);
    }
}
