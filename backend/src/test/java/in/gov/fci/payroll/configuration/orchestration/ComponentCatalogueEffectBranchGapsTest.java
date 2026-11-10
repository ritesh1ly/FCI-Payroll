package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-07 approved changes (SRS-19-1, SRS-G-42): an approved retirement retires the code and puts it
 * on the explicit retired list (never reused) without adding engine rows; a change of a kind M-07
 * does not submit fails the decision instead of changing anything.
 */
class ComponentCatalogueEffectBranchGapsTest {

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ComponentCatalogueEffect effect =
            new ComponentCatalogueEffect(
                    components, mock(AuditService.class), jdbc, JsonMapper.builder().build());

    private static PayComponent live() {
        return PayComponent.defined(
                new ComponentRules.Definition(
                        111,
                        "Dearness Allowance",
                        "EARNING",
                        "PAY",
                        null,
                        "CALC",
                        "510102",
                        "Y",
                        true,
                        Set.of("STAFF"),
                        LocalDate.of(2016, 1, 1),
                        true));
    }

    private void approve(String payload) {
        effect.on(
                new ApprovalDecided(
                        5L,
                        "M-07",
                        ComponentCatalogueService.ENTITY,
                        "111",
                        ApprovalStatus.APPROVED,
                        null,
                        payload,
                        "rule.approver",
                        Instant.parse("2026-11-02T05:00:00Z")));
    }

    @Test
    void SRS_G_42_anApprovedRetirementPutsTheCodeOnTheRetiredListAndAddsNoEngine() {
        PayComponent c = live();
        when(components.findByLegacyPayCode(111)).thenReturn(Optional.of(c));
        when(components.save(any())).thenAnswer(call -> call.getArgument(0));
        approve(
                "{\"action\":\"RETIRE\",\"payCode\":111,\"lastDay\":\"2026-10-31\","
                        + "\"reason\":\"Merged into DA\"}");
        assertThat(c.isIsRetired()).isTrue();
        assertThat(c.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 10, 31));
        verify(jdbc)
                .update(
                        contains("INSERT INTO retired_pay_code"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        verify(jdbc, never())
                .update(contains("INSERT INTO pay_component_engine"), any(), any(), any(), any());
    }

    @Test
    void SRS_19_1_aChangeOfAnUnknownKindFailsTheDecision() {
        assertThatThrownBy(() -> approve("{\"action\":\"MERGE\",\"payCode\":111}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown M-07 change MERGE");
        verify(components, never()).save(any());
    }

    /** SRS-19-1: an approved M-07 change whose stored payload cannot be read fails the decision. */
    @Test
    void SRS_19_1_anUnreadableStoredChangeFailsTheDecisionAndChangesNothing() {
        assertThatThrownBy(() -> approve("{bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unreadable M-07 change 5")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
        verify(components, never()).save(any());
        verifyNoInteractions(jdbc);
    }

    /** SRS-19-1: another entity's decision and a rejected M-07 change are ignored. */
    @Test
    void SRS_19_1_aRejectedOrForeignDecisionChangesNothing() {
        String payload = "{\"action\":\"RETIRE\",\"payCode\":111,\"lastDay\":\"2026-10-31\"}";
        effect.on(
                new ApprovalDecided(
                        5L,
                        "M-07",
                        ComponentCatalogueService.ENTITY,
                        "111",
                        ApprovalStatus.REJECTED,
                        "no",
                        payload,
                        "rule.approver",
                        Instant.parse("2026-11-02T05:00:00Z")));
        effect.on(
                new ApprovalDecided(
                        6L,
                        "M-07",
                        "other_entity",
                        "111",
                        ApprovalStatus.APPROVED,
                        null,
                        payload,
                        "rule.approver",
                        Instant.parse("2026-11-02T05:00:00Z")));
        verify(components, never()).save(any());
        verifyNoInteractions(jdbc);
    }
}
