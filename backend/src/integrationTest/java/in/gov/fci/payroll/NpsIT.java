package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.nps.orchestration.NpsHrmsEffects;
import in.gov.fci.payroll.nps.orchestration.NpsLockEffect;
import in.gov.fci.payroll.nps.orchestration.NpsMismatchCheck;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.business.PayRules;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NPS as received from HRMS (plan 18 phases 18a / 18b; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-18,
 * SRS-9-21, SRS-9-23; D18-2, D18-3, D18-6, D18-8). The records are for a month far ahead of today,
 * so no other test's cycle meets them; the member is an NPS member outside Delhi with the highest
 * id (the CPF / PT / TDS ITs take other NPS members). Demo pay codes 906 / 907 / 908.
 */
class NpsIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired private NpsReceipts receipts;
    @Autowired private NpsMismatchCheck check;
    @Autowired private NpsLockEffect lockEffect;
    @Autowired private StaffDeductionRunner deductions;
    @Autowired private PayConfiguration configuration;
    @Autowired private OrgDirectory org;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NpsHrmsEffects npsEffects;

    private long employeeId;
    private long locationId;
    private String eis;
    private long unitId;

    @BeforeEach
    void member() {
        Map<String, Object> m =
                jdbc.queryForMap(
                        "SELECT e.employee_id, e.emp_num, e.location_id FROM employee e JOIN"
                                + " org_location l ON l.location_id = e.location_id WHERE"
                                + " e.provident_fund_type = 'NPS' AND e.record_status = 'ACTIVE'"
                                + " AND l.state_code <> 'DL' ORDER BY e.employee_id DESC FETCH"
                                + " FIRST 1 ROWS ONLY");
        employeeId = ((Number) m.get("EMPLOYEE_ID")).longValue();
        locationId = ((Number) m.get("LOCATION_ID")).longValue();
        eis = (String) m.get("EMP_NUM");
        unitId = org.cycleUnitOf(locationId).orElseThrow().getId();
    }

    /** A month far ahead of today, different for each test so they do not meet. */
    private static String month(int ahead) {
        YearMonth m = YearMonth.now(IST).plusMonths(24 + ahead);
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private NpsReceipts.Received receive(
            String yymm, boolean flag, String employee, String employer) {
        return receipts.receive(
                "it.hrms",
                new NpsReceipts.Receipt(
                        eis,
                        yymm,
                        flag,
                        "110012345678",
                        employee == null ? null : new BigDecimal(employee),
                        employer == null ? null : new BigDecimal(employer),
                        null));
    }

    private Map<String, Object> row(String yymm) {
        return jdbc.queryForMap(
                "SELECT mismatch_status, apply_status, employee_amount, deducted_amount,"
                        + " cycle_instance_id, exception_id FROM nps_deduction WHERE employee_id ="
                        + " ? AND payroll_yymm = ?",
                employeeId,
                yymm);
    }

    private long openExceptions() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM payroll_exception WHERE employee_id = ? AND exception_code ="
                        + " 'NPS_FLAG_AMOUNT_MISMATCH' AND exception_status IN ('OPEN',"
                        + " 'ROUTED_UPSTREAM')",
                Long.class,
                employeeId);
    }

    /**
     * SRS l.4005 (US-18-01 AC4): NPS is for Employee / Staff members only; a record received for a
     * labour member is refused with its reason and nothing is stored.
     */
    @Test
    void SRS_9_1_aLabourMemberHasNoNps() {
        String labour =
                jdbc.queryForObject(
                        "SELECT MIN(emp_num) FROM employee WHERE emp_lbr_flag = 'Y'", String.class);
        assertThat(labour).as("a labour member of the seed").isNotNull();
        String yymm = month(7);
        assertThatThrownBy(
                        () ->
                                receipts.receive(
                                        "it.hrms",
                                        new NpsReceipts.Receipt(
                                                labour,
                                                yymm,
                                                true,
                                                "110012345678",
                                                new BigDecimal("1500"),
                                                new BigDecimal("2100"),
                                                null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.NPS_RECEIPT);
                            assertThat(e.getMessageArgs()).containsExactly("LABOUR_MEMBER");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM nps_deduction d JOIN employee e ON"
                                        + " e.employee_id = d.employee_id WHERE e.emp_num = ? AND"
                                        + " d.payroll_yymm = ?",
                                Long.class,
                                labour,
                                yymm))
                .isZero();
    }

    @Test
    void SRS_9_23_aRecordOfTheMonthIsReplacedAndAudited() {
        String yymm = month(1);
        NpsReceipts.Received first = receive(yymm, true, "1500", "2100");
        assertThat(first.mismatchStatus()).isEqualTo("CONSISTENT");
        assertThat(first.replaced()).isFalse();
        assertThat(first.heldForTask()).isNull();
        // A PRAN change alone has no pay effect (SRS-9-2: a reference): applied at once.
        NpsReceipts.Received second =
                receipts.receive(
                        "it.hrms",
                        new NpsReceipts.Receipt(
                                eis,
                                yymm,
                                true,
                                "110012345679",
                                new BigDecimal("1500.00"),
                                new BigDecimal("2100"),
                                null));
        assertThat(second.npsDeductionId()).isEqualTo(first.npsDeductionId());
        assertThat(second.replaced()).isTrue();
        assertThat(second.heldForTask()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'nps_deduction' AND entity_key = ? AND field_name ="
                                        + " 'pran_reference' AND old_value = '110012345678' AND"
                                        + " new_value = '110012345679'",
                                Long.class,
                                eis + "|" + yymm))
                .as("R-AUD: before and after")
                .isEqualTo(1L);
        // US-GAP-291: an amount change has a pay effect: it waits for its I-21 task (SRS-9-23).
        NpsReceipts.Received third = receive(yymm, true, "1600", "2100");
        assertThat(third.heldForTask()).isNotBlank();
        assertThat(third.replaced()).isFalse();
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("1500");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT t.task_status FROM hrms_change_task t JOIN"
                                        + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                        + " t.pay_effect_field_id WHERE t.task_no = ? AND"
                                        + " f.field_code = 'NPS_CHANGE'",
                                String.class,
                                third.heldForTask()))
                .isEqualTo("PENDING_ORDER_DETAILS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM nps_deduction WHERE employee_id = ? AND"
                                        + " payroll_yymm = ?",
                                Long.class,
                                employeeId,
                                yymm))
                .as("one MONTHLY row per member and month")
                .isEqualTo(1L);
    }

    @Test
    void SRS_9_1a_aMismatchIsRaisedHoldsTheLockAndClearsWhenHrmsCorrectsIt() {
        String yymm = month(2);
        PreLockCheck.Cycle cycle = new PreLockCheck.Cycle(0L, unitId, yymm);
        long before = openExceptions();
        assertThat(check.evaluate(cycle).failures()).isZero();
        assertThat(receive(yymm, true, null, "2100").mismatchStatus())
                .isEqualTo("FLAG_WITHOUT_AMOUNT");
        assertThat(row(yymm).get("EXCEPTION_ID")).isNotNull();
        assertThat(openExceptions()).isEqualTo(before + 1);
        assertThat(check.evaluate(cycle).failures()).isEqualTo(1L);
        assertThat(check.evaluate(new PreLockCheck.Cycle(0L, unitId + 100_000, yymm)).failures())
                .as("another unit's cycle is not held")
                .isZero();
        assertThat(check.ruleKey()).isEqualTo("NPS_FLAG_AMOUNT_MISMATCH");

        assertThat(receive(yymm, true, "1500", "2100").mismatchStatus())
                .isEqualTo("RESOLVED_IN_HRMS");
        assertThat(openExceptions()).isEqualTo(before);
        assertThat(check.evaluate(cycle).failures()).isZero();

        assertThat(receive(yymm, false, "1500", null).mismatchStatus())
                .isEqualTo("AMOUNT_WITHOUT_FLAG");
        assertThat(check.evaluate(cycle).failures()).isEqualTo(1L);
        receive(yymm, false, null, null);
        assertThat(check.evaluate(cycle).failures()).isZero();
    }

    // SC-18-0012: the statutory computation carries the NPS lines as received from HRMS.
    @Test
    void SRS_9_21_theEmployeeNpsIsDeductedAndTheEmployerNpsLeavesNetUnchanged() {
        String yymm = month(3);
        receive(yymm, true, "1500", "2100");
        YearMonth m =
                YearMonth.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)));
        PayConfiguration.Snapshot config = configuration.staffConfiguration(m.atDay(1));
        BigDecimal basic = new BigDecimal("30000");
        PayRules.Outcome computed =
                new PayRules.Outcome(
                        PayRules.COMPUTED,
                        null,
                        List.of(
                                new PayRules.Line(
                                        PayRules.BASIC,
                                        config.componentIds().get(PayRules.BASIC),
                                        basic,
                                        null,
                                        null)),
                        basic,
                        BigDecimal.ZERO,
                        basic);
        PayRules.Outcome o =
                deductions.open(m).apply(computed, employeeId, locationId, "CDA", config);
        assertThat(amount(o, 906)).isEqualByComparingTo("-1500");
        assertThat(amount(o, 908)).isEqualByComparingTo("2100");
        assertThat(amount(o, 907)).isEqualByComparingTo("-2100");
        assertThat(o.gross()).isEqualByComparingTo("32100");
        assertThat(o.net().add(o.deductions()).subtract(o.gross())).isZero();
        assertThat(o.net().add(sumOtherDeductions(o))).isEqualByComparingTo("28500");

        receive(yymm, true, null, "2100");
        PayRules.Outcome held =
                deductions.open(m).apply(computed, employeeId, locationId, "CDA", config);
        assertThat(amount(held, 906)).as("D18-3: nothing for a mismatch").isNull();
        assertThat(amount(held, 908)).isNull();
    }

    @Test
    void D18_6_theRecordsBecomeAppliedWhenTheCycleLocks() {
        String yymm = month(4);
        receive(yymm, true, "1500", "2100");
        locked(new CycleLocked(0L, unitId + 100_000, yymm, "it.checker", Instant.now()));
        assertThat(row(yymm).get("APPLY_STATUS")).as("another unit's lock").isEqualTo("RECEIVED");
        long cycleId = cycle(yymm, "VERIFIED");
        locked(new CycleLocked(cycleId, unitId, yymm, "it.checker", Instant.now()));
        Map<String, Object> r = row(yymm);
        assertThat(r.get("APPLY_STATUS")).isEqualTo("APPLIED");
        assertThat((BigDecimal) r.get("DEDUCTED_AMOUNT")).isEqualByComparingTo("1500");
        assertThat(((Number) r.get("CYCLE_INSTANCE_ID")).longValue()).isEqualTo(cycleId);
    }

    @Test
    void SRS_9_23_aRecordOfAnOpenCycleIsLinkedToItAndANotFlaggedMemberIsKept() {
        String yymm = month(7);
        long cycleId = cycle(yymm, "INITIATED");
        LocalDate first =
                LocalDate.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)),
                        1);
        receipts.receive(
                "it.hrms", new NpsReceipts.Receipt(eis, yymm, false, null, null, null, first));
        Map<String, Object> r = row(yymm);
        assertThat(((Number) r.get("CYCLE_INSTANCE_ID")).longValue()).isEqualTo(cycleId);
        assertThat(r.get("MISMATCH_STATUS")).isEqualTo("CONSISTENT");
        receipts.receive(
                "it.hrms",
                new NpsReceipts.Receipt(eis, yymm, false, null, null, null, first.plusDays(1)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'nps_deduction' AND entity_key = ? AND field_name ="
                                        + " 'hrms_effective_from' AND old_value = ?",
                                Long.class,
                                eis + "|" + yymm,
                                first.toString()))
                .isEqualTo(1L);
    }

    @Test
    void D18_2_aLockedMonthOrABadRecordIsRefused() {
        String yymm = month(5);
        cycle(yymm, "LOCKED");
        assertThatThrownBy(() -> receive(yymm, true, "1500", "2100"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.NPS_LOCKED));
        assertThatThrownBy(
                        () ->
                                receipts.receive(
                                        "it.hrms",
                                        new NpsReceipts.Receipt(
                                                "000000",
                                                month(6),
                                                true,
                                                null,
                                                BigDecimal.ONE,
                                                null,
                                                null)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.NPS_RECEIPT);
                            assertThat(e.getMessageArgs()).containsExactly("UNKNOWN_EMPLOYEE");
                        });
        assertThatThrownBy(
                        () ->
                                receipts.receive(
                                        "it.hrms",
                                        new NpsReceipts.Receipt(
                                                eis,
                                                month(6),
                                                true,
                                                "PRAN-1",
                                                BigDecimal.ONE,
                                                null,
                                                LocalDate.now(IST))))
                .isInstanceOf(FciException.class);
    }

    private NpsReceipts.Received receive(String yymm, String pran, String employee) {
        return receipts.receive(
                "it.hrms",
                new NpsReceipts.Receipt(
                        eis,
                        yymm,
                        true,
                        pran,
                        employee == null ? null : new BigDecimal(employee),
                        new BigDecimal("2100"),
                        null));
    }

    private Map<String, Object> task(String taskNo) {
        return jdbc.queryForMap(
                "SELECT hrms_change_task_id, task_status, superseded_by_task_id, new_value,"
                        + " effective_date FROM hrms_change_task WHERE task_no = ?",
                taskNo);
    }

    /** The approved task's effect, as I-21 applies it in the decision's transaction. */
    private void approve(String taskNo) {
        Map<String, Object> t = task(taskNo);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                npsEffects.apply(
                                        new HrmsTaskEffects.Task(
                                                ((Number) t.get("HRMS_CHANGE_TASK_ID")).longValue(),
                                                taskNo,
                                                employeeId,
                                                NpsHrmsEffects.CHANGE_FIELD,
                                                (String) t.get("NEW_VALUE"),
                                                t.get("EFFECTIVE_DATE") instanceof java.sql.Date d
                                                        ? d.toLocalDate()
                                                        : ((java.sql.Timestamp)
                                                                        t.get("EFFECTIVE_DATE"))
                                                                .toLocalDateTime()
                                                                .toLocalDate(),
                                                null),
                                        "it.checker",
                                        1L,
                                        Instant.now()));
    }

    private void stale(String taskNo) {
        assertThatThrownBy(() -> approve(taskNo))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.NPS_STALE));
    }

    private Object supersededBy(String taskNo) {
        Object by = task(taskNo).get("SUPERSEDED_BY_TASK_ID");
        return by == null ? null : ((Number) by).longValue();
    }

    private long id(String taskNo) {
        return ((Number) task(taskNo).get("HRMS_CHANGE_TASK_ID")).longValue();
    }

    /**
     * Wave C review, findings 1 and 2 (SRS-9-23): HRMS sends A to B (T1), then A to C (T2): T1 is
     * SUPERSEDED by T2 and, approved after T2, is refused - the record stays C. A resend of C opens
     * no new task.
     */
    @Test
    void SRS_9_23_aSecondChangeSupersedesTheFirstWhichNeverOverwritesIt() {
        String yymm = month(8);
        receive(yymm, "110012345678", "1500");
        String t1 = receive(yymm, "110012345678", "1600").heldForTask();
        String t2 = receive(yymm, "110012345678", "1700").heldForTask();
        assertThat(t2).isNotEqualTo(t1);
        assertThat(task(t1).get("TASK_STATUS")).isEqualTo("SUPERSEDED");
        assertThat(supersededBy(t1)).isEqualTo(id(t2));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_event WHERE entity_name ="
                                        + " 'hrms_change_task' AND entity_key = ? AND field_name ="
                                        + " 'task_status' AND new_value = 'SUPERSEDED'",
                                Long.class,
                                t1))
                .isEqualTo(1L);
        assertThat(receive(yymm, "110012345678", "1700").heldForTask())
                .as("finding 2: a resend opens no new task")
                .isEqualTo(t2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hrms_change_task t JOIN"
                                        + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                        + " t.pay_effect_field_id WHERE t.employee_id = ? AND"
                                        + " f.field_code = 'NPS_CHANGE' AND t.task_status ="
                                        + " 'PENDING_ORDER_DETAILS' AND t.new_value LIKE ?",
                                Long.class,
                                employeeId,
                                "%\"" + yymm + "\"%"))
                .isEqualTo(1L);
        approve(t2);
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("1700");
        stale(t1);
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("1700");
        // Finding 5: no effective date was sent, so none is recorded.
        assertThat(
                        jdbc.queryForList(
                                "SELECT hrms_effective_from FROM nps_deduction WHERE employee_id ="
                                        + " ? AND payroll_yymm = ?",
                                employeeId,
                                yymm))
                .singleElement()
                .satisfies(r -> assertThat(r.get("HRMS_EFFECTIVE_FROM")).isNull());
    }

    /**
     * Wave C review, finding 1: a PRAN change (or a change back) applied at once while T1 waits
     * supersedes T1, which approved later is refused - the PRAN change is not undone.
     */
    @Test
    void SRS_9_23_aChangeAppliedAtOnceSupersedesTheOneWaiting() {
        String yymm = month(9);
        receive(yymm, "110012345678", "1500");
        String t1 = receive(yymm, "110012345678", "1600").heldForTask();
        NpsReceipts.Received pran = receive(yymm, "110012345679", "1500");
        assertThat(pran.heldForTask()).isNull();
        assertThat(task(t1).get("TASK_STATUS")).isEqualTo("SUPERSEDED");
        assertThat(supersededBy(t1)).isNull();
        stale(t1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pran_reference FROM nps_deduction WHERE employee_id = ?"
                                        + " AND payroll_yymm = ?",
                                String.class,
                                employeeId,
                                yymm))
                .isEqualTo("110012345679");
        assertThat((BigDecimal) row(yymm).get("EMPLOYEE_AMOUNT")).isEqualByComparingTo("1500");
    }

    /**
     * Wave C review, finding 1: a mismatch applied at once while T1 waits supersedes T1, which
     * approved later is refused - the mismatch (and its X-05 exception) is not overwritten.
     */
    @Test
    void SRS_9_1a_aMismatchSupersedesTheChangeWaitingWhichCannotOverwriteIt() {
        String yymm = month(10);
        receive(yymm, "110012345678", "1500");
        String t1 = receive(yymm, "110012345678", "1600").heldForTask();
        long before = openExceptions();
        assertThat(receive(yymm, "110012345678", null).mismatchStatus())
                .isEqualTo("FLAG_WITHOUT_AMOUNT");
        assertThat(task(t1).get("TASK_STATUS")).isEqualTo("SUPERSEDED");
        stale(t1);
        assertThat(row(yymm).get("MISMATCH_STATUS")).isEqualTo("FLAG_WITHOUT_AMOUNT");
        assertThat(openExceptions()).isEqualTo(before + 1);
        // Its correction applies at once and clears the exception.
        assertThat(receive(yymm, "110012345678", "1600").mismatchStatus())
                .isEqualTo("RESOLVED_IN_HRMS");
        assertThat(openExceptions()).isEqualTo(before);
    }

    /** The lock's effect runs in the lock's transaction (CycleLockEffect). */
    private void locked(CycleLocked event) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> lockEffect.on(event));
    }

    private long cycle(String yymm, String status) {
        int year = Integer.parseInt(yymm.substring(0, 4));
        int start = Integer.parseInt(yymm.substring(4)) >= 4 ? year : year - 1;
        jdbc.update(
                "INSERT INTO payroll_cycle_instance (payroll_yymm, org_unit_id, org_unit_level,"
                        + " fin_year, cycle_status, trigger_type, initiated_by, locked_by) VALUES"
                        + " (?, ?, 'DO', ?, ?, 'ON_DEMAND', 'it.maker', 'it.checker')",
                yymm,
                unitId,
                String.format("%d-%02d", start, (start + 1) % 100),
                status);
        return jdbc.queryForObject(
                "SELECT cycle_instance_id FROM payroll_cycle_instance WHERE payroll_yymm = ? AND"
                        + " org_unit_id = ?",
                Long.class,
                yymm,
                unitId);
    }

    private static BigDecimal amount(PayRules.Outcome o, int code) {
        return o.lines().stream()
                .filter(l -> l.payCode() == code)
                .map(PayRules.Line::amount)
                .findFirst()
                .orElse(null);
    }

    /** Deductions other than NPS (PT of the member's state, if any). */
    private static BigDecimal sumOtherDeductions(PayRules.Outcome o) {
        return o.lines().stream()
                .filter(l -> l.amount().signum() < 0 && l.payCode() != 906 && l.payCode() != 907)
                .map(PayRules.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate();
    }
}
