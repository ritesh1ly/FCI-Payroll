package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.persistence.EventRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import jakarta.persistence.Column;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Oracle review F5 (lost update of employee): every JDBC write of an employee row bumps {@code
 * row_version}, the column the JPA {@code Employee} checks ({@code @Version}). Without it a
 * transfer or status change saved through JPA at the same time passes its version check and writes
 * the values it read before back over the JDBC change (the Basic of an increment, a Hold, an
 * absconding flag). The columns only the JDBC flows own are not written by a JPA save at all.
 */
class EmployeeRowVersionTest {

    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** SRS-1-7: an approved Hold or Stop-Payment is written with the row version bumped. */
    @Test
    void SRS_1_7_anApprovedHoldOrStopPaymentBumpsTheRowVersion() {
        EmployeeControlFlagEffect effect =
                new EmployeeControlFlagEffect(jdbc, mock(AuditService.class), new ObjectMapper());
        for (String flag : new String[] {"HOLD", "STOP_PAYMENT"}) {
            effect.on(
                    new ApprovalDecided(
                            9L,
                            "M-01",
                            EmployeeControlFlagService.ENTITY,
                            "7|" + flag,
                            ApprovalStatus.APPROVED,
                            null,
                            "{\"employeeId\":7,\"flagCode\":\""
                                    + flag
                                    + "\",\"value\":\"Y\",\"eis\":\"100041\","
                                    + "\"reason\":\"Order 4\",\"effectiveFrom\":\"2026-10-26\"}",
                            "checker",
                            DECIDED));
        }
        verify(jdbc)
                .update(
                        "UPDATE employee SET hold_flag = ?, row_version = row_version + 1 WHERE"
                                + " employee_id = ?",
                        "Y",
                        7L);
        verify(jdbc)
                .update(
                        "UPDATE employee SET stop_payment_flag = ?, row_version = row_version + 1"
                                + " WHERE employee_id = ?",
                        "Y",
                        7L);
    }

    /** SRS-1-64: an approved HRMS absconding sets the flag with the row version bumped. */
    @Test
    void SRS_1_64_anApprovedAbscondingBumpsTheRowVersion() {
        EventRepositories.LifecycleEvents events = mock(EventRepositories.LifecycleEvents.class);
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        HrmsEventApplier applier =
                new HrmsEventApplier(
                        events, mock(EmployeeEventEffects.class), jdbc, mock(AuditService.class));
        Map<String, Object> task = new HashMap<>();
        task.put("EFFECTIVE_DATE", java.sql.Date.valueOf(LocalDate.of(2026, 10, 26)));
        task.put("NEW_VALUE", null);
        task.put("EMPLOYEE_ID", 7L);
        task.put("HRMS_CHANGE_TASK_ID", 11L);
        task.put("TASK_NO", "HCA-2026-0011");
        applier.apply(
                task,
                "ABSCONDING",
                new ApprovalDecided(
                        9L,
                        "I-21",
                        "hrms_change_task",
                        "11",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "checker",
                        DECIDED));
        verify(jdbc)
                .update(
                        eq(
                                "UPDATE employee SET absconding_flag = 'Y', row_version ="
                                        + " row_version + 1 WHERE employee_id = ?"),
                        eq(7L));
    }

    /**
     * SRS-1-28: the birth date, PAN, Basic and bank account change only through their JDBC flows
     * (I-21, increments and pay fixation); a JPA save of the member never writes them.
     */
    @Test
    void SRS_1_28_theColumnsOnlyTheJdbcFlowsChangeAreNeverWrittenByAJpaSave() throws Exception {
        for (String field :
                new String[] {"birthDate", "panNumber", "currentBasic", "bankAccountNumber"}) {
            Column column = Employee.class.getDeclaredField(field).getAnnotation(Column.class);
            assertThat(column.updatable()).as(field).isFalse();
            assertThat(column.insertable()).as(field).isTrue();
        }
    }
}
