package in.gov.fci.payroll.leave.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.leave.business.LeaveArrearRules;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.leave.business.LeaveParameterRules;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.payroll.api.StaffEarnings;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I-24 Leave Encashment Arrear (plan 13 phase 13d, D13-10; SRS-8-23, SRS-8-24, SRS-1-43;
 * BR-ARR-LE-001 to 004). The Payroll Maker runs the arrear for one employee or the unit against a
 * revision order (M-25) and its effective date: every encashment dated on or after it is
 * recalculated month by month on the pay as revised (the month recomputed on the rates and master
 * as approved now, {@code PendingSalary}; the M-20 pay elements and divisor of the encashment's
 * date); a month with a further difference becomes one CALCULATED record - the calculation sheet -
 * under one LEAVE_ENCASHMENT arrear event of the run, an earlier record of the month not yet
 * submitted is CANCELLED (never deleted). A member who cannot be computed is reported, never
 * guessed. The Maker selects one or many records and submits them together; the Payroll Checker
 * approves (route {@value #ACTION}). The invoices to APPS and the TDS follow with modules 23 and 08
 * (PENDING 13-le-apps): records stay APPROVED with no invoice.
 */
@Service
@Loggable
public class LeaveArrearService {

    public static final String SCREEN = "I-24";
    public static final String ACTION = "I24_SUBMIT";
    static final String ENTITY = "leave_encashment_arrear_batch";
    private static final int BASIC = 101;
    private static final int DA = 111;

    /** A run: one member (EIS) or the unit, against a revision order. */
    public record Run(String eis, Long circularId, LocalDate revisionEffective, String reason) {}

    /** A member the run could not compute, with the reason. */
    public record Skipped(String eis, String reason) {}

    public record RunResult(long eventId, int members, int records, List<Skipped> skipped) {}

    /** One record of the list: the calculation sheet (BR-ARR-LE-002). */
    public record Row(
            long id,
            String recordNumber,
            String eis,
            String name,
            LocalDate separationDate,
            String order,
            LocalDate revisionEffective,
            String month,
            BigDecimal days,
            BigDecimal oldBasic,
            BigDecimal oldDa,
            BigDecimal revisedBasic,
            BigDecimal revisedDa,
            BigDecimal paid,
            BigDecimal due,
            BigDecimal arrear,
            BigDecimal tds,
            String status,
            String ineligibleReason,
            String batch) {}

    public record Filter(String status, String eis) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final Circulars circulars;
    private final PendingSalary salary;
    private final StaffEarnings earnings;
    private final LeaveParameters parameters;
    private final RetroEvents events;
    private final TransactionTemplate reading;
    private final TransactionTemplate atomic;

    public LeaveArrearService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            Circulars circulars,
            PendingSalary salary,
            StaffEarnings earnings,
            LeaveParameters parameters,
            RetroEvents events,
            PlatformTransactionManager transactions) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.circulars = circulars;
        this.salary = salary;
        this.earnings = earnings;
        this.parameters = parameters;
        this.events = events;
        // A member's computation reads in a transaction of its own, so a refusal inside it cannot
        // mark the run's write transaction rollback-only.
        this.reading = new TransactionTemplate(transactions);
        this.reading.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reading.setReadOnly(true);
        this.atomic = new TransactionTemplate(transactions);
    }

    /**
     * SRS-8-23: runs the arrear. Every member is computed first (reads only): a member who cannot
     * be computed is reported and nothing is written for them. The event and all the records are
     * then written in one transaction: all or nothing.
     */
    public RunResult run(AccessContext context, Run run) {
        policy.require(context, SCREEN, "COMPUTE");
        if (run.circularId() == null || run.revisionEffective() == null) {
            throw new FciException(
                    LeaveErrorCode.ARREAR_SELECTION,
                    new Object[] {"the revision order and its effective date"},
                    List.of(),
                    null);
        }
        circulars.requireActive(run.circularId());
        MapSqlParameterSource scope =
                scope(context).addValue("from", Date.valueOf(run.revisionEffective()));
        String eis = run.eis() == null || run.eis().isBlank() ? null : run.eis().strip();
        scope.addValue("eis", eis, Types.VARCHAR);
        List<Map<String, Object>> members =
                named.queryForList(
                        "SELECT DISTINCT e.employee_id, e.emp_num, e.separation_date FROM employee"
                            + " e JOIN leave_encashment x ON x.employee_id = e.employee_id WHERE"
                            + " (:all = 'Y' OR e.location_id IN (:locations)) AND (:eis IS NULL OR"
                            + " e.emp_num = :eis) AND x.period_from >= :from ORDER BY e.emp_num",
                        scope);
        if (eis != null && members.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        // Phase 1, reads only: each member's months are computed; a member who cannot be computed
        // is reported and nothing is written for them.
        Map<Long, List<Line>> plans = new java.util.LinkedHashMap<>();
        Map<Long, LocalDate> separations = new java.util.HashMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Map<String, Object> m : members) {
            long employeeId = ((Number) m.get("EMPLOYEE_ID")).longValue();
            LocalDate separation = date(m.get("SEPARATION_DATE"));
            try {
                plans.put(employeeId, reading.execute(s -> member(employeeId, separation, run)));
                separations.put(employeeId, separation);
            } catch (PendingSalary.NotComputable
                    | StaffDeductions.NotComputable
                    | FciException ex) {
                skipped.add(new Skipped((String) m.get("EMP_NUM"), ex.getMessage()));
            }
        }
        // Phase 2, all or nothing: the event, the superseded records and every new record in one
        // transaction; a failure leaves none of them.
        String key = "LEA-RUN-" + UUID.randomUUID().toString().substring(0, 8);
        long[] written = new long[2];
        atomic.executeWithoutResult(
                s -> {
                    written[0] =
                            events.header(
                                    new RetroEvents.Header(
                                            "LEAVE_ENCASHMENT",
                                            context.locationId(),
                                            run.revisionEffective(),
                                            "LEAVE",
                                            "leave_encashment_arrear",
                                            key,
                                            "Leave encashment arrear "
                                                    + key
                                                    + ": "
                                                    + (run.reason() == null
                                                            ? ""
                                                            : run.reason().strip()),
                                            run.circularId(),
                                            context.loginId()));
                    plans.forEach(
                            (employeeId, lines) -> {
                                for (Line l : lines) {
                                    written[1] +=
                                            write(
                                                    context.loginId(),
                                                    written[0],
                                                    employeeId,
                                                    separations.get(employeeId),
                                                    run,
                                                    l);
                                }
                            });
                });
        long eventId = written[0];
        int records = (int) written[1];
        return new RunResult(eventId, members.size(), records, skipped);
    }

    /** One member's months, computed (no write). */
    private List<Line> member(long employeeId, LocalDate separation, Run run) {
        List<Map<String, Object>> encashments =
                named.queryForList(
                        "SELECT period_from, days_encashed, amount_paid FROM leave_encashment WHERE"
                            + " employee_id = :id AND period_from >= :from ORDER BY period_from",
                        new MapSqlParameterSource("id", employeeId)
                                .addValue("from", Date.valueOf(run.revisionEffective())));
        Map<YearMonth, Object[]> months = new TreeMap<>();
        for (Map<String, Object> x : encashments) {
            LocalDate day = date(x.get("PERIOD_FROM"));
            if (!LeaveArrearRules.affected(run.revisionEffective(), day)) {
                continue;
            }
            Object[] acc =
                    months.computeIfAbsent(
                            YearMonth.from(day),
                            k -> new Object[] {day, BigDecimal.ZERO, BigDecimal.ZERO});
            acc[1] = ((BigDecimal) acc[1]).add((BigDecimal) x.get("DAYS_ENCASHED"));
            acc[2] = ((BigDecimal) acc[2]).add((BigDecimal) x.get("AMOUNT_PAID"));
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<YearMonth, Object[]> e : months.entrySet()) {
            lines.add(month(employeeId, separation, run, e.getKey(), e.getValue()));
        }
        return lines;
    }

    private Line month(
            long employeeId, LocalDate separation, Run run, YearMonth month, Object[] acc) {
        LocalDate day = (LocalDate) acc[0];
        BigDecimal days = (BigDecimal) acc[1];
        String yymm = yymm(month);
        MapSqlParameterSource key =
                new MapSqlParameterSource("id", employeeId).addValue("yymm", yymm);
        BigDecimal earlier =
                named.queryForObject(
                        "SELECT COALESCE(SUM(arrear_amount), 0) FROM leave_encashment_arrear WHERE"
                                + " employee_id = :id AND arrear_yymm = :yymm AND le_status IN"
                                + " ('SUBMITTED', 'APPROVED', 'INVOICED', 'PAID')",
                        key,
                        BigDecimal.class);
        BigDecimal paid = ((BigDecimal) acc[2]).add(earlier == null ? BigDecimal.ZERO : earlier);
        java.util.Optional<String> ineligible =
                LeaveArrearRules.ineligible(run.revisionEffective(), separation);
        if (ineligible.isPresent()) {
            return new Line(yymm, days, null, paid, null, ineligible.get(), true);
        }
        BigDecimal divisor =
                parameters
                        .number(LeaveParameters.ENCASH_DIVISOR, null, day)
                        .filter(d -> d.signum() > 0)
                        .orElseThrow(() -> notConfigured("Encashment divisor"));
        Set<Integer> elements =
                parameters
                        .text(LeaveParameters.ENCASH_PAY_ELEMENTS, null, day)
                        .map(t -> Set.copyOf(LeaveParameterRules.payCodes(t)))
                        .orElseThrow(() -> notConfigured("Encashment pay elements"));
        Map<Integer, BigDecimal> revised = new TreeMap<>();
        for (PendingSalary.Line l : salary.compute(employeeId, month).lines()) {
            if (elements.contains(l.payCode())) {
                revised.merge(l.payCode(), l.amount(), BigDecimal::add);
            }
        }
        BigDecimal revisedPay = revised.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (revisedPay.signum() <= 0) {
            throw notConfigured("The member's revised pay elements in " + month);
        }
        Map<Integer, BigDecimal> old = new TreeMap<>();
        List<StaffEarnings.Month> paidMonths =
                earnings.months(employeeId, yymm(month.minusMonths(12)), yymm);
        if (!paidMonths.isEmpty()) {
            for (StaffEarnings.Line l : paidMonths.getLast().lines()) {
                if (l.payCode() == BASIC || l.payCode() == DA) {
                    old.merge(l.payCode(), l.amount(), BigDecimal::add);
                }
            }
        }
        BigDecimal due = LeaveArrearRules.due(days, revisedPay, divisor);
        java.util.Optional<BigDecimal> arrear = LeaveArrearRules.arrear(due, paid);
        if (arrear.isEmpty()) {
            return new Line(yymm, days, null, paid, due, null, false);
        }
        return new Line(
                yymm,
                days,
                new BigDecimal[] {old.get(BASIC), old.get(DA), revised.get(BASIC), revised.get(DA)},
                paid,
                due,
                null,
                true);
    }

    /**
     * One computed month of a member, to be written in the run's transaction.
     *
     * @param pay old Basic, old DA, revised Basic, revised DA; null when not eligible
     * @param record false when nothing is due: the month only supersedes an earlier record
     */
    private record Line(
            String yymm,
            BigDecimal days,
            BigDecimal[] pay,
            BigDecimal paid,
            BigDecimal due,
            String ineligible,
            boolean record) {}

    /** Writes one month: the earlier unsubmitted record superseded, then this run's record. */
    private int write(
            String by, long eventId, long employeeId, LocalDate separation, Run run, Line l) {
        // BR-ARR-LE-003: an earlier record not yet submitted is replaced by this run's.
        named.update(
                "UPDATE leave_encashment_arrear SET le_status = 'CANCELLED', ineligible_reason ="
                        + " 'Superseded by a later run', row_version = row_version + 1 WHERE"
                        + " employee_id = :id AND arrear_yymm = :yymm AND le_status ="
                        + " 'CALCULATED'",
                new MapSqlParameterSource("id", employeeId).addValue("yymm", l.yymm()));
        if (!l.record()) {
            return 0;
        }
        insert(
                by,
                eventId,
                employeeId,
                separation,
                run,
                l.yymm(),
                l.days(),
                l.pay(),
                l.paid(),
                l.due(),
                l.ineligible());
        return 1;
    }

    private void insert(
            String by,
            long eventId,
            long employeeId,
            LocalDate separation,
            Run run,
            String yymm,
            BigDecimal days,
            BigDecimal[] pay,
            BigDecimal paid,
            BigDecimal due,
            String ineligible) {
        String provisional = "LEA-TMP-" + UUID.randomUUID().toString().substring(0, 18);
        MapSqlParameterSource p =
                new MapSqlParameterSource("number", provisional)
                        .addValue("employee", employeeId)
                        .addValue("event", eventId)
                        .addValue("circular", run.circularId())
                        .addValue("yymm", yymm)
                        .addValue(
                                "separation",
                                separation == null ? null : Date.valueOf(separation),
                                Types.DATE)
                        .addValue("effective", Date.valueOf(run.revisionEffective()))
                        .addValue("eligible", ineligible == null ? "Y" : "N")
                        .addValue("reason", ineligible, Types.VARCHAR)
                        .addValue("days", days)
                        .addValue("oldBasic", pay == null ? null : pay[0], Types.NUMERIC)
                        .addValue("oldDa", pay == null ? null : pay[1], Types.NUMERIC)
                        .addValue("newBasic", pay == null ? null : pay[2], Types.NUMERIC)
                        .addValue("newDa", pay == null ? null : pay[3], Types.NUMERIC)
                        .addValue("paid", paid)
                        .addValue("due", due, Types.NUMERIC)
                        .addValue("arrear", due == null ? null : due.subtract(paid), Types.NUMERIC)
                        .addValue("status", ineligible == null ? "CALCULATED" : "NOT_ELIGIBLE");
        named.update(
                "INSERT INTO leave_encashment_arrear (record_number, employee_id, arrear_event_id,"
                    + " circular_order_id, arrear_yymm, separation_date, revision_effective_date,"
                    + " eligible_flag, ineligible_reason, encashable_days, old_basic, old_da,"
                    + " revised_basic, revised_da, amount_paid, amount_due, arrear_amount,"
                    + " le_status) VALUES (:number, :employee, :event, :circular, :yymm,"
                    + " :separation, :effective, :eligible, :reason, :days, :oldBasic, :oldDa,"
                    + " :newBasic, :newDa, :paid, :due, :arrear, :status)",
                p);
        Long id =
                named.queryForObject(
                        "SELECT le_arrear_id FROM leave_encashment_arrear WHERE record_number ="
                                + " :number",
                        p,
                        Long.class);
        String number =
                String.format(
                        "LEA-%04d-%05d",
                        YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).getYear(),
                        java.util.Objects.requireNonNullElse(id, 0L));
        named.update(
                "UPDATE leave_encashment_arrear SET record_number = :final WHERE le_arrear_id ="
                        + " :id",
                new MapSqlParameterSource("final", number).addValue("id", id));
        audit.record(
                by,
                AuditService.Entry.change(
                                "leave_encashment_arrear",
                                number,
                                "le_status",
                                null,
                                ineligible == null ? "CALCULATED" : "NOT_ELIGIBLE",
                                ineligible == null
                                        ? "Leave encashment arrear of " + yymm
                                        : ineligible)
                        .module(SCREEN));
    }

    /** The records of the session's offices (all at Headquarters), one page. */
    @Transactional(readOnly = true)
    public Paged<Row> records(AccessContext context, Filter filter, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = filtered(context, filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<Row> rows =
                named.query(
                        SELECT
                                + FROM
                                + WHERE
                                + " ORDER BY a.le_arrear_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        p,
                        (rs, i) -> row(rs));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * SRS-8-24: the Maker submits one or many CALCULATED records together for the Payroll Checker.
     */
    @Transactional
    public SubmittedApproval submit(AccessContext context, List<Long> ids, String remark) {
        policy.require(context, SCREEN, "SUBMIT");
        if (ids == null || ids.isEmpty() || remark == null || remark.isBlank()) {
            throw new FciException(
                    LeaveErrorCode.ARREAR_SELECTION,
                    new Object[] {"records and remark"},
                    List.of(),
                    null);
        }
        MapSqlParameterSource p = filtered(context, new Filter(null, null)).addValue("ids", ids);
        List<Map<String, Object>> found =
                named.queryForList(
                        "SELECT a.le_arrear_id, a.record_number, a.le_status, a.arrear_amount"
                                + FROM
                                + WHERE
                                + " AND a.le_arrear_id IN (:ids)",
                        p);
        if (found.size() != Set.copyOf(ids).size()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : found) {
            if (!"CALCULATED".equals(r.get("LE_STATUS"))) {
                throw new FciException(
                        LeaveErrorCode.ARREAR_STATE,
                        new Object[] {r.get("RECORD_NUMBER"), r.get("LE_STATUS")},
                        List.of(),
                        null);
            }
            total = total.add((BigDecimal) r.get("ARREAR_AMOUNT"));
        }
        String batch = "LEB-" + UUID.randomUUID().toString().substring(0, 13);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        ACTION,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        batch,
                                        ActionType.UPDATE,
                                        context.locationId(),
                                        null,
                                        LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),
                                        remark.strip())
                                .withImpact(total),
                        (OtpProof) null);
        // Oracle review F4: the CALCULATED check above is a plain read, so two Makers can both
        // pass it. The state is checked again in the UPDATE itself; a record another submission
        // took meanwhile is not counted, and the whole submission (its approval too) rolls back.
        int submitted =
                named.update(
                        "UPDATE leave_encashment_arrear SET le_status = 'SUBMITTED',"
                                + " submission_batch_ref = :batch, row_version = row_version + 1"
                                + " WHERE le_arrear_id IN (:ids) AND le_status = 'CALCULATED'",
                        new MapSqlParameterSource("batch", batch).addValue("ids", ids));
        if (submitted != Set.copyOf(ids).size()) {
            throw new FciException(CommonErrorCode.PRECONDITION_FAILED);
        }
        return item;
    }

    /** SRS-8-24: the Checker's decision on a batch: APPROVED, or back to CALCULATED. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY)) {
            return;
        }
        MapSqlParameterSource p =
                new MapSqlParameterSource("batch", d.entityKey())
                        .addValue("approval", d.approvalId());
        if (d.approved()) {
            named.update(
                    "UPDATE leave_encashment_arrear SET le_status = 'APPROVED', approval_id ="
                            + " :approval, row_version = row_version + 1 WHERE"
                            + " submission_batch_ref = :batch AND le_status = 'SUBMITTED'",
                    p);
        } else {
            named.update(
                    "UPDATE leave_encashment_arrear SET le_status = 'CALCULATED',"
                            + " submission_batch_ref = NULL, row_version = row_version + 1 WHERE"
                            + " submission_batch_ref = :batch AND le_status = 'SUBMITTED'",
                    p);
        }
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                "leave_encashment_arrear",
                                d.entityKey(),
                                "le_status",
                                "SUBMITTED",
                                d.approved() ? "APPROVED" : "CALCULATED",
                                d.approved() ? "Approved" : "Not approved: " + d.reason())
                        .approval(d.approvalId())
                        .module(SCREEN));
    }

    /** SRS-G-13: the rows of the LE Arrear Invoice Report (checked against the export limit). */
    @Transactional(readOnly = true)
    public long reportSize(AccessContext context, Filter filter) {
        policy.require(context, SCREEN, "EXPORT");
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + FROM + WHERE, filtered(context, filter), Long.class);
        return RowCounts.of(n);
    }

    /**
     * SRS-8-24 (Annexure A.11): the LE Arrear Invoice Report - each record's calculation sheet with
     * its invoice columns (empty until module 23 sends invoices to APPS).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void report(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        MapSqlParameterSource p = filtered(context, filter);
        Long expected = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        sink.header(
                "record",
                "eis",
                "member",
                "separation_date",
                "order",
                "revision_effective",
                "month",
                "encashable_days",
                "old_basic",
                "old_da",
                "revised_basic",
                "revised_da",
                "amount_paid",
                "amount_due",
                "arrear",
                "tds",
                "status",
                "invoice_number",
                "invoice_status",
                "invoice_sent_to_apps");
        named.query(
                SELECT + FROM + WHERE + " ORDER BY a.le_arrear_id",
                p,
                rs -> {
                    Row r = row(rs);
                    try {
                        sink.row(
                                r.recordNumber(),
                                r.eis(),
                                r.name(),
                                r.separationDate() == null ? "" : r.separationDate().toString(),
                                nz(r.order()),
                                r.revisionEffective().toString(),
                                r.month(),
                                r.days(),
                                r.oldBasic(),
                                r.oldDa(),
                                r.revisedBasic(),
                                r.revisedDa(),
                                r.paid(),
                                r.due(),
                                r.arrear(),
                                r.tds(),
                                r.status(),
                                "",
                                "NOT_RAISED",
                                "");
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    // ---------------------------------------------------------------- helpers

    private static final String SELECT =
            "SELECT a.le_arrear_id, a.record_number, m.emp_num, m.first_name, m.last_name,"
                    + " a.separation_date, c.circular_number, a.revision_effective_date,"
                    + " a.arrear_yymm, a.encashable_days, a.old_basic, a.old_da, a.revised_basic,"
                    + " a.revised_da, a.amount_paid, a.amount_due, a.arrear_amount, a.tds_amount,"
                    + " a.le_status, a.ineligible_reason, a.submission_batch_ref";

    private static final String FROM =
            " FROM leave_encashment_arrear a JOIN employee m ON m.employee_id = a.employee_id LEFT"
                    + " JOIN circular_order c ON c.circular_order_id = a.circular_order_id";

    private static final String WHERE =
            " WHERE (:all = 'Y' OR m.location_id IN (:locations)) AND (:status IS NULL OR"
                    + " a.le_status = :status) AND (:eis IS NULL OR m.emp_num = :eis)";

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date sep = rs.getDate("separation_date");
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        return new Row(
                rs.getLong("le_arrear_id"),
                rs.getString("record_number"),
                rs.getString("emp_num"),
                ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip(),
                sep == null ? null : sep.toLocalDate(),
                rs.getString("circular_number"),
                rs.getDate("revision_effective_date").toLocalDate(),
                rs.getString("arrear_yymm"),
                rs.getBigDecimal("encashable_days"),
                rs.getBigDecimal("old_basic"),
                rs.getBigDecimal("old_da"),
                rs.getBigDecimal("revised_basic"),
                rs.getBigDecimal("revised_da"),
                rs.getBigDecimal("amount_paid"),
                rs.getBigDecimal("amount_due"),
                rs.getBigDecimal("arrear_amount"),
                rs.getBigDecimal("tds_amount"),
                rs.getString("le_status"),
                rs.getString("ineligible_reason"),
                rs.getString("submission_batch_ref"));
    }

    private MapSqlParameterSource filtered(AccessContext context, Filter filter) {
        return scope(context)
                .addValue("status", blank(filter.status()), Types.VARCHAR)
                .addValue("eis", blank(filter.eis()), Types.VARCHAR);
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        return new Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                LeaveErrorCode.NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }
}
