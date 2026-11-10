package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.arrear.business.ArrearRules;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.GeneratedKeys;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single-member retrospective events (plan 13 phase 13b; SRS-8-13, SRS-21-1, SRS-21-7, SRS-21-9,
 * SRS-21-11; D13-5, D13-7). The owning module raises the event in its own transaction; once that
 * has committed, each locked month of the member from the effective date is recomputed with that
 * month's rates ({@link PendingSalary}: the member as now, the back-dated change included) and set
 * against what the month's ACTUAL result paid, component by component, for the pay-linked
 * components (D13-7: the earnings of the PAY and ALLOWANCES payslip groups); the differentials are
 * written as CALCULATED details and the event becomes COMPUTED, for the Maker on I-08. A member
 * that cannot be computed leaves the event RAISED with the reason in its audit; the Maker computes
 * it again on I-08 once the data is complete.
 *
 * <p>SRS-1-42 (BR-EMPR-012): only the engine's pay-linked earnings of the locked result are set
 * against the recompute, so a Maintain Direct Payroll (I-19) deduction or a supplementary-window
 * (I-23) payment or recovery of the month keeps its nature: it is never netted into a differential
 * nor turned into a payment or a recovery (RetroEventIT).
 *
 * <p>SRS-8-6 (BR-EMPR-007): an event whose first month lies before the Headquarters back-date limit
 * (A-07 MAX_RETRO_PERIOD) is written RAISED with {@code beyond_window_flag = 'Y'} and is not
 * computed until a Headquarters exception with its order is approved ({@link
 * ArrearExceptionService}); DDL {@code chk_arrear_event_window} holds it at RAISED meanwhile. The
 * I-24 header events are not checked here: they follow a revision already checked at its source
 * (the C-13 arrear job window or the Headquarters wage revision, D13-15).
 */
@Service
@Loggable
public class RetroEventService implements RetroEvents {

    /** D13-7: the payslip groups of the pay-linked components (SRS-8-13). */
    static final List<String> PAY_LINKED = List.of("PAY", "ALLOWANCES");

    /** Published in the raising transaction, handled after it commits. */
    record Raised(long eventId, long employeeId, String by) {}

    private final JdbcTemplate jdbc;
    private final PendingSalary salary;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate own;
    private final ArrearRegisterService register;
    private final SystemParameters parameters;

    public RetroEventService(
            JdbcTemplate jdbc,
            PendingSalary salary,
            AuditService audit,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions,
            ArrearRegisterService register,
            SystemParameters parameters) {
        this.jdbc = jdbc;
        this.salary = salary;
        this.audit = audit;
        this.events = events;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.register = register;
        this.parameters = parameters;
    }

    /**
     * SRS-8-6: the first month lies before the Headquarters back-date limit. A first month in the
     * current period or later is inside every limit; an earlier one needs the limit, and while it
     * is not set the event is refused "not configured" (CLR-147, OD-97; R-RULE: never a guessed
     * limit, never every event held as beyond it).
     */
    private boolean beyondWindow(LocalDate effectiveFrom) {
        YearMonth current = YearMonth.from(parameters.today());
        if (!YearMonth.from(effectiveFrom).isBefore(current)) {
            return false;
        }
        return ArrearRules.beyondWindow(effectiveFrom, current, maxRetroMonths());
    }

