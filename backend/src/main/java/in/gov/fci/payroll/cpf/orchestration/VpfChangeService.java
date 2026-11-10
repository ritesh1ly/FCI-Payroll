package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.VpfRules;
import in.gov.fci.payroll.cpf.business.VpfRules.Status;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's VPF subscription change (plan 14 phase 14c; BR-ESEVA-006, SRS-17-3, SRS-6-1; DDL
 * {@code vpf_change_request}; D14-4). The member asks on E-01 for a new monthly VPF from the next
 * open payroll month; the Payroll Maker of the member's office verifies it on E-02 and sends it to
 * the Payroll Checker (route E02_VPF, the same maker-checker as any staff change), or returns it to
 * the member; approved, the subscription of the CPF ledger of that year changes and the request is
 * APPLIED. One change of a member at a time. The engine keeps refusing employee + VPF above the
 * CPF.VPF.MAX limit (SRS-6-1, CpfContributionDeduction).
 *
 * <p>OPEN-QUESTION (PENDING 14-vpf-frequency): no source says how often a member may change the VPF
 * in a year; every change is accepted, one at a time.
 */
@Service
@Loggable
public class VpfChangeService {

    /** DDL table and audit entity. */
    public static final String ENTITY = "vpf_change_request";

    /** The staff screen (D14-1) and its route. */
    static final String STAFF_SCREEN = "E-02";

    static final String ROUTE = "E02_VPF";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    /** One request as the member and the staff see it. */
    public record Request(
            long id,
            String eis,
            String member,
            String office,
            BigDecimal currentAmount,
            BigDecimal requestedAmount,
            String effectiveFromYymm,
            String status,
            boolean awaitingChecker,
            String decisionReason,
            LocalDateTime submittedAt) {}

    /** E-01: the subscription in force and the member's requests, newest first. */
    public record Mine(
            String finYear, BigDecimal currentAmount, boolean cpfMember, List<Request> requests) {}

    private static final String SELECT =
            "SELECT r.vpf_change_request_id, e.emp_num, e.first_name, e.last_name, l.location_code,"
                + " l.location_name, r.current_vpf_amount, r.requested_vpf_amount,"
                + " r.effective_from_yymm, r.request_status, CASE WHEN q.status ="
                + " 'PENDING_APPROVAL' THEN 'Y' ELSE 'N' END AS awaiting, r.decision_reason,"
                + " r.submitted_at FROM vpf_change_request r JOIN employee e ON e.employee_id ="
                + " r.employee_id LEFT JOIN org_location l ON l.location_id = e.location_id LEFT"
                + " JOIN approval_queue q ON q.approval_id = r.approval_id";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public VpfChangeService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            MakerChecker approvals,
            AuditService audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.approvals = approvals;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the member (E-01)

    /** E-01: the member's VPF of the current year and the requests. */
    @Transactional(readOnly = true)
    public Mine mine(AccessContext context) {
        long me = self(context, "SELF");
        YearMonth month = YearMonth.from(LocalDate.now(clock.withZone(IST)));
        String year = VpfRules.finYear(month);
        List<BigDecimal> current = subscription(me, year);
        List<Request> requests =
                jdbc.query(
                        SELECT
                                + " WHERE r.employee_id = ? ORDER BY r.vpf_change_request_id DESC"
                                + " FETCH FIRST 10 ROWS ONLY",
                        VpfChangeService::request,
                        me);
        return new Mine(
                year,
                current.isEmpty() || current.getFirst() == null
                        ? BigDecimal.ZERO
                        : current.getFirst(),
                !current.isEmpty(),
                requests);
    }

