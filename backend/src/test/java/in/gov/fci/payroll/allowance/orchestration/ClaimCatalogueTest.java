package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The claim catalogue (plan 10 D10-1 … D10-3; SRS-11-6, SRS-11-9): a norm not configured, the
 * claims a window counts, the overlap, the member by number and an inactive pay code.
 */
class ClaimCatalogueTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final ClaimRules.Adjudication PAID =
            new ClaimRules.Adjudication(new BigDecimal("1000"), new BigDecimal("1000"), false);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AllowanceRepositories.Claims claims = mock(AllowanceRepositories.Claims.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final ClaimCatalogue catalogue = new ClaimCatalogue(jdbc, claims, statuses);

    ClaimCatalogueTest() {
        // Approval items 1 to 9 are approved unless a test says otherwise.
        when(statuses.approved(any()))
                .thenAnswer(
                        i -> {
                            java.util.Collection<Long> ids = i.getArgument(0);
                            return Set.copyOf(ids.stream().filter(id -> id < 10).toList());
                        });
    }

    private static void refused(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    private static ReimbursementClaim claim(long id, LocalDate from, boolean adjudicated) {
        ReimbursementClaim c =
                ReimbursementClaim.labour(
                        7L,
                        "LABOUR_TA",
                        new ClaimRules.Period(from, from.plusDays(4)),
                        BigDecimal.TEN);
        if (adjudicated) {
            c.enteredWith(1L, PAID);
        }
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private void norms(Map<String, Object> row) {
        norms(row == null ? List.of() : List.of(row));
    }

    private void norms(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(
                        argThat((String sql) -> sql != null && sql.contains("IS NULL AND")),
                        eq("LABOUR_TA"),
                        eq("LABOUR"),
                        any(),
                        any()))
                .thenReturn(rows);
    }

    private void categorised(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(
                        argThat((String sql) -> sql != null && sql.contains("IS NOT NULL")),
                        eq("LABOUR_TA"),
                        eq("LABOUR"),
                        any(),
                        any()))
                .thenReturn(rows);
    }

    private static Map<String, Object> norm(long id, String value, Long approvalId) {
        Map<String, Object> row = new HashMap<>();
        row.put("CEILING_NORM_ID", id);
        row.put("CEILING_VALUE", value == null ? null : new BigDecimal(value));
        row.put("PERIODICITY", "MONTHLY");
        row.put("APPROVAL_ID", approvalId);
        return row;
    }

    /**
     * R-MC, SRS-16-14 (UI audit S2): a norm version still awaiting the Checker (or rejected, or
     * never submitted) never applies to a claim; the approved version in force below it does, and
     * with none approved the norm is not configured.
     */
    @Test
    void SRS_16_14_onlyAnApprovedNormVersionAppliesToAClaim() {
        // The newest row (approval 11) still waits; the approved one below it applies.
        norms(List.of(norm(5L, "7000", 11L), norm(4L, "5000", 3L)));
        assertThat(catalogue.norm("LABOUR_TA", "LABOUR", APRIL).id()).isEqualTo(4L);
        // Only pending or unsubmitted rows: not configured.
        norms(List.of(norm(5L, "7000", 11L), norm(6L, "6000", null)));
        categorised(List.of());
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        norms(List.of(norm(6L, "6000", null)));
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        // A categorised norm still waiting does not make the claimant's category decide.
        norms(List.of());
        categorised(List.of(norm(7L, "1", 12L)));
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        categorised(List.of(norm(7L, "1", 2L)));
        refused(AllowanceErrorCode.CATEGORY, () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
    }

    @Test
    void SRS_11_9_aNormWithoutAValueOrPeriodicityIsNotConfigured() {
        norms((Map<String, Object>) null);
        categorised(List.of());
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        Map<String, Object> row = new HashMap<>();
        row.put("CEILING_NORM_ID", 3L);
        row.put("CEILING_VALUE", null);
        row.put("PERIODICITY", "MONTHLY");
        row.put("APPROVAL_ID", 1L);
        norms(row);
        categorised(List.of());
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        row.put("CEILING_VALUE", new BigDecimal("5000"));
        row.put("PERIODICITY", null);
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        row.put("PERIODICITY", "MONTHLY");
        assertThat(catalogue.norm("LABOUR_TA", "LABOUR", APRIL))
                .isEqualTo(
                        new ClaimCatalogue.Norm(
                                3L, new BigDecimal("5000"), ClaimRules.Periodicity.MONTHLY));
    }

    @Test
    void US_GAP_212_aTypeNormedOnlyByEligibilityCategoryRefusesAClaimantWithoutOne() {
        norms((Map<String, Object>) null);
        categorised(List.of(norm(9L, "1", 1L)));
        refused(AllowanceErrorCode.CATEGORY, () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
        // No categorised norm either: plainly not configured.
        categorised(List.of());
        refused(
                AllowanceErrorCode.NOT_CONFIGURED,
                () -> catalogue.norm("LABOUR_TA", "LABOUR", APRIL));
    }

    @Test
    void SRS_11_6_theOverlapBoundariesAndTheClaimsThatNoLongerCount() {
        // US-GAP-213: an existing live claim 1 to 5 April, both days included.
        ReimbursementClaim live = claim(1L, APRIL, true);
        ReimbursementClaim rejected = claim(2L, APRIL.plusDays(10), true);
        rejected.rejected();
        ReimbursementClaim overlapRejected = claim(3L, APRIL.plusDays(10), true);
        overlapRejected.overlapRejected();
        ReimbursementClaim cancelled = claim(4L, APRIL.plusDays(10), true);
        ReflectionTestUtils.setField(cancelled, "status", "CANCELLED");
        when(claims.findByEmployeeIdAndClaimType(7L, "LABOUR_TA"))
                .thenReturn(List.of(live, rejected, overlapRejected, cancelled));
        // From = the existing To: the shared day overlaps.
        assertThat(
                        catalogue.overlap(
                                7L,
                                "LABOUR_TA",
                                new ClaimRules.Period(APRIL.plusDays(4), APRIL.plusDays(8)),
                                null))
                .contains(live);
        // To = the existing From: so does that day.
        assertThat(
                        catalogue.overlap(
                                7L,
                                "LABOUR_TA",
                                new ClaimRules.Period(APRIL.minusDays(3), APRIL),
                                null))
                .contains(live);
        // The adjacent periods (ending the day before, starting the day after) do not.
        assertThat(
                        catalogue.overlap(
                                7L,
                                "LABOUR_TA",
                                new ClaimRules.Period(APRIL.minusDays(3), APRIL.minusDays(1)),
                                null))
                .isEmpty();
        assertThat(
                        catalogue.overlap(
                                7L,
                                "LABOUR_TA",
                                new ClaimRules.Period(APRIL.plusDays(5), APRIL.plusDays(9)),
                                null))
                .isEmpty();
        // A rejected, overlap-rejected or cancelled claim of the same days no longer counts.
        ClaimRules.Period theirs = new ClaimRules.Period(APRIL.plusDays(10), APRIL.plusDays(14));
        assertThat(catalogue.overlap(7L, "LABOUR_TA", theirs, null)).isEmpty();
        catalogue.requireNoOverlap(7L, "LABOUR_TA", theirs, null);
    }

    @Test
    void SRS_11_9_theWindowCountsOnlyTheOtherLiveAdjudicatedClaimsInIt() {
        ReimbursementClaim self = claim(1L, APRIL.plusDays(10), true);
        ReimbursementClaim counted = claim(2L, APRIL.plusDays(20), true);
        ReimbursementClaim rejected = claim(3L, APRIL.plusDays(15), true);
        rejected.rejected();
        ReimbursementClaim unadjudicated = claim(4L, APRIL.plusDays(5), false);
        ReimbursementClaim nextMonth = claim(5L, APRIL.plusMonths(1), true);
        when(claims.findByEmployeeIdAndClaimType(7L, "LABOUR_TA"))
                .thenReturn(List.of(self, counted, rejected, unadjudicated, nextMonth));
        ClaimRules.Period period = new ClaimRules.Period(APRIL, APRIL.plusDays(4));
        ClaimCatalogue.Norm monthly =
                new ClaimCatalogue.Norm(3L, new BigDecimal("5000"), ClaimRules.Periodicity.MONTHLY);
        assertThat(
                        catalogue
                                .adjudicate(
                                        monthly,
                                        7L,
                                        "LABOUR_TA",
                                        period,
                                        new BigDecimal("6000"),
                                        1L)
                                .payable())
                .isEqualByComparingTo("4000");
        ClaimCatalogue.Norm once =
                new ClaimCatalogue.Norm(
                        3L, new BigDecimal("5000"), ClaimRules.Periodicity.ONE_TIME_PER_EVENT);
        assertThat(
                        catalogue
                                .adjudicate(
                                        once, 7L, "LABOUR_TA", period, new BigDecimal("6000"), 1L)
                                .payable())
                .isEqualByComparingTo("5000");
    }

    // SC-10-0025: a claim overlapping a live claim of the same type is rejected.
    @Test
    void SRS_11_6_anOverlapIsAnotherLiveClaimWhosePeriodMeetsThisOne() {
        ReimbursementClaim self = claim(1L, APRIL, true);
        ReimbursementClaim rejected = claim(2L, APRIL, true);
        rejected.overlapRejected();
        ReimbursementClaim later = claim(3L, APRIL.plusDays(20), true);
        when(claims.findByEmployeeIdAndClaimType(7L, "LABOUR_TA"))
                .thenReturn(List.of(self, rejected, later));
        ClaimRules.Period period = new ClaimRules.Period(APRIL, APRIL.plusDays(4));
        assertThat(catalogue.overlap(7L, "LABOUR_TA", period, 1L)).isEmpty();
        catalogue.requireNoOverlap(7L, "LABOUR_TA", period, 1L);
        assertThat(catalogue.overlap(7L, "LABOUR_TA", period, null)).contains(self);
        refused(
                AllowanceErrorCode.OVERLAP,
                () -> catalogue.requireNoOverlap(7L, "LABOUR_TA", period, null));
    }

    @Test
    void SRS_11_13_aMemberNumberMatchesOneMemberAndANullOrUnusedPayCodeIsInactive() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("L1"))).thenReturn(List.of());
        refused(CommonErrorCode.NOT_FOUND, () -> catalogue.memberByNumber("L1"));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("L2"))).thenReturn(List.of(1L, 2L));
        refused(CommonErrorCode.NOT_FOUND, () -> catalogue.memberByNumber("L2"));
        assertThat(catalogue.payCodeActive(null, APRIL)).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(913), any(), any()))
                .thenReturn(null);
        assertThat(catalogue.payCodeActive(913, APRIL)).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(914), any(), any()))
                .thenReturn(0);
        assertThat(catalogue.payCodeActive(914, APRIL)).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(915), any(), any()))
                .thenReturn(1);
        assertThat(catalogue.payCodeActive(915, APRIL)).isTrue();
        assertThat(
                        new ClaimCatalogue.MemberFacts(1L, "L", "N", null, "IN_SERVICE", null, 5L)
                                .labour())
                .isFalse();
        assertThat(
                        new ClaimCatalogue.MemberFacts(
                                        1L, "L", "N", "DPS_LABOUR", "IN_SERVICE", null, 5L)
                                .labour())
                .isTrue();
        assertThat(
                        new ClaimCatalogue.MemberFacts(
                                        1L, "L", "N", "STAFF", "IN_SERVICE", null, 5L)
                                .labour())
                .isFalse();
    }
}
