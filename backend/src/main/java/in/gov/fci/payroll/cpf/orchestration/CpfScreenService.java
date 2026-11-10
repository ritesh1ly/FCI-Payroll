package in.gov.fci.payroll.cpf.orchestration;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of the CPF / PF &amp; VPF screens as Wireframes v6.1 draws them (OD-121): I-09
 * (SRS-6-27 … 6-30, BR-CPF-021 … 023), I-22 (SRS-6-43, BR-CPF-025 … 027), I-12 (SRS-6-48, SRS-G-37)
 * and I-13 (SRS-6-49, BR-CPF-020, BR-CPF-038). Nothing here writes or computes: a figure no run has
 * stored stays null and the screen says why (Q-072; PENDING 07h-blocked, 07k-blocked). Every list
 * is paged in the database and filtered to the data scope; a record outside it answers as not
 * found.
 */
@Service
@Loggable
public class CpfScreenService {

    private static final String I09 = "I-09";
    private static final String I22 = "I-22";
    private static final String I12 = "I-12";
    private static final String I13 = "I-13";
    private static final String VIEW = "VIEW";
    private static final String SCOPE = "scope";
    private static final String OFFSET = " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";

    /** The I-09 tiles of the cycle {@code yymm}. */
    public record DeputationBoard(
            long members,
            long withAdvance,
            BigDecimal recovered,
            long organisations,
            BigDecimal contributions,
            long pending) {}

    /** One deputed member as the drawn table shows them; null where nothing is remitted yet. */
    public record DeputedRow(
            String eis,
            String name,
            String organisation,
            LocalDate from,
            LocalDate to,
            BigDecimal outstanding,
            BigDecimal instalment,
            BigDecimal balanceAfter,
            BigDecimal contribution,
            String status) {}

    /** The I-22 tiles. {@code interestQueued} stays null until Q-072 gives the method. */
    public record CorrectionBoard(
            long open, long pendingAgm, long pendingHq, BigDecimal interestQueued) {}

    /** One part of a correction request, the row the drawing shows. */
    public record CorrectionLine(
            String ref,
            String eis,
            String name,
            String cpfCode,
            String part,
            BigDecimal current,
            BigDecimal corrected,
            BigDecimal difference,
            String reasonCode,
            String evidence,
            BigDecimal interestEffect,
            String status,
            Long evidenceId,
            String fileName,
            String documentType,
            String uploadedBy,
            Instant uploadedAt) {}

    /** A CPF supplementary payment with its member and invoice (SRS I-12 field table). */
    public record SupplementaryRow(
            String entry,
            String eis,
            String name,
            String designation,
            String category,
            String employeeType,
            String cpfCode,
            String posting,
            String employmentStatus,
            String sanctionNumber,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf,
            BigDecimal sanctioned,
            String orderNumber,
            LocalDate orderDate,
            String paymentStatus,
            String invoiceNumber,
            String invoiceStatus,
            Instant sentToApps,
            String appsCancelStatus) {}

    /** A parent-zone change or zone surrender as received from HRMS (SRS I-13 field table). */
    public record ZoneChangeRow(
            String reference,
            String eis,
            String name,
            String reason,
            String fromZone,
            String toZone,
            String order,
            String oldId,
            String newId,
            LocalDate effective,
            String decision,
            String status,
            String taskNumber) {}

    /** One CPF Member ID of the member (prior IDs are retained with their dates and zone). */
    public record MemberId(
            String id, String zone, LocalDate from, LocalDate to, String status, String reason) {}

    /** What the approval of the change does, as far as it has happened. */
    public record ZoneChangeDetail(
            ZoneChangeRow change,
            BigDecimal carriedEmployee,
            BigDecimal carriedEmployer,
            BigDecimal carriedVpf,
            LocalDate interestTo,
            String reconStatus,
            Instant sspSyncedAt,
            String ecrFrom,
            String iocpfStatus,
            Instant iocpfAcknowledged,
            List<MemberId> ids) {}

    /** An IOCPF not yet acknowledged by APPS (the IOCPF Pending List). */
    public record IocpfRow(
            String number,
            String eis,
            String name,
            String reason,
            BigDecimal total,
            String appsStatus,
            Instant sentAt,
            int retries) {}

