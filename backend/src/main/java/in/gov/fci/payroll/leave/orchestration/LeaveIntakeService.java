package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.leave.api.LeaveReceipts;
import in.gov.fci.payroll.leave.business.EncashmentRules;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.leave.business.LeaveRules;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The employee leave data received from HRMS (plan 19 phase 19a; SRS-LV-24, SRS-LV-25, SRS-LV-13,
 * SRS-LV-26, SRS-LV-30; D19-1). Leave types and balances are stored as received and audited. A
 * leave event is validated: a failure is held on the X-05 worklist as an HRMS-owned entry (code
 * {@value #HELD}, corrected in HRMS) and never applied; a valid event is recorded as the HRMS
 * sanction ({@code leave_application}, SANCTIONED) and opens an I-21 task (field {@value
 * LeaveHrmsEffects#FIELD}) that applies its pay effect once the Payroll Checker approves it. The
 * same event received again (member, type, sanction reference, period) is a duplicate and opens
 * nothing (SRS-LV-25 "no duplicate").
 */
@Service
@Loggable
public class LeaveIntakeService implements LeaveReceipts {

    /** The X-05 code of a leave event held for HRMS (SRS-LV-13). */
    public static final String HELD = "LEAVE_EVENT_INVALID";

    /** The X-05 code of an encashment sanction held for HRMS (SRS-LV-21, SRS-LV-22). */
    public static final String HELD_ENCASHMENT = "LEAVE_ENCASHMENT_INVALID";

    static final String MODULE = "I-11";

    private final JdbcTemplate jdbc;
    private final HrmsChanges hrms;
    private final ExceptionWorklist worklist;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public LeaveIntakeService(
            JdbcTemplate jdbc,
            HrmsChanges hrms,
            ExceptionWorklist worklist,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.hrms = hrms;
        this.worklist = worklist;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void receiveType(String actor, LeaveType t) {
        LeaveRules.typeDefect(t.code(), t.name())
                .ifPresent(
                        d -> {
                            throw receipt(d);
                        });
        String code = t.code().strip();
        Timestamp now = Timestamp.from(clock.instant());
        Optional<Map<String, Object>> before = type(code);
        if (before.isEmpty()) {
            jdbc.update(
                    "INSERT INTO leave_type_reference (leave_type_code, leave_type_name,"
                            + " employee_category, source_code, is_encashable, supports_half_day,"
                            + " last_received_at) VALUES (?, ?, 'STAFF', 'HRMS', ?, ?, ?)",
                    code,
                    t.name().strip(),
                    flag(t.encashable()),
                    flag(t.halfDay()),
                    now);
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    "leave_type_reference",
                                    code,
                                    "leave_type_name",
                                    null,
                                    t.name().strip(),
                                    "Leave type received from HRMS")
                            .module("M-13"));
            return;
        }
        jdbc.update(
                "UPDATE leave_type_reference SET leave_type_name = ?, is_encashable = ?,"
                        + " supports_half_day = ?, last_received_at = ? WHERE leave_type_code = ?",
                t.name().strip(),
                flag(t.encashable()),
                flag(t.halfDay()),
                now,
                code);
        Map<String, Object> old = before.get();
        changed(actor, code, "leave_type_name", old.get("LEAVE_TYPE_NAME"), t.name().strip());
        changed(actor, code, "is_encashable", old.get("IS_ENCASHABLE"), flag(t.encashable()));
        changed(actor, code, "supports_half_day", old.get("SUPPORTS_HALF_DAY"), flag(t.halfDay()));
    }

    @Override
    @Transactional
    public void receiveBalance(String actor, Balance b) {
        Long employeeId = employee(b.eis()).orElseThrow(() -> receipt("MEMBER"));
        String leaveType = b.leaveType() == null ? "" : b.leaveType().strip();
        LeaveRules.balanceDefect(type(leaveType).isPresent(), b.days(), b.asOf(), b.finYear())
                .ifPresent(
                        d -> {
                            throw receipt(d);
                        });
        Timestamp now = Timestamp.from(clock.instant());
        List<BigDecimal> before =
                jdbc.queryForList(
                        "SELECT balance_days FROM leave_balance_snapshot WHERE employee_id = ? AND"
                                + " leave_type_code = ? AND as_of_date = ?",
                        BigDecimal.class,
                        employeeId,
                        leaveType,
                        Date.valueOf(b.asOf()));
        if (before.isEmpty()) {
            jdbc.update(
                    "INSERT INTO leave_balance_snapshot (employee_id, leave_type_code, fin_year,"
                            + " balance_days, as_of_date, balance_source, synced_at) VALUES (?, ?,"
                            + " ?, ?, ?, 'INTF', ?)",
                    employeeId,
                    leaveType,
                    b.finYear(),
                    b.days(),
                    Date.valueOf(b.asOf()),
                    now);
        } else {
            jdbc.update(
                    "UPDATE leave_balance_snapshot SET balance_days = ?, fin_year = ?, synced_at ="
                            + " ? WHERE employee_id = ? AND leave_type_code = ? AND as_of_date = ?",
                    b.days(),
                    b.finYear(),
                    now,
                    employeeId,
                    leaveType,
                    Date.valueOf(b.asOf()));
        }
        String previous = before.isEmpty() ? null : plain(before.getFirst());
        if (!plain(b.days()).equals(previous)) {
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    "leave_balance_snapshot",
                                    b.eis().strip() + "|" + leaveType + "|" + b.asOf(),
                                    "balance_days",
                                    previous,
                                    plain(b.days()),
                                    "Encashable balance received from HRMS")
                            .module(MODULE));
        }
    }

    @Override
    @Transactional
    public Received receiveEvent(String actor, Event e) {
        Optional<Long> employeeId = employee(e.eis());
        String leaveType = e.leaveType() == null ? "" : e.leaveType().strip();
        Optional<Map<String, Object>> type = type(leaveType);
        Optional<String> defect =
                LeaveRules.eventDefect(
                        e,
                        employeeId.isPresent(),
                        type.isPresent(),
                        type.map(t -> "Y".equals(t.get("SUPPORTS_HALF_DAY"))).orElse(false));
        if (defect.isEmpty() && !active(employeeId.orElseThrow())) {
            // SRS I-11 Member (l.4196, US-GAP-305; OD-63): "must match an active Payroll
            // employee"; a member no longer on the rolls is held and referred to HRMS.
            // OPEN-QUESTION (CLR-69): a separated member whose F&F case is still open.
            defect = Optional.of("MEMBER_INACTIVE");
        }
        if (defect.isPresent()) {
            hold(actor, employeeId.orElse(null), e, defect.get());
            return new Received(Outcome.HELD, null, null, defect.get());
        }
        long member = employeeId.orElseThrow();
        Optional<Long> existing = duplicate(member, leaveType, e);
        if (existing.isPresent()) {
            return new Received(Outcome.DUPLICATE, existing.get(), null, null);
        }
        long applicationId = insert(member, leaveType, e);
        audit.record(
                actor,
                AuditService.Entry.change(
                                "leave_application",
                                String.valueOf(applicationId),
                                "decision",
                                null,
                                decision(e),
                                "Leave event "
                                        + e.eventType()
                                        + " received from HRMS, sanction "
                                        + e.sanctionRef().strip()
                                        + ", "
                                        + e.from()
                                        + " to "
                                        + e.to())
                        .module(MODULE));
        HrmsChanges.Received task =
                hrms.receive(
                        new HrmsChanges.Change(
                                member,
                                LeaveHrmsEffects.FIELD,
                                value(applicationId, leaveType, e),
                                e.from(),
                                e.hrmsRequestId(),
                                HrmsChanges.Source.HRMS_PUSH));
        return new Received(Outcome.TASK_OPENED, applicationId, task.taskId(), null);
    }

    @Override
    @Transactional
    public Received receiveEncashment(String actor, EncashmentSanction e) {
        Optional<Long> employeeId = employee(e.eis());
        String leaveType = e.leaveType() == null ? "" : e.leaveType().strip();
        Optional<Map<String, Object>> type = type(leaveType);
        Optional<String> defect = encashmentDefect(e, employeeId, type, leaveType);
        if (defect.isPresent()) {
            holdEncashment(actor, employeeId.orElse(null), e, defect.get());
            return new Received(Outcome.HELD, null, null, defect.get());
        }
        long member = employeeId.orElseThrow();
        String sanction = e.sanctionRef().strip();
        if (encashmentReceived(member, sanction)) {
            return new Received(Outcome.DUPLICATE, null, null, null);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sanctionRef", sanction);
        v.put("leaveType", leaveType);
        v.put("days", e.days().toPlainString());
        v.put("encashmentDate", e.encashmentDate().toString());
        v.put("orderPermits", e.orderPermits());
        HrmsChanges.Received task =
                hrms.receive(
                        new HrmsChanges.Change(
                                member,
                                LeaveHrmsEffects.ENCASHMENT_FIELD,
                                json.writeValueAsString(v),
                                e.encashmentDate(),
                                e.hrmsRequestId(),
                                HrmsChanges.Source.HRMS_PUSH));
        audit.record(
                actor,
                AuditService.Entry.change(
                                "leave_encashment",
                                e.eis().strip() + "|" + sanction,
                                "days_encashed",
                                null,
                                e.days().toPlainString(),
                                "In-service encashment sanction received from HRMS")
                        .module(MODULE));
        return new Received(Outcome.TASK_OPENED, null, task.taskId(), null);
    }

    /** SRS-LV-17 / LV-21 / LV-22 / LV-4, and the sanction reference of SRS-LV-34. */
    private Optional<String> encashmentDefect(
            EncashmentSanction e,
            Optional<Long> employeeId,
            Optional<Map<String, Object>> type,
            String leaveType) {
        if (employeeId.isEmpty()) {
            return Optional.of("MEMBER");
        }
        if (e.sanctionRef() == null || e.sanctionRef().isBlank()) {
            return Optional.of("NO_SANCTION_REF");
        }
        if (e.sanctionRef().strip().length() > 50
                || !e.sanctionRef().strip().matches("^[\\x20-\\x7E]+$")) {
            return Optional.of("SANCTION_REF");
        }
        if (type.isEmpty()) {
            return Optional.of("LEAVE_TYPE");
        }
        if (e.encashmentDate() == null) {
            return Optional.of("DATE");
        }
        BigDecimal balance =
                jdbc
                        .queryForList(
                                "SELECT balance_days FROM leave_balance_snapshot WHERE employee_id"
                                    + " = ? AND leave_type_code = ? AND as_of_date <= ? ORDER BY"
                                    + " as_of_date DESC FETCH FIRST 1 ROWS ONLY",
                                BigDecimal.class,
                                employeeId.get(),
                                leaveType,
                                Date.valueOf(e.encashmentDate()))
                        .stream()
                        .findFirst()
                        .orElse(null);
        boolean suspended =
                "SUSPENDED"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT employment_status FROM employee WHERE employee_id"
                                                + " = ?",
                                        String.class,
                                        employeeId.get()));
        return EncashmentRules.inServiceDefect(
                leaveType,
                "Y".equals(type.get().get("IS_ENCASHABLE")),
                e.days(),
                balance,
                suspended,
                e.orderPermits());
    }

    /** SRS-LV-34: never the same sanction twice (its I-21 task, whatever became of it). */
    private boolean encashmentReceived(long employeeId, String sanction) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM hrms_change_task t JOIN hrms_pay_effect_field f ON"
                            + " f.pay_effect_field_id = t.pay_effect_field_id WHERE t.employee_id ="
                            + " ? AND f.field_code = ? AND t.new_value LIKE ? AND t.task_status NOT"
                            + " IN ('REJECTED_RETURNED_TO_HRMS', 'CANCELLED')",
                        Long.class,
                        employeeId,
                        LeaveHrmsEffects.ENCASHMENT_FIELD,
                        // The value begins with the sanction reference ({@link
                        // #receiveEncashment});
                        // a printable reference is matched literally (no wildcard in the pattern).
                        "{\"sanctionRef\":\"" + sanction.replace("%", "_") + "\",%");
        return RowCounts.of(n) > 0;
    }

    private void holdEncashment(
            String actor, Long employeeId, EncashmentSanction e, String defect) {
        worklist.raise(
                actor,
                new ExceptionWorklist.NewEntry(
                        null,
                        employeeId,
                        MODULE,
                        HELD_ENCASHMENT,
                        "WARNING",
                        null,
                        null,
                        "leave_encashment",
                        "days_encashed",
                        "HRMS",
                        "HRMS",
                        "Leave encashment of "
                                + safe(e.eis())
                                + " ("
                                + safe(e.leaveType())
                                + ", "
                                + e.days()
                                + " days, sanction "
                                + safe(e.sanctionRef())
                                + ") held: "
                                + defect));
    }

    // ---------------------------------------------------------------- steps

    /** SRS-LV-13: held on X-05, referred to HRMS (HRMS-owned, not resolvable in Payroll). */
    private void hold(String actor, Long employeeId, Event e, String defect) {
        worklist.raise(
                actor,
                new ExceptionWorklist.NewEntry(
                        null,
                        employeeId,
                        MODULE,
                        HELD,
                        "WARNING",
                        null,
                        null,
                        "leave_application",
                        "hrms_sanction_ref",
                        "HRMS",
                        "HRMS",
                        "Leave event of "
                                + safe(e.eis())
                                + " ("
                                + safe(e.leaveType())
                                + ", "
                                + e.from()
                                + " to "
                                + e.to()
                                + ", sanction "
                                + safe(e.sanctionRef())
                                + ") held: "
                                + defect));
    }

    /**
     * SRS-LV-25 "no duplicate": the same member, type, sanction reference and period.
     *
     * <p>OPEN-QUESTION (CLR-70, US-GAP-306): a correction with the same key but other days is
     * answered as a duplicate until FCI says how a correction "received again" (BR-LEAVE-003)
     * applies.
     */
    private Optional<Long> duplicate(long employeeId, String leaveType, Event e) {
        return jdbc
                .queryForList(
                        "SELECT application_id FROM leave_application WHERE employee_id = ? AND"
                                + " leave_type_code = ? AND hrms_sanction_ref = ? AND applied_from"
                                + " = ? AND applied_to = ? AND decision = ?",
                        Long.class,
                        employeeId,
                        leaveType,
                        e.sanctionRef().strip(),
                        Date.valueOf(e.from()),
                        Date.valueOf(e.to()),
                        decision(e))
                .stream()
                .findFirst();
    }

    private long insert(long employeeId, String leaveType, Event e) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO leave_application (employee_id, leave_type_code,"
                                            + " applied_from, applied_to, days_applied,"
                                            + " is_half_day, sanctioning_authority,"
                                            + " hrms_sanction_ref, hrms_request_id, decision,"
                                            + " source_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                            + " ?, 'HRMS')",
                                    new String[] {"application_id"});
                    ps.setLong(1, employeeId);
                    ps.setString(2, leaveType);
                    ps.setDate(3, Date.valueOf(e.from()));
                    ps.setDate(4, Date.valueOf(e.to()));
                    ps.setBigDecimal(5, e.days());
                    ps.setString(6, flag(e.halfDay()));
                    ps.setString(7, trim(e.sanctioningAuthority(), 150));
                    ps.setString(8, e.sanctionRef().strip());
                    ps.setString(9, e.hrmsRequestId());
                    ps.setString(10, decision(e));
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    /** The task's read-only value: the received event (JSON), with its application row. */
    private String value(long applicationId, String leaveType, Event e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("applicationId", applicationId);
        v.put("leaveType", leaveType);
        v.put("from", e.from().toString());
        v.put("to", e.to().toString());
        v.put("days", e.days().toPlainString());
        v.put("eventType", e.eventType().name());
        v.put("sanctionRef", e.sanctionRef().strip());
        if (e.earlierSanctionRef() != null) {
            v.put("earlierSanctionRef", e.earlierSanctionRef().strip());
        }
        return json.writeValueAsString(v);
    }

    /** A cancellation mirrors the HRMS decision CANCELLED; an addition or conversion SANCTIONED. */
    private static String decision(Event e) {
        return e.eventType() == EventType.CANCELLATION ? "CANCELLED" : "SANCTIONED";
    }

    private Optional<Long> employee(String eis) {
        if (eis == null || eis.isBlank()) {
            return Optional.empty();
        }
        return jdbc
                .queryForList(
                        "SELECT employee_id FROM employee WHERE emp_num = ?",
                        Long.class,
                        eis.strip())
                .stream()
                .findFirst();
    }

    /**
     * SRS I-11 Member (l.4196, US-GAP-305; OD-63): an active Payroll employee - a record not
     * rejected, of a member still on the rolls (not separated or dismissed).
     */
    private boolean active(long employeeId) {
        return jdbc
                .queryForList(
                        "SELECT employee_id FROM employee WHERE employee_id = ? AND record_status"
                                + " <> 'REJECTED' AND employment_status NOT IN ('SEPARATED',"
                                + " 'DISMISSED')",
                        Long.class,
                        employeeId)
                .stream()
                .findFirst()
                .isPresent();
    }

    private Optional<Map<String, Object>> type(String code) {
        return jdbc
                .queryForList(
                        "SELECT leave_type_name, is_encashable, supports_half_day FROM"
                                + " leave_type_reference WHERE leave_type_code = ?",
                        code)
                .stream()
                .findFirst();
    }

    private void changed(String actor, String code, String field, Object old, String now) {
        String before = old == null ? null : old.toString();
        if (!now.equals(before)) {
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    "leave_type_reference",
                                    code,
                                    field,
                                    before,
                                    now,
                                    "Leave type received from HRMS")
                            .module("M-13"));
        }
    }

    private static String flag(boolean value) {
        return value ? "Y" : "N";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.strip();
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** A received value for the worklist text: printable, at most 40 characters (R-ASCII). */
    private static String safe(String value) {
        if (value == null) {
            return "-";
        }
        String s = value.replaceAll("[^\\x20-\\x7E]", "?").strip();
        return s.length() <= 40 ? s : s.substring(0, 40);
    }

    private static FciException receipt(String defect) {
        return new FciException(LeaveErrorCode.RECEIPT, new Object[] {defect}, List.of(), null);
    }
}
