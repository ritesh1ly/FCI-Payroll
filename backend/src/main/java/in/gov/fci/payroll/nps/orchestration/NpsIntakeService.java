package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.nps.business.NpsRules.Mismatch;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The NPS record received from HRMS (plan 18 phase 18a; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-23;
 * D18-1, D18-2, D18-8): one MONTHLY row per member and month (DDL {@code uk_nps_deduction}),
 * classified on receipt; a later record of the month replaces its values while the month is not
 * locked, every changed value audited before and after (R-AUD; never a delete, R-DEL). A mismatch
 * is raised on the X-05 worklist (C-08, gate G2) as an HRMS-owned exception, cleared when HRMS
 * sends a consistent record.
 *
 * <p>SRS-9-23 (US-GAP-291, OD-55): a later record of an open month that changes what the month pays
 * (the NPS flag or an amount of a consistent record) is not applied here: it opens its I-21 task
 * (field {@code NPS_CHANGE}, the record as JSON, read-only) and the month keeps its record until
 * the Payroll Checker approves it ({@link NpsHrmsEffects}). A PRAN or effective date change alone,
 * a mismatch and a record correcting a mismatch apply at once.
 */
@Service
@Loggable
public class NpsIntakeService implements NpsReceipts {

    /** The X-05 code, the same as the pre-lock check's rule key. */
    public static final String EXCEPTION = "NPS_FLAG_AMOUNT_MISMATCH";

