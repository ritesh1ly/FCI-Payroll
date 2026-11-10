package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.business.LabourRules;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.PiiMasks;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads behind the labour masters as Wireframes v6.1 draws them (OD-121; SRS-2-27, SRS-2-30,
 * SRS-2-46, SRS-2-47, SRS-2-48, SRS-2-49, SRS-3-6, SRS-3-12, SRS-3-13, SRS-4-11, SRS-5-1, SRS-5-2,
 * SRS-5-3, SRS-G-13; BR-LABM-001, BR-LABM-006, BR-LABM-011; screens M-02, M-05, M-06, M-09; DDL
 * labour_profile, labour_gang, labour_temp_attachment, labour_light_duty, labour_norm,
 * labour_above_norm_slab, labour_min_wage, nwnp_sor_rate). The proposals, approvals and rules stay
 * in {@link LabourMasterService}, {@link GangService}, {@link NormService}, {@link
 * LabourRateService} and {@link NwnpRateService}; this class only reads, always within the
 * session's offices where the record has an office (rate tables are all-India, SRS-4-11). Labour
 * are not in HRMS (SRS-2-27): nothing here calls the HRMS gateway, whatever badge a column is drawn
 * with (WIREFRAME_GAPS).
 */
@Service
@Loggable
public class LabourScreenService {

    static final String M02 = "M-02";
    static final String M05 = "M-05";
    static final String M06 = "M-06";
    static final String M09 = "M-09";

    /** document_attachment.entity_name of the orders stored with a labour member (M-02). */
    public static final String LABOUR_ORDERS = "labour_profile";

    private static final String NWNP = "NWNP_LABOUR";
    private static final String DEPT = "DEPT_LABOUR";
    private static final String HANDLING = "HANDLING";
    private static final String VIEW = "VIEW";
    private static final String EXPORT = "EXPORT";
    private static final String APPROVED = "APPROVED";

    /** The M-06 filter: a norm type a depot's set declares (labour_norm columns, never a value). */
    private static final Set<String> NORM_TYPES = Set.of("HANDLING", "HEIGHT", "LEAD");

    private static final int EXPORT_PAGE = 500;

    /** One labour member as the drawn M-02 table lists it; identity numbers masked (R-PII). */
    public record MemberLine(
            long employeeId,
            String labourCode,
            String name,
            String payrollType,
            String designation,
            String gangRole,
            String gang,
            String depot,
            String aadhaarMasked,
            BigDecimal basicWage,
            String cpfCode,
            String status,
            boolean transferPending) {}

    /** The M-02 filters; each optional but the engine the view belongs to. */
    public record MemberFilter(String payrollType, Long depot, String gang, String text) {}

    /** The M-09 filters. */
    public record GangFilter(String payrollType, Long depot, String text) {}

    /** The M-06 filters. */
    public record NormFilter(Long depot, String normType, String text) {}

    /** SRS-2-47: one attachment of labour to a gang (M-02 card). */
    public record AttachmentLine(
            long id,
            String reference,
            String labourCode,
            String labour,
            String category,
            String gang,
            LocalDate from,
            LocalDate to,
            String status) {}

    /** SRS-2-49: one Permanent Light Duty order (M-02 card). */
    public record LightDutyLine(
            long id,
            String labourCode,
            String labour,
            String fromDesignation,
            String toDesignation,
            String reasonCode,
            LocalDate effectiveDate,
            String orderReference,
            String issuingAuthority,
            String payOption,
            BigDecimal protectedBasic,
            String status) {}

    /** One gang as the drawn M-09 register lists it. */
    public record GangLine(
            long gangId,
            String gangCode,
            String gangName,
            String payrollType,
            String gangType,
            String status,
            String depot,
            String shed,
            String sardar,
            String mandal,
            long labourCount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean endBlocked,
            boolean pending) {}

    /** The figures of M-09 "Structural Validations". */
    public record GangSummary(long leaderBreaches, long endBlocked) {}

    /**
     * BR-LABM-011: one gang in "Gang strength today"; sanctioned is null for the Ancillary Pool.
     */
    public record StrengthLine(
            long gangId,
            String gangCode,
            String gangName,
            String gangType,
            Integer sanctioned,
            int present,
            int shortBy,
            boolean alertOpen,
            List<String> filledBy) {}

    /** One depot's norm set as the drawn M-06 register lists it. */
    public record NormLine(
            long id,
            String depotCode,
            String depot,
            String mouReference,
            long minLabourNumber,
            BigDecimal minSlabWeight,
            BigDecimal heightNorm,
            BigDecimal leadNorm,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalState) {}

    /** The figures of the M-06 tiles. */
    public record NormSummary(long depotsWithSet, long slabTables) {}

    /** SRS-3-6: one above-norm band of a norm set. */
    public record SlabLine(
            String table, int order, long from, Long to, BigDecimal ratePercent, Long stepSize) {}

