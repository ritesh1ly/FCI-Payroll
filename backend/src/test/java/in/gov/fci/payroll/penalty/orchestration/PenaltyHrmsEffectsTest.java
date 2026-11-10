package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.penalty.api.PenaltyPayEffects;
import in.gov.fci.payroll.penalty.business.PenaltyErrorCode;
import in.gov.fci.payroll.penalty.business.model.PenaltyOrder;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * An employee penalty from HRMS (plan 12 D12-2; SRS-14-1, SRS-14-12, SRS-1-28): the record is an
 * object with a nature of the list, a printable reference, an effective date and a period in order;
 * a recovery carries valid terms, any other nature no amount; an approved recovery starts no
 * earlier than the member's open month (the next once this month is locked) and is back-dated when
 * its date is before it.
 */
class PenaltyHrmsEffectsTest {

    private static final Instant DECIDED = Instant.parse("2026-10-27T05:00:00Z");
    private final PenaltyWriter writer = mock(PenaltyWriter.class);
    private final ReferenceData references = mock(ReferenceData.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final PenaltyPayEffects pay = mock(PenaltyPayEffects.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final PenaltyHrmsEffects effects =
            new PenaltyHrmsEffects(
                    writer,
                    references,
                    employees,
                    org,
                    payroll,
                    pay,
                    worklist,
                    JsonMapper.builder().build());

    PenaltyHrmsEffectsTest() {
        when(references.contains(eq(PenaltyOrderService.NATURES), anyString()))
                .thenAnswer(call -> !"FINE".equals(call.getArgument(1)));
    }

    private static final String REDUCTION =
            "{\"nature\":\"BASIC_REDUCTION\",\"orderReference\":\"VIG/9\","
                    + "\"effectiveDate\":\"2026-08-01\",\"reducedBasic\":48000}";

    private HrmsTaskEffects.Task task(String value) {
        PenaltyOrder order = mock(PenaltyOrder.class);
        when(order.getPenaltyRef()).thenReturn("PEN-1");
        when(writer.create(any(), anyString(), anyLong(), any())).thenReturn(order);
        return new HrmsTaskEffects.Task(
                7L, "HCA-1", 9L, PenaltyHrmsEffects.FIELD, value, null, "HR-1");
    }

    @Test
    void SRS_14_8_aBasicReductionFromHrmsWritesTheBasicItGives() {
        effects.apply(
                task(REDUCTION), "ddo@x", 44L, java.time.Instant.parse("2026-08-02T05:00:00Z"));

        ArgumentCaptor<PenaltyPayEffects.Reduction> r =
                ArgumentCaptor.forClass(PenaltyPayEffects.Reduction.class);
        verify(pay).reduceBasic(r.capture());
        assertThat(r.getValue().basic()).isEqualByComparingTo("48000");
        assertThat(r.getValue().penaltyRef()).isEqualTo("PEN-1");
        assertThat(r.getValue().sourceCode()).isEqualTo("HRMS");
        verify(worklist, never()).raise(any(), any());
    }

    @Test
    void SRS_14_8_aBasicReductionWithNoFigureIsRecordedAndListedNeverGuessed() {
        effects.apply(
                task(
                        "{\"nature\":\"BASIC_REDUCTION\",\"orderReference\":\"VIG/9\","
                                + "\"effectiveDate\":\"2026-08-01\"}"),
                "ddo@x",
                44L,
                java.time.Instant.parse("2026-08-02T05:00:00Z"));

        verify(pay, never()).reduceBasic(any());
        verify(worklist).raise(eq("ddo@x"), any());
    }

    @Test
    void SRS_13_23_aReductionThatCannotBeTakenIsRefusedAtIntake() {
        when(pay.reductionDefect(eq(9L), any(), any())).thenReturn("not a stage");

        assertThatThrownBy(() -> effects.check(PenaltyHrmsEffects.FIELD, 9L, REDUCTION))
                .isInstanceOf(FciException.class)
                .hasFieldOrPropertyWithValue("errorCode", PenaltyErrorCode.ORDER);
    }

    @Test
    void SRS_14_8_aReducedBasicIsANumberOnABasicReductionOnly() {
        String text =
                "{\"nature\":\"BASIC_REDUCTION\",\"orderReference\":\"V\","
                        + "\"effectiveDate\":\"2026-08-01\",\"reducedBasic\":\"x\"}";
        String stop =
                "{\"nature\":\"INCREMENT_STOP\",\"orderReference\":\"V\","
                        + "\"effectiveDate\":\"2026-08-01\",\"reducedBasic\":1}";
        assertThatThrownBy(() -> effects.check(PenaltyHrmsEffects.FIELD, 9L, text))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> effects.check(PenaltyHrmsEffects.FIELD, 9L, stop))
                .isInstanceOf(FciException.class);
        effects.check(PenaltyHrmsEffects.FIELD, 9L, REDUCTION);
    }

    private static final String RECOVERY =
            "{\"nature\":\"RECOVERY\",\"orderReference\":\"PEN/9\",\"effectiveDate\":\"2026-08-01\","
                + "\"amount\":1200,\"instalments\":2,\"startYymm\":\"202609\"}";

    @Test
    void SRS_14_1_theRecordIsAnObjectWithANatureReferenceDateAndOrderedPeriod() {
        String[] bad = {
            null,
            "[1]",
            "{",
            "{\"nature\":\"FINE\"}",
            "{\"nature\":\"CENSURE\",\"orderReference\":\" \"}",
            "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\"}",
            "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\",\"effectiveDate\":\"01-08-2026\"}",
            "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\",\"effectiveDate\":\"2026-08-01\",\"periodTo\":\"2026-07-01\"}",
            "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\",\"effectiveDate\":\"2026-08-01\",\"amount\":5}",
            "{\"nature\":\"RECOVERY\",\"orderReference\":\"P/1\",\"effectiveDate\":\"2026-08-01\",\"amount\":\"x\",\"instalments\":1,\"startYymm\":\"202609\"}",
        };
        for (String v : bad) {
            assertThatThrownBy(() -> effects.check("PENALTY_ORDER", 7L, v))
                    .as(String.valueOf(v))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(PenaltyErrorCode.ORDER);
        }
        assertThatCode(() -> effects.check("PENALTY_ORDER", 7L, RECOVERY))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                effects.check(
                                        "PENALTY_ORDER",
                                        7L,
                                        "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\",\"effectiveDate\":\"2026-08-01\",\"amount\":null,\"periodTo\":\"2026-12-31\"}"))
                .doesNotThrowAnyException();
        assertThat(effects.fields()).containsExactly("PENALTY_ORDER");
    }

