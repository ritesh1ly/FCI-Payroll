package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.increment.business.IncrementRules;
import in.gov.fci.payroll.increment.business.IncrementRules.Flag;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-05 annual increment run (plan 17 phases 17b / 17c; SRS-13-1, SRS-13-4, SRS-13-5, SRS-13-8,
 * SRS-13-14, SRS-13-21; D17-3, D17-4; TX-1): the proposal and its flags - barred, no scale, the
 * next stage or the band's rule amount, above the maximum, not configured; the submission checks
 * every member first, reports the refused ones and sends the accepted ones together; the Checker's
 * approval records the increment once, the next due date a year on.
 */
class IncrementRunServiceTest {

    private static final LocalDate DUE = LocalDate.of(2026, 10, 1);
    private static final List<BigDecimal> STAGES =
            List.of(new BigDecimal("60000"), new BigDecimal("62400"), new BigDecimal("64300"));

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Scales scales = mock(Scales.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final IncrementStops stops = mock(IncrementStops.class);
    private final BasicPayService basic = mock(BasicPayService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final IncrementRunService service;

    private final AccessContext context =
            new AccessContext(
                    1L, "hr@x", "HR", "s", "P", 10L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    IncrementRunServiceTest() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service =
                new IncrementRunService(
                        named,
                        policy,
                        approvals,
                        scales,
                        rules,
                        stops,
                        new Stagnation(named, rules, stops),
                        basic,
                        JsonMapper.builder().build(),
                        transactions);
        when(stops.stopping(any(Long.class), any())).thenReturn(List.of());
    }

    private void scale(List<BigDecimal> stages, String ruleKey) {
        Optional<Scales.Scale> scale =
                Optional.of(
                        new Scales.Scale(
                                1L,
                                "E-4",
                                2L,
                                new BigDecimal("60000"),
                                new BigDecimal("64300"),
                                null,
                                ruleKey,
                                stages));
        when(scales.ofMember(7L, DUE)).thenReturn(scale);
        when(scales.of(1L, DUE)).thenReturn(scale);
    }

    @Test
    void SRS_13_5_theNextStageIsProposedAndTheLastStageIsAboveTheMaximum() {
        scale(STAGES, null);
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("60000"), DUE, 10L);
        assertThat(p.revised()).isEqualByComparingTo("62400");
        assertThat(p.flags()).isEmpty();
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).flags())
                .containsExactly(Flag.ABOVE_MAXIMUM);
        assertThat(service.propose(7L, new BigDecimal("61000"), DUE, 10L).flags())
                .contains(Flag.NOT_A_STAGE);
    }

    @Test
    void SRS_13_8_aBarredMemberOrOneWithoutAScaleOrBasicIsFlagged() {
        when(stops.stopping(7L, DUE)).thenReturn(List.of("PEN-2026-00001"));
        when(scales.ofMember(7L, DUE)).thenReturn(Optional.empty());
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("60000"), DUE, 10L);
        assertThat(p.flags()).containsExactly(Flag.BARRED, Flag.NO_SCALE);
        assertThat(p.barredBy()).containsExactly("PEN-2026-00001");
        scale(STAGES, null);
        assertThat(service.propose(7L, null, DUE, 10L).flags())
                .as("no Basic")
                .contains(Flag.NO_SCALE);
    }

    @Test
    void D17_4_withoutStagesTheBandsRuleGivesTheAmountAndUnconfiguredIsFlagged() {
        scale(List.of(), null);
        assertThat(service.propose(7L, new BigDecimal("60000"), DUE, 10L).flags())
                .containsExactly(Flag.STAGES_NOT_CONFIGURED, Flag.NOT_CONFIGURED);
        scale(List.of(), "INC.E4");
        when(rules.evaluate(eq("INC.E4"), eq(DUE), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("1800")));
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("60000"), DUE, null);
        assertThat(p.revised()).isEqualByComparingTo("61800");
        assertThat(p.flags()).containsExactly(Flag.STAGES_NOT_CONFIGURED);
        when(rules.evaluate(eq("INC.E4"), eq(DUE), any(), any()))
                .thenReturn(Optional.of(new BigDecimal("9000")));
        assertThat(service.propose(7L, new BigDecimal("60000"), DUE, 10L).flags())
                .contains(Flag.ABOVE_MAXIMUM);
        when(rules.evaluate(eq("INC.E4"), eq(DUE), any(), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(service.propose(7L, new BigDecimal("60000"), DUE, 10L).flags())
                .contains(Flag.NOT_CONFIGURED);
    }

    private static Map<String, Object> dueRow(long id, String eis, String first, String last) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMPLOYEE_ID", id);
        m.put("EMP_NUM", eis);
        m.put("FIRST_NAME", first);
        m.put("LAST_NAME", last);
        m.put("LOCATION_ID", 10L);
        m.put("BAND_ID", 1L);
        m.put("CURRENT_BASIC", new BigDecimal("60000"));
        m.put("NEXT_DUE_DATE", Date.valueOf(DUE));
        m.put("LOCATION_CODE", "DH001");
        m.put("LOCATION_NAME", "Delhi");
        return m;
    }

    @Test
    void SRS_13_21_theDueListShowsEachMemberWithTheProposalAndAnOfficeIsNeeded() {
        scale(STAGES, null);
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        Map<String, Object> noBand = dueRow(9L, "109993", "N", "B");
        noBand.put("BAND_ID", null);
        when(named.queryForList(
                        contains("ORDER BY d.next_due_date"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                dueRow(7L, "109991", null, null),
                                dueRow(8L, "109992", "A", "B"),
                                noBand));
        when(named.queryForList(
                        contains("entity_key IN (:keys)"),
                        any(SqlParameterSource.class),
                        eq(String.class)))
                .thenReturn(List.of("8:" + IncrementRules.duePeriod(DUE)));
        var page = service.due(context, YearMonth.from(DUE), new PageSpec(0, 20));
        assertThat(page.items().getFirst().name()).isEmpty();
        assertThat(page.items().getFirst().revisedBasic()).isEqualByComparingTo("62400");
        // P2: the page's members share one scale lookup per band and due date, one pending query.
        verify(scales, times(1)).of(1L, DUE);
        assertThat(page.items().get(1).pending()).isTrue();
        assertThat(page.items().getFirst().pending()).isFalse();
        assertThat(page.items().get(2).flags()).contains(Flag.NO_SCALE.name());
        AccessContext nowhere =
                new AccessContext(
                        1L, "hr@x", "HR", "s", "P", null, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThatThrownBy(() -> service.due(nowhere, YearMonth.from(DUE), new PageSpec(0, 20)))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.ACCESS_DENIED);
    }

    private void member(long id, String eis) {
        when(named.queryForList(contains("AND e.employee_id = :id"), any(SqlParameterSource.class)))
                .thenAnswer(
                        call -> {
                            SqlParameterSource p = call.getArgument(1);
                            Object asked = p.getValue("id");
                            return asked.equals(id)
                                    ? List.of(dueRow(id, eis, "A", "B"))
                                    : List.of(dueRow((Long) asked, "1099" + asked, "C", "D"));
                        });
    }

    @Test
    void SRS_13_14_membersAndARemarkAreNeededAndAnOutOfScopeMemberStopsTheWholeRun() {
        for (List<Long> ids : java.util.Arrays.asList(null, List.<Long>of())) {
            assertThatThrownBy(() -> service.submit(context, ids, "r"))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(IncrementErrorCode.ENTRY);
        }
        assertThatThrownBy(() -> service.submit(context, List.of(7L), null))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.submit(context, List.of(7L), " "))
                .isInstanceOf(FciException.class);
        scale(STAGES, null);
        when(named.queryForList(contains("AND e.employee_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of(dueRow(7L, "109991", "A", "B")))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.submit(context, List.of(7L, 99L), "Run"))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        verify(approvals, never()).submit(any(), any(), any());
    }

    // SC-17-0038: a member already given the increment for the due period is not given it again.
    @Test
    void TX_1_BR_INCR_003_flaggedDuplicateAndPendingMembersAreReportedTheOthersSentTogether() {
        when(scales.ofMember(any(Long.class), eq(DUE)))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(
                                        1L,
                                        "E-4",
                                        2L,
                                        new BigDecimal("60000"),
                                        new BigDecimal("64300"),
                                        null,
                                        null,
                                        STAGES)));
        when(scales.ofMember(eq(9L), eq(DUE))).thenReturn(Optional.empty());
        member(7L, "109991");
        // 8: already incremented this period; 6: already waiting for the Checker; 9: no scale.
        when(named.queryForObject(
                        contains("FROM pay_increment_cycle"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenAnswer(
                        call ->
                                ((SqlParameterSource) call.getArgument(1))
                                                .getValue("employee")
                                                .equals(8L)
                                        ? 1L
                                        : null);
        when(named.queryForObject(
                        contains("FROM approval_queue"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenAnswer(
                        call ->
                                String.valueOf(
                                                        ((SqlParameterSource) call.getArgument(1))
                                                                .getValue("key"))
                                                .startsWith("6:")
                                        ? 1L
                                        : 0L);
        when(approvals.submit(any(), any(), any())).thenReturn(mock(SubmittedApproval.class));
        IncrementRunService.Submitted out = service.submit(context, List.of(7L, 8L, 6L, 9L), "Run");
        assertThat(out.approvalIds()).hasSize(1);
        assertThat(out.refused())
                .extracting(IncrementRunService.Refused::reason)
                .anyMatch(r -> r.startsWith("INC-409-DUPLICATE"))
                .anyMatch(r -> r.startsWith("INC-409-STATE"))
                .anyMatch(r -> r.contains("NO_SCALE"));
        verify(approvals, times(1)).submit(any(), any(), any());
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome) {
        return decided(entity, outcome, "");
    }

    private ApprovalDecided decided(String entity, ApprovalStatus outcome, String extra) {
        String payload =
                "{\"employeeId\":7,\"due\":\""
                        + DUE
                        + "\",\"old\":\"60000\",\"revised\":\"62400\",\"period\":\"202610\""
                        + extra
                        + "}";
        return new ApprovalDecided(
                4L, "I-05", entity, "7:202610", outcome, null, payload, "ddo@x", Instant.now());
    }

    @Test
    void SRS_13_4_theApprovedIncrementIsRecordedOnceWithTheNextDueDateAYearOn() {
        service.on(decided("other", ApprovalStatus.APPROVED));
        service.on(decided(IncrementRunService.ENTITY, ApprovalStatus.REJECTED));
        verify(named, never()).update(anyString(), any(SqlParameterSource.class));

        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, null));
        when(named.queryForObject(
                        contains("SELECT increment_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(null);
        when(named.update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class)))
                .thenReturn(0);
        service.on(decided(IncrementRunService.ENTITY, ApprovalStatus.APPROVED));
        verify(named)
                .update(
                        contains("INSERT INTO employee_increment_due"),
                        any(SqlParameterSource.class));

        when(named.queryForObject(
                        contains(
                                "FROM pay_increment_cycle WHERE employee_id = :employee AND"
                                        + " due_period"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                IncrementRunService.ENTITY,
                                                ApprovalStatus.APPROVED)))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(IncrementErrorCode.DUPLICATE);
    }

    @Test
    void SRS_13_10_anAdditionalIncrementKeepsTheDueDateAndAStagnationOneMovesIt() {
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, null));
        when(named.queryForObject(
                        contains("SELECT increment_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(5L);
        when(named.update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class)))
                .thenReturn(1);
        assertThat(
                        service.record(
                                7L,
                                DUE,
                                "202610",
                                "ADDITIONAL",
                                BigDecimal.ONE,
                                BigDecimal.TEN,
                                4L,
                                "ddo@x",
                                "INCREMENT"))
                .isEqualTo(5L);
        verify(named, never())
                .update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class));
        service.record(
                7L,
                DUE,
                "202610",
                "STAGNATION",
                BigDecimal.ONE,
                BigDecimal.TEN,
                4L,
                "ddo@x",
                "STAGNATION_INCREMENT");
        verify(named)
                .update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class));
        verify(named, never())
                .update(
                        contains("INSERT INTO employee_increment_due"),
                        any(SqlParameterSource.class));
    }

    // ---------------------------------------------------------------- stagnation (B-28)

    private void stagnationScale(BigDecimal amount) {
        when(scales.ofMember(7L, DUE))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(
                                        1L,
                                        "E-4",
                                        2L,
                                        new BigDecimal("60000"),
                                        new BigDecimal("64300"),
                                        amount,
                                        null,
                                        STAGES)));
    }

    private void rule(String key, String value) {
        // doReturn: a stub that throws is replaced without being called.
        org.mockito.Mockito.doReturn(
                        Optional.of(
                                new RuleEngine.Resolved(
                                        key,
                                        "SCALAR",
                                        1L,
                                        1,
                                        DUE,
                                        null,
                                        new BigDecimal(value),
                                        null,
                                        null,
                                        null,
                                        false)))
                .when(rules)
                .resolve(eq(key), eq(DUE), any());
    }

    private void atMaximumSince(LocalDate since) {
        when(named.queryForList(
                        contains("FROM employee_basic_history"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "BASIC_AMOUNT",
                                        new BigDecimal("64300"),
                                        "EFFECTIVE_FROM",
                                        Date.valueOf(since)),
                                Map.of(
                                        "BASIC_AMOUNT",
                                        new BigDecimal("62400"),
                                        "EFFECTIVE_FROM",
                                        Date.valueOf(since.minusYears(1)))));
    }

    private void given(Long stagnations) {
        when(named.queryForObject(
                        contains("increment_type = 'STAGNATION'"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(stagnations);
    }

    // BR-INCR-STAG-001, BR-INCR-STAG-007, SRS-13-14: a member at the maximum is evaluated on the
    // due date in the run itself; eligible, the stagnation increment is proposed with its test.
    @Test
    void BR_INCR_STAG_001_aMemberAtTheMaximumIsOfferedTheStagnationIncrementWithTheTest() {
        stagnationScale(new BigDecimal("1500"));
        rule("STAGNATION_QUALIFYING_MONTHS", "12");
        rule("STAGNATION_FREQUENCY_CAP", "2");
        atMaximumSince(DUE.minusYears(2));
        given(1L);
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("64300"), DUE, 10L);
        assertThat(p.revised()).isEqualByComparingTo("65800");
        assertThat(p.flags()).containsExactly(Flag.STAGNATION);
        assertThat(p.type()).isEqualTo("STAGNATION");
        assertThat(p.stagnation()).isEqualTo(Stagnation.Verdict.ELIGIBLE);
        assertThat(p.test()).contains("since " + DUE.minusYears(2)).contains("1 of 2 given");

        // After one stagnation increment the Basic is above the maximum and not a stage: expected.
        IncrementRunService.Proposal again = service.propose(7L, new BigDecimal("65800"), DUE, 10L);
        assertThat(again.flags()).containsExactly(Flag.STAGNATION);
        assertThat(again.revised()).isEqualByComparingTo("67300");
        // A member below the maximum is never evaluated.
        assertThat(service.propose(7L, new BigDecimal("62400"), DUE, 10L).stagnation()).isNull();
    }

    // BR-INCR-STAG-004, BR-INCR-STAG-005, SRS-13-11, SRS-13-12: not eligible, the member stays
    // flagged above the maximum with the reason; unseeded values are not configured, never guessed.
    @Test
    void BR_INCR_STAG_004_notEligibleStaysAboveTheMaximumWithTheReason() {
        stagnationScale(null);
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("64300"), DUE, 10L);
        assertThat(p.flags()).containsExactly(Flag.ABOVE_MAXIMUM);
        assertThat(p.stagnation()).isEqualTo(Stagnation.Verdict.NOT_CONFIGURED);
        assertThat(p.type()).isEqualTo("ANNUAL");
        assertThat(p.test()).isNull();

        stagnationScale(new BigDecimal("1500"));
        when(rules.resolve(anyString(), eq(DUE), any())).thenReturn(Optional.empty());
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).stagnation())
                .as("qualifying months unseeded")
                .isEqualTo(Stagnation.Verdict.NOT_CONFIGURED);
        rule("STAGNATION_QUALIFYING_MONTHS", "12");
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).stagnation())
                .as("frequency cap unseeded")
                .isEqualTo(Stagnation.Verdict.NOT_CONFIGURED);
        when(rules.resolve(eq("STAGNATION_FREQUENCY_CAP"), eq(DUE), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).stagnation())
                .as("a rule error is not configured")
                .isEqualTo(Stagnation.Verdict.NOT_CONFIGURED);
        rule("STAGNATION_FREQUENCY_CAP", "2");

        atMaximumSince(DUE.minusMonths(6));
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).stagnation())
                .as("six of twelve months at the maximum")
                .isEqualTo(Stagnation.Verdict.NOT_QUALIFIED);
        when(named.queryForList(
                        contains("FROM employee_basic_history"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(service.propose(7L, new BigDecimal("64300"), DUE, 10L).stagnation())
                .as("no Basic history")
                .isEqualTo(Stagnation.Verdict.NOT_QUALIFIED);

        atMaximumSince(DUE.minusYears(2));
        given(2L);
        IncrementRunService.Proposal capped =
                service.propose(7L, new BigDecimal("64300"), DUE, 10L);
        assertThat(capped.stagnation()).isEqualTo(Stagnation.Verdict.AT_CAP);
        assertThat(capped.flags()).containsExactly(Flag.ABOVE_MAXIMUM);

        given(null);
        when(stops.stopping(7L, DUE)).thenReturn(List.of("PEN-1"));
        IncrementRunService.Proposal barred =
                service.propose(7L, new BigDecimal("64300"), DUE, 10L);
        assertThat(barred.stagnation()).isEqualTo(Stagnation.Verdict.BARRED);
        assertThat(barred.flags()).containsExactly(Flag.BARRED, Flag.ABOVE_MAXIMUM);
    }

    // BR-INCR-STAG-007, BR-INCR-STAG-008: the Checker's approval records a stagnation increment,
    // its own Basic event; an item without a type (sent before the offer) is an annual one.
    @Test
    void BR_INCR_STAG_007_anApprovedStagnationItemIsRecordedAsStagnation() {
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, null));
        when(named.update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class)))
                .thenReturn(1);
        service.on(
                decided(
                        IncrementRunService.ENTITY,
                        ApprovalStatus.APPROVED,
                        ",\"type\":\"STAGNATION\""));
        verify(basic)
                .apply(
                        org.mockito.ArgumentMatchers.argThat(
                                c -> "STAGNATION_INCREMENT".equals(c.eventType())));
        service.on(decided(IncrementRunService.ENTITY, ApprovalStatus.APPROVED));
        verify(basic)
                .apply(
                        org.mockito.ArgumentMatchers.argThat(
                                c -> "INCREMENT".equals(c.eventType())));
    }

    // ---------------------------------------------------------------- branch edges

    /** SRS-13-5: a scale without a maximum never evaluates the member at the maximum. */
    @Test
    void SRS_13_5_aScaleWithoutAMaximumProposesTheNextStageWithoutTheStagnationTest() {
        when(scales.ofMember(7L, DUE))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(
                                        1L,
                                        "E-4",
                                        2L,
                                        new BigDecimal("60000"),
                                        null,
                                        new BigDecimal("1500"),
                                        null,
                                        STAGES)));
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("62400"), DUE, 10L);
        assertThat(p.revised()).isEqualByComparingTo("64300");
        assertThat(p.flags()).isEmpty();
        assertThat(p.stagnation()).isNull();
    }

    /**
     * BR-INCR-STAG-004 / D17-3: a member at the maximum of a scale without stages and without its
     * stagnation amount stays flagged above the maximum, beside the unconfigured flags.
     */
    @Test
    void BR_INCR_STAG_004_atTheMaximumWithoutStagesTheMemberIsFlaggedAboveTheMaximum() {
        scale(List.of(), null);
        IncrementRunService.Proposal p = service.propose(7L, new BigDecimal("64300"), DUE, 10L);
        assertThat(p.flags())
                .containsExactly(
                        Flag.STAGES_NOT_CONFIGURED, Flag.NOT_CONFIGURED, Flag.ABOVE_MAXIMUM);
        assertThat(p.stagnation()).isEqualTo(Stagnation.Verdict.NOT_CONFIGURED);
        assertThat(p.revised()).isEqualByComparingTo("64300");
    }

    /** SRS-13-21: a month with nobody due lists nothing and asks for no pending items. */
    @Test
    void SRS_13_21_aMonthWithNobodyDueListsNothing() {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        var page = service.due(context, YearMonth.from(DUE), new PageSpec(0, 20));
        assertThat(page.items()).isEmpty();
        verify(named, never())
                .queryForList(
                        contains("entity_key IN (:keys)"),
                        any(SqlParameterSource.class),
                        eq(String.class));
    }

    /** BR-INCR-STAG-004 (I-05 l.4001): the due list shows a member's stagnation verdict. */
    @Test
    void BR_INCR_STAG_004_theDueListShowsTheStagnationVerdictOfAMemberAtTheMaximum() {
        when(scales.of(1L, DUE))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(
                                        1L,
                                        "E-4",
                                        2L,
                                        new BigDecimal("60000"),
                                        new BigDecimal("64300"),
                                        null,
                                        null,
                                        STAGES)));
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        Map<String, Object> atMax = dueRow(7L, "109991", "A", "B");
        atMax.put("CURRENT_BASIC", new BigDecimal("64300"));
        when(named.queryForList(
                        contains("ORDER BY d.next_due_date"), any(SqlParameterSource.class)))
                .thenReturn(List.of(atMax));
        IncrementRunService.DueRow row =
                service.due(context, YearMonth.from(DUE), new PageSpec(0, 20)).items().getFirst();
        assertThat(row.stagnation()).isEqualTo("NOT_CONFIGURED");
        assertThat(row.flags()).contains(Flag.ABOVE_MAXIMUM.name());
    }

    /** SRS-13-19 (US-GAP-52): a stagnation increment carries its test to the Checker. */
    @Test
    void SRS_13_19_aSubmittedStagnationIncrementCarriesItsTest() {
        stagnationScale(new BigDecimal("1500"));
        rule("STAGNATION_QUALIFYING_MONTHS", "12");
        rule("STAGNATION_FREQUENCY_CAP", "2");
        atMaximumSince(DUE.minusYears(2));
        given(1L);
        Map<String, Object> atMax = dueRow(7L, "109991", "A", "B");
        atMax.put("CURRENT_BASIC", new BigDecimal("64300"));
        when(named.queryForList(contains("AND e.employee_id = :id"), any(SqlParameterSource.class)))
                .thenReturn(List.of(atMax));
        when(approvals.submit(any(), any(), any())).thenReturn(mock(SubmittedApproval.class));
        IncrementRunService.Submitted out = service.submit(context, List.of(7L), "Stagnation");
        assertThat(out.refused()).isEmpty();
        org.mockito.ArgumentCaptor<in.gov.fci.payroll.platform.approval.Submission> sent =
                org.mockito.ArgumentCaptor.captor();
        verify(approvals).submit(eq(context), sent.capture(), any());
        tools.jackson.databind.JsonNode payload =
                JsonMapper.builder().build().readTree(sent.getValue().payload());
        assertThat(payload.path("type").asString()).isEqualTo("STAGNATION");
        assertThat(payload.path("test").asString()).contains("1 of 2 given");
    }
}
