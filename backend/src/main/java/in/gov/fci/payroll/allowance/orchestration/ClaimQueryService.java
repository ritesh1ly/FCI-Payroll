package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of I-03 (plan 10 phase 10e; SRS I-03 field table, SRS-11-19, SRS-11-21, SRS-11-23):
 * the Claim Register paged on the server with its filters, one claim with its documents and the
 * Checker's reason, the employee claims waiting on I-21, the Medical Claim view month by month, and
 * the Claim and Medical Registers for download. Scoped to the user's offices ({@link ClaimScope}).
 */
@Service
@Loggable
public class ClaimQueryService {

    private static final Pattern YYMM = Pattern.compile("^\\d{4}(0[1-9]|1[0-2])$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");

    /** One claim as the register shows it (no PAN, bank or Aadhaar: SRS-SEC-4). */
    public record ClaimRow(
            long id,
            String eis,
            String name,
            String memberType,
            String claimType,
            String claimTypeLabel,
            LocalDate periodFrom,
            LocalDate periodTo,
            BigDecimal claimed,
            BigDecimal entitlement,
            BigDecimal payable,
            boolean capped,
            String status,
            String source,
            String hrmsRequestId,
            Boolean taxable,
            Instant taxableConfirmedAt,
            String invoiceStatus,
            String iogaNumber,
            LocalDate iogaDate,
            BigDecimal iogaAmount,
            Long approvalId,
            String decisionReason,
            String taxTreatment,
            BigDecimal taxableAmount,
            // SRS l.2805 (SRS-G-37): when the invoice went to APPS; null until it is sent.
            Instant invoiceSentAt) {}

    /** A claim with its documents. */
    public record ClaimDetail(ClaimRow claim, List<DocumentStore.AttachmentView> documents) {}

    /** An employee claim received from HRMS and waiting on its I-21 task. */
    public record PendingHrmsClaim(
            long taskId, String taskNo, String eis, String name, String value, String status) {}

    /**
     * The register's filters (null = every).
     *
     * @param yymm the month of the drawn "Claim Register - MON YYYY" (YYYYMM): claims not yet
     *     settled, which the next open cycle takes (SRS-11-12), and those whose period overlaps it
     */
    public record Filter(
            String memberType, String status, String claimType, String eis, String yymm) {

        public Filter(String memberType, String status, String claimType, String eis) {
            this(memberType, status, claimType, eis, null);
        }
    }

    private static final String FROM =
            " FROM reimbursement_claim c JOIN employee e ON e.employee_id = c.employee_id LEFT JOIN"
                + " reference_value t ON t.value_code = c.claim_type AND t.list_id = (SELECT"
                + " l.list_id FROM reference_list l WHERE l.list_code = 'CLAIM_TYPE') LEFT JOIN"
                + " approval_queue a ON a.approval_id = c.approval_id LEFT JOIN pay_component pc ON"
                + " pc.legacy_pay_code = t.numeric_attribute WHERE (:all = 'Y' OR e.location_id IN"
                + " (:locations)) AND (:memberType IS NULL OR c.member_type = :memberType) AND"
                + " (:status IS NULL OR c.claim_status = :status) AND (:claimType IS NULL OR"
                + " c.claim_type = :claimType) AND (:eis IS NULL OR e.emp_num = :eis) AND (:month ="
                + " 'N' OR c.claim_status IN ('SUBMITTED', 'VALIDATED', 'CAPPED', 'APPROVED') OR"
                + " (c.period_to >= :monthFirst AND c.period_from <= :monthLast))";

    private static final String COLUMNS =
            "SELECT c.claim_id, e.emp_num, e.first_name, e.last_name, c.member_type, c.claim_type,"
                    + " t.value_label_en, c.period_from, c.period_to, c.claimed_amount,"
                    + " c.entitlement_amount, c.payable_amount, c.was_capped, c.claim_status,"
                    + " c.source_code, c.hrms_request_id, c.taxable_flag, c.taxable_confirmed_at,"
                    + " c.invoice_status, c.ioga_number, c.ioga_date, c.ioga_amount, c.approval_id,"
                    + " a.decision_reason, pc.taxable_flag, c.invoice_sent_at";

    private final NamedParameterJdbcTemplate named;
    private final ClaimScope scope;
    private final DocumentStore documents;

    public ClaimQueryService(
            NamedParameterJdbcTemplate named, ClaimScope scope, DocumentStore documents) {
        this.named = named;
        this.scope = scope;
        this.documents = documents;
    }

