package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-25 bulk fixation (plan 13 phase 13e / plan 17; SRS-8-25 steps 5, 6, 8; SRS-13-25; D17-11):
 * Headquarters fixes an approved revision; a stagnation case, a suspended member, one not in
 * service and a new Basic above the scale maximum are exceptions; each fixed member's past months
 * raise an arrear; step 6 is done at once when no arrear is due; the status report lists every
 * office.
 */
class WageRevisionFixationServiceTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 7, 1);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final BasicPayService basic = mock(BasicPayService.class);
    private final Scales scales = mock(Scales.class);
    private final AuditService audit = mock(AuditService.class);
    private final WageRevisionFixationService service =
            new WageRevisionFixationService(
                    named,
                    policy,
                    basic,
                    scales,
                    audit,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));
    private final AccessContext hq =
            new AccessContext(
                    1L, "ra@x", "R", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private void revision(String status) {
        when(named.queryForList(
                        contains("FROM wage_revision w JOIN wage_revision_component"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "REVISION_CODE",
                                        "WR-2026-0001",
                                        "REVISION_STATUS",
                                        status,
                                        "EFFECTIVE_FROM",
                                        Date.valueOf(EFFECTIVE))));
    }

    private static Map<String, Object> member(
            long id, String status, String stagnation, String newPay) {
        Map<String, Object> m = new HashMap<>();
        m.put("WR_MEMBER_ID", id);
        m.put("EMPLOYEE_ID", 100 + id);
        m.put("OLD_PAY", new BigDecimal("50000"));
        m.put("NEW_PAY", new BigDecimal(newPay));
        m.put("EMPLOYMENT_STATUS", status);
        m.put("IS_STAGNATION_CASE", stagnation);
        return m;
    }

    @Test
    void SRS_8_25_onlyHeadquartersFixesAnApprovedRevision() {
        assertThatThrownBy(() -> service.fix(hq, 5L))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(named.queryForList(
                        contains("FROM wage_revision w JOIN wage_revision_component"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.fix(hq, 5L))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        revision("SIMULATED");
        assertThatThrownBy(() -> service.fix(hq, 5L))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(IncrementErrorCode.STATE);
    }

    @Test
    void SRS_13_25_theCasesOutsideTheRuleAreExceptionsAndEachFixedMemberRaisesItsArrear() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        revision("APPROVED");
        when(named.queryForList(
                        contains("FROM wage_revision_member m JOIN employee e"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                member(1, "IN_SERVICE", "Y", "55000"),
                                member(2, "SUSPENDED", "N", "55000"),
                                member(3, "RETIRED", "N", "55000"),
                                member(4, "IN_SERVICE", "N", "99000"),
                                member(5, "IN_SERVICE", "N", "55000"),
                                member(6, "IN_SERVICE", "N", "56000")));
        Scales.Scale scale =
                new Scales.Scale(
                        1L,
                        "E-4",
                        2L,
                        new BigDecimal("40000"),
                        new BigDecimal("90000"),
                        null,
                        null,
                        List.of());
        when(scales.ofMember(any(Long.class), eq(EFFECTIVE))).thenReturn(Optional.of(scale));
        when(scales.ofMember(eq(106L), eq(EFFECTIVE))).thenReturn(Optional.empty());
        when(basic.apply(argThat(c -> c != null && c.employeeId() == 105L)))
                .thenReturn(new BasicPayService.Applied(7L, null, true, 70L));
        when(basic.apply(argThat(c -> c != null && c.employeeId() == 106L)))
                .thenReturn(new BasicPayService.Applied(8L, new BigDecimal("51000"), true, null));
        WageRevisionFixationService.Fixed out = service.fix(hq, 5L);
        assertThat(out.fixed()).isEqualTo(2);
        assertThat(out.exceptions()).isEqualTo(4);
        assertThat(out.arrears()).isEqualTo(1);
        verify(named, times(4))
                .update(contains("fixation_status = 'EXCEPTION'"), any(SqlParameterSource.class));
        verify(named)
                .update(
                        contains(
                                "step_status = 'IN_PROGRESS', started_at = :at WHERE"
                                        + " wage_revision_id = :id AND step_no = 6"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_8_25_step6IsDoneAtOnceWhenNoArrearIsDue() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        revision("APPROVED");
        when(named.queryForList(
                        contains("FROM wage_revision_member m JOIN employee e"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(service.fix(hq, 5L).fixed()).isZero();
        verify(named)
                .update(
                        contains("completed_by = :by WHERE wage_revision_id = :id AND step_no = 6"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_8_25_theStatusReportListsEachOfficeAndAWriteFailureStopsIt() throws Exception {
        AccessContext nowhere =
                new AccessContext(
                        1L, "u@x", "U", "s", "P", null, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThatThrownBy(() -> service.statusSize(nowhere, 5L)).isInstanceOf(FciException.class);
        when(named.queryForObject(
                        contains("SELECT COUNT(*) FROM (SELECT"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThat(service.statusSize(hq, 5L)).isEqualTo(1L);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("DH001");
        when(rs.getString(2)).thenReturn("Delhi");
        doAnswer(
                        call -> {
                            ((RowCallbackHandler) call.getArgument(2)).processRow(rs);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        AccessContext unit =
                new AccessContext(
                        1L,
                        "u@x",
                        "U",
                        "s",
                        "P",
                        10L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(10L));
        service.statusReport(unit, 5L, sink);
        verify(sink).row(eq("DH001 - Delhi"), any(), any(), any(), any(), any());
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new java.io.IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(() -> service.statusReport(hq, 5L, broken))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