    /** E-01: the member asks for a new monthly VPF from the next open payroll month. */
    @Transactional
    public Request request(AccessContext context, BigDecimal amount) {
        long me = self(context, "CREATE");
        EmployeeDirectory.Member member =
                employees.member(me).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        List<Long> open =
                jdbc.queryForList(
                        "SELECT vpf_change_request_id FROM vpf_change_request WHERE employee_id = ?"
                                + " AND request_status = 'SUBMITTED'",
                        Long.class,
                        me);
        if (!open.isEmpty()) {
            throw new FciException(
                    CpfErrorCode.VPF_PENDING,
                    new Object[] {"#" + open.getFirst()},
                    List.of(),
                    null);
        }
        YearMonth effective = effective(member.locationId());
        String year = VpfRules.finYear(effective);
        List<Map<String, Object>> headers =
                jdbc.queryForList(
                        "SELECT pf_header_id, vcpf_subscription_amt FROM employee_pf_header WHERE"
                                + " employee_id = ? AND fin_year = ? AND fund_type = 'CPF' ORDER BY"
                                + " pf_header_id DESC",
                        me,
                        year);
        if (headers.isEmpty()) {
            throw invalid("NO_CPF_LEDGER " + year);
        }
        BigDecimal current = (BigDecimal) headers.getFirst().get("VCPF_SUBSCRIPTION_AMT");
        VpfRules.defect(amount, current)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        String from = VpfRules.yymm(effective);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    "INSERT INTO vpf_change_request (employee_id, pf_header_id,"
                                            + " current_vpf_amount, requested_vpf_amount,"
                                            + " effective_from_yymm, request_status, submitted_at)"
                                            + " VALUES (?, ?, ?, ?, ?, 'SUBMITTED', ?)",
                                    new String[] {"vpf_change_request_id"});
                    ps.setLong(1, me);
                    ps.setLong(2, ((Number) headers.getFirst().get("PF_HEADER_ID")).longValue());
                    ps.setObject(3, current, Types.DECIMAL);
                    ps.setBigDecimal(4, amount);
                    ps.setString(5, from);
                    ps.setTimestamp(6, now);
                    return ps;
                },
                key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException("The VPF change just written has no id");
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id.longValue()),
                                "requested_vpf_amount",
                                current == null ? null : current.toPlainString(),
                                amount.toPlainString(),
                                "VPF change from " + from + " asked in eSeva")
                        .module("E-01"));
        events.publishEvent(
                new SelfServiceEvents.Submitted(
                        me,
                        SelfServiceEvents.Type.VPF_SUBSCRIPTION,
                        ENTITY,
                        id.longValue(),
                        null,
                        id.longValue(),
                        null,
                        "VPF " + amount.toPlainString() + " a month from " + from,
                        null,
                        context.loginId()));
        return find(id.longValue());
    }

    // ---------------------------------------------------------------- the Payroll Maker (E-02)

    /** E-02: the requests of the session's offices, newest first, paged. */
    @Transactional(readOnly = true)
    public Paged<Request> page(AccessContext context, String status, PageSpec spec) {
        policy.require(context, STAFF_SCREEN, "VIEW");
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue(
                                "status",
                                status == null || status.isBlank() ? null : status,
                                Types.VARCHAR);
        String where =
                " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:status IS NULL OR"
                        + " r.request_status = :status)";
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM vpf_change_request r JOIN employee e ON e.employee_id"
                                + " = r.employee_id"
                                + where,
                        p,
                        Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Request> rows =
                named.query(
                        SELECT
                                + where
                                + " ORDER BY r.vpf_change_request_id DESC OFFSET :offset ROWS"
                                + " FETCH NEXT :size ROWS ONLY",
                        p,
                        VpfChangeService::request);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** E-02: the Maker verifies the request and sends it to the Payroll Checker. */
    @Transactional
    public Request verify(AccessContext context, long id) {
        Map<String, Object> r = requireAt(context, id, "SUBMIT");
        Status status = Status.valueOf((String) r.get("REQUEST_STATUS"));
        if (!VpfRules.verifiable(status, "Y".equals(r.get("AWAITING")))) {
            throw state(status, "SUBMITTED");
        }
        BigDecimal amount = (BigDecimal) r.get("REQUESTED_VPF_AMOUNT");
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                STAFF_SCREEN,
                                "SUBMIT",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                ((Number) r.get("LOCATION_ID")).longValue(),
                                null,
                                LocalDate.now(clock.withZone(IST)),
                                "VPF "
                                        + amount.toPlainString()
                                        + " a month from "
                                        + r.get("EFFECTIVE_FROM_YYMM")
                                        + " for "
                                        + r.get("EMP_NUM")),
                        null);
        jdbc.update(
                "UPDATE vpf_change_request SET approval_id = ? WHERE vpf_change_request_id = ?",
                item.id(),
                id);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "approval_id",
                                null,
                                String.valueOf(item.id()),
                                "Verified and sent to the Payroll Checker")
                        .approval(item.id())
                        .module(STAFF_SCREEN));
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        ENTITY, id, SelfServiceEvents.Status.ROUTED, item.id(), context.loginId()));
        return find(id);
    }

    /** E-02: the Maker returns the request to the member with the reason (it ends there). */
    @Transactional
    public Request returnToMember(AccessContext context, long id, String reason) {
        Map<String, Object> r = requireAt(context, id, "SUBMIT");
        Status status = Status.valueOf((String) r.get("REQUEST_STATUS"));
        if (!VpfRules.verifiable(status, "Y".equals(r.get("AWAITING")))) {
            throw state(status, "SUBMITTED");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500 || !PRINTABLE.matcher(why).matches()) {
            throw invalid("REASON");
        }
        decided(id, Status.RETURNED, why, context.loginId(), null);
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        ENTITY, id, SelfServiceEvents.Status.REJECTED, context.loginId()));
        return find(id);
    }

    // ---------------------------------------------------------------- the Checker's decision

    /**
     * BR-ESEVA-006: approved, the subscription of the year's CPF ledger changes (every ledger of
     * the member that year: a parent-zone change may have opened a second) and the request is
     * APPLIED; rejected, it ends; returned, it goes back to the Maker (still SUBMITTED).
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long id = Long.parseLong(decision.entityKey());
        if (decision.outcome() == ApprovalStatus.RETURNED) {
            return;
        }
        if (!decision.approved()) {
            decided(
                    id,
                    Status.REJECTED,
                    decision.reason() == null ? "Rejected" : decision.reason(),
                    decision.checkerLoginId(),
                    decision.approvalId());
            return;
        }
        Map<String, Object> r =
                jdbc.queryForMap(
                        "SELECT employee_id, requested_vpf_amount, effective_from_yymm FROM"
                                + " vpf_change_request WHERE vpf_change_request_id = ?",
                        id);
        String from = (String) r.get("EFFECTIVE_FROM_YYMM");
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(from.substring(0, 4)),
                        Integer.parseInt(from.substring(4)));
        jdbc.update(
                "UPDATE employee_pf_header SET vcpf_subscription_amt = ? WHERE employee_id = ? AND"
                        + " fin_year = ? AND fund_type = 'CPF'",
                r.get("REQUESTED_VPF_AMOUNT"),
                r.get("EMPLOYEE_ID"),
                VpfRules.finYear(month));
        decided(id, Status.APPLIED, null, decision.checkerLoginId(), decision.approvalId());
    }

    // ---------------------------------------------------------------- helpers

    private void decided(long id, Status to, String reason, String actor, Long approval) {
        String before =
                jdbc.queryForObject(
                        "SELECT request_status FROM vpf_change_request WHERE"
                                + " vpf_change_request_id = ?",
                        String.class,
                        id);
        jdbc.update(
                "UPDATE vpf_change_request SET request_status = ?, decision_reason = ? WHERE"
                        + " vpf_change_request_id = ?",
                to.name(),
                reason,
                id);
        AuditService.Entry entry =
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "request_status",
                                before,
                                to.name(),
                                reason == null ? to.name() : reason)
                        .module(STAFF_SCREEN);
        audit.record(actor, approval == null ? entry : entry.approval(approval));
    }

    /** The request in the user's office scope (else not found, the attempt logged). */
    private Map<String, Object> requireAt(AccessContext context, long id, String action) {
        Map<String, Object> r =
                jdbc
                        .queryForList(
                                "SELECT r.request_status, r.employee_id, r.requested_vpf_amount,"
                                        + " r.effective_from_yymm, e.emp_num, e.location_id, CASE"
                                        + " WHEN q.status = 'PENDING_APPROVAL' THEN 'Y' ELSE 'N'"
                                        + " END AS awaiting FROM vpf_change_request r JOIN employee"
                                        + " e ON e.employee_id = r.employee_id LEFT JOIN"
                                        + " approval_queue q ON q.approval_id = r.approval_id WHERE"
                                        + " r.vpf_change_request_id = ?",
                                id)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Object location = r.get("LOCATION_ID");
        policy.requireRecordAt(
                context,
                STAFF_SCREEN,
                action,
                location == null ? null : ((Number) location).longValue());
        return r;
    }

    /** D14-4: this month, or the next once this month's payroll of the member's unit is locked. */
    private YearMonth effective(Long locationId) {
        YearMonth now = YearMonth.from(LocalDate.now(clock.withZone(IST)));
        boolean locked =
                locationId != null
                        && org.cycleUnitOf(locationId)
                                .flatMap(unit -> payroll.cycle(unit.getId(), VpfRules.yymm(now)))
                                .map(PayrollRecords.Cycle::isLocked)
                                .orElse(false);
        return VpfRules.effectiveMonth(now, locked);
    }

    private List<BigDecimal> subscription(long employeeId, String year) {
        return jdbc.query(
                "SELECT vcpf_subscription_amt FROM employee_pf_header WHERE employee_id = ? AND"
                        + " fin_year = ? AND fund_type = 'CPF' ORDER BY pf_header_id DESC",
                (rs, i) -> rs.getBigDecimal(1),
                employeeId,
                year);
    }

    private Request find(long id) {
        return jdbc.queryForObject(
                SELECT + " WHERE r.vpf_change_request_id = ?", VpfChangeService::request, id);
    }

    private static Request request(ResultSet rs, int i) throws SQLException {
        String last = rs.getString("last_name");
        String code = rs.getString("location_code");
        Timestamp at = rs.getTimestamp("submitted_at");
        return new Request(
                rs.getLong("vpf_change_request_id"),
                rs.getString("emp_num"),
                last == null || last.isBlank()
                        ? rs.getString("first_name")
                        : rs.getString("first_name") + " " + last,
                code == null ? null : code + " - " + rs.getString("location_name"),
                rs.getBigDecimal("current_vpf_amount"),
                rs.getBigDecimal("requested_vpf_amount"),
                rs.getString("effective_from_yymm"),
                rs.getString("request_status"),
                "Y".equals(rs.getString("awaiting")),
                rs.getString("decision_reason"),
                at.toLocalDateTime());
    }

    /** The signed-in member (eSeva is the member's own record only, SRS-17-9). */
    private long self(AccessContext context, String action) {
        policy.require(context, "E-01", action);
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {"E-01", action}, List.of(), null);
        }
        return context.employeeId();
    }

    private static FciException invalid(String defect) {
        return new FciException(CpfErrorCode.VPF_CHANGE, new Object[] {defect}, List.of(), null);
    }

    private static FciException state(Status status, String needed) {
        return new FciException(
                CpfErrorCode.VPF_STATE, new Object[] {status.name(), needed}, List.of(), null);
    }
}
