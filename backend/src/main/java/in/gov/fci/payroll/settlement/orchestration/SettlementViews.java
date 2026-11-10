package in.gov.fci.payroll.settlement.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementRules.Stage;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The O-05 reads (plan 11; SRS-10-8, SRS-10-11, SRS-10-12, SRS-10-3; SRS O-05 field table): the
 * cases of the session's offices (every office at Headquarters), newest first, each with its stage
 * and the clearance it awaits; how many cases await each clearance; the four drawn tiles (O-05
 * summary, SRS-10-6, SRS-10-11, SRS-10-22); the settlement payments with their invoice (SRS-G-37,
 * SRS-10-31); and one case with its clearances, amounts, itemised recoveries, ex-gratia entries and
 * the CPF final payment for information.
 */
@Service
public class SettlementViews {

    /**
     * One case of the O-05 list.
     *
     * @param payrollType the member's payroll (Staff, or the labour payroll of labour_profile)
     * @param payrollDues pending salary + leave encashment + arrears + ex-gratia (SRS-10-13), null
     *     before the computation
     * @param recoveredFromGratuity the part of {@code totalRecoveries} taken from gratuity
     * @param awaitingChecker the computed settlement waits for the Payroll Checker's decision
     * @param hqApprovalPending a recovery against gratuity waits for Headquarters (SRS-10-22)
     */
    public record CaseRow(
            long caseId,
            String caseReference,
            String eis,
            String name,
            String office,
            String separationType,
            LocalDate separationDate,
            String stage,
            List<String> awaiting,
            String status,
            BigDecimal netPayable,
            String payrollType,
            BigDecimal payrollDues,
            BigDecimal totalRecoveries,
            BigDecimal recoveredFromGratuity,
            boolean awaitingChecker,
            boolean hqApprovalPending) {}

    /** Separations of one type in the period. */
    public record TypeCount(String type, long cases) {}

    /**
     * The four O-05 tiles of the session's offices.
     *
     * @param separations cases opened on a separation this calendar month (the case opens on the
     *     separation event, SRS-10-19; the sources do not define the tile's period: PENDING
     *     O05-PERIOD)
     * @param settled those posted or closed
     * @param settledAmount their payroll net payable
     * @param byType those opened, by separation type
     * @param awaitingClearance open cases with a clearance outstanding (SRS-10-11)
     * @param hqApproval recoveries against gratuity awaiting Headquarters (SRS-10-22)
     * @param netRecovery open cases with a negative net payable (SRS-10-6)
     */
    public record Summary(
            long separations,
            long settled,
            BigDecimal settledAmount,
            List<TypeCount> byType,
            long awaitingClearance,
            long hqApproval,
            long netRecovery) {}

    /**
     * One settlement payment (SRS-G-37, SRS-10-31; DDL final_settlement invoice columns): the net
     * settlement, or the credit memo of a net recovery (BR-FNF-016).
     */
    public record InvoiceRow(
            long caseId,
            String caseReference,
            String eis,
            String name,
            boolean creditMemo,
            BigDecimal amount,
            String invoiceNo,
            String invoiceStatus,
            LocalDate sentToApps,
            boolean appsCancelled,
            String settlementStatus) {}

    /** Cases awaiting a clearance, by clearance (SRS-10-11). */
    public record Awaiting(String clearance, long cases) {}

    public record Clearance(String type, String status, String remark) {}

    public record Recovery(
            long id,
            String type,
            String reference,
            String period,
            BigDecimal amount,
            String source,
            String gratuityReason,
            String gratuityOrderRef,
            Long hqApprovalId,
            String hqApprovalStatus) {}

    public record ExGratia(
            long id, String letterNo, LocalDate letterDate, BigDecimal amount, String status) {}

