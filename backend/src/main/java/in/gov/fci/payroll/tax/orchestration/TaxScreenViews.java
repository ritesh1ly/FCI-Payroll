package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of M-17, I-07, C-07, C-10 and C-11 as Wireframes v6.1 draws them (OD-121; DDL
 * report_definition, it_regime_detail, it_declaration_submission, it_declaration, payroll_result,
 * payroll_result_component, nps_deduction, form16, statutory_return, tax_challan).
 *
 * <p>Nothing here decides or computes a rule: the tiles and tables count what is stored, the tax of
 * a member is {@link TaxComputationService#forecast} itself, and a rate that is not configured
 * stays empty (R-RULE, SRS-7-26). The EPS and higher-pension rates of the C-07 table are not in the
 * rule engine (PENDING TAX-C07-EPS), so that table holds the wages and no amount. The NPS columns
 * are nps_deduction as HRMS sent it through {@code nps.api.NpsReceipts} (SRS-9-1): nothing is keyed
 * or derived in Payroll.
 *
 * <p>Every read is gated on its screen and limited to the session's offices (R-RBAC data scope); a
 * PAN is masked before it leaves (R-PII, SRS-7-23). Tracker API-08-8.
 */
@Service
@Loggable
public class TaxScreenViews {

    private static final String VIEW = "VIEW";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** I-07 order copies: apart from the proofs of the round, which belong to the member. */
    public static final String ORDER_ENTITY = "it_declaration_order";

    /** A statutory output on M-17, and the two statements the SRS lists beside them (l.2503). */
    private static final String OUTPUTS =
            "SELECT d.report_key, d.report_name, d.periodicity, d.lock_requirement,"
                    + " d.statutory_reference, d.output_domain FROM report_definition d WHERE"
                    + " d.active_flag = 'Y' AND (d.output_domain = 'STATUTORY_OUTPUT' OR"
                    + " d.report_key IN ('CPF_CONTRIBUTION_STATEMENT', 'NPS_DEDUCTION_REPORT'))"
                    + " ORDER BY d.report_key";

    /** The outputs SRS l.2503 gives the current-system layout (PENDING CLR-56). */
    private static final Set<String> CARRIED_FORWARD =
            Set.of(
                    "IT_TDS_STATEMENT",
                    "PT_SCHEDULE",
                    "CPF_CONTRIBUTION_STATEMENT",
                    "NPS_DEDUCTION_REPORT");

    private static final String MEMBERS_IN_SCOPE =
            " FROM employee e WHERE (:all = 'Y' OR e.location_id IN (:locations))"
                    + " AND e.emp_lbr_flag = 'N'";

    /** The member's latest round of the year that is not superseded. */
    private static final String LATEST_ROUND =
            "(SELECT MAX(s.submission_id) FROM it_declaration_submission s WHERE s.employee_id ="
                    + " e.employee_id AND s.fin_year = :year AND s.submission_status <>"
                    + " 'SUPERSEDED')";

    /**
     * SRS-G-30: the latest open round of the year, at the user's offices, with an order stored. The
     * register lists members by staff number, so such a round can sit past its first page.
     */
    private static final String ORDER_ROUND =
            "SELECT MAX(s.submission_id) FROM it_declaration_submission s JOIN employee e ON"
                    + " e.employee_id = s.employee_id WHERE (:all = 'Y' OR e.location_id IN"
                    + " (:locations)) AND e.emp_lbr_flag = 'N' AND s.fin_year = :year AND"
                    + " s.submission_status <> 'SUPERSEDED' AND EXISTS (SELECT 1 FROM"
                    + " document_attachment a WHERE a.entity_name = '"
                    + ORDER_ENTITY
                    + "' AND a.entity_key = TO_CHAR(s.submission_id) AND a.attachment_status ="
                    + " 'ACTIVE')";

    private static final String DECLARED =
            "(SELECT SUM(d.declared_amount) FROM it_declaration d WHERE d.submission_id = "
                    + LATEST_ROUND
                    + " AND d.section_code = '%s') %s";

    private static final String MEMBER_ROW =
            "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name, r.regime_code,"
                    + " r.locked_flag, r.verified_at, "
                    + LATEST_ROUND
                    + " round_id, (SELECT s.submission_status FROM it_declaration_submission s"
                    + " WHERE s.submission_id = "
                    + LATEST_ROUND
                    + ") round_status, "
                    + DECLARED.formatted("80C", "d80c")
                    + ", "
                    + DECLARED.formatted("80D", "d80d")
                    + ", "
                    + DECLARED.formatted("24(b)", "d24b")
                    + ", (SELECT SUM(COALESCE(d.verified_amount, d.allowed_amount)) FROM"
                    + " it_declaration d WHERE d.submission_id = "
                    + LATEST_ROUND
                    + ") allowed, (SELECT COUNT(*) FROM it_declaration d WHERE d.submission_id = "
                    + LATEST_ROUND
                    + " AND d.allowed_amount < d.declared_amount) capped, (SELECT COUNT(*) FROM"
                    + " it_declaration d WHERE d.submission_id = "
                    + LATEST_ROUND
                    + " AND d.proof_status NOT IN ('VERIFIED', 'APPROVED')) unproved, (SELECT"
                    + " COUNT(*) FROM it_declaration d WHERE d.submission_id = "
                    + LATEST_ROUND
                    + ") lines"
                    + " FROM employee e LEFT JOIN it_regime_detail r ON r.employee_id ="
                    + " e.employee_id AND r.fin_year = :year WHERE (:all = 'Y' OR e.location_id IN"
                    + " (:locations)) AND e.emp_lbr_flag = 'N' ORDER BY e.emp_num OFFSET :offset"
                    + " ROWS FETCH NEXT :size ROWS ONLY";

    /** The section limits in force on the day (the section master, M-18). */
    private static final String SECTIONS =
            "SELECT s.section_code, s.max_deduction_amount, s.regime_applicability,"
                    + " s.effective_from FROM it_section_master s WHERE s.effective_from <= :day"
                    + " AND (s.effective_to IS NULL OR s.effective_to >= :day) AND s.section_code"
                    + " IN ('80C', '80D', '24(b)') ORDER BY s.section_code";

    private static final String RULES =
            "SELECT d.rule_key, MIN(v.effective_from) effective_from FROM rule_definition d JOIN"
                    + " rule_version v ON v.rule_id = d.rule_id WHERE d.rule_key IN"
                    + " ('TAX.SLAB.NEW', 'TAX.SLAB.OLD', 'PT.SLAB') AND v.approval_state ="
                    + " 'APPROVED' AND v.effective_from <= :day AND (v.effective_to IS NULL OR"
                    + " v.effective_to >= :day) GROUP BY d.rule_key ORDER BY d.rule_key";

    /** The committed, locked results of the unit of the period (BR-CYC-007: never a simulation). */
    private static final String RESULTS =
            " FROM payroll_result r WHERE r.run_type = 'ACTUAL' AND r.is_locked = 'Y' AND"
                    + " r.result_status = 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN"
                    + " (:locations))";

    private static final String PAY_CODE =
            "(SELECT MAX(v.numeric_attribute) FROM reference_value v JOIN reference_list l ON"
                    + " l.list_id = v.list_id WHERE l.list_code = 'STATUTORY_DEDUCTION' AND"
                    + " v.value_code = '%s')";

    private static final String BY_TYPE =
            "SELECT r.payroll_type, COUNT(DISTINCT r.employee_id) members, SUM(CASE WHEN"
                    + " p.legacy_pay_code = "
                    + PAY_CODE.formatted("CPF")
                    + " THEN ABS(c.amount) END) cpf, SUM(CASE WHEN p.legacy_pay_code = "
                    + PAY_CODE.formatted("VPF")
                    + " THEN ABS(c.amount) END) vpf, SUM(CASE WHEN p.legacy_pay_code = "
                    + PAY_CODE.formatted("TDS")
                    + " THEN ABS(c.amount) END) tds FROM payroll_result r LEFT JOIN"
                    + " payroll_result_component c ON c.payroll_result_id = r.payroll_result_id AND"
                    + " c.period_date = r.period_date LEFT JOIN pay_component p ON p.component_id ="
                    + " c.component_id WHERE r.run_type = 'ACTUAL' AND r.is_locked = 'Y' AND"
                    + " r.result_status = 'CURRENT' AND (:all = 'Y' OR r.org_unit_id IN"
                    + " (:locations)) AND r.payroll_yymm = :yymm GROUP BY r.payroll_type ORDER BY"
                    + " r.payroll_type";

    private static final String CPF_RATE =
            "SELECT MAX(v.scalar_value) FROM rule_version v JOIN rule_definition d ON d.rule_id ="
                    + " v.rule_id WHERE d.rule_key = 'CPF.RATE.EMPLOYEE' AND v.approval_state ="
                    + " 'APPROVED' AND v.effective_from <= :day AND (v.effective_to IS NULL OR"
                    + " v.effective_to >= :day)";

    private static final String EPF_WAGES =
            "SELECT r.employee_id, e.emp_num, e.first_name, e.last_name, (SELECT SUM(c.amount) FROM"
                    + " payroll_result_component c JOIN pay_component p ON p.component_id ="
                    + " c.component_id WHERE c.payroll_result_id = r.payroll_result_id AND"
                    + " c.period_date = r.period_date AND p.in_cpf_base_flag = 'Y') wages FROM"
                    + " payroll_result r JOIN employee e ON e.employee_id = r.employee_id WHERE"
                    + " r.run_type = 'ACTUAL' AND r.is_locked = 'Y' AND r.result_status = 'CURRENT'"
                    + " AND (:all = 'Y' OR r.org_unit_id IN (:locations)) AND r.payroll_yymm ="
                    + " :yymm ORDER BY e.emp_num OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";

    private static final String PT_OF =
            "SELECT SUM(ABS(c.amount)) FROM payroll_result_component c JOIN pay_component p ON"
                    + " p.component_id = c.component_id JOIN payroll_result r ON"
                    + " r.payroll_result_id = c.payroll_result_id AND r.period_date = c.period_date"
                    + " WHERE r.employee_id = :employee AND r.payroll_yymm = :yymm AND"
                    + " p.legacy_pay_code = "
                    + PAY_CODE.formatted("PT");

    private static final String NPS_WHERE =
            " FROM nps_deduction n JOIN employee e ON e.employee_id = n.employee_id WHERE (:all ="
                    + " 'Y' OR e.location_id IN (:locations)) AND n.record_type = 'MONTHLY' AND"
                    + " n.payroll_yymm = (SELECT MAX(x.payroll_yymm) FROM nps_deduction x JOIN"
                    + " employee y ON y.employee_id = x.employee_id WHERE (:all = 'Y' OR"
                    + " y.location_id IN (:locations)) AND x.record_type = 'MONTHLY')";

    private static final String NPS_ROW =
            "SELECT n.nps_deduction_id, e.emp_num, e.first_name, e.last_name, e.pay_pattern,"
                    + " e.provident_fund_type, n.hrms_nps_flag, n.pran_reference,"
                    + " n.employee_amount, n.employer_amount, n.mismatch_status, n.apply_status"
                    + NPS_WHERE
                    + " ORDER BY e.emp_num OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";

    private static final String CERTIFICATE_WHERE =
            " FROM form16 f JOIN employee e ON e.employee_id = f.employee_id LEFT JOIN"
                    + " it_regime_detail r ON r.employee_id = f.employee_id AND r.fin_year ="
                    + " f.fin_year WHERE f.fin_year = :year AND (:all = 'Y' OR e.location_id IN"
                    + " (:locations))";

    private static final String CERTIFICATE_ROW =
            "SELECT f.form16_id, e.emp_num, e.first_name, e.last_name, e.pan_number, r.regime_code,"
                    + " f.taxable_income, f.tax_deducted, f.part_b_generated_at,"
                    + " f.part_a_attachment_id, f.signed_at, f.release_status, f.report_output_id";

    private static final String CERTIFICATE_UNITS =
            "SELECT l.location_id, l.location_code, l.location_name FROM org_location l WHERE (:all"
                    + " = 'Y' OR l.location_id IN (:locations)) AND EXISTS (SELECT 1 FROM form16 f"
                    + " JOIN employee e ON e.employee_id = f.employee_id WHERE f.fin_year = :year"
                    + " AND e.location_id = l.location_id) ORDER BY l.location_code";

    private static final String RETURN_UNITS =
            "SELECT l.location_id, l.location_code, l.location_name FROM org_location l WHERE (:all"
                    + " = 'Y' OR l.location_id IN (:locations)) AND EXISTS (SELECT 1 FROM"
                    + " statutory_return r WHERE r.org_unit_id = l.location_id) ORDER BY"
                    + " l.location_code";

    private static final String RETURN_ROW =
            "SELECT r.return_id, r.return_type, r.fin_year, r.period_code, r.deductee_count,"
                    + " r.total_amount, r.validated_flag, r.return_status, r.is_correction,"
                    + " (SELECT MAX(x.filing_date) FROM statutory_filing_record x WHERE x.return_id"
                    + " = r.return_id) filed, (SELECT MAX(x.acknowledgement_token) FROM"
                    + " statutory_filing_record x WHERE x.return_id = r.return_id) token FROM"
                    + " statutory_return r WHERE (:all = 'Y' OR r.org_unit_id IN (:locations)) AND"
                    + " r.return_type IN ('FORM_24Q', 'FORM_24Q_CORRECTION')%s ORDER BY r.fin_year"
                    + " DESC, r.period_code DESC, r.return_id OFFSET :offset ROWS FETCH NEXT :size"
                    + " ROWS ONLY";

    // ---------------------------------------------------------------- M-17

    /** One drawn row beside the catalogue's record: what the catalogue has no column for. */
    public record OutputLine(
            String key,
            String name,
            String family,
            String periodicity,
            String lockRequirement,
            String statutoryReference,
            boolean carriedForward,
            boolean catalogueReport) {}

    /** The tiles of M-17. */
    public record OutputSummary(
            long carriedForward, long superseded, int families, List<OutputLine> outputs) {}

    // ---------------------------------------------------------------- I-07

    /** A row of the drawn section table: the section master or the rule of the year. */
    public record SectionLine(
            String code, String regime, BigDecimal limit, LocalDate effectiveFrom, boolean rule) {}

    /** The tiles and the validations of I-07 for a year. */
    public record DeclarationSummary(
            String finYear,
            long members,
            long newRegime,
            long oldRegime,
            long notSelected,
            long received,
            long pending,
            long capped,
            long unproved,
            List<SectionLine> sections,
            Long orderRound) {}

    /** A member's line of the register; a member with no regime has a line too (SRS-7-1). */
    public record DeclarationLine(
            Long employeeId,
            String eis,
            String name,
            String regime,
            boolean frozen,
            Instant frozenAt,
            Long roundId,
            String roundStatus,
            BigDecimal declared80c,
            BigDecimal limit80c,
            BigDecimal declared80d,
            BigDecimal declared24b,
            BigDecimal allowed,
            boolean capped,
            boolean unproved) {}

    // ---------------------------------------------------------------- C-07

    /** One payroll type of the CPF table; an amount no run stored stays null. */
    public record TypeLine(
            String payrollType,
            long members,
            BigDecimal employee,
            BigDecimal vpf,
            BigDecimal tds) {}

    /** The tiles and the CPF table of C-07, for the unit's latest locked period. */
    public record ComputationSummary(
            String period,
            BigDecimal cpfRate,
            List<TypeLine> types,
            long noCpfCode,
            long npsMismatch,
            long regimeMissing) {}

    /** A member's EPS line: the wages stored by the run; the rates are not configured. */
    public record EpsLine(Long employeeId, String eis, String name, BigDecimal wages) {}

    /** A member's tax line: the forecast of the year, or what stands in its way. */
    public record TaxLine(
            Long employeeId,
            String eis,
            String name,
            String regime,
            BigDecimal taxable,
            BigDecimal annualTax,
            BigDecimal monthlyTds,
            BigDecimal professionalTax,
            boolean higherRate,
            String reason) {}

    /** A member's NPS record as HRMS sent it (SRS-9-1). */
    public record NpsLine(
            Long id,
            String eis,
            String name,
            String pattern,
            String pfType,
            boolean npsMember,
            String pran,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            String mismatch,
            String applyStatus) {}

    // ---------------------------------------------------------------- C-10

    /** A unit of the drawn office filters: one that holds records of the screen, in scope. */
    public record Unit(Long id, String code, String name) {}

    /** The tiles and the chain of C-10. */
    public record CertificateSummary(
            String finYear,
            long due,
            long partB,
            long partA,
            long merged,
            long issued,
            long form10e,
            List<Unit> units) {}

    /**
     * A register line; the PAN is masked (SRS-7-23). {@code issuedCopy}: the copy frozen at the
     * release is on file (OD-153); a released line without one has no PDF.
     */
    public record CertificateLine(
            Long id,
            String eis,
            String name,
            String panMasked,
            String panStatus,
            String regime,
            BigDecimal taxable,
            BigDecimal tds,
            boolean partB,
            boolean partA,
            boolean signed,
            String status,
            boolean issuedCopy) {}

    // ---------------------------------------------------------------- C-11

    /** The tiles and the steps of C-11 for a quarter. */
    public record ReturnSummary(
            String finYear,
            String quarter,
            String status,
            boolean validated,
            long deductees,
            long staff,
            long labour,
            BigDecimal tds,
            long challans,
            long challansMapped,
            long panExceptions,
            int definitionVersion,
            LocalDate definitionFrom,
            List<Unit> units) {}

    private record Definition(int version, LocalDate from) {}

    /** A register line of C-11. */
    public record ReturnLine(
            Long id,
            String type,
            String finYear,
            String quarter,
            long deductees,
            BigDecimal tds,
            boolean validated,
            LocalDate filed,
            String token,
            String status,
            boolean correction) {}

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;
    private final TaxComputationService computation;
    private final DeclarationService declarations;
    private final OutputDefinitionService outputs;
    private final DocumentStore documents;
    private final Clock clock;

    public TaxScreenViews(
            NamedParameterJdbcTemplate named,
            Authorization policy,
            TaxComputationService computation,
            DeclarationService declarations,
            OutputDefinitionService outputs,
            DocumentStore documents,
            Clock clock) {
        this.named = named;
        this.policy = policy;
        this.computation = computation;
        this.declarations = declarations;
        this.outputs = outputs;
        this.documents = documents;
        this.clock = clock;
    }

    /** M-17: the drawn columns and tiles (SRS M-17; the versions stay on the catalogue read). */
    @Transactional(readOnly = true)
    public OutputSummary outputSummary(AccessContext context) {
        policy.require(context, OutputDefinitionService.SCREEN, VIEW);
        List<OutputLine> lines =
                named.query(
                        OUTPUTS,
                        (rs, n) -> {
                            String key = rs.getString("report_key");
                            return new OutputLine(
                                    key,
                                    rs.getString("report_name"),
                                    familyOf(key),
                                    rs.getString("periodicity"),
                                    rs.getString("lock_requirement"),
                                    rs.getString("statutory_reference"),
                                    CARRIED_FORWARD.contains(key),
                                    "REPORT".equals(rs.getString("output_domain")));
                        });
        Long superseded =
                named.queryForObject(
                        "SELECT COUNT(*) FROM report_definition_version v JOIN report_definition d"
                                + " ON d.report_def_id = v.report_def_id WHERE d.output_domain ="
                                + " 'STATUTORY_OUTPUT' AND v.effective_to IS NOT NULL AND"
                                + " v.effective_to < :day",
                        new MapSqlParameterSource("day", Date.valueOf(today())),
                        Long.class);
        return new OutputSummary(
                lines.stream().filter(OutputLine::carriedForward).count(),
                RowCounts.of(superseded),
                (int) lines.stream().map(OutputLine::family).distinct().count(),
                lines);
    }

    /** The family of an output: the M-17 list STATUTORY_OUTPUT_FAMILY the filter reads. */
    static String familyOf(String key) {
        return switch (key) {
            case "PT_SCHEDULE" -> "PROFESSIONAL_TAX";
            case "CPF_CONTRIBUTION_STATEMENT" -> "PROVIDENT_FUND";
            case "NPS_DEDUCTION_REPORT" -> "NPS_DEDUCTION";
            default -> "INCOME_TAX";
        };
    }

    /** I-07: the tiles, the validations and the section table of a year (SRS-7-1, SRS-7-20). */
    @Transactional(readOnly = true)
    public DeclarationSummary declarationSummary(AccessContext context, String finYear) {
        policy.require(context, DeclarationService.SCREEN, VIEW);
        LocalDate day = today();
        List<SectionLine> sections = sections(day);
        if (context.dataScope().isEmpty()) {
            return new DeclarationSummary(finYear, 0, 0, 0, 0, 0, 0, 0, 0, sections, null);
        }
        MapSqlParameterSource p = scope(context).addValue("year", finYear);
        long members = count("SELECT COUNT(*)" + MEMBERS_IN_SCOPE, p);
        long fresh = regimes("N", p);
        long old = regimes("O", p);
        long received =
                count(
                        "SELECT COUNT(*)"
                                + MEMBERS_IN_SCOPE
                                + " AND "
                                + LATEST_ROUND
                                + " IS NOT NULL",
                        p);
        long capped =
                count(
                        "SELECT COUNT(*) FROM it_declaration d JOIN employee e ON e.employee_id ="
                                + " d.employee_id WHERE d.fin_year = :year AND (:all = 'Y' OR"
                                + " e.location_id IN (:locations)) AND d.allowed_amount <"
                                + " d.declared_amount AND d.submission_id = "
                                + LATEST_ROUND,
                        p);
        long unproved =
                count(
                        "SELECT COUNT(*) FROM it_declaration d JOIN employee e ON e.employee_id ="
                                + " d.employee_id WHERE d.fin_year = :year AND (:all = 'Y' OR"
                                + " e.location_id IN (:locations)) AND d.proof_status NOT IN"
                                + " ('VERIFIED', 'APPROVED') AND d.submission_id = "
                                + LATEST_ROUND,
                        p);
        return new DeclarationSummary(
                finYear,
                members,
                fresh,
                old,
                members - fresh - old,
                received,
                members - received,
                capped,
                unproved,
                sections,
                named.queryForObject(ORDER_ROUND, p, Long.class));
    }

    private long regimes(String code, MapSqlParameterSource scope) {
        return count(
                "SELECT COUNT(*)"
                        + MEMBERS_IN_SCOPE
                        + " AND EXISTS (SELECT 1 FROM it_regime_detail r WHERE r.employee_id ="
                        + " e.employee_id AND r.fin_year = :year AND r.regime_code = '"
                        + code
                        + "')",
                scope);
    }

    private List<SectionLine> sections(LocalDate day) {
        MapSqlParameterSource p = new MapSqlParameterSource("day", Date.valueOf(day));
        List<SectionLine> lines =
                new ArrayList<>(
                        named.query(
                                SECTIONS,
                                p,
                                (rs, n) ->
                                        new SectionLine(
                                                rs.getString("section_code"),
                                                rs.getString("regime_applicability"),
                                                rs.getBigDecimal("max_deduction_amount"),
                                                rs.getDate("effective_from").toLocalDate(),
                                                false)));
        lines.addAll(
                named.query(
                        RULES,
                        p,
                        (rs, n) ->
                                new SectionLine(
                                        rs.getString("rule_key"),
                                        null,
                                        null,
                                        rs.getDate("effective_from").toLocalDate(),
                                        true)));
        return lines;
    }

    /** I-07: every member of the offices, a page at a time, with the latest round of the year. */
    @Transactional(readOnly = true)
    public Paged<DeclarationLine> declarationMembers(
            AccessContext context, String finYear, PageSpec page) {
        policy.require(context, DeclarationService.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        MapSqlParameterSource p = paged(context, page).addValue("year", finYear);
        long total = count("SELECT COUNT(*)" + MEMBERS_IN_SCOPE, p);
        BigDecimal limit = limitOf80c();
        return new Paged<>(
                named.query(MEMBER_ROW, p, (rs, n) -> declarationLine(rs, limit)),
                page.page(),
                page.size(),
                total);
    }

    private BigDecimal limitOf80c() {
        return sections(today()).stream()
                .filter(s -> "80C".equals(s.code()))
                .findFirst()
                .map(SectionLine::limit)
                .orElse(null);
    }

    private static DeclarationLine declarationLine(ResultSet rs, BigDecimal limit)
            throws SQLException {
        String regime = rs.getString("regime_code");
        return new DeclarationLine(
                rs.getLong("employee_id"),
                rs.getString("emp_num"),
                nameOf(rs),
                regime,
                "Y".equals(rs.getString("locked_flag")),
                instant(rs.getTimestamp("verified_at")),
                rs.getObject("round_id", Long.class),
                rs.getString("round_status"),
                rs.getBigDecimal("d80c"),
                limit,
                rs.getBigDecimal("d80d"),
                rs.getBigDecimal("d24b"),
                rs.getBigDecimal("allowed"),
                rs.getLong("capped") > 0,
                rs.getLong("unproved") > 0);
    }

    /** I-07: the order copies of a round, in the user's offices (SRS-G-30). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, Long round) {
        declarations.get(context, round);
        return documents.active(ORDER_ENTITY, String.valueOf(round));
    }

    /** I-07: an order copy of a round is stored by the Payroll Maker (SRS-G-30, FRS §6.4.3). */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context,
            Long round,
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {
        policy.require(context, DeclarationService.SCREEN, "SUBMIT");
        declarations.get(context, round);
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ORDER_ENTITY,
                        String.valueOf(round),
                        documentType,
                        reference,
                        orderDate,
                        issuingAuthority,
                        fileName,
                        content,
                        true));
    }

    /** I-07: the file of an order copy (the read is logged by the document module). */
    @Transactional
    public DocumentStore.Content orderFile(
            AccessContext context, String sourceIp, Long round, Long attachmentId) {
        declarations.get(context, round);
        return documents.read(
                context.loginId(), sourceIp, ORDER_ENTITY, String.valueOf(round), attachmentId);
    }

    /** C-07: the tiles and the CPF table of the latest locked period (SRS-7-12, BR-CYC-007). */
    @Transactional(readOnly = true)
    public ComputationSummary computationSummary(AccessContext context) {
        policy.require(context, TaxComputationService.SCREEN, VIEW);
        LocalDate day = today();
        BigDecimal rate =
                named.queryForObject(
                        CPF_RATE,
                        new MapSqlParameterSource("day", Date.valueOf(day)),
                        BigDecimal.class);
        if (context.dataScope().isEmpty()) {
            return new ComputationSummary(null, rate, List.of(), 0, 0, 0);
        }
        MapSqlParameterSource p = scope(context).addValue("year", DeclarationYear.of(day));
        String period = latestPeriod(p);
        List<TypeLine> types =
                period == null
                        ? List.of()
                        : named.query(
                                BY_TYPE,
                                p.addValue("yymm", period),
                                (rs, n) ->
                                        new TypeLine(
                                                rs.getString("payroll_type"),
                                                rs.getLong("members"),
                                                rs.getBigDecimal("cpf"),
                                                rs.getBigDecimal("vpf"),
                                                rs.getBigDecimal("tds")));
        long noCode =
                count(
                        "SELECT COUNT(*)"
                                + MEMBERS_IN_SCOPE
                                + " AND e.provident_fund_type = 'CPF' AND e.cpf_code IS NULL",
                        p);
        long mismatch =
                count(
                        "SELECT COUNT(*)"
                                + NPS_WHERE
                                + " AND n.mismatch_status IN ('FLAG_WITHOUT_AMOUNT',"
                                + " 'AMOUNT_WITHOUT_FLAG')",
                        p);
        long regime =
                count(
                        "SELECT COUNT(*)"
                                + MEMBERS_IN_SCOPE
                                + " AND NOT EXISTS (SELECT 1 FROM it_regime_detail r WHERE"
                                + " r.employee_id = e.employee_id AND r.fin_year = :year)",
                        p);
        return new ComputationSummary(period, rate, types, noCode, mismatch, regime);
    }

    private String latestPeriod(MapSqlParameterSource scope) {
        return named.queryForObject(
                "SELECT MAX(r.payroll_yymm)"
                        + RESULTS
                        + " AND EXISTS (SELECT 1 FROM payroll_result_component c WHERE"
                        + " c.payroll_result_id = r.payroll_result_id AND c.period_date ="
                        + " r.period_date)",
                scope,
                String.class);
    }

    /** C-07: the wages of the latest locked period; no EPS amount (PENDING TAX-C07-EPS). */
    @Transactional(readOnly = true)
    public Paged<EpsLine> epsLines(AccessContext context, PageSpec page) {
        policy.require(context, TaxComputationService.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        MapSqlParameterSource p = paged(context, page);
        String period = latestPeriod(p);
        if (period == null) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        p.addValue("yymm", period);
        long total = count("SELECT COUNT(*)" + RESULTS + " AND r.payroll_yymm = :yymm", p);
        return new Paged<>(
                named.query(
                        EPF_WAGES,
                        p,
                        (rs, n) ->
                                new EpsLine(
                                        rs.getLong("employee_id"),
                                        rs.getString("emp_num"),
                                        nameOf(rs),
                                        rs.getBigDecimal("wages"))),
                page.page(),
                page.size(),
                total);
    }

    /**
     * C-07: each member's forecast of the year, a page at a time: the computation of SRS-7-1, or
     * the first reason it cannot be made (SRS-7-26), never an assumed figure.
     */
    @Transactional(readOnly = true)
    public Paged<TaxLine> taxLines(AccessContext context, PageSpec page) {
        policy.require(context, TaxComputationService.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        String year = DeclarationYear.of(today());
        MapSqlParameterSource p = paged(context, page);
        long total = count("SELECT COUNT(*)" + MEMBERS_IN_SCOPE, p);
        List<Map<String, Object>> rows =
                named.queryForList(
                        "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name"
                                + MEMBERS_IN_SCOPE
                                + " ORDER BY e.emp_num OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                + " ONLY",
                        p);
        String period = latestPeriod(p);
        return new Paged<>(
                rows.stream().map(r -> taxLine(context, r, year, period)).toList(),
                page.page(),
                page.size(),
                total);
    }

    private TaxLine taxLine(
            AccessContext context, Map<String, Object> row, String year, String period) {
        Long id = ((Number) row.get("EMPLOYEE_ID")).longValue();
        String eis = (String) row.get("EMP_NUM");
        TaxComputationService.Forecast f = computation.forecast(context, eis, year);
        BigDecimal pt =
                period == null
                        ? null
                        : named.queryForObject(
                                PT_OF,
                                new MapSqlParameterSource("employee", id).addValue("yymm", period),
                                BigDecimal.class);
        TaxCalculator.Result result = f.result().orElse(null);
        return new TaxLine(
                id,
                eis,
                f.name(),
                f.inputs().regime() == null ? null : f.inputs().regime().name(),
                result == null ? null : result.taxableIncome(),
                result == null ? null : result.annualTax(),
                result == null ? null : result.monthlyTds(),
                pt,
                result != null && result.higherRateApplied(),
                f.reasons().isEmpty() ? null : f.reasons().get(0));
    }

    /** C-07: the NPS records of the latest month as HRMS sent them (SRS-9-1, BR-PEN-001). */
    @Transactional(readOnly = true)
    public Paged<NpsLine> npsLines(AccessContext context, PageSpec page) {
        policy.require(context, TaxComputationService.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        MapSqlParameterSource p = paged(context, page);
        return new Paged<>(
                named.query(
                        NPS_ROW,
                        p,
                        (rs, n) ->
                                new NpsLine(
                                        rs.getLong("nps_deduction_id"),
                                        rs.getString("emp_num"),
                                        nameOf(rs),
                                        rs.getString("pay_pattern"),
                                        rs.getString("provident_fund_type"),
                                        "Y".equals(rs.getString("hrms_nps_flag")),
                                        rs.getString("pran_reference"),
                                        rs.getBigDecimal("employee_amount"),
                                        rs.getBigDecimal("employer_amount"),
                                        rs.getString("mismatch_status"),
                                        rs.getString("apply_status"))),
                page.page(),
                page.size(),
                count("SELECT COUNT(*)" + NPS_WHERE, p));
    }

    /** C-10: the tiles and the chain of a year (SRS-7-9, BR-TAX-010). */
    @Transactional(readOnly = true)
    public CertificateSummary certificateSummary(AccessContext context, String finYear) {
        policy.require(context, Form16Service.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new CertificateSummary(finYear, 0, 0, 0, 0, 0, 0, List.of());
        }
        MapSqlParameterSource p = scope(context).addValue("year", finYear);
        long form10e =
                count(
                        "SELECT COUNT(*) FROM report_output o WHERE o.report_key = 'FORM_10E' AND"
                                + " o.fin_year = :year AND o.run_type = 'ACTUAL'",
                        p);
        return new CertificateSummary(
                finYear,
                count("SELECT COUNT(*)" + CERTIFICATE_WHERE, p),
                count(
                        "SELECT COUNT(*)"
                                + CERTIFICATE_WHERE
                                + " AND f.part_b_generated_at IS NOT NULL",
                        p),
                count(
                        "SELECT COUNT(*)"
                                + CERTIFICATE_WHERE
                                + " AND f.part_a_received_at IS NOT NULL",
                        p),
                count("SELECT COUNT(*)" + CERTIFICATE_WHERE + " AND f.merged_at IS NOT NULL", p),
                count(
                        "SELECT COUNT(*)"
                                + CERTIFICATE_WHERE
                                + " AND f.release_status = 'RELEASED'",
                        p),
                form10e,
                units(CERTIFICATE_UNITS, p));
    }

    /** C-10: one page of the register, by status and office (null: all), the PAN masked. */
    @Transactional(readOnly = true)
    public Paged<CertificateLine> certificateLines(
            AccessContext context, String finYear, String status, Long office, PageSpec page) {
        policy.require(context, Form16Service.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        String filters =
                (status == null ? "" : " AND f.release_status = :status")
                        + (office == null ? "" : " AND e.location_id = :office");
        MapSqlParameterSource p =
                paged(context, page)
                        .addValue("year", finYear)
                        .addValue("status", status)
                        .addValue("office", office);
        return new Paged<>(
                named.query(
                        CERTIFICATE_ROW
                                + CERTIFICATE_WHERE
                                + filters
                                + " ORDER BY e.emp_num OFFSET :offset ROWS FETCH NEXT :size ROWS"
                                + " ONLY",
                        p,
                        (rs, n) -> certificateLine(rs)),
                page.page(),
                page.size(),
                count("SELECT COUNT(*)" + CERTIFICATE_WHERE + filters, p));
    }

    private List<Unit> units(String sql, MapSqlParameterSource p) {
        return named.query(
                sql,
                p,
                (rs, n) ->
                        new Unit(
                                rs.getLong("location_id"),
                                rs.getString("location_code"),
                                rs.getString("location_name")));
    }

    private static CertificateLine certificateLine(ResultSet rs) throws SQLException {
        EmployeeDirectory.MaskedPan pan = EmployeeDirectory.maskedPanOf(rs.getString("pan_number"));
        return new CertificateLine(
                rs.getLong("form16_id"),
                rs.getString("emp_num"),
                nameOf(rs),
                pan.masked(),
                pan.status().name(),
                rs.getString("regime_code"),
                rs.getBigDecimal("taxable_income"),
                rs.getBigDecimal("tax_deducted"),
                rs.getTimestamp("part_b_generated_at") != null,
                rs.getObject("part_a_attachment_id") != null,
                rs.getTimestamp("signed_at") != null,
                rs.getString("release_status"),
                rs.getObject("report_output_id") != null);
    }

    /** C-11: the tiles and the steps of a quarter of the user's units (SRS-7-7, SRS-7-8). */
    @Transactional(readOnly = true)
    public ReturnSummary returnSummary(AccessContext context, String finYear, String quarter) {
        policy.require(context, ReturnService.SCREEN, VIEW);
        LocalDate day = today();
        List<Definition> definition =
                named.query(
                        "SELECT v.version_no, v.effective_from FROM report_definition_version v"
                                + " JOIN report_definition d ON d.report_def_id = v.report_def_id"
                                + " WHERE d.report_key = 'FORM_24Q' AND v.approval_state ="
                                + " 'APPROVED' AND v.effective_from <= :day AND (v.effective_to IS"
                                + " NULL OR v.effective_to >= :day)",
                        new MapSqlParameterSource("day", Date.valueOf(day)),
                        (rs, n) ->
                                new Definition(
                                        rs.getInt("version_no"),
                                        rs.getDate("effective_from").toLocalDate()));
        int version = definition.isEmpty() ? 0 : definition.get(0).version();
        LocalDate from = definition.isEmpty() ? null : definition.get(0).from();
        MapSqlParameterSource p =
                scope(context).addValue("year", finYear).addValue("quarter", quarter);
        List<Map<String, Object>> found =
                context.dataScope().isEmpty()
                        ? List.of()
                        : named.queryForList(
                                "SELECT r.return_id, r.return_status, r.validated_flag,"
                                        + " r.total_amount FROM statutory_return r WHERE"
                                        + " r.return_type = 'FORM_24Q' AND r.fin_year = :year AND"
                                        + " r.period_code = :quarter AND r.is_correction = 'N' AND"
                                        + " (:all = 'Y' OR r.org_unit_id IN (:locations)) ORDER BY"
                                        + " r.return_id",
                                p);
        if (found.isEmpty()) {
            return new ReturnSummary(
                    finYear,
                    quarter,
                    null,
                    false,
                    0,
                    0,
                    0,
                    null,
                    0,
                    0,
                    0,
                    version,
                    from,
                    context.dataScope().isEmpty() ? List.of() : units(RETURN_UNITS, p));
        }
        Map<String, Object> row = found.get(0);
        p.addValue("id", ((Number) row.get("RETURN_ID")).longValue());
        String deductees =
                " FROM statutory_return_deductee d JOIN employee e ON e.employee_id ="
                        + " d.employee_id WHERE d.return_id = :id";
        return new ReturnSummary(
                finYear,
                quarter,
                (String) row.get("RETURN_STATUS"),
                "Y".equals(row.get("VALIDATED_FLAG")),
                count("SELECT COUNT(*)" + deductees, p),
                count("SELECT COUNT(*)" + deductees + " AND e.emp_lbr_flag = 'N'", p),
                count("SELECT COUNT(*)" + deductees + " AND e.emp_lbr_flag = 'Y'", p),
                (BigDecimal) row.get("TOTAL_AMOUNT"),
                count(
                        "SELECT COUNT(*) FROM tax_challan c WHERE c.fin_year = :year AND"
                                + " c.period_code = :quarter AND (:all = 'Y' OR c.org_unit_id IN"
                                + " (:locations))",
                        p),
                count(
                        "SELECT COUNT(DISTINCT d.challan_id) FROM statutory_return_deductee d"
                                + " WHERE d.return_id = :id",
                        p),
                count("SELECT COUNT(*)" + deductees + " AND d.pan_valid_flag = 'N'", p),
                version,
                from,
                units(RETURN_UNITS, p));
    }

    /** C-11: the Form 24Q register of the user's units, newest quarter first. */
    @Transactional(readOnly = true)
    public Paged<ReturnLine> returnLines(AccessContext context, Long office, PageSpec page) {
        policy.require(context, ReturnService.SCREEN, VIEW);
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        String unit = office == null ? "" : " AND r.org_unit_id = :office";
        MapSqlParameterSource p = paged(context, page).addValue("office", office);
        return new Paged<>(
                named.query(RETURN_ROW.formatted(unit), p, (rs, n) -> returnLine(rs)),
                page.page(),
                page.size(),
                count(
                        "SELECT COUNT(*) FROM statutory_return r WHERE (:all = 'Y' OR"
                                + " r.org_unit_id IN (:locations)) AND r.return_type IN"
                                + " ('FORM_24Q', 'FORM_24Q_CORRECTION')"
                                + unit,
                        p));
    }

    private static ReturnLine returnLine(ResultSet rs) throws SQLException {
        Date filed = rs.getDate("filed");
        return new ReturnLine(
                rs.getLong("return_id"),
                rs.getString("return_type"),
                rs.getString("fin_year"),
                rs.getString("period_code"),
                rs.getLong("deductee_count"),
                rs.getBigDecimal("total_amount"),
                "Y".equals(rs.getString("validated_flag")),
                filed == null ? null : filed.toLocalDate(),
                rs.getString("token"),
                rs.getString("return_status"),
                "Y".equals(rs.getString("is_correction")));
    }

    private MapSqlParameterSource scope(AccessContext context) {
        return OfficeScope.of(context, policy.isAtHeadquarters(context));
    }

    private MapSqlParameterSource paged(AccessContext context, PageSpec page) {
        return scope(context)
                .addValue("offset", page.page() * page.size())
                .addValue("size", page.size());
    }

    private long count(String sql, MapSqlParameterSource p) {
        return RowCounts.of(named.queryForObject(sql, p, Long.class));
    }

    private static String nameOf(ResultSet rs) throws SQLException {
        String last = rs.getString("last_name");
        return last == null ? rs.getString("first_name") : rs.getString("first_name") + " " + last;
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    /** The financial year of a day, as the tax tables write fin_year (2026-27). */
    private static final class DeclarationYear {
        private DeclarationYear() {}

        static String of(LocalDate day) {
            int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
            return start + "-" + String.format("%02d", (start + 1) % 100);
        }
    }
}