    /** The deputed members of the scope: the latest DEPUTATION_OUT of a member not yet back. */
    private static final String DEPUTED =
            " FROM employee_lifecycle_event ev JOIN employee e ON e.employee_id = ev.employee_id"
                    + " WHERE ev.event_type = 'DEPUTATION_OUT' AND e.location_id IN (:scope)"
                    + " AND NOT EXISTS (SELECT 1 FROM employee_lifecycle_event n WHERE"
                    + " n.employee_id = ev.employee_id AND n.event_date > ev.event_date"
                    + " AND n.event_type IN ('DEPUTATION_OUT', 'DEPUTATION_IN'))";

    private static final String ENTRY_IN_SCOPE =
            " FROM cpf_deputation_recovery r JOIN employee e ON e.employee_id = r.employee_id"
                    + " WHERE e.location_id IN (:scope)";

    /** What is left of the member's advances: sanctioned less approved instalments (BR-CPF-021). */
    private static final String OUTSTANDING =
            "(SELECT SUM(s.sanctioned_amount) FROM pf_sanction s WHERE s.employee_id ="
                + " e.employee_id AND s.sanction_type = 'ADVANCE' AND s.workflow_stage IN"
                + " ('SANCTIONED', 'PAID')) - COALESCE((SELECT SUM(i.instalment_remitted) FROM"
                + " cpf_deputation_recovery i WHERE i.employee_id = e.employee_id AND i.entry_type"
                + " = 'RECOVERY_INSTALMENT' AND i.entry_status = 'APPROVED'), 0)";

    private static final String DEPUTED_SELECT =
            "SELECT e.emp_num, e.first_name, e.last_name, ev.event_date, ev.end_date,"
                    + " (SELECT o.org_name FROM borrowing_organisation o WHERE o.borrowing_org_id ="
                    + " COALESCE(rec.borrowing_org_id, con.borrowing_org_id)) org_name,"
                    + " COALESCE(rec.advance_outstanding, "
                    + OUTSTANDING
                    + ") outstanding, rec.instalment_remitted, rec.balance_after,"
                    + " con.cpf_contribution_received, rec.entry_status rec_status,"
                    + " con.entry_status con_status FROM employee_lifecycle_event ev JOIN employee"
                    + " e ON e.employee_id = ev.employee_id LEFT JOIN cpf_deputation_recovery rec"
                    + " ON rec.recovery_id = (SELECT MAX(x.recovery_id) FROM"
                    + " cpf_deputation_recovery x WHERE x.employee_id = e.employee_id AND"
                    + " x.entry_type = 'RECOVERY_INSTALMENT') LEFT JOIN cpf_deputation_recovery con"
                    + " ON con.recovery_id = (SELECT MAX(x.recovery_id) FROM"
                    + " cpf_deputation_recovery x WHERE x.employee_id = e.employee_id AND"
                    + " x.entry_type = 'CONTRIBUTION_RECEIPT') WHERE ev.event_type ="
                    + " 'DEPUTATION_OUT' AND e.location_id IN (:scope) AND NOT EXISTS (SELECT 1"
                    + " FROM employee_lifecycle_event n WHERE n.employee_id = ev.employee_id AND"
                    + " n.event_date > ev.event_date AND n.event_type IN ('DEPUTATION_OUT',"
                    + " 'DEPUTATION_IN')) ORDER BY e.emp_num";

    private static final String CORRECTIONS_IN_SCOPE =
            " FROM opening_balance_correction c JOIN employee e ON e.employee_id = c.employee_id"
                    + " WHERE e.location_id IN (:scope)";

    private static final String CORRECTION_SELECT =
            "SELECT c.correction_ref, e.emp_num, e.first_name, e.last_name, e.cpf_code,"
                + " c.balance_component, c.old_amount, c.new_amount, c.reason_code,"
                + " c.interest_effect_amount, c.correction_status, a.attachment_id,"
                + " a.document_reference_number, a.file_name, a.uploaded_by, a.uploaded_at,"
                + " t.document_type_name FROM opening_balance_correction c JOIN employee e ON"
                + " e.employee_id = c.employee_id LEFT JOIN document_attachment a ON"
                + " a.attachment_id = c.evidence_attachment_id LEFT JOIN document_type_master t ON"
                + " t.document_type_id = a.document_type_id WHERE e.location_id IN (:scope) ORDER"
                + " BY c.raised_at DESC, c.correction_ref, c.balance_component";