    /** SRS-11-23: the Claim Register, newest first, paged on the server. */
    @Transactional(readOnly = true)
    public Paged<ClaimRow> register(AccessContext context, Filter f, PageSpec spec) {
        MapSqlParameterSource p = params(context, f);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<ClaimRow> rows =
                named.query(
                        COLUMNS
                                + FROM
                                + " ORDER BY c.claim_id DESC OFFSET :offset ROWS FETCH NEXT :size"
                                + " ROWS ONLY",
                        p,
                        (rs, i) -> row(rs));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** One claim with its supporting documents; outside the scope it is not found. */
    @Transactional(readOnly = true)
    public ClaimDetail detail(AccessContext context, long id) {
        ClaimRow row = one(context, id);
        return new ClaimDetail(
                row, documents.active(LabourClaimService.ENTITY, String.valueOf(id)));
    }

    /** A document of a claim in scope; logs the access (OC-7). */
    @Transactional
    public DocumentStore.Content document(
            AccessContext context, String sourceIp, long id, long attachmentId) {
        one(context, id);
        return documents.read(
                context.loginId(),
                sourceIp,
                LabourClaimService.ENTITY,
                String.valueOf(id),
                attachmentId);
    }

    /** The claim in scope, or not found. */
    @Transactional(readOnly = true)
    public ClaimRow one(AccessContext context, long id) {
        MapSqlParameterSource p = params(context, new Filter(null, null, null, null));
        p.addValue("id", id);
        return named.query(COLUMNS + FROM + " AND c.claim_id = :id", p, (rs, i) -> row(rs)).stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** SRS-11-1: the employee claims received from HRMS whose I-21 task is still open. */
    @Transactional(readOnly = true)
    public Paged<PendingHrmsClaim> pendingFromHrms(AccessContext context, PageSpec spec) {
        MapSqlParameterSource p = params(context, new Filter(null, null, null, null));
        String from =
                " FROM hrms_change_task k JOIN employee e ON e.employee_id = k.employee_id JOIN"
                    + " hrms_pay_effect_field f ON f.pay_effect_field_id = k.pay_effect_field_id"
                    + " WHERE f.field_code = 'REIMBURSEMENT_CLAIM' AND k.task_status IN"
                    + " ('PENDING_ORDER_DETAILS', 'PENDING_CHECKER', 'PENDING_HQ_ESTABLISHMENT',"
                    + " 'RETURNED_TO_MAKER') AND (:all = 'Y' OR e.location_id IN (:locations))";
        Long total = named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
        p.addValue("offset", (long) spec.page() * spec.size()).addValue("size", spec.size());
        List<PendingHrmsClaim> rows =
                named.query(
                        "SELECT k.hrms_change_task_id, k.task_no, e.emp_num, e.first_name,"
                                + " e.last_name, k.new_value, k.task_status"
                                + from
                                + " ORDER BY k.hrms_change_task_id DESC OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) ->
                                new PendingHrmsClaim(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        name(rs.getString(4), rs.getString(5)),
                                        rs.getString(6),
                                        rs.getString(7)));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * SRS-11-19: the Medical Claim view of one employee, month by month (by the claim's start),
     * newest first.
     */
    @Transactional(readOnly = true)
    public List<ClaimRow> medical(AccessContext context, String eis) {
        if (eis == null || !eis.matches("^[A-Za-z0-9]{1,20}$")) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        MapSqlParameterSource p = params(context, new Filter("EMPLOYEE", null, null, eis));
        return named.query(
                COLUMNS + FROM + " AND c.claim_type LIKE 'MEDICAL%' ORDER BY c.period_from DESC",
                p,
                (rs, i) -> row(rs));
    }

    /** SRS-11-23: the Claim Register's size for the download (as the screen filters it). */
    @Transactional(readOnly = true)
    public long registerSize(AccessContext context, Filter f) {
        Long n = named.queryForObject("SELECT COUNT(*)" + FROM, params(context, f), Long.class);
        return RowCounts.of(n);
    }

    /** SRS-11-23: the Claim Register: claimed, entitlement, payable, source and status. */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void register(AccessContext context, Filter f, ExportSink sink) throws IOException {
        MapSqlParameterSource p = params(context, f);
        Long expected = named.queryForObject("SELECT COUNT(*)" + FROM, p, Long.class);
        sink.header(
                "claim_id",
                "eis",
                "member",
                "member_type",
                "claim_type",
                "period_from",
                "period_to",
                "claimed",
                "entitlement",
                "payable",
                "capped",
                "source",
                "hrms_request_id",
                "status",
                "invoice_status");
        stream(
                COLUMNS + FROM + " ORDER BY c.claim_id",
                p,
                sink,
                r ->
                        new Object[] {
                            r.id(),
                            r.eis(),
                            r.name(),
                            r.memberType(),
                            r.claimType(),
                            r.periodFrom(),
                            r.periodTo(),
                            r.claimed(),
                            r.entitlement(),
                            r.payable(),
                            r.capped() ? "Y" : "N",
                            r.source(),
                            r.hrmsRequestId(),
                            r.status(),
                            r.invoiceStatus()
                        });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    /** SRS-11-23 / SRS-11-21: the Medical Register of a month (claims starting in it). */
    @Transactional(readOnly = true)
    public long medicalRegisterSize(AccessContext context, String yymm) {
        MapSqlParameterSource p = monthParams(context, yymm);
        Long n = named.queryForObject("SELECT COUNT(*)" + FROM + MEDICAL_MONTH, p, Long.class);
        return RowCounts.of(n);
    }

    private static final String MEDICAL_MONTH =
            " AND c.claim_type LIKE 'MEDICAL%' AND c.period_from BETWEEN :first AND :last";

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void medicalRegister(AccessContext context, String yymm, ExportSink sink)
            throws IOException {
        MapSqlParameterSource p = monthParams(context, yymm);
        Long expected =
                named.queryForObject("SELECT COUNT(*)" + FROM + MEDICAL_MONTH, p, Long.class);
        sink.header(
                "claim_id",
                "eis",
                "member",
                "claim_type",
                "scheme_type",
                "period_from",
                "period_to",
                "claimed",
                "payable",
                "taxable",
                "hrms_request_id",
                "ioga_number",
                "ioga_date",
                "ioga_amount",
                "status");
        stream(
                COLUMNS + FROM + MEDICAL_MONTH + " ORDER BY e.emp_num, c.claim_id",
                p,
                sink,
                r ->
                        new Object[] {
                            r.id(),
                            r.eis(),
                            r.name(),
                            r.claimType(),
                            "",
                            r.periodFrom(),
                            r.periodTo(),
                            r.claimed(),
                            r.payable(),
                            r.taxable() == null ? "" : r.taxable() ? "Y" : "N",
                            r.hrmsRequestId(),
                            r.iogaNumber(),
                            r.iogaDate(),
                            r.iogaAmount(),
                            r.status()
                        });
        ExportSink.requireComplete(RowCounts.of(expected), sink);
    }

    private interface Cells {
        Object[] of(ClaimRow r);
    }

    private void stream(String sql, MapSqlParameterSource p, ExportSink sink, Cells cells)
            throws IOException {
        try {
            named.query(
                    sql,
                    p,
                    rs -> {
                        try {
                            sink.row(cells.of(row(rs)));
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private MapSqlParameterSource monthParams(AccessContext context, String yymm) {
        if (yymm == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        YearMonth month = month(yymm);
        return params(context, new Filter("EMPLOYEE", null, null, null))
                .addValue("first", Date.valueOf(month.atDay(1)))
                .addValue("last", Date.valueOf(month.atEndOfMonth()));
    }

    private MapSqlParameterSource params(AccessContext context, Filter f) {
        if (f.claimType() != null && !CODE.matcher(f.claimType()).matches()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Optional<Set<Long>> offices = scope.offices(context, LabourClaimService.SCREEN, "VIEW");
        YearMonth month = f.yymm() == null ? null : month(f.yymm());
        return new MapSqlParameterSource()
                .addValue("all", offices.isEmpty() ? "Y" : "N", Types.VARCHAR)
                .addValue("locations", offices.orElse(Set.of(-1L)))
                .addValue("memberType", f.memberType(), Types.VARCHAR)
                .addValue("status", f.status(), Types.VARCHAR)
                .addValue("claimType", f.claimType(), Types.VARCHAR)
                .addValue("eis", f.eis(), Types.VARCHAR)
                .addValue("month", month == null ? "N" : "Y", Types.VARCHAR)
                .addValue(
                        "monthFirst",
                        month == null ? null : Date.valueOf(month.atDay(1)),
                        Types.DATE)
                .addValue(
                        "monthLast",
                        month == null ? null : Date.valueOf(month.atEndOfMonth()),
                        Types.DATE);
    }

    private static YearMonth month(String yymm) {
        if (!YYMM.matcher(yymm).matches()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return YearMonth.of(
                Integer.parseInt(yymm.substring(0, 4)), Integer.parseInt(yymm.substring(4)));
    }

    private static ClaimRow row(ResultSet rs) throws SQLException {
        Date to = rs.getDate(9);
        Timestamp confirmed = rs.getTimestamp(18);
        Timestamp sent = rs.getTimestamp(26);
        Date iogaDate = rs.getDate(21);
        String taxable = rs.getString(17);
        long approval = rs.getLong(23);
        Long approvalId = rs.wasNull() ? null : approval;
        String status = rs.getString(14);
        String source = rs.getString(15);
        Boolean confirmedTaxable = taxable == null ? null : "Y".equals(taxable);
        ClaimRules.TaxImpact tax =
                ClaimRules.taxImpact(rs.getBigDecimal(12), rs.getString(25), confirmedTaxable);
        return new ClaimRow(
                rs.getLong(1),
                rs.getString(2),
                name(rs.getString(3), rs.getString(4)),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getDate(8).toLocalDate(),
                to.toLocalDate(),
                rs.getBigDecimal(10),
                rs.getBigDecimal(11),
                rs.getBigDecimal(12),
                "Y".equals(rs.getString(13)),
                status,
                "ENT".equals(source) ? "LDH" : "ESS".equals(source) ? "ESEVA" : "HRMS",
                rs.getString(16),
                confirmedTaxable,
                confirmed == null ? null : confirmed.toInstant(),
                rs.getString(19),
                rs.getString(20),
                iogaDate == null ? null : iogaDate.toLocalDate(),
                rs.getBigDecimal(22),
                approvalId,
                "REJECTED".equals(status) || "SUBMITTED".equals(status) ? rs.getString(24) : null,
                tax.treatment(),
                tax.taxableAmount(),
                sent == null ? null : sent.toInstant());
    }

    private static String name(String first, String last) {
        return last == null ? first : first + " " + last;
    }
}