    /** One case in full (O-05 detail). */
    public record CaseDetail(
            CaseRow summary,
            long settlementId,
            List<Clearance> clearances,
            BigDecimal pendingSalary,
            BigDecimal leaveEncashment,
            BigDecimal arrears,
            BigDecimal exGratia,
            BigDecimal gratuity,
            BigDecimal totalRecoveries,
            BigDecimal recoveredFromGratuity,
            boolean netRecovery,
            List<Recovery> recoveries,
            List<ExGratia> exGratiaEntries,
            String cpfStatus,
            BigDecimal cpfAmount,
            String cpfSanctionNumber,
            boolean benefitsHeld,
            String invoiceStatus,
            boolean awaitingChecker) {}

    /** The O-05 filters; each may be null. */
    public record Filter(Integer stage, String eis) {}

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final String FROM =
            " FROM settlement_case c JOIN employee e ON e.employee_id = c.employee_id LEFT JOIN"
                + " org_location l ON l.location_id = e.location_id LEFT JOIN labour_profile lp ON"
                + " lp.employee_id = e.employee_id LEFT JOIN final_settlement s ON"
                + " s.settlement_case_id = c.settlement_case_id";

    /** SRS-10-13: the dues before recoveries, once computed. */
    private static final String DUES =
            " COALESCE(lp.payroll_type, 'STAFF') AS payroll_type, s.total_recoveries,"
                + " s.recovered_from_gratuity, CASE WHEN s.net_payable IS NULL THEN NULL ELSE"
                + " COALESCE(s.pending_salary_wage, 0) + COALESCE(s.leave_encashment_amount, 0) +"
                + " COALESCE(s.total_arrears, 0) + COALESCE(s.ex_gratia_amount, 0) END AS"
                + " payroll_dues";

    private static final String WHERE =
            " WHERE (:all = 'Y' OR e.location_id IN (:locations)) AND (:stage IS NULL OR"
                    + " c.case_stage = :stage) AND (:eis IS NULL OR e.emp_num = :eis)";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final ApprovalStatuses statuses;
    private final Clock clock;