    static final String ENTITY = "nps_deduction";

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final NpsExceptions exceptions;
    private final AuditService audit;
    private final HrmsChanges changes;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public NpsIntakeService(
            JdbcTemplate jdbc,
            OrgDirectory org,
            PayrollRecords payroll,
            NpsExceptions exceptions,
            AuditService audit,
            HrmsChanges changes,
            ObjectMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.org = org;
        this.payroll = payroll;
        this.exceptions = exceptions;
        this.audit = audit;
        this.changes = changes;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Received receive(String actor, Receipt r) {
        NpsRules.defect(
                        r.eis(),
                        r.payrollYymm(),
                        r.employeeAmount(),
                        r.employerAmount(),
                        r.pran(),
                        r.effectiveFrom())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        YearMonth month = NpsRules.month(r.payrollYymm()).orElseThrow();
        Map<String, Object> employee =
                jdbc
                        .queryForList(
                                "SELECT employee_id, location_id, emp_lbr_flag FROM employee WHERE"
                                        + " emp_num = ?",
                                r.eis().strip())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> invalid("UNKNOWN_EMPLOYEE"));
        // SRS l.4005 (US-18-01 AC4): NPS applies to Employee / Staff members only; labour are
        // covered by CPF, so a labour member's record is refused, never stored.
        if ("Y".equals(employee.get("EMP_LBR_FLAG"))) {
            throw invalid("LABOUR_MEMBER");
        }
        long employeeId = ((Number) employee.get("EMPLOYEE_ID")).longValue();
        Long cycleId = cycle(employee.get("LOCATION_ID"), r.payrollYymm());
        Map<String, Object> before = current(jdbc, employeeId, r.payrollYymm());
        Mismatch previous =
                before == null ? null : Mismatch.valueOf((String) before.get("MISMATCH_STATUS"));
        Mismatch status =
                NpsRules.status(r.npsMember(), r.employeeAmount(), r.employerAmount(), previous);
        if (before != null
                && NpsRules.payEffect(
                        previous,
                        "Y".equals(before.get("HRMS_NPS_FLAG")),
                        (BigDecimal) before.get("EMPLOYEE_AMOUNT"),
                        (BigDecimal) before.get("EMPLOYER_AMOUNT"),
                        status,
                        r.npsMember(),
                        r.employeeAmount(),
                        r.employerAmount())) {
            return throughI21(actor, employeeId, month, before, previous, r);
        }
        Timestamp now = Timestamp.from(clock.instant());
        long id;
        if (before == null) {
            id = insert(employeeId, cycleId, month, r, status, now);
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    ENTITY,
                                    key(r),
                                    "mismatch_status",
                                    null,
                                    status.name(),
                                    "NPS record received from HRMS")
                            .module("I-14"));
        } else {
            id = ((Number) before.get("NPS_DEDUCTION_ID")).longValue();
            update(jdbc, id, cycleId, r, status, now);
            audit.recordAll(actor, changes(before, r, status, "NPS record replaced by HRMS"));
        }
        exceptions.raiseOrClear(actor, id, employeeId, cycleId, status, r.payrollYymm());
        // Wave C review: the newest HRMS record of the month stands; a change still waiting for
        // its I-21 task no longer matches it and is closed (never approved over it).
        if (before != null) {
            supersedeOpen(
                    changes.open(employeeId, NpsHrmsEffects.CHANGE_FIELD),
                    r.payrollYymm(),
                    null,
                    "the record applied at once");
        }
        return new Received(id, status.name(), before != null, null);
    }

    // ---------------------------------------------------------------- steps

    /**
     * SRS-9-23, SRS-G-45 (US-GAP-291): the change opens its I-21 task with the record as HRMS sent
     * it; the month's record stays as it is, audited as held for the task.
     */
    private Received throughI21(
            String actor,
            long employeeId,
            YearMonth month,
            Map<String, Object> before,
            Mismatch previous,
            Receipt r) {
        long recordId = ((Number) before.get("NPS_DEDUCTION_ID")).longValue();
        // Wave C review: a resend of the change already waiting opens no second task.
        List<HrmsChanges.Open> waiting = changes.open(employeeId, NpsHrmsEffects.CHANGE_FIELD);
        for (HrmsChanges.Open open : waiting) {
            if (NpsHrmsEffects.sameRecord(json, open.newValue(), r)) {
                return new Received(recordId, previous.name(), false, open.taskNo());
            }
        }
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("payrollYymm", r.payrollYymm());
        value.put("npsMember", r.npsMember());
        value.put("pran", r.pran());
        value.put("employeeAmount", r.employeeAmount());
        value.put("employerAmount", r.employerAmount());
        // Wave C review: the effective date as HRMS sent it (null kept), and the record the change
        // was made against, so an approval over a newer record is refused.
        value.put("effectiveFrom", r.effectiveFrom() == null ? null : r.effectiveFrom().toString());
        value.put(NpsHrmsEffects.BASIS, basis(before));
        HrmsChanges.Received task =
                changes.receive(
                        new HrmsChanges.Change(
                                employeeId,
                                NpsHrmsEffects.CHANGE_FIELD,
                                json.writeValueAsString(value),
                                r.effectiveFrom() != null ? r.effectiveFrom() : month.atDay(1),
                                null,
                                HrmsChanges.Source.HRMS_PUSH));
        supersedeOpen(
                waiting,
                r.payrollYymm(),
                task.taskId(),
                "the newer record of task " + task.taskNo());
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                key(r),
                                "hrms_change_task_id",
                                null,
                                task.taskNo(),
                                "NPS change with pay effect held for I-21 task " + task.taskNo())
                        .module("I-14"));
        return new Received(recordId, previous.name(), false, task.taskNo());
    }

    /**
     * Wave C review (SRS-9-23): every NPS_CHANGE task of the member and month still waiting (read
     * before {@code newTask} opened) is closed as SUPERSEDED by the newer record (through I-21,
     * audited).
     */
    private void supersedeOpen(
            List<HrmsChanges.Open> waiting, String yymm, Long newTask, String newer) {
        for (HrmsChanges.Open open : waiting) {
            if (yymm.equals(NpsHrmsEffects.monthOf(json, open.newValue()))) {
                changes.supersede(
                        open.taskId(),
                        newTask,
                        "system.hrms",
                        "Superseded by a newer NPS record of " + yymm + " from HRMS: " + newer);
            }
        }
    }

    /**
     * What a task's change was made against: the month's record and when HRMS last sent it. Any
     * newer record of the month changes it (Wave C review, {@link NpsHrmsEffects}).
     */
    static String basis(Map<String, Object> record) {
        Object at = record.get("HRMS_RECEIVED_AT");
        String when =
                switch (at) {
                    case null -> "-";
                    case Timestamp ts -> ts.toInstant().toString();
                    default -> at.toString();
                };
        return ((Number) record.get("NPS_DEDUCTION_ID")).longValue() + "@" + when;
    }

    /** The member's cycle instance of the month, if initiated; refused once it is locked. */
    private Long cycle(Object locationId, String yymm) {
        if (locationId == null) {
            return null;
        }
        return org.cycleUnitOf(((Number) locationId).longValue())
                .flatMap(unit -> payroll.cycle(unit.getId(), yymm))
                .map(
                        c -> {
                            if (c.isLocked()) {
                                throw new FciException(
                                        ApiErrorCode.NPS_LOCKED,
                                        new Object[] {yymm},
                                        List.of(),
                                        null);
                            }
                            return c.cycleInstanceId();
                        })
                .orElse(null);
    }

    /** The month's MONTHLY record of the member, or null. */
    static Map<String, Object> current(JdbcTemplate jdbc, long employeeId, String yymm) {
        return jdbc
                .queryForList(
                        "SELECT nps_deduction_id, hrms_nps_flag, pran_reference, employee_amount,"
                            + " employer_amount, hrms_effective_from, mismatch_status,"
                            + " apply_status, hrms_received_at FROM nps_deduction WHERE employee_id"
                            + " = ? AND payroll_yymm = ? AND record_type = 'MONTHLY'",
                        employeeId,
                        yymm)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long insert(
            long employeeId,
            Long cycleId,
            YearMonth month,
            Receipt r,
            Mismatch status,
            Timestamp now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO nps_deduction (employee_id, cycle_instance_id,"
                                            + " payroll_yymm, record_type, hrms_nps_flag,"
                                            + " pran_reference, employee_amount, employer_amount,"
                                            + " hrms_effective_from, hrms_received_at,"
                                            + " mismatch_status, apply_status, period_date) VALUES"
                                            + " (?, ?, ?, 'MONTHLY', ?, ?, ?, ?, ?, ?, ?,"
                                            + " 'RECEIVED', ?)",
                                    new String[] {"nps_deduction_id"});
                    ps.setLong(1, employeeId);
                    ps.setObject(2, cycleId, Types.BIGINT);
                    ps.setString(3, r.payrollYymm());
                    ps.setString(4, r.npsMember() ? "Y" : "N");
                    ps.setString(5, r.pran());
                    ps.setBigDecimal(6, r.employeeAmount());
                    ps.setBigDecimal(7, r.employerAmount());
                    ps.setObject(8, date(r.effectiveFrom()), Types.DATE);
                    ps.setTimestamp(9, now);
                    ps.setString(10, status.name());
                    ps.setDate(11, Date.valueOf(month.atDay(1)));
                    return ps;
                },
                key);
        return GeneratedKeys.id(key);
    }

    /** Replaces the values of the month's record with those HRMS sent. */
    static void update(
            JdbcTemplate jdbc, long id, Long cycleId, Receipt r, Mismatch status, Timestamp now) {
        jdbc.update(
                "UPDATE nps_deduction SET hrms_nps_flag = ?, pran_reference = ?, employee_amount ="
                        + " ?, employer_amount = ?, hrms_effective_from = ?, hrms_received_at = ?,"
                        + " mismatch_status = ?, cycle_instance_id = COALESCE(?, cycle_instance_id)"
                        + " WHERE nps_deduction_id = ? AND period_date = ?",
                r.npsMember() ? "Y" : "N",
                r.pran(),
                r.employeeAmount(),
                r.employerAmount(),
                date(r.effectiveFrom()),
                now,
                status.name(),
                cycleId,
                id,
                Date.valueOf(NpsRules.month(r.payrollYymm()).orElseThrow().atDay(1)));
    }

    /** R-AUD: every value HRMS changed, before and after. */
    static List<AuditService.Entry> changes(
            Map<String, Object> before, Receipt r, Mismatch status, String reason) {
        List<AuditService.Entry> out = new ArrayList<>();
        String flag = r.npsMember() ? "Y" : "N";
        add(out, r, reason, "hrms_nps_flag", before.get("HRMS_NPS_FLAG"), flag);
        add(out, r, reason, "pran_reference", before.get("PRAN_REFERENCE"), r.pran());
        add(out, r, reason, "employee_amount", before.get("EMPLOYEE_AMOUNT"), r.employeeAmount());
        add(out, r, reason, "employer_amount", before.get("EMPLOYER_AMOUNT"), r.employerAmount());
        add(
                out,
                r,
                reason,
                "hrms_effective_from",
                text(before.get("HRMS_EFFECTIVE_FROM")),
                r.effectiveFrom() == null ? null : r.effectiveFrom().toString());
        add(out, r, reason, "mismatch_status", before.get("MISMATCH_STATUS"), status.name());
        return out;
    }

    private static void add(
            List<AuditService.Entry> out,
            Receipt r,
            String reason,
            String field,
            Object old,
            Object now) {
        String a = text(old);
        String b = text(now);
        if (!Objects.equals(a, b)) {
            out.add(AuditService.Entry.change(ENTITY, key(r), field, a, b, reason).module("I-14"));
        }
    }

    static String key(Receipt r) {
        return r.eis().strip() + "|" + r.payrollYymm();
    }

    private static Date date(LocalDate day) {
        return day == null ? null : Date.valueOf(day);
    }

    static String text(Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal amount -> amount.stripTrailingZeros().toPlainString();
            // A DATE column reads as java.sql.Date or Timestamp by driver: both are dates here.
            case java.util.Date d -> new Date(d.getTime()).toLocalDate().toString();
            default -> value.toString();
        };
    }

    private static FciException invalid(String defect) {
        return new FciException(ApiErrorCode.NPS_RECEIPT, new Object[] {defect}, List.of(), null);
    }
}
