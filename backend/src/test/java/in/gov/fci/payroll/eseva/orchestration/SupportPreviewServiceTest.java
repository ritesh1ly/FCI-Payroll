package in.gov.fci.payroll.eseva.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.eseva.business.EsevaErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Staff support preview (plan 14 phase 14b; SRS-17-6, SRS-17-8, SRS-17-9, SRS-17-10; D14-5): the
 * reason is required; an unknown member reads as not found and one outside the scope is refused by
 * the policy; the user's own record is not previewed; a look writes a closed STAFF session and its
 * audit entry and returns the member masked with every submit disabled.
 */
class SupportPreviewServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final SupportPreviewService service =
            new SupportPreviewService(
                    jdbc,
                    policy,
                    audit,
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    private static AccessContext staff(Long employeeId) {
        return new AccessContext(
                9L,
                "maker.delhi",
                "Maker",
                "s",
                "P",
                5L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(5L));
    }

    /** The E-02 right of a Payroll Maker or Checker, unless a test takes it away. */
    @org.junit.jupiter.api.BeforeEach
    void holdsSupport() {
        when(policy.can(any(), eq("E-02"), eq("SUPPORT"))).thenReturn(true);
    }

    private void member() {
        Map<String, Object> e = new HashMap<>();
        e.put("EMPLOYEE_ID", 7L);
        e.put("EMP_NUM", "104882");
        e.put("FIRST_NAME", "Rakesh");
        e.put("LAST_NAME", "Sharma");
        e.put("LOCATION_ID", 5L);
        e.put("LOCATION_CODE", "DH001");
        e.put("LOCATION_NAME", "Delhi");
        e.put("EMPLOYEE_TYPE", "EMPLOYEE");
        e.put("EMPLOYMENT_STATUS", "IN_SERVICE");
        e.put("PAN_NUMBER", "ABCPK1234D");
        e.put("AADHAAR_NUMBER", "234567891234");
        e.put("BANK_ACCOUNT_NUMBER", "00112233445566");
        e.put("MOBILE_NUMBER", "9876543210");
        e.put("EMAIL_ADDRESS", "rakesh@fci-demo.test");
        when(jdbc.queryForList(contains("FROM employee e"), any(Object[].class)))
                .thenReturn(List.of(e));
    }

    private void refused(AccessContext who, String eis, String reason, Object code) {
        assertThatThrownBy(() -> service.preview(who, eis, reason))
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void SRS_17_8_aPreviewNamesAReasonAndAKnownMemberInScopeNotTheUsersOwn() {
        refused(staff(null), "104882", " ", EsevaErrorCode.REASON);
        when(jdbc.queryForList(contains("FROM employee e"), any(Object[].class)))
                .thenReturn(List.of());
        refused(staff(null), null, "Ticket 42", CommonErrorCode.NOT_FOUND);
        member();
        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(any(), eq("E-02"), eq("SUPPORT"), eq(5L));
        refused(staff(null), "104882", "Ticket 42", CommonErrorCode.NOT_FOUND);
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void SRS_17_6_theUsersOwnRecordIsOpenedOnE01() {
        member();
        refused(staff(7L), "104882", "Ticket 42", EsevaErrorCode.OWN_RECORD);
    }

    // BR-ESEVA-002: a support view disables every submit action.
    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_10_aLookIsRecordedAndShowsTheMemberMaskedWithSubmitDisabled() {
        member();
        doAnswer(
                        call -> {
                            ((GeneratedKeyHolder) call.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("SESSION_ID", 31L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(jdbc.query(contains("FROM payroll_result"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("FROM ess_submission"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        SupportPreviewService.Preview p = service.preview(staff(null), " 104882 ", "Ticket 42");
        assertThat(p.sessionId()).isEqualTo(31L);
        assertThat(p.submitEnabled()).isFalse();
        assertThat(p.member().name()).isEqualTo("Rakesh Sharma");
        assertThat(p.member().office()).isEqualTo("DH001 - Delhi");
        assertThat(p.member().panMasked()).isEqualTo("******234D");
        assertThat(p.member().aadhaarMasked()).isEqualTo("********1234");
        assertThat(p.member().bankAccountMasked()).endsWith("5566").doesNotContain("0011");
        assertThat(p.member().mobileMasked()).isEqualTo("********10");
        assertThat(p.member().emailMasked()).doesNotContain("rakesh@");
        verify(jdbc).update(contains("SET session_reference"), eq("ESS-SUP-31"), eq(31L));
        verify(audit).record(eq("maker.delhi"), any());
    }

    @Test
    void SRS_17_8_aSessionWithoutAKeyIsAnError() {
        member();
        assertThatThrownBy(() -> service.preview(staff(null), "104882", "Ticket 42"))
                .isInstanceOf(IllegalStateException.class);
        verify(audit, never()).record(anyString(), any());
    }

    /**
     * SRS-17-10: a member with no last name and no office shows the first name and no office; the
     * locked months show gross, deductions and net as paid.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_17_10_aMemberWithoutLastNameOrOfficeShowsTheLockedMonths() throws Exception {
        Map<String, Object> e = new HashMap<>();
        e.put("EMPLOYEE_ID", 8L);
        e.put("EMP_NUM", "104883");
        e.put("FIRST_NAME", "Meena");
        e.put("LAST_NAME", null);
        e.put("LOCATION_ID", null);
        e.put("LOCATION_CODE", null);
        when(jdbc.queryForList(contains("FROM employee e"), any(Object[].class)))
                .thenReturn(List.of(e));
        when(policy.canAt(any(), eq("E-02"), eq("SUPPORT"), isNull())).thenReturn(true);
        doAnswer(
                        call -> {
                            ((GeneratedKeyHolder) call.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("SESSION_ID", 32L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        java.sql.ResultSet month = mock(java.sql.ResultSet.class);
        when(month.getString(1)).thenReturn("202609");
        when(month.getBigDecimal(2)).thenReturn(new java.math.BigDecimal("52000.00"));
        when(month.getBigDecimal(3)).thenReturn(new java.math.BigDecimal("9000.00"));
        when(month.getBigDecimal(4)).thenReturn(new java.math.BigDecimal("43000.00"));
        when(jdbc.query(contains("FROM payroll_result"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(a -> List.of(((RowMapper<Object>) a.getArgument(1)).mapRow(month, 0)));
        when(jdbc.query(contains("FROM ess_submission"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        SupportPreviewService.Preview p = service.preview(staff(null), "104883", "Ticket 43");
        assertThat(p.member().name()).isEqualTo("Meena");
        assertThat(p.member().office()).isNull();
        assertThat(p.payslipMonths())
                .singleElement()
                .satisfies(
                        m -> {
                            assertThat(m.yymm()).isEqualTo("202609");
                            assertThat(m.net()).isEqualByComparingTo("43000");
                        });
    }

    /**
     * FRS V0.5 l.6958 (US-GAP-261): the staff user closes their own open preview with its closing
     * time, audited; a session not open for this user reads as not found and nothing is audited.
     */
    @Test
    void FRS_l6958_aSupportSessionKeepsItsClosingTimeAndIsClosedOnce() {
        when(jdbc.update(contains("closed_explicitly = 'Y'"), any(), eq(31L), eq(9L)))
                .thenReturn(1);
        assertThat(service.close(staff(null), 31L))
                .isEqualTo(Instant.parse("2026-10-28T05:00:00Z"));
        verify(audit).record(eq("maker.delhi"), any(AuditService.Entry.class));
        assertThatThrownBy(() -> service.close(staff(null), 32L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        verify(audit, org.mockito.Mockito.times(1))
                .record(anyString(), any(AuditService.Entry.class));
    }

    /** SRS-17-10: a blank last name (spaces only) shows the first name alone. */
    @Test
    void SRS_17_10_aBlankLastNameShowsTheFirstNameAlone() {
        Map<String, Object> e = new HashMap<>();
        e.put("EMPLOYEE_ID", 8L);
        e.put("EMP_NUM", "104884");
        e.put("FIRST_NAME", "Asha");
        e.put("LAST_NAME", "   ");
        e.put("LOCATION_ID", 5L);
        e.put("LOCATION_CODE", "DH001");
        e.put("LOCATION_NAME", "Delhi");
        when(jdbc.queryForList(contains("FROM employee e"), any(Object[].class)))
                .thenReturn(List.of(e));
        doAnswer(
                        call -> {
                            ((GeneratedKeyHolder) call.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("SESSION_ID", 33L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        SupportPreviewService.Preview p = service.preview(staff(null), "104884", "Ticket 44");
        assertThat(p.member().name()).isEqualTo("Asha");
        assertThat(p.member().office()).isEqualTo("DH001 - Delhi");
    }

    /** OD-135, SRS l.5499: the Administrator's E-01 VIEW opens the preview with no E-02 grant. */
    @Test
    void OD_135_aStaffUserWithoutSupportLooksWithTheStaffViewOfE01() {
        when(policy.can(any(), eq("E-02"), eq("SUPPORT"))).thenReturn(false);
        member();
        AccessContext admin = withGroup();
        org.assertj.core.api.Assertions.catchThrowable(
                () -> service.preview(admin, "104882", "support"));
        verify(policy).require(admin, "E-01", "VIEW");
        verify(policy).requireRecordAt(admin, "E-01", "VIEW", 5L);
    }

    /** OD-135, SRS-17-6: an eSeva session holds E-01 VIEW and is refused here. */
    @Test
    void OD_135_anEsevaSessionCannotOpenAPreview() {
        org.mockito.Mockito.doThrow(
                        new in.gov.fci.payroll.platform.error.FciException(
                                in.gov.fci.payroll.platform.error.ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .require(any(), eq("E-02"), eq("SUPPORT"));
        assertThatThrownBy(() -> service.preview(staff(7L), "104882", "support"))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    private static AccessContext withGroup() {
        return new AccessContext(
                9L,
                "sysadmin",
                "Admin",
                "s",
                "SYSTEM_ADMINISTRATOR",
                5L,
                null,
                List.of(mock(in.gov.fci.payroll.platform.security.ActiveGroup.class)),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(5L));
    }
}
