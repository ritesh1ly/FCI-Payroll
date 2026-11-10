package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.business.ArrearErrorCode;
import in.gov.fci.payroll.arrear.business.WageRevisionRules;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-25 wage revision (plan 13 phase 13e; SRS-8-25 steps 1 - 4, 8; OQ-14 (v0.4)): Headquarters only;
 * a definition needs its name, date, reason, a staff payroll type, the Basic fitment rule and 1 -
 * 99 instalments, a labour type or a selected scope waits for its dependency; the simulation
 * refreshes existing rows; the Checker approves, returns (SIMULATED) or rejects; the member sheet
 * exports.
 */
class WageRevisionServiceTest {

    private static final LocalDate FROM = LocalDate.of(2027, 1, 1);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final AuditService audit = mock(AuditService.class);
    private final Circulars circulars = mock(Circulars.class);
    private final WageRevisionService service =
            new WageRevisionService(
                    named,
                    policy,
                    approvals,
                    audit,
                    circulars,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    private final AccessContext hq =
            new AccessContext(
                    1L, "su@x", "S", "s", "P", 1L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private static final WageRevisionRules.Rule FACTOR =
            new WageRevisionRules.Rule(
                    WageRevisionRules.Fitment.FACTOR,
                    new BigDecimal("1.1"),
                    null,
                    null,
                    WageRevisionRules.Rounding.RUPEE);

    private static WageRevisionService.Definition def(
            String name,
            List<String> types,
            String scope,
            WageRevisionRules.Rule rule,
            Integer instalments,
            String reason) {
        return new WageRevisionService.Definition(
                name, 3L, types, scope, FROM, rule, instalments, reason);
    }

    private ErrorCode refusal(Runnable call) {
        return ((FciException) Assertions.catchThrowable(call::run)).getErrorCode();
    }

    // SC-13-0057 (outside Headquarters no revision is defined), SC-13-0061 (the scope: All-India or
    // selected).
    @Test
    void SRS_8_9_SRS_8_25_onlyHeadquartersDefinesAndADefinitionNeedsEveryPart() {
        assertThat(
                        refusal(
                                () ->
                                        service.define(
                                                hq,
                                                def(
                                                        "R",
                                                        List.of("STAFF_IDA"),
                                                        null,
                                                        FACTOR,
                                                        1,
                                                        "r"))))
                .as("a unit office")
                .isEqualTo(ArrearErrorCode.WR_HQ_ONLY);
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        List<WageRevisionService.Definition> bad =
                List.of(
                        def(null, List.of("STAFF_IDA"), null, FACTOR, 1, "r"),
                        def(" ", List.of("STAFF_IDA"), null, FACTOR, 1, "r"),
                        new WageRevisionService.Definition(
                                "R", 3L, List.of("STAFF_IDA"), null, null, FACTOR, 1, "r"),
                        def("R", List.of("STAFF_IDA"), null, FACTOR, 1, null),
                        def("R", List.of("STAFF_IDA"), null, FACTOR, 1, " "),
                        def("R", null, null, FACTOR, 1, "r"),
                        def("R", List.of(), null, FACTOR, 1, "r"),
                        def("R", List.of("STAFF_XYZ"), null, FACTOR, 1, "r"),
                        def("R", List.of("STAFF_IDA"), null, null, 1, "r"),
                        def(
                                "R",
                                List.of("STAFF_IDA"),
                                null,
                                new WageRevisionRules.Rule(
                                        WageRevisionRules.Fitment.FACTOR,
                                        BigDecimal.ZERO,
                                        null,
                                        null,
                                        WageRevisionRules.Rounding.RUPEE),
                                1,
                                "r"),
                        def("R", List.of("STAFF_IDA"), null, FACTOR, 0, "r"),
                        def("R", List.of("STAFF_IDA"), null, FACTOR, 100, "r"));
        for (WageRevisionService.Definition d : bad) {
            assertThat(refusal(() -> service.define(hq, d)))
                    .as(d.toString())
                    .isEqualTo(ArrearErrorCode.WR_DEFINITION);
        }
        assertThat(
                        refusal(
                                () ->
                                        service.define(
                                                hq,
                                                def(
                                                        "R",
                                                        List.of("DPS_LABOUR"),
                                                        null,
                                                        FACTOR,
                                                        1,
                                                        "r"))))
                .as("labour waits for modules 20 - 22")
                .isEqualTo(ArrearErrorCode.WR_NOT_AVAILABLE);
        assertThat(
                        refusal(
                                () ->
                                        service.define(
                                                hq,
                                                def(
                                                        "R",
                                                        List.of("STAFF_IDA"),
                                                        "SELECTED",
                                                        FACTOR,
                                                        1,
                                                        "r"))))
                .as("OQ-14 (v0.4)")
                .isEqualTo(ArrearErrorCode.WR_NOT_AVAILABLE);
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private ResultSet summaryRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(5L);
        when(rs.getString(2)).thenReturn("WR-2027-0005");
        when(rs.getString(6)).thenReturn("DEFINED");
        when(rs.getInt(7)).thenReturn(2);
        when(rs.getTimestamp(8)).thenReturn(Timestamp.from(Instant.parse("2026-10-27T05:00:00Z")));
        return rs;
    }

    /** OD-121: the list draws Scope, Effective and Fitment rule, read from the first component. */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_8_25_theListCarriesTheScopeEffectiveDateAndFitmentRule() throws Exception {
        org.mockito.ArgumentCaptor<
                        org.springframework.jdbc.core.RowMapper<WageRevisionService.Summary>>
                mapper =
                        org.mockito.ArgumentCaptor.forClass(
                                org.springframework.jdbc.core.RowMapper.class);
        when(named.queryForObject(
                        contains("COUNT(*) FROM wage_revision"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        when(named.query(
                        contains("ORDER BY w.wage_revision_id"),
                        any(SqlParameterSource.class),
                        mapper.capture()))
                .thenReturn(List.of());
        service.revisions(hq, new in.gov.fci.payroll.platform.paging.PageSpec(0, 20));
        ResultSet rs = summaryRow();
        when(rs.getString(9)).thenReturn("ALL_INDIA");
        when(rs.getDate(10)).thenReturn(java.sql.Date.valueOf(FROM));
        when(rs.getBigDecimal(12)).thenReturn(new BigDecimal("1.1"));
        when(rs.getBigDecimal(13)).thenReturn(new BigDecimal("3"));
        when(rs.getBigDecimal(14)).thenReturn(new BigDecimal("500"));
        when(rs.getString(15)).thenReturn("NEXT_10");
        for (Map.Entry<String, String> e :
                Map.of("FACTOR", "1.1", "PERCENTAGE", "3", "FIXED_AMOUNT", "500").entrySet()) {
            when(rs.getString(11)).thenReturn(e.getKey());
            WageRevisionService.Summary row = mapper.getValue().mapRow(rs, 0);
            assertThat(row.ruleValue()).isEqualByComparingTo(e.getValue());
            assertThat(row.rule()).isEqualTo(e.getKey());
            assertThat(row.scope()).isEqualTo("ALL_INDIA");
            assertThat(row.effectiveFrom()).isEqualTo(FROM);
            assertThat(row.rounding()).isEqualTo("NEXT_10");
        }
        when(rs.getString(11)).thenReturn("RATE_SET");
        assertThat(mapper.getValue().mapRow(rs, 0).ruleValue()).isNull();
        // A revision without a component reads as empty cells, not as a failure.
        ResultSet bare = summaryRow();
        WageRevisionService.Summary row = mapper.getValue().mapRow(bare, 0);
        assertThat(row.rule()).isNull();
        assertThat(row.ruleValue()).isNull();
        assertThat(row.effectiveFrom()).isNull();
    }

    private void detailReads(String type, Object instalments) {
        Map<String, Object> c = new HashMap<>();
        c.put("EFFECTIVE_FROM", FROM);
        c.put("FITMENT_RULE_TYPE", type);
        c.put("FITMENT_FACTOR", new BigDecimal("1.1"));
        c.put("FITMENT_PERCENT", new BigDecimal("10"));
        c.put("FIXED_AMOUNT", new BigDecimal("5000"));
        c.put("ROUNDING_RULE_KEY", "NEXT_10");
        when(named.queryForMap(
                        contains("FROM wage_revision_component"), any(SqlParameterSource.class)))
                .thenReturn(c);
        when(named.queryForMap(contains("COUNT(*) AS n"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("N", 2L, "TOTAL", BigDecimal.TEN));
        when(named.queryForObject(
                        contains("SELECT instalments_allowed"),
                        any(SqlParameterSource.class),
                        eq(Number.class)))
                .thenReturn((Number) instalments);
        when(named.query(
                        contains("FROM wage_revision w"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<WageRevisionService.Summary>>any()))
                .thenAnswer(
                        call ->
                                List.of(
                                        ((RowMapper<?>) call.getArgument(2))
                                                .mapRow(summaryRow(), 0)));
    }

    @Test
    void SRS_8_25_aDefinitionIsRecordedWithItsNineStepsAndTheOrderLinked() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(named.queryForObject(
                        contains("SELECT wage_revision_id FROM"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(null);
        detailReads("PERCENTAGE", null);
        WageRevisionService.Detail d =
                service.define(
                        hq,
                        def(
                                " Pay revision ",
                                List.of("STAFF_IDA", "STAFF_CDA"),
                                "ALL_INDIA",
                                FACTOR,
                                null,
                                " Order "));
        assertThat(d.instalments()).as("no instalments recorded: one").isEqualTo(1);
        assertThat(d.rule()).isEqualTo("PERCENTAGE");
        verify(named, org.mockito.Mockito.times(9))
                .update(contains("INSERT INTO wage_revision_step"), any(SqlParameterSource.class));
        // I-25 field table (SRS l.3164): the order is checked as an Active Wage Revision circular.
        verify(circulars).requireActive(3L, "WAGE_REVISION");
        verify(circulars)
                .link(
                        eq("su@x"),
                        eq(3L),
                        eq(Circulars.LinkType.TRANSACTION),
                        eq("wage_revision"),
                        anyString(),
                        eq("I-25"));
    }

    private void revision(String status) {
        when(named.queryForList(
                        contains("FROM wage_revision WHERE wage_revision_id"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "REVISION_CODE",
                                        "WR-2027-0005",
                                        "REVISION_STATUS",
                                        status,
                                        "PAYROLL_TYPES",
                                        "STAFF_IDA,STAFF_CDA")));
    }

    @Test
    void SRS_8_25_onlyADefinedOrSimulatedRevisionIsSimulatedAndASecondRunRefreshesTheRows() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        when(named.queryForList(
                        contains("FROM wage_revision WHERE wage_revision_id"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(refusal(() -> service.simulate(hq, 5L))).isEqualTo(CommonErrorCode.NOT_FOUND);
        revision("APPROVED");
        assertThat(refusal(() -> service.simulate(hq, 5L))).isEqualTo(ArrearErrorCode.WR_STATE);
        revision("SIMULATED");
        detailReads("FIXED_AMOUNT", 3);
        when(named.queryForList(contains("FROM employee WHERE"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "EMPLOYEE_ID",
                                        7L,
                                        "LOCATION_ID",
                                        10L,
                                        "CURRENT_BASIC",
                                        new BigDecimal("50000")),
                                Map.of(
                                        "EMPLOYEE_ID",
                                        8L,
                                        "LOCATION_ID",
                                        10L,
                                        "CURRENT_BASIC",
                                        new BigDecimal("60000"))));
        when(named.queryForList(
                        contains("FROM wage_revision_member WHERE"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(List.of(7L));
        WageRevisionService.Detail d = service.simulate(hq, 5L);
        assertThat(d.instalments()).isEqualTo(3);
        verify(named)
                .batchUpdate(
                        contains("UPDATE wage_revision_member"),
                        any(MapSqlParameterSource[].class));
        verify(named)
                .batchUpdate(
                        contains("INSERT INTO wage_revision_member"),
                        any(MapSqlParameterSource[].class));

        revision("DEFINED");
        detailReads("FACTOR", 1);
        when(named.queryForList(contains("FROM employee WHERE"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        service.simulate(hq, 5L);
        verify(named, org.mockito.Mockito.times(1))
                .batchUpdate(
                        contains("UPDATE wage_revision_member"),
                        any(MapSqlParameterSource[].class));
    }

    @Test
    void SRS_8_25_onlyASimulatedRevisionGoesToTheCheckerWithARemark() {
        when(policy.isAtHeadquarters(hq)).thenReturn(true);
        assertThat(refusal(() -> service.submit(hq, 5L, null))).isEqualTo(ArrearErrorCode.REMARK);
        assertThat(refusal(() -> service.submit(hq, 5L, " "))).isEqualTo(ArrearErrorCode.REMARK);
        revision("DEFINED");
        assertThat(refusal(() -> service.submit(hq, 5L, "Order")))
                .isEqualTo(ArrearErrorCode.WR_STATE);
        revision("SIMULATED");
        service.submit(hq, 5L, " Order ");
        verify(approvals).submit(eq(hq), any(), any());
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                4L,
                "I-25",
                entity,
                "5",
                outcome,
                "check rates",
                null,
                "checker@x",
                Instant.now(),
                77L);
    }

    @Test
    void SRS_8_25_theCheckerApprovesWithTheOtpReturnsToSimulatedOrRejects() {
        service.on(decided("other", ApprovalStatus.APPROVED));
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));
        revision("PENDING_APPROVAL");
        service.on(decided(WageRevisionService.ENTITY, ApprovalStatus.APPROVED));
        verify(named)
                .update(
                        contains("SET revision_status = 'APPROVED'"),
                        any(SqlParameterSource.class));
        service.on(decided(WageRevisionService.ENTITY, ApprovalStatus.RETURNED));
        service.on(decided(WageRevisionService.ENTITY, ApprovalStatus.REJECTED));
        verify(named, org.mockito.Mockito.times(2))
                .update(contains("SET revision_status = :status"), any(SqlParameterSource.class));
        verify(audit, org.mockito.Mockito.times(3)).record(eq("checker@x"), any());
    }

    @Test
    void SRS_8_25_anUnknownRevisionIsNotFoundAndAUnitWithoutAnOfficeSeesNoMembers() {
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<WageRevisionService.Summary>>any()))
                .thenReturn(List.of());
        assertThat(refusal(() -> service.detail(hq, 9L))).isEqualTo(CommonErrorCode.NOT_FOUND);
        AccessContext nowhere =
                new AccessContext(
                        1L, "u@x", "U", "s", "P", null, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThat(refusal(() -> service.membersSize(nowhere, 5L)))
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        assertThat(service.membersSize(hq, 5L)).isZero();
    }

    private ResultSet memberRow(boolean named_) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("104882");
        when(rs.getString(2)).thenReturn(named_ ? "Rakesh" : null);
        when(rs.getString(3)).thenReturn(named_ ? "Sharma" : null);
        when(rs.getString(4)).thenReturn("DH001");
        when(rs.getString(5)).thenReturn("Delhi");
        return rs;
    }

    @Test
    void SRS_8_25_theMemberSheetExportsEveryRowAndAWriteFailureStopsIt() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(named.query(
                        anyString(),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<WageRevisionService.MemberRow>>any()))
                .thenAnswer(
                        call ->
                                List.of(
                                        ((RowMapper<?>) call.getArgument(2))
                                                .mapRow(memberRow(true), 0),
                                        ((RowMapper<?>) call.getArgument(2))
                                                .mapRow(memberRow(false), 1)));
        var page = service.members(hq, 5L, new in.gov.fci.payroll.platform.paging.PageSpec(0, 20));
        assertThat(page.items())
                .extracting(WageRevisionService.MemberRow::name)
                .containsExactly("Rakesh Sharma", "");
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            h.processRow(memberRow(true));
                            h.processRow(memberRow(false));
                            return null;
                        })
                .when(named)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
        service.exportMembers(hq, 5L, sink);
        verify(sink, org.mockito.Mockito.times(2)).row(any(Object[].class));
        ExportSink broken = mock(ExportSink.class);
        doAnswer(
                        call -> {
                            throw new java.io.IOException("disk full");
                        })
                .when(broken)
                .row(any(Object[].class));
        assertThatThrownBy(() -> service.exportMembers(hq, 5L, broken))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}
