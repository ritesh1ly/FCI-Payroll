package in.gov.fci.payroll.reporting.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.i18n.MessageBundles;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportErrorCode;
import in.gov.fci.payroll.reporting.business.ReportRules.Level;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * O-04 Component-wise Summary (plan 15; SRS-18-8, SRS-18-18, SRS-18-23): a month and level are
 * required, each level reads its own grouping, a mismatch with the posted payroll halts the
 * release, a provisional month exports as such, and an export needs an approved version.
 */
@SuppressWarnings("unchecked")
class ComponentSummaryServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory directory = mock(OrgDirectory.class);
    private final MessageBundles bundles = mock(MessageBundles.class);
    private final ComponentSummaryService service =
            new ComponentSummaryService(
                    named,
                    jdbc,
                    policy,
                    directory,
                    bundles,
                    Clock.fixed(Instant.parse("2026-11-01T04:30:00Z"), ZoneOffset.UTC));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "maker.delhi",
                    "Maker",
                    "s",
                    "P",
                    3L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(3L));

    private void results(
            String earning,
            String deduction,
            String postedGross,
            String postedDeductions,
            long cycles,
            long locked) {
        when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(
                        List.of(
                                new ComponentSummaryService.Line(
                                        "DH001",
                                        "Delhi",
                                        "IDA",
                                        101,
                                        "Basic",
                                        "EARNING",
                                        new BigDecimal(earning),
                                        3),
                                new ComponentSummaryService.Line(
                                        "DH001",
                                        "Delhi",
                                        "IDA",
                                        701,
                                        "CPF",
                                        "DEDUCTION",
                                        new BigDecimal(deduction),
                                        3)));
        when(named.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "GROSS",
                                new BigDecimal(postedGross),
                                "DEDUCTIONS",
                                new BigDecimal(postedDeductions),
                                "CYCLES",
                                cycles,
                                "LOCKED",
                                locked));
    }

    @BeforeEach
    void wire() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO report_output", 31L);
        results("1000", "100", "1000", "100", 1, 1);
        when(bundles.messages(any())).thenReturn(Map.of());
    }

    @Test
    void SRS_18_12_aMonthAndALevelAreRequiredAndEachLevelIsRead() {
        assertThatThrownBy(() -> service.summary(context, "2026-10", Level.UNIT))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.summary(context, "202610", null))
                .isInstanceOf(FciException.class);
        for (Level level : Level.values()) {
            ComponentSummaryService.Summary s = service.summary(context, "202610", level);
            assertThat(s.level()).isEqualTo(level.name());
            assertThat(s.controlTotalsMatch()).isTrue();
            assertThat(s.provisional()).isFalse();
        }
        verify(named)
                .query(
                        contains("JOIN org_location g"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class));
        verify(named)
                .query(
                        contains("JOIN org_zone z"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class));
    }

    @Test
    void SRS_18_8_aDeductionMismatchHaltsTheReleaseBeforeAnyByte() {
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "Y")));
        results("1000", "100", "1000", "90", 1, 1);
        assertThatThrownBy(() -> service.requireReleasable(context, "202610", Level.UNIT))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.CONTROL_TOTALS);
        results("999", "100", "1000", "100", 1, 1);
        assertThatThrownBy(() -> service.requireReleasable(context, "202610", Level.UNIT))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.CONTROL_TOTALS);
        // A locked month whose totals match is releasable.
        results("1000", "100", "1000", "100", 1, 1);
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> service.requireReleasable(context, "202610", Level.UNIT))
                .doesNotThrowAnyException();
    }

    /**
     * FRS V0.5 l.7130: a provisional (pre-lock) export needs every cycle it reads verified; a
     * computed cycle refuses as Blocked, a verified one releases provisionally.
     */
    @Test
    void FRS_l7130_aProvisionalReportNeedsAVerifiedCycle() {
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "N")));
        results("1000", "100", "1000", "100", 1, 0);
        when(named.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "GROSS", new BigDecimal("1000"),
                                "DEDUCTIONS", new BigDecimal("100"),
                                "CYCLES", 1L,
                                "LOCKED", 0L,
                                "VERIFIED", 0L));
        assertThatThrownBy(() -> service.requireReleasable(context, "202610", Level.UNIT))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.BLOCKED);
        when(named.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "GROSS", new BigDecimal("1000"),
                                "DEDUCTIONS", new BigDecimal("100"),
                                "CYCLES", 1L,
                                "LOCKED", 0L,
                                "VERIFIED", 1L));
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> service.requireReleasable(context, "202610", Level.UNIT))
                .doesNotThrowAnyException();
    }

    private void versions(List<Map<String, Object>> rows) {
        when(jdbc.queryForList(
                        contains("v.approval_state = 'APPROVED' AND v.is_blocked = 'N'"),
                        any(Object[].class)))
                .thenReturn(rows);
    }

    @Test
    void FRS_V05_23_9_anOnScreenGenerationIsRecordedWithItsVersionAndNoExportLog() {
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "Y")));
        results("1000", "100", "1000", "100", 1, 0);
        assertThat(service.summary(context, "202610", Level.UNIT).provisional()).isTrue();
        List<Object> output = GeneratedKeysStub.args(jdbc, "INSERT INTO report_output");
        assertThat(output.subList(0, 4))
                .containsExactly(
                        5L,
                        ComponentSummaryService.KEY,
                        "202610",
                        "{\"yymm\":\"202610\",\"level\":\"UNIT\"}");
        assertThat(output.get(5)).isEqualTo("maker.delhi");
        assertThat(output.subList(7, 10)).containsExactly("N", "Y", "Y");
        verify(jdbc, never())
                .update(contains("INSERT INTO report_export_log"), any(), any(), any(), any());
    }

    @Test
    void FRS_V05_23_9_withoutAVersionInForceTheScreenShowsTheMonthAndNothingIsRecorded() {
        versions(List.of());
        assertThat(service.summary(context, "202610", Level.UNIT).lines()).hasSize(2);
        verify(jdbc, never())
                .update(
                        org.mockito.ArgumentMatchers.argThat(
                                GeneratedKeysStub.insertOf("INSERT INTO report_output")),
                        any(org.springframework.jdbc.support.KeyHolder.class));
    }

    @Test
    void SRS_18_24_anExportNeedsAnApprovedVersionInForceAndALockedMonthWhenTheVersionSaysSo()
            throws Exception {
        results("1000", "100", "1000", "100", 2, 1);
        ExportSink sink = mock(ExportSink.class);
        // US-GAP-35: none approved, unblocked and in force for the month: Blocked.
        versions(List.of());
        assertThatThrownBy(() -> service.export(context, "202610", Level.UNIT, "CSV", sink))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.BLOCKED);
        verify(jdbc)
                .queryForList(
                        contains("v.effective_from <= ? AND (v.effective_to IS NULL"),
                        eq("COMPONENT_WISE_SUMMARY"),
                        eq(java.sql.Date.valueOf("2026-10-01")),
                        eq(java.sql.Date.valueOf("2026-10-01")));
        // US-GAP-36: the version needs the period locked and the month is not: Blocked.
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "Y")));
        assertThatThrownBy(() -> service.export(context, "202610", Level.UNIT, "CSV", sink))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(ReportErrorCode.BLOCKED);
        verify(sink, org.mockito.Mockito.never()).header(any(String[].class));
    }

    @Test
    void SRS_18_23_aVersionWithoutThePeriodLockExportsAnOpenMonthAsProvisional() throws Exception {
        results("1000", "100", "1000", "100", 2, 1);
        ExportSink sink = mock(ExportSink.class);
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "N")));
        service.export(context, "202610", Level.ALL, "XLSX", sink);
        verify(sink)
                .row(
                        "PROVISIONAL",
                        "TOTAL",
                        "",
                        "",
                        "",
                        "Earnings",
                        "EARNING",
                        new BigDecimal("1000"),
                        "");
        verify(jdbc)
                .update(
                        contains("INSERT INTO report_export_log"),
                        any(),
                        eq("maker.delhi"),
                        eq("EXCEL"),
                        any());
        List<Object> output = GeneratedKeysStub.args(jdbc, "INSERT INTO report_output");
        assertThat(output.get(0)).isEqualTo(5L);
        assertThat(output.get(2)).isEqualTo("202610");
        assertThat(output.get(5)).isEqualTo("maker.delhi");
        assertThat(output.subList(7, 10)).containsExactly("N", "Y", "Y");
    }

    private static ComponentSummaryService.Line line(
            String level, String type, int code, String kind, String amount) {
        return new ComponentSummaryService.Line(
                level, level + " office", type, code, "C" + code, kind, new BigDecimal(amount), 1);
    }

    /** Every row the sink was given, the title lines first, as text. */
    private static List<String> written(ExportSink sink) throws Exception {
        List<String> out = new ArrayList<>();
        doAnswer(
                        call -> {
                            for (Object o : call.getArguments()) {
                                if (o instanceof String[] lines) {
                                    out.addAll(List.of(lines));
                                } else {
                                    out.add(String.valueOf(o));
                                }
                            }
                            return null;
                        })
                .when(sink)
                .title(any(String[].class));
        doAnswer(
                        call -> {
                            out.add(java.util.Arrays.toString(call.getArguments()));
                            return null;
                        })
                .when(sink)
                .row(any(Object[].class));
        return out;
    }

    /**
     * SRS-18-12 (a) (US-GAP-37): the office, payroll type, category and member status chosen reach
     * the query; the office must be in the user's scope and stands for the offices under it.
     */
    @Test
    void SRS_18_12_theParametersChosenNarrowTheSummary() {
        when(directory.subtree(7L)).thenReturn(Set.of(7L, 8L));
        service.summary(
                context,
                "202610",
                Level.UNIT,
                new ComponentSummaryService.Filters(
                        7L, "STAFF", "III", ComponentSummaryService.MemberStatus.SEPARATED));
        verify(policy).requireAt(context, "O-04", "VIEW", 7L);
        verify(named)
                .query(
                        contains("em.separation_date <= :monthEnd"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "Y".equals(p.getValue("office"))
                                                && Set.of(7L, 8L).equals(p.getValue("offices"))
                                                && "STAFF".equals(p.getValue("type"))
                                                && "III".equals(p.getValue("category"))
                                                && "SEPARATED".equals(p.getValue("status"))
                                                && java.sql.Date.valueOf("2026-10-31")
                                                        .equals(p.getValue("monthEnd"))),
                        any(RowMapper.class));
    }

    /**
     * SRS-18-12 (US-GAP-265): a member range reaches the query (blank ends are none) and the lines
     * are sorted by amount, largest first within the office, when asked; the pay-code order stays
     * the default.
     */
    @Test
    void SRS_18_12_aMemberRangeNarrowsTheSummaryAndTheLinesSortByAmount() {
        results("50", "900", "50", "900", 1, 1);
        ComponentSummaryService.Summary byAmount =
                service.summary(
                        context,
                        "202610",
                        Level.UNIT,
                        new ComponentSummaryService.Filters(
                                null,
                                null,
                                null,
                                null,
                                " 104000 ",
                                "",
                                ComponentSummaryService.Sort.AMOUNT));
        assertThat(byAmount.lines())
                .extracting(ComponentSummaryService.Line::payCode)
                .containsExactly(701, 101);
        verify(named)
                .query(
                        contains("ef.emp_num >= :fromEis"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "104000".equals(p.getValue("fromEis"))
                                                && p.getValue("toEis") == null),
                        any(RowMapper.class));
        ComponentSummaryService.Summary byCode =
                service.summary(
                        context,
                        "202610",
                        Level.UNIT,
                        new ComponentSummaryService.Filters(
                                null, null, null, null, null, "109999", null));
        assertThat(byCode.lines())
                .extracting(ComponentSummaryService.Line::payCode)
                .containsExactly(101, 701);
    }

    @Test
    void SRS_18_12_withoutParametersEveryOfficeTypeCategoryAndMemberIsRead() {
        service.summary(
                context,
                "202602",
                Level.ALL,
                new ComponentSummaryService.Filters(null, null, null, null));
        verify(policy, never()).requireAt(any(), any(), any(), any());
        verify(named)
                .query(
                        anyString(),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "N".equals(p.getValue("office"))
                                                && List.of(-1L).equals(p.getValue("offices"))
                                                && p.getValue("type") == null
                                                && "BOTH".equals(p.getValue("status"))
                                                && java.sql.Date.valueOf("2026-02-28")
                                                        .equals(p.getValue("monthEnd"))),
                        any(RowMapper.class));
        // An office whose subtree is empty matches nothing rather than everything.
        when(directory.subtree(9L)).thenReturn(Set.of());
        service.summary(
                context,
                "202602",
                Level.ALL,
                new ComponentSummaryService.Filters(
                        9L, null, null, ComponentSummaryService.MemberStatus.CURRENT));
        verify(named)
                .query(
                        anyString(),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "Y".equals(p.getValue("office"))
                                                && List.of(-1L).equals(p.getValue("offices"))
                                                && "CURRENT".equals(p.getValue("status"))),
                        any(RowMapper.class));
    }

    @Test
    void SRS_18_12_anOfficeOutsideTheScopeIsRefused() {
        doThrow(new FciException(in.gov.fci.payroll.platform.error.CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .requireAt(context, "O-04", "EXPORT", 99L);
        assertThatThrownBy(
                        () ->
                                service.requireReleasable(
                                        context,
                                        "202610",
                                        Level.UNIT,
                                        new ComponentSummaryService.Filters(99L, null, null, null)))
                .isInstanceOf(FciException.class);
    }

    /**
     * SRS-18-12 (c), (d) (US-GAP-37): the export starts with the report header (name, period,
     * scope, run date-time, user), then the lines sorted by office and payroll type with a
     * sub-total for each payroll type of an office and each office, then the grand total.
     */
    @Test
    void SRS_18_12_theExportCarriesTheReportHeaderAndSubTotalsAtEachLevel() throws Exception {
        when(named.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(
                        List.of(
                                line("DH001", "STAFF", 101, "EARNING", "100"),
                                line("DH001", "STAFF", 701, "DEDUCTION", "10"),
                                line("DH001", "DEPT", 101, "EARNING", "50"),
                                line("KL001", "STAFF", 101, "EARNING", "200")));
        when(named.queryForMap(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "GROSS",
                                new BigDecimal("350"),
                                "DEDUCTIONS",
                                new BigDecimal("10"),
                                "CYCLES",
                                2L,
                                "LOCKED",
                                2L));
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "Y")));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getCode()).thenReturn("NZ01");
        when(office.getName()).thenReturn("North Zone");
        when(directory.location(4L)).thenReturn(Optional.of(office));
        when(directory.subtree(4L)).thenReturn(Set.of(3L, 4L));
        ExportSink sink = mock(ExportSink.class);
        List<String> out = written(sink);
        service.export(
                context,
                "202610",
                Level.UNIT,
                new ComponentSummaryService.Filters(
                        4L, "STAFF", "III", ComponentSummaryService.MemberStatus.CURRENT),
                "en",
                "CSV",
                sink);
        assertThat(out.subList(0, 5))
                .containsExactly(
                        "Component-wise Summary",
                        "Period: 10-2026",
                        "Scope: NZ01 - North Zone; UNIT; Payroll type STAFF; Category III; CURRENT",
                        "Run: 01-11-2026 10:00 IST",
                        "User: maker.delhi");
        List<String> rows = out.subList(5, out.size());
        assertThat(rows)
                .containsExactly(
                        "[FINAL, DH001, DH001 office, STAFF, 101, C101, EARNING, 100, 1]",
                        "[FINAL, DH001, DH001 office, STAFF, 701, C701, DEDUCTION, 10, 1]",
                        "[FINAL, DH001, DH001 office, STAFF, , Sub-total Earnings, EARNING, 100, ]",
                        "[FINAL, DH001, DH001 office, STAFF, , Sub-total Deductions, DEDUCTION, 10,"
                                + " ]",
                        "[FINAL, DH001, DH001 office, DEPT, 101, C101, EARNING, 50, 1]",
                        "[FINAL, DH001, DH001 office, DEPT, , Sub-total Earnings, EARNING, 50, ]",
                        "[FINAL, DH001, DH001 office, DEPT, , Sub-total Deductions, DEDUCTION, 0,"
                                + " ]",
                        "[FINAL, DH001, DH001 office, , , Sub-total Earnings, EARNING, 150, ]",
                        "[FINAL, DH001, DH001 office, , , Sub-total Deductions, DEDUCTION, 10, ]",
                        "[FINAL, KL001, KL001 office, STAFF, 101, C101, EARNING, 200, 1]",
                        "[FINAL, KL001, KL001 office, STAFF, , Sub-total Earnings, EARNING, 200, ]",
                        "[FINAL, KL001, KL001 office, STAFF, , Sub-total Deductions, DEDUCTION, 0,"
                                + " ]",
                        "[FINAL, KL001, KL001 office, , , Sub-total Earnings, EARNING, 200, ]",
                        "[FINAL, KL001, KL001 office, , , Sub-total Deductions, DEDUCTION, 0, ]",
                        "[FINAL, TOTAL, , , , Earnings, EARNING, 350, ]",
                        "[FINAL, TOTAL, , , , Deductions, DEDUCTION, 10, ]");
        List<Object> output = GeneratedKeysStub.args(jdbc, "INSERT INTO report_output");
        assertThat(output.get(0)).isEqualTo(5L);
        assertThat(output.get(2)).isEqualTo("202610");
        assertThat(output.get(3))
                .isEqualTo(
                        "{\"yymm\":\"202610\",\"level\":\"UNIT\",\"office\":4,"
                                + "\"payrollType\":\"STAFF\",\"category\":\"III\","
                                + "\"memberStatus\":\"CURRENT\"}");
        assertThat(output.get(5)).isEqualTo("maker.delhi");
        assertThat(output.subList(7, 10)).containsExactly("Y", "N", "Y");
    }

    /** SRS-G-40 (US-GAP-37): the export in Hindi takes its titles from the Hindi bundle. */
    @Test
    void SRS_G_40_theExportIsWrittenInTheLanguageChosen() throws Exception {
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "N")));
        when(bundles.messages("hi"))
                .thenReturn(
                        Map.of(
                                "o04.sum.title", "घटक-वार सारांश",
                                "o04.sum.col.amount", "राशि",
                                "o04.sum.total", "कुल",
                                "o04.sum.allOffices", " "));
        ExportSink sink = mock(ExportSink.class);
        List<String> out = written(sink);
        service.export(
                context,
                "202610",
                Level.ALL,
                ComponentSummaryService.Filters.NONE,
                "hi",
                "XLSX",
                sink);
        assertThat(out.get(0)).isEqualTo("घटक-वार सारांश");
        // A blank label falls back to English.
        assertThat(out.get(2))
                .startsWith("Scope: All offices in your Access Group scope; ALL; BOTH");
        verify(sink)
                .header(
                        "status",
                        "level_code",
                        "level",
                        "payroll_type",
                        "pay_code",
                        "component",
                        "type",
                        "राशि",
                        "members");
        assertThat(out.get(out.size() - 1)).startsWith("[FINAL, कुल");
        // An unknown language is written in English.
        ExportSink english = mock(ExportSink.class);
        service.export(
                context,
                "202610",
                Level.ALL,
                ComponentSummaryService.Filters.NONE,
                "fr",
                "XLSX",
                english);
        verify(bundles).messages("en");
    }

    @Test
    void SRS_18_12_theOptionsAreThePayrollTypesOfTheMonthAndTheCategoriesHeld() {
        when(named.queryForList(
                        contains("DISTINCT r.payroll_type"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("STAFF"));
        when(named.queryForList(
                        contains("category_code"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("I", "III"));
        when(named.query(
                        contains("FROM org_location"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                            when(rs.getLong(1)).thenReturn(3L);
                            when(rs.getString(2)).thenReturn("DH001");
                            when(rs.getString(3)).thenReturn("Delhi");
                            return List.of(m.mapRow(rs, 0));
                        });
        ComponentSummaryService.Options o = service.options(context, "202610");
        assertThat(o.offices())
                .containsExactly(new ComponentSummaryService.Office(3L, "DH001 - Delhi"));
        assertThat(o.payrollTypes()).containsExactly("STAFF");
        assertThat(o.categories()).containsExactly("I", "III");
        assertThatThrownBy(() -> service.options(context, "2026-10"))
                .isInstanceOf(FciException.class);
    }

    /** SRS l.3501: the screen opens on the latest locked month, or on none before any lock. */
    @Test
    void SRS_18_12_theDefaultMonthIsTheLatestLockedOneOrNone() {
        assertThat(service.latestLocked(context).yymm()).isNull();
        when(named.queryForList(
                        contains("MAX(payroll_yymm)"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("202609"));
        assertThat(service.latestLocked(context).yymm()).isEqualTo("202609");
    }

    /** SRS-18-12 (US-GAP-265): a blank member-range end is no end; a padded one is stripped. */
    @Test
    void SRS_18_12_aBlankMemberRangeEndIsNoEnd() {
        ComponentSummaryService.Filters f =
                new ComponentSummaryService.Filters(
                        null, null, null, null, "   ", " 109999 ", null);
        assertThat(f.fromEis()).isNull();
        assertThat(f.toEis()).isEqualTo("109999");
        assertThat(f.memberStatus()).isEqualTo(ComponentSummaryService.MemberStatus.BOTH);
        assertThat(f.sort()).isEqualTo(ComponentSummaryService.Sort.PAY_CODE);
    }

    /**
     * SRS-18-12: no parameters at all are every office, type, category and member (the summary is
     * shown; with no version in force nothing is recorded).
     */
    @Test
    void SRS_18_12_aSummaryWithoutFiltersReadsEveryMember() {
        versions(List.of());
        ComponentSummaryService.Summary s = service.summary(context, "202610", Level.UNIT, null);
        assertThat(s.lines()).hasSize(2);
        verify(policy, never()).requireAt(any(), any(), any(), any());
        verify(named)
                .query(
                        anyString(),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "N".equals(p.getValue("office"))
                                                && "BOTH".equals(p.getValue("status"))),
                        any(RowMapper.class));
    }

    /**
     * FRS V0.5 §23.9, SRS-18-8: an on-screen generation whose totals differ from the posted payroll
     * is recorded with control_totals_matched N (the release is what the mismatch halts).
     */
    @Test
    void FRS_V05_23_9_aMismatchedGenerationIsRecordedAsNotMatched() {
        versions(List.of(Map.of("REPORT_VERSION_ID", 5L, "PERIOD_LOCK_REQUIRED", "Y")));
        results("1000", "100", "1000", "90", 1, 1);
        assertThat(service.summary(context, "202610", Level.UNIT).controlTotalsMatch()).isFalse();
        List<Object> output = GeneratedKeysStub.args(jdbc, "INSERT INTO report_output");
        assertThat(output.subList(7, 10)).containsExactly("Y", "N", "N");
    }
}
