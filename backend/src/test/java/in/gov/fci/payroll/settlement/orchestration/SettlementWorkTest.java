package in.gov.fci.payroll.settlement.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.allowance.api.AllowanceClearance;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.leave.api.LeaveEncashments;
import in.gov.fci.payroll.loans.api.LoanClearance;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.penalty.api.PenaltyClearance;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.settlement.business.SettlementErrorCode;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O-05 computation and the establishment no-dues (plan 11; SRS-10-1, SRS-10-4, SRS-10-7, SRS-10-13,
 * SRS-10-27, SRS-10-28; D11-3): a member still in service or without a separation date is not
 * settled; only an initiated or computed case is computed, once its clearances are received; a
 * source that cannot answer - part-month pay, the pending salary, the leave encashment - stops the
 * computation; every outstanding loan, penalty and PLI advance is itemised at its amount now, a
 * changed amount re-read and audited; the no-dues takes dues of zero or more with a remark.
 */
class SettlementWorkTest {

    private static final LocalDate SEPARATED = LocalDate.of(2026, 9, 30);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final SettlementClearances clearances = mock(SettlementClearances.class);
    private final PendingSalary pending = mock(PendingSalary.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final LeaveEncashments encashments = mock(LeaveEncashments.class);
    private final LoanClearance loans = mock(LoanClearance.class);
    private final PenaltyClearance penalties = mock(PenaltyClearance.class);
    private final AllowanceClearance allowances = mock(AllowanceClearance.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final AuditService audit = mock(AuditService.class);
    private final SettlementWork work =
            new SettlementWork(
                    jdbc,
                    policy,
                    clearances,
                    pending,
                    payroll,
                    encashments,
                    loans,
                    penalties,
                    allowances,
                    statuses,
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

    private void caseRow(
            int stage, String status, String employment, LocalDate separation, Long location) {
        Map<String, Object> r = new HashMap<>();
        r.put("CASE_REFERENCE", "FNF-2026-00005");
        r.put("EMPLOYEE_ID", 7L);
        r.put("LOCATION_ID", location);
        r.put("SEPARATION_DATE", Date.valueOf(separation));
        r.put("CASE_STAGE", stage);
        r.put("SETTLEMENT_ID", 9L);
        r.put("SETTLEMENT_STATUS", status);
        r.put("EMPLOYMENT_STATUS", employment);
        when(jdbc.queryForList(contains("FROM settlement_case c JOIN employee"), eq(5L)))
                .thenReturn(List.of(r));
    }

    private ErrorCode refusal(Runnable call) {
        return ((FciException) catchThrowable(call::run)).getErrorCode();
    }

    private void sources() {
        when(payroll.paidInRegularRun(eq(7L), anyString())).thenReturn(true);
        when(encashments.atSeparation(7L, SEPARATED))
                .thenReturn(
                        new LeaveEncashments.Valuation(
                                BigDecimal.TEN,
                                BigDecimal.TEN,
                                false,
                                new BigDecimal("75000"),
                                new BigDecimal("30"),
                                new BigDecimal("2500"),
                                new BigDecimal("25000"),
                                3001,
                                "demo"));
        when(jdbc.queryForObject(contains("FROM arrear_detail"), eq(BigDecimal.class), eq(7L)))
                .thenReturn(BigDecimal.ZERO);
        when(jdbc.queryForObject(contains("FROM fs_ex_gratia_entry"), eq(BigDecimal.class), eq(9L)))
                .thenReturn(BigDecimal.ZERO);
        when(jdbc.queryForObject(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " recovery_source"),
                        eq(BigDecimal.class),
                        eq(9L),
                        anyString()))
                .thenReturn(BigDecimal.ZERO);
    }

    @Test
    void SRS_10_1_anUnknownCaseIsNotFoundAndAMemberInServiceIsNotSettled() {
        when(jdbc.queryForList(contains("FROM settlement_case c JOIN employee"), eq(5L)))
                .thenReturn(List.of());
        assertThat(refusal(() -> work.compute(context, 5L))).isEqualTo(CommonErrorCode.NOT_FOUND);
        caseRow(3, "INITIATED", "IN_SERVICE", SEPARATED, null);
        assertThat(refusal(() -> work.compute(context, 5L)))
                .isEqualTo(SettlementErrorCode.NOT_SETTLEABLE);
    }

    /**
     * SRS-10-14 (US-GAP-218): a clearance still outstanding (stage Clearances raised) does not hold
     * the computation; it blocks only the move to Approved. Stage Computed follows.
     */
    @Test
    void SRS_10_14_aCaseWithAClearanceOutstandingIsComputed() {
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(2);
        sources();
        when(loans.outstanding(7L)).thenReturn(List.of());
        when(penalties.outstanding(7L)).thenReturn(List.of());
        when(allowances.outstanding(7L)).thenReturn(List.of());
        work.compute(context, 5L);
        verify(jdbc).update(contains("settlement_status = 'COMPUTED'"), any(Object[].class));
        verify(jdbc)
                .update(
                        contains("UPDATE settlement_case SET case_stage = ?, owner_role_code"),
                        eq(4),
                        eq("maker@x"),
                        eq(5L));
    }

    /**
     * SRS-10-3 / 10-14 (US-GAP-218): dues itemised by the no-dues after the settlement was computed
     * send it back for recomputation (status Initiated, stage back to Clearances raised), audited;
     * a nil answer, or one before the computation, changes no computation.
     */
    @Test
    void SRS_10_3_duesItemisedAfterTheComputationSendItBackForRecomputation() {
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("establishment"),
                        anyString()))
                .thenReturn(List.of());
        caseRow(4, "COMPUTED", "RETIRED", SEPARATED, 10L);
        work.establishmentNoDues(context, 5L, BigDecimal.ZERO, "Nil");
        verify(jdbc, never())
                .update(contains("SET settlement_status = 'INITIATED'"), any(Object[].class));
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        work.establishmentNoDues(context, 5L, new BigDecimal("500"), "Licence fee");
        verify(jdbc, never())
                .update(contains("SET settlement_status = 'INITIATED'"), any(Object[].class));
        caseRow(4, "COMPUTED", "RETIRED", SEPARATED, 10L);
        work.establishmentNoDues(context, 5L, new BigDecimal("500"), "Licence fee");
        verify(jdbc)
                .update(
                        contains("SET settlement_status = 'INITIATED' WHERE settlement_status ="),
                        eq(5L));
        verify(jdbc)
                .update(
                        contains("SET case_stage = ? WHERE settlement_case_id = ? AND case_stage"),
                        eq(2),
                        eq(5L),
                        eq(4));
        org.mockito.ArgumentCaptor<AuditService.Entry> entries =
                org.mockito.ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, org.mockito.Mockito.times(4)).record(eq("maker@x"), entries.capture());
        assertThat(entries.getAllValues())
                .anySatisfy(
                        e -> {
                            assertThat(e.fieldName()).isEqualTo("settlement_status");
                            assertThat(e.newValue()).isEqualTo("INITIATED");
                        });
        verify(clearances, org.mockito.Mockito.times(3)).advance(5L);
    }

    /**
     * SRS-10-30 / SRS-10-14 (Wave A review): a settlement waiting for the Payroll Checker is never
     * recomputed - not even after dues itemised later sent it back to Initiated - so the item
     * submitted always stands for the settlement as then computed; once the Checker returns it, it
     * is recomputed.
     */
    @Test
    void SRS_10_30_aSettlementAwaitingTheCheckerIsNotRecomputedUntilReturned() {
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        when(statuses.pending(SettlementWork.ENTITY, "FNF-2026-00005"))
                .thenReturn(
                        List.of(
                                new ApprovalStatuses.Pending(
                                        61L,
                                        "FNF-2026-00005",
                                        "{}",
                                        "maker@x",
                                        Instant.parse("2026-10-27T04:00:00Z"))));
        FciException held = (FciException) catchThrowable(() -> work.compute(context, 5L));
        assertThat(held.getErrorCode()).isEqualTo(SettlementErrorCode.APPROVAL_PENDING);
        assertThat((String) held.getMessageArgs()[0]).contains("return it to recompute");
        verify(clearances, never()).refresh(anyLong(), anyLong());
        verify(jdbc, never())
                .update(contains("settlement_status = 'COMPUTED'"), any(Object[].class));

        when(statuses.pending(SettlementWork.ENTITY, "FNF-2026-00005")).thenReturn(List.of());
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(2);
        sources();
        work.compute(context, 5L);
        verify(jdbc).update(contains("settlement_status = 'COMPUTED'"), any(Object[].class));
    }

    @Test
    void SRS_10_13_onlyAnInitiatedOrComputedCaseWithItsClearancesReceivedIsComputed() {
        caseRow(6, "APPROVED", "RETIRED", SEPARATED, 10L);
        assertThat(refusal(() -> work.compute(context, 5L))).isEqualTo(SettlementErrorCode.STAGE);
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(null);
        assertThat(refusal(() -> work.compute(context, 5L)))
                .as("stage 1 when unread")
                .isEqualTo(SettlementErrorCode.STAGE);
    }

    // SC-11-0039: a source that cannot answer blocks the finalisation; never settled on partial
    // data.
    @Test
    void SRS_10_28_aSourceThatCannotAnswerStopsTheComputation() {
        caseRow(3, "INITIATED", "RETIRED", LocalDate.of(2026, 9, 15), 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(3);
        when(payroll.paidInRegularRun(eq(7L), anyString())).thenReturn(false);
        assertThat(refusal(() -> work.compute(context, 5L)))
                .as("part-month pay")
                .isEqualTo(SettlementErrorCode.SOURCE);

        caseRow(3, "INITIATED", "RETIRED", SEPARATED, 10L);
        when(pending.compute(any(), any()))
                .thenThrow(new PendingSalary.NotComputable("no DA rate"));
        assertThat(refusal(() -> work.compute(context, 5L)))
                .as("pending salary")
                .isEqualTo(SettlementErrorCode.SOURCE);

        when(payroll.paidInRegularRun(eq(7L), anyString())).thenReturn(true);
        when(encashments.atSeparation(7L, SEPARATED))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        assertThat(refusal(() -> work.compute(context, 5L)))
                .as("leave encashment")
                .isEqualTo(SettlementErrorCode.SOURCE);
        verify(jdbc, never()).update(contains("UPDATE final_settlement"), any(Object[].class));
    }

    private void unpaidSeparationMonth() {
        caseRow(3, "COMPUTED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(3);
        sources();
        when(payroll.paidInRegularRun(eq(7L), anyString())).thenReturn(false);
        when(loans.outstanding(7L)).thenReturn(List.of());
        when(penalties.outstanding(7L)).thenReturn(List.of());
        when(allowances.outstanding(7L)).thenReturn(List.of());
    }

    /** SRS-10-13: the separation month the regular run has not paid is its net pay. */
    @Test
    void SRS_10_13_theUnpaidSeparationMonthIsItsNetPay() {
        unpaidSeparationMonth();
        when(pending.compute(any(), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                true, null, null, null, new BigDecimal("41000"), List.of(), "h"));
        work.compute(context, 5L);
        verify(jdbc)
                .update(
                        contains("settlement_status = 'COMPUTED'"),
                        eq(new BigDecimal("41000")),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    /** SRS-1-41 (l.3581): a Salary Stop suppresses the period's pay, so nothing is pending. */
    @Test
    void SRS_1_41_aSalaryStoppedMonthIsNil() {
        unpaidSeparationMonth();
        when(pending.compute(any(), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                false, "STOP_PAYMENT", null, null, null, List.of(), "h"));
        work.compute(context, 5L);
        verify(jdbc)
                .update(
                        contains("settlement_status = 'COMPUTED'"),
                        eq(BigDecimal.ZERO),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    /**
     * SRS-10-28 (l.2884): pay held or withheld (it accrues until a release order, SRS-1-41), a
     * member with no Basic pay, or a result with no net is a due that cannot be stated: the
     * computation stops, with the reason, and is never settled as nil.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"SALARY_WITHHELD", "HOLD_FLAG", "ABSCONDING", "NO_BASIC_PAY"})
    void SRS_10_28_aMonthThatYieldsNoAmountStopsTheComputation(String reason) {
        unpaidSeparationMonth();
        when(pending.compute(any(), any()))
                .thenReturn(
                        new PendingSalary.Result(
                                false,
                                reason,
                                null,
                                null,
                                new BigDecimal("41000"),
                                List.of(),
                                "h"));
        FciException refused = (FciException) catchThrowable(() -> work.compute(context, 5L));
        assertThat(refused.getErrorCode()).isEqualTo(SettlementErrorCode.SOURCE);
        assertThat(refused.getMessageArgs()[0].toString()).contains(reason);
        verify(jdbc, never())
                .update(contains("settlement_status = 'COMPUTED'"), any(Object[].class));
    }

    @Test
    void SRS_10_28_aComputedResultWithNoNetStopsTheComputation() {
        unpaidSeparationMonth();
        when(pending.compute(any(), any()))
                .thenReturn(new PendingSalary.Result(true, null, null, null, null, List.of(), "h"));
        assertThat(refusal(() -> work.compute(context, 5L))).isEqualTo(SettlementErrorCode.SOURCE);
    }

    /**
     * SRS-8-16 (l.3053), UC-02, A-08: only arrears the Checker approved are summed, and an arrear
     * awaiting its decision holds the computation (SRS-10-28): neither paid unapproved nor lost.
     */
    @Test
    void SRS_8_16_onlyApprovedArrearsAreSummedAndAnUndecidedOneHoldsTheComputation() {
        caseRow(3, "COMPUTED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(3);
        sources();
        when(jdbc.queryForObject(
                        contains("arrear_status = 'CALCULATED'"), eq(BigDecimal.class), eq(7L)))
                .thenReturn(BigDecimal.ONE);
        when(jdbc.queryForObject(
                        contains("arrear_status = 'APPROVED'"), eq(BigDecimal.class), eq(7L)))
                .thenReturn(new BigDecimal("700"));
        when(loans.outstanding(7L)).thenReturn(List.of());
        when(penalties.outstanding(7L)).thenReturn(List.of());
        when(allowances.outstanding(7L)).thenReturn(List.of());
        assertThat(refusal(() -> work.compute(context, 5L))).isEqualTo(SettlementErrorCode.SOURCE);
        verify(jdbc, never())
                .update(contains("settlement_status = 'COMPUTED'"), any(Object[].class));

        when(jdbc.queryForObject(
                        contains("arrear_status = 'CALCULATED'"), eq(BigDecimal.class), eq(7L)))
                .thenReturn(BigDecimal.ZERO);
        work.compute(context, 5L);
        verify(jdbc)
                .update(
                        contains("settlement_status = 'COMPUTED'"),
                        any(),
                        any(),
                        eq(new BigDecimal("700")),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        verify(jdbc, never())
                .queryForObject(contains("IN ('CALCULATED'"), eq(BigDecimal.class), any());
    }

    // SC-11-0047: one itemised row per recovery with its type, reference and amount.
    @Test
    void SRS_10_4_everyOutstandingAccountIsItemisedAndAChangedAmountReReadAndAudited() {
        caseRow(4, "COMPUTED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(4);
        sources();
        when(loans.outstanding(7L))
                .thenReturn(
                        List.of(
                                new LoanClearance.Outstanding(
                                        1L, "LOAN", "HBA", "L/1", new BigDecimal("5000"))));
        when(penalties.outstanding(7L))
                .thenReturn(
                        List.of(
                                new PenaltyClearance.Outstanding(
                                        2L, "PEN-1", "O/1", new BigDecimal("700"))));
        when(allowances.outstanding(7L))
                .thenReturn(
                        List.of(
                                new AllowanceClearance.Outstanding(
                                        3L, "PLI/" + "x".repeat(120), new BigDecimal("900"))));
        // The loan is new, the penalty unchanged, the PLI advance changed since the last
        // computation.
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("loan_header"),
                        anyString()))
                .thenReturn(List.of());
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("penalty_order"),
                        anyString()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "RECOVERY_DETAIL_ID",
                                        21L,
                                        "RECOVERY_AMOUNT",
                                        new BigDecimal("700.00"))));
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("pli_policy"),
                        anyString()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "RECOVERY_DETAIL_ID",
                                        22L,
                                        "RECOVERY_AMOUNT",
                                        new BigDecimal("1000"))));
        work.compute(context, 5L);
        verify(jdbc).update(contains("INSERT INTO fs_recovery_detail"), any(Object[].class));
        verify(jdbc)
                .update(
                        contains("UPDATE fs_recovery_detail SET recovery_amount"),
                        eq(new BigDecimal("900")),
                        eq(22L));
        verify(audit, org.mockito.Mockito.times(2)).record(eq("maker@x"), any());
    }

    /**
     * SRS-10-13 / BR-FNF-001 (SC-11-0013): the net pay of a month after the separation month that a
     * regular run paid is itemised as excess wages; a month of nil or no net pay is not.
     */
    @Test
    void SRS_10_13_salaryPaidForAMonthAfterTheSeparationIsItemisedAsExcessWages() {
        caseRow(4, "COMPUTED", "RETIRED", SEPARATED, 10L);
        when(jdbc.queryForObject(
                        contains("SELECT case_stage FROM settlement_case"),
                        eq(Integer.class),
                        eq(5L)))
                .thenReturn(4);
        sources();
        when(payroll.paidAfter(7L, java.time.YearMonth.from(SEPARATED)))
                .thenReturn(
                        List.of(
                                new PayrollRecords.PaidMonth("202610", new BigDecimal("41250.00")),
                                new PayrollRecords.PaidMonth("202611", BigDecimal.ZERO),
                                new PayrollRecords.PaidMonth("202612", null)));
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("payroll_result"),
                        anyString()))
                .thenReturn(List.of());
        work.compute(context, 5L);
        verify(jdbc)
                .update(
                        contains("INSERT INTO fs_recovery_detail"),
                        eq(9L),
                        eq("EXCESS_WAGES"),
                        eq("payroll_result"),
                        eq("202610 salary paid after separation"),
                        any(),
                        eq(new BigDecimal("41250.00")));
        verify(jdbc, never())
                .update(
                        contains("INSERT INTO fs_recovery_detail"),
                        eq(9L),
                        eq("EXCESS_WAGES"),
                        eq("payroll_result"),
                        eq("202611 salary paid after separation"),
                        any(),
                        any());
    }

    @Test
    void SRS_10_27_theNoDuesTakesDuesOfZeroOrMoreWithARemarkAndItemisesTheDues() {
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        for (Object[] bad :
                new Object[][] {
                    {null, "r"},
                    {new BigDecimal("-1"), "r"},
                    {BigDecimal.ZERO, null},
                    {BigDecimal.ZERO, " "}
                }) {
            assertThat(
                            refusal(
                                    () ->
                                            work.establishmentNoDues(
                                                    context,
                                                    5L,
                                                    (BigDecimal) bad[0],
                                                    (String) bad[1])))
                    .isEqualTo(SettlementErrorCode.ENTRY);
        }
        caseRow(6, "APPROVED", "RETIRED", SEPARATED, 10L);
        assertThat(refusal(() -> work.establishmentNoDues(context, 5L, BigDecimal.ZERO, "Nil")))
                .isEqualTo(SettlementErrorCode.STAGE);
        caseRow(2, "INITIATED", "RETIRED", SEPARATED, 10L);
        work.establishmentNoDues(context, 5L, BigDecimal.ZERO, " Nil ");
        verify(jdbc, never())
                .update(contains("INSERT INTO fs_recovery_detail"), any(Object[].class));
        when(jdbc.queryForList(
                        contains(
                                "FROM fs_recovery_detail WHERE settlement_id = ? AND"
                                        + " source_entity"),
                        eq(9L),
                        eq("establishment"),
                        anyString()))
                .thenReturn(List.of());
        work.establishmentNoDues(
                context, 5L, new BigDecimal("500"), "Licence fee " + "y".repeat(1100));
        verify(jdbc).update(contains("INSERT INTO fs_recovery_detail"), any(Object[].class));
        verify(clearances, org.mockito.Mockito.times(2)).advance(5L);
        assertThat(SettlementWork.plain(null)).isEqualTo("0");
        assertThat(SettlementWork.plain(new BigDecimal("12.50"))).isEqualTo("12.5");
        verify(loans, never()).outstanding(anyLong());
    }
}