    private static final String SUPPLEMENTARY_IN_SCOPE =
            " FROM cpf_supplementary_payment p JOIN employee e ON e.employee_id = p.employee_id"
                    + " WHERE e.location_id IN (:scope)";

    private static final String SUPPLEMENTARY_SELECT =
            "SELECT p.entry_number, e.emp_num, e.first_name, e.last_name, e.employee_type,"
                + " e.cpf_code, e.employment_status, d.designation_name, d.category_code,"
                + " l.location_name, p.system_sanction_number, p.employee_contribution,"
                + " p.employer_contribution, p.vpf_contribution, p.sanctioned_amount,"
                + " p.sanction_order_no, p.sanction_order_date, p.payment_status, i.invoice_number,"
                + " i.posting_status, i.sent_to_apps_at, i.apps_cancel_status FROM"
                + " cpf_supplementary_payment p JOIN employee e ON e.employee_id = p.employee_id"
                + " LEFT JOIN org_designation d ON d.designation_id = e.designation_id LEFT JOIN"
                + " org_location l ON l.location_id = e.location_id LEFT JOIN ap_invoice_staging i"
                + " ON i.invoice_staging_id = p.ap_invoice_id WHERE e.location_id IN (:scope) ORDER"
                + " BY p.created_at DESC, p.entry_number";

    private static final String CHANGES_IN_SCOPE =
            " FROM cpf_parent_zone_change z JOIN employee e ON e.employee_id = z.employee_id"
                    + " WHERE e.location_id IN (:scope)";

    private static final String CHANGE_SELECT =
            "SELECT z.hrms_reference, e.emp_num, e.first_name, e.last_name, z.change_reason,"
                + " f.zone_name from_zone, n.zone_name to_zone, z.hrms_order_reference,"
                + " z.old_cpf_member_id, z.new_cpf_member_id, z.effective_date, z.hrms_decision,"
                + " z.payroll_status, t.task_no FROM cpf_parent_zone_change z JOIN employee e ON"
                + " e.employee_id = z.employee_id JOIN org_zone f ON f.zone_id = z.from_zone_id"
                + " JOIN org_zone n ON n.zone_id = z.to_zone_id LEFT JOIN hrms_change_task t ON"
                + " t.hrms_change_task_id = z.hrms_change_task_id WHERE e.location_id IN (:scope)";

    private static final String IOCPF_IN_SCOPE =
            " FROM iocpf_register i JOIN employee e ON e.employee_id = i.employee_id WHERE"
                    + " e.location_id IN (:scope) AND i.apps_status IN ('PENDING', 'SENT')";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public CpfScreenService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    // ---------------------------------------------------------------- I-09

