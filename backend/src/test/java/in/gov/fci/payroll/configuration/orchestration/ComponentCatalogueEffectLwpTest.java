package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
 * Wave D review 10 (US-GAP-298; SRS-LV-15, BR-LEAVE-007): an approved M-07 revision whose payload
 * does not name "Subject to LWP" (submitted before V077_1, or sent as null) keeps the entry's
 * current value; one that names it sets it; a definition that does not name it is not subject.
 */
class ComponentCatalogueEffectLwpTest {

    private final ConfigurationRepositories.PayComponents components =
            mock(ConfigurationRepositories.PayComponents.class);
    private final ComponentCatalogueEffect effect =
            new ComponentCatalogueEffect(
                    components,
                    mock(AuditService.class),
                    mock(JdbcTemplate.class),
                    JsonMapper.builder().build());

    private static PayComponent subject() {
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

    private void approve(String action, String lwp) {
        String payload =
                "{\"action\":\""
                        + action
                        + "\",\"payCode\":111,\"name\":\"Dearness Allowance\",\"type\":\"EARNING\","
                        + "\"payslipGroup\":\"PAY\",\"source\":\"CALC\",\"naturalAccount\":"
                        + "\"510102\",\"taxable\":\"Y\",\"inCpfBase\":true,\"payrollTypes\":"
                        + "[\"STAFF\"],\"effectiveFrom\":\"2026-11-01\""
                        + (lwp == null ? "" : ",\"subjectToLwp\":" + lwp)
                        + "}";
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
    void SRS_LV_15_aRevisionNotNamingTheLwpAttributeKeepsTheCurrentValue() {
        PayComponent c = subject();
        when(components.findByLegacyPayCode(111)).thenReturn(Optional.of(c));
        when(components.save(any())).thenAnswer(i -> i.getArgument(0));

        approve("REVISE", null);
        assertThat(c.isLwpSubjectFlag()).as("absent keeps Y").isTrue();
        approve("REVISE", "null");
        assertThat(c.isLwpSubjectFlag()).as("null keeps Y").isTrue();
        approve("REVISE", "false");
        assertThat(c.isLwpSubjectFlag()).as("named false clears it").isFalse();
        approve("REVISE", null);
        assertThat(c.isLwpSubjectFlag()).as("absent keeps N").isFalse();
        approve("REVISE", "true");
        assertThat(c.isLwpSubjectFlag()).isTrue();
    }

    @Test
    void SRS_LV_15_aDefinitionNotNamingTheLwpAttributeIsNotSubject() {
        org.mockito.ArgumentCaptor<PayComponent> saved =
                org.mockito.ArgumentCaptor.forClass(PayComponent.class);
        when(components.save(saved.capture())).thenAnswer(i -> i.getArgument(0));
        approve("DEFINE", null);
        assertThat(saved.getValue().isLwpSubjectFlag()).isFalse();
    }
}
