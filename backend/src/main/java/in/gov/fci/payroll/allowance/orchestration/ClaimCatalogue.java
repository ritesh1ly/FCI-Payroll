package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What I-03 adjudicates a claim with (plan 10 D10-1 … D10-5): the claim types (M-16 CLAIM_TYPE, the
 * pay code in its numeric attribute), the confirmed M-15 norm in force, the required documents
 * (M-16 CLAIM_REQUIRED_DOCUMENT) and the member's type and status. Never a default: a missing norm
 * is "not configured" (R-RULE, AL-2).
 */
@Component
public class ClaimCatalogue {

    /** A claim type as I-03 offers it. */
    public record ClaimType(String code, String label, Integer payCode) {}

    /** The confirmed M-15 norm in force for a claim. */
    public record Norm(long id, BigDecimal ceiling, ClaimRules.Periodicity periodicity) {}

    /** The member facts the rules read. */
    public record MemberFacts(
            long employeeId,
            String eis,
            String name,
            String employeeType,
            String employmentStatus,
            LocalDate retirementDate,
            Long locationId) {

        public boolean labour() {
            return employeeType != null && employeeType.endsWith("_LABOUR");
        }
    }

    private final JdbcTemplate jdbc;
    private final AllowanceRepositories.Claims claims;
    private final ApprovalStatuses statuses;

