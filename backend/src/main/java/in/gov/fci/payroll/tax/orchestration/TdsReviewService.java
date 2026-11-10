package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.OfficeScope;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The TDS cases the Payroll Checker reviews with the cycle (FRS V0.5 §14.4 Step 4, L5460; US-08-07,
 * US-GAP-29): "Checker reviews any cases flagged for proof-deadline reversal or regime-change
 * mid-year" when verifying the computed TDS as part of the monthly cycle. Listed on C-01 Payroll
 * Cycle Control for the cycle's financial year and the session's offices: each member whose
 * approved declaration lost its provisional exemption at the proof deadline (SRS-7-14, {@code
 * proof_deadline_reversed}) or whose frozen regime was reset by an approved, audited reset
 * (BR-TAX-001, route I07_RESET). Approving or rejecting the cycle stays the C-02 lock decision on
 * W-01 (approve: the TDS posts with the cycle; reject: returned for correction).
 */
@Service
@Loggable
public class TdsReviewService {

    /** The screen the review is part of. */
    static final String SCREEN = "C-01";

    /** Why a member's TDS is flagged. */
    public enum Flag {
        PROOF_DEADLINE_REVERSAL,
        REGIME_RESET
    }

    /**
     * One flagged case: the member, why, the declaration round (reversal) or the reset's approval
     * item, and when the reset was approved (null for a reversal: the round carries no date of it).
     */
    public record FlaggedCase(
            long employeeId,
            String eis,
            String name,
            String office,
            Flag flag,
            Long reference,
            String reason,
            LocalDateTime at) {}

    private static final String CASES =
            "SELECT e.employee_id, e.emp_num, e.first_name, e.last_name, l.location_code,"
                    + " l.location_name, 'PROOF_DEADLINE_REVERSAL' AS flag, s.submission_no AS"
                    + " reference, CAST(NULL AS VARCHAR(1000)) AS reason, CAST(NULL AS TIMESTAMP)"
                    + " AS flagged_at FROM it_declaration_submission s JOIN employee e ON"
                    + " e.employee_id = s.employee_id LEFT JOIN org_location l ON l.location_id ="
                    + " e.location_id WHERE s.fin_year = :year AND s.proof_deadline_reversed = 'Y'"
                    + " AND (:all = 'Y' OR e.location_id IN (:locations)) UNION ALL SELECT"
                    + " e.employee_id, e.emp_num, e.first_name, e.last_name, l.location_code,"
                    + " l.location_name, 'REGIME_RESET' AS flag, r.reset_approval_id AS reference,"
                    + " r.reset_reason AS reason, q.decided_at AS flagged_at FROM it_regime_detail"
                    + " r JOIN approval_queue q ON q.approval_id = r.reset_approval_id JOIN"
                    + " employee e ON e.employee_id = r.employee_id LEFT JOIN org_location l ON"
                    + " l.location_id = e.location_id WHERE r.fin_year = :year AND q.status ="
                    + " 'APPROVED' AND (:all = 'Y' OR e.location_id IN (:locations))";

    private final NamedParameterJdbcTemplate named;
    private final Authorization policy;

    public TdsReviewService(NamedParameterJdbcTemplate named, Authorization policy) {
        this.named = named;
        this.policy = policy;
    }

    /**
     * FRS V0.5 §14.4 Step 4: the flagged TDS cases of the cycle's financial year in the session's
     * offices (every office at Headquarters), by member then flag; one page.
     */
    @Transactional(readOnly = true)
    public Paged<FlaggedCase> flagged(AccessContext context, YearMonth period, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        MapSqlParameterSource p =
                OfficeScope.of(context, policy.isAtHeadquarters(context))
                        .addValue("year", DeclarationRules.finYearOf(period.atDay(1)));
        Long total = named.queryForObject("SELECT COUNT(*) FROM (" + CASES + ") c", p, Long.class);
        p.addValue("offset", (long) page.page() * page.size()).addValue("size", page.size());
        List<FlaggedCase> rows =
                named.query(
                        "SELECT * FROM ("
                                + CASES
                                + ") c ORDER BY emp_num, flag, reference, employee_id OFFSET"
                                + " :offset ROWS FETCH"
                                + " NEXT :size ROWS ONLY",
                        p,
                        (rs, i) -> {
                            String first = rs.getString("first_name");
                            String last = rs.getString("last_name");
                            String code = rs.getString("location_code");
                            long reference = rs.getLong("reference");
                            Long ref = rs.wasNull() ? null : reference;
                            Timestamp at = rs.getTimestamp("flagged_at");
                            return new FlaggedCase(
                                    rs.getLong("employee_id"),
                                    rs.getString("emp_num"),
                                    ((first == null ? "" : first)
                                                    + " "
                                                    + (last == null ? "" : last))
                                            .strip(),
                                    code == null
                                            ? null
                                            : code + " - " + rs.getString("location_name"),
                                    Flag.valueOf(rs.getString("flag")),
                                    ref,
                                    rs.getString("reason"),
                                    at == null ? null : at.toLocalDateTime());
                        });
        return new Paged<>(rows, page.page(), page.size(), RowCounts.of(total));
    }
}
