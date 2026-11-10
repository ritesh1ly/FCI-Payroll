package in.gov.fci.payroll.loans.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * The I-04 sanction's refusals (plan 09 phase 09b; SRS-12-1, SRS-12-11 … 12-15, SRS-12-17,
 * SRS-12-18; R-RULE): an unknown or unpayable member, interest on a type that bears none, an office
 * without a cycle unit, a start in a locked month, an unset limit or ceiling, a combined ceiling
 * exceeded, no room under the deduction ceiling, no computed pay, and a missing order. The limits
 * are fixtures (26 October 2026, October not locked).
 */
class LoanSanctionServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final in.gov.fci.payroll.audit.api.AuditService audit =
            mock(in.gov.fci.payroll.audit.api.AuditService.class);
    private final LoanSanctionService service =
            new LoanSanctionService(
                    jdbc,
                    employees,
                    org,
                    payroll,
                    rules,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    mock(DocumentStore.class),
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC),
                    audit);

    private static AccessContext maker() {
        return new AccessContext(
                3L, "maker@x", "Maker", "s", "P", 300L, null, List.of(), Map.of(), Set.of(),
                Set.of(), Set.of());
    }

    @SuppressWarnings("unchecked")
    LoanSanctionServiceTest() {
        when(employees.memberByEis("100041"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        7L, "100041", "A B", 30L, "IN_SERVICE")));
        when(employees.memberByEis("100042"))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(
                                        8L, "100042", "C D", 30L, "SEPARATED")));
        type(
                new LoanSanctionService.LoanType(
                        1L, "FESTIVAL_ADVANCE", false, "LOAN.FA.CEILING", 10));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(eq(300L), anyString())).thenReturn(Optional.empty());
        rule("LOAN.FA.CEILING", "25000");
        rule("LOAN.FESTIVAL_ADVANCE.MAX_CONCURRENT", "1");
        rule("LOAN.COMBINED.CEILING", "100000");
        rule("LOAN.DEDUCTION.CEILING", "50");
        when(jdbc.queryForObject(
                        startsWith("SELECT COALESCE(SUM"),
                        eq(BigDecimal.class),
                        any(Object[].class)))
                .thenReturn(null);
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM loan_header"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(null);
        pay("40000", "5000");
        when(jdbc.queryForList(
                        startsWith("SELECT approval_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private void type(LoanSanctionService.LoanType... types) {
        when(jdbc.query(
                        startsWith("SELECT loan_type_id"),
                        any(RowMapper.class),
                        any(Object[].class)))
                .thenReturn(List.of(types));
    }

    private void rule(String key, String value) {
        when(rules.resolve(eq(key), any(), any()))
                .thenReturn(
                        Optional.of(
                                new RuleEngine.Resolved(
                                        key,
                                        "CEILING",
                                        1L,
                                        1,
                                        LocalDate.of(2026, 4, 1),
                                        null,
                                        value == null ? null : new BigDecimal(value),
                                        List.of(),
                                        null,
                                        null,
                                        false)));
    }

    private void pay(String gross, String deductions) {
        when(jdbc.queryForList(startsWith("SELECT gross_earnings"), any(Object[].class)))
                .thenReturn(
                        gross == null
                                ? List.of()
                                : List.of(
                                        Map.of(
                                                "GROSS_EARNINGS", new BigDecimal(gross),
                                                "TOTAL_DEDUCTIONS", new BigDecimal(deductions))));
    }

    private static LoanSanctionService.Proposal p(
            String eis, String amount, int n, String instalment, String first) {
        return new LoanSanctionService.Proposal(
                eis,
                "FESTIVAL_ADVANCE",
                new BigDecimal(amount),
                n,
                new BigDecimal(instalment),
                first == null ? null : new BigDecimal(first),
                null,
                0,
                null,
                "202611",
                false);
    }

    private void refused(LoanSanctionService.Proposal proposal, ErrorCode code, String detail) {
        assertThatThrownBy(() -> service.preview(maker(), proposal))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(code);
                            if (detail != null) {
                                assertThat(String.valueOf(e.getMessageArgs()[0])).contains(detail);
                            }
                        });
    }

    @Test
    void SRS_12_11_aSanctionWithinEveryLimitShowsItsScheduleAndARaisedFirstInstalmentIsTested() {
        LoanSanctionService.Preview ok =
                service.preview(maker(), p("100041", "20000", 4, "5000", null));
        assertThat(ok.schedule()).hasSize(4);
        assertThat(ok.maxNewSanction()).isEqualByComparingTo("100000");
        assertThat(ok.outstanding()).isEqualByComparingTo("0");
        assertThat(ok.reduced()).isFalse();
        LoanSanctionService.Preview first =
                service.preview(maker(), p("100041", "20000", 4, "4000", "8000"));
        assertThat(first.reduced()).as("8,000 fits the 15,000 room").isFalse();
        LoanSanctionService.Preview reduced =
                service.preview(maker(), p("100041", "20000", 2, "4000", "16000"));
        assertThat(reduced.reduced()).as("the first instalment is the largest").isTrue();
    }

    @Test
    void SRS_12_17_anUnknownOrUnpayableMemberAndBadTermsAreRefused() {
        refused(p(null, "20000", 4, "5000", null), ApiErrorCode.LOAN_SANCTION, "UNKNOWN_EMPLOYEE");
        refused(
                p("999999", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_SANCTION,
                "UNKNOWN_EMPLOYEE");
        refused(p("100042", "20000", 4, "5000", null), ApiErrorCode.LOAN_SANCTION, "NOT_PAYABLE");
        refused(
                p("100041", "20000", 4, "4000", null),
                ApiErrorCode.LOAN_SANCTION,
                "NOT_RECONCILED");
        LoanSanctionService.Proposal interest =
                new LoanSanctionService.Proposal(
                        "100041",
                        "FESTIVAL_ADVANCE",
                        new BigDecimal("20000"),
                        4,
                        new BigDecimal("5000"),
                        null,
                        new BigDecimal("500"),
                        1,
                        new BigDecimal("500"),
                        "202611",
                        false);
        refused(interest, ApiErrorCode.LOAN_SANCTION, "INTEREST_NOT_BORNE");
        type();
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.LOAN_SANCTION, "TYPE");
    }

    @Test
    void SRS_12_1_theRecoveryStartsInAnOpenMonthOfAUnit() {
        when(org.cycleUnitOf(30L)).thenReturn(Optional.empty());
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.CYCLE_NO_UNIT, null);
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(300L);
        when(org.cycleUnitOf(30L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(300L, "202611"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 300L, "202611", "LOCKED")));
        when(payroll.cycle(300L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 300L, "202610", "LOCKED")));
        LoanSanctionService.Proposal october =
                new LoanSanctionService.Proposal(
                        "100041",
                        "FESTIVAL_ADVANCE",
                        new BigDecimal("20000"),
                        4,
                        new BigDecimal("5000"),
                        null,
                        null,
                        0,
                        null,
                        "202610",
                        false);
        refused(october, ApiErrorCode.LOAN_SANCTION, "START_BEFORE_OPEN_PERIOD");
    }

    @Test
    void R_RULE_anUnsetLimitOrCeilingRefusesAsNotConfigured() {
        type(
                new LoanSanctionService.LoanType(
                        1L, "FESTIVAL_ADVANCE", false, "LOAN.FA.CEILING", null));
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_NOT_CONFIGURED,
                "instalment limit");
        type(new LoanSanctionService.LoanType(1L, "FESTIVAL_ADVANCE", false, null, 10));
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_NOT_CONFIGURED,
                "ceiling of");
        type(
                new LoanSanctionService.LoanType(
                        1L, "FESTIVAL_ADVANCE", false, "LOAN.FA.CEILING", 10));
        rule("LOAN.FA.CEILING", null);
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_NOT_CONFIGURED,
                "LOAN.FA.CEILING");
        when(rules.resolve(eq("LOAN.FA.CEILING"), any(), any())).thenReturn(Optional.empty());
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_NOT_CONFIGURED,
                "LOAN.FA.CEILING");
        when(rules.resolve(eq("LOAN.FA.CEILING"), any(), any()))
                .thenThrow(
                        new FciException(
                                ApiErrorCode.RULE_NOT_CONFIGURED,
                                new Object[] {"k", "d"},
                                List.of(),
                                null));
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_NOT_CONFIGURED,
                "LOAN.FA.CEILING");
    }

    // SRS-12-15: the deduction-from-salary ceiling is a dated Rule Engine parameter.
    @Test
    void SRS_12_12_theTypeTheCombinedAndTheDeductionCeilingsAreTested() {
        refused(p("100041", "22000", 11, "2000", null), ApiErrorCode.LOAN_CEILING, "more than 10");
        refused(p("100041", "30000", 6, "5000", null), ApiErrorCode.LOAN_CEILING, "limit");
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM loan_header"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(1L);
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.LOAN_CEILING, "open");
        when(jdbc.queryForObject(
                        startsWith("SELECT COUNT(*) FROM loan_header"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(0L);
        rule("LOAN.COMBINED.CEILING", "10000");
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.LOAN_CEILING, "combined");
        rule("LOAN.COMBINED.CEILING", "100000");
        pay("40000", "20000");
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.LOAN_CEILING, "no room");
        pay(null, null);
        refused(p("100041", "20000", 4, "5000", null), ApiErrorCode.LOAN_SANCTION, "NO_PAY_RESULT");
    }

    @Test
    void SRS_12_18_theOrderIsMandatory() {
        for (LoanSanctionService.Order order :
                new LoanSanctionService.Order[] {
                    null,
                    new LoanSanctionService.Order(
                            "SANCTION_ORDER",
                            null,
                            LocalDate.of(2026, 10, 1),
                            "AGM",
                            "o.pdf",
                            null),
                    new LoanSanctionService.Order(
                            "SANCTION_ORDER",
                            null,
                            LocalDate.of(2026, 10, 1),
                            "AGM",
                            "o.pdf",
                            new byte[0])
                }) {
            assertThatThrownBy(
                            () ->
                                    service.submit(
                                            maker(), p("100041", "20000", 4, "5000", null), order))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getMessageArgs()).containsExactly("ORDER_MISSING"));
        }
        verify(audit, times(3))
                .submissionBlocked(
                        "maker@x",
                        LoanSanctionService.ENTITY,
                        "100041",
                        LoanSanctionService.SCREEN,
                        "sanction order copy");
    }

    // SRS-20-27: a submission without an order is audited even when it names no member.
    @Test
    void SRS_12_18_aSubmissionWithoutAnOrderOrAMemberIsAuditedUnderNoKey() {
        for (LoanSanctionService.Proposal none :
                java.util.Arrays.asList(null, p(null, "20000", 4, "5000", null))) {
            assertThatThrownBy(() -> service.submit(maker(), none, null))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> assertThat(e.getMessageArgs()).containsExactly("ORDER_MISSING"));
        }
        verify(audit, times(2))
                .submissionBlocked(
                        "maker@x",
                        LoanSanctionService.ENTITY,
                        "-",
                        LoanSanctionService.SCREEN,
                        "sanction order copy");
    }

    /**
     * SRS-12-1 / SRS-12-13: a type that bears interest takes its interest instalments; a first
     * instalment no larger than the others leaves the others as the largest.
     */
    @Test
    void SRS_12_1_anInterestBearingTypeTakesInterestAndASmallerFirstInstalmentIsNotTheLargest() {
        type(new LoanSanctionService.LoanType(1L, "FESTIVAL_ADVANCE", true, "LOAN.FA.CEILING", 10));
        LoanSanctionService.Proposal interest =
                new LoanSanctionService.Proposal(
                        "100041",
                        "FESTIVAL_ADVANCE",
                        new BigDecimal("20000"),
                        4,
                        new BigDecimal("5000"),
                        null,
                        new BigDecimal("500"),
                        1,
                        new BigDecimal("500"),
                        "202611",
                        false);
        assertThat(service.preview(maker(), interest).schedule()).isNotEmpty();
        type(
                new LoanSanctionService.LoanType(
                        1L, "FESTIVAL_ADVANCE", false, "LOAN.FA.CEILING", 10));
        assertThat(service.preview(maker(), p("100041", "20000", 5, "4000", "4000")).reduced())
                .isFalse();
    }

    /** I-04 field table (L2713): an interest-bearing type without interest instalments. */
    @Test
    void SRS_12_1_anInterestBearingTypeWithoutInterestInstalmentsIsRefused() {
        type(new LoanSanctionService.LoanType(1L, "FESTIVAL_ADVANCE", true, "LOAN.FA.CEILING", 10));
        refused(
                p("100041", "20000", 4, "5000", null),
                ApiErrorCode.LOAN_SANCTION,
                "INTEREST_REQUIRED");
    }
}