    /** The back-date limit in force, or ARR-422-NOT-CONFIGURED while FCI has not set it. */
    private int maxRetroMonths() {
        return (int)
                parameters
                        .maxRetroMonths()
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ArrearErrorCode.NOT_CONFIGURED,
                                                new Object[] {"MAX_RETRO_PERIOD"},
                                                List.of(),
                                                null));
    }

    private String beyondNote(boolean beyond) {
        return beyond
                ? " (beyond the back-date limit of "
                        + maxRetroMonths()
                        + " months: Headquarters exception needed, SRS-8-6)"
                : "";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long raise(Raise r) {
        List<Long> open =
                jdbc.queryForList(
                        "SELECT arrear_event_id FROM arrear_event WHERE source_module = ? AND"
                                + " source_entity = ? AND source_key = ? AND event_status IN"
                                + " ('RAISED', 'COMPUTED', 'APPROVED')",
                        Long.class,
                        r.sourceModule(),
                        r.sourceEntity(),
                        r.sourceKey());
        if (!open.isEmpty()) {
            return open.getFirst();
        }
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        r.employeeId());
        String from = yymm(YearMonth.from(r.effectiveFrom()));
        boolean beyond = beyondWindow(r.effectiveFrom());
        long type = eventType(r.eventCode());
        // Oracle review F2: the generated key of this INSERT, never MAX(id) read back.
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                                + " scope_payroll_type, scope_location_id, scope_from_yymm,"
                                + " source_module, source_entity, source_key, beyond_window_flag,"
                                + " event_status, raised_by) VALUES (?, ?, ?, 'STAFF', ?, ?, ?, ?,"
                                + " ?, ?, 'RAISED', ?)",
                        "arrear_event_id",
                        type,
                        cut(r.sourceModule() + " " + r.sourceKey() + ": " + r.reason().strip()),
                        Date.valueOf(r.effectiveFrom()),
                        location,
                        from,
                        r.sourceModule(),
                        r.sourceEntity(),
                        r.sourceKey(),
                        beyond ? "Y" : "N",
                        r.raisedBy());
        jdbc.update(
                "INSERT INTO arrear_event_member (arrear_event_id, employee_id) VALUES (?, ?)",
                id,
                r.employeeId());
        audit.record(
                r.raisedBy(),
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(id),
                                "event_status",
                                null,
                                "RAISED",
                                "Back-dated "
                                        + r.eventCode()
                                        + " from "
                                        + r.effectiveFrom()
                                        + ": "
                                        + cut(r.reason().strip() + beyondNote(beyond)))
                        .module(ArrearRegisterService.SCREEN));
        if (!beyond) {
            events.publishEvent(new Raised(id, r.employeeId(), r.raisedBy()));
        }
        return id;
    }

    /** The id of an arrear event type; an unknown code is a programming error, never a skip. */
    private long eventType(String code) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT event_type_id FROM arrear_event_type WHERE event_code = ?",
                        Long.class,
                        code);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Unknown arrear event type " + code);
        }
        return ids.getFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long header(Header h) {
        long type = eventType(h.eventCode());
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                                + " scope_payroll_type, scope_location_id, source_module,"
                                + " source_entity, source_key, circular_order_id, event_status,"
                                + " raised_by) VALUES (?, ?, ?, 'STAFF', ?, ?, ?, ?, ?, 'COMPUTED',"
                                + " ?)",
                        "arrear_event_id",
                        type,
                        cut(h.reference()),
                        Date.valueOf(h.effectiveFrom()),
                        h.scopeLocationId(),
                        h.sourceModule(),
                        h.sourceEntity(),
                        h.sourceKey(),
                        h.circularId(),
                        h.raisedBy());
        audit.record(
                h.raisedBy(),
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(id),
                                "event_status",
                                null,
                                "COMPUTED",
                                cut(h.reference()))
                        .module(ArrearRegisterService.SCREEN));
        return id;
    }

    /**
     * Plan 13 phase 13g (D13-14): the owning module's differentials are written as CALCULATED
     * details of a COMPUTED event; the details carry no original or recomputed amount, which is how
     * I-08 knows the event is not recomputed.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long known(Known k) {
        List<Long> open =
                jdbc.queryForList(
                        "SELECT arrear_event_id FROM arrear_event WHERE source_module = ? AND"
                                + " source_entity = ? AND source_key = ? AND event_status IN"
                                + " ('RAISED', 'COMPUTED', 'APPROVED')",
                        Long.class,
                        k.sourceModule(),
                        k.sourceEntity(),
                        k.sourceKey());
        if (!open.isEmpty()) {
            return open.getFirst();
        }
        Map<Integer, Long> components = new HashMap<>();
        for (Line l : k.lines()) {
            components.computeIfAbsent(l.payCode(), this::component);
        }
        List<String> months = k.lines().stream().map(Line::yymm).sorted().toList();
        String first = months.getFirst();
        String last = months.getLast();
        boolean beyond = beyondWindow(month(first).atDay(1));
        String status = beyond ? "RAISED" : "COMPUTED";
        Long location =
                jdbc.queryForObject(
                        "SELECT location_id FROM employee WHERE employee_id = ?",
                        Long.class,
                        k.employeeId());
        long type = eventType(k.eventCode());
        long id =
                GeneratedKeys.insert(
                        jdbc,
                        "INSERT INTO arrear_event (event_type_id, event_reference, effective_from,"
                                + " scope_payroll_type, scope_location_id, scope_from_yymm,"
                                + " scope_to_yymm, source_module, source_entity, source_key,"
                                + " beyond_window_flag, event_status, raised_by) VALUES (?, ?, ?,"
                                + " 'STAFF', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "arrear_event_id",
                        type,
                        cut(k.sourceModule() + " " + k.sourceKey() + ": " + k.reason().strip()),
                        Date.valueOf(month(first).atDay(1)),
                        location,
                        first,
                        last,
                        k.sourceModule(),
                        k.sourceEntity(),
                        k.sourceKey(),
                        beyond ? "Y" : "N",
                        status,
                        k.raisedBy());
        jdbc.update(
                "INSERT INTO arrear_event_member (arrear_event_id, employee_id) VALUES (?, ?)",
                id,
                k.employeeId());
        for (Line l : k.lines()) {
            jdbc.update(
                    "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                            + " differential_yymm, arrear_amount, period_date) VALUES (?, ?, ?, ?,"
                            + " ?, ?)",
                    id,
                    k.employeeId(),
                    components.get(l.payCode()),
                    l.yymm(),
                    l.amount(),
                    Date.valueOf(month(l.yymm()).atDay(1)));
        }
        audit.record(
                k.raisedBy(),
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(id),
                                "event_status",
                                null,
                                status,
                                cut(
                                        "Back-dated "
                                                + k.eventCode()
                                                + " "
                                                + first
                                                + " - "
                                                + last
                                                + ": "
                                                + k.reason().strip()
                                                + beyondNote(beyond)))
                        .module(ArrearRegisterService.SCREEN));
        return id;
    }

    /** The component of a pay code; none is a configuration error (R-RULE), never a skip. */
    private long component(int payCode) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT component_id FROM pay_component WHERE legacy_pay_code = ?",
                        Long.class,
                        payCode);
        if (ids.isEmpty()) {
            throw new FciException(
                    ArrearErrorCode.NOT_CONFIGURED,
                    new Object[] {"Pay component of pay code " + payCode},
                    List.of(),
                    null);
        }
        return ids.getFirst();
    }

    /** Computes the event once the raising transaction has committed (never inside it). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(Raised raised) {
        compute(raised.eventId(), raised.employeeId(), raised.by());
    }

    /**
     * I-08: the Maker computes a single-member event again (RAISED after a refusal, or COMPUTED and
     * not yet submitted): earlier CALCULATED details are REVERSED, never deleted (R-DEL).
     *
     * @return the reason the member cannot be computed, or empty when computed
     */
    public Optional<String> recompute(AccessContext context, long eventId) {
        Map<String, Object> e = register.requireEvent(context, eventId, "SUBMIT");
        Object status = e.get("EVENT_STATUS");
        requireInsideWindowOrException(e);
        if (!"RAISED".equals(status) && !"COMPUTED".equals(status)) {
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {status, "RAISED / COMPUTED"},
                    List.of(),
                    null);
        }
        List<Long> member =
                jdbc.queryForList(
                        "SELECT employee_id FROM arrear_event_member WHERE arrear_event_id = ?",
                        Long.class,
                        eventId);
        if (member.isEmpty()) {
            // A bulk event (DA arrear job) is recomputed by its job, not here.
            throw new FciException(
                    ArrearErrorCode.STATE, new Object[] {"BULK", "SINGLE_MEMBER"}, List.of(), null);
        }
        Integer stated =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = ? AND"
                                + " arrear_status = 'CALCULATED' AND recomputed_amount IS NULL",
                        Integer.class,
                        eventId);
        if (RowCounts.of(stated) > 0) {
            // D13-14: the owning module stated these amounts; a recompute would lose them.
            throw new FciException(
                    ArrearErrorCode.STATE,
                    new Object[] {"STATED_BY_SOURCE", "RECOMPUTABLE"},
                    List.of(),
                    null);
        }
        return compute(eventId, member.getFirst(), context.loginId());
    }

    /**
     * SRS-8-6: an event beyond the back-date limit is computed only once its Headquarters exception
     * is approved.
     */
    private void requireInsideWindowOrException(Map<String, Object> event) {
        if ("Y".equals(event.get("BEYOND_WINDOW_FLAG"))
                && event.get("HQ_EXCEPTION_APPROVAL_ID") == null) {
            throw new FciException(
                    ArrearErrorCode.BEYOND_WINDOW,
                    new Object[] {maxRetroMonths()},
                    List.of(),
                    null);
        }
    }

    /**
     * After the Headquarters exception of an event beyond the back-date limit is approved
     * (SRS-8-6): an event whose lines the owning module stated becomes COMPUTED; a single-member
     * event is computed. Runs in the decision's transaction.
     */
    void releaseException(long eventId, String by) {
        Integer stated =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arrear_detail WHERE arrear_event_id = ? AND"
                                + " arrear_status = 'CALCULATED' AND recomputed_amount IS NULL",
                        Integer.class,
                        eventId);
        if (RowCounts.of(stated) > 0) {
            jdbc.update(
                    "UPDATE arrear_event SET event_status = 'COMPUTED' WHERE arrear_event_id = ?"
                            + " AND event_status = 'RAISED'",
                    eventId);
            return;
        }
        List<Long> member =
                jdbc.queryForList(
                        "SELECT employee_id FROM arrear_event_member WHERE arrear_event_id = ?",
                        Long.class,
                        eventId);
        if (!member.isEmpty()) {
            events.publishEvent(new Raised(eventId, member.getFirst(), by));
        }
    }

    /**
     * The recompute in its own transaction; a refusal rolls it back and is recorded in a second.
     */
    Optional<String> compute(long eventId, long employeeId, String by) {
        try {
            own.executeWithoutResult(s -> differentials(eventId, employeeId, by));
            return Optional.empty();
        } catch (PendingSalary.NotComputable | StaffDeductions.NotComputable ex) {
            String reason = ex.getMessage();
            own.executeWithoutResult(
                    s ->
                            audit.record(
                                    by,
                                    AuditService.Entry.change(
                                                    ArrearRegisterService.ENTITY,
                                                    String.valueOf(eventId),
                                                    "event_status",
                                                    "RAISED",
                                                    "RAISED",
                                                    cut("Not computable: " + reason))
                                            .module(ArrearRegisterService.SCREEN)));
            return Optional.of(reason);
        }
    }

    private void differentials(long eventId, long employeeId, String by) {
        Map<String, Object> e =
                jdbc.queryForMap(
                        "SELECT event_status, effective_from FROM arrear_event WHERE"
                                + " arrear_event_id = ?",
                        eventId);
        String before = (String) e.get("EVENT_STATUS");
        if (!"RAISED".equals(before) && !"COMPUTED".equals(before)) {
            return;
        }
        LocalDate from =
                new Date(((java.util.Date) e.get("EFFECTIVE_FROM")).getTime()).toLocalDate();
        jdbc.update(
                "UPDATE arrear_detail SET arrear_status = 'REVERSED' WHERE arrear_event_id = ? AND"
                        + " arrear_status = 'CALCULATED'",
                eventId);
        Map<Integer, Long> payLinked = payLinkedComponents();
        List<String> months =
                jdbc.queryForList(
                        "SELECT r.payroll_yymm FROM payroll_result r JOIN payroll_cycle_instance c"
                                + " ON c.cycle_instance_id = r.cycle_instance_id WHERE"
                                + " r.employee_id = ? AND r.run_type = 'ACTUAL' AND"
                                + " r.result_status = 'CURRENT' AND c.cycle_status = 'LOCKED' AND"
                                + " r.payroll_yymm >= ? ORDER BY r.payroll_yymm",
                        String.class,
                        employeeId,
                        yymm(YearMonth.from(from)));
        String last = null;
        for (String month : months) {
            YearMonth ym =
                    YearMonth.of(
                            Integer.parseInt(month.substring(0, 4)),
                            Integer.parseInt(month.substring(4)));
            Map<Integer, BigDecimal> paid = paid(employeeId, month, payLinked);
            Map<Integer, BigDecimal> due = new HashMap<>();
            for (PendingSalary.Line l : salary.compute(employeeId, ym).lines()) {
                if (payLinked.containsKey(l.payCode())) {
                    due.merge(l.payCode(), l.amount(), BigDecimal::add);
                }
            }
            for (ArrearRules.Recomputed d : ArrearRules.differentials(paid, due)) {
                jdbc.update(
                        "INSERT INTO arrear_detail (arrear_event_id, employee_id, component_id,"
                                + " differential_yymm, original_amount, recomputed_amount,"
                                + " arrear_amount, period_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        eventId,
                        employeeId,
                        payLinked.get(d.payCode()),
                        month,
                        d.original(),
                        d.recomputed(),
                        d.arrear(),
                        Date.valueOf(ym.atDay(1)));
            }
            last = month;
        }
        jdbc.update(
                "UPDATE arrear_event SET event_status = 'COMPUTED', scope_to_yymm = ? WHERE"
                        + " arrear_event_id = ?",
                last,
                eventId);
        audit.record(
                by,
                AuditService.Entry.change(
                                ArrearRegisterService.ENTITY,
                                String.valueOf(eventId),
                                "event_status",
                                before,
                                "COMPUTED",
                                months.isEmpty()
                                        ? "No locked month since the effective date: nothing to"
                                                + " recompute"
                                        : "Recomputed " + months.getFirst() + " - " + last)
                        .module(ArrearRegisterService.SCREEN));
    }

    /** D13-7: pay code -> component id of the pay-linked earnings. */
    private Map<Integer, Long> payLinkedComponents() {
        Map<Integer, Long> out = new HashMap<>();
        jdbc.query(
                "SELECT legacy_pay_code, component_id FROM pay_component WHERE component_type ="
                        + " 'EARNING' AND payslip_group IN ('PAY', 'ALLOWANCES')",
                rs -> {
                    out.put(rs.getInt(1), rs.getLong(2));
                });
        return out;
    }

    /** What the month's ACTUAL result paid, pay-linked component by component. */
    private Map<Integer, BigDecimal> paid(
            long employeeId, String month, Map<Integer, Long> payLinked) {
        Map<Integer, BigDecimal> out = new HashMap<>();
        jdbc.query(
                "SELECT c.legacy_pay_code, rc.amount FROM payroll_result r JOIN"
                        + " payroll_result_component rc ON rc.payroll_result_id ="
                        + " r.payroll_result_id JOIN pay_component c ON c.component_id ="
                        + " rc.component_id WHERE r.employee_id = ? AND r.payroll_yymm = ? AND"
                        + " r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT'",
                rs -> {
                    int code = rs.getInt(1);
                    if (payLinked.containsKey(code)) {
                        out.merge(code, rs.getBigDecimal(2), BigDecimal::add);
                    }
                },
                employeeId,
                month);
        return out;
    }

    private static YearMonth month(String yymm) {
        return YearMonth.of(
                Integer.parseInt(yymm.substring(0, 4)), Integer.parseInt(yymm.substring(4)));
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static String cut(String text) {
        return text.length() <= 100 ? text : text.substring(0, 100);
    }
}
