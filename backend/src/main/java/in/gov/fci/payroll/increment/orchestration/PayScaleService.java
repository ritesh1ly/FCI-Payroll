package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-03 Pay Scales & Grades (plan 17 phase 17b; SRS M-03 field table, SRS-13-23, SRS-G-38; D17-3). A
 * scale's bounds are versioned: the Rule Administrator at Headquarters proposes a version - the
 * minimum, the maximum, the stagnation amount, the increment stages (each between the minimum and
 * the maximum), the effective date and the Active pay-scale revision circular (M-25) - and the
 * Payroll Checker at Headquarters approves it (route {@value #ACTION}). Until approved a version is
 * not in force; on approval the version before it is end-dated (never deleted) and the circular
 * linked. The members whose Basic is above the proposed maximum are listed with the proposal, for
 * review before activation. Each version names the wage revision it belongs to (SRS M-03 "Wage
 * Revision": "scales are kept for each revision with their effective period"), kept in {@code
 * pay_scale_band_version.pay_commission_ref}.
 */
@Service
@Loggable
public class PayScaleService {

    /**
     * The business day is India's (the JVM and the database run on UTC): bound, never CURRENT_DATE.
     */
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    public static final String SCREEN = "M-03";
    public static final String ACTION = "M03_VERSION";
    static final String ENTITY = "pay_scale_band_version";

    /** One scale with the version in force. */
    public record ScaleRow(
            long bandId,
            String code,
            String name,
            String scaleType,
            BigDecimal min,
            BigDecimal max,
            BigDecimal stagnationAmount,
            LocalDate effectiveFrom,
            int stages,
            boolean pending,
            // SC-17-0030 (US-GAP-56): a scale defined on M-03 is active once its first version is
            // approved.
            boolean active,
            // The drawn Increment column: the rule the band names, resolved on Increment rows.
            String incrementRuleKey) {}

    /**
     * One row of the drawn Grade to Designation to Scale Mapping (DDL org_designation.band_id,
     * grade_id): the grade and the designation are HRMS's, the headcount is the active staff on it.
     */
    public record MappingRow(
            String gradeCode,
            String gradeName,
            String designationCode,
            String designationName,
            String scaleCode,
            long headcount) {}

    /** A proposed version. */
    public record Proposal(
            String bandCode,
            BigDecimal min,
            BigDecimal max,
            BigDecimal stagnationAmount,
            LocalDate effectiveFrom,
            Long circularId,
            List<BigDecimal> stages,
            String reason,
            String wageRevision) {}

    /**
     * SRS M-03 "Scale Code / Type" (SC-17-0030, US-GAP-56): a new scale with its first version. The
     * name is the DDL's {@code pay_scale_band.band_name} (NOT NULL); the type is IDA or CDA, the
     * dropdown of the field table.
     */
    public record NewScale(String code, String name, String scaleType, Proposal first) {}

    /** The scale types M-03 offers (SRS M-03 field table: "Dropdown (IDA / CDA)"). */
    public static final java.util.Set<String> SCALE_TYPES = java.util.Set.of("IDA", "CDA");

    /** A wage revision a scale version may belong to (the M-03 Wage Revision lookup). */
    public record WageRevision(String code, String name, String payrollTypes, String status) {}

    /** The proposal's outcome: the approval item and the members above the new maximum. */
    public record Proposed(long approvalId, String status, List<String> aboveMaximum) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final Circulars circulars;
    private final AuditService audit;

    public PayScaleService(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            MakerChecker approvals,
            Circulars circulars,
            AuditService audit) {
        this.named = named;
        this.policy = policy;
        this.approvals = approvals;
        this.circulars = circulars;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Paged<ScaleRow> scales(AccessContext context, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_scale_band",
                        new MapSqlParameterSource(),
                        Long.class);
        List<ScaleRow> rows =
                named.query(
                        "SELECT b.band_id, b.band_code, b.band_name, b.scale_type, v.min_basic,"
                                + " v.max_basic, v.stagnation_increment_amt, v.effective_from,"
                                + " (SELECT COUNT(*) FROM pay_scale_band_stage s WHERE"
                                + " s.band_version_id = v.band_version_id) AS stages, (SELECT"
                                + " COUNT(*) FROM pay_scale_band_version p JOIN approval_queue q ON"
                                + " q.approval_id = p.approval_id WHERE p.band_id = b.band_id AND"
                                + " q.status = 'PENDING_APPROVAL') AS pending, b.active_flag,"
                                + " b.increment_rule_key FROM pay_scale_band b"
                                + " LEFT JOIN pay_scale_band_version v ON v.band_id = b.band_id AND"
                                + " v.effective_from <= :today AND (v.effective_to IS NULL OR"
                                + " v.effective_to >= :today) AND (v.approval_id IS NULL OR"
                                + " EXISTS (SELECT 1 FROM approval_queue a WHERE a.approval_id ="
                                + " v.approval_id AND a.status = 'APPROVED')) ORDER BY b.band_code"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        new MapSqlParameterSource("offset", (long) page.page() * page.size())
                                .addValue("size", page.size())
                                .addValue("today", Date.valueOf(java.time.LocalDate.now(IST))),
                        (rs, i) -> {
                            Date from = rs.getDate("effective_from");
                            return new ScaleRow(
                                    rs.getLong("band_id"),
                                    rs.getString("band_code"),
                                    rs.getString("band_name"),
                                    rs.getString("scale_type"),
                                    rs.getBigDecimal("min_basic"),
                                    rs.getBigDecimal("max_basic"),
                                    rs.getBigDecimal("stagnation_increment_amt"),
                                    from == null ? null : from.toLocalDate(),
                                    rs.getInt("stages"),
                                    rs.getInt("pending") > 0,
                                    "Y".equals(rs.getString("active_flag")),
                                    rs.getString("increment_rule_key"));
                        });
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /**
     * The drawn Grade to Designation to Scale Mapping (API-17-3): every active staff designation
     * with its grade, the scale it maps to (none: Unmapped) and how many active staff hold it.
     */
    @Transactional(readOnly = true)
    public Paged<MappingRow> mapping(AccessContext context, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        String from =
                " FROM org_designation d LEFT JOIN org_grade g ON g.grade_id = d.grade_id LEFT JOIN"
                        + " pay_scale_band b ON b.band_id = d.band_id WHERE d.active_flag = 'Y'"
                        + " AND d.designation_family = 'STAFF'";
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + from, new MapSqlParameterSource(), Long.class);
        List<MappingRow> rows =
                named.query(
                        "SELECT g.grade_code, g.grade_name, d.designation_code,"
                                + " d.designation_name, b.band_code, (SELECT COUNT(*) FROM employee"
                                + " e WHERE e.designation_id = d.designation_id AND e.record_status"
                                + " = 'ACTIVE' AND e.employment_status = 'IN_SERVICE') AS headcount"
                                + from
                                + " ORDER BY d.designation_code OFFSET :offset ROWS FETCH NEXT"
                                + " :size ROWS ONLY",
                        new MapSqlParameterSource("offset", (long) page.page() * page.size())
                                .addValue("size", page.size()),
                        (rs, i) ->
                                new MappingRow(
                                        rs.getString("grade_code"),
                                        rs.getString("grade_name"),
                                        rs.getString("designation_code"),
                                        rs.getString("designation_name"),
                                        rs.getString("band_code"),
                                        rs.getLong("headcount")));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /** The stages of the version in force (the stage grid, SRS M-03). */
    @Transactional(readOnly = true)
    public List<BigDecimal> stages(AccessContext context, String bandCode) {
        policy.require(context, SCREEN, "VIEW");
        return named.queryForList(
                "SELECT s.stage_basic FROM pay_scale_band_stage s JOIN pay_scale_band_version v ON"
                    + " v.band_version_id = s.band_version_id JOIN pay_scale_band b ON b.band_id ="
                    + " v.band_id WHERE b.band_code = :code AND v.effective_from <= :today AND"
                    + " (v.effective_to IS NULL OR v.effective_to >= :today) ORDER BY"
                    + " s.stage_basic",
                new MapSqlParameterSource("code", bandCode)
                        .addValue("today", Date.valueOf(java.time.LocalDate.now(IST))),
                BigDecimal.class);
    }

    /**
     * One version of a scale: its bounds and span; an open end is the version in force or later.
     */
    public record Version(
            int versionNo,
            BigDecimal min,
            BigDecimal max,
            BigDecimal stagnationAmount,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo) {}

    /**
     * M-03 (SRS l.3952, SC-17-0036): every version of the scale, newest first - "a superseded scale
     * is end-dated, never deleted", so each earlier version shows its Effective To.
     */
    @Transactional(readOnly = true)
    public List<Version> versions(AccessContext context, String bandCode) {
        policy.require(context, SCREEN, "VIEW");
        return named.query(
                "SELECT v.version_no, v.min_basic, v.max_basic, v.stagnation_increment_amt,"
                        + " v.effective_from, v.effective_to FROM"
                        + " pay_scale_band_version v JOIN pay_scale_band b ON b.band_id = v.band_id"
                        + " WHERE b.band_code = :code ORDER BY v.effective_from DESC,"
                        + " v.band_version_id DESC",
                new MapSqlParameterSource("code", bandCode),
                (rs, i) ->
                        new Version(
                                rs.getInt(1),
                                rs.getBigDecimal(2),
                                rs.getBigDecimal(3),
                                rs.getBigDecimal(4),
                                rs.getDate(5).toLocalDate(),
                                rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate()));
    }

    /**
     * SRS M-03 (SC-17-0030, US-GAP-56): the Rule Administrator at Headquarters defines a new scale
     * - a unique code, its name and type (IDA / CDA) - with its first version, which follows the
     * version checks and route of {@link #propose}. The scale is held inactive (never offered to
     * HRMS records or computation) until the Payroll Checker approves that version; a code once
     * used stays taken, so a rejected definition does not free it.
     */
    @Transactional
    public Proposed define(AccessContext context, NewScale s) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        String code = s.code() == null ? "" : s.code().strip();
        String name = s.name() == null ? "" : s.name().strip();
        if (!code.matches("[A-Za-z0-9-]{1,10}")) {
            throw entry("the scale code (letters, digits or a hyphen, at most 10)");
        }
        if (name.isEmpty() || name.length() > 100) {
            throw entry("the scale name (at most 100 characters)");
        }
        if (s.scaleType() == null || !SCALE_TYPES.contains(s.scaleType())) {
            throw entry("the scale type (IDA or CDA)");
        }
        if (s.first() == null) {
            throw entry("the first version of the scale");
        }
        Long taken =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_scale_band WHERE UPPER(band_code) = :code",
                        new MapSqlParameterSource("code", code.toUpperCase(java.util.Locale.ROOT)),
                        Long.class);
        if (RowCounts.of(taken) > 0) {
            throw new FciException(
                    IncrementErrorCode.SCALE_CODE_TAKEN, new Object[] {code}, List.of(), null);
        }
        named.update(
                "INSERT INTO pay_scale_band (band_code, band_name, scale_type, active_flag) VALUES"
                        + " (:code, :name, :type, 'N')",
                new MapSqlParameterSource("code", code)
                        .addValue("name", name)
                        .addValue("type", s.scaleType()));
        Proposal first = s.first();
        return propose(
                context,
                new Proposal(
                        code,
                        first.min(),
                        first.max(),
                        first.stagnationAmount(),
                        first.effectiveFrom(),
                        first.circularId(),
                        first.stages(),
                        first.reason(),
                        first.wageRevision()));
    }

    /** M-03: a new version of a scale for the Payroll Checker at Headquarters. */
    @Transactional
    public Proposed propose(AccessContext context, Proposal p) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(CommonErrorCode.ACCESS_DENIED);
        }
        if (p.min() == null || p.max() == null || p.min().compareTo(p.max()) >= 0) {
            throw entry("the scale minimum must be below the maximum");
        }
        if (p.effectiveFrom() == null || p.reason() == null || p.reason().isBlank()) {
            throw entry("the effective date and a reason");
        }
        List<BigDecimal> stages = p.stages() == null ? List.of() : p.stages();
        if (stages.isEmpty()) {
            throw entry("the increment stages (SRS M-03)");
        }
        for (BigDecimal s : stages) {
            if (s.compareTo(p.min()) < 0 || s.compareTo(p.max()) > 0) {
                throw entry("stage " + s.toPlainString() + " is outside the minimum and maximum");
            }
        }
        if (new TreeSet<>(stages).size() != stages.size()) {
            throw entry("a stage is repeated");
        }
        circulars.requireActive(p.circularId());
        List<Map<String, Object>> bands =
                named.queryForList(
                        "SELECT band_id, scale_type FROM pay_scale_band WHERE band_code = :code",
                        new MapSqlParameterSource("code", p.bandCode()));
        if (bands.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        long band = ((Number) bands.getFirst().get("BAND_ID")).longValue();
        String revision =
                wageRevision(p.wageRevision(), (String) bands.getFirst().get("SCALE_TYPE"));
        MapSqlParameterSource row =
                new MapSqlParameterSource("band", band)
                        .addValue("min", p.min())
                        .addValue("max", p.max())
                        .addValue("stagnation", p.stagnationAmount(), Types.NUMERIC)
                        .addValue("from", Date.valueOf(p.effectiveFrom()))
                        .addValue("revision", revision);
        Long taken =
                named.queryForObject(
                        "SELECT COUNT(*) FROM pay_scale_band_version WHERE band_id = :band AND"
                                + " effective_from >= :from",
                        row,
                        Long.class);
        if (RowCounts.of(taken) > 0) {
            throw entry("a version must start after every existing one, on a free date");
        }
        Integer number =
                named.queryForObject(
                        "SELECT COALESCE(MAX(version_no), 0) + 1 FROM pay_scale_band_version WHERE"
                                + " band_id = :band",
                        row,
                        Integer.class);
        named.update(
                "INSERT INTO pay_scale_band_version (band_id, version_no, min_basic, max_basic,"
                        + " stagnation_increment_amt, pay_commission_ref, effective_from) VALUES"
                        + " (:band, :no, :min, :max, :stagnation, :revision, :from)",
                row.addValue("no", java.util.Objects.requireNonNullElse(number, 1)));
        Long version =
                named.queryForObject(
                        "SELECT band_version_id FROM pay_scale_band_version WHERE band_id = :band"
                                + " AND effective_from = :from",
                        row,
                        Long.class);
        for (BigDecimal s : new TreeSet<>(stages)) {
            named.update(
                    "INSERT INTO pay_scale_band_stage (band_version_id, stage_basic) VALUES"
                            + " (:version, :basic)",
                    new MapSqlParameterSource("version", version).addValue("basic", s));
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ACTION,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(version),
                                ActionType.CREATE,
                                context.locationId(),
                                "{\"circularId\":" + p.circularId() + "}",
                                p.effectiveFrom(),
                                p.reason().strip()),
                        (OtpProof) null);
        named.update(
                "UPDATE pay_scale_band_version SET approval_id = :approval WHERE band_version_id ="
                        + " :version",
                new MapSqlParameterSource("approval", item.id()).addValue("version", version));
        List<String> above =
                named.queryForList(
                        "SELECT emp_num FROM employee WHERE band_id = :band AND record_status ="
                                + " 'ACTIVE' AND current_basic > :max ORDER BY emp_num",
                        row,
                        String.class);
        return new Proposed(item.id(), item.status().name(), above);
    }

    /**
     * SRS M-03 Wage Revision lookup: the wage revisions a staff scale can belong to (every I-25
     * revision for a staff pay pattern that was not rejected or cancelled), newest code first.
     */
    @Transactional(readOnly = true)
    public List<WageRevision> wageRevisions(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return named.query(
                "SELECT revision_code, revision_name, payroll_types, revision_status FROM"
                        + " wage_revision WHERE revision_status NOT IN ('REJECTED', 'CANCELLED')"
                        + " AND payroll_types LIKE '%STAFF_%' ORDER BY revision_code DESC",
                new MapSqlParameterSource(),
                (rs, i) ->
                        new WageRevision(
                                rs.getString("revision_code"),
                                rs.getString("revision_name"),
                                rs.getString("payroll_types"),
                                rs.getString("revision_status")));
    }

    /**
     * SRS M-03 Wage Revision (mandatory): an I-25 revision, not rejected or cancelled, whose
     * payroll types include the scale's staff pattern ({@code STAFF_IDA} for an IDA scale, {@code
     * STAFF_CDA} for a CDA one).
     */
    private String wageRevision(String code, String scaleType) {
        String c = code == null ? "" : code.strip();
        if (c.isEmpty()) {
            throw entry("the wage revision the scale belongs to (SRS M-03)");
        }
        List<String> types =
                named.queryForList(
                        "SELECT payroll_types FROM wage_revision WHERE revision_code = :code AND"
                                + " revision_status NOT IN ('REJECTED', 'CANCELLED')",
                        new MapSqlParameterSource("code", c),
                        String.class);
        String wanted = "STAFF_" + scaleType;
        if (types.isEmpty()
                || java.util.Arrays.stream(types.getFirst().split(","))
                        .map(String::strip)
                        .noneMatch(wanted::equals)) {
            throw entry("wage revision " + c + " is not a revision of " + wanted + " scales");
        }
        return c;
    }

    /** On approval: the version before ends the day before; the circular is linked. */
    @EventListener
    public void on(ApprovalDecided d) {
        if (!d.concerns(ENTITY)) {
            return;
        }
        long version = Long.parseLong(d.entityKey());
        Map<String, Object> v =
                named.queryForMap(
                        "SELECT v.band_id, v.effective_from, b.band_code, b.active_flag FROM"
                                + " pay_scale_band_version v JOIN pay_scale_band b ON b.band_id ="
                                + " v.band_id WHERE v.band_version_id = :id",
                        new MapSqlParameterSource("id", version));
        if (!d.approved()) {
            return;
        }
        LocalDate from = Dates.local(v.get("EFFECTIVE_FROM"));
        named.update(
                "UPDATE pay_scale_band_version SET effective_to = :before WHERE band_id = :band AND"
                        + " band_version_id <> :id AND effective_from < :from AND effective_to IS"
                        + " NULL",
                new MapSqlParameterSource("before", Date.valueOf(from.minusDays(1)))
                        .addValue("band", v.get("BAND_ID"))
                        .addValue("id", version)
                        .addValue("from", Date.valueOf(from)));
        // SC-17-0030 (US-GAP-56): a scale defined on M-03 becomes active with its first version.
        if ("N".equals(v.get("ACTIVE_FLAG"))) {
            named.update(
                    "UPDATE pay_scale_band SET active_flag = 'Y', row_version = row_version + 1"
                            + " WHERE band_id = :band AND active_flag = 'N'",
                    new MapSqlParameterSource("band", v.get("BAND_ID")));
            audit.record(
                    d.checkerLoginId(),
                    AuditService.Entry.change(
                                    ENTITY,
                                    v.get("BAND_CODE") + " from " + from,
                                    "active_flag",
                                    "N",
                                    "Y",
                                    "Pay scale defined and activated with its first version")
                            .approval(d.approvalId())
                            .effective(from)
                            .module(SCREEN));
        }
        Long circular = Long.valueOf(d.payload().replaceAll("[^0-9]", ""));
        circulars.link(
                d.checkerLoginId(),
                circular,
                Circulars.LinkType.MASTER_ENTRY,
                ENTITY,
                String.valueOf(version),
                SCREEN);
        audit.record(
                d.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                v.get("BAND_CODE") + " from " + from,
                                "version",
                                null,
                                "APPROVED",
                                "Pay scale version approved")
                        .approval(d.approvalId())
                        .effective(from)
                        .module(SCREEN));
    }

    private static FciException entry(String what) {
        return new FciException(IncrementErrorCode.ENTRY, new Object[] {what}, List.of(), null);
    }
}