    /** SRS-5-2 / SRS-5-3: one NWNP ASOR set, with the piece rate and the MGW per gang role. */
    public record AsorLine(
            long id,
            String code,
            String depot,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalState,
            BigDecimal pieceOfRate,
            Map<String, BigDecimal> mgw) {}

    /** SRS-5-2: one line of the NWNP Schedule of Rates. */
    public record SorLine(
            long id,
            String operationCode,
            String operation,
            String unit,
            BigDecimal rate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalState) {}

    /** SRS-5-11 / DDL labour_min_wage: the (Basic Wage + VDA) x 26 row of a location type. */
    public record MinWageLine(
            long id,
            String locationType,
            BigDecimal basicWage,
            BigDecimal vda,
            BigDecimal mgw,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalState) {}

    private static final String MEMBERS =
            " FROM employee e JOIN labour_profile p ON p.employee_id = e.employee_id LEFT JOIN"
                + " org_designation d ON d.designation_id = e.designation_id LEFT JOIN org_location"
                + " l ON l.location_id = p.depot_location_id WHERE e.emp_lbr_flag = 'Y' AND (:all ="
                + " 'Y' OR e.location_id IN (:locations)) AND p.payroll_type = :type AND (:depot IS"
                + " NULL OR p.depot_location_id = :depot) AND (:text IS NULL OR UPPER(e.first_name"
                + " || ' ' || COALESCE(e.last_name, '') || ' ' || p.labour_number) LIKE :text) AND"
                + " (:gang IS NULL OR EXISTS (SELECT 1 FROM labour_gang_membership m JOIN"
                + " labour_gang g ON g.gang_id = m.gang_id WHERE m.employee_id = e.employee_id AND"
                + " m.effective_from <= :today AND (m.effective_to IS NULL OR m.effective_to >="
                + " :today) AND g.gang_code = :gang))";

    private static final String GANGS =
            " FROM labour_gang g JOIN org_location l ON l.location_id = g.depot_location_id WHERE"
                    + " (:all = 'Y' OR l.location_id IN (:locations) OR l.parent_location_id IN"
                    + " (:locations)) AND (:depot IS NULL OR g.depot_location_id = :depot) AND"
                    + " (:type IS NULL OR g.payroll_type = :type) AND (:text IS NULL OR"
                    + " UPPER(g.gang_code) LIKE :text OR UPPER(g.gang_name) LIKE :text)";

    private static final String LEADER =
            "(SELECT MAX(e.first_name || ' ' || COALESCE(e.last_name, '')) FROM"
                + " labour_gang_membership m JOIN employee e ON e.employee_id = m.employee_id WHERE"
                + " m.gang_id = g.gang_id AND m.gang_role = '%s' AND m.effective_from <= :today AND"
                + " (m.effective_to IS NULL OR m.effective_to >= :today)) AS %s";

    private static final String NORMS =
            " FROM labour_norm n JOIN org_location l ON l.location_id = n.depot_location_id LEFT"
                + " JOIN approval_queue q ON q.approval_id = n.approval_id WHERE n.engine_code ="
                + " 'DEPT_LABOUR' AND (:all = 'Y' OR l.location_id IN (:locations)) AND (:depot IS"
                + " NULL OR n.depot_location_id = :depot) AND (:handling = 'N' OR n.handling_norm"
                + " IS NOT NULL) AND (:height = 'N' OR n.height_norm IS NOT NULL) AND (:lead = 'N'"
                + " OR n.lead_norm IS NOT NULL) AND (:text IS NULL OR UPPER(l.location_code || ' '"
                + " || l.location_name || ' ' || COALESCE(n.mou_reference, '')) LIKE :text)";

    private static final String SETS =
            " FROM rate_set s JOIN org_location l ON l.location_id = s.location_id WHERE"
                    + " s.payroll_type = :type AND s.rate_set_code LIKE :version";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final SystemParameters parameters;
    private final GangStrengthService strengths;
    private final DocumentStore documents;

    public LabourScreenService(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate named,
            Authorization policy,
            SystemParameters parameters,
            GangStrengthService strengths,
            DocumentStore documents) {
        this.jdbc = jdbc;
        this.named = named;
        this.policy = policy;
        this.parameters = parameters;
        this.strengths = strengths;
        this.documents = documents;
    }

    // ---------------------------------------------------------------- M-02

    /** M-02: the labour of one engine at the session's offices (SRS-2-27); paged. */
    @Transactional(readOnly = true)
    public Paged<MemberLine> members(AccessContext context, MemberFilter filter, PageSpec spec) {
        policy.require(context, M02, VIEW);
        return memberPage(context, filter, spec);
    }

