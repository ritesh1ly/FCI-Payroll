package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Rule Approver's decision on an M-07 change (plan 06 phase 06d; SRS M-07 postconditions): an
 * approved definition becomes a catalogue entry with its engines (available to eligibility flags
 * and computation on the selected payroll types), an approved revision changes the entry and adds
 * any new payroll type, an approved retirement end-dates the code. Audited with the approval. A
 * rejected or returned change writes nothing (it stays in its approval item).
 */
@Component
public class ComponentCatalogueEffect {

    private final ConfigurationRepositories.PayComponents components;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ComponentCatalogueEffect(
            ConfigurationRepositories.PayComponents components,
            AuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper json) {
        this.components = components;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ComponentCatalogueService.ENTITY) || !decision.approved()) {
            return;
        }
        JsonNode p;
        try {
            p = json.readTree(decision.payload());
        } catch (JacksonException ex) {
            throw new IllegalStateException("Unreadable M-07 change " + decision.approvalId(), ex);
        }
        int payCode = p.path("payCode").asInt();
        String action = p.path("action").asString();
        PayComponent c;
        String before = null;
        switch (action) {
            case "DEFINE" -> c = components.save(PayComponent.defined(definition(p, false)));
            case "REVISE" -> {
                c = components.findByLegacyPayCode(payCode).orElseThrow();
                before = image(c);
                // Wave D review 10: a revision submitted before V077_1 names no LWP attribute;
                // it keeps the entry's current value.
                c.revised(definition(p, c.isLwpSubjectFlag()));
                c = components.save(c);
            }
            case "RETIRE" -> {
                c = components.findByLegacyPayCode(payCode).orElseThrow();
                before = image(c);
                c.retired(LocalDate.parse(p.path("lastDay").asString()));
                c = components.save(c);
                // The DDL's explicit retired list (A.5 master-load acceptance): a code on it is
                // never
                // reused (SRS-G-42).
                jdbc.update(
                        "INSERT INTO retired_pay_code (legacy_pay_code, legacy_description,"
                            + " retirement_reason, confirmed_by, confirmed_at) SELECT ?, ?, ?, ?, ?"
                            + " FROM dual WHERE NOT EXISTS (SELECT 1 FROM retired_pay_code WHERE"
                            + " legacy_pay_code = ?)",
                        payCode,
                        c.getComponentName(),
                        p.path("reason").asString(),
                        decision.checkerLoginId(),
                        java.sql.Timestamp.from(decision.decidedAt()),
                        payCode);
            }
            default -> throw new IllegalStateException("Unknown M-07 change " + action);
        }
        if (!"RETIRE".equals(action)) {
            for (String type : types(p)) {
                // An engine row is added, never removed (plan 06 D-8).
                jdbc.update(
                        "INSERT INTO pay_component_engine (component_id, payroll_type) SELECT ?, ?"
                                + " FROM dual WHERE NOT EXISTS (SELECT 1 FROM pay_component_engine"
                                + " WHERE component_id = ? AND payroll_type = ?)",
                        c.getComponentId(),
                        type,
                        c.getComponentId(),
                        type);
            }
        }
        if (!"RETIRE".equals(action)) {
            registerUsage(c.getComponentId(), c.getBasisRuleKey(), "Driver / Basis");
            registerUsage(c.getComponentId(), c.getEligibilityRuleKey(), "Eligibility flag");
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ComponentCatalogueService.ENTITY,
                                String.valueOf(payCode),
                                "component",
                                before,
                                image(c),
                                "M-07 " + action.toLowerCase(java.util.Locale.ROOT) + " approved")
                        .approval(decision.approvalId())
                        .module(ComponentCatalogueService.SCREEN));
    }

    /**
     * SRS l.1887, l.1932 "Where used": the rule a component reads is known at approval, so it is
     * registered here, once (DDL uk_component_usage). A consumer of another kind registers itself.
     */
    private void registerUsage(Long componentId, String ruleKey, String note) {
        if (ruleKey == null) {
            return;
        }
        jdbc.update(
                "INSERT INTO component_usage (component_id, consumer_type, consumer_key,"
                        + " binding_note) SELECT ?, 'RULE', ?, ? FROM dual WHERE NOT EXISTS"
                        + " (SELECT 1 FROM component_usage WHERE component_id = ? AND consumer_type"
                        + " = 'RULE' AND consumer_key = ?)",
                componentId,
                ruleKey,
                note,
                componentId,
                ruleKey);
    }

    private static Set<String> types(JsonNode p) {
        Set<String> out = new TreeSet<>();
        p.path("payrollTypes").forEach(t -> out.add(t.asString()));
        return out;
    }

    /**
     * The definition in the payload.
     *
     * @param lwpWhenAbsent the LWP attribute when the payload does not name it (US-GAP-298: an item
     *     submitted before V077_1): not subject for a new code, the current value for a revision
     */
    private static ComponentRules.Definition definition(JsonNode p, boolean lwpWhenAbsent) {
        return new ComponentRules.Definition(
                p.path("payCode").asInt(),
                p.path("name").asString(),
                p.path("type").asString(),
                text(p, "payslipGroup"),
                text(p, "basisRuleKey"),
                p.path("source").asString(),
                p.path("naturalAccount").asString(),
                p.path("taxable").asString(),
                p.path("inCpfBase").asBoolean(),
                types(p),
                LocalDate.parse(p.path("effectiveFrom").asString()),
                // US-GAP-298: absent in an item submitted before V077_1.
                p.path("subjectToLwp").isBoolean()
                        ? p.path("subjectToLwp").asBoolean()
                        : lwpWhenAbsent,
                // Absent in an item submitted before SRS l.1909 was built: no flag.
                text(p, "eligibilityRuleKey"));
    }

    private static String text(JsonNode p, String field) {
        JsonNode v = p.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    /** The audited image of a component (also its X-04 current value, SRS-20-29). */
    static String image(PayComponent c) {
        return String.join(
                "|",
                List.of(
                        String.valueOf(c.getComponentName()),
                        String.valueOf(c.getComponentType()),
                        String.valueOf(c.getSourceCode()),
                        String.valueOf(c.getTaxableFlag()),
                        c.isInCpfBaseFlag() ? "CPF" : "NOCPF",
                        c.isLwpSubjectFlag() ? "LWP" : "NOLWP",
                        String.valueOf(c.getGlNaturalAccount()),
                        String.valueOf(c.getBasisRuleKey()),
                        String.valueOf(c.getEffectiveFrom()),
                        String.valueOf(c.getEffectiveTo())));
    }
}
