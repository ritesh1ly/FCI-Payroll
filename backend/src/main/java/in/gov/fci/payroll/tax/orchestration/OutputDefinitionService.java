package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.FieldSources;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.OutputRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputDefinition;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-17 Statutory Output Definitions (SRS M-17, BR-TAX-013, SRS-7-5, SRS-7-8, SRS-7-30; plan 08
 * phase 08f). The catalogue is the statutory outputs the SRS names, seeded without a version: an
 * output whose definition is not Active is blocked and never generated on an assumed layout. The
 * Rule Administrator at Headquarters proposes an effective-dated version (field set bound to the
 * component catalogue or the field-source model, format, rounding, control totals, period lock); a
 * different Rule Approver approves it (route M17_VERSION); earlier periods keep their version.
 */
@Service
@Loggable
public class OutputDefinitionService {

    public static final String SCREEN = "M-17";
    public static final String ENTITY = "report_definition_version";
    public static final String ROUTE = "M17_VERSION";
    static final String DOMAIN = "STATUTORY_OUTPUT";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A version as proposed. */
    public record Proposal(
            String outputKey,
            LocalDate effectiveFrom,
            String layoutFormat,
            String layoutNote,
            String roundingRuleKey,
            boolean periodLockRequired,
            List<OutputRules.Field> fields,
            List<OutputRules.Control> controls,
            String proposedBy,
            Instant proposedAt) {}

    /** One version as M-17 shows it. */
    public record VersionView(
            Long id,
            int number,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String layoutFormat,
            boolean periodLockRequired,
            boolean inForce,
            List<OutputRules.Field> fields,
            List<OutputRules.Control> controls) {}

    /** One output of the catalogue: BLOCKED while no Active version governs today. */
    public record OutputView(
            String key,
            String name,
            String producingScreen,
            String statutoryReference,
            String state,
            List<VersionView> versions,
            List<Long> pendingApprovals) {}

    /** The version governing an output on a day, with its fields and control totals. */
    public record Governing(
            OutputVersion version, List<OutputField> fields, List<OutputControlTotal> controls) {}

    private final TaxRepositories.OutputDefinitions definitions;
    private final TaxRepositories.OutputVersions versions;
    private final TaxRepositories.OutputFields fields;
    private final TaxRepositories.OutputControls controls;
    private final PayConfiguration pay;
    private final FieldSources fieldSources;
    private final RuleEngine rules;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public OutputDefinitionService(
            TaxRepositories.OutputDefinitions definitions,
            TaxRepositories.OutputVersions versions,
            TaxRepositories.OutputFields fields,
            TaxRepositories.OutputControls controls,
            PayConfiguration pay,
            FieldSources fieldSources,
            RuleEngine rules,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.definitions = definitions;
        this.versions = versions;
        this.fields = fields;
        this.controls = controls;
        this.pay = pay;
        this.fieldSources = fieldSources;
        this.rules = rules;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** M-17: the statutory outputs with their versions, state and waiting proposals. */
    @Transactional(readOnly = true)
    public List<OutputView> catalogue(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = today();
        List<OutputDefinition> outputs =
                definitions.findByOutputDomainOrderByReportKey(DOMAIN).stream()
                        .filter(OutputDefinition::isActive)
                        .toList();
        Map<Long, List<OutputVersion>> byDefinition =
                versions
                        .findByReportDefIdIn(
                                outputs.stream().map(OutputDefinition::getReportDefId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(OutputVersion::getReportDefId));
        // SRS-NF-6: the fields and control totals of every version in one read each (no query
        // per version: the catalogue grows by a version a year and output).
        List<Long> versionIds =
                byDefinition.values().stream()
                        .flatMap(List::stream)
                        .map(OutputVersion::getReportVersionId)
                        .toList();
        Map<Long, List<OutputField>> fieldsOf =
                versionIds.isEmpty()
                        ? Map.of()
                        : fields.findByReportVersionIdInOrderByFieldOrder(versionIds).stream()
                                .collect(Collectors.groupingBy(OutputField::getReportVersionId));
        Map<Long, List<OutputControlTotal>> controlsOf =
                versionIds.isEmpty()
                        ? Map.of()
                        : controls.findByReportVersionIdIn(versionIds).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                OutputControlTotal::getReportVersionId));
        Map<String, List<Long>> pending =
                statuses.pending(ENTITY).stream()
                        .collect(
                                Collectors.groupingBy(
                                        ApprovalStatuses.Pending::entityKey,
                                        Collectors.mapping(
                                                ApprovalStatuses.Pending::approvalId,
                                                Collectors.toList())));
        return outputs.stream()
                .map(
                        d -> {
                            List<OutputVersion> vs =
                                    byDefinition
                                            .getOrDefault(d.getReportDefId(), List.of())
                                            .stream()
                                            .sorted(
                                                    (a, b) ->
                                                            b.getEffectiveFrom()
                                                                    .compareTo(
                                                                            a.getEffectiveFrom()))
                                            .toList();
                            boolean active = vs.stream().anyMatch(v -> v.governs(today));
                            return new OutputView(
                                    d.getReportKey(),
                                    d.getReportName(),
                                    d.getProducingScreenCode(),
                                    d.getStatutoryReference(),
                                    active ? "ACTIVE" : "BLOCKED",
                                    vs.stream()
                                            .map(
                                                    v ->
                                                            view(
                                                                    v,
                                                                    today,
                                                                    fieldsOf.getOrDefault(
                                                                            v.getReportVersionId(),
                                                                            List.of()),
                                                                    controlsOf.getOrDefault(
                                                                            v.getReportVersionId(),
                                                                            List.of())))
                                            .toList(),
                                    pending.getOrDefault(d.getReportKey(), List.of()));
                        })
                .toList();
    }

