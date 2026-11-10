package in.gov.fci.payroll.cpf.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The VPF subscription change (plan 14 phase 14c; BR-ESEVA-006; D14-4): only a member asks, one
 * change at a time, with a CPF ledger of the year and a new amount; the Maker verifies or returns
 * only a request with the Maker, a return names its reason; the Checker's approval applies the
 * amount to the year's ledgers, a rejection ends the request, a return leaves it with the Maker,
 * another entity's decision is not this module's.
 */
class VpfChangeServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final VpfChangeService service =
            new VpfChangeService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    mock(Authorization.class),
                    employees,
                    mock(OrgDirectory.class),
                    mock(PayrollRecords.class),
                    mock(MakerChecker.class),
                    audit,
                    mock(ApplicationEventPublisher.class),
                    Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));

    private static AccessContext who(Long employeeId) {
        return new AccessContext(
                9L,
                "m",
                "Member",
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

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
    }

    @Test
    void BR_ESEVA_006_onlyAMemberWithALedgerAsksOneNewAmountAtATime() {
        refused(() -> service.request(who(null), BigDecimal.TEN), ApiErrorCode.PERMISSION_DENIED);
        when(employees.member(7L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(7L, "104882", "A", 5L, "IN_SERVICE")));
        when(jdbc.queryForList(
                        contains("request_status = 'SUBMITTED'"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of(3L));
        refused(() -> service.request(who(7L), BigDecimal.TEN), CpfErrorCode.VPF_PENDING);
        when(jdbc.queryForList(
                        contains("request_status = 'SUBMITTED'"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM employee_pf_header"), any(Object[].class)))
                .thenReturn(List.of());
        refused(() -> service.request(who(7L), BigDecimal.TEN), CpfErrorCode.VPF_CHANGE);
        Map<String, Object> header = new HashMap<>();
        header.put("PF_HEADER_ID", 4L);
        header.put("VCPF_SUBSCRIPTION_AMT", BigDecimal.TEN);
        when(jdbc.queryForList(contains("FROM employee_pf_header"), any(Object[].class)))
                .thenReturn(List.of(header));
        refused(() -> service.request(who(7L), BigDecimal.TEN), CpfErrorCode.VPF_CHANGE);
        verify(audit, never()).record(anyString(), any());
    }

    private void row(String status, String awaiting) {
        Map<String, Object> r = new HashMap<>();
        r.put("REQUEST_STATUS", status);
        r.put("EMPLOYEE_ID", 7L);
        r.put("REQUESTED_VPF_AMOUNT", BigDecimal.TEN);
        r.put("EFFECTIVE_FROM_YYMM", "202611");
        r.put("EMP_NUM", "104882");
        r.put("LOCATION_ID", 5L);
        r.put("AWAITING", awaiting);
        when(jdbc.queryForList(contains("WHERE r.vpf_change_request_id = ?"), any(Object[].class)))
                .thenReturn(List.of(r));
    }

    @Test
    void BR_ESEVA_006_theMakerActsOnlyOnARequestWithTheMakerAndAReturnNamesItsReason() {
        when(jdbc.queryForList(contains("WHERE r.vpf_change_request_id = ?"), any(Object[].class)))
                .thenReturn(List.of());
        refused(
                () -> service.verify(who(null), 1L),
                in.gov.fci.payroll.platform.error.CommonErrorCode.NOT_FOUND);
        row("APPLIED", "N");
        refused(() -> service.verify(who(null), 1L), CpfErrorCode.VPF_STATE);
        row("SUBMITTED", "Y");
        refused(() -> service.verify(who(null), 1L), CpfErrorCode.VPF_STATE);
        refused(() -> service.returnToMember(who(null), 1L, "x"), CpfErrorCode.VPF_STATE);
        row("SUBMITTED", "N");
        refused(() -> service.returnToMember(who(null), 1L, " "), CpfErrorCode.VPF_CHANGE);
        refused(
                () -> service.returnToMember(who(null), 1L, "x".repeat(501)),
                CpfErrorCode.VPF_CHANGE);
        refused(() -> service.returnToMember(who(null), 1L, "réason"), CpfErrorCode.VPF_CHANGE);
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                12L, "W-01", entity, "1", outcome, reason, null, "ddo", Instant.now());
    }

    @Test
    void BR_ESEVA_006_theCheckersApprovalAppliesTheAmountToTheYearsLedgers() {
        service.on(decided("other", ApprovalStatus.APPROVED, null));
        service.on(decided(VpfChangeService.ENTITY, ApprovalStatus.RETURNED, "fix"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        when(jdbc.queryForObject(
                        contains("SELECT request_status"), eq(String.class), any(Object[].class)))
                .thenReturn("SUBMITTED");
        service.on(decided(VpfChangeService.ENTITY, ApprovalStatus.REJECTED, null));
        verify(jdbc).update(contains("SET request_status"), eq("REJECTED"), eq("Rejected"), eq(1L));
        Map<String, Object> r = new HashMap<>();
        r.put("EMPLOYEE_ID", 7L);
        r.put("REQUESTED_VPF_AMOUNT", new BigDecimal("2500"));
        r.put("EFFECTIVE_FROM_YYMM", "202703");
        when(jdbc.queryForMap(contains("FROM vpf_change_request WHERE"), any(Object[].class)))
                .thenReturn(r);
        service.on(decided(VpfChangeService.ENTITY, ApprovalStatus.APPROVED, null));
        verify(jdbc)
                .update(
                        contains("UPDATE employee_pf_header"),
                        eq(new BigDecimal("2500")),
                        eq(7L),
                        eq("2026-27"));
        verify(jdbc).update(contains("SET request_status"), eq("APPLIED"), eq(null), eq(1L));
    }

    /** The member's subscription rows of the year (an element may be null: no VPF set). */
    @SuppressWarnings("unchecked")
    private void subscription(java.util.List<BigDecimal> amounts) {
        when(jdbc.query(contains("FROM employee_pf_header"), any(RowMapper.class), any(), any()))
                .thenAnswer(
                        a -> {
                            java.util.List<Object> out = new java.util.ArrayList<>();
                            for (BigDecimal amount : amounts) {
                                ResultSet rs = mock(ResultSet.class);
                                when(rs.getBigDecimal(1)).thenReturn(amount);
                                out.add(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                            }
                            return out;
                        });
    }

    private static ResultSet request(String last, String code) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("vpf_change_request_id")).thenReturn(1L);
        when(rs.getString("first_name")).thenReturn("Asha");
        when(rs.getString("last_name")).thenReturn(last);
        when(rs.getString("location_code")).thenReturn(code);
        when(rs.getString("location_name")).thenReturn("Delhi");
        when(rs.getString("awaiting")).thenReturn("N");
        when(rs.getTimestamp("submitted_at")).thenReturn(Timestamp.valueOf("2026-10-20 10:00:00"));
        return rs;
    }

    /**
     * BR-ESEVA-006 / E-01: a member without a CPF ledger this year shows no VPF and is not a CPF
     * member; a ledger without a VPF amount shows zero; each request shows the member's name (the
     * first name alone when there is no last name) and the office with its name when known.
     */
    @Test
    @SuppressWarnings("unchecked")
    void BR_ESEVA_006_theMembersOwnViewShowsTheSubscriptionAndTheRequests() throws Exception {
        subscription(List.of());
        VpfChangeService.Mine none = service.mine(who(7L));
        assertThat(none.currentAmount()).isZero();
        assertThat(none.cpfMember()).isFalse();

        subscription(java.util.Arrays.asList((BigDecimal) null));
        java.util.List<ResultSet> rows =
                List.of(request(null, null), request(" ", "DH001"), request("Rao", "DH001"));
        when(jdbc.query(contains("FETCH FIRST 10"), any(RowMapper.class), any()))
                .thenAnswer(
                        a -> {
                            java.util.List<Object> out = new java.util.ArrayList<>();
                            for (ResultSet rs : rows) {
                                out.add(((RowMapper<Object>) a.getArgument(1)).mapRow(rs, 0));
                            }
                            return out;
                        });
        VpfChangeService.Mine unset = service.mine(who(7L));
        assertThat(unset.currentAmount()).isZero();
        assertThat(unset.cpfMember()).isTrue();
        assertThat(unset.requests())
                .extracting(VpfChangeService.Request::member, VpfChangeService.Request::office)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Asha", null),
                        org.assertj.core.groups.Tuple.tuple("Asha", "DH001 - Delhi"),
                        org.assertj.core.groups.Tuple.tuple("Asha Rao", "DH001 - Delhi"));
    }

    /** BR-ESEVA-006: a return without a reason is refused, whatever office the record is in. */
    @Test
    void BR_ESEVA_006_aReturnWithoutAReasonIsRefused() {
        Map<String, Object> r = new HashMap<>();
        r.put("REQUEST_STATUS", "SUBMITTED");
        r.put("AWAITING", "N");
        r.put("LOCATION_ID", null);
        when(jdbc.queryForList(contains("WHERE r.vpf_change_request_id = ?"), any(Object[].class)))
                .thenReturn(List.of(r));
        refused(() -> service.returnToMember(who(null), 1L, null), CpfErrorCode.VPF_CHANGE);
    }
}
