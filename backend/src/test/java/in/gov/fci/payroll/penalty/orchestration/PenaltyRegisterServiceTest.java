package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.penalty.persistence.PenaltyRepositories;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The drawn I-06 reads (API-12-3; SRS-14-10, SRS-14-12): every one of them is limited to the
 * session's unit; a request or an order of another office answers 404, the counts never reach
 * another office's rows, and the order copy is read as a file of its own order.
 */
class PenaltyRegisterServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final PenaltyQueryService queries = mock(PenaltyQueryService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyRegisterService service =
            new PenaltyRegisterService(
                    named, queries, org, documents, JsonMapper.builder().build());

    PenaltyRegisterServiceTest() {
        when(queries.scope(context)).thenReturn(3L);
        when(org.subtree(3L)).thenReturn(Set.of(3L, 5L));
    }

    private MapSqlParameterSource paramsOf(String sqlPart) {
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForMap(contains(sqlPart), p.capture());
        return p.getValue();
    }

    @Test
    void SRS_14_10_theCountsAreOfTheSessionsUnitOnly() {
        when(named.queryForMap(contains("FROM penalty_order o"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("N", 3L, "OUTSTANDING", new BigDecimal("16000"), "FNF", 1L));
        when(named.queryForMap(
                        contains("FROM over_and_above_payment"), any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of("N", 2L, "WAITING", 1L, "WAITING_AMOUNT", new BigDecimal("40000")));
        when(named.queryForObject(
                        contains("net_pay_floor_override"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(4L);
        when(named.queryForObject(
                        contains("arrear_detail"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);

        PenaltyRegisterService.Counts c = service.counts(context);

        assertThat(c.orders()).isEqualTo(3);
        assertThat(c.outstanding()).isEqualByComparingTo("16000");
        assertThat(c.paymentsAwaiting()).isEqualTo(1);
        assertThat(c.minNetGuard()).isEqualTo(4);
        assertThat(c.incentiveRecovery()).isZero();
        assertThat(c.carriedToFnf()).isEqualTo(1);
        MapSqlParameterSource p = paramsOf("FROM penalty_order o");
        assertThat(p.getValue("all")).isEqualTo("N");
        assertThat(p.getValue("locations")).isEqualTo(Set.of(3L, 5L));
    }

    @Test
    void CLAUDE_6_3_noUnitMeansNoCounts() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED)).when(queries).scope(context);

        assertThatThrownBy(() -> service.counts(context)).isInstanceOf(FciException.class);
        verify(named, never()).queryForMap(any(), any(SqlParameterSource.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void SRS_14_15_theRegisterPagesOverRequestsOrdersAndPayments() {
        when(named.queryForObject(
                        contains("UNION ALL"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(7L);
        when(named.<PenaltyRegisterService.RegisterRow>query(
                        contains("ORDER BY r.rank_no"),
                        any(SqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of());

        assertThat(service.register(context, new PageSpec(1, 20)).total()).isEqualTo(7);
    }

    private void floorRequest() {
        when(named.queryForList(contains("I06_FLOOR"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "APPROVAL_ID",
                                        9L,
                                        "EMPLOYEE_ID",
                                        2L,
                                        "EMP_NUM",
                                        "881305",
                                        "FIRST_NAME",
                                        "Tapan",
                                        "LAST_NAME",
                                        "Bose",
                                        "PAYLOAD",
                                        "{\"penaltyRef\":\"PEN-1\",\"latestNet\":12000,"
                                                + "\"netAfter\":-7767,\"floor\":null}")));
    }

    @Test
    void SRS_14_10_theGuardShowsTheFiguresTheMakersCheckSaved() {
        floorRequest();
        when(named.queryForList(contains("FROM payroll_result"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "GROSS_EARNINGS", new BigDecimal("64936"),
                                        "TOTAL_DEDUCTIONS", new BigDecimal("52936"))));

        PenaltyRegisterService.MinNetGuard g = service.guard(context, null).orElseThrow();

        assertThat(g.reference()).isEqualTo("PEN-1");
        assertThat(g.proposedInstalment()).isEqualByComparingTo("19767");
        assertThat(g.resultingNet()).isEqualByComparingTo("-7767");
        assertThat(g.gross()).isEqualByComparingTo("64936");
        // R-RULE: no floor configured stays unset, never a guess.
        assertThat(g.floor()).isNull();
    }

    @Test
    void SRS_14_10_aMemberWithNoPayShowsNoGross() {
        floorRequest();
        when(named.queryForList(contains("FROM payroll_result"), any(SqlParameterSource.class)))
                .thenReturn(List.of());

        PenaltyRegisterService.MinNetGuard g = service.guard(context, 9L).orElseThrow();

        assertThat(g.gross()).isNull();
        assertThat(g.existingDeductions()).isNull();
    }

    @Test
    void SRS_14_10_noRequestWaitingIsEmptyAndAnotherOfficesIsNotFound() {
        when(named.queryForList(contains("I06_FLOOR"), any(SqlParameterSource.class)))
                .thenReturn(List.of());

        assertThat(service.guard(context, null)).isEmpty();
        assertThatThrownBy(() -> service.guard(context, 9L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_14_12_anOrderCopyOutsideTheUnitIsNotFound() {
        when(queries.detail(context, 7L)).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service.orderCopies(context, 7L)).isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.orderCopy(context, "1.1.1.1", 7L, 1L))
                .isInstanceOf(FciException.class);
        verify(documents, never()).active(any(), any());
    }

    @Test
    void SRS_14_12_theCopyIsReadAsAFileOfItsOwnOrder() {
        PenaltyQueryService.OrderRow row = mock(PenaltyQueryService.OrderRow.class);
        when(row.penaltyRef()).thenReturn("PEN-1");
        when(queries.detail(context, 7L))
                .thenReturn(new PenaltyQueryService.Detail(row, List.of(), List.of()));
        when(context.loginId()).thenReturn("maker");
        when(named.queryForObject(
                        contains("FROM penalty_order"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(2L);

        service.orderCopies(context, 7L);
        service.orderCopy(context, "1.1.1.1", 7L, 11L);

        verify(documents).active(PenaltyOrderService.ENTITY, "2|PEN-1");
        verify(documents).read("maker", "1.1.1.1", PenaltyOrderService.ENTITY, "2|PEN-1", 11L);
    }

    @Test
    void SRS_14_16_aRevokeOfAnOrderOutsideTheUnitIsNotFound() {
        PenaltyQueryService scoped = mock(PenaltyQueryService.class);
        when(scoped.detail(context, 7L)).thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        PenaltyChangeService changes =
                new PenaltyChangeService(
                        mock(PenaltyRepositories.Orders.class),
                        mock(PenaltyRepositories.Lines.class),
                        mock(PenaltyRepositories.Revokes.class),
                        scoped,
                        documents,
                        mock(MakerChecker.class),
                        mock(in.gov.fci.payroll.general.api.ExceptionWorklist.class),
                        mock(in.gov.fci.payroll.audit.api.AuditService.class),
                        mock(in.gov.fci.payroll.arrear.api.RetroEvents.class),
                        mock(in.gov.fci.payroll.penalty.api.PenaltyPayEffects.class),
                        mock(in.gov.fci.payroll.configuration.api.ReferenceData.class),
                        JsonMapper.builder().build(),
                        Clock.systemUTC());

        assertThatThrownBy(() -> changes.cancel(context, 7L, "x", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> changes.revoke(context, 7L, null, null))
                .isInstanceOf(FciException.class);
        assertThat(Optional.empty()).isEmpty();
    }
}
