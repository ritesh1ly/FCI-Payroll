package in.gov.fci.payroll.increment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.increment.business.IncrementErrorCode;
import in.gov.fci.payroll.penalty.api.IncrementStops;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * I-05 individual cases (plan 17 phases 17d / 17e; SRS-13-9 … 13-20, SRS-13-24; D17-1, D17-8): the
 * proposal of each case type with its refusals - the order and reason mandatory, the scale and its
 * stages, the stagnation eligibility test with its unseeded rule values - and the Checker's
 * approval applying each type, a rejection changing nothing.
 */
class PayFixationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final List<BigDecimal> STAGES =
            List.of(new BigDecimal("60000"), new BigDecimal("62400"), new BigDecimal("64300"));

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Scales scales = mock(Scales.class);
    private final RuleEngine rules = mock(RuleEngine.class);
    private final IncrementStops stops = mock(IncrementStops.class);
    private final BasicPayService basic = mock(BasicPayService.class);
    private final IncrementRunService increments = mock(IncrementRunService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final in.gov.fci.payroll.document.api.DocumentStore documents =
            mock(in.gov.fci.payroll.document.api.DocumentStore.class);
    private final in.gov.fci.payroll.audit.api.AuditService audit =
            mock(in.gov.fci.payroll.audit.api.AuditService.class);
    private static final String KEY = "PROVISIONAL:7c9e6679-7425-40de-944b-e07fc1f90ae7";

    /** The order copy the Maker stored before submitting (SRS-13-18). */
    private void copyStoredBy(String login) {
        when(documents.active(PayFixationService.ENTITY, KEY))
                .thenReturn(
                        java.util.List.of(
                                new in.gov.fci.payroll.document.api.DocumentStore.AttachmentView(
                                        5L,
                                        PayFixationService.ENTITY,
                                        KEY,
                                        "OTHER",
                                        "Other document",
                                        "EST/17",
                                        DAY,
                                        "GM (P)",
                                        "order.pdf",
                                        "application/pdf",
                                        10L,
                                        "abc",
                                        "ACTIVE",
                                        login,
                                        java.time.Instant.EPOCH)));
    }

    private final PayFixationService service =
            new PayFixationService(
                    named,
                    policy,
                    approvals,
                    scales,
                    new Stagnation(named, rules, stops),
                    basic,
                    increments,
                    json,
                    documents,
                    audit);

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "hr@x",
                    "HR",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static PayFixationService.Case kase(
            String type, BigDecimal basic, LocalDate corrected) {
        return new PayFixationService.Case(
                "109991",
                type,
                DAY,
                basic,
                corrected,
                "EST/17",
                DAY.minusDays(3),
                "GM (P)",
                "Order of the competent authority",
                5L,
                KEY);
    }

    private void member(BigDecimal current, LocalDate due) {
        Map<String, Object> row = new HashMap<>();
        row.put("EMPLOYEE_ID", 7L);
        row.put("EMP_NUM", "109991");
        row.put("CURRENT_BASIC", current);
        row.put("NEXT_DUE_DATE", due == null ? null : Date.valueOf(due));
        when(named.queryForList(
                        contains("LEFT JOIN employee_increment_due"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of(row));
    }

    private void scale(BigDecimal max, BigDecimal stagnation, List<BigDecimal> stages) {
        when(scales.ofMember(7L, DAY))
                .thenReturn(
                        Optional.of(
                                new Scales.Scale(
                                        1L,
                                        "E-4",
                                        2L,
                                        new BigDecimal("60000"),
                                        max,
                                        stagnation,
                                        null,
                                        stages)));
    }

    private void rule(String key, String value) {
        when(rules.resolve(eq(key), eq(DAY), any()))
                .thenReturn(
                        Optional.of(
                                new RuleEngine.Resolved(
                                        key,
                                        "SCALAR",
                                        1L,
                                        1,
                                        DAY,
                                        null,
                                        new BigDecimal(value),
                                        null,
                                        null,
                                        null,
                                        false)));
    }

    private void history(Object... basicAndFrom) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < basicAndFrom.length; i += 2) {
            rows.add(
                    Map.of(
                            "BASIC_AMOUNT",
                            new BigDecimal((String) basicAndFrom[i]),
                            "EFFECTIVE_FROM",
                            Date.valueOf((LocalDate) basicAndFrom[i + 1])));
        }
        when(named.queryForList(
                        contains("FROM employee_basic_history"), any(SqlParameterSource.class)))
                .thenReturn(rows);
    }

    private ErrorCode refusal(PayFixationService.Case c) {
        return ((FciException)
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> service.preview(context, c)))
                .getErrorCode();
    }

    // BR-INCR-004: the effective date of an increment or fixation is mandatory.
    // SC-17-0052: the order is mandatory for a fixation case.
    @Test
    void SRS_13_18_theCaseTypeDateOrderAuthorityReasonAndMemberAreMandatory() {
        PayFixationService.Case ok = kase("DATE_CORRECTION", null, DAY.plusMonths(1));
        List<PayFixationService.Case> bad =
                List.of(
                        new PayFixationService.Case(
                                "109991", null, DAY, null, null, "o", DAY, "a", "r"),
                        new PayFixationService.Case(
                                "109991", "BOGUS", DAY, null, null, "o", DAY, "a", "r"),
                        new PayFixationService.Case(
                                "109991", "DATE_CORRECTION", null, null, null, "o", DAY, "a", "r"),
                        new PayFixationService.Case(
                                "109991", "DATE_CORRECTION", DAY, null, null, " ", DAY, "a", "r"),
                        new PayFixationService.Case(
                                "109991", "DATE_CORRECTION", DAY, null, null, "o", null, "a", "r"),
                        new PayFixationService.Case(
                                "109991", "DATE_CORRECTION", DAY, null, null, "o", DAY, null, "r"),
                        new PayFixationService.Case(
                                "109991", "DATE_CORRECTION", DAY, null, null, "o", DAY, "a", " "),
                        new PayFixationService.Case(
                                " ", "DATE_CORRECTION", DAY, null, null, "o", DAY, "a", "r"),
                        kase("DATE_CORRECTION", null, null));
        member(new BigDecimal("62400"), DAY);
        for (PayFixationService.Case c : bad) {
            assertThat(refusal(c)).as(c.toString()).isEqualTo(IncrementErrorCode.ENTRY);
        }
        PayFixationService.Proposal p = service.preview(context, ok);
        assertThat(p.oldBasic()).isEqualByComparingTo("62400");
        assertThat(p.revisedBasic()).isEqualByComparingTo("62400");
        assertThat(p.correctedDueDate()).isEqualTo(DAY.plusMonths(1));
        assertThat(p.oldDueDate()).isEqualTo(DAY);
    }

    @Test
    void anUnknownOrOutOfScopeMemberIsNotFoundAndHeadquartersSearchesEveryOffice() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(named.queryForList(anyString(), any(SqlParameterSource.class))).thenReturn(List.of());
        assertThat(refusal(kase("DATE_CORRECTION", null, DAY)))
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        AccessContext nowhere =
                new AccessContext(
                        1L, "hr@x", "HR", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThatThrownBy(() -> service.preview(nowhere, kase("DATE_CORRECTION", null, DAY)))
                .isInstanceOf(FciException.class);
    }

    @Test
    void
            SRS_13_3_SRS_13_9_promotionFixesTheNextStageAboveTheBasicInTheMembersScaleAndIsRefusedWithoutAScaleStagesOrRoom() {
        member(new BigDecimal("62400"), null);
        when(basic.basicOn(7L, DAY)).thenReturn(null);
        PayFixationService.Case promotion = kase("PROMOTION_FIXATION", null, null);
        when(scales.ofMember(7L, DAY)).thenReturn(Optional.empty());
        assertThat(refusal(promotion)).isEqualTo(IncrementErrorCode.FLAGGED);
        scale(new BigDecimal("64300"), null, List.of());
        assertThat(refusal(promotion))
                .as("stages not configured")
                .isEqualTo(IncrementErrorCode.FLAGGED);
        scale(new BigDecimal("64300"), null, STAGES);
        assertThat(service.preview(context, promotion).revisedBasic())
                .isEqualByComparingTo("64300");
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("64300"));
        assertThat(refusal(promotion))
                .as("already at the last stage")
                .isEqualTo(IncrementErrorCode.FLAGGED);
    }

    @Test
    void SRS_13_18_aRevisedBasicIsWithinTheScaleAndOnAStageWhenStagesAreConfigured() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        assertThat(refusal(kase("INDIVIDUAL_REVISION", null, null)))
                .isEqualTo(IncrementErrorCode.ENTRY);
        assertThat(refusal(kase("INDIVIDUAL_REVISION", BigDecimal.ZERO, null)))
                .isEqualTo(IncrementErrorCode.ENTRY);
        when(scales.ofMember(7L, DAY)).thenReturn(Optional.empty());
        assertThat(refusal(kase("ADDITIONAL_INCREMENT", new BigDecimal("62400"), null)))
                .isEqualTo(IncrementErrorCode.FLAGGED);
        scale(new BigDecimal("64300"), null, STAGES);
        assertThat(refusal(kase("BASIC_CORRECTION", new BigDecimal("70000"), null)))
                .as("above the maximum")
                .isEqualTo(IncrementErrorCode.FLAGGED);
        assertThat(refusal(kase("BASIC_CORRECTION", new BigDecimal("61000"), null)))
                .as("not a stage")
                .isEqualTo(IncrementErrorCode.FLAGGED);
        assertThat(
                        service.preview(
                                        context,
                                        kase("ADDITIONAL_INCREMENT", new BigDecimal("62400"), null))
                                .revisedBasic())
                .isEqualByComparingTo("62400");
        scale(new BigDecimal("64300"), null, List.of());
        assertThat(
                        service.preview(
                                        context,
                                        kase("INDIVIDUAL_REVISION", new BigDecimal("61000"), null))
                                .revisedBasic())
                .as("no stages: any Basic within the scale")
                .isEqualByComparingTo("61000");
    }

    // BR-INCR-STAG-006, SRS-13-13: the quantum is a parameter per scale, never per member.
    // SC-17-0012: the stagnation increment amount is a rule parameter per scale, never keyed.
    @Test
    void
            SRS_13_11_BR_INCR_STAG_002_stagnationNeedsTheScaleAmountAndItsRuleValuesUnseededItIsNotConfigured() {
        member(new BigDecimal("64300"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("64300"));
        PayFixationService.Case stagnation = kase("STAGNATION_INCREMENT", null, null);
        when(scales.ofMember(7L, DAY)).thenReturn(Optional.empty());
        assertThat(refusal(stagnation)).isEqualTo(IncrementErrorCode.FLAGGED);
        scale(new BigDecimal("64300"), null, STAGES);
        assertThat(refusal(stagnation))
                .as("no stagnation amount (M-03)")
                .isEqualTo(IncrementErrorCode.NOT_CONFIGURED);
        scale(new BigDecimal("64300"), new BigDecimal("1500"), STAGES);
        when(rules.resolve(anyString(), eq(DAY), any())).thenReturn(Optional.empty());
        assertThat(refusal(stagnation))
                .as("D17-8: unseeded")
                .isEqualTo(IncrementErrorCode.NOT_CONFIGURED);
        when(rules.resolve(anyString(), eq(DAY), any()))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(refusal(stagnation))
                .as("a rule error is not configured")
                .isEqualTo(IncrementErrorCode.NOT_CONFIGURED);
    }

    // BR-INCR-STAG-004, BR-INCR-STAG-005: the eligibility test and the frequency cap.
    @Test
    void SRS_13_12_stagnationIsForAMemberAtTheMaximumForTheQualifyingMonthsNotBarredBelowTheCap() {
        member(new BigDecimal("64300"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("62400"));
        scale(new BigDecimal("64300"), new BigDecimal("1500"), STAGES);
        rule("STAGNATION_QUALIFYING_MONTHS", "12");
        rule("STAGNATION_FREQUENCY_CAP", "2");
        PayFixationService.Case stagnation = kase("STAGNATION_INCREMENT", null, null);
        assertThat(refusal(stagnation)).as("below the maximum").isEqualTo(IncrementErrorCode.ENTRY);

        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("64300"));
        history();
        assertThat(refusal(stagnation))
                .as("no history at the maximum")
                .isEqualTo(IncrementErrorCode.ENTRY);
        history("64300", DAY.minusMonths(6), "62400", DAY.minusYears(2));
        assertThat(refusal(stagnation))
                .as("only six months at the maximum")
                .isEqualTo(IncrementErrorCode.ENTRY);

        history(
                "64300",
                DAY.minusMonths(6),
                "64300",
                DAY.minusYears(2),
                "62400",
                DAY.minusYears(3));
        when(stops.stopping(7L, DAY)).thenReturn(List.of("PEN-1"));
        assertThat(refusal(stagnation))
                .as("an increment-stop order")
                .isEqualTo(IncrementErrorCode.FLAGGED);

        when(stops.stopping(7L, DAY)).thenReturn(List.of());
        when(named.queryForObject(
                        contains("increment_type = 'STAGNATION'"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(2L);
        assertThat(refusal(stagnation)).as("the frequency cap").isEqualTo(IncrementErrorCode.ENTRY);

        when(named.queryForObject(
                        contains("increment_type = 'STAGNATION'"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(null);
        PayFixationService.Proposal p = service.preview(context, stagnation);
        assertThat(p.revisedBasic()).isEqualByComparingTo("65800");
        assertThat(p.test()).contains("since " + DAY.minusYears(2)).contains("0 of 2 given");

        // BMAD TR-041 (SRS-13-11 l.3900, SRS-13-12 l.3901): the boundaries. One below the cap is
        // still eligible; at the maximum for exactly the qualifying months it is eligible, a month
        // short it is not.
        when(named.queryForObject(
                        contains("increment_type = 'STAGNATION'"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        assertThat(service.preview(context, stagnation).test()).contains("1 of 2 given");
        history("64300", DAY.minusMonths(12), "62400", DAY.minusYears(2));
        assertThat(service.preview(context, stagnation).revisedBasic())
                .as("exactly the qualifying 12 months at the maximum")
                .isEqualByComparingTo("65800");
        history("64300", DAY.minusMonths(11), "62400", DAY.minusYears(2));
        assertThat(refusal(stagnation))
                .as("11 months: one short of the qualifying period")
                .isEqualTo(IncrementErrorCode.ENTRY);
    }

    @Test
    void SRS_13_24_aCorrectionGoesToTheCorrectionRouteWithTheProposalAsPayload() {
        member(null, null);
        when(basic.basicOn(7L, DAY)).thenReturn(null);
        when(named.queryForObject(
                        contains("SELECT location_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(10L);
        copyStoredBy("hr@x");
        service.submit(context, kase("DATE_CORRECTION", null, DAY.plusMonths(2)));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        JsonNode payload = json.readTree(sent.getValue().payload());
        assertThat(sent.getValue().workflowAction()).isEqualTo(PayFixationService.CORRECTION);
        assertThat(payload.path("old").asString()).isEmpty();
        assertThat(payload.path("oldDue").asString()).isEmpty();
        assertThat(payload.path("test").asString()).isEmpty();
        assertThat(payload.path("correctedDue").asString()).isEqualTo(DAY.plusMonths(2).toString());
    }

    @Test
    void SRS_13_18_aFixationGoesToTheFixationRouteWithItsImpact() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        scale(new BigDecimal("64300"), null, STAGES);
        copyStoredBy("hr@x");
        service.submit(context, kase("INDIVIDUAL_REVISION", new BigDecimal("62400"), null));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        assertThat(sent.getValue().workflowAction()).isEqualTo(PayFixationService.FIXATION);
        // The stored copy becomes the case's: the Checker sees it with the item.
        verify(documents)
                .adopt("hr@x", PayFixationService.ENTITY, KEY, 5L, sent.getValue().entityKey());
        assertThat(json.readTree(sent.getValue().payload()).path("revised").asString())
                .isEqualTo("62400");
    }

    /** SRS-13-18 (US-GAP-10): no copy, or a copy of another user, is an audited refusal. */
    @Test
    void SRS_13_18_aCaseWithoutItsOrderCopyIsRefusedAndTheRefusalAudited() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        scale(new BigDecimal("64300"), null, STAGES);
        copyStoredBy("someone.else");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                service.submit(
                                        context,
                                        kase("INDIVIDUAL_REVISION", new BigDecimal("62400"), null)))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        PayFixationService.Case none =
                new PayFixationService.Case(
                        "109991",
                        "INDIVIDUAL_REVISION",
                        DAY,
                        new BigDecimal("62400"),
                        null,
                        "EST/17",
                        DAY.minusDays(3),
                        "GM (P)",
                        "Order",
                        null,
                        null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(context, none))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        verify(audit, org.mockito.Mockito.times(2))
                .submissionBlocked(
                        "hr@x", PayFixationService.ENTITY, "109991", "I-05", "order copy");
        verify(approvals, org.mockito.Mockito.never()).submit(any(), any(), any());
    }

    /** SRS-13-18: a copy named by an id without its key, a non-provisional key or another id. */
    @Test
    void SRS_13_18_aCaseNamingACopyThatIsNotItsStoredOneIsRefused() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        scale(new BigDecimal("64300"), null, STAGES);
        copyStoredBy("hr@x");
        java.util.List<Object[]> copies =
                java.util.List.of(
                        new Object[] {5L, null},
                        new Object[] {5L, "EMPLOYEE:109991"},
                        new Object[] {6L, KEY});
        for (Object[] copy : copies) {
            PayFixationService.Case c =
                    new PayFixationService.Case(
                            "109991",
                            "INDIVIDUAL_REVISION",
                            DAY,
                            new BigDecimal("62400"),
                            null,
                            "EST/17",
                            DAY.minusDays(3),
                            "GM (P)",
                            "Order",
                            (Long) copy[0],
                            (String) copy[1]);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(context, c))
                    .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        }
        verify(audit, org.mockito.Mockito.times(3))
                .submissionBlocked(
                        "hr@x", PayFixationService.ENTITY, "109991", "I-05", "order copy");
        verify(approvals, org.mockito.Mockito.never()).submit(any(), any(), any());
    }

    // ---------------------------------------------------------------- the Checker's decision

    private ApprovalDecided decided(
            ApprovalStatus outcome, String entity, Map<String, Object> payload) {
        return new ApprovalDecided(
                55L,
                "I-05",
                entity,
                "key-1",
                outcome,
                null,
                json.writeValueAsString(payload),
                "ddo@x",
                Instant.now(),
                9L);
    }

    private static Map<String, Object> payload(
            String type, String old, String revised, String oldDue, String corrected) {
        Map<String, Object> p = new HashMap<>();
        p.put("employeeId", 7L);
        p.put("caseType", type);
        p.put("effective", DAY.toString());
        p.put("old", old);
        p.put("revised", revised);
        p.put("oldDue", oldDue);
        p.put("correctedDue", corrected);
        p.put("order", "EST/17 " + "x".repeat(120));
        p.put("orderDate", DAY.minusDays(3).toString());
        p.put("authority", "GM (P)");
        p.put("reason", "Order");
        p.put("test", "");
        return p;
    }

    // SC-17-0041: a rejected revision leaves the record as it was and goes back with the reason.
    @Test
    void SRS_13_20_aRejectionOrAnotherEntityChangesNothing() {
        service.on(
                decided(
                        ApprovalStatus.REJECTED,
                        PayFixationService.ENTITY,
                        payload("PROMOTION_FIXATION", "1", "2", "", "")));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        "other_entity",
                        payload("PROMOTION_FIXATION", "1", "2", "", "")));
        verifyNoInteractions(basic, increments, named);
    }

    @Test
    void SRS_13_9_anApprovedFixationAppliesTheBasicAndRecordsTheFixationEvent() {
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, 4L));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload("PROMOTION_FIXATION", "62400", "64300", "", "")));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload("INDIVIDUAL_REVISION", "62400", "64300", "", "")));
        ArgumentCaptor<BasicPayService.Change> changes =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic, org.mockito.Mockito.times(2)).apply(changes.capture());
        assertThat(changes.getAllValues())
                .extracting(BasicPayService.Change::eventType)
                .containsExactly("PROMOTION", "FIXATION");
        assertThat(changes.getValue().orderReference()).hasSize(100);
        verify(named, org.mockito.Mockito.times(2))
                .update(contains("INSERT INTO pay_fixation_event"), any(SqlParameterSource.class));
    }

    // BR-INCR-STAG-003, BR-INCR-STAG-008, SRS-13-17: each stagnation increment is its own entry,
    // the prior Basic kept.
    @Test
    void SRS_13_10_anApprovedAdditionalOrStagnationIncrementIsRecordedAsAnIncrement() {
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload("ADDITIONAL_INCREMENT", "62400", "64300", "", "")));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload("STAGNATION_INCREMENT", "64300", "65800", "", "")));
        verify(increments)
                .record(
                        eq(7L),
                        eq(DAY),
                        anyString(),
                        eq("ADDITIONAL"),
                        any(),
                        any(),
                        eq(55L),
                        eq("ddo@x"),
                        eq("INCREMENT"));
        verify(increments)
                .record(
                        eq(7L),
                        eq(DAY),
                        anyString(),
                        eq("STAGNATION"),
                        any(),
                        any(),
                        eq(55L),
                        eq("ddo@x"),
                        eq("STAGNATION_INCREMENT"));
    }

    @Test
    void SRS_13_24_anApprovedBasicCorrectionAppliesTheBasicAndRecordsTheCorrection() {
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, null));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload("BASIC_CORRECTION", "", "64300", "", "")));
        verify(named)
                .update(
                        contains("INSERT INTO increment_correction"),
                        any(SqlParameterSource.class));
    }

    @Test
    void SRS_13_24_aDateCorrectionMovesTheDueDateAndReappliesAnIncrementGivenOnTheWrongDate() {
        // Nothing given on the old date and no due row yet: the corrected date itself, inserted.
        when(named.queryForList(
                        contains("FROM pay_increment_cycle"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        when(named.update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class)))
                .thenReturn(0);
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload(
                                "DATE_CORRECTION",
                                "64300",
                                "64300",
                                "",
                                DAY.plusMonths(1).toString())));
        verify(named)
                .update(
                        contains("INSERT INTO employee_increment_due"),
                        any(SqlParameterSource.class));
        verify(basic, never()).apply(any());

        // Given on the old date: re-applied from the corrected one, the next due a year on.
        when(named.queryForList(
                        contains("FROM pay_increment_cycle"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "INCREMENT_ID",
                                        1L,
                                        "REVISED_BASIC",
                                        new BigDecimal("64300"))));
        when(named.update(contains("UPDATE employee_increment_due"), any(SqlParameterSource.class)))
                .thenReturn(1);
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(3L, BigDecimal.ONE, true, 8L));
        service.on(
                decided(
                        ApprovalStatus.APPROVED,
                        PayFixationService.ENTITY,
                        payload(
                                "DATE_CORRECTION",
                                "64300",
                                "64300",
                                DAY.toString(),
                                DAY.plusMonths(1).toString())));
        verify(basic).apply(any());
        verify(named, org.mockito.Mockito.times(2))
                .update(
                        contains("INSERT INTO increment_correction"),
                        any(SqlParameterSource.class));
    }

    @Test
    void anUnknownCaseTypeInAnApprovedPayloadIsAnError() {
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                PayFixationService.ENTITY,
                                                payload("BOGUS", "1", "2", "", ""))))
                .isInstanceOf(IllegalStateException.class);
        verify(basic, never()).apply(any());
        verify(increments, never())
                .record(
                        anyLong(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString());
    }

    // ---------------------------------------------------------------- US-GAP-50 / US-GAP-54

    private void latest(
            long id, String basicNow, String before, LocalDate from, String type, String source) {
        Map<String, Object> row = new HashMap<>();
        row.put("HISTORY_ID", id);
        row.put("BASIC_AMOUNT", new BigDecimal(basicNow));
        row.put("OLD_BASIC_AMOUNT", before == null ? null : new BigDecimal(before));
        row.put("EFFECTIVE_FROM", Date.valueOf(from));
        row.put("EVENT_TYPE", type);
        row.put("SOURCE_CODE", source);
        when(named.queryForList(
                        contains("event_sequence DESC FETCH FIRST 1 ROWS ONLY"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of(row));
    }

    private static PayFixationService.Case observed(
            LocalDate from, String source, String reference) {
        return new PayFixationService.Case(
                "109991",
                "OBSERVATION_REFIXATION",
                from,
                new BigDecimal("62400"),
                null,
                "EST/17",
                DAY.minusDays(3),
                "GM (P)",
                "Audit para 4",
                5L,
                KEY,
                source,
                reference);
    }

    @Test
    void SRS_13_24_aReversalUndoesTheLatestEntryFromItsDateThroughTheCorrectionRoute() {
        member(new BigDecimal("62400"), DAY.plusYears(1));
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("62400"));
        latest(41L, "62400", "60000", DAY, "FIXATION", "ENT");
        when(named.queryForObject(
                        contains("SELECT location_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(10L);
        PayFixationService.Proposal p = service.preview(context, kase("REVERSE_ENTRY", null, null));
        assertThat(p.oldBasic()).isEqualByComparingTo("62400");
        assertThat(p.revisedBasic()).as("the Basic before the entry").isEqualByComparingTo("60000");
        assertThat(p.reversedEntry()).isEqualTo(41L);
        copyStoredBy("hr@x");
        service.submit(context, kase("REVERSE_ENTRY", null, null));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        assertThat(sent.getValue().workflowAction())
                .as("with the Checker's OTP")
                .isEqualTo(PayFixationService.CORRECTION);
        JsonNode payload = json.readTree(sent.getValue().payload());
        assertThat(payload.path("reversed").asString()).isEqualTo("41");
        assertThat(payload.path("revised").asString()).isEqualTo("60000");
    }

    @Test
    void SRS_13_24_aReversalIsRefusedForAnotherDateAnHrmsEntryAnOpeningBasicOrNoEntry() {
        member(new BigDecimal("62400"), null);
        when(named.queryForList(
                        contains("event_sequence DESC FETCH FIRST 1 ROWS ONLY"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(refusal(kase("REVERSE_ENTRY", null, null))).isEqualTo(IncrementErrorCode.ENTRY);
        latest(41L, "62400", "60000", DAY.minusMonths(1), "FIXATION", "ENT");
        assertThat(refusal(kase("REVERSE_ENTRY", null, null)))
                .as("the date names the latest entry")
                .isEqualTo(IncrementErrorCode.ENTRY);
        latest(41L, "62400", "60000", DAY, "HRMS_CHANGE", "HRMS");
        assertThat(refusal(kase("REVERSE_ENTRY", null, null)))
                .as("SRS-G-45: corrected through HRMS")
                .isEqualTo(IncrementErrorCode.ENTRY);
        latest(41L, "62400", null, DAY, "MIGRATION_OPENING", "SYS");
        assertThat(refusal(kase("REVERSE_ENTRY", null, null))).isEqualTo(IncrementErrorCode.ENTRY);
        latest(41L, "62400", null, DAY, "FIXATION", "ENT");
        assertThat(refusal(kase("REVERSE_ENTRY", null, null))).isEqualTo(IncrementErrorCode.ENTRY);
        latest(41L, "62400", "60000", DAY, "MIGRATION_OPENING", "SYS");
        assertThat(refusal(kase("REVERSE_ENTRY", null, null))).isEqualTo(IncrementErrorCode.ENTRY);
        verifyNoInteractions(approvals);
    }

    @Test
    void SRS_13_24_anApprovedReversalRestoresTheBasicAndRecordsAReverseEntryCorrection() {
        latest(41L, "62400", "60000", DAY, "FIXATION", "ENT");
        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(42L, new BigDecimal("62400"), true, 8L));
        Map<String, Object> p = payload("REVERSE_ENTRY", "62400", "60000", "", "");
        p.put("reversed", "41");
        service.on(decided(ApprovalStatus.APPROVED, PayFixationService.ENTITY, p));
        ArgumentCaptor<BasicPayService.Change> change =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic).apply(change.capture());
        assertThat(change.getValue().basic()).isEqualByComparingTo("60000");
        assertThat(change.getValue().effectiveFrom()).isEqualTo(DAY);
        assertThat(change.getValue().eventType()).isEqualTo("CORRECTION");
        assertThat(change.getValue().reason()).startsWith("Reversal of a wrong entry");
        ArgumentCaptor<SqlParameterSource> row = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).update(contains("INSERT INTO increment_correction"), row.capture());
        assertThat(row.getValue().getValue("type")).isEqualTo("REVERSE_ENTRY");
        assertThat(row.getValue().getValue("history")).isEqualTo(42L);
        assertThat(row.getValue().getValue("arrear")).isEqualTo(8L);
        assertThat(row.getValue().getValue("otp")).isEqualTo(9L);
    }

    @Test
    void SRS_13_24_aReversalWhoseEntryIsNoLongerTheLatestIsRefusedAtApproval() {
        latest(43L, "64300", "62400", DAY, "INCREMENT", "CALC");
        Map<String, Object> p = payload("REVERSE_ENTRY", "62400", "60000", "", "");
        p.put("reversed", "41");
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                PayFixationService.ENTITY,
                                                p)))
                .isInstanceOf(FciException.class);
        when(named.queryForList(
                        contains("event_sequence DESC FETCH FIRST 1 ROWS ONLY"),
                        any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                ApprovalStatus.APPROVED,
                                                PayFixationService.ENTITY,
                                                p)))
                .isInstanceOf(FciException.class);
        verify(basic, never()).apply(any());
    }

    @Test
    void SRS_13_27_anObservationRefixationNeedsItsSourceReferenceAndAPastBasic() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        scale(new BigDecimal("64300"), null, STAGES);
        for (PayFixationService.Case bad :
                List.of(
                        observed(DAY, null, "AUD/2026/4"),
                        observed(DAY, "INSPECTION", "AUD/2026/4"),
                        observed(DAY, "AUDIT", null),
                        observed(DAY, "AUDIT", "  "),
                        observed(DAY, "AUDIT", "R".repeat(101)),
                        observed(
                                LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1),
                                "AUDIT",
                                "AUD/2026/4"))) {
            assertThat(refusal(bad)).as(bad.toString()).isEqualTo(IncrementErrorCode.ENTRY);
        }
        // The refixed Basic is checked against the scale like any revised Basic.
        PayFixationService.Case above =
                new PayFixationService.Case(
                        "109991",
                        "OBSERVATION_REFIXATION",
                        DAY,
                        new BigDecimal("70000"),
                        null,
                        "EST/17",
                        DAY.minusDays(3),
                        "GM (P)",
                        "Audit para 4",
                        5L,
                        KEY,
                        "AUDIT",
                        "AUD/2026/4");
        assertThat(refusal(above)).isEqualTo(IncrementErrorCode.FLAGGED);
        assertThat(service.preview(context, observed(DAY, "ACCOUNTS", "ACC/9")).revisedBasic())
                .isEqualByComparingTo("62400");
        assertThat(service.preview(context, observed(DAY, "AUDIT", "R".repeat(100))).revisedBasic())
                .isEqualByComparingTo("62400");
    }

    @Test
    void SRS_13_27_anObservationRefixationKeepsItsReferenceAndRaisesTheDifferential() {
        member(new BigDecimal("60000"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("60000"));
        scale(new BigDecimal("64300"), null, STAGES);
        when(named.queryForObject(
                        contains("SELECT location_id"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(10L);
        copyStoredBy("hr@x");
        service.submit(context, observed(DAY, " AUDIT ", " AUD/2026/4 "));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        assertThat(sent.getValue().workflowAction()).isEqualTo(PayFixationService.CORRECTION);
        JsonNode payload = json.readTree(sent.getValue().payload());
        assertThat(payload.path("observationSource").asString()).isEqualTo("AUDIT");
        assertThat(payload.path("observationReference").asString()).isEqualTo("AUD/2026/4");

        when(basic.apply(any()))
                .thenReturn(new BasicPayService.Applied(44L, new BigDecimal("60000"), true, 12L));
        for (String source : List.of("AUDIT", "ACCOUNTS")) {
            Map<String, Object> p = payload("OBSERVATION_REFIXATION", "60000", "62400", "", "");
            p.put("observationSource", source);
            p.put("observationReference", "OBS/1");
            service.on(decided(ApprovalStatus.APPROVED, PayFixationService.ENTITY, p));
        }
        ArgumentCaptor<BasicPayService.Change> change =
                ArgumentCaptor.forClass(BasicPayService.Change.class);
        verify(basic, org.mockito.Mockito.times(2)).apply(change.capture());
        assertThat(change.getAllValues().get(0).reason()).startsWith("Audit observation OBS/1");
        assertThat(change.getAllValues().get(1).reason()).startsWith("Accounts observation OBS/1");
        ArgumentCaptor<SqlParameterSource> row = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named, org.mockito.Mockito.times(2))
                .update(contains("INSERT INTO increment_correction"), row.capture());
        assertThat(row.getAllValues().get(0).getValue("type")).isEqualTo("BASIC_PAY");
        assertThat((String) row.getAllValues().get(0).getValue("reason"))
                .isEqualTo("Audit observation OBS/1: Order");
        assertThat(row.getAllValues().get(0).getValue("arrear")).isEqualTo(12L);
    }

    // ---------------------------------------------------------------- branch edges

    /**
     * SRS-13-18: a revision for a member with no Basic on record (none in the history, none on the
     * employee) is sent with no pay impact rather than a guessed one.
     */
    @Test
    void SRS_13_18_aRevisionOfAMemberWithoutABasicOnRecordCarriesNoImpact() {
        member(null, DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(null);
        scale(new BigDecimal("64300"), null, STAGES);
        copyStoredBy("hr@x");
        service.submit(context, kase("INDIVIDUAL_REVISION", new BigDecimal("62400"), null));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        assertThat(sent.getValue().impactValue()).isEqualByComparingTo(BigDecimal.ZERO);
        JsonNode payload = json.readTree(sent.getValue().payload());
        assertThat(payload.path("old").asString()).isEmpty();
        assertThat(payload.path("revised").asString()).isEqualTo("62400");
    }

    /** SRS-13-19 (US-GAP-52): a stagnation case carries the test that fired it to the Checker. */
    @Test
    void SRS_13_19_aSubmittedStagnationCaseCarriesItsTest() {
        member(new BigDecimal("64300"), DAY);
        when(basic.basicOn(7L, DAY)).thenReturn(new BigDecimal("64300"));
        scale(new BigDecimal("64300"), new BigDecimal("1500"), STAGES);
        rule("STAGNATION_QUALIFYING_MONTHS", "12");
        rule("STAGNATION_FREQUENCY_CAP", "2");
        history("64300", DAY.minusYears(2), "62400", DAY.minusYears(3));
        copyStoredBy("hr@x");
        service.submit(context, kase("STAGNATION_INCREMENT", null, null));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), isNull(OtpProof.class));
        JsonNode payload = json.readTree(sent.getValue().payload());
        assertThat(payload.path("revised").asString()).isEqualTo("65800");
        assertThat(payload.path("test").asString()).contains("since " + DAY.minusYears(2));
        assertThat(sent.getValue().impactValue()).isEqualByComparingTo("1500");
    }
}