    public ClaimCatalogue(
            JdbcTemplate jdbc, AllowanceRepositories.Claims claims, ApprovalStatuses statuses) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.statuses = statuses;
    }

    /**
     * The claim types in force for a member type (D10-1): a type serves the member types its
     * confirmed or unconfirmed norms name (LABOUR, EMPLOYEE or BOTH).
     */
    @Transactional(readOnly = true)
    public List<ClaimType> types(String memberType, LocalDate day) {
        return jdbc.query(
                "SELECT v.value_code, v.value_label_en, v.numeric_attribute FROM reference_value v"
                        + " JOIN reference_list l ON l.list_id = v.list_id WHERE l.list_code ="
                        + " 'CLAIM_TYPE' AND v.effective_from <= ? AND (v.effective_to IS NULL OR"
                        + " v.effective_to >= ?) AND EXISTS (SELECT 1 FROM allowance_ceiling_norm n"
                        + " WHERE n.claim_type = v.value_code AND n.member_scope IN (?, 'BOTH'))"
                        + " ORDER BY v.sort_order, v.value_code",
                (rs, i) -> {
                    BigDecimal code = rs.getBigDecimal(3);
                    return new ClaimType(
                            rs.getString(1),
                            rs.getString(2),
                            code == null ? null : code.intValue());
                },
                Date.valueOf(day),
                Date.valueOf(day),
                memberType);
    }

    /** The claim type, if it is in force on {@code day}. */
    @Transactional(readOnly = true)
    public Optional<ClaimType> type(String code, LocalDate day) {
        return jdbc
                .query(
                        "SELECT v.value_code, v.value_label_en, v.numeric_attribute FROM"
                            + " reference_value v JOIN reference_list l ON l.list_id = v.list_id"
                            + " WHERE l.list_code = 'CLAIM_TYPE' AND v.value_code = ? AND"
                            + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to"
                            + " >= ?)",
                        (rs, i) -> {
                            BigDecimal pay = rs.getBigDecimal(3);
                            return new ClaimType(
                                    rs.getString(1),
                                    rs.getString(2),
                                    pay == null ? null : pay.intValue());
                        },
                        code,
                        Date.valueOf(day),
                        Date.valueOf(day))
                .stream()
                .findFirst();
    }

    /**
     * D10-2 / D10-3: the confirmed norm without an eligibility category in force on the claim's
     * start for the type and member type; refused "not configured" otherwise.
     */
    @Transactional(readOnly = true)
    public Norm norm(String claimType, String memberType, LocalDate day) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT ceiling_norm_id, ceiling_value, periodicity, approval_id FROM"
                            + " allowance_ceiling_norm WHERE claim_type = ? AND member_scope IN (?,"
                            + " 'BOTH') AND is_confirmed = 'Y' AND eligibility_category IS NULL AND"
                            + " effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)"
                            + " ORDER BY effective_from DESC, version_no DESC, ceiling_norm_id"
                            + " DESC",
                        claimType,
                        memberType,
                        Date.valueOf(day),
                        Date.valueOf(day));
        // R-MC (UI audit S2): only a version the Checker approved applies; one still awaiting its
        // decision (or rejected / returned) is never used for money.
        rows = approvedOnly(rows);
        if (rows.isEmpty() && byCategoryOnly(claimType, memberType, day)) {
            // US-GAP-212 (SRS use case precondition l.2765): the claimant's category and
            // eligibility must be set; no member category is held yet (PENDING Q-10-1).
            throw new FciException(
                    AllowanceErrorCode.CATEGORY, new Object[] {claimType}, List.of(), null);
        }
        if (rows.isEmpty()
                || rows.get(0).get("CEILING_VALUE") == null
                || rows.get(0).get("PERIODICITY") == null) {
            throw new FciException(
                    AllowanceErrorCode.NOT_CONFIGURED,
                    new Object[] {"The M-15 entitlement norm of " + claimType},
                    List.of(),
                    null);
        }
        Map<String, Object> r = rows.get(0);
        return new Norm(
                ((Number) r.get("CEILING_NORM_ID")).longValue(),
                (BigDecimal) r.get("CEILING_VALUE"),
                ClaimRules.Periodicity.valueOf((String) r.get("PERIODICITY")));
    }

    /**
     * R-MC, SRS-16-14 (UI audit S2): the norm rows whose approval item is APPROVED, in their order;
     * a row with no approval item (never submitted through M-15) is not in force either.
     */
    private List<Map<String, Object>> approvedOnly(List<Map<String, Object>> rows) {
        List<Long> ids =
                rows.stream()
                        .map(r -> r.get("APPROVAL_ID"))
                        .filter(Objects::nonNull)
                        .map(v -> ((Number) v).longValue())
                        .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> approved = statuses.approved(ids);
        return rows.stream()
                .filter(
                        r ->
                                r.get("APPROVAL_ID") != null
                                        && approved.contains(
                                                ((Number) r.get("APPROVAL_ID")).longValue()))
                .toList();
    }

    /**
     * US-GAP-212: whether the type's only confirmed norms in force for the member type are set by
     * eligibility category (so the claimant's category decides, and it is not set).
     */
    private boolean byCategoryOnly(String claimType, String memberType, LocalDate day) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT approval_id FROM allowance_ceiling_norm WHERE claim_type = ? AND"
                                + " member_scope IN (?, 'BOTH') AND is_confirmed = 'Y' AND"
                                + " eligibility_category IS NOT NULL AND effective_from <= ? AND"
                                + " (effective_to IS NULL OR effective_to >= ?)",
                        claimType,
                        memberType,
                        Date.valueOf(day),
                        Date.valueOf(day));
        return !approvedOnly(rows).isEmpty();
    }

    /**
     * SRS-11-9 / D10-2: the claim adjudicated against the norm, the member's other live claims of
     * the type in the window counted; {@code self} (a claim being validated) is left out.
     */
    @Transactional(readOnly = true)
    public ClaimRules.Adjudication adjudicate(
            Norm norm,
            long employeeId,
            String claimType,
            ClaimRules.Period period,
            BigDecimal claimed,
            Long self) {
        ClaimRules.Period window = ClaimRules.window(norm.periodicity(), period);
        BigDecimal taken = BigDecimal.ZERO;
        if (norm.periodicity() != ClaimRules.Periodicity.ONE_TIME_PER_EVENT) {
            for (ReimbursementClaim c :
                    claims.findByEmployeeIdAndClaimType(employeeId, claimType)) {
                if (c.live()
                        && !c.getId().equals(self)
                        && c.getPayableAmount() != null
                        && window.overlaps(c.period())) {
                    taken = taken.add(c.getPayableAmount());
                }
            }
        }
        return ClaimRules.adjudicate(norm.ceiling(), taken, claimed);
    }

    /**
     * SRS-11-6: the live claim of the type whose period overlaps, if any (no exception: a caller
     * that records the overlap must not have its transaction marked for rollback).
     */
    @Transactional(readOnly = true)
    public Optional<ReimbursementClaim> overlap(
            long employeeId, String claimType, ClaimRules.Period period, Long self) {
        return claims.findByEmployeeIdAndClaimType(employeeId, claimType).stream()
                .filter(c -> c.live() && !c.getId().equals(self) && period.overlaps(c.period()))
                .findFirst();
    }

    /** SRS-11-6: refuses the period when a live claim of the type overlaps it. */
    @Transactional(readOnly = true)
    public void requireNoOverlap(
            long employeeId, String claimType, ClaimRules.Period period, Long self) {
        Optional<ReimbursementClaim> c = overlap(employeeId, claimType, period, self);
        if (c.isPresent()) {
            throw new FciException(
                    AllowanceErrorCode.OVERLAP,
                    new Object[] {c.get().getId(), c.get().getPeriodFrom(), c.get().getPeriodTo()},
                    List.of(),
                    null);
        }
    }

    /** SRS-11-14 / D10-4: the configured requirements (list values) in force. */
    @Transactional(readOnly = true)
    public List<String> requiredDocuments(LocalDate day) {
        return jdbc.queryForList(
                "SELECT v.value_code FROM reference_value v JOIN reference_list l ON l.list_id ="
                        + " v.list_id WHERE l.list_code = 'CLAIM_REQUIRED_DOCUMENT' AND"
                        + " v.effective_from <= ? AND (v.effective_to IS NULL OR v.effective_to >="
                        + " ?) ORDER BY v.sort_order, v.value_code",
                String.class,
                Date.valueOf(day),
                Date.valueOf(day));
    }

    /** The member's type and status, or not found. */
    @Transactional(readOnly = true)
    public MemberFacts member(long employeeId) {
        return jdbc
                .query(
                        "SELECT employee_id, emp_num, first_name, last_name, employee_type,"
                                + " employment_status, retirement_date, location_id FROM employee"
                                + " WHERE employee_id = ?",
                        (rs, i) -> {
                            Date retire = rs.getDate(7);
                            long location = rs.getLong(8);
                            boolean noLocation = rs.wasNull();
                            String last = rs.getString(4);
                            return new MemberFacts(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    rs.getString(3) + (last == null ? "" : " " + last),
                                    rs.getString(5),
                                    rs.getString(6),
                                    retire == null ? null : retire.toLocalDate(),
                                    noLocation ? null : location);
                        },
                        employeeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The member with Employee / Labour Number {@code eis}, or not found. */
    @Transactional(readOnly = true)
    public MemberFacts memberByNumber(String eis) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT employee_id FROM employee WHERE emp_num = ? AND record_status <>"
                                + " 'CANCELLED'",
                        Long.class,
                        eis);
        if (ids.size() != 1) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return member(ids.get(0));
    }

    /** Whether the pay code is an active component of the catalogue on {@code day}. */
    @Transactional(readOnly = true)
    public boolean payCodeActive(Integer payCode, LocalDate day) {
        if (payCode == null) {
            return false;
        }
        Integer n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pay_component WHERE legacy_pay_code = ? AND"
                                + " is_retired = 'N' AND effective_from <= ? AND (effective_to IS"
                                + " NULL OR effective_to >= ?)",
                        Integer.class,
                        payCode,
                        Date.valueOf(day),
                        Date.valueOf(day));
        return RowCounts.of(n) > 0;
    }
}
