package in.gov.fci.payroll.payroll.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-15-35, FRS BR-SEC-007 (SRS label BR-SEC-006, CLR-43) (plan 05 phase 05h): the approved
 * resumption closes the suspension with the checker (cut to the column's 50), when and why; a
 * second approval finds it already resumed and says so in the audit; a rejection or another entity
 * changes nothing.
 */
class ReleaseSuspensionEffectTest {

    private final AuditService audit = mock(AuditService.class);
    private JdbcTemplate jdbc;
    private ReleaseSuspensionEffect effect;

    @BeforeEach
    void schema() {
        org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL("jdbc:h2:mem:suspensioneffect;MODE=Oracle;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(h2);
        jdbc.execute("DROP TABLE IF EXISTS disb_release_suspension");
        jdbc.execute(
                "CREATE TABLE disb_release_suspension (suspension_id BIGINT, resumed_by"
                        + " VARCHAR(50), resumed_at TIMESTAMP, resume_reason VARCHAR(1000),"
                        + " resume_approval_id BIGINT)");
        jdbc.update("INSERT INTO disb_release_suspension (suspension_id) VALUES (5)");
        effect =
                new ReleaseSuspensionEffect(
                        jdbc,
                        audit,
                        Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC),
                        JsonMapper.builder().build());
    }

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, String checker) {
        return new ApprovalDecided(
                72L,
                "O-02",
                entity,
                "5",
                outcome,
                "",
                "{\"suspensionId\":5,\"reason\":\"Reconciled\"}",
                checker,
                Instant.parse("2026-10-26T06:00:00Z"));
    }

    @Test
    void SRS_15_35_theApprovedResumptionClosesTheSuspensionOnce() {
        String longChecker = "c".repeat(60) + "@fci-demo.test";
        effect.on(decided(ReleaseSuspensionService.ENTITY, ApprovalStatus.APPROVED, longChecker));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resumed_by FROM disb_release_suspension", String.class))
                .hasSize(50);
        effect.on(decided(ReleaseSuspensionService.ENTITY, ApprovalStatus.APPROVED, "checker.hq"));
        ArgumentCaptor<AuditService.Entry> entries =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(anyString(), entries.capture());
        assertThat(entries.getAllValues().get(0).newValue()).isEqualTo("RESUMED");
        assertThat(entries.getAllValues().get(1).newValue()).isEqualTo("ALREADY_RESUMED");
    }

    @Test
    void SRS_15_35_aRejectionOrAnotherEntityChangesNothing() {
        effect.on(decided(ReleaseSuspensionService.ENTITY, ApprovalStatus.REJECTED, "checker.hq"));
        effect.on(decided("mdp_transaction", ApprovalStatus.APPROVED, "checker.hq"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM disb_release_suspension WHERE resumed_at IS"
                                        + " NOT NULL",
                                Integer.class))
                .isZero();
        verifyNoInteractions(audit);
    }
}