    public SettlementViews(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            ApprovalStatuses statuses,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.statuses = statuses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Paged<CaseRow> cases(AccessContext context, Filter filter, PageSpec page) {
        MapSqlParameterSource p = scoped(context, "VIEW", filter);
        Long total = named.queryForObject("SELECT COUNT(*)" + FROM + WHERE, p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<CaseRow> rows =
                named.query(
                        "SELECT c.settlement_case_id, c.case_reference, e.emp_num, e.first_name,"
                                + " e.last_name, l.location_code, l.location_name,"
                                + " c.separation_type, c.separation_date, c.case_stage,"
                                + " s.settlement_status, s.net_payable,"
                                + DUES
                                + FROM
                                + WHERE
                                + " ORDER BY c.settlement_case_id DESC OFFSET :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> row(rs, List.of()));
        List<Long> ids = rows.stream().map(CaseRow::caseId).toList();
        Map<Long, List<String>> awaiting = awaiting(ids);
        Set<Long> gratuity = hqPending(ids);
        Set<String> submitted =
                rows.isEmpty()
                        ? Set.of()
                        : statuses.pending(SettlementWork.ENTITY).stream()
                                .map(ApprovalStatuses.Pending::entityKey)
                                .collect(Collectors.toSet());
        return new Paged<>(
                rows.stream()
                        .map(
                                r ->
                                        withState(
                                                r,
                                                awaiting.getOrDefault(r.caseId(), List.of()),
                                                submitted.contains(r.caseReference()),
                                                gratuity.contains(r.caseId())))
                        .toList(),
                page.page(),
                page.size(),
                RowCounts.of(total));
    }

    /** SRS-10-11: how many open cases await each clearance. */
    @Transactional(readOnly = true)
    public List<Awaiting> awaitingCounts(AccessContext context) {
        MapSqlParameterSource p = scoped(context, "VIEW", new Filter(null, null));
        return named.query(
                "SELECT k.clearance_type, COUNT(*) FROM settlement_clearance k JOIN settlement_case"
                        + " c ON c.settlement_case_id = k.settlement_case_id JOIN employee e ON"
                        + " e.employee_id = c.employee_id WHERE k.clearance_status IN ('PENDING',"
                        + " 'RAISED') AND c.case_stage < 8 AND (:all = 'Y' OR e.location_id IN"
                        + " (:locations)) GROUP BY k.clearance_type ORDER BY k.clearance_type",
                p,
                (rs, i) -> new Awaiting(rs.getString(1), rs.getLong(2)));
    }

    /** The four tiles (O-05 summary; SRS-10-6, SRS-10-11, SRS-10-22). */
    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        MapSqlParameterSource p = scoped(context, "VIEW", new Filter(null, null));
        YearMonth month = YearMonth.now(clock.withZone(IST));
        p.addValue("from", Timestamp.valueOf(month.atDay(1).atStartOfDay()))
                .addValue("to", Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay()));
        long[] settled = new long[1];
        BigDecimal[] amount = {BigDecimal.ZERO};
        List<TypeCount> byType =
                named.query(
                        "SELECT c.separation_type, COUNT(*), SUM(CASE WHEN c.case_stage >= 7 THEN 1"
                                + " ELSE 0 END), SUM(CASE WHEN c.case_stage >= 7 THEN"
                                + " COALESCE(s.net_payable, 0) ELSE 0 END)"
                                + FROM
                                + WHERE
                                + " AND c.opened_at >= :from AND c.opened_at < :to GROUP BY"
                                + " c.separation_type ORDER BY c.separation_type",
                        p,
                        (rs, i) -> {
                            settled[0] += rs.getLong(3);
                            amount[0] = amount[0].add(rs.getBigDecimal(4));
                            return new TypeCount(rs.getString(1), rs.getLong(2));
                        });
        Long awaiting =
                named.queryForObject(
                        "SELECT COUNT(DISTINCT c.settlement_case_id) FROM settlement_clearance k"
                                + " JOIN settlement_case c ON c.settlement_case_id ="
                                + " k.settlement_case_id JOIN employee e ON e.employee_id ="
                                + " c.employee_id WHERE k.clearance_status IN ('PENDING',"
                                + " 'RAISED') AND c.case_stage < 8 AND (:all = 'Y' OR"
                                + " e.location_id IN (:locations))",
                        p,
                        Long.class);
        List<Long> gratuity =
                named.queryForList(
                        "SELECT r.hq_approval_id FROM fs_recovery_detail r JOIN final_settlement s"
                                + " ON s.settlement_id = r.settlement_id JOIN settlement_case c ON"
                                + " c.settlement_case_id = s.settlement_case_id JOIN employee e ON"
                                + " e.employee_id = c.employee_id WHERE r.hq_approval_id IS NOT"
                                + " NULL AND c.case_stage < 8 AND (:all = 'Y' OR e.location_id IN"
                                + " (:locations))",
                        p,
                        Long.class);
        Long netRecovery =
                named.queryForObject(
                        "SELECT COUNT(*)"
                                + FROM
                                + WHERE
                                + " AND s.net_payable < 0 AND c.case_stage < 8",
                        p,
                        Long.class);
        return new Summary(
                byType.stream().mapToLong(TypeCount::cases).sum(),
                settled[0],
                amount[0],
                byType,
                RowCounts.of(awaiting),
                pendingCount(gratuity),
                RowCounts.of(netRecovery));
    }

    /** SRS-G-37, SRS-10-31: each computed settlement payment with its invoice, newest first. */
    @Transactional(readOnly = true)
    public Paged<InvoiceRow> invoices(AccessContext context, PageSpec page) {
        MapSqlParameterSource p = scoped(context, "VIEW", new Filter(null, null));
        Long total =
                named.queryForObject(
                        "SELECT COUNT(*)" + FROM + WHERE + " AND s.net_payable IS NOT NULL",
                        p,
                        Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<InvoiceRow> rows =
                named.query(
                        "SELECT c.settlement_case_id, c.case_reference, e.emp_num, e.first_name,"
                                + " e.last_name, s.net_payable, s.ap_invoice_id,"
                                + " s.credit_memo_invoice_id, s.invoice_status, s.invoice_sent_at,"
                                + " s.apps_cancel_status, s.settlement_status"
                                + FROM
                                + WHERE
                                + " AND s.net_payable IS NOT NULL ORDER BY c.settlement_case_id"
                                + " DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> invoice(rs));
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }

    /** The reference of a case in the session's offices (else not found). */
    @Transactional(readOnly = true)
    public String reference(AccessContext context, long caseId) {
        MapSqlParameterSource p =
                scoped(context, "VIEW", new Filter(null, null)).addValue("id", caseId);
        return named
                .queryForList(
                        "SELECT c.case_reference"
                                + FROM
                                + WHERE
                                + " AND c.settlement_case_id = :id",
                        p,
                        String.class)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** One case in full, in the session's offices (else not found). */
    @Transactional(readOnly = true)
    public CaseDetail detail(AccessContext context, long caseId) {
        MapSqlParameterSource p =
                scoped(context, "VIEW", new Filter(null, null)).addValue("id", caseId);
        List<Map<String, Object>> found =
                named.queryForList(
                        "SELECT c.settlement_case_id, c.case_reference, e.emp_num, e.first_name,"
                            + " e.last_name, l.location_code, l.location_name, c.separation_type,"
                            + " c.separation_date, c.case_stage, c.cpf_final_status_info,"
                            + " c.cpf_final_amount_info, c.benefits_held_flag, s.settlement_id,"
                            + " ps.system_sanction_number, s.settlement_status, s.net_payable,"
                            + " s.pending_salary_wage, s.leave_encashment_amount, s.total_arrears,"
                            + " s.ex_gratia_amount, s.gratuity_amount, s.is_net_recovery,"
                            + " s.invoice_status,"
                                + DUES
                                + FROM
                                // SRS-10-3 (O-05 l.2937): the CPF final payment's sanction
                                // reference, shown for information beside its amount and status.
                                + " LEFT JOIN pf_sanction ps ON ps.sanction_id = c.cpf_sanction_id"
                                + WHERE
                                + " AND c.settlement_case_id = :id",
                        p);
        if (found.isEmpty()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = found.getFirst();
        long settlementId = ((Number) r.get("SETTLEMENT_ID")).longValue();
        MapSqlParameterSource q =
                new MapSqlParameterSource("id", caseId).addValue("sid", settlementId);
        List<Clearance> clearances =
                named.query(
                        "SELECT clearance_type, clearance_status, remark FROM settlement_clearance"
                                + " WHERE settlement_case_id = :id ORDER BY clearance_type",
                        q,
                        (rs, i) ->
                                new Clearance(rs.getString(1), rs.getString(2), rs.getString(3)));
        List<Recovery> recoveries =
                named.query(
                        "SELECT recovery_detail_id, recovery_type, source_key, recovery_period,"
                            + " recovery_amount, recovery_source, gratuity_reason,"
                            + " gratuity_order_ref, hq_approval_id FROM fs_recovery_detail WHERE"
                            + " settlement_id = :sid ORDER BY recovery_detail_id",
                        q,
                        (rs, i) ->
                                new Recovery(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getBigDecimal(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getObject(9, Long.class),
                                        null));
        recoveries = withHqApproval(recoveries);
        List<ExGratia> entries =
                named.query(
                        "SELECT ex_gratia_entry_id, sanction_letter_no, sanction_letter_date,"
                                + " ex_gratia_amount, entry_status FROM fs_ex_gratia_entry WHERE"
                                + " settlement_id = :sid ORDER BY ex_gratia_entry_id",
                        q,
                        (rs, i) ->
                                new ExGratia(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getDate(3).toLocalDate(),
                                        rs.getBigDecimal(4),
                                        rs.getString(5)));
        List<String> awaiting =
                clearances.stream()
                        .filter(c -> Set.of("PENDING", "RAISED").contains(c.status()))
                        .map(Clearance::type)
                        .toList();
        // SRS-10-14 (US-GAP-219): the settlement waits for the Checker, who may then record an
        // exclusion of an outstanding clearance.
        boolean submitted =
                !statuses.pending(SettlementWork.ENTITY, (String) r.get("CASE_REFERENCE"))
                        .isEmpty();
        CaseRow summary =
                new CaseRow(
                        caseId,
                        (String) r.get("CASE_REFERENCE"),
                        (String) r.get("EMP_NUM"),
                        name((String) r.get("FIRST_NAME"), (String) r.get("LAST_NAME")),
                        office((String) r.get("LOCATION_CODE"), (String) r.get("LOCATION_NAME")),
                        (String) r.get("SEPARATION_TYPE"),
                        date(r.get("SEPARATION_DATE")),
                        Stage.of(((Number) r.get("CASE_STAGE")).intValue()).name(),
                        awaiting,
                        (String) r.get("SETTLEMENT_STATUS"),
                        (BigDecimal) r.get("NET_PAYABLE"),
                        (String) r.get("PAYROLL_TYPE"),
                        (BigDecimal) r.get("PAYROLL_DUES"),
                        (BigDecimal) r.get("TOTAL_RECOVERIES"),
                        (BigDecimal) r.get("RECOVERED_FROM_GRATUITY"),
                        submitted,
                        recoveries.stream()
                                .anyMatch(
                                        x ->
                                                ApprovalStatus.PENDING_APPROVAL
                                                        .name()
                                                        .equals(x.hqApprovalStatus())));
        return new CaseDetail(
                summary,
                settlementId,
                clearances,
                (BigDecimal) r.get("PENDING_SALARY_WAGE"),
                (BigDecimal) r.get("LEAVE_ENCASHMENT_AMOUNT"),
                (BigDecimal) r.get("TOTAL_ARREARS"),
                (BigDecimal) r.get("EX_GRATIA_AMOUNT"),
                (BigDecimal) r.get("GRATUITY_AMOUNT"),
                (BigDecimal) r.get("TOTAL_RECOVERIES"),
                (BigDecimal) r.get("RECOVERED_FROM_GRATUITY"),
                "Y".equals(r.get("IS_NET_RECOVERY")),
                recoveries,
                entries,
                (String) r.get("CPF_FINAL_STATUS_INFO"),
                (BigDecimal) r.get("CPF_FINAL_AMOUNT_INFO"),
                (String) r.get("SYSTEM_SANCTION_NUMBER"),
                "Y".equals(r.get("BENEFITS_HELD_FLAG")),
                (String) r.get("INVOICE_STATUS"),
                submitted);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * SRS-10-22 (O-05 l.2940): the Headquarters approval of a recovery taken from gratuity, as its
     * approval item stands (PENDING_APPROVAL, APPROVED, RETURNED, REJECTED); none for a recovery
     * from the payroll dues.
     */
    private List<Recovery> withHqApproval(List<Recovery> recoveries) {
        List<Long> ids =
                recoveries.stream()
                        .map(Recovery::hqApprovalId)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (ids.isEmpty()) {
            return recoveries;
        }
        Map<Long, ApprovalStatus> status = statuses.of(ids);
        return recoveries.stream()
                .map(
                        x ->
                                x.hqApprovalId() == null || !status.containsKey(x.hqApprovalId())
                                        ? x
                                        : new Recovery(
                                                x.id(),
                                                x.type(),
                                                x.reference(),
                                                x.period(),
                                                x.amount(),
                                                x.source(),
                                                x.gratuityReason(),
                                                x.gratuityOrderRef(),
                                                x.hqApprovalId(),
                                                status.get(x.hqApprovalId()).name()))
                .toList();
    }

    /** The cases of {@code caseIds} with a recovery against gratuity awaiting Headquarters. */
    private Set<Long> hqPending(List<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, Long> caseOf = new java.util.HashMap<>();
        named.query(
                "SELECT r.hq_approval_id, s.settlement_case_id FROM fs_recovery_detail r JOIN"
                        + " final_settlement s ON s.settlement_id = r.settlement_id WHERE"
                        + " r.hq_approval_id IS NOT NULL AND s.settlement_case_id IN (:ids)",
                new MapSqlParameterSource("ids", caseIds),
                rs -> {
                    caseOf.put(rs.getLong(1), rs.getLong(2));
                });
        if (caseOf.isEmpty()) {
            return Set.of();
        }
        return statuses.of(caseOf.keySet()).entrySet().stream()
                .filter(e -> e.getValue() == ApprovalStatus.PENDING_APPROVAL)
                .map(e -> caseOf.get(e.getKey()))
                .collect(Collectors.toSet());
    }

    private long pendingCount(List<Long> approvalIds) {
        if (approvalIds.isEmpty()) {
            return 0;
        }
        return statuses.of(approvalIds).values().stream()
                .filter(s -> s == ApprovalStatus.PENDING_APPROVAL)
                .count();
    }

    private Map<Long, List<String>> awaiting(List<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> out = new java.util.HashMap<>();
        named.query(
                "SELECT settlement_case_id, clearance_type FROM settlement_clearance WHERE"
                        + " settlement_case_id IN (:ids) AND clearance_status IN ('PENDING',"
                        + " 'RAISED') ORDER BY clearance_type",
                new MapSqlParameterSource("ids", caseIds),
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>())
                            .add(rs.getString(2));
                });
        return out;
    }

    private MapSqlParameterSource scoped(AccessContext context, String action, Filter filter) {
        policy.require(context, SettlementCaseOpener.SCREEN, action);
        return OfficeScope.of(context, policy.isAtHeadquarters(context))
                .addValue("stage", filter.stage(), Types.INTEGER)
                .addValue(
                        "eis",
                        filter.eis() == null || filter.eis().isBlank()
                                ? null
                                : filter.eis().strip(),
                        Types.VARCHAR);
    }

    private static CaseRow row(ResultSet rs, List<String> awaiting) throws SQLException {
        return new CaseRow(
                rs.getLong("settlement_case_id"),
                rs.getString("case_reference"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                office(rs.getString("location_code"), rs.getString("location_name")),
                rs.getString("separation_type"),
                rs.getDate("separation_date").toLocalDate(),
                Stage.of(rs.getInt("case_stage")).name(),
                awaiting,
                rs.getString("settlement_status"),
                rs.getBigDecimal("net_payable"),
                rs.getString("payroll_type"),
                rs.getBigDecimal("payroll_dues"),
                rs.getBigDecimal("total_recoveries"),
                rs.getBigDecimal("recovered_from_gratuity"),
                false,
                false);
    }

    private static InvoiceRow invoice(ResultSet rs) throws SQLException {
        BigDecimal net = rs.getBigDecimal("net_payable");
        boolean creditMemo = net.signum() < 0;
        String invoice = rs.getString(creditMemo ? "credit_memo_invoice_id" : "ap_invoice_id");
        Timestamp sent = rs.getTimestamp("invoice_sent_at");
        return new InvoiceRow(
                rs.getLong("settlement_case_id"),
                rs.getString("case_reference"),
                rs.getString("emp_num"),
                name(rs.getString("first_name"), rs.getString("last_name")),
                creditMemo,
                net,
                invoice,
                rs.getString("invoice_status"),
                sent == null ? null : sent.toInstant().atZone(IST).toLocalDate(),
                "CONFIRMED".equals(rs.getString("apps_cancel_status")),
                rs.getString("settlement_status"));
    }

    private static CaseRow withState(
            CaseRow r, List<String> awaiting, boolean submitted, boolean hqPending) {
        return new CaseRow(
                r.caseId(),
                r.caseReference(),
                r.eis(),
                r.name(),
                r.office(),
                r.separationType(),
                r.separationDate(),
                r.stage(),
                awaiting,
                r.status(),
                r.netPayable(),
                r.payrollType(),
                r.payrollDues(),
                r.totalRecoveries(),
                r.recoveredFromGratuity(),
                submitted,
                hqPending);
    }

    private static String name(String first, String last) {
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
    }

    private static String office(String code, String name) {
        return code == null ? null : code + " - " + name;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : new Date(((java.util.Date) value).getTime()).toLocalDate();
    }
}