    private void cycle(boolean locked) {
        when(employees.member(7L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(7L, "104882", "A", 5L, "IN_SERVICE")));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(3L, "202610"))
                .thenReturn(
                        Optional.of(
                                new PayrollRecords.Cycle(
                                        1L, 3L, "202610", locked ? "LOCKED" : "FROZEN")));
    }

    private PenaltyWriter.Approved applied(String value) {
        effects.apply(
                new HrmsTaskEffects.Task(4L, "HCA-4", 7L, "PENALTY_ORDER", value, null, "HR-1"),
                "ddo@x",
                55L,
                DECIDED);
        ArgumentCaptor<PenaltyWriter.Approved> written =
                ArgumentCaptor.forClass(PenaltyWriter.Approved.class);
        verify(writer, atLeastOnce()).create(written.capture(), eq("ddo@x"), eq(55L), eq(DECIDED));
        return written.getValue();
    }

    @Test
    void SRS_14_12_anApprovedRecoveryStartsNoEarlierThanTheOpenMonthAndIsBackDated() {
        cycle(true);
        PenaltyWriter.Approved a = applied(RECOVERY);
        assertThat(a.startYymm()).as("September and October are closed").isEqualTo("202611");
        assertThat(a.backdated()).isTrue();
        cycle(false);
        assertThat(applied(RECOVERY.replace("202609", "202612")).startYymm()).isEqualTo("202612");
        when(employees.member(anyLong())).thenReturn(Optional.empty());
        PenaltyWriter.Approved censure =
                applied(
                        "{\"nature\":\"CENSURE\",\"orderReference\":\"P/1\",\"effectiveDate\":\"2026-11-01\"}");
        assertThat(censure.startYymm()).isNull();
        assertThat(censure.backdated()).isFalse();
        verify(writer, atLeast(3)).create(any(), any(), any(Long.class), any());
    }
}