    /**
     * M-17: a new version of an output, checked and sent to the Rule Approver. Headquarters only.
     */
    @Transactional
    public Long propose(AccessContext context, Proposal request, String reason) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
        if (reason == null || reason.isBlank() || request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        OutputDefinition d = definition(request.outputKey());
        check(d, request);
        statuses.pending(ENTITY).stream()
                .filter(p -> d.getReportKey().equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {d.getReportKey(), p.approvalId()},
                                    List.of(),
                                    null);
                        });
        Proposal p =
                new Proposal(
                        d.getReportKey(),
                        request.effectiveFrom(),
                        request.layoutFormat(),
                        blankToNull(request.layoutNote()),
                        blankToNull(request.roundingRuleKey()),
                        request.periodLockRequired(),
                        request.fields(),
                        request.controls() == null ? List.of() : request.controls(),
                        context.loginId(),
                        clock.instant());
        return approvals
                .submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                d.getReportKey(),
                                ActionType.CREATE,
                                context.locationId(),
                                json.writeValueAsString(p),
                                p.effectiveFrom(),
                                reason.strip()),
                        null)
                .id();
    }

    /**
     * The Rule Approver's approval: checked again (a version may have been approved meanwhile), the
     * version in force ends the day before, the new one is written with its fields and totals.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode())
                || !decision.concerns(ENTITY)
                || !decision.approved()) {
            return;
        }
        Proposal p = json.readValue(decision.payload(), Proposal.class);
        OutputDefinition d = definition(p.outputKey());
        check(d, p);
        List<OutputVersion> earlier =
                versions.findByReportDefIdOrderByEffectiveFromDesc(d.getReportDefId());
        earlier.stream()
                .filter(
                        v ->
                                v.getEffectiveTo() == null
                                        || !v.getEffectiveTo().isBefore(p.effectiveFrom()))
                .forEach(
                        v -> {
                            v.endBefore(p.effectiveFrom());
                            versions.save(v);
                        });
        OutputVersion saved =
                versions.save(
                        OutputVersion.approved(
                                d.getReportDefId(),
                                earlier.stream()
                                                .mapToInt(OutputVersion::getVersionNo)
                                                .max()
                                                .orElse(0)
                                        + 1,
                                p.effectiveFrom(),
                                p.layoutFormat(),
                                p.layoutNote(),
                                p.roundingRuleKey(),
                                p.periodLockRequired(),
                                earlier.isEmpty() ? null : earlier.get(0).getReportVersionId(),
                                decision.approvalId(),
                                p.proposedBy(),
                                clock.instant()));
        int order = 1;
        for (OutputRules.Field f : p.fields()) {
            String ref = f.reference().strip();
            fields.save(
                    OutputField.of(
                            saved.getReportVersionId(),
                            order++,
                            f.label().strip(),
                            f.binding(),
                            f.binding() == OutputField.Binding.COMPONENT ? componentId(ref) : null,
                            f.binding() == OutputField.Binding.FIELD_SOURCE
                                    ? fieldSourceId(ref)
                                    : null,
                            f.binding() == OutputField.Binding.AGGREGATE ? ref : null,
                            f.binding() == OutputField.Binding.DERIVED
                                            || f.binding() == OutputField.Binding.LITERAL
                                    ? ref
                                    : null,
                            blankToNull(f.formatMask())));
        }
        p.controls()
                .forEach(
                        c ->
                                controls.save(
                                        OutputControlTotal.of(
                                                saved.getReportVersionId(),
                                                c.label().strip(),
                                                c.reconcilesAgainst(),
                                                c.tolerance(),
                                                c.releaseGate())));
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                d.getReportKey(),
                                "version",
                                earlier.isEmpty()
                                        ? null
                                        : earlier.get(0).getEffectiveFrom().toString(),
                                "v"
                                        + saved.getVersionNo()
                                        + " from "
                                        + p.effectiveFrom()
                                        + "; "
                                        + p.fields().size()
                                        + " fields",
                                decision.reason() == null
                                        ? "M-17 version approved"
                                        : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
    }

    /** The version governing {@code key} on {@code day}, if any (an Active definition). */
    @Transactional(readOnly = true)
    public Optional<Governing> governing(String key, LocalDate day) {
        return definitions
                .findByReportKey(key)
                .flatMap(
                        d ->
                                versions
                                        .findByReportDefIdOrderByEffectiveFromDesc(
                                                d.getReportDefId())
                                        .stream()
                                        .filter(v -> v.governs(day))
                                        .findFirst())
                .map(
                        v ->
                                new Governing(
                                        v,
                                        fields.findByReportVersionIdOrderByFieldOrder(
                                                v.getReportVersionId()),
                                        controls.findByReportVersionId(v.getReportVersionId())));
    }

    /** The control totals of one version: those an output generated under it is checked against. */
    @Transactional(readOnly = true)
    public List<OutputControlTotal> controlsOf(Long reportVersionId) {
        return controls.findByReportVersionId(reportVersionId);
    }

    /** SRS M-17: the governing version, or the output is blocked (never an assumed layout). */
    public Governing requireGoverning(String key, LocalDate day) {
        return governing(key, day)
                .orElseThrow(
                        () ->
                                new FciException(
                                        TaxErrorCode.OUTPUT_BLOCKED,
                                        new Object[] {key, day.toString()},
                                        List.of(),
                                        null));
    }

    // ---------------------------------------------------------------- helpers

    private void check(OutputDefinition d, Proposal p) {
        OutputRules.requireAfter(
                d.getReportKey(),
                p.effectiveFrom(),
                versions.findByReportDefIdOrderByEffectiveFromDesc(d.getReportDefId()).stream()
                        .findFirst()
                        .map(OutputVersion::getEffectiveFrom));
        OutputRules.requireLayout(p.layoutFormat(), p.controls());
        OutputRules.requireFields(
                p.fields(),
                ref -> componentId(ref) != null,
                ref -> fieldSourceId(ref) != null,
                this::formulaValid);
    }

    private Long componentId(String payCode) {
        try {
            return pay.component(Integer.parseInt(payCode))
                    .map(PayConfiguration.Component::componentId)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long fieldSourceId(String entityDotField) {
        int dot = entityDotField.indexOf('.');
        if (dot <= 0 || dot == entityDotField.length() - 1) {
            return null;
        }
        return fieldSources
                .field(entityDotField.substring(0, dot), entityDotField.substring(dot + 1))
                .map(FieldSources.Field::fieldSourceId)
                .orElse(null);
    }

    private boolean formulaValid(String formula) {
        try {
            rules.checkFormula(formula, false);
            return true;
        } catch (FciException e) {
            return false;
        }
    }

    private OutputDefinition definition(String key) {
        return definitions
                .findByReportKey(key == null ? "" : key)
                // An inactive definition is withdrawn: never versioned again (R-DEL: kept, not
                // deleted).
                .filter(d -> DOMAIN.equals(d.getOutputDomain()) && d.isActive())
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private VersionView view(
            OutputVersion v,
            LocalDate today,
            List<OutputField> versionFields,
            List<OutputControlTotal> versionControls) {
        return new VersionView(
                v.getReportVersionId(),
                v.getVersionNo(),
                v.getEffectiveFrom(),
                v.getEffectiveTo(),
                v.getLayoutFormat(),
                v.isPeriodLockRequired(),
                v.governs(today),
                versionFields.stream()
                        .map(
                                f ->
                                        new OutputRules.Field(
                                                f.getFieldLabel(),
                                                f.binding(),
                                                reference(f),
                                                f.getFormatMask()))
                        .toList(),
                versionControls.stream()
                        .map(
                                c ->
                                        new OutputRules.Control(
                                                c.getControlLabel(),
                                                c.getReconcilesAgainst(),
                                                c.getToleranceAmount(),
                                                c.isReleaseGate()))
                        .toList());
    }

    private String reference(OutputField f) {
        return switch (f.binding()) {
            case COMPONENT ->
                    pay.componentById(f.getComponentId())
                            .map(c -> String.valueOf(c.legacyPayCode()))
                            .orElse("?");
            case FIELD_SOURCE ->
                    fieldSources
                            .byId(f.getFieldSourceId())
                            .map(x -> x.entityName() + "." + x.fieldName())
                            .orElse("?");
            case AGGREGATE -> f.getAggregateFunction();
            case LITERAL, DERIVED -> f.getDerivedExpression();
        };
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
