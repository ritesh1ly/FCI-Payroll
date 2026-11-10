package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drawn I-05 reads (API-17-2; SRS-13-4, SRS-13-5, SRS-13-8, SRS-13-21): the worklist in one
 * list with a status per line, the counts, and a case's orders only for a member in scope.
 */
class IncrementWorklistServiceTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);
    private static final LocalDate DUE = MONTH.atDay(1);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final IncrementRunService run = mock(IncrementRunService.class);
    private final Scales scales = mock(Scales.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context = mock(AccessContext.class);
    private final IncrementWorklistService service =
            new IncrementWorklistService(
                    named, policy, run, scales, rules, documents, JsonMapper.builder().build());

    IncrementWorklistServiceTest() {
        when(context.dataScope()).thenReturn(Set.of(5L));
        when(named.queryForList(
                        contains("approval_queue WHERE entity_name"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        when(named.queryForObject(
                        contains("UNION ALL"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
    }

    private static Map<String, Object> line(String kind, LocalDate effective) {
        Map<String, Object> r = new HashMap<>();
        r.put("KIND", kind);
        r.put("ROW_KEY", 3L);
        r.put("EMPLOYEE_ID", 9L);
        r.put("EMP_NUM", "E1");
        r.put("FIRST_NAME", "Mohan");
        r.put("LAST_NAME", null);
        r.put("BAND_ID", 1L);
        r.put("BAND_CODE", "E-4");
        r.put("LOCATION_ID", 5L);
        r.put("ROW_TYPE", "ANNUAL");
        r.put("PRESENT_BASIC", new BigDecimal("60000"));
        r.put("REVISED_BASIC", new BigDecimal("62400"));
        r.put("EFFECTIVE", Date.valueOf(effective));
        r.put("ORDER_REFERENCE", null);
        r.put("ARREAR_EVENT", null);
        r.put("APPROVAL_ID", null);
        return r;
    }

    private void page(Map<String, Object> row) {
        when(named.queryForList(contains("SELECT w.*"), any(SqlParameterSource.class)))
                .thenReturn(List.of(row));
    }

    private void scale(BigDecimal max, String rule) {
        when(scales.of(eq(1L), any()))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(1L, "E-4", 2L, null, max, null, rule, List.of())));
    }

    private void proposal(List<Flag> flags) {
        when(run.propose(anyLong(), any(), any(), any()))
                .thenReturn(
                        new IncrementRunService.Proposal(
                                new BigDecimal("62400"),
                                flags,
                                List.of("PEN-1"),
                                "ANNUAL",
                                null,
                                null));
    }

    private IncrementWorklistService.Row only() {
        Paged<IncrementWorklistService.Row> p =
                service.worklist(context, MONTH, new PageSpec(0, 20));
        assertThat(p.items()).hasSize(1);
        return p.items().getFirst();
    }

    @Test
    void SRS_13_21_aMemberDueCarriesTheRunsProposal() {
        page(line("DUE", DUE));
        scale(new BigDecimal("90000"), null);
        proposal(List.of());

        IncrementWorklistService.Row r = only();

        assertThat(r.status()).isEqualTo("DUE");
        assertThat(r.revisedBasic()).isEqualByComparingTo("62400");
        assertThat(r.atMaximum()).isFalse();
        assertThat(r.incrementPct()).isNull();
    }

    @Test
    void SRS_13_8_aBarredMemberIsBarredAndAFlaggedOneFlagged() {
        page(line("DUE", DUE));
        scale(null, null);
        proposal(List.of(Flag.BARRED));
        assertThat(only().status()).isEqualTo("BARRED");
        assertThat(only().barredBy()).containsExactly("PEN-1");
        proposal(List.of(Flag.NOT_A_STAGE));
        assertThat(only().status()).isEqualTo("FLAGGED");
    }

    @Test
    void SRS_13_5_aDuplicateIsBlockedAndAWaitingOneIsWithTheChecker() {
        page(line("DUE", DUE));
        scale(null, null);
        proposal(List.of());
        when(run.exists(9L, "2026-27", "ANNUAL")).thenReturn(true);
        assertThat(only().status()).isEqualTo("DUPLICATE");
        when(run.exists(9L, "2026-27", "ANNUAL")).thenReturn(false);
        when(run.waiting(9L, DUE)).thenReturn(true);
        assertThat(only().status()).isEqualTo("CHECKER");
    }

    @Test
    void SRS_13_4_aDueDateBeforeTheMonthIsRetrospective() {
        page(line("DUE", DUE.minusMonths(2)));
        scale(new BigDecimal("60000"), null);
        proposal(List.of());

        IncrementWorklistService.Row r = only();

        assertThat(r.status()).isEqualTo("RETRO");
        assertThat(r.atMaximum()).isTrue();
    }

    @Test
    void SRS_13_4_aDecidedFixationShowsItsArrearEventAndAmount() {
        Map<String, Object> row = line("FIXATION", DUE.minusMonths(3));
        row.put("ARREAR_EVENT", 129L);
        row.put("APPROVAL_ID", 8L);
        row.put("ORDER_REFERENCE", "DL/EST/118");
        page(row);
        scale(null, null);
        when(named.queryForObject(
                        contains("arrear_detail"),
                        any(SqlParameterSource.class),
                        eq(BigDecimal.class)))
                .thenReturn(new BigDecimal("164250"));

        IncrementWorklistService.Row r = only();

        assertThat(r.status()).isEqualTo("RETRO");
        assertThat(r.arrearEvent()).isEqualTo(129L);
        assertThat(r.arrearAmount()).isEqualByComparingTo("164250");
        assertThat(r.orderReference()).isEqualTo("DL/EST/118");
        assertThat(r.approvalId()).isEqualTo(8L);
    }

    @Test
    void SRS_13_20_aTaskFromHrmsAndAnApprovedIncrementReadTheirOwnStatus() {
        page(line("HRMS_TASK", DUE));
        scale(null, null);
        assertThat(only().status()).isEqualTo("HRMS_TASK");
        page(line("APPLIED", DUE));
        assertThat(only().status()).isEqualTo("APPROVED");
    }

    @Test
    void API_17_2_theRuleValueComesFromTheRuleEngineAndAnUnsetRuleIsNoValue() {
        page(line("APPLIED", DUE));
        scale(null, "INCR_E4");
        RuleEngine.Resolved resolved = mock(RuleEngine.Resolved.class);
        when(resolved.scalar()).thenReturn(new BigDecimal("3"));
        when(rules.resolve(eq("INCR_E4"), any(), any())).thenReturn(Optional.of(resolved));
        assertThat(only().incrementPct()).isEqualByComparingTo("3");
        when(rules.resolve(eq("INCR_E4"), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(only().incrementPct()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void SRS_13_18_aCaseWithTheCheckerComesFirstAndAnotherOfficesIsLeftOut() {
        when(named.queryForList(
                        contains("approval_queue WHERE entity_name"),
                        any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "APPROVAL_ID",
                                        4L,
                                        "ENTITY_KEY",
                                        "K1",
                                        "EFFECTIVE_DATE",
                                        Date.valueOf(DUE),
                                        "PAYLOAD",
                                        "{\"employeeId\":2,\"caseType\":\"INDIVIDUAL_REVISION\","
                                                + "\"old\":\"60000\",\"revised\":\"\","
                                                + "\"order\":\"O/1\"}"),
                                Map.of(
                                        "APPROVAL_ID",
                                        5L,
                                        "ENTITY_KEY",
                                        "K2",
                                        "EFFECTIVE_DATE",
                                        Date.valueOf(DUE),
                                        "PAYLOAD",
                                        "{\"employeeId\":3}")));
        when(named.queryForList(
                        contains("FROM employee e LEFT JOIN"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(Map.of("EMP_NUM", "E2", "BAND_CODE", "E-3", "FIRST_NAME", "A")),
                        List.of());
        when(named.queryForList(contains("SELECT w.*"), any(SqlParameterSource.class)))
                .thenReturn(List.of());

        Paged<IncrementWorklistService.Row> p =
                service.worklist(context, MONTH, new PageSpec(0, 20));

        assertThat(p.total()).isEqualTo(2);
        assertThat(p.items()).hasSize(1);
        IncrementWorklistService.Row r = p.items().getFirst();
        assertThat(r.kind()).isEqualTo("CASE");
        assertThat(r.status()).isEqualTo("CHECKER");
        assertThat(r.revisedBasic()).isNull();
        assertThat(r.approvalId()).isEqualTo(4L);
    }

    @Test
    void SRS_13_21_theTilesCountTheMonthAndAtMaximumIsStagnation() {
        when(named.queryForObject(any(String.class), any(SqlParameterSource.class), eq(Long.class)))
                .thenAnswer(
                        c -> {
                            String sql = c.getArgument(0);
                            if (sql.contains("MAX(a.arrear_event_id)")) return 129L;
                            if (sql.contains("increment_due_date = d.next_due_date")) return 1L;
                            if (sql.contains("hrms_change_task")) return 4L;
                            if (sql.contains("arrear_event a")) return 2L;
                            if (sql.contains("employee_increment_due")) {
                                return sql.contains("max_basic") ? 3L : 10L;
                            }
                            return 12L;
                        });

        IncrementWorklistService.Counts c = service.counts(context, MONTH);

        assertThat(c.due()).isEqualTo(10);
        assertThat(c.stagnation()).isEqualTo(3);
        assertThat(c.annual()).isEqualTo(7);
        assertThat(c.fixations()).isEqualTo(4);
        assertThat(c.retrospective()).isEqualTo(2);
        assertThat(c.latestArrearEvent()).isEqualTo(129L);
        assertThat(c.atMaximum()).isEqualTo(12);
        assertThat(c.duplicates()).isEqualTo(1);
    }

    @Test
    void CLAUDE_6_3_noScreenRightNoRead() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, "I-05", "VIEW");

        assertThatThrownBy(() -> service.counts(context, MONTH)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.worklist(context, MONTH, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.orders(context, 1L)).isInstanceOf(FciException.class);
    }

    private void caseItem() {
        when(named.queryForList(contains("WHERE approval_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of(Map.of("ENTITY_KEY", "K1", "PAYLOAD", "{\"employeeId\":2}")));
    }

    @Test
    void SRS_13_18_aCaseOfAMemberOutsideTheOfficeIsNotFoundAndNoOtherEntityIsRead() {
        when(named.queryForList(contains("WHERE approval_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.orders(context, 1L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));

        caseItem();
        when(named.queryForObject(
                        contains("FROM employee e WHERE"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        assertThatThrownBy(() -> service.orderFile(context, "1.1.1.1", 1L, 11L))
                .isInstanceOf(FciException.class);
        verify(documents, never()).active(any(), any());
    }

    @Test
    void SRS_13_18_theOrdersAreThoseOfTheCaseInScope() {
        caseItem();
        when(named.queryForObject(
                        contains("FROM employee e WHERE"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        when(context.loginId()).thenReturn("hr");

        service.orders(context, 1L);
        service.orderFile(context, "1.1.1.1", 1L, 11L);

        verify(documents).active("pay_fixation_case", "K1");
        verify(documents).read("hr", "1.1.1.1", "pay_fixation_case", "K1", 11L);
    }
}
