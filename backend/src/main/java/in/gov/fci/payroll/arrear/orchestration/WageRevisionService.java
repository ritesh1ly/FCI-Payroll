package in.gov.fci.payroll.arrear.orchestration;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.arrear.business.WageRevisionRules;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-25 Wage Revision, steps 1 to 4 (plan 13 phase 13e; SRS-8-25, SRS-8-9; D13-11). Headquarters
 * defines a staff revision once, linked to its Active order on M-25 (step 1): the payroll types,
 * all-India scope, the Basic fitment rule (factor, percentage or fixed amount; minimum, maximum,
 * rounding) and its effective date (step 2); it simulates old pay, new pay and the estimated Basic
 * arrear for every member in scope, changing nothing (step 3); the Payroll Checker at Headquarters
 * approves with the OTP WAGE_REVISION_HQ (step 4, route {@value #ACTION}). The bulk fixation, the
 * arrears with their linked effects, the payment, the status report and corrective revisions (steps
 * 5 to 9), labour revisions, pay-matrix fitment and a selected scope wait for module 17, the labour
 * engines and FCI's answer to OQ-14 (v0.4) (PENDING 13-i25): asked for, they are refused as not
 * available - never computed on a guess.
 */
@Service
@Loggable
public class WageRevisionService {

    public static final String SCREEN = "I-25";
    public static final String ACTION = "I25_APPROVE";
    static final String ENTITY = "wage_revision";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int BASIC = 101;

    /** The CIRCULAR_CATEGORY code of a revision order (I-25 field table, SRS l.3164). */
    static final String WAGE_REVISION_CATEGORY = "WAGE_REVISION";

    /** The staff payroll types and the pay pattern each covers. */
    static final Map<String, String> STAFF_TYPES = Map.of("STAFF_IDA", "IDA", "STAFF_CDA", "CDA");

    static final Set<String> LABOUR_TYPES = Set.of("DEPT_LABOUR", "DPS_LABOUR", "NWNP_LABOUR");

    /** A definition (step 2). */
    public record Definition(
            String name,
            Long circularId,
            List<String> payrollTypes,
            String scope,
            LocalDate effectiveFrom,
            WageRevisionRules.Rule basic,
            Integer instalments,
            String reason) {}

    public record Summary(
            long id,
            String code,
            String name,
            String order,
            String payrollTypes,
            String status,
            int step,
            LocalDateTime createdAt,
            String scope,
            LocalDate effectiveFrom,
            String rule,
            BigDecimal ruleValue,
            String rounding) {}

    public record Step(int no, String code, String status, String by, LocalDateTime at) {}

    public record Detail(
            Summary summary,
            LocalDate effectiveFrom,
            String rule,
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String rounding,
            int instalments,
            List<Step> steps,
            long members,
            BigDecimal estimatedArrear) {}

    public record MemberRow(
            String eis,
            String name,
            String office,
            BigDecimal oldPay,
            BigDecimal newPay,
            BigDecimal estimate,
            String status) {}

    private static final String[] STEPS = {
        "ORDER", "DEFINE", "SIMULATE", "APPROVE", "FIX_PAY", "ARREARS", "PAY", "TRACK", "CORRECT"
    };

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final Circulars circulars;
    private final Clock clock;

    public WageRevisionService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            Circulars circulars,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.circulars = circulars;
        this.clock = clock;
    }

    /** SRS-8-25 steps 1 and 2: Headquarters defines the revision on its Active order. */
    @Transactional
    public Detail define(AccessContext context, Definition d) {
        requireHq(context, "CREATE");
        if (d.name() == null || d.name().isBlank() || d.effectiveFrom() == null) {
            throw definition("the name and the effective date are required");
        }
        if (d.reason() == null || d.reason().isBlank()) {
            throw definition("a reason is required");
        }
        List<String> types = d.payrollTypes() == null ? List.of() : d.payrollTypes();
        if (types.isEmpty()) {
            throw definition("at least one payroll type");
        }
        for (String type : types) {
            if (LABOUR_TYPES.contains(type)) {
                throw notAvailable("A labour wage revision", "the labour engines, modules 20 - 22");
            }
            if (!STAFF_TYPES.containsKey(type)) {
                throw definition("unknown payroll type " + type);
            }
        }
        if (d.scope() != null && !"ALL_INDIA".equals(d.scope())) {
            throw notAvailable("A selected scope", "FCI's answer to OQ-14 (v0.4)");
        }
        WageRevisionRules.Rule rule = d.basic();
        if (rule == null) {
            throw definition("the Basic fitment rule is required");
        }
        WageRevisionRules.defect(rule)
                .ifPresent(
                        defect -> {
                            throw definition(defect);
                        });
        int instalments = d.instalments() == null ? 1 : d.instalments();
        if (instalments < 1 || instalments > 99) {
            throw definition("instalments must be 1 to 99");
        }
        // I-25 field table (SRS l.3164): an active circular of category Wage Revision.
        circulars.requireActive(d.circularId(), WAGE_REVISION_CATEGORY);
        String provisional = "WR-TMP-" + UUID.randomUUID().toString().substring(0, 20);
        MapSqlParameterSource p =
                new MapSqlParameterSource("code", provisional)
                        .addValue("name", d.name().strip())
                        .addValue("circular", d.circularId())
                        .addValue("types", String.join(",", types))
                        .addValue("location", context.locationId())
                        .addValue("instalments", instalments)
                        .addValue("by", context.loginId());
        named.update(
                "INSERT INTO wage_revision (revision_code, revision_name, circular_order_id,"
                        + " payroll_types, scope_level, current_step, revision_status,"
                        + " instalments_allowed, defined_location_id, created_by) VALUES (:code,"
                        + " :name, :circular, :types, 'ALL_INDIA', 2, 'DEFINED', :instalments,"
                        + " :location, :by)",
                p);
        Long id =
                named.queryForObject(
                        "SELECT wage_revision_id FROM wage_revision WHERE revision_code = :code",
                        p,
                        Long.class);
        long revision = id == null ? -1 : id;
        String code = String.format("WR-%04d-%04d", d.effectiveFrom().getYear(), revision);
        named.update(
                "UPDATE wage_revision SET revision_code = :final WHERE wage_revision_id = :id",
                new MapSqlParameterSource("final", code).addValue("id", revision));
        Timestamp now = Timestamp.from(clock.instant());
        for (int i = 0; i < STEPS.length; i++) {
            int no = i + 1;
            String status = no <= 2 ? "DONE" : no == 9 ? "SKIPPED" : "PENDING";
            named.update(
                    "INSERT INTO wage_revision_step (wage_revision_id, step_no, step_code,"
                            + " step_status, completed_at, completed_by) VALUES (:id, :no, :code,"
                            + " :status, :at, :by)",
                    new MapSqlParameterSource("id", revision)
                            .addValue("no", no)
                            .addValue("code", STEPS[i])
                            .addValue("status", status)
                            .addValue("at", no <= 2 ? now : null, Types.TIMESTAMP)
                            .addValue("by", no <= 2 ? context.loginId() : null, Types.VARCHAR));
        }
        named.update(
                "INSERT INTO wage_revision_component (wage_revision_id, component_id,"
                        + " effective_from, fitment_rule_type, fitment_factor, fitment_percent,"
                        + " fixed_amount, minimum_amount, maximum_amount, rounding_rule_key) SELECT"
                        + " :id, component_id, :from, :type, :factor, :percent, :fixed, :min, :max,"
                        + " :rounding FROM pay_component WHERE legacy_pay_code = :basic",
                new MapSqlParameterSource("id", revision)
                        .addValue("from", Date.valueOf(d.effectiveFrom()))
                        .addValue("type", rule.type().name())
                        .addValue(
                                "factor",
                                only(rule, WageRevisionRules.Fitment.FACTOR),
                                Types.NUMERIC)
                        .addValue(
                                "percent",
                                only(rule, WageRevisionRules.Fitment.PERCENTAGE),
                                Types.NUMERIC)
                        .addValue(
                                "fixed",
                                only(rule, WageRevisionRules.Fitment.FIXED_AMOUNT),
                                Types.NUMERIC)
                        .addValue("min", rule.minimum(), Types.NUMERIC)
                        .addValue("max", rule.maximum(), Types.NUMERIC)
                        .addValue("rounding", rule.rounding().name())
                        .addValue("basic", BASIC));
        circulars.link(
                context.loginId(),
                d.circularId(),
                Circulars.LinkType.TRANSACTION,
                ENTITY,
                code,
                SCREEN);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                code,
                                "revision_status",
                                null,
                                "DEFINED",
                                d.reason().strip())
                        .module(SCREEN));
        return detail(context, revision);
    }

    /**
     * SRS-8-25 step 3: old pay, new pay and the estimated Basic arrear of every staff member in
     * scope; changes no pay. Run again, it refreshes the same rows.
     */
    @Transactional
    public Detail simulate(AccessContext context, long id) {
        requireHq(context, "CREATE");
        Map<String, Object> r = revision(id);
        String status = (String) r.get("REVISION_STATUS");
        if (!"DEFINED".equals(status) && !"SIMULATED".equals(status)) {
            throw state(status, "DEFINED / SIMULATED");
        }
        Map<String, Object> c =
                named.queryForMap(
                        "SELECT effective_from, fitment_rule_type, fitment_factor, fitment_percent,"
                                + " fixed_amount, minimum_amount, maximum_amount, rounding_rule_key"
                                + " FROM wage_revision_component WHERE wage_revision_id = :id",
                        new MapSqlParameterSource("id", id));
        WageRevisionRules.Rule rule = rule(c);
        YearMonth effective = YearMonth.from(date(c.get("EFFECTIVE_FROM")));
        long months = WageRevisionRules.arrearMonths(effective, YearMonth.now(clock.withZone(IST)));
        List<String> patterns = new ArrayList<>();
        for (String type : ((String) r.get("PAYROLL_TYPES")).split(",")) {
            patterns.add(STAFF_TYPES.get(type));
        }
        List<Map<String, Object>> members =
                named.queryForList(
                        "SELECT employee_id, location_id, current_basic FROM employee WHERE"
                                + " record_status = 'ACTIVE' AND employment_status = 'IN_SERVICE'"
                                + " AND emp_lbr_flag = 'N' AND current_basic IS NOT NULL AND"
                                + " pay_pattern IN (:patterns)",
                        new MapSqlParameterSource("patterns", patterns));
        Set<Long> existing =
                new HashSet<>(
                        named.queryForList(
                                "SELECT employee_id FROM wage_revision_member WHERE"
                                        + " wage_revision_id = :id",
                                new MapSqlParameterSource("id", id),
                                Long.class));
        List<MapSqlParameterSource> updates = new ArrayList<>();
        List<MapSqlParameterSource> inserts = new ArrayList<>();
        for (Map<String, Object> m : members) {
            BigDecimal old = (BigDecimal) m.get("CURRENT_BASIC");
            BigDecimal fitted = WageRevisionRules.fit(old, rule);
            long employee = ((Number) m.get("EMPLOYEE_ID")).longValue();
            MapSqlParameterSource row =
                    new MapSqlParameterSource("id", id)
                            .addValue("employee", employee)
                            .addValue("location", m.get("LOCATION_ID"))
                            .addValue("old", old)
                            .addValue("new", fitted)
                            .addValue("estimate", WageRevisionRules.estimate(old, fitted, months));
            (existing.contains(employee) ? updates : inserts).add(row);
        }
        if (!updates.isEmpty()) {
            named.batchUpdate(
                    "UPDATE wage_revision_member SET location_id = :location, old_pay = :old,"
                            + " new_pay = :new, estimated_arrear = :estimate, row_version ="
                            + " row_version + 1 WHERE wage_revision_id = :id AND employee_id ="
                            + " :employee",
                    updates.toArray(MapSqlParameterSource[]::new));
        }
        if (!inserts.isEmpty()) {
            named.batchUpdate(
                    "INSERT INTO wage_revision_member (wage_revision_id, employee_id, location_id,"
                            + " old_pay, new_pay, estimated_arrear) VALUES (:id, :employee,"
                            + " :location, :old, :new, :estimate)",
                    inserts.toArray(MapSqlParameterSource[]::new));
        }
        MapSqlParameterSource p =
                new MapSqlParameterSource("id", id)
                        .addValue("at", Timestamp.from(clock.instant()))
                        .addValue("by", context.loginId());
        named.update(
                "UPDATE wage_revision SET revision_status = 'SIMULATED', current_step = 3,"
                        + " row_version = row_version + 1 WHERE wage_revision_id = :id",
                p);
        named.update(
                "UPDATE wage_revision_step SET step_status = 'DONE', completed_at = :at,"
                        + " completed_by = :by WHERE wage_revision_id = :id AND step_no = 3",
                p);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                (String) r.get("REVISION_CODE"),
                                "revision_status",
                                status,
                                "SIMULATED",
                                "Simulated for " + members.size() + " members")
                        .module(SCREEN));
        return detail(context, id);
    }

    /** SRS-8-25 step 4: the simulated revision goes to the Payroll Checker at HQ (OTP). */
    @Transactional
    public SubmittedApproval submit(AccessContext context, long id, String remark) {
        requireHq(context, "CREATE");
        if (remark == null || remark.isBlank()) {
            throw new FciException(ArrearErrorCode.REMARK);
        }
        Map<String, Object> r = revision(id);
        if (!"SIMULATED".equals(r.get("REVISION_STATUS"))) {
            throw state((String) r.get("REVISION_STATUS"), "SIMULATED");
        }
        BigDecimal total =
                named.queryForObject(
                        "SELECT COALESCE(SUM(estimated_arrear), 0) FROM wage_revision_member WHERE"
                                + " wage_revision_id = :id",
                        new MapSqlParameterSource("id", id),
                        BigDecimal.class);
        return approvals.submit(
                context,
                new Submission(
                                ACTION,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.UPDATE,
                                context.locationId(),
                                null,
                                LocalDate.now(clock.withZone(IST)),
                                remark.strip())
                        .withImpact(total),
                (OtpProof) null);
    }

    /** SRS-8-25 step 4: APPROVED with the OTP; returned: SIMULATED again; rejected: REJECTED. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY)) {
            return;
        }
        long id = Long.parseLong(d.entityKey());
        Map<String, Object> r = revision(id);
        MapSqlParameterSource p =
                new MapSqlParameterSource("id", id)
                        .addValue("approval", d.approvalId())
                        .addValue("otp", d.otpChallengeId(), Types.NUMERIC)
                        .addValue("by", d.checkerLoginId())
                        .addValue("at", Timestamp.from(d.decidedAt()));
        String after;
        if (d.approved()) {
            after = "APPROVED";
            named.update(
                    "UPDATE wage_revision SET revision_status = 'APPROVED', current_step = 4,"
                            + " approval_id = :approval, otp_challenge_id = :otp, approved_by ="
                            + " :by, approved_at = :at, row_version = row_version + 1 WHERE"
                            + " wage_revision_id = :id",
                    p);
            named.update(
                    "UPDATE wage_revision_step SET step_status = 'DONE', completed_at = :at,"
                            + " completed_by = :by, approval_id = :approval WHERE wage_revision_id"
                            + " = :id AND step_no = 4",
                    p);
        } else {
            after = d.outcome() == ApprovalStatus.RETURNED ? "SIMULATED" : "REJECTED";
            named.update(
                    "UPDATE wage_revision SET revision_status = :status, row_version = row_version"
                            + " + 1 WHERE wage_revision_id = :id",
                    p.addValue("status", after));
        }
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                (String) r.get("REVISION_CODE"),
                                "revision_status",
                                "SIMULATED",
                                after,
                                d.approved() ? "Approved with OTP" : "Not approved: " + d.reason())
                        .approval(d.approvalId())
                        .module(SCREEN));
    }

    /** Every revision (a revision is all-India), newest first, one page. */
    @Transactional(readOnly = true)
    public Paged<Summary> revisions(AccessContext context, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM wage_revision",
                        new MapSqlParameterSource(),
                        Long.class);
        List<Summary> rows =
                named.query(
                        SUMMARY
                                + " ORDER BY w.wage_revision_id DESC OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        new MapSqlParameterSource("offset", (long) page.page() * page.size())
                                .addValue("size", page.size()),
                        (rs, i) -> summary(rs));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    @Transactional(readOnly = true)
    public Detail detail(AccessContext context, long id) {
        policy.require(context, SCREEN, "VIEW");
        List<Summary> found =
                named.query(
                        SUMMARY + " WHERE w.wage_revision_id = :id",
                        new MapSqlParameterSource("id", id),
                        (rs, i) -> summary(rs));
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        Map<String, Object> c =
                named.queryForMap(
                        "SELECT effective_from, fitment_rule_type, fitment_factor, fitment_percent,"
                                + " fixed_amount, minimum_amount, maximum_amount, rounding_rule_key"
                                + " FROM wage_revision_component WHERE wage_revision_id = :id",
                        p);
        WageRevisionRules.Rule rule = rule(c);
        List<Step> steps =
                named.query(
                        "SELECT step_no, step_code, step_status, completed_by, completed_at FROM"
                                + " wage_revision_step WHERE wage_revision_id = :id ORDER BY"
                                + " step_no",
                        p,
                        (rs, i) -> {
                            Timestamp at = rs.getTimestamp(5);
                            return new Step(
                                    rs.getInt(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                    rs.getString(4),
                                    at == null ? null : at.toLocalDateTime());
                        });
        Map<String, Object> totals =
                named.queryForMap(
                        "SELECT COUNT(*) AS n, COALESCE(SUM(estimated_arrear), 0) AS total FROM"
                                + " wage_revision_member WHERE wage_revision_id = :id",
                        p);
        Number instalments =
                named.queryForObject(
                        "SELECT instalments_allowed FROM wage_revision WHERE wage_revision_id ="
                                + " :id",
                        p,
                        Number.class);
        return new Detail(
                found.getFirst(),
                date(c.get("EFFECTIVE_FROM")),
                rule.type().name(),
                rule.value(),
                rule.minimum(),
                rule.maximum(),
                rule.rounding().name(),
                instalments == null ? 1 : instalments.intValue(),
                steps,
                ((Number) totals.get("N")).longValue(),
                (BigDecimal) totals.get("TOTAL"));
    }

    private static final String MEMBERS =
            " FROM wage_revision_member m JOIN employee e ON e.employee_id = m.employee_id JOIN"
                    + " org_location l ON l.location_id = m.location_id WHERE m.wage_revision_id ="
                    + " :id AND (:all = 'Y' OR m.location_id IN (:locations))";

    /** The members of the revision in the session's offices (all at Headquarters), one page. */
    @Transactional(readOnly = true)
    public Paged<MemberRow> members(AccessContext context, long id, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = scoped(context).addValue("id", id);
        Long total = named.queryForObject("SELECT COUNT(*)" + MEMBERS, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<MemberRow> rows =
                named.query(
                        MEMBER_COLUMNS
                                + MEMBERS
                                + " ORDER BY e.emp_num, m.wr_member_id OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> member(rs));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    @Transactional(readOnly = true)
    public long membersSize(AccessContext context, long id) {
        policy.require(context, SCREEN, "EXPORT");
        Long n =
                named.queryForObject(
                        "SELECT COUNT(*)" + MEMBERS,
                        scoped(context).addValue("id", id),
                        Long.class);
        return RowCounts.of(n);
    }

    /** SRS-8-25 steps 3 and 8: the simulation sheet / member status, in one snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void exportMembers(AccessContext context, long id, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        MapSqlParameterSource p = scoped(context).addValue("id", id);
        Long expected = named.queryForObject("SELECT COUNT(*)" + MEMBERS, p, Long.class);
        sink.header("eis", "member", "office", "old_pay", "new_pay", "estimated_arrear", "status");
        named.query(
                MEMBER_COLUMNS + MEMBERS + " ORDER BY e.emp_num, m.wr_member_id",
                p,
                rs -> {
                    MemberRow m = member(rs);
                    try {
                        sink.row(
                                m.eis(),
                                m.name(),
                                m.office(),
                                m.oldPay(),
                                m.newPay(),
                                m.estimate(),
                                m.status());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    // ---------------------------------------------------------------- helpers

    private static final String SUMMARY =
            "SELECT w.wage_revision_id, w.revision_code, w.revision_name, c.circular_number,"
                    + " w.payroll_types, w.revision_status, w.current_step, w.created_at,"
                    + " w.scope_level, k.effective_from, k.fitment_rule_type, k.fitment_factor,"
                    + " k.fitment_percent, k.fixed_amount, k.rounding_rule_key FROM wage_revision w"
                    + " LEFT JOIN circular_order c ON c.circular_order_id = w.circular_order_id"
                    + " LEFT JOIN (SELECT wage_revision_id, MIN(wr_component_id) AS first_id FROM"
                    + " wage_revision_component GROUP BY wage_revision_id) f ON f.wage_revision_id"
                    + " = w.wage_revision_id LEFT JOIN wage_revision_component k ON"
                    + " k.wr_component_id = f.first_id";

    private static final String MEMBER_COLUMNS =
            "SELECT e.emp_num, e.first_name, e.last_name, l.location_code, l.location_name,"
                    + " m.old_pay, m.new_pay, m.estimated_arrear, m.fixation_status";

    private static Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        Date effective = rs.getDate(10);
        String rule = rs.getString(11);
        return new Summary(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getInt(7),
                rs.getTimestamp(8).toLocalDateTime(),
                rs.getString(9),
                effective == null ? null : effective.toLocalDate(),
                rule,
                ruleValue(rule, rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14)),
                rs.getString(15));
    }

    /** The value of the component's fitment rule (I-25 list, column Fitment rule). */
    private static BigDecimal ruleValue(
            String rule, BigDecimal factor, BigDecimal percent, BigDecimal fixed) {
        return switch (rule == null ? "" : rule) {
            case "FACTOR" -> factor;
            case "PERCENTAGE" -> percent;
            case "FIXED_AMOUNT" -> fixed;
            default -> null;
        };
    }

    private static MemberRow member(java.sql.ResultSet rs) throws java.sql.SQLException {
        String first = rs.getString(2);
        String last = rs.getString(3);
        return new MemberRow(
                rs.getString(1),
                ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip(),
                rs.getString(4) + " - " + rs.getString(5),
                rs.getBigDecimal(6),
                rs.getBigDecimal(7),
                rs.getBigDecimal(8),
                rs.getString(9));
    }

    private static WageRevisionRules.Rule rule(Map<String, Object> c) {
        WageRevisionRules.Fitment type =
                WageRevisionRules.Fitment.valueOf((String) c.get("FITMENT_RULE_TYPE"));
        BigDecimal value =
                switch (type) {
                    case FACTOR -> (BigDecimal) c.get("FITMENT_FACTOR");
                    case PERCENTAGE -> (BigDecimal) c.get("FITMENT_PERCENT");
                    case FIXED_AMOUNT -> (BigDecimal) c.get("FIXED_AMOUNT");
                };
        return new WageRevisionRules.Rule(
                type,
                value,
                (BigDecimal) c.get("MINIMUM_AMOUNT"),
                (BigDecimal) c.get("MAXIMUM_AMOUNT"),
                WageRevisionRules.Rounding.valueOf((String) c.get("ROUNDING_RULE_KEY")));
    }

    private static BigDecimal only(WageRevisionRules.Rule r, WageRevisionRules.Fitment type) {
        return r.type() == type ? r.value() : null;
    }

    private Map<String, Object> revision(long id) {
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT revision_code, revision_status, payroll_types FROM wage_revision"
                                + " WHERE wage_revision_id = :id",
                        new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return rows.getFirst();
    }

    private void requireHq(AccessContext context, String action) {
        policy.require(context, SCREEN, action);
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ArrearErrorCode.WR_HQ_ONLY);
        }
    }

    private MapSqlParameterSource scoped(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate d) {
            return d;
        }
        return new Date(((java.util.Date) value).getTime()).toLocalDate();
    }

    private static FciException definition(String why) {
        return new FciException(ArrearErrorCode.WR_DEFINITION, new Object[] {why}, List.of(), null);
    }

    private static FciException notAvailable(String what, String waitsFor) {
        return new FciException(
                ArrearErrorCode.WR_NOT_AVAILABLE, new Object[] {what, waitsFor}, List.of(), null);
    }

    private static FciException state(String status, String needs) {
        return new FciException(
                ArrearErrorCode.WR_STATE, new Object[] {status, needs}, List.of(), null);
    }
}
