package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.audit.api.AuditService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Resilience: all or nothing. A fault is injected late in a business transaction (the audit row of
 * a chosen entity, written after the change itself, R-AUD) and the database must then hold nothing
 * of that request: no event header, no superseded record, no new record, no decided approval. With
 * the fault cleared the same request succeeds, so the failure left no lock or half state behind.
 * Covers the two-phase leave encashment arrear run (SRS-8-23) and the Checker's decision with its
 * effect in the same transaction (R-MC, SRS-8-24).
 */
@Import(FaultInjectionIT.Faults.class)
class FaultInjectionIT extends ApiTestSupport {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** The audited entity whose first audit row fails; null when no fault is armed. */
    private static final AtomicReference<String> FAIL_ON = new AtomicReference<>();

    /**
     * Adds a fault advice in front of the audit service's own proxy: it runs inside the caller's
     * transaction (before the audit's MANDATORY check) and fails the first audit of the armed
     * entity.
     */
    @TestConfiguration
    static class Faults {
        @Bean
        static BeanPostProcessor auditFault() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof AuditService && bean instanceof Advised advised) {
                        advised.addAdvice(
                                0,
                                (MethodInterceptor)
                                        call -> {
                                            if ("record".equals(call.getMethod().getName())
                                                    && call.getArguments()[1]
                                                            instanceof AuditService.Entry entry
                                                    && entry.entityName().equals(FAIL_ON.get())) {
                                                throw new IllegalStateException(
                                                        "Injected fault (IT)");
                                            }
                                            return call.proceed();
                                        });
                    }
                    return bean;
                }
            };
        }
    }

    @AfterEach
    void clearFaults() {
        FAIL_ON.set(null);
    }

    private long member() {
        return jdbc.queryForObject(
                "SELECT employee_id FROM employee WHERE location_id = ? AND employment_status ="
                        + " 'IN_SERVICE' AND record_status = 'ACTIVE' ORDER BY emp_num OFFSET 12"
                        + " ROWS FETCH NEXT 1 ROWS ONLY",
                Long.class,
                locationId("DH001"));
    }

    private long circular() {
        String number = "IT/FAULT/" + System.nanoTime();
        jdbc.update(
                "INSERT INTO document_attachment (entity_name, entity_key, file_name,"
                        + " storage_provider, storage_object_key, contains_pii, uploaded_by) VALUES"
                        + " ('circular_order', ?, 'it.pdf', 'ORACLE_SECUREFILE', ?, 'N', 'it')",
                number,
                "it/circular/" + number);
        jdbc.update(
                "INSERT INTO circular_order (circular_number, circular_date, subject,"
                        + " category_code, applies_all_offices, effective_from,"
                        + " document_attachment_id, circular_status, created_by) SELECT ?, ?, 'DA"
                        + " revision (IT)', 'DA', 'Y', ?, attachment_id, 'ACTIVE', 'it.fixture'"
                        + " FROM document_attachment WHERE storage_object_key = ?",
                number,
                java.sql.Date.valueOf(LocalDate.now(IST)),
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)),
                "it/circular/" + number);
        return jdbc.queryForObject(
                "SELECT circular_order_id FROM circular_order WHERE circular_number = ?",
                Long.class,
                number);
    }

    private int run(Session s, String eis, long circular, LocalDate effective) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("eis", eis);
        body.put("circularId", circular);
        body.put("revisionEffective", effective.toString());
        body.put("reason", "DA revision order (IT)");
        return mvc.perform(userPost(s, "/v1/leave-encashment-arrears/runs", body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Map<String, Object> state(long employeeId) {
        return jdbc.queryForMap(
                "SELECT (SELECT COUNT(*) FROM leave_encashment_arrear WHERE employee_id = ?) AS"
                        + " records, (SELECT COUNT(*) FROM leave_encashment_arrear WHERE"
                        + " employee_id = ? AND le_status = 'CALCULATED') AS calculated, (SELECT"
                        + " COUNT(*) FROM arrear_event WHERE source_module = 'LEAVE') AS events"
                        + " FROM dual",
                employeeId,
                employeeId);
    }

    @Test
    void SRS_8_23_aFaultLateInTheRunLeavesNothingAndTheEarlierRecordStands() throws Exception {
        long employeeId = member();
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        LocalDate demo = LocalDate.of(2026, 4, 12);
        LocalDate twoBack = LocalDate.now(IST).minusMonths(2).withDayOfMonth(12);
        LocalDate day = twoBack.isAfter(demo) ? twoBack : demo;
        jdbc.update(
                "INSERT INTO leave_encashment (employee_id, leave_type_code, encashment_reason,"
                        + " period_from, period_to, days_encashed, valuation_basis, rate_per_day,"
                        + " amount_paid, was_capped) VALUES (?, 'EL', 'IN_SERVICE', ?, ?, 10, 'IT"
                        + " fixture', 100, 1000, 'N')",
                employeeId,
                java.sql.Date.valueOf(day),
                java.sql.Date.valueOf(day));
        long order = circular();
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        assertThat(run(maker, eis, order, day.withDayOfMonth(1))).isEqualTo(200);
        Map<String, Object> before = state(employeeId);
        assertThat(((Number) before.get("CALCULATED")).intValue()).isEqualTo(1);

        // The second run supersedes the record, writes the event and the new record, then fails.
        FAIL_ON.set("leave_encashment_arrear");
        assertThat(run(maker, eis, order, day.withDayOfMonth(1))).isEqualTo(500);
        assertThat(state(employeeId))
                .as("no event, no superseded record, no new record")
                .isEqualTo(before);

        FAIL_ON.set(null);
        assertThat(run(maker, eis, order, day.withDayOfMonth(1))).isEqualTo(200);
        Map<String, Object> after = state(employeeId);
        assertThat(((Number) after.get("RECORDS")).intValue())
                .isEqualTo(((Number) before.get("RECORDS")).intValue() + 1);
        assertThat(((Number) after.get("CALCULATED")).intValue()).isEqualTo(1);
    }

    // SC-01-0057: a failed downstream effect leaves the approval pending and nothing applied.
    @Test
    void SRS_8_24_aFaultInTheApprovalEffectLeavesTheItemPendingAndTheRecordSubmitted()
            throws Exception {
        long employeeId =
                jdbc.queryForObject(
                        "SELECT employee_id FROM employee WHERE location_id = ? AND"
                                + " employment_status = 'IN_SERVICE' AND record_status = 'ACTIVE'"
                                + " ORDER BY emp_num OFFSET 13 ROWS FETCH NEXT 1 ROWS ONLY",
                        Long.class,
                        locationId("DH001"));
        String eis =
                jdbc.queryForObject(
                        "SELECT emp_num FROM employee WHERE employee_id = ?",
                        String.class,
                        employeeId);
        LocalDate demo = LocalDate.of(2026, 4, 12);
        LocalDate twoBack = LocalDate.now(IST).minusMonths(2).withDayOfMonth(12);
        LocalDate day = twoBack.isAfter(demo) ? twoBack : demo;
        jdbc.update(
                "INSERT INTO leave_encashment (employee_id, leave_type_code, encashment_reason,"
                        + " period_from, period_to, days_encashed, valuation_basis, rate_per_day,"
                        + " amount_paid, was_capped) VALUES (?, 'EL', 'IN_SERVICE', ?, ?, 10, 'IT"
                        + " fixture', 100, 1000, 'N')",
                employeeId,
                java.sql.Date.valueOf(day),
                java.sql.Date.valueOf(day));
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        Session checker = login("ddo.delhi@fci-demo.test", "PAYROLL_CHECKER", "DH001");
        assertThat(run(maker, eis, circular(), day.withDayOfMonth(1))).isEqualTo(200);
        long id =
                jdbc.queryForObject(
                        "SELECT le_arrear_id FROM leave_encashment_arrear WHERE employee_id = ? AND"
                                + " le_status = 'CALCULATED'",
                        Long.class,
                        employeeId);
        MvcResult submitted =
                mvc.perform(
                                userPost(
                                        maker,
                                        "/v1/leave-encashment-arrears/submissions",
                                        Map.of("ids", List.of(id), "remark", "Order (IT)")))
                        .andReturn();
        assertThat(submitted.getResponse().getStatus()).isEqualTo(202);
        long approval = body(submitted).get("approvalId").asLong();

        FAIL_ON.set("leave_encashment_arrear");
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(500);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM approval_queue WHERE approval_id = ?",
                                String.class,
                                approval))
                .as("the decision is rolled back with its effect")
                .isEqualTo("PENDING_APPROVAL");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT le_status FROM leave_encashment_arrear WHERE le_arrear_id ="
                                        + " ?",
                                String.class,
                                id))
                .isEqualTo("SUBMITTED");

        FAIL_ON.set(null);
        assertThat(decide(checker, approval, "APPROVE", null, null)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT le_status FROM leave_encashment_arrear WHERE le_arrear_id ="
                                        + " ?",
                                String.class,
                                id))
                .isEqualTo("APPROVED");
    }
}
