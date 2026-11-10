package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportRules;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O-02 Bank Advice &amp; EBS Posting and O-11 Monthly &amp; Year-End Remittance, the read-only
 * parts (SRS-15-34, SRS-15-35, SRS-15-41, SRS-G-52, SRS-18-19, SRS l.1707-1713; Wireframes v6.1,
 * OD-121; API-15-5 in PENDING). Nothing here generates, posts or transmits: the bank file layout,
 * the EBS and APPS contracts and the year-end logic are not supplied (API-23-2, API-23-6, OQ-36),
 * so the drawn buttons stay disabled with the reason, and these reads carry what the screens can
 * already show.
 *
 * <p>The figures read committed ACTUAL results of the session's offices. Banks come from the
 * employee record fed through I-21 (a per-member HRMS call cannot serve an aggregate, WF-1549).
 */
@Service
@Loggable
public class OutputWindowsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String BANK_SCREEN = "O-02";
    private static final String REMITTANCE_SCREEN = "O-11";

    /** One bank of the summary; {@code status}: bank_advice status, or NOT_GENERATED. */
    public record BankRow(
            String bank,
            long members,
            BigDecimal net,
            long blocked,
            String fileFormat,
            String status) {}

    /** One line of the GL posting preview. */
    public record GlRow(String head, String account, BigDecimal debit, BigDecimal credit) {}

    /** One invoice state of the month (SRS l.1713). */
    public record InvoiceState(String status, long count, Instant sentToApps) {}

    /** O-02 of a month. {@code locked}: every cycle of the month in scope is locked. */
    public record BankOverview(
            String yymm,
            boolean locked,
            BigDecimal net,
            long banks,
            long blocked,
            String ebsStatus,
            boolean suspended,
            List<BankRow> bankRows,
            long members,
            List<GlRow> gl,
            List<InvoiceState> invoices) {}

    /** The tiles of O-11 for the month. */
    public record RemittanceOverview(
            String yymm,
            boolean locked,
            long payments,
            long lateArrears,
            long supplementary,
            long unmappedPayCodes,
            String yearEndFinYear,
            String yearEndStatus,
            Instant yearEndClosedAt) {}

    /** A row of Remittance runs; the invoice numbers are the APPS staging range of the run. */
    public record RunRow(
            long id,
            String type,
            String period,
            String finYear,
            String invoiceFrom,
            String invoiceTo,
            Instant generated,
            String transmittedBy,
            Instant transmittedAt,
            String reference,
            String status) {}

    private static final String RESULTS =
            " FROM payroll_result r JOIN employee e ON e.employee_id = r.employee_id LEFT JOIN"
                    + " bank_branch bb ON bb.branch_id = e.bank_branch_id LEFT JOIN bank_master m"
                    + " ON m.bank_id = bb.bank_id WHERE r.period_date = :day AND r.payroll_yymm ="
                    + " :yymm AND r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT' AND (:all ="
                    + " 'Y' OR r.org_unit_id IN (:locations))";

    /** US-GAP-147: a member with no account or no branch cannot be paid: left out and counted. */
    private static final String BLOCKED =
            "(e.bank_account_number IS NULL OR e.bank_branch_id IS NULL)";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final Clock clock;

    public OutputWindowsService(
            NamedParameterJdbcTemplate named, Authorization policy, Clock clock) {
        this.named = named;
        this.policy = policy;
        this.clock = clock;
    }

    /** O-02: the month (default: the session's newest month with a cycle in scope). */
    @Transactional(readOnly = true)
    public BankOverview bank(AccessContext context, String yymm) {
        policy.require(context, BANK_SCREEN, "VIEW");
        String month = month(context, yymm);
        MapSqlParameterSource p = scoped(context, month);
        List<BankRow> banks =
                named.query(
                        "SELECT m.bank_id, COALESCE(m.bank_name, '-') bank, COUNT(*) members,"
                                + " SUM(r.net_pay) net, SUM(CASE WHEN "
                                + BLOCKED
                                + " THEN 1 ELSE 0 END) blocked, MAX(a.file_format) ff,"
                                + " MAX(a.advice_status) st"
                                + RESULTS.replace(
                                        " WHERE",
                                        " LEFT JOIN bank_advice a ON a.bank_id = m.bank_id AND"
                                            + " a.payroll_yymm = r.payroll_yymm AND"
                                            + " a.cycle_instance_id = r.cycle_instance_id WHERE")
                                + " GROUP BY m.bank_id, m.bank_name ORDER BY net DESC",
                        p,
                        (rs, i) ->
                                new BankRow(
                                        rs.getString("bank"),
                                        rs.getLong("members"),
                                        rs.getBigDecimal("net"),
                                        rs.getLong("blocked"),
                                        rs.getString("ff"),
                                        rs.getString("st") == null
                                                ? "NOT_GENERATED"
                                                : rs.getString("st")));
        BigDecimal net = banks.stream().map(BankRow::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        long blocked = banks.stream().mapToLong(BankRow::blocked).sum();
        long members = banks.stream().mapToLong(BankRow::members).sum();
        List<GlRow> gl =
                named.query(
                        "SELECT COALESCE(c.component_name, l.natural_account) head,"
                                + " l.natural_account, l.debit_amount, l.credit_amount FROM"
                                + " gl_posting_line l JOIN gl_posting_statement s ON s.statement_id"
                                + " = l.statement_id JOIN payroll_cycle_instance i ON"
                                + " i.cycle_instance_id = s.cycle_instance_id LEFT JOIN"
                                + " pay_component c ON c.component_id = l.component_id WHERE"
                                + " s.payroll_yymm = :yymm AND (:all = 'Y' OR i.org_unit_id IN"
                                + " (:locations)) ORDER BY l.posting_line_id",
                        p,
                        (rs, i) ->
                                new GlRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getBigDecimal(3),
                                        rs.getBigDecimal(4)));
        List<InvoiceState> invoices =
                named.query(
                        "SELECT i.posting_status, COUNT(*), MAX(i.sent_to_apps_at) FROM"
                                + " ap_invoice_staging i WHERE i.payroll_yymm = :yymm AND (:all ="
                                + " 'Y' OR i.org_unit_id IN (:locations)) GROUP BY i.posting_status"
                                + " ORDER BY i.posting_status",
                        p,
                        (rs, i) ->
                                new InvoiceState(
                                        rs.getString(1),
                                        rs.getLong(2),
                                        instant(rs.getTimestamp(3))));
        List<String> ebs =
                named.queryForList(
                        "SELECT b.batch_status FROM ap_posting_batch b WHERE b.payroll_yymm = :yymm"
                                + " AND (:all = 'Y' OR b.org_unit_id IN (:locations)) ORDER BY"
                                + " b.batch_id DESC FETCH FIRST 1 ROWS ONLY",
                        p,
                        String.class);
        return new BankOverview(
                month,
                locked(p),
                net,
                banks.stream().filter(b -> !"-".equals(b.bank())).count(),
                blocked,
                ebs.isEmpty() ? "IDLE" : ebs.getFirst(),
                suspended(context),
                banks,
                members,
                gl,
                invoices);
    }

    /** O-11: the Monthly Remittance window of the month (SRS-G-52) and the year-end state. */
    @Transactional(readOnly = true)
    public RemittanceOverview remittance(AccessContext context, String yymm) {
        policy.require(context, REMITTANCE_SCREEN, "VIEW");
        String month = month(context, yymm);
        MapSqlParameterSource p = scoped(context, month);
        YearMonth m = YearMonth.parse(month, YYMM);
        p.addValue("from", Timestamp.valueOf(m.atDay(1).atStartOfDay()))
                .addValue("to", Timestamp.valueOf(m.plusMonths(1).atDay(1).atStartOfDay()));
        String window =
                " FROM ap_invoice_staging i WHERE i.sent_to_apps_at >= :from AND i.sent_to_apps_at"
                        + " < :to AND i.posting_status <> 'CANCELLED' AND (:all = 'Y' OR"
                        + " i.org_unit_id IN (:locations))";
        long payments = count("SELECT COUNT(*)" + window, p);
        // SRS-G-52: the window gathers by the date sent, whatever the processing date.
        long late = count("SELECT COUNT(*)" + window + " AND i.source_txn_type LIKE '%ARREAR%'", p);
        long supplementary =
                count("SELECT COUNT(*)" + window + " AND i.source_txn_type = 'SUPPLEMENTARY'", p);
        // SRS-18-19 (BR-MIS-006): a deduction with no agency is left out and flagged.
        long unmapped =
                count(
                        "SELECT COUNT(*) FROM pay_component c WHERE c.is_retired = 'N' AND"
                                + " c.agency_id IS NULL AND c.component_type = 'DEDUCTION' AND"
                                + " :yymm IS NOT NULL",
                        p);
        List<Object[]> yearEnd =
                named.query(
                        "SELECT r.fin_year, r.run_status, r.fin_year_closed_at FROM remittance_run"
                                + " r WHERE r.remittance_type = 'YEAR_END' AND (:all = 'Y' OR"
                                + " r.org_unit_id IN (:locations)) ORDER BY r.fin_year DESC FETCH"
                                + " FIRST 1 ROWS ONLY",
                        p,
                        (rs, i) ->
                                new Object[] {
                                    rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3))
                                });
        Object[] ye = yearEnd.isEmpty() ? new Object[3] : yearEnd.getFirst();
        return new RemittanceOverview(
                month,
                locked(p),
                payments,
                late,
                supplementary,
                unmapped,
                (String) ye[0],
                (String) ye[1],
                (Instant) ye[2]);
    }

    /** O-11: the runs of the session's offices, newest first, paged on the server. */
    @Transactional(readOnly = true)
    public Paged<RunRow> runs(AccessContext context, PageSpec spec) {
        policy.require(context, REMITTANCE_SCREEN, "VIEW");
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        String from = " FROM remittance_run r WHERE (:all = 'Y' OR r.org_unit_id IN (:locations))";
        long total = count("SELECT COUNT(*)" + from, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<RunRow> rows =
                named.query(
                        "SELECT r.remittance_run_id, r.remittance_type, r.payroll_yymm,"
                                + " r.fin_year, o.generated_at, r.transmitted_by_emp_no,"
                                + " r.released_at, r.transmission_reference, r.run_status, (SELECT"
                                + " MIN(i.invoice_number) FROM remittance_line l JOIN"
                                + " ap_invoice_staging i ON i.invoice_staging_id ="
                                + " l.invoice_staging_id WHERE l.remittance_run_id ="
                                + " r.remittance_run_id) AS first_invoice, (SELECT"
                                + " MAX(i.invoice_number) FROM remittance_line l JOIN"
                                + " ap_invoice_staging i ON i.invoice_staging_id ="
                                + " l.invoice_staging_id WHERE l.remittance_run_id ="
                                + " r.remittance_run_id) AS last_invoice"
                                + from.replace(
                                        " WHERE",
                                        " LEFT JOIN report_output o ON o.report_output_id ="
                                                + " r.preview_report_output_id WHERE")
                                + " ORDER BY r.remittance_run_id DESC OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new RunRow(
                                        rs.getLong("remittance_run_id"),
                                        rs.getString("remittance_type"),
                                        rs.getString("payroll_yymm"),
                                        rs.getString("fin_year"),
                                        rs.getString("first_invoice"),
                                        rs.getString("last_invoice"),
                                        instant(rs.getTimestamp("generated_at")),
                                        rs.getString("transmitted_by_emp_no"),
                                        instant(rs.getTimestamp("released_at")),
                                        rs.getString("transmission_reference"),
                                        rs.getString("run_status")));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** {@code yymm}, or the newest month of the offices, or this month when they hold none. */
    private String month(AccessContext context, String yymm) {
        if (yymm != null) {
            return yymm;
        }
        return named
                .queryForList(
                        "SELECT MAX(c.payroll_yymm) FROM payroll_cycle_instance c WHERE (:all = 'Y'"
                                + " OR c.org_unit_id IN (:locations))",
                        OfficeScope.of(context, policy.isAtHeadquarters(context)),
                        String.class)
                .stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(YYMM.format(LocalDate.now(clock.withZone(IST))));
    }

    private MapSqlParameterSource scoped(AccessContext context, String month) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("yymm", month)
                .addValue("day", Date.valueOf(YearMonth.parse(month, YYMM).atDay(1)));
    }

    private boolean locked(MapSqlParameterSource p) {
        long cycles =
                count(
                        "SELECT COUNT(*) FROM payroll_cycle_instance c WHERE c.payroll_yymm = :yymm"
                                + " AND (:all = 'Y' OR c.org_unit_id IN (:locations))",
                        p);
        long locked =
                count(
                        "SELECT COUNT(*) FROM payroll_cycle_instance c WHERE c.payroll_yymm = :yymm"
                                + " AND c.cycle_status = 'LOCKED' AND (:all = 'Y' OR c.org_unit_id"
                                + " IN (:locations))",
                        p);
        return !ReportRules.provisional(cycles, locked);
    }

    /** SRS l.1709 "Suspended": an open stop covers some office of the session. */
    private boolean suspended(AccessContext context) {
        return count(
                        "SELECT COUNT(*) FROM disb_release_suspension s WHERE s.resumed_at IS NULL"
                                + " AND (s.scope_type = 'ALL_UNITS' OR (:all = 'Y' OR s.org_unit_id"
                                + " IN (:locations)))",
                        OfficeScope.of(context, policy.isAtHeadquarters(context)))
                > 0;
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