    private Paged<MemberLine> memberPage(
            AccessContext context, MemberFilter filter, PageSpec spec) {
        MapSqlParameterSource p =
                scope(context)
                        .addValue("type", engine(filter.payrollType()))
                        .addValue("depot", filter.depot(), Types.BIGINT)
                        .addValue("gang", blankToNull(filter.gang()), Types.VARCHAR)
                        .addValue("text", like(filter.text()), Types.VARCHAR)
                        .addValue("today", Date.valueOf(parameters.today()));
        Long total = named.queryForObject("SELECT COUNT(*)" + MEMBERS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<MemberLine> rows =
                named.query(
                        "SELECT e.employee_id, p.labour_number, e.first_name, e.last_name,"
                            + " p.payroll_type, d.designation_name, d.gang_role_code,"
                            + " l.location_name, e.aadhaar_number, e.current_basic, p.cpf_code,"
                            + " p.labour_status, (SELECT MAX(g.gang_code) FROM"
                            + " labour_gang_membership m JOIN labour_gang g ON g.gang_id ="
                            + " m.gang_id WHERE m.employee_id = e.employee_id AND m.effective_from"
                            + " <= :today AND (m.effective_to IS NULL OR m.effective_to >= :today))"
                            + " AS gang_code, (SELECT COUNT(*) FROM labour_transfer t JOIN"
                            + " approval_queue q ON q.approval_id = t.approval_id WHERE"
                            + " t.employee_id = e.employee_id AND q.status = 'PENDING_APPROVAL') AS"
                            + " transfers"
                                + MEMBERS
                                + " ORDER BY p.labour_number, e.employee_id OFFSET :offset ROWS"
                                + " FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> member(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private static MemberLine member(ResultSet rs) throws SQLException {
        String last = rs.getString("last_name");
        return new MemberLine(
                rs.getLong("employee_id"),
                rs.getString("labour_number"),
                last == null ? rs.getString("first_name") : rs.getString("first_name") + " " + last,
                rs.getString("payroll_type"),
                rs.getString("designation_name"),
                rs.getString("gang_role_code"),
                rs.getString("gang_code"),
                rs.getString("location_name"),
                PiiMasks.lastFour(rs.getString("aadhaar_number")),
                rs.getBigDecimal("current_basic"),
                rs.getString("cpf_code"),
                rs.getString("labour_status"),
                rs.getLong("transfers") > 0);
    }

    /** M-02 Export (SRS-G-13): how many rows the filtered list has. */
    @Transactional(readOnly = true)
    public long memberExportSize(AccessContext context, MemberFilter filter) {
        policy.require(context, M02, EXPORT);
        return memberPage(context, filter, new PageSpec(0, 1)).total();
    }

    /** M-02 Export (SRS-G-13): the filtered list, page by page; Aadhaar stays masked (R-PII). */
    @Transactional(readOnly = true)
    public void exportMembers(AccessContext context, MemberFilter filter, ExportSink sink)
            throws IOException {
        policy.require(context, M02, EXPORT);
        sink.header(
                "Worker Code",
                "Name",
                "Designation",
                "Gang",
                "Depot",
                "Aadhaar",
                "Basic Wage",
                "CPF Code",
                "Status");
        int page = 0;
        Paged<MemberLine> rows;
        do {
            rows = memberPage(context, filter, new PageSpec(page, EXPORT_PAGE));
            for (MemberLine r : rows.items()) {
                sink.row(
                        r.labourCode(),
                        r.name(),
                        r.designation(),
                        r.gang(),
                        r.depot(),
                        r.aadhaarMasked(),
                        r.basicWage(),
                        r.cpfCode(),
                        r.status());
            }
            page++;
        } while ((long) page * EXPORT_PAGE < rows.total());
    }

    /** M-02 card: the attachments of the engine's labour at the session's offices (SRS-2-47). */
    @Transactional(readOnly = true)
    public Paged<AttachmentLine> attachments(AccessContext context, String type, PageSpec spec) {
        policy.require(context, M02, VIEW);
        MapSqlParameterSource p = scope(context).addValue("type", engine(type));
        String from =
                " FROM labour_temp_attachment t JOIN employee e ON e.employee_id = t.employee_id"
                    + " JOIN labour_profile p ON p.employee_id = t.employee_id JOIN labour_gang g"
                    + " ON g.gang_id = t.to_gang_id WHERE p.payroll_type = :type AND (:all = 'Y' OR"
                    + " e.location_id IN (:locations))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<AttachmentLine> rows =
                named.query(
                        "SELECT t.temp_attachment_id, t.attachment_ref, p.labour_number,"
                                + " e.first_name, e.last_name, t.labour_category, g.gang_name,"
                                + " t.from_date, t.to_date, t.attachment_status"
                                + from
                                + " ORDER BY t.from_date DESC, t.temp_attachment_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new AttachmentLine(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        name(rs.getString(4), rs.getString(5)),
                                        rs.getString(6),
                                        rs.getString(7),
                                        day(rs, 8),
                                        day(rs, 9),
                                        rs.getString(10)));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-02 card: the Permanent Light Duty orders of the engine, newest first (SRS-2-49). */
    @Transactional(readOnly = true)
    public Paged<LightDutyLine> lightDutyOrders(AccessContext context, String type, PageSpec spec) {
        policy.require(context, M02, VIEW);
        MapSqlParameterSource p = scope(context).addValue("type", engine(type));
        String from =
                " FROM labour_light_duty o JOIN employee e ON e.employee_id = o.employee_id JOIN"
                    + " labour_profile p ON p.employee_id = o.employee_id JOIN org_designation f ON"
                    + " f.designation_id = o.from_designation_id JOIN org_designation t ON"
                    + " t.designation_id = o.to_designation_id WHERE p.payroll_type = :type AND"
                    + " (:all = 'Y' OR e.location_id IN (:locations))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<LightDutyLine> rows =
                named.query(
                        "SELECT o.light_duty_id, p.labour_number, e.first_name, e.last_name,"
                                + " f.designation_name, t.designation_name, o.change_reason_code,"
                                + " o.effective_date, o.order_reference, o.issuing_authority,"
                                + " o.pay_option, o.protected_basic, o.light_duty_status"
                                + from
                                + " ORDER BY o.effective_date DESC, o.light_duty_id DESC OFFSET"
                                + " :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new LightDutyLine(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        name(rs.getString(3), rs.getString(4)),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        day(rs, 8),
                                        rs.getString(9),
                                        rs.getString(10),
                                        rs.getString(11),
                                        rs.getBigDecimal(12),
                                        rs.getString(13)));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** SRS-G-30: the orders stored with a labour member, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, long employeeId) {
        return documents.active(LABOUR_ORDERS, key(context, M02, VIEW, employeeId));
    }

    /** SRS-G-30: Establishment / HR stores an order with the member (M-02 EDIT, in scope). */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context, long employeeId, OrderCopy copy) {
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        LABOUR_ORDERS,
                        key(context, M02, "EDIT", employeeId),
                        copy.documentType(),
                        copy.reference(),
                        copy.orderDate(),
                        copy.issuingAuthority(),
                        copy.fileName(),
                        copy.content(),
                        true));
    }

    /** One stored order; a file of another record reads as not found. */
    @Transactional
    public DocumentStore.Content order(
            AccessContext context, String sourceIp, long employeeId, long fileId) {
        return documents.read(
                context.loginId(),
                sourceIp,
                LABOUR_ORDERS,
                key(context, M02, VIEW, employeeId),
                fileId);
    }

    /** The particulars of an order copy (M-02 card, SRS-G-30). */
    public record OrderCopy(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** The record's office check: a member outside the session's offices is refused. */
    private String key(AccessContext context, String screen, String action, long employeeId) {
        List<Map<String, Object>> found =
                jdbc.queryForList(
                        "SELECT p.labour_number, e.location_id FROM labour_profile p JOIN employee"
                                + " e ON e.employee_id = p.employee_id WHERE p.employee_id = ?",
                        employeeId);
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> row = found.get(0);
        policy.requireRecordAt(
                context, screen, action, ((Number) row.get("LOCATION_ID")).longValue());
        return (String) row.get("LABOUR_NUMBER");
    }

    // ---------------------------------------------------------------- M-09

    /** M-09: the gangs of one engine at the session's depots (SRS-2-1, BR-LABM-001); paged. */
    @Transactional(readOnly = true)
    public Paged<GangLine> gangs(AccessContext context, GangFilter filter, PageSpec spec) {
        policy.require(context, M09, VIEW);
        return gangPage(context, filter, spec);
    }

    private MapSqlParameterSource gangParameters(AccessContext context, GangFilter filter) {
        return scope(context)
                .addValue("depot", filter.depot(), Types.BIGINT)
                .addValue("type", engine(filter.payrollType()))
                .addValue("text", like(filter.text()), Types.VARCHAR)
                .addValue("today", Date.valueOf(parameters.today()));
    }

    private Paged<GangLine> gangPage(AccessContext context, GangFilter filter, PageSpec spec) {
        MapSqlParameterSource p = gangParameters(context, filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + GANGS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<GangLine> rows =
                named.query(
                        "SELECT g.gang_id, g.gang_code, g.gang_name, g.payroll_type, g.gang_type,"
                            + " g.gang_status, l.location_name, (SELECT s.location_name FROM"
                            + " org_location s WHERE s.location_id = g.shed_location_id) AS shed, "
                                + String.format(Locale.ROOT, LEADER, "SARDAR", "sardar")
                                + ", "
                                + String.format(Locale.ROOT, LEADER, "MANDAL", "mandal")
                                + ", (SELECT COUNT(*) FROM labour_gang_membership m WHERE m.gang_id"
                                + " = g.gang_id AND m.effective_from <= :today AND (m.effective_to"
                                + " IS NULL OR m.effective_to >= :today)) AS labour_count,"
                                + " g.effective_from, g.effective_to, (SELECT COUNT(*) FROM"
                                + " work_slip w WHERE w.gang_id = g.gang_id AND w.work_slip_date >"
                                + " g.effective_to) AS slips, CASE WHEN q.approval_id IS NULL THEN"
                                + " 0 ELSE 1 END AS waiting"
                                + GANGS.replace(
                                        " WHERE (:all",
                                        " LEFT JOIN approval_queue q ON q.approval_id ="
                                                + " g.approval_id AND q.status ="
                                                + " 'PENDING_APPROVAL' WHERE (:all")
                                + " ORDER BY l.location_code, g.gang_type, g.gang_code, g.gang_id"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new GangLine(
                                        rs.getLong("gang_id"),
                                        rs.getString("gang_code"),
                                        rs.getString("gang_name"),
                                        rs.getString("payroll_type"),
                                        rs.getString("gang_type"),
                                        rs.getString("gang_status"),
                                        rs.getString("location_name"),
                                        rs.getString("shed"),
                                        strip(rs.getString("sardar")),
                                        strip(rs.getString("mandal")),
                                        rs.getLong("labour_count"),
                                        day(rs, "effective_from"),
                                        day(rs, "effective_to"),
                                        rs.getLong("slips") > 0,
                                        rs.getInt("waiting") == 1));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * M-09 "Structural Validations": the active handling gangs without exactly one Sardar and one
     * Mandal today (BR-LABM-001, BR-LABM-002) and the gangs with work-slips after their end date
     * (BR-LABM-006), over the whole filtered list, never one page.
     */
    @Transactional(readOnly = true)
    public GangSummary gangSummary(AccessContext context, GangFilter filter) {
        policy.require(context, M09, VIEW);
        MapSqlParameterSource p = gangParameters(context, filter);
        String role =
                "(SELECT COUNT(*) FROM labour_gang_membership m WHERE m.gang_id = g.gang_id AND"
                        + " m.gang_role = '%s' AND m.effective_from <= :today AND (m.effective_to"
                        + " IS NULL OR m.effective_to >= :today))";
        Long breaches =
                named.queryForObject(
                        "SELECT COUNT(*)"
                                + GANGS
                                + " AND g.gang_type = 'HANDLING' AND g.gang_status = 'ACTIVE' AND"
                                + " ("
                                + String.format(Locale.ROOT, role, "SARDAR")
                                + " <> 1 OR "
                                + String.format(Locale.ROOT, role, "MANDAL")
                                + " <> 1)",
                        p,
                        Long.class);
        Long blocked =
                named.queryForObject(
                        "SELECT COUNT(*)"
                                + GANGS
                                + " AND EXISTS (SELECT 1 FROM work_slip w WHERE w.gang_id ="
                                + " g.gang_id AND w.work_slip_date > g.effective_to)",
                        p,
                        Long.class);
        return new GangSummary(RowCounts.of(breaches), RowCounts.of(blocked));
    }

    /** M-09 Export (SRS-G-13): how many rows the filtered register has. */
    @Transactional(readOnly = true)
    public long gangExportSize(AccessContext context, GangFilter filter) {
        policy.require(context, M09, EXPORT);
        return gangPage(context, filter, new PageSpec(0, 1)).total();
    }

    /** M-09 Export (SRS-G-13): the filtered register, page by page. */
    @Transactional(readOnly = true)
    public void exportGangs(AccessContext context, GangFilter filter, ExportSink sink)
            throws IOException {
        policy.require(context, M09, EXPORT);
        sink.header(
                "Gang",
                "Shed",
                "Depot",
                "Sardar",
                "Mandal",
                "Labour count",
                "Eff. From",
                "Eff. To",
                "Status");
        int page = 0;
        Paged<GangLine> rows;
        do {
            rows = gangPage(context, filter, new PageSpec(page, EXPORT_PAGE));
            for (GangLine r : rows.items()) {
                sink.row(
                        r.gangCode(),
                        r.shed(),
                        r.depot(),
                        r.sardar(),
                        r.mandal(),
                        r.labourCount(),
                        r.effectiveFrom(),
                        r.effectiveTo(),
                        r.status());
            }
            page++;
        } while ((long) page * EXPORT_PAGE < rows.total());
    }

    /**
     * M-09 "Gang strength today" (BR-LABM-011, SRS-2-48): today's check of the page's gangs, as
     * I-01 reads it; the depot's Ancillary Pool shows its present labour, without a strength.
     */
    @Transactional(readOnly = true)
    public Paged<StrengthLine> strengthToday(
            AccessContext context, GangFilter filter, PageSpec spec) {
        policy.require(context, M09, VIEW);
        LocalDate today = parameters.today();
        MapSqlParameterSource p = gangParameters(context, filter);
        String active = GANGS + " AND g.gang_status = 'ACTIVE'";
        Long total = named.queryForObject("SELECT COUNT(*)" + active, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<StrengthLine> rows =
                named
                        .query(
                                "SELECT g.gang_id, g.gang_code, g.gang_name, g.gang_type"
                                        + active
                                        + " ORDER BY l.location_code, g.gang_type, g.gang_code,"
                                        + " g.gang_id OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                        + " ONLY",
                                p,
                                (rs, i) ->
                                        new StrengthLine(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                null,
                                                0,
                                                0,
                                                false,
                                                List.of()))
                        .stream()
                        .map(g -> withToday(g, today))
                        .toList();
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    private StrengthLine withToday(StrengthLine g, LocalDate today) {
        GangStrengthService.DayRead read = strengths.read(g.gangId(), today);
        return new StrengthLine(
                g.gangId(),
                g.gangCode(),
                g.gangName(),
                g.gangType(),
                HANDLING.equals(g.gangType()) ? read.sanctioned() : null,
                read.present(),
                read.shortBy(),
                read.alertOpen(),
                read.attached());
    }

    // ---------------------------------------------------------------- M-06

    /** M-06: the norm sets at the session's depots, the set in force first (SRS-3-12); paged. */
    @Transactional(readOnly = true)
    public Paged<NormLine> norms(AccessContext context, NormFilter filter, PageSpec spec) {
        policy.require(context, M06, VIEW);
        return normPage(context, filter, spec);
    }

    private MapSqlParameterSource normParameters(AccessContext context, NormFilter filter) {
        String type = filter.normType();
        if (type != null && !type.isBlank() && !NORM_TYPES.contains(type)) {
            throw new FciException(
                    LabourErrorCode.ENTRY, new Object[] {"NORM_TYPE"}, List.of(), null);
        }
        return scope(context)
                .addValue("depot", filter.depot(), Types.BIGINT)
                .addValue("handling", "HANDLING".equals(type) ? "Y" : "N")
                .addValue("height", "HEIGHT".equals(type) ? "Y" : "N")
                .addValue("lead", "LEAD".equals(type) ? "Y" : "N")
                .addValue("text", like(filter.text()), Types.VARCHAR);
    }

    private Paged<NormLine> normPage(AccessContext context, NormFilter filter, PageSpec spec) {
        MapSqlParameterSource p = normParameters(context, filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + NORMS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<NormLine> rows =
                named.query(
                        "SELECT n.norm_id, l.location_code, l.location_name, n.mou_reference,"
                                + " n.min_labour_number, n.min_labour_slab_weight, n.height_norm,"
                                + " n.lead_norm, n.effective_from, n.effective_to, q.status"
                                + NORMS
                                + " ORDER BY l.location_code, n.effective_from DESC, n.norm_id"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new NormLine(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getLong(5),
                                        rs.getBigDecimal(6),
                                        rs.getBigDecimal(7),
                                        rs.getBigDecimal(8),
                                        day(rs, 9),
                                        day(rs, 10),
                                        state(rs.getString(11))));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** The M-06 tiles: the depots with a norm set, and the slab tables in use (SRS-3-6). */
    @Transactional(readOnly = true)
    public NormSummary normSummary(AccessContext context) {
        policy.require(context, M06, VIEW);
        MapSqlParameterSource p = normParameters(context, new NormFilter(null, null, null));
        Long depots =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT n.depot_location_id)" + NORMS, p, Long.class);
        Long tables =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT s.slab_table_ref)"
                                + NORMS.replace(
                                        " LEFT JOIN approval_queue q",
                                        " JOIN labour_above_norm_slab s ON s.norm_id = n.norm_id"
                                                + " LEFT JOIN approval_queue q"),
                        p,
                        Long.class);
        return new NormSummary(RowCounts.of(depots), RowCounts.of(tables));
    }

    /** M-06 "Above-Norm Slab Rates": the bands of a norm set of the session's depots (SRS-3-6). */
    @Transactional(readOnly = true)
    public List<SlabLine> slabs(AccessContext context, long normId) {
        policy.require(context, M06, VIEW);
        List<Long> depot =
                jdbc.queryForList(
                        "SELECT depot_location_id FROM labour_norm WHERE norm_id = ?",
                        Long.class,
                        normId);
        if (depot.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        policy.requireRecordAt(context, M06, VIEW, depot.get(0));
        return jdbc.query(
                "SELECT slab_table_ref, band_order, range_from, range_to, slab_rate_percent,"
                        + " step_size FROM labour_above_norm_slab WHERE norm_id = ? ORDER BY"
                        + " slab_table_ref, band_order",
                (rs, i) ->
                        new SlabLine(
                                rs.getString(1),
                                rs.getInt(2),
                                rs.getLong(3),
                                rs.getObject(4) == null ? null : rs.getLong(4),
                                rs.getBigDecimal(5),
                                rs.getObject(6) == null ? null : rs.getLong(6)),
                normId);
    }

    /** M-06 Export (SRS-G-13): how many rows the filtered register has. */
    @Transactional(readOnly = true)
    public long normExportSize(AccessContext context, NormFilter filter) {
        policy.require(context, M06, EXPORT);
        return normPage(context, filter, new PageSpec(0, 1)).total();
    }

    /** M-06 Export (SRS-G-13): the filtered register, page by page. */
    @Transactional(readOnly = true)
    public void exportNorms(AccessContext context, NormFilter filter, ExportSink sink)
            throws IOException {
        policy.require(context, M06, EXPORT);
        sink.header(
                "Depot",
                "MoU reference",
                "Handling Norm (MLN)",
                "MLSW",
                "Height Norm",
                "Lead Norm",
                "Eff. From",
                "Eff. To",
                "Status");
        int page = 0;
        Paged<NormLine> rows;
        do {
            rows = normPage(context, filter, new PageSpec(page, EXPORT_PAGE));
            for (NormLine r : rows.items()) {
                sink.row(
                        r.depot(),
                        r.mouReference(),
                        r.minLabourNumber(),
                        r.minSlabWeight(),
                        r.heightNorm(),
                        r.leadNorm(),
                        r.effectiveFrom(),
                        r.effectiveTo(),
                        r.approvalState());
            }
            page++;
        } while ((long) page * EXPORT_PAGE < rows.total());
    }

    // ---------------------------------------------------------------- M-05

    /** M-05 ▸ NWNP: the ASOR sets with the designation-wise MGW, newest first (SRS-5-3); paged. */
    @Transactional(readOnly = true)
    public Paged<AsorLine> asorRates(AccessContext context, PageSpec spec) {
        policy.require(context, M05, VIEW);
        MapSqlParameterSource p =
                new MapSqlParameterSource().addValue("type", NWNP).addValue("version", "%-ASOR");
        Long total = named.queryForObject("SELECT COUNT(*)" + SETS, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<AsorLine> sets =
                named.query(
                        "SELECT s.rate_set_id, s.rate_set_code, l.location_name, s.effective_from,"
                            + " s.effective_to, s.approval_state, (SELECT MAX(r.piece_of_rate) FROM"
                            + " nwnp_sor_rate r WHERE r.rate_set_id = s.rate_set_id) AS piece"
                                + SETS
                                + " ORDER BY s.effective_from, s.rate_set_id OFFSET :offset ROWS"
                                + " FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new AsorLine(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        day(rs, 4),
                                        day(rs, 5),
                                        rs.getString(6),
                                        rs.getBigDecimal(7),
                                        Map.of()));
        return new Paged<>(
                sets.stream().map(this::withMgw).toList(),
                spec.page(),
                spec.size(),
                RowCounts.of(total));
    }

    private AsorLine withMgw(AsorLine s) {
        Map<String, BigDecimal> mgw =
                jdbc
                        .query(
                                "SELECT gang_role, min_guaranteed_wage FROM labour_min_wage WHERE"
                                        + " rate_set_id = ? AND gang_role IS NOT NULL AND"
                                        + " min_guaranteed_wage IS NOT NULL",
                                (rs, i) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)),
                                s.id())
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        return new AsorLine(
                s.id(),
                s.code(),
                s.depot(),
                s.effectiveFrom(),
                s.effectiveTo(),
                s.approvalState(),
                s.pieceOfRate(),
                mgw);
    }

    /** M-05 ▸ NWNP: the lines of the Schedule of Rates, newest first (SRS-5-2); paged. */
    @Transactional(readOnly = true)
    public Paged<SorLine> sorRates(AccessContext context, PageSpec spec) {
        policy.require(context, M05, VIEW);
        String from =
                " FROM nwnp_sor_rate r JOIN nwnp_operation n ON n.operation_code = r.operation_code"
                        + " LEFT JOIN rate_set s ON s.rate_set_id = r.rate_set_id WHERE"
                        + " r.rate_version = 'SOR'";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class);
        List<SorLine> rows =
                jdbc.query(
                        "SELECT r.sor_rate_id, r.operation_code, n.operation_name, (SELECT"
                            + " MAX(v.value_label_en) FROM reference_value v JOIN reference_list l"
                            + " ON l.list_id = v.list_id WHERE l.list_code = 'NWNP_UOM' AND"
                            + " v.value_code = r.unit_of_measure) AS unit, r.rate_per_unit,"
                            + " r.effective_from, r.effective_to, s.approval_state"
                                + from
                                + " ORDER BY r.effective_from DESC, r.operation_code OFFSET ? ROWS"
                                + " FETCH NEXT ? ROWS ONLY",
                        (rs, i) ->
                                new SorLine(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getBigDecimal(5),
                                        day(rs, 6),
                                        day(rs, 7),
                                        state(rs.getString(8))),
                        (long) spec.page() * spec.size(),
                        spec.size());
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * M-05 ▸ NWNP: the Minimum Wage by location type (SRS-5-11; DDL labour_min_wage mgw_basis
     * BASIC_PLUS_VDA), with MGW = (Basic Wage + VDA) x 26. Which representation pays is PENDING
     * NWNP-22b-1, so nothing but this read uses the row.
     */
    @Transactional(readOnly = true)
    public List<MinWageLine> minWages(AccessContext context) {
        policy.require(context, M05, VIEW);
        return jdbc.query(
                "SELECT w.min_wage_id, w.nwnp_loc_type, w.basic_wage, w.vda, w.effective_from,"
                        + " w.effective_to, q.status FROM labour_min_wage w LEFT JOIN"
                        + " approval_queue q ON q.approval_id = w.approval_id WHERE w.engine_code"
                        + " = ? AND w.nwnp_loc_type IS NOT NULL AND w.rate_set_id IS NULL ORDER BY"
                        + " w.nwnp_loc_type, w.effective_from DESC",
                (rs, i) -> {
                    BigDecimal basic = rs.getBigDecimal(3);
                    BigDecimal vda = rs.getBigDecimal(4);
                    return new MinWageLine(
                            rs.getLong(1),
                            rs.getString(2),
                            basic,
                            vda,
                            basic == null || vda == null
                                    ? null
                                    : basic.add(vda).multiply(BigDecimal.valueOf(26)),
                            day(rs, 5),
                            day(rs, 6),
                            state(rs.getString(7)));
                },
                NWNP);
    }

    private static final String RATE_TABLE =
            " FROM labour_min_wage w LEFT JOIN org_designation d ON d.designation_id ="
                    + " w.designation_id WHERE w.engine_code = ?";

    /** M-05 Export (SRS-G-13): how many rows the engine's rate table has. */
    @Transactional(readOnly = true)
    public long rateExportSize(AccessContext context, String type) {
        policy.require(context, M05, EXPORT);
        return RowCounts.of(
                jdbc.queryForObject("SELECT COUNT(*)" + RATE_TABLE, Long.class, engine(type)));
    }

    /** M-05 Export (SRS-G-13): the engine's rate table, every version. */
    @Transactional(readOnly = true)
    public void exportRates(AccessContext context, String type, ExportSink sink)
            throws IOException {
        policy.require(context, M05, EXPORT);
        sink.header(
                "Designation",
                "Location type",
                "MGW",
                "Basic wage",
                "VDA",
                "Interim Relief",
                "GoI rate",
                "Eff. From",
                "Eff. To");
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT d.designation_name, w.nwnp_loc_type, w.min_guaranteed_wage,"
                                + " w.basic_wage, w.vda, w.interim_relief, w.goi_rate,"
                                + " w.effective_from, w.effective_to"
                                + RATE_TABLE
                                + " ORDER BY w.effective_from DESC, d.designation_name",
                        engine(type));
        for (Map<String, Object> r : rows) {
            sink.row(
                    r.get("DESIGNATION_NAME"),
                    r.get("NWNP_LOC_TYPE"),
                    r.get("MIN_GUARANTEED_WAGE"),
                    r.get("BASIC_WAGE"),
                    r.get("VDA"),
                    r.get("INTERIM_RELIEF"),
                    r.get("GOI_RATE"),
                    r.get("EFFECTIVE_FROM"),
                    r.get("EFFECTIVE_TO"));
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    // ---------------------------------------------------------------- shared

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    /** The engine of a view; a value outside the three labour payrolls is refused. */
    private static String engine(String payrollType) {
        String type = payrollType == null || payrollType.isBlank() ? DEPT : payrollType;
        if (!LabourRules.PAYROLL_TYPES.contains(type)) {
            throw new FciException(
                    LabourErrorCode.ENTRY, new Object[] {"PAYROLL_TYPE"}, List.of(), null);
        }
        return type;
    }

    private static String like(String text) {
        return text == null || text.isBlank()
                ? null
                : "%" + text.strip().toUpperCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String strip(String text) {
        return text == null ? null : text.strip();
    }

    private static String name(String first, String last) {
        return last == null ? first : first + " " + last;
    }

    private static String state(String status) {
        return status == null ? APPROVED : status;
    }

    private static LocalDate day(ResultSet rs, int column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    private static LocalDate day(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
