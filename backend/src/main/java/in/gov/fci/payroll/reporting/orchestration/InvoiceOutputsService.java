package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O-06 Invoice Outputs, the read side as Wireframes v6.1 draws it (OD-121; FRS Annexure A.4,
 * §6.10.3.3, BR-AUDIT-009 / 010; DDL ap_invoice_staging, gl_posting_statement). Invoices are read,
 * never raised here: generating them, the reconciliation run and the release to Finance come with
 * the APPS posting of module 23 (PENDING 15-invoices, API-23-2), and the catalogue of invoice
 * outputs is {@code report_definition} rows with producing screen O-06. Every read is O-06 VIEW,
 * limited to the unit offices of the session.
 *
 * <p>An invoice type is derived from the staged row: the DDL holds no such column (PENDING
 * API-15-3). The GL gap is the invoices' total less the total debit of the same period's GL posting
 * statements (the pack's "same period, same total").
 */
@Service
@Loggable
public class InvoiceOutputsService {

    private static final String SCREEN = "O-06";

    /** The O-06 catalogue key of a staged invoice. */
    private static final String TYPE =
            "CASE WHEN i.source_txn_type = 'SALARY' AND i.gang_id IS NOT NULL THEN"
                    + " 'SALARY_INVOICE_GANG' WHEN i.source_txn_type = 'SALARY' AND i.employee_id"
                    + " IS NOT NULL THEN 'SALARY_INVOICE_EMPLOYEE' WHEN i.source_txn_type ="
                    + " 'SALARY' THEN 'SALARY_INVOICE_DEPOT' WHEN i.source_txn_type ="
                    + " 'LABOUR_INCENTIVE' THEN 'INCENTIVE_INVOICE' WHEN i.source_txn_type IN"
                    + " ('REMITTANCE', 'YEAR_END_REMITTANCE') THEN 'REMITTANCE_INVOICE' WHEN"
                    + " i.source_txn_type = 'SUPPLEMENTARY' THEN 'SUPPLEMENTARY_INVOICE' END";

    private static final String STAGED =
            " FROM ap_invoice_staging i WHERE (:all = 'Y' OR i.org_unit_id IN (:locations)) AND"
                    + " i.payroll_yymm = :yymm AND (:unit IS NULL OR i.org_unit_id = :unit) AND"
                    + " i.posting_status <> 'CANCELLED'";

    /** A unit of the O-06 filter: the offices of the session that hold invoices. */
    public record Unit(long id, String label) {}

    /** An invoice output of the catalogue for the filter. */
    public record TypeOption(String key, String name) {}

    /** The toolbar's choices and the tiles of the period shown; null period: no invoice yet. */
    public record Overview(
            String period,
            List<String> periods,
            List<Unit> units,
            long count,
            BigDecimal value,
            BigDecimal salary,
            BigDecimal incentive,
            BigDecimal glGap,
            long released,
            long held,
            boolean locked,
            List<TypeOption> types) {}

    /** A row of the Invoice Register, one per invoice output. */
    public record RegisterRow(
            String key,
            String name,
            String section,
            long count,
            BigDecimal value,
            long held,
            long released,
            String blockedReason) {}

    /** An invoice with its status (BR-AUDIT-010). */
    public record InvoiceLine(
            long id,
            String number,
            String unit,
            String paymentType,
            BigDecimal amount,
            String postingStatus,
            Instant sentToApps,
            String appsCancelStatus) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public InvoiceOutputsService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    /** The toolbar and the tiles: {@code yymm} or, when absent, the latest period with invoices. */
    @Transactional(readOnly = true)
    public Overview overview(AccessContext context, String yymm, Long unit) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = OfficeScope.of(context, policy.isAtHeadquarters(context));
        List<String> periods =
                named.queryForList(
                        "SELECT DISTINCT i.payroll_yymm FROM ap_invoice_staging i WHERE (:all = 'Y'"
                                + " OR i.org_unit_id IN (:locations)) AND i.payroll_yymm IS NOT"
                                + " NULL ORDER BY i.payroll_yymm DESC FETCH FIRST 24 ROWS ONLY",
                        p,
                        String.class);
        List<TypeOption> types =
                named.query(
                        "SELECT d.report_key, d.report_name FROM report_definition d WHERE"
                                + " d.producing_screen_code = :screen AND d.active_flag = 'Y' AND"
                                + " d.catalogue_section <> 'A.11' ORDER BY d.report_name",
                        p.addValue("screen", SCREEN),
                        (rs, i) ->
                                new TypeOption(
                                        rs.getString("report_key"), rs.getString("report_name")));
        String shown = yymm != null ? yymm : periods.stream().findFirst().orElse(null);
        if (shown == null) {
            return new Overview(
                    null,
                    periods,
                    List.of(),
                    0,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    0,
                    false,
                    types);
        }
        p.addValue("yymm", shown).addValue("unit", unit, Types.BIGINT);
        List<Unit> units =
                named.query(
                        "SELECT l.location_id, l.location_code, l.location_name FROM org_location l"
                                + " WHERE l.location_id IN (SELECT i.org_unit_id FROM"
                                + " ap_invoice_staging i WHERE (:all = 'Y' OR i.org_unit_id IN"
                                + " (:locations))) ORDER BY l.location_code",
                        p,
                        (rs, i) ->
                                new Unit(
                                        rs.getLong("location_id"),
                                        rs.getString("location_code")
                                                + " - "
                                                + rs.getString("location_name")));
        long count = count("SELECT COUNT(*)" + STAGED, p);
        long held = count("SELECT COUNT(*)" + STAGED + " AND i.posting_status = 'HELD'", p);
        long released =
                count(
                        "SELECT COUNT(*)" + STAGED + " AND i.posting_status IN ('SENT', 'POSTED')",
                        p);
        BigDecimal value = sum("SELECT SUM(i.invoice_amount)" + STAGED, p);
        BigDecimal salary =
                sum(
                        "SELECT SUM(i.invoice_amount)"
                                + STAGED
                                + " AND i.source_txn_type = 'SALARY'",
                        p);
        BigDecimal incentive =
                sum(
                        "SELECT SUM(i.invoice_amount)"
                                + STAGED
                                + " AND i.source_txn_type = 'LABOUR_INCENTIVE'",
                        p);
        BigDecimal debit =
                sum(
                        "SELECT SUM(s.total_debit) FROM gl_posting_statement s WHERE (:all = 'Y' OR"
                                + " s.org_unit_id IN (:locations)) AND s.payroll_yymm = :yymm AND"
                                + " (:unit IS NULL OR s.org_unit_id = :unit)",
                        p);
        long open =
                count(
                        "SELECT COUNT(*) FROM payroll_cycle_instance c WHERE c.payroll_yymm = :yymm"
                                + " AND c.cycle_status <> 'LOCKED' AND c.org_unit_id IN (SELECT"
                                + " i.org_unit_id"
                                + STAGED
                                + ")",
                        p);
        return new Overview(
                shown,
                periods,
                units,
                count,
                value,
                salary,
                incentive,
                value.subtract(debit).abs(),
                released,
                held,
                open == 0,
                types);
    }

    /** The Invoice Register of the period: every invoice output, with what is staged for it. */
    @Transactional(readOnly = true)
    public Paged<RegisterRow> register(
            AccessContext context, String yymm, Long unit, String type, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = staged(context, yymm, unit).addValue("type", type, Types.VARCHAR);
        String from =
                " FROM report_definition d LEFT JOIN report_definition_version v ON"
                        + " v.report_def_id = d.report_def_id AND v.effective_to IS NULL WHERE"
                        + " d.producing_screen_code = 'O-06' AND d.active_flag = 'Y' AND"
                        + " d.catalogue_section <> 'A.11' AND (:type IS NULL OR d.report_key ="
                        + " :type)";
        long total = count("SELECT COUNT(*)" + from, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<RegisterRow> rows =
                named.query(
                        "SELECT d.report_key, d.report_name, d.catalogue_section, v.blocked_reason,"
                                + " t.cnt, t.val, t.held, t.released"
                                + " FROM report_definition d LEFT JOIN report_definition_version v"
                                + " ON v.report_def_id = d.report_def_id AND v.effective_to IS NULL"
                                + " LEFT JOIN (SELECT "
                                + TYPE
                                + " k, COUNT(*) cnt, SUM(i.invoice_amount) val, SUM(CASE WHEN"
                                + " i.posting_status = 'HELD' THEN 1 ELSE 0 END) held, SUM(CASE"
                                + " WHEN i.posting_status IN ('SENT', 'POSTED') THEN 1 ELSE 0 END)"
                                + " released"
                                + STAGED
                                + " GROUP BY "
                                + TYPE
                                + ") t ON t.k = d.report_key WHERE d.producing_screen_code = 'O-06'"
                                + " AND d.active_flag = 'Y' AND d.catalogue_section <> 'A.11' AND"
                                + " (:type IS NULL OR d.report_key = :type) ORDER BY d.report_name"
                                + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            BigDecimal val = rs.getBigDecimal("val");
                            return new RegisterRow(
                                    rs.getString("report_key"),
                                    rs.getString("report_name"),
                                    rs.getString("catalogue_section"),
                                    rs.getLong("cnt"),
                                    val == null ? BigDecimal.ZERO : val,
                                    rs.getLong("held"),
                                    rs.getLong("released"),
                                    rs.getString("blocked_reason"));
                        });
        return new Paged<>(rows, spec.page(), spec.size(), total);
    }

    /** Invoices — status and cancellation, one page, by invoice number. */
    @Transactional(readOnly = true)
    public Paged<InvoiceLine> lines(AccessContext context, String yymm, Long unit, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p = staged(context, yymm, unit);
        long total = count("SELECT COUNT(*)" + STAGED, p);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<InvoiceLine> rows =
                named.query(
                        "SELECT i.invoice_staging_id, i.invoice_number, i.payment_type,"
                                + " i.invoice_amount, i.posting_status, i.sent_to_apps_at,"
                                + " i.apps_cancel_status, (SELECT l.location_code || ' - ' ||"
                                + " l.location_name FROM org_location l WHERE l.location_id ="
                                + " i.org_unit_id) unit_label"
                                + STAGED
                                + " ORDER BY i.invoice_number OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        p,
                        (rs, i) ->
                                new InvoiceLine(
                                        rs.getLong("invoice_staging_id"),
                                        rs.getString("invoice_number"),
                                        rs.getString("unit_label"),
                                        rs.getString("payment_type"),
                                        rs.getBigDecimal("invoice_amount"),
                                        rs.getString("posting_status"),
                                        Optional.ofNullable(rs.getTimestamp("sent_to_apps_at"))
                                                .map(Timestamp::toInstant)
                                                .orElse(null),
                                        rs.getString("apps_cancel_status")));
        return new Paged<>(rows, spec.page(), spec.size(), total);
    }

    private MapSqlParameterSource staged(AccessContext context, String yymm, Long unit) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("yymm", yymm)
                .addValue("unit", unit, Types.BIGINT);
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }

    private BigDecimal sum(String sql, MapSqlParameterSource p) {
        BigDecimal value = named.queryForObject(sql, p, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
