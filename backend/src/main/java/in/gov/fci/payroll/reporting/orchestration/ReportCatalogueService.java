package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportRules;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report catalogue as O-04 shows it (plan 15 phase 15b; SRS-18-9, SRS-18-14, SRS-18-24,
 * SRS-18-25; D15-2, D15-3): every active definition with the version in force today and its status
 * (produced on O-04, produced on its own screen, or Blocked with the reason), by family; paged.
 */
@Service
@Loggable
public class ReportCatalogueService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /** A family of the M-12 summary: an Annexure section with its entries (Wireframes v6.1). */
    public record SectionRow(String section, long reports, long blocked) {}

    /**
     * The M-12 tiles, the family table and the filter choices from the catalogue itself (OD-123):
     * {@code active} counts the entries with an approved version in force, not Blocked (SRS-18-24).
     */
    public record Summary(
            long size,
            long active,
            List<SectionRow> sections,
            List<String> families,
            List<String> payrollTypes) {}

    /** The version of a month's CPF reports and of the month before (BR-CPF-036). */
    public record CpfMonths(
            String period, String version, String previous, String previousVersion) {}

    /** One catalogue entry. */
    public record Entry(
            String key,
            String name,
            String family,
            String section,
            String domain,
            String producingScreen,
            String payrollTypes,
            String periodicity,
            String lockRequirement,
            Integer versionNo,
            String layoutFormat,
            String blockedReason,
            String status) {}

    private static final String FROM =
            " FROM report_definition d LEFT JOIN report_definition_version v ON"
                    + " v.report_def_id = d.report_def_id AND v.approval_state = 'APPROVED' AND"
                    + " v.effective_from <= :today AND (v.effective_to IS NULL OR v.effective_to >="
                    + " :today) WHERE d.active_flag = 'Y' AND (:family IS NULL OR d.report_family ="
                    + " :family) AND (:text IS NULL OR UPPER(d.report_name) LIKE :text) AND (:type"
                    + " IS NULL OR d.applicable_payroll_types = 'All' OR"
                    + " INSTR(d.applicable_payroll_types, :type) > 0 OR"
                    + " (d.applicable_payroll_types = 'Labour' AND :type <> 'Employee'))";

    private static final String SELECT =
            "SELECT d.report_key, d.report_name, d.report_family, d.catalogue_section,"
                    + " d.output_domain, d.producing_screen_code, d.applicable_payroll_types,"
                    + " d.periodicity, d.lock_requirement, v.version_no, v.layout_format,"
                    + " v.is_blocked, v.blocked_reason";

    private static final String ORDER =
            " ORDER BY d.report_family, d.report_name, d.report_key OFFSET :offset ROWS"
                    + " FETCH NEXT :size ROWS ONLY";

    /** M-12 lists in the drawn order of the Annexure sections (Wireframes v6.1). */
    private static final String SECTION_ORDER =
            " ORDER BY d.catalogue_section, d.report_name, d.report_key OFFSET :offset ROWS"
                    + " FETCH NEXT :size ROWS ONLY";

    /**
     * The drawn M-12 family: no report_family of its own (DDL: Control-Audit-MIS includes it), it
     * is the Annexure A.4.6 set of O-07.
     */
    private static final String CPF_FAMILY = "CPF Control & Audit";

    private static final String LABOUR_SCREEN = "O-03";

    /** The report family of O-07 in the drawn order (Annexure A.4.6; BR-CPF-036). */
    private static final List<String> O_07 =
            List.of(
                    "FCI_ESEVA_REPORT",
                    "FCI_ESEVA_ARREAR_REPORT",
                    "CPF_SANCTION_PAYMENT",
                    "CPF_LIST_OF_SANCTION",
                    "CPF_CONTROL_REGISTER",
                    "CPF_OBCB",
                    "ZONE_SURRENDER",
                    "PF_ADJUSTMENT",
                    "CPF_FINAL_BILL",
                    "CPF_NEW_JOINEES",
                    "CPF_NOT_CALCULATED",
                    "IOCPF_PENDING");

    /** The CPF report set of O-08 in the order of the SRS table (l.2443). */
    private static final List<String> O_08 =
            List.of(
                    "CPF_OBCB",
                    "CPF_CONTROL_REGISTER",
                    "CPF_LIST_OF_SANCTION",
                    "CPF_FINAL_BILL",
                    "ZONE_SURRENDER",
                    "PF_ADJUSTMENT",
                    "CPF_BONUS_INTEREST",
                    "CPF_MAKER_PENDING",
                    "CPF_UNMATCHED");

    /** BR-CPF-036: a month is Final from the start of its lock, Provisional before it. */
    private static final String FINAL = "FINAL";

    private static final String PROVISIONAL = "PROVISIONAL";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final Clock clock;

    public ReportCatalogueService(
            NamedParameterJdbcTemplate named, Authorization policy, Clock clock) {
        this.named = named;
        this.policy = policy;
        this.clock = clock;
    }

    /** O-04: the catalogue, by family and name; paged. */
    @Transactional(readOnly = true)
    public Paged<Entry> page(AccessContext context, String family, String text, PageSpec spec) {
        return page(context, ReportRules.HERE, family, text, spec);
    }

    /**
     * The catalogue as {@code gate} shows it: O-04 (the reports a user runs) or M-12 Report
     * Definition Catalogue (SRS l.5440: viewed by maker, checker, hr, fin, admin; the Rule Approver
     * holds M-12 only, UI audit S8). The same rows; the screen's VIEW is checked.
     */
    @Transactional(readOnly = true)
    public Paged<Entry> page(
            AccessContext context, String gate, String family, String text, PageSpec spec) {
        return page(context, gate, family, null, text, spec);
    }

    /** M-12: as above, with the drawn payroll-type filter (OD-123: a value of the catalogue). */
    @Transactional(readOnly = true)
    public Paged<Entry> page(
            AccessContext context,
            String gate,
            String family,
            String payrollType,
            String text,
            PageSpec spec) {
        policy.require(context, gate, "VIEW");
        boolean cpfFamily = CPF_FAMILY.equals(blank(family));
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("today", Date.valueOf(LocalDate.now(clock.withZone(IST))))
                        .addValue("family", cpfFamily ? null : blank(family), Types.VARCHAR)
                        .addValue("type", blank(payrollType), Types.VARCHAR)
                        .addValue(
                                "text",
                                blank(text) == null
                                        ? null
                                        : "%"
                                                + text.strip().toUpperCase(java.util.Locale.ROOT)
                                                + "%",
                                Types.VARCHAR);
        String where = FROM;
        if (cpfFamily) {
            where = FROM + " AND d.report_key IN (:cpfKeys)";
            p.addValue("cpfKeys", O_07);
        }
        Long total = named.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        String order = "M-12".equals(gate) ? SECTION_ORDER : ORDER;
        List<Entry> rows = named.query(SELECT + where + order, p, ReportCatalogueService::entry);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** M-12: the catalogue by Annexure section and the choices of its two filters. */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, "M-12", "VIEW");
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("today", Date.valueOf(LocalDate.now(clock.withZone(IST))));
        List<SectionRow> sections =
                named.query(
                        "SELECT d.catalogue_section, COUNT(*), SUM(CASE WHEN v.report_version_id IS"
                                + " NULL OR v.is_blocked = 'Y' THEN 1 ELSE 0 END) FROM"
                                + " report_definition d LEFT JOIN report_definition_version v ON"
                                + " v.report_def_id = d.report_def_id AND v.approval_state ="
                                + " 'APPROVED' AND v.effective_from <= :today AND (v.effective_to"
                                + " IS NULL OR v.effective_to >= :today) WHERE d.active_flag = 'Y'"
                                + " GROUP BY d.catalogue_section ORDER BY d.catalogue_section",
                        p,
                        (rs, i) -> new SectionRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        long size = sections.stream().mapToLong(SectionRow::reports).sum();
        long blocked = sections.stream().mapToLong(SectionRow::blocked).sum();
        return new Summary(
                size,
                size - blocked,
                sections,
                named.queryForList(
                        "SELECT DISTINCT d.report_family FROM report_definition d WHERE"
                                + " d.active_flag = 'Y' ORDER BY d.report_family",
                        p,
                        String.class),
                named.queryForList(
                        "SELECT DISTINCT d.applicable_payroll_types FROM report_definition d WHERE"
                                + " d.active_flag = 'Y' AND d.applicable_payroll_types IS NOT NULL"
                                + " ORDER BY d.applicable_payroll_types",
                        p,
                        String.class));
    }

    /**
     * O-03 Labour Registers &amp; Reports (Wireframes v6.1, OD-121; FRS §6.21, Annexure A.4, A.11):
     * the labour report catalogue of one labour payroll. {@code annexure} false: the definitions
     * O-03 produces; true: the labour reports of Annexure A.11. Nothing is generated here: a
     * Blocked entry says why (SRS-18-24, V069_1).
     */
    @Transactional(readOnly = true)
    public Paged<Entry> labour(
            AccessContext context, boolean annexure, String engineWord, PageSpec spec) {
        policy.require(context, LABOUR_SCREEN, "VIEW");
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("today", Date.valueOf(LocalDate.now(clock.withZone(IST))))
                        .addValue("family", null, Types.VARCHAR)
                        .addValue("type", null, Types.VARCHAR)
                        .addValue("text", null, Types.VARCHAR)
                        .addValue("word", "%" + engineWord + "%")
                        .addValue("screen", LABOUR_SCREEN);
        String where =
                FROM
                        + (annexure
                                ? " AND d.catalogue_section = 'A.11' AND d.report_family ="
                                        + " 'Labour Registers'"
                                : " AND d.producing_screen_code = :screen"
                                        + " AND d.catalogue_section <> 'A.11'")
                        + " AND (d.applicable_payroll_types IN ('All', 'Labour') OR"
                        + " UPPER(d.applicable_payroll_types) LIKE :word)";
        Long total = named.queryForObject("SELECT COUNT(*)" + where, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<Entry> rows = named.query(SELECT + where + ORDER, p, ReportCatalogueService::entry);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * O-07 and O-08 (Wireframes v6.1, OD-121; BR-CPF-036, SRS O-08 table): the CPF report set of
     * the screen in the drawn order. Nothing is generated: an entry is Blocked with its reason
     * until FCI gives the layout or the method (SRS-18-16, SRS-18-24, OQ-12, Q-072; PENDING 07j).
     */
    @Transactional(readOnly = true)
    public Paged<Entry> cpf(AccessContext context, String screen, PageSpec spec) {
        policy.require(context, screen, "VIEW");
        List<String> keys = "O-07".equals(screen) ? O_07 : O_08;
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("today", Date.valueOf(LocalDate.now(clock.withZone(IST))))
                        .addValue("family", null, Types.VARCHAR)
                        .addValue("type", null, Types.VARCHAR)
                        .addValue("text", null, Types.VARCHAR)
                        .addValue("keys", keys);
        List<Entry> rows =
                named.query(
                        SELECT + FROM + " AND d.report_key IN (:keys)",
                        p,
                        ReportCatalogueService::entry);
        return Paged.slice(
                rows.stream().sorted(Comparator.comparingInt(e -> keys.indexOf(e.key()))).toList(),
                spec);
    }

    /**
     * BR-CPF-036: whether the month and the month before it are Final at the session's payroll unit
     * (a hosted DO runs at its parent, {@code cycle_host_location_id}), read from the cycle state.
     */
    @Transactional(readOnly = true)
    public CpfMonths cpfMonths(AccessContext context, String yymm) {
        policy.require(context, "O-07", "VIEW");
        YearMonth month = YearMonth.parse(yymm, YYMM);
        String previous = month.minusMonths(1).format(YYMM);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("unit", context.locationId())
                        .addValue("months", List.of(yymm, previous));
        List<String> locked =
                named.queryForList(
                        "SELECT c.payroll_yymm FROM payroll_cycle_instance c JOIN org_location l ON"
                                + " c.org_unit_id = COALESCE(l.cycle_host_location_id,"
                                + " l.location_id) WHERE l.location_id = :unit AND c.payroll_yymm"
                                + " IN (:months) AND c.cycle_status = 'LOCKED'",
                        p,
                        String.class);
        return new CpfMonths(
                yymm,
                locked.contains(yymm) ? FINAL : PROVISIONAL,
                previous,
                locked.contains(previous) ? FINAL : PROVISIONAL);
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        int version = rs.getInt("version_no");
        boolean hasVersion = !rs.wasNull();
        boolean blocked = "Y".equals(rs.getString("is_blocked"));
        String screen = rs.getString("producing_screen_code");
        return new Entry(
                rs.getString("report_key"),
                rs.getString("report_name"),
                rs.getString("report_family"),
                rs.getString("catalogue_section"),
                rs.getString("output_domain"),
                screen,
                rs.getString("applicable_payroll_types"),
                rs.getString("periodicity"),
                rs.getString("lock_requirement"),
                hasVersion ? version : null,
                rs.getString("layout_format"),
                hasVersion ? rs.getString("blocked_reason") : "No approved version in force",
                ReportRules.status(hasVersion, blocked, screen).name());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
