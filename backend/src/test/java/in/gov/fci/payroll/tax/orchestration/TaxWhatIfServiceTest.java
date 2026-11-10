package in.gov.fci.payroll.tax.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.orchestration.TaxWhatIfService.Claim;
import in.gov.fci.payroll.tax.orchestration.TaxWhatIfService.WhatIf;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The what-if forecast's checks (plan 08 phase 08-C09; SRS-7-28): nothing varied, a section code, a
 * duplicate, too many sections, amounts beyond paise or the column, negative where not signed, no
 * employee record; what a run passes to the computation. The computation is TaxWhatIfIT.
 */
class TaxWhatIfServiceTest {

    private static final EmployeeDirectory.Member MEMBER =
            new EmployeeDirectory.Member(7L, "870001", "Test Member", 3L, "IN_SERVICE");

    private final TaxComputationService computation = mock(TaxComputationService.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TaxWhatIfService service =
            new TaxWhatIfService(
                    computation,
                    employees,
                    policy,
                    jdbc,
                    JsonMapper.builder().build(),
                    Clock.fixed(Instant.parse("2026-10-26T05:00:00Z"), ZoneOffset.UTC));

    TaxWhatIfServiceTest() {
        when(employees.memberByEis("870001")).thenReturn(Optional.of(MEMBER));
        when(employees.member(7L)).thenReturn(Optional.of(MEMBER));
        when(computation.yearOr(any())).thenReturn("2026-27");
        when(computation.whatIf(any(), any(), any(), anyMap(), anyList()))
                .thenReturn(new TaxComputationService.Forecast[2]);
        doAnswer(
                        i -> {
                            KeyHolder key = i.getArgument(1);
                            ((GeneratedKeyHolder) key)
                                    .getKeyList()
                                    .add(Map.of("simulation_id", 41L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    private static AccessContext context(String login, Long employeeId) {
        return new AccessContext(
                2L,
                login,
                "User",
                "s",
                "P",
                3L,
                employeeId,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private static BigDecimal rs(String v) {
        return new BigDecimal(v);
    }

    private void refused(WhatIf change) {
        ThrowingCallable call =
                () -> service.forMember(context("maker", null), "870001", null, change);
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.WHAT_IF));
    }

    @Test
    void SRS_7_28_aChangeMustVarySomethingTheForecastCanTake() {
        refused(new WhatIf(null, null, null, null));
        refused(new WhatIf(null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO));
        refused(new WhatIf(null, Collections.singletonList(null), null, null));
        refused(new WhatIf(null, List.of(new Claim(null, rs("1"))), null, null));
        refused(new WhatIf(null, List.of(new Claim("80 C", rs("1"))), null, null));
        refused(new WhatIf(null, List.of(new Claim("80C", rs("-1"))), null, null));
        refused(new WhatIf(null, List.of(new Claim("80C", rs("1.005"))), null, null));
        refused(new WhatIf(null, List.of(new Claim("80C", null)), null, null));
        refused(
                new WhatIf(
                        null,
                        List.of(new Claim("80C", rs("1")), new Claim("80C", rs("2"))),
                        null,
                        null));
        List<Claim> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(new Claim("S" + i, rs("1")));
        }
        refused(new WhatIf(null, many, null, null));
        refused(new WhatIf(null, null, null, rs("-1")));
        refused(new WhatIf(null, null, rs("10000000000.00"), null));
        refused(new WhatIf(null, null, rs("0.001"), null));
    }

    @Test
    void SRS_7_28_aRunPassesTheChangeAndKeepsItsRecord() {
        TaxWhatIfService.Result r =
                service.forMember(
                        context("m".repeat(60), null),
                        "870001",
                        "2026-27",
                        new WhatIf(
                                Regime.NEW,
                                List.of(
                                        new Claim("80C", rs("150000")),
                                        new Claim("24(b)", rs("0"))),
                                rs("-9999999999.99"),
                                rs("5000.50")));
        assertThat(r.runId()).isEqualTo(41L);
        verify(policy).require(any(), eq("C-09"), eq("RUN"));
        verify(policy).requireRecordAt(any(), eq("C-09"), eq("RUN"), eq(3L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, BigDecimal>> claims = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaxComputationService.IncomeView>> extra =
                ArgumentCaptor.forClass(List.class);
        verify(computation)
                .whatIf(
                        eq(MEMBER),
                        eq("2026-27"),
                        eq(Regime.NEW),
                        claims.capture(),
                        extra.capture());
        assertThat(claims.getValue()).containsOnlyKeys("80C", "24(b)");
        assertThat(extra.getValue())
                .extracting(TaxComputationService.IncomeView::head)
                .containsExactly(TaxWhatIfService.SALARY_CHANGE, TaxWhatIfService.OTHER_INCOME);
    }

    @Test
    void SRS_7_28_theEmployeeRunsOnTheirOwnRecord() {
        service.mine(context("member", 7L), null, new WhatIf(Regime.OLD, null, null, null));
        verify(policy).require(any(), eq("E-01"), eq("SELF"));
        assertThatThrownBy(
                        () ->
                                service.mine(
                                        context("staff", null),
                                        null,
                                        new WhatIf(Regime.OLD, null, null, null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                service.forMember(
                                        context("maker", null),
                                        "999999",
                                        null,
                                        new WhatIf(Regime.OLD, null, null, null)))
                .isInstanceOf(FciException.class);
    }

    /**
     * SRS-7-28: the run record names its requester within rule_simulation.requested_by (50): a
     * login of exactly 50 is kept, a longer one cut to 50.
     */
    @Test
    void SRS_7_28_theRequesterIsRecordedWithinTheColumnLength() throws Exception {
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(
                        org.mockito.ArgumentMatchers.anyString(), any(String[].class)))
                .thenReturn(ps);
        doAnswer(
                        i -> {
                            ((PreparedStatementCreator) i.getArgument(0))
                                    .createPreparedStatement(connection);
                            ((GeneratedKeyHolder) i.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("simulation_id", 42L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        WhatIf change = new WhatIf(Regime.NEW, null, null, null);
        service.forMember(context("a".repeat(50), null), "870001", "2026-27", change);
        service.forMember(context("b".repeat(51), null), "870001", "2026-27", change);
        verify(ps).setString(1, "a".repeat(50));
        verify(ps).setString(1, "b".repeat(50));
    }
}