    /** I-09 tiles: the deputed members of the scope and what was remitted in the cycle. */
    @Transactional(readOnly = true)
    public DeputationBoard deputationBoard(AccessContext context, String yymm) {
        policy.require(context, I09, VIEW);
        if (context.dataScope().isEmpty()) {
            return new DeputationBoard(0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
        }
        MapSqlParameterSource p = scope(context).addValue("yymm", yymm);
        Long members = count(DEPUTED, p);
        Long withAdvance = count(DEPUTED + " AND " + OUTSTANDING + " > 0", p);
        BigDecimal recovered =
                sum(
                        "r.instalment_remitted",
                        " AND r.entry_type = 'RECOVERY_INSTALMENT' AND r.entry_status = 'APPROVED'"
                                + " AND r.payroll_yymm = :yymm",
                        p);
        BigDecimal contributions =
                sum(
                        "r.cpf_contribution_received",
                        " AND r.entry_type = 'CONTRIBUTION_RECEIPT' AND r.entry_status ="
                                + " 'APPROVED' AND r.payroll_yymm = :yymm",
                        p);
        Long organisations =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT r.borrowing_org_id)"
                                + ENTRY_IN_SCOPE
                                + " AND r.payroll_yymm = :yymm",
                        p,
                        Long.class);
        Long pending =
                count(
                        ENTRY_IN_SCOPE + " AND r.entry_status IN ('MAKER_SAVED','PENDING_CHECKER')",
                        p);
        // COUNT always answers a row; requireNonNullElse states it for the unboxing (SpotBugs NP).
        return new DeputationBoard(
                members,
                withAdvance,
                recovered,
                java.util.Objects.requireNonNullElse(organisations, 0L),
                contributions,
                pending);
    }

    /** I-09 table: one row per deputed member, their latest instalment and contribution. */
    @Transactional(readOnly = true)
    public Paged<DeputedRow> deputedMembers(AccessContext context, PageSpec spec) {
        policy.require(context, I09, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource p = page(context, spec);
        Long total = count(DEPUTED, p);
        List<DeputedRow> rows =
                named.query(DEPUTED_SELECT + OFFSET, p, CpfScreenService::deputedRow);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /** Pending while either entry waits; else the latest instalment's, else "awaiting". */
    static String deputationStatus(String instalment, String contribution) {
        if ("PENDING_CHECKER".equals(instalment) || "PENDING_CHECKER".equals(contribution)) {
            return "PENDING_CHECKER";
        }
        if (instalment != null) {
            return instalment;
        }
        return contribution == null ? "AWAITING_ADVICE" : contribution;
    }

    // ---------------------------------------------------------------- I-22

    /** I-22 tiles: the open requests by the tier they wait at (BR-APPR-008). */
    @Transactional(readOnly = true)
    public CorrectionBoard correctionBoard(AccessContext context) {
        policy.require(context, I22, VIEW);
        if (context.dataScope().isEmpty()) {
            return new CorrectionBoard(0, 0, 0, null);
        }
        MapSqlParameterSource p = scope(context);
        String distinct = "SELECT COUNT(DISTINCT c.correction_ref)" + CORRECTIONS_IN_SCOPE;
        Long open =
                named.queryForObject(
                        distinct
                                + " AND c.correction_status NOT IN ('APPLIED', 'REJECTED',"
                                + " 'CANCELLED')",
                        p,
                        Long.class);
        Long agm =
                named.queryForObject(
                        distinct + " AND c.correction_status = 'PENDING_AGM_CPF'", p, Long.class);
        Long hq =
                named.queryForObject(
                        distinct + " AND c.correction_status = 'PENDING_HQ_CPF'", p, Long.class);
        BigDecimal queued =
                named.queryForObject(
                        "SELECT SUM(c.interest_effect_amount)"
                                + CORRECTIONS_IN_SCOPE
                                + " AND c.correction_status = 'APPROVED'",
                        p,
                        BigDecimal.class);
        return new CorrectionBoard(
                java.util.Objects.requireNonNullElse(open, 0L),
                java.util.Objects.requireNonNullElse(agm, 0L),
                java.util.Objects.requireNonNullElse(hq, 0L),
                queued);
    }

    /** I-22 table: one row per corrected part with the member and its evidence. */
    @Transactional(readOnly = true)
    public Paged<CorrectionLine> correctionLines(AccessContext context, PageSpec spec) {
        policy.require(context, I22, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource p = page(context, spec);
        Long total = count(CORRECTIONS_IN_SCOPE, p);
        List<CorrectionLine> rows =
                named.query(CORRECTION_SELECT + OFFSET, p, CpfScreenService::correctionLine);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    // ---------------------------------------------------------------- I-12

    /** I-12: the supplementary payments of the scope, newest first, with invoice status. */
    @Transactional(readOnly = true)
    public Paged<SupplementaryRow> supplementaryPayments(AccessContext context, PageSpec spec) {
        policy.require(context, I12, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource p = page(context, spec);
        Long total = count(SUPPLEMENTARY_IN_SCOPE, p);
        List<SupplementaryRow> rows =
                named.query(SUPPLEMENTARY_SELECT + OFFSET, p, CpfScreenService::supplementaryRow);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    // ---------------------------------------------------------------- I-13

    /** I-13: the changes received from HRMS for members of the scope, latest first. */
    @Transactional(readOnly = true)
    public Paged<ZoneChangeRow> zoneChanges(AccessContext context, PageSpec spec) {
        policy.require(context, I13, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource p = page(context, spec);
        Long total = count(CHANGES_IN_SCOPE, p);
        List<ZoneChangeRow> rows =
                named.query(
                        CHANGE_SELECT + " ORDER BY z.hrms_reference DESC" + OFFSET,
                        p,
                        CpfScreenService::zoneChangeRow);
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    /**
     * I-13 detail: the change, what its approval carried and the member's CPF IDs. Outside the data
     * scope it answers as not found (no enumeration of members).
     */
    @Transactional(readOnly = true)
    public ZoneChangeDetail zoneChange(AccessContext context, String reference) {
        policy.require(context, I13, VIEW);
        if (context.dataScope().isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        MapSqlParameterSource p = scope(context).addValue("reference", reference);
        List<ZoneChangeRow> found =
                named.query(
                        CHANGE_SELECT + " AND z.hrms_reference = :reference",
                        p,
                        CpfScreenService::zoneChangeRow);
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        ZoneChangeRow change = found.getFirst();
        List<MemberId> ids =
                named.query(
                        "SELECT m.identifier_value, o.zone_name, m.effective_from, m.effective_to,"
                                + " m.id_status, m.supersede_reason FROM cpf_member_id_history m"
                                + " JOIN cpf_parent_zone_change z ON z.employee_id = m.employee_id"
                                + " LEFT JOIN org_zone o ON o.zone_id = m.zone_id WHERE"
                                + " z.hrms_reference = :reference AND m.id_type = 'CPF' ORDER BY"
                                + " m.effective_from",
                        p,
                        (rs, n) ->
                                new MemberId(
                                        rs.getString(1),
                                        rs.getString(2),
                                        localDate(rs.getDate(3)),
                                        localDate(rs.getDate(4)),
                                        rs.getString(5),
                                        rs.getString(6)));
        return named.queryForObject(
                "SELECT z.carried_employee, z.carried_employer, z.carried_vpf, z.interest_to_date,"
                        + " z.recon_status, z.ssp_synced_at, z.ecr_effective_yymm, (SELECT"
                        + " MAX(i.apps_status) FROM iocpf_register i WHERE i.pzc_id = z.pzc_id)"
                        + " iocpf_status, (SELECT MAX(i.apps_ack_at) FROM iocpf_register i WHERE"
                        + " i.pzc_id = z.pzc_id) iocpf_ack FROM cpf_parent_zone_change z WHERE"
                        + " z.hrms_reference = :reference",
                p,
                (rs, n) ->
                        new ZoneChangeDetail(
                                change,
                                rs.getBigDecimal(1),
                                rs.getBigDecimal(2),
                                rs.getBigDecimal(3),
                                localDate(rs.getDate(4)),
                                rs.getString(5),
                                instant(rs.getTimestamp(6)),
                                rs.getString(7),
                                rs.getString(8),
                                instant(rs.getTimestamp(9)),
                                ids));
    }

    /** The IOCPF Pending List: an IOCPF APPS has not acknowledged stays here (SRS-6-52). */
    @Transactional(readOnly = true)
    public Paged<IocpfRow> iocpfPending(AccessContext context, PageSpec spec) {
        policy.require(context, I13, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        MapSqlParameterSource p = page(context, spec);
        Long total = count(IOCPF_IN_SCOPE, p);
        List<IocpfRow> rows =
                named.query(
                        "SELECT i.iocpf_number, e.emp_num, e.first_name, e.last_name,"
                                + " i.iocpf_reason, i.total_amount, i.apps_status,"
                                + " i.sent_to_apps_at, i.retry_count"
                                + IOCPF_IN_SCOPE
                                + " ORDER BY i.created_at"
                                + OFFSET,
                        p,
                        (rs, n) ->
                                new IocpfRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        name(rs.getString(3), rs.getString(4)),
                                        rs.getString(5),
                                        rs.getBigDecimal(6),
                                        rs.getString(7),
                                        instant(rs.getTimestamp(8)),
                                        rs.getInt(9)));
        return new Paged<>(rows, spec.page(), spec.size(), RowCounts.of(total));
    }

    // ---------------------------------------------------------------- helpers

    private static MapSqlParameterSource scope(AccessContext context) {
        return new MapSqlParameterSource().addValue(SCOPE, context.dataScope());
    }

    private static MapSqlParameterSource page(AccessContext context, PageSpec spec) {
        return scope(context)
                .addValue("offset", (long) spec.page() * spec.size())
                .addValue("size", spec.size());
    }

    private Long count(String from, MapSqlParameterSource p) {
        return named.queryForObject("SELECT COUNT(*)" + from, p, Long.class);
    }

    private BigDecimal sum(String column, String where, MapSqlParameterSource p) {
        BigDecimal total =
                named.queryForObject(
                        "SELECT SUM(" + column + ")" + ENTRY_IN_SCOPE + where, p, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    private static DeputedRow deputedRow(ResultSet rs, int row) throws SQLException {
        return new DeputedRow(
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("org_name"),
                localDate(rs.getDate("event_date")),
                localDate(rs.getDate("end_date")),
                rs.getBigDecimal("outstanding"),
                rs.getBigDecimal("instalment_remitted"),
                rs.getBigDecimal("balance_after"),
                rs.getBigDecimal("cpf_contribution_received"),
                deputationStatus(rs.getString("rec_status"), rs.getString("con_status")));
    }

    private static CorrectionLine correctionLine(ResultSet rs, int row) throws SQLException {
        BigDecimal current = rs.getBigDecimal("old_amount");
        BigDecimal corrected = rs.getBigDecimal("new_amount");
        return new CorrectionLine(
                rs.getString("correction_ref"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("cpf_code"),
                rs.getString("balance_component"),
                current,
                corrected,
                corrected.subtract(current),
                rs.getString("reason_code"),
                rs.getString("document_reference_number"),
                rs.getBigDecimal("interest_effect_amount"),
                rs.getString("correction_status"),
                rs.getObject("attachment_id") == null ? null : rs.getLong("attachment_id"),
                rs.getString("file_name"),
                rs.getString("document_type_name"),
                rs.getString("uploaded_by"),
                instant(rs.getTimestamp("uploaded_at")));
    }

    private static SupplementaryRow supplementaryRow(ResultSet rs, int row) throws SQLException {
        return new SupplementaryRow(
                rs.getString("entry_number"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("designation_name"),
                rs.getString("category_code"),
                rs.getString("employee_type"),
                rs.getString("cpf_code"),
                rs.getString("location_name"),
                rs.getString("employment_status"),
                rs.getString("system_sanction_number"),
                rs.getBigDecimal("employee_contribution"),
                rs.getBigDecimal("employer_contribution"),
                rs.getBigDecimal("vpf_contribution"),
                rs.getBigDecimal("sanctioned_amount"),
                rs.getString("sanction_order_no"),
                localDate(rs.getDate("sanction_order_date")),
                rs.getString("payment_status"),
                rs.getString("invoice_number"),
                rs.getString("posting_status"),
                instant(rs.getTimestamp("sent_to_apps_at")),
                rs.getString("apps_cancel_status"));
    }

    private static ZoneChangeRow zoneChangeRow(ResultSet rs, int row) throws SQLException {
        return new ZoneChangeRow(
                rs.getString("hrms_reference"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                rs.getString("change_reason"),
                rs.getString("from_zone"),
                rs.getString("to_zone"),
                rs.getString("hrms_order_reference"),
                rs.getString("old_cpf_member_id"),
                rs.getString("new_cpf_member_id"),
                localDate(rs.getDate("effective_date")),
                rs.getString("hrms_decision"),
                rs.getString("payroll_status"),
                rs.getString("task_no"));
    }

    /** A person as the drawn tables print them: "R. Sharma". */
    static String name(String first, String last) {
        String family = last == null ? "" : last.strip();
        if (first == null || first.isBlank()) {
            return family;
        }
        return family.isEmpty() ? first.strip() : first.strip().charAt(0) + ". " + family;
    }

    private static LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
