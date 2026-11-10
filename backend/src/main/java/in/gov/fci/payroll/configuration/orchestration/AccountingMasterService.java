package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-24 Accounting &amp; Bank Mapping as the SRS table and Wireframes v6.1 list it (SRS-G-7,
 * SRS-22-7, OD-121): the seven mapping masters with their effective date and records, and a
 * read-only page of the records of each. A pay-code to natural-account change stays a dated version
 * in {@link GlMappingService}. The other six are read here only: the agency on a pay code is kept
 * on M-07 / M-16 (BR-MIS-006), the payroll types on M-07, and the cost centres, statuses, bank
 * branches and locations are received or Finance-owned (FRS §6.7.3, §6.7.4); their maintenance has
 * no specified API (PENDING API-M24-1).
 */
@Service
@Loggable
public class AccountingMasterService {

    static final String SCREEN = "M-24";

    /** The seven masters, in the drawn order; the SQL is fixed per master, never from a request. */
    public enum Master {
        NATURAL_ACCOUNT(
                "SELECT COUNT(*) n, MAX(m.effective_from) f FROM gl_account_mapping m"
                        + " JOIN approval_queue a ON a.approval_id = m.approval_id"
                        + " WHERE a.status = 'APPROVED' AND m.effective_to IS NULL",
                "SELECT TO_CHAR(c.legacy_pay_code) k, c.component_name n, m.natural_account d,"
                        + " m.effective_from f, m.effective_to t FROM gl_account_mapping m"
                        + " JOIN pay_component c ON c.component_id = m.component_id"
                        + " JOIN approval_queue a ON a.approval_id = m.approval_id"
                        + " WHERE a.status = 'APPROVED' AND m.effective_to IS NULL"
                        + " ORDER BY c.legacy_pay_code"),
        AGENCY(
                "SELECT COUNT(*) n, MAX(c.effective_from) f FROM pay_component c"
                        + " WHERE c.agency_id IS NOT NULL AND c.is_retired = 'N'",
                "SELECT TO_CHAR(c.legacy_pay_code) k, c.component_name n,"
                        + " a.agency_code || ' ' || a.agency_name d, c.effective_from f,"
                        + " c.effective_to t FROM pay_component c"
                        + " JOIN agency_master a ON a.agency_id = c.agency_id"
                        + " WHERE c.is_retired = 'N' ORDER BY c.legacy_pay_code"),
        CLASSIFICATION(
                "SELECT COUNT(DISTINCT c.component_id) n, MAX(c.effective_from) f"
                        + " FROM pay_component_engine e"
                        + " JOIN pay_component c ON c.component_id = e.component_id"
                        + " WHERE c.is_retired = 'N'",
                "SELECT TO_CHAR(c.legacy_pay_code) k, c.component_name n,"
                        + " LISTAGG(e.payroll_type, ', ') WITHIN GROUP (ORDER BY e.payroll_type) d,"
                        + " c.effective_from f, c.effective_to t FROM pay_component_engine e"
                        + " JOIN pay_component c ON c.component_id = e.component_id"
                        + " WHERE c.is_retired = 'N' GROUP BY c.legacy_pay_code, c.component_name,"
                        + " c.effective_from, c.effective_to ORDER BY c.legacy_pay_code"),
        COST_CENTRE(
                "SELECT COUNT(*) n, MAX(c.effective_from) f FROM cost_centre c"
                        + " WHERE c.effective_to IS NULL",
                "SELECT c.cost_centre_code k, c.cost_centre_name n,"
                        + " c.responsibility_code || ' ' || c.owning_system d, c.effective_from f,"
                        + " c.effective_to t FROM cost_centre c WHERE c.effective_to IS NULL"
                        + " ORDER BY c.cost_centre_code"),
        BUDGET_BILL_STATUS(
                "SELECT COUNT(*) n, MAX(s.effective_from) f FROM budget_bill_status s"
                        + " WHERE s.effective_to IS NULL",
                "SELECT s.status_code k, s.status_name n,"
                        + " s.status_domain || ' ' || s.owning_system d, s.effective_from f,"
                        + " s.effective_to t FROM budget_bill_status s"
                        + " WHERE s.effective_to IS NULL ORDER BY s.status_domain, s.status_code"),
        BANK(
                "SELECT COUNT(*) n, CAST(NULL AS DATE) f FROM bank_branch b"
                        + " WHERE b.active_flag = 'Y'",
                "SELECT b.ifsc_code k, b.branch_name n, m.bank_name d, CAST(NULL AS DATE) f,"
                        + " CAST(NULL AS DATE) t FROM bank_branch b"
                        + " JOIN bank_master m ON m.bank_id = b.bank_id"
                        + " WHERE b.active_flag = 'Y' ORDER BY b.ifsc_code"),
        CITY_TYPE(
                "SELECT COUNT(*) n, MAX(c.effective_from) f FROM city_type_location_map c"
                        + " WHERE c.effective_to IS NULL",
                "SELECT l.location_code k, l.location_name n,"
                        + " c.city_class || ' ' || COALESCE(c.remote_area_type, '-') d,"
                        + " c.effective_from f, c.effective_to t FROM city_type_location_map c"
                        + " JOIN org_location l ON l.location_id = c.location_id"
                        + " WHERE c.effective_to IS NULL ORDER BY l.location_code");

        private final String summary;
        private final String records;

        Master(String summary, String records) {
            this.summary = summary;
            this.records = records;
        }
    }

    /** One drawn row: the master, its latest effective date, its records, a waiting proposal. */
    public record MasterRow(String code, LocalDate effectiveFrom, long records, boolean pending) {}

    /** One record of a master: its key, name, what it maps to and its dates. */
    public record RecordRow(
            String key,
            String name,
            String detail,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    private static final String PENDING_MAPPINGS =
            "SELECT COUNT(*) FROM gl_account_mapping m"
                    + " JOIN approval_queue a ON a.approval_id = m.approval_id"
                    + " WHERE a.status = 'PENDING_APPROVAL'";

    private final JdbcTemplate jdbc;
    private final Authorization policy;

    public AccountingMasterService(JdbcTemplate jdbc, Authorization policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** The seven masters in the drawn order. */
    @Transactional(readOnly = true)
    public List<MasterRow> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<MasterRow> rows = new ArrayList<>();
        for (Master master : Master.values()) {
            boolean pending = master == Master.NATURAL_ACCOUNT && count(PENDING_MAPPINGS) > 0;
            rows.add(
                    jdbc.queryForObject(
                            master.summary,
                            (rs, n) ->
                                    new MasterRow(
                                            master.name(),
                                            day(rs, "f"),
                                            rs.getLong("n"),
                                            pending)));
        }
        return rows;
    }

    /** One page of the records of a master, read only. */
    @Transactional(readOnly = true)
    public Paged<RecordRow> records(AccessContext context, String code, int page, int size) {
        policy.require(context, SCREEN, "VIEW");
        Master master =
                Arrays.stream(Master.values())
                        .filter(m -> m.name().equals(code))
                        .findFirst()
                        .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
        long total = count(master.summary);
        List<RecordRow> rows =
                jdbc.query(
                        master.records + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                        (rs, n) ->
                                new RecordRow(
                                        rs.getString("k"),
                                        rs.getString("n"),
                                        rs.getString("d"),
                                        day(rs, "f"),
                                        day(rs, "t")),
                        (long) page * size,
                        size);
        return new Paged<>(rows, page, size, total);
    }

    /** The first column of a one-row count. */
    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, (rs, n) -> rs.getLong(1));
        return value == null ? 0 : value;
    }

    private static LocalDate day(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
