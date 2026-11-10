package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-08 arrear register (plan 13 phase 13a; SRS-8-8, SRS-8-14, SRS-8-21, SRS-21-9): only a computed
 * event in scope goes for Approve &amp; Merge, with a remark; the approval posts it to the next
 * open month of its unit (the month after a locked one); a rejection reverses its details; the
 * register and the details read every row.
 */
class ArrearRegisterServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final OrgDirectory directory = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final AuditService audit = mock(AuditService.class);
    private final ArrearRegisterService service =
            new ArrearRegisterService(
                    named,
                    policy,
                    approvals,
                    directory,
                    payroll,
                    audit,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker@x",
                    "M",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private void event(String status, Long location) {
        Map<String, Object> e = new HashMap<>();
        e.put("EVENT_STATUS", status);
        e.put("SCOPE_LOCATION_ID", location);
        e.put("EFFECTIVE_FROM", Date.valueOf(LocalDate.of(2026, 7, 1)));
        when(named.queryForList(
                        contains("AND e.arrear_event_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of(e));
    }

    @Test
    void SRS_8_14_anUnknownEventIsNotFoundOnlyAComputedOneGoesWithARemark() {
        when(named.queryForList(
                        contains("AND e.arrear_event_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.submit(context, 5L, "r"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        event("RAISED", 10L);
        assertThatThrownBy(() -> service.submit(context, 5L, "r"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ArrearErrorCode.STATE);
        event("COMPUTED", null);
        for (String remark : new String[] {null, " "}) {
            assertThatThrownBy(() -> service.submit(context, 5L, remark))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(ArrearErrorCode.REMARK);
        }
        service.submit(context, 5L, " Order 12 ");
        verify(approvals).submit(eq(context), any(), any());
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                4L, "I-08", entity, "5", outcome, "wrong month", null, "ddo@x", Instant.now());
    }

    @Test
    void
            SRS_8_8_BR_ARR_DA_006_theApprovedEventIsPostedToTheNextOpenMonthOfItsUnitARejectedOneReversed() {
        service.on(decided("other", ApprovalStatus.APPROVED));
        verify(named, org.mockito.Mockito.never())
                .update(anyString(), any(SqlParameterSource.class));
        when(named.queryForMap(contains("SELECT scope_location_id"), any(SqlParameterSource.class)))
                .thenReturn(
                        new HashMap<>(
                                java.util.Collections.singletonMap("SCOPE_LOCATION_ID", null)));
        service.on(decided(ArrearRegisterService.ENTITY, ApprovalStatus.APPROVED));
        // No unit: the business month itself (2026-10).
        verify(named)
                .update(
                        contains("posted_to_yymm = :posted"),
                        org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                                p -> "202610".equals(p.getValue("posted"))));
        service.on(decided(ArrearRegisterService.ENTITY, ApprovalStatus.REJECTED));
        verify(named)
                .update(contains("SET arrear_status = 'REVERSED'"), any(SqlParameterSource.class));
        verify(audit, org.mockito.Mockito.times(2)).record(eq("ddo@x"), any());

        // BMAD TR-031 (SRS-8-8): the unit's October is LOCKED, so the approval posts to November -
        // never into the locked month, never the event's own arrear month.
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(directory.cycleUnitOf(11L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(3L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202610", "LOCKED")));
        when(named.queryForMap(contains("SELECT scope_location_id"), any(SqlParameterSource.class)))
                .thenReturn(new HashMap<>(Map.of("SCOPE_LOCATION_ID", 11L)));
        org.mockito.Mockito.clearInvocations(named);
        service.on(decided(ArrearRegisterService.ENTITY, ApprovalStatus.APPROVED));
        verify(named)
                .update(
                        contains("posted_to_yymm = :posted"),
                        org.mockito.ArgumentMatchers.<SqlParameterSource>argThat(
                                p -> "202611".equals(p.getValue("posted"))));
    }

    @Test
    void SRS_8_8_theMonthAfterALockedOneIsTheOpenMonth() {
        assertThat(service.nextOpen(null)).isEqualTo("202610");
        when(directory.cycleUnitOf(10L)).thenReturn(Optional.empty());
        assertThat(service.nextOpen(10L)).as("no cycle unit").isEqualTo("202610");
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(directory.cycleUnitOf(11L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(3L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202610", "LOCKED")));
        assertThat(service.nextOpen(11L)).isEqualTo("202611");
        when(payroll.cycle(3L, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202610", "FROZEN")));
        assertThat(service.nextOpen(11L)).isEqualTo("202610");
    }

    @Test
    void SRS_8_21_theDetailsAndTheRegisterReadEveryRow() throws Exception {
        event("COMPUTED", 10L);
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ResultSet nameless = mock(ResultSet.class);
        ResultSet named_ = mock(ResultSet.class);
        when(named_.getString(2)).thenReturn("Rakesh");
        when(named_.getString(3)).thenReturn("Sharma");
        when(named.query(
                        contains("FROM arrear_detail d JOIN employee m"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<ArrearRegisterService.DetailRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(m.mapRow(nameless, 0), m.mapRow(named_, 1));
                        });
        var page = service.details(context, 5L, new PageSpec(0, 20));
        assertThat(page.items())
                .extracting(ArrearRegisterService.DetailRow::name)
                .containsExactly("", "Rakesh Sharma");
        // Oracle review F3: a member has several lines per month and component, and the EIS
        // repeats across zones, so the detail id ends the order: no line repeats or is skipped
        // between pages.
        verify(named)
                .query(
                        contains("c.legacy_pay_code, d.arrear_detail_id OFFSET"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<ArrearRegisterService.DetailRow>>any());

        assertThat(
                        service.registerSize(
                                context, new ArrearRegisterService.Filter(" APPROVED ", " ")))
                .isEqualTo(1L);
        ResultSet row = mock(ResultSet.class);
        when(row.getTimestamp(6)).thenReturn(Timestamp.from(Instant.parse("2026-10-01T05:00:00Z")));
        when(row.getBigDecimal(12)).thenReturn(BigDecimal.TEN);
        doAnswer(
                        call -> {
                            ((RowCallbackHandler) call.getArgument(2)).processRow(row);
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        service.register(context, new ArrearRegisterService.Filter(null, null), sink);
        verify(sink).row(any(Object[].class));
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new java.io.IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(
                        () ->
                                service.register(
                                        context,
                                        new ArrearRegisterService.Filter(null, null),
                                        broken))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
