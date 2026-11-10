package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.nps.business.NpsRules;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * US-GAP-291 (SRS-9-23, SRS-G-45; OD-55): a change with pay effect to the NPS record of a month
 * still open, through I-21 field NPS_CHANGE. The value is a consistent record of a month that is
 * not locked and has a record; approved, it replaces the month's MONTHLY record linked to its task,
 * each changed value audited with the approval; a month that locked meanwhile is settled as a
 * back-dated change on I-08.
 */
class NpsChangeEffectsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final NpsExceptions exceptions = mock(NpsExceptions.class);
    private final NpsHrmsEffects effects =
            new NpsHrmsEffects(
                    jdbc,
                    org,
                    payroll,
                    retro,
                    audit,
                    JsonMapper.builder().build(),
                    employees,
                    exceptions);

    NpsChangeEffectsTest() {
        when(employees.locationOf(7L)).thenReturn(Optional.of(5L));
        when(employees.eisOf(7L)).thenReturn(Optional.of("870777"));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(retro.known(any())).thenReturn(42L);
    }

    private void cycle(String status) {
        when(payroll.cycle(3L, "202607"))
                .thenReturn(
                        status == null
                                ? Optional.empty()
                                : Optional.of(new PayrollRecords.Cycle(1L, 3L, "202607", status)));
    }

    /** When HRMS last sent the month's record, as the task's basis names it. */
    private static final String RECEIVED = "2026-10-01T04:00:00Z";

    private static final String BASIS = "77@" + RECEIVED;

    private void monthly(boolean held) {
        monthly(held, "CONSISTENT", RECEIVED);
    }

    private void monthly(boolean held, String mismatch, String receivedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("NPS_DEDUCTION_ID", 77L);
        row.put("HRMS_NPS_FLAG", "Y");
        row.put("PRAN_REFERENCE", "110012345678");
        row.put("EMPLOYEE_AMOUNT", new BigDecimal("5000.00"));
        row.put("EMPLOYER_AMOUNT", new BigDecimal("7000.00"));
        row.put("HRMS_EFFECTIVE_FROM", null);
        row.put("MISMATCH_STATUS", mismatch);
        row.put("APPLY_STATUS", "RECEIVED");
        row.put("HRMS_RECEIVED_AT", java.sql.Timestamp.from(Instant.parse(receivedAt)));
        when(jdbc.queryForList(contains("record_type = 'MONTHLY'"), any(Object[].class)))
                .thenReturn(held ? List.of(row) : List.of());
    }

    private static String value(String employee) {
        return value(employee, null, BASIS);
    }

    private static String value(String employee, String effectiveFrom, String basis) {
        return "{\"payrollYymm\":\"202607\",\"npsMember\":true,\"pran\":\"110012345678\","
                + "\"employeeAmount\":"
                + employee
                + ",\"employerAmount\":7000,\"effectiveFrom\":"
                + (effectiveFrom == null ? "null" : "\"" + effectiveFrom + "\"")
                + (basis == null ? "" : ",\"basis\":\"" + basis + "\"")
                + "}";
    }

    private void refused(String value, String defect) {
        assertThatThrownBy(() -> effects.check(NpsHrmsEffects.CHANGE_FIELD, 7L, value))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly(defect));
    }

    private void apply(String value) {
        effects.apply(
                new HrmsTaskEffects.Task(
                        9L,
                        "HCA-9",
                        7L,
                        NpsHrmsEffects.CHANGE_FIELD,
                        value,
                        LocalDate.of(2026, 7, 1),
                        null),
                "ddo@x",
                55L,
                Instant.parse("2026-10-27T05:00:00Z"));
    }

    @Test
    void SRS_9_23_theChangeIsAConsistentRecordOfAnOpenMonthThatHasARecord() {
        assertThat(effects.fields())
                .containsExactlyInAnyOrder(NpsHrmsEffects.FIELD, NpsHrmsEffects.CHANGE_FIELD);
        monthly(true);
        cycle(null);
        assertThatCode(() -> effects.check(NpsHrmsEffects.CHANGE_FIELD, 7L, value("5400")))
                .as("no cycle yet")
                .doesNotThrowAnyException();
        cycle("COMPUTED");
        assertThatCode(() -> effects.check(NpsHrmsEffects.CHANGE_FIELD, 7L, value("5400")))
                .doesNotThrowAnyException();
        refused(value("null"), "FLAG_WITHOUT_AMOUNT");
        cycle("LOCKED");
        refused(value("5400"), "LOCKED");
        cycle("COMPUTED");
        monthly(false);
        refused(value("5400"), "NO_RECORD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_9_23_anApprovedChangeReplacesTheMonthlyRecordAuditedWithItsApproval() {
        monthly(true);
        cycle("COMPUTED");
        apply(value("5400"));
        verify(jdbc)
                .update(contains("UPDATE nps_deduction SET hrms_nps_flag"), any(Object[].class));
        verify(jdbc)
                .update(
                        contains("SET hrms_change_task_id = ?"),
                        eq(9L),
                        eq(77L),
                        eq(java.sql.Date.valueOf("2026-07-01")));
        ArgumentCaptor<Collection<AuditService.Entry>> entries =
                ArgumentCaptor.forClass(Collection.class);
        verify(audit).recordAll(eq("ddo@x"), entries.capture());
        assertThat(entries.getValue())
                .extracting(e -> e.fieldName() + ":" + e.oldValue() + ">" + e.newValue())
                .as("Wave C review: no effective date sent, none recorded")
                .containsExactlyInAnyOrder("employee_amount:5000>5400");
        assertThat(entries.getValue()).allSatisfy(e -> assertThat(e.approvalId()).isEqualTo(55L));
        verify(retro, never()).known(any());
    }

    @Test
    void SRS_9_23_aMonthLockedMeanwhileSettlesTheApprovedChangeAsBackDated() {
        monthly(true);
        cycle("LOCKED");
        when(jdbc.queryForList(contains("apply_status = 'APPLIED' ORDER BY"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.<String, Object>of(
                                        "RECORD_TYPE",
                                        "MONTHLY",
                                        "DEDUCTED_AMOUNT",
                                        new BigDecimal("5000"))));
        when(jdbc.queryForList(
                        contains("STATUTORY_DEDUCTION"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of(BigDecimal.valueOf(905)));
        apply(value("5400"));
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines())
                .containsExactly(new RetroEvents.Line("202607", 905, new BigDecimal("-400")));
        verify(jdbc, never())
                .update(contains("UPDATE nps_deduction SET hrms_nps_flag"), any(Object[].class));
    }

    @Test
    void SRS_9_23_thePreviewNamesTheValuesBeforeAndAfter() {
        monthly(true);
        assertThat(effects.preview(NpsHrmsEffects.CHANGE_FIELD, 7L, value("5400")))
                .hasValue(
                        "NPS of 202607: flag Y -> Y, employee 5000 -> 5400, employer 7000 ->"
                                + " 7000");
        assertThat(effects.preview(NpsHrmsEffects.FIELD, 7L, value("5400"))).isEmpty();
        monthly(false);
        assertThat(effects.preview(NpsHrmsEffects.CHANGE_FIELD, 7L, value("5400"))).isEmpty();
        verify(audit, never()).record(anyString(), any());
    }

    // US-GAP-296 (SRS-1-64, SRS-9-23): on revocation the NPS of a suspended (locked) month at full
    // pay comes from HRMS as a back-dated record; the difference to what subsistence deducted is
    // recovered on I-08, never recomputed in Payroll.
    @Test
    void SRS_1_64_onRevocationTheFullPayNpsOfASuspendedMonthIsRecoveredAsItsDifference() {
        cycle("LOCKED");
        when(jdbc.queryForList(contains("apply_status = 'APPLIED' ORDER BY"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.<String, Object>of(
                                        "RECORD_TYPE",
                                        "MONTHLY",
                                        "DEDUCTED_AMOUNT",
                                        new BigDecimal("2500"))));
        when(jdbc.queryForList(
                        contains("STATUTORY_DEDUCTION"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of(BigDecimal.valueOf(905)));
        effects.apply(
                new HrmsTaskEffects.Task(
                        10L,
                        "HCA-10",
                        7L,
                        NpsHrmsEffects.FIELD,
                        value("5000"),
                        LocalDate.of(2026, 7, 1),
                        "HR-REV-1"),
                "ddo@x",
                56L,
                Instant.parse("2026-10-27T05:00:00Z"));
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines())
                .containsExactly(new RetroEvents.Line("202607", 905, new BigDecimal("-2500")));
    }

    // Wave C review, finding 1(b): a task is approved only over the record it was made against.
    @Test
    void SRS_9_23_aTaskOverANewerRecordOfTheMonthIsRefusedAsStale() {
        cycle("COMPUTED");
        // HRMS sent A->B (T1) and then A->C (T2); T2 approved rewrote the record: T1 is stale.
        monthly(true, "CONSISTENT", "2026-10-02T04:00:00Z");
        stale(value("5400"));
        // A PRAN change, a change back or a mismatch (and its correction) also rewrote it.
        monthly(true, "FLAG_WITHOUT_AMOUNT", "2026-10-03T04:00:00Z");
        stale(value("5400"));
        monthly(true, "RESOLVED_IN_HRMS", "2026-10-04T04:00:00Z");
        stale(value("5400"));
        monthly(true);
        stale(value("5400", null, "78@" + RECEIVED));
        // The month's record is gone meanwhile: nothing current to approve the task over.
        monthly(false);
        stale(value("5400"));
        verify(jdbc, never())
                .update(contains("UPDATE nps_deduction SET hrms_nps_flag"), any(Object[].class));
        verify(retro, never()).known(any());
    }

    /**
     * Wave C review (SRS-9-23): the basis of a task is the record and when HRMS last sent it - no
     * receipt time yet reads "-", a time not held as a timestamp its text - so a record received
     * after the task was made changes the basis.
     */
    @Test
    void SRS_9_23_theBasisOfATaskIsTheRecordAndItsLastReceipt() {
        assertThat(NpsIntakeService.basis(java.util.Map.of("NPS_DEDUCTION_ID", 77L)))
                .isEqualTo("77@-");
        java.util.Map<String, Object> received = new java.util.HashMap<>();
        received.put("NPS_DEDUCTION_ID", 77);
        received.put("HRMS_RECEIVED_AT", java.time.LocalDateTime.of(2026, 10, 1, 9, 30));
        assertThat(NpsIntakeService.basis(received)).isEqualTo("77@2026-10-01T09:30");
        received.put(
                "HRMS_RECEIVED_AT", java.sql.Timestamp.from(java.time.Instant.parse(RECEIVED)));
        assertThat(NpsIntakeService.basis(received)).isEqualTo(BASIS);
        assertThat(NpsIntakeService.basis(received)).isNotEqualTo("77@-");
    }

    private void stale(String value) {
        assertThatThrownBy(() -> apply(value))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.NPS_STALE);
                            assertThat(ex.getMessageArgs()).containsExactly("202607");
                        });
    }

    // Wave C review, finding 3: the approved record clears (or raises) its X-05 mismatch.
    @Test
    void SRS_9_1a_anApprovedConsistentRecordClearsTheBlockingException() {
        cycle("COMPUTED");
        monthly(true, "FLAG_WITHOUT_AMOUNT", RECEIVED);
        apply(value("5400"));
        verify(exceptions)
                .raiseOrClear("ddo@x", 77L, 7L, 1L, NpsRules.Mismatch.RESOLVED_IN_HRMS, "202607");
        monthly(true);
        cycle(null);
        apply(value("5400"));
        verify(exceptions)
                .raiseOrClear("ddo@x", 77L, 7L, null, NpsRules.Mismatch.CONSISTENT, "202607");
    }

    // Wave C review, finding 5: the effective date HRMS sent is carried in the value, null kept.
    @Test
    void SRS_9_23_theEffectiveDateHrmsSentIsTheOneRecorded() {
        cycle("COMPUTED");
        monthly(true);
        apply(value("5000", "2026-07-05", BASIS));
        ArgumentCaptor<Collection<AuditService.Entry>> entries = auditEntries();
        assertThat(entries.getValue())
                .extracting(e -> e.fieldName() + ":" + e.oldValue() + ">" + e.newValue())
                .containsExactly("hrms_effective_from:null>2026-07-05");
        refused(value("5400", "2026-08-01", BASIS), "EFFECTIVE_DATE");
        refused(value("5400", "not-a-date", BASIS), "EFFECTIVE_DATE");
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<AuditService.Entry>> auditEntries() {
        ArgumentCaptor<Collection<AuditService.Entry>> entries =
                ArgumentCaptor.forClass(Collection.class);
        verify(audit).recordAll(eq("ddo@x"), entries.capture());
        return entries;
    }

    // Wave C review, finding 8: an NPS change shows no month-by-month impact.
    @Test
    void SRS_9_23_anNpsChangeShowsNoMonthByMonthPayImpact() {
        assertThat(effects.showsPayImpact(NpsHrmsEffects.CHANGE_FIELD)).isFalse();
        assertThat(effects.showsPayImpact(NpsHrmsEffects.FIELD)).isTrue();
    }

    // Wave C review, finding 2: a resend is recognised by its value, never by its timing.
    @Test
    void SRS_9_23_aResendIsTheSameRecordAndAnyDifferenceIsNot() {
        JsonMapper json = JsonMapper.builder().build();
        NpsReceipts.Receipt r =
                new NpsReceipts.Receipt(
                        "870777",
                        "202607",
                        true,
                        "110012345678",
                        new BigDecimal("5400.00"),
                        new BigDecimal("7000"),
                        null);
        assertThat(NpsHrmsEffects.sameRecord(json, value("5400"), r)).isTrue();
        assertThat(NpsHrmsEffects.sameRecord(json, value("5401"), r)).isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, value("null"), r)).isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, value("5400", "2026-07-01", BASIS), r))
                .isFalse();
        assertThat(
                        NpsHrmsEffects.sameRecord(
                                json,
                                value("5400"),
                                new NpsReceipts.Receipt(
                                        "870777",
                                        "202608",
                                        true,
                                        "110012345678",
                                        new BigDecimal("5400"),
                                        new BigDecimal("7000"),
                                        null)))
                .isFalse();
        assertThat(
                        NpsHrmsEffects.sameRecord(
                                json,
                                value("5400"),
                                new NpsReceipts.Receipt(
                                        "870777",
                                        "202607",
                                        false,
                                        "110012345678",
                                        new BigDecimal("5400"),
                                        new BigDecimal("7000"),
                                        null)))
                .isFalse();
        assertThat(
                        NpsHrmsEffects.sameRecord(
                                json,
                                value("5400"),
                                new NpsReceipts.Receipt(
                                        "870777",
                                        "202607",
                                        true,
                                        "110012345679",
                                        new BigDecimal("5400"),
                                        new BigDecimal("7000"),
                                        null)))
                .isFalse();
        assertThat(
                        NpsHrmsEffects.sameRecord(
                                json,
                                value("5400"),
                                new NpsReceipts.Receipt(
                                        "870777",
                                        "202607",
                                        true,
                                        "110012345678",
                                        new BigDecimal("5400"),
                                        null,
                                        null)))
                .isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, "not json", r)).isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, "[1]", r)).isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, null, r)).isFalse();
        assertThat(NpsHrmsEffects.sameRecord(json, value("\"x\""), r)).isFalse();
        assertThat(NpsHrmsEffects.monthOf(json, value("5400"))).isEqualTo("202607");
        assertThat(NpsHrmsEffects.monthOf(json, "not json")).isNull();
    }

    private void taskReceived(String at) {
        when(jdbc.queryForList(
                        contains("SELECT received_at FROM hrms_change_task"),
                        eq(java.sql.Timestamp.class),
                        eq(9L)))
                .thenReturn(
                        at == null
                                ? List.of()
                                : List.of(java.sql.Timestamp.from(Instant.parse(at))));
    }

    /**
     * Wave D review 2 (SRS-9-23): a task opened before its value carried the basis is compared by
     * time: approved while the month's record was last received no later than the task (equal is
     * not newer), refused once a newer record came, or when the task's receipt is unknown.
     */
    @Test
    void SRS_9_23_aLegacyTaskWithoutABasisStandsUntilANewerRecordCame() {
        cycle("COMPUTED");
        monthly(true);
        taskReceived("2026-10-01T04:00:01Z");
        assertThatCode(() -> apply(value("5400", null, null))).doesNotThrowAnyException();
        taskReceived(RECEIVED);
        assertThatCode(() -> apply(value("5400", null, null))).doesNotThrowAnyException();
        taskReceived("2026-10-01T03:59:59Z");
        stale(value("5400", null, null));
        taskReceived(null);
        stale(value("5400", null, null));
        // A record never stamped with a receipt time is not newer than the task.
        Map<String, Object> row = new HashMap<>();
        row.put("NPS_DEDUCTION_ID", 77L);
        row.put("HRMS_NPS_FLAG", "Y");
        row.put("MISMATCH_STATUS", "CONSISTENT");
        row.put("HRMS_RECEIVED_AT", null);
        when(jdbc.queryForList(contains("record_type = 'MONTHLY'"), any(Object[].class)))
                .thenReturn(List.of(row));
        taskReceived("2026-10-01T04:00:00Z");
        assertThatCode(
                        () ->
                                effects.beforeApproval(
                                        new HrmsTaskEffects.Task(
                                                9L,
                                                "HCA-9",
                                                7L,
                                                NpsHrmsEffects.CHANGE_FIELD,
                                                value("5400", null, null),
                                                LocalDate.of(2026, 7, 1),
                                                null)))
                .doesNotThrowAnyException();
    }

    /**
     * Wave D review 2 (SRS-G-21): the stale check runs before the Checker's OTP - a stale change is
     * refused there; a back-dated record (NPS_BACK_DATED) has no such precondition.
     */
    @Test
    void SRS_G_21_aStaleChangeIsRefusedBeforeTheOtp() {
        monthly(true, "CONSISTENT", "2026-10-02T04:00:00Z");
        HrmsTaskEffects.Task stale =
                new HrmsTaskEffects.Task(
                        9L,
                        "HCA-9",
                        7L,
                        NpsHrmsEffects.CHANGE_FIELD,
                        value("5400"),
                        LocalDate.of(2026, 7, 1),
                        null);
        assertThatThrownBy(() -> effects.beforeApproval(stale))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.NPS_STALE));
        monthly(true);
        assertThatCode(() -> effects.beforeApproval(stale)).doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                effects.beforeApproval(
                                        new HrmsTaskEffects.Task(
                                                9L,
                                                "HCA-9",
                                                7L,
                                                NpsHrmsEffects.FIELD,
                                                "not read",
                                                LocalDate.of(2026, 7, 1),
                                                null)))
                .doesNotThrowAnyException();
    }
}
