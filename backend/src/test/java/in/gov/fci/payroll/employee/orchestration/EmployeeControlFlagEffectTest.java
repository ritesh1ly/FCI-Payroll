package in.gov.fci.payroll.employee.orchestration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-01 hold / stop-payment flags (SRS-1-63, SRS-G-32): the approved item is applied once to the
 * member with its own audit row; a payload that cannot be read, another entity's decision and a
 * decision that is not an approval change nothing.
 */
class EmployeeControlFlagEffectTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final Instant DECIDED = Instant.parse("2026-10-26T06:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeControlFlagEffect effect =
            new EmployeeControlFlagEffect(jdbc, audit, JsonMapper.builder().build());

    private static ApprovalDecided decision(String entity, ApprovalStatus outcome, String payload) {
        return new ApprovalDecided(
                9L, "M-01", entity, "7|STOP_PAYMENT", outcome, "r", payload, "checker", DECIDED);
    }

    @Test
    void SRS_1_63_anUnreadableStoredFlagChangeFailsTheDecisionAndWritesNothing() {
        ApprovalDecided broken =
                decision(EmployeeControlFlagService.ENTITY, ApprovalStatus.APPROVED, "{bad");

        assertThatThrownBy(() -> effect.on(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unreadable control flag approval 9")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void SRS_1_63_aRejectedOrForeignDecisionChangesNoFlag() {
        String payload =
                "{\"employeeId\":7,\"flagCode\":\"HOLD\",\"value\":\"Y\",\"eis\":\"900001\","
                        + "\"effectiveFrom\":\""
                        + TODAY
                        + "\",\"reason\":\"r\"}";
        for (ApprovalStatus outcome :
                new ApprovalStatus[] {
                    ApprovalStatus.REJECTED, ApprovalStatus.RETURNED, ApprovalStatus.WITHDRAWN
                }) {
            effect.on(decision(EmployeeControlFlagService.ENTITY, outcome, payload));
        }
        effect.on(decision("employee_transfer", ApprovalStatus.APPROVED, payload));

        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void SRS_G_32_anApprovedStopPaymentWritesOnlyTheStopPaymentColumnAndIsAudited() {
        when(jdbc.queryForObject(contains("stop_payment_flag"), eq(String.class), eq(7L)))
                .thenReturn("N");
        String payload =
                "{\"employeeId\":7,\"flagCode\":\"STOP_PAYMENT\",\"value\":\"Y\","
                        + "\"eis\":\"900001\",\"effectiveFrom\":\""
                        + TODAY
                        + "\",\"reason\":\"Recovery pending\"}";

        effect.on(decision(EmployeeControlFlagService.ENTITY, ApprovalStatus.APPROVED, payload));

        verify(jdbc).update(contains("SET stop_payment_flag = ?"), eq("Y"), eq(7L));
        verify(audit).record(eq("checker"), any(AuditService.Entry.class));
    }

    @Test
    void SRS_G_32_anApprovedHoldWritesOnlyTheHoldColumn() {
        when(jdbc.queryForObject(contains("hold_flag"), eq(String.class), eq(7L))).thenReturn("Y");
        String payload =
                "{\"employeeId\":7,\"flagCode\":\"HOLD\",\"value\":\"N\",\"eis\":\"900001\","
                        + "\"effectiveFrom\":\""
                        + TODAY
                        + "\",\"reason\":\"Cleared\"}";

        effect.on(decision(EmployeeControlFlagService.ENTITY, ApprovalStatus.APPROVED, payload));

        verify(jdbc).update(contains("SET hold_flag = ?"), eq("N"), eq(7L));
        verify(audit).record(eq("checker"), any(AuditService.Entry.class));
    }
}
