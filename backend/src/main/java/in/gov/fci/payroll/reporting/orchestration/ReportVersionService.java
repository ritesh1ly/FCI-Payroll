package in.gov.fci.payroll.reporting.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.FieldSources;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.reporting.business.ReportErrorCode;
import in.gov.fci.payroll.reporting.business.ReportModelRules;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Control;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Distribution;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Field;
import in.gov.fci.payroll.reporting.business.ReportModelRules.Parameter;
import in.gov.fci.payroll.reporting.business.ReportRules;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-12 Report Catalogue versions (plan 15 phase 15c; SRS-18-9, SRS-18-10, SRS-G-12; BR-MIS-002,
 * BR-MIS-003; D15-1). Adding, changing or withdrawing a report is a new version of its definition:
 * the Report Administrator proposes it (layout format, period lock, store mode, blocked flag and
 * reason, effective from today or later), the Rule Approver approves it on W-01 (route M12_VERSION,
 * never the proposer). Approved, it is in force from its date and the version before ends the day
 * before; a closed period keeps the version effective then (SRS-G-12).
 *
 * <p>SRS-18-9 (US-GAP-34): a version also carries its Report Definition Model: the parameter set,
 * the field bindings (each resolving to the component catalogue or the field-source model, else the
 * proposal is rejected), the rounding / presentation key, the control totals (computable from the
 * field bindings) and the distribution routing, written with the version and kept with it.
 */
@Service
@Loggable
public class ReportVersionService {

    /** The screen, the approval entity and the route. */
    static final String SCREEN = "M-12";

    /** The approval entity: M-17 (module 08) owns "report_definition_version" for its outputs. */
    static final String ENTITY = "report_catalogue_version";

    static final String ROUTE = "M12_VERSION";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One version of a definition. */
    public record Version(
            long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String layoutFormat,
            boolean periodLockRequired,
            String storeMode,
            String approvalState,
            boolean blocked,
            String blockedReason,
            String createdBy,
            LocalDateTime createdAt,
            String roundingRuleKey,
            List<Parameter> parameters,
            List<Field> fields,
            List<Control> controls,
            List<Distribution> distribution) {}

    /** A proposed version with its Report Definition Model (SRS-18-9). */
    public record Proposal(
            String layoutFormat,
            boolean periodLockRequired,
            String storeMode,
            boolean blocked,
            String blockedReason,
            LocalDate effectiveFrom,
            String roundingRuleKey,
            List<Parameter> parameters,
            List<Field> fields,
            List<Control> controls,
            List<Distribution> distribution) {

        public Proposal {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            fields = fields == null ? List.of() : List.copyOf(fields);
            controls = controls == null ? List.of() : List.copyOf(controls);
            distribution = distribution == null ? List.of() : List.copyOf(distribution);
        }

        /** A version without a model: format, lock, store mode, blocked flag and date only. */
        public Proposal(
                String layoutFormat,
                boolean periodLockRequired,
                String storeMode,
                boolean blocked,
                String blockedReason,
                LocalDate effectiveFrom) {
            this(
                    layoutFormat,
                    periodLockRequired,
                    storeMode,
                    blocked,
                    blockedReason,
                    effectiveFrom,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }
    }

    private final JdbcTemplate jdbc;
    private final Authorization policy;
    private final MakerChecker approvals;
    private final AuditService audit;
    private final PayConfiguration pay;
    private final FieldSources fieldSources;
    private final RuleEngine rules;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public ReportVersionService(
            JdbcTemplate jdbc,
            Authorization policy,
            MakerChecker approvals,
            AuditService audit,
            PayConfiguration pay,
            FieldSources fieldSources,
            RuleEngine rules,
            Clock clock) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.pay = pay;
        this.fieldSources = fieldSources;
        this.rules = rules;
        this.clock = clock;
    }

    /** M-12: a definition's versions, newest first. */
    @Transactional(readOnly = true)
    public List<Version> versions(AccessContext context, String key) {
        policy.require(context, SCREEN, "VIEW");
        long definition = definition(key);
        return jdbc.query(
                "SELECT report_version_id, version_no, effective_from, effective_to, layout_format,"
                        + " period_lock_required, output_store_mode, approval_state, is_blocked,"
                        + " blocked_reason, created_by, created_at, rounding_rule_key FROM"
                        + " report_definition_version WHERE report_def_id = ? ORDER BY version_no"
                        + " DESC",
                (rs, i) -> {
                    java.sql.Date to = rs.getDate(4);
                    long id = rs.getLong(1);
                    return new Version(
                            rs.getLong(1),
                            rs.getInt(2),
                            rs.getDate(3).toLocalDate(),
                            to == null ? null : to.toLocalDate(),
                            rs.getString(5),
                            "Y".equals(rs.getString(6)),
                            rs.getString(7),
                            rs.getString(8),
                            "Y".equals(rs.getString(9)),
                            rs.getString(10),
                            rs.getString(11),
                            rs.getTimestamp(12).toLocalDateTime(),
                            rs.getString(13),
                            parametersOf(id),
                            fieldsOf(id),
                            controlsOf(id),
                            distributionOf(id));
                },
                definition);
    }

    /** M-12: the Report Administrator proposes a new version (SRS-18-10). */
    @Transactional
    public SubmittedApproval propose(AccessContext context, String key, Proposal p) {
        policy.require(context, SCREEN, "CREATE");
        long definition = definition(key);
        LocalDate today = LocalDate.now(clock.withZone(IST));
        if (p == null) {
            throw invalid("PROPOSAL");
        }
        ReportRules.versionDefect(
                        p.layoutFormat(),
                        p.effectiveFrom(),
                        today,
                        p.blocked(),
                        p.blockedReason(),
                        p.storeMode())
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        modelDefect(p)
                .ifPresent(
                        d -> {
                            throw invalid(d);
                        });
        List<Long> open =
                jdbc.queryForList(
                        "SELECT report_version_id FROM report_definition_version WHERE"
                                + " report_def_id = ? AND approval_state = 'PENDING_APPROVAL'",
                        Long.class,
                        definition);
        if (!open.isEmpty()) {
            throw invalid("PENDING");
        }
        // DDL uk_report_version_eff: one version a day, a rejected one included.
        Integer sameDay =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM report_definition_version WHERE report_def_id = ? AND"
                                + " effective_from = ?",
                        Integer.class,
                        definition,
                        Date.valueOf(p.effectiveFrom()));
        if (sameDay != null && sameDay > 0) {
            throw invalid("EFFECTIVE_TAKEN");
        }
        Integer last =
                jdbc.queryForObject(
                        "SELECT MAX(version_no) FROM report_definition_version WHERE report_def_id"
                                + " = ?",
                        Integer.class,
                        definition);
        int next = last == null ? 1 : last + 1;
        jdbc.update(
                "INSERT INTO report_definition_version (report_def_id, version_no, effective_from,"
                        + " layout_format, rounding_rule_key, period_lock_required,"
                        + " output_store_mode, approval_state, is_blocked, blocked_reason,"
                        + " created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " 'PENDING_APPROVAL', ?, ?, ?, ?)",
                definition,
                next,
                Date.valueOf(p.effectiveFrom()),
                p.layoutFormat(),
                p.roundingRuleKey(),
                p.periodLockRequired() ? "Y" : "N",
                p.storeMode(),
                p.blocked() ? "Y" : "N",
                p.blocked() ? p.blockedReason().strip() : null,
                context.loginId(),
                Timestamp.from(clock.instant()));
        Long written =
                jdbc.queryForObject(
                        "SELECT report_version_id FROM report_definition_version WHERE"
                                + " report_def_id = ? AND version_no = ?",
                        Long.class,
                        definition,
                        next);
        if (written == null) {
            throw new IllegalStateException("The version just written is not found");
        }
        long version = written;
        writeModel(version, p);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                ROUTE,
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(version),
                                ActionType.CREATE,
                                context.locationId(),
                                null,
                                p.effectiveFrom(),
                                key + " version " + next + " from " + p.effectiveFrom()),
                        null);
        jdbc.update(
                "UPDATE report_definition_version SET approval_id = ? WHERE report_version_id = ?",
                item.id(),
                version);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(version),
                                "approval_state",
                                null,
                                "PENDING_APPROVAL",
                                key + " version " + next + " proposed")
                        .approval(item.id())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
        return item;
    }

    /** The Rule Approver's decision: approved, in force from its date; else REJECTED. */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        long version = Long.parseLong(decision.entityKey());
        Map<String, Object> v =
                jdbc.queryForMap(
                        "SELECT report_def_id, effective_from, approval_state FROM"
                                + " report_definition_version WHERE report_version_id = ?",
                        version);
        if (!"PENDING_APPROVAL".equals(v.get("APPROVAL_STATE"))) {
            return;
        }
        String state = decision.approved() ? "APPROVED" : "REJECTED";
        if (decision.approved()) {
            LocalDate from =
                    new java.sql.Date(((java.util.Date) v.get("EFFECTIVE_FROM")).getTime())
                            .toLocalDate();
            // The version in force until now ends the day before (SRS-G-12: closed periods keep
            // it).
            Long previous =
                    jdbc
                            .queryForList(
                                    "SELECT report_version_id FROM report_definition_version WHERE"
                                        + " report_def_id = ? AND approval_state = 'APPROVED' AND"
                                        + " effective_to IS NULL AND report_version_id <> ?",
                                    Long.class,
                                    v.get("REPORT_DEF_ID"),
                                    version)
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (previous != null) {
                jdbc.update(
                        "UPDATE report_definition_version SET effective_to = ? WHERE"
                                + " report_version_id = ?",
                        Date.valueOf(from.minusDays(1)),
                        previous);
            }
            jdbc.update(
                    "UPDATE report_definition_version SET approval_state = 'APPROVED',"
                            + " supersedes_version_id = ? WHERE report_version_id = ?",
                    previous,
                    version);
        } else {
            jdbc.update(
                    "UPDATE report_definition_version SET approval_state = ? WHERE"
                            + " report_version_id = ?",
                    state,
                    version);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(version),
                                "approval_state",
                                "PENDING_APPROVAL",
                                state,
                                decision.reason() == null ? state : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN));
    }

    // ---------------------------------------------------------------- the model (SRS-18-9)

    /** The first defect of the version's Report Definition Model, or empty. */
    private Optional<String> modelDefect(Proposal p) {
        return ReportModelRules.roundingDefect(p.roundingRuleKey())
                .or(() -> ReportModelRules.parameterDefect(p.parameters()))
                .or(
                        () ->
                                ReportModelRules.fieldDefect(
                                        p.fields(),
                                        ref -> componentId(ref) != null,
                                        ref -> fieldSourceId(ref) != null,
                                        this::formulaValid))
                .or(() -> ReportModelRules.controlDefect(p.controls(), p.fields()))
                .or(() -> ReportModelRules.distributionDefect(p.distribution()));
    }

    private void writeModel(long version, Proposal p) {
        int order = 0;
        for (Parameter x : p.parameters()) {
            jdbc.update(
                    "INSERT INTO report_parameter (report_version_id, parameter_code,"
                            + " parameter_label, data_type, is_mandatory, role_scoped,"
                            + " default_expression, validation_rule, display_order) VALUES (?, ?,"
                            + " ?, ?, ?, ?, ?, ?, ?)",
                    version,
                    x.code(),
                    blank(x.label()),
                    x.dataType(),
                    x.mandatory() ? "Y" : "N",
                    x.roleScoped() ? "Y" : "N",
                    blank(x.defaultExpression()),
                    x.validationRule().strip(),
                    ++order);
        }
        order = 0;
        for (Field f : p.fields()) {
            String ref = f.reference().strip();
            jdbc.update(
                    "INSERT INTO report_field_binding (report_version_id, field_order, field_label,"
                        + " binding_type, component_id, field_source_id, aggregate_function,"
                        + " derived_expression, format_mask) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    version,
                    ++order,
                    f.label().strip(),
                    f.binding().name(),
                    f.binding() == ReportModelRules.Binding.COMPONENT ? componentId(ref) : null,
                    f.binding() == ReportModelRules.Binding.FIELD_SOURCE
                            ? fieldSourceId(ref)
                            : null,
                    f.binding() == ReportModelRules.Binding.AGGREGATE ? ref : null,
                    f.binding() == ReportModelRules.Binding.LITERAL
                                    || f.binding() == ReportModelRules.Binding.DERIVED
                            ? ref
                            : null,
                    blank(f.formatMask()));
        }
        for (Control c : p.controls()) {
            jdbc.update(
                    "INSERT INTO report_control_total (report_version_id, control_label,"
                        + " reconciles_against, tolerance_amount, is_release_gate) VALUES (?, ?, ?,"
                        + " ?, ?)",
                    version,
                    c.label().strip(),
                    c.reconcilesAgainst(),
                    c.tolerance(),
                    c.releaseGate() ? "Y" : "N");
        }
        for (Distribution d : p.distribution()) {
            jdbc.update(
                    "INSERT INTO report_distribution (report_version_id, consumer_name,"
                            + " consumer_type, delivery_channel, schedule_expression) VALUES (?, ?,"
                            + " ?, ?, ?)",
                    version,
                    d.consumerName().strip(),
                    d.consumerType(),
                    d.channel(),
                    blank(d.schedule()));
        }
    }

    private List<Parameter> parametersOf(long version) {
        return jdbc.query(
                "SELECT parameter_code, parameter_label, data_type, is_mandatory, role_scoped,"
                        + " default_expression, validation_rule FROM report_parameter WHERE"
                        + " report_version_id = ? ORDER BY display_order, parameter_id",
                (rs, i) ->
                        new Parameter(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                "Y".equals(rs.getString(4)),
                                "Y".equals(rs.getString(5)),
                                rs.getString(6),
                                rs.getString(7)),
                version);
    }

    /** The fields as proposed: a component by its pay code, a field source as entity.field. */
    private List<Field> fieldsOf(long version) {
        return jdbc.query(
                "SELECT b.field_label, b.binding_type, c.legacy_pay_code, f.entity_name,"
                        + " f.field_name, b.aggregate_function, b.derived_expression, b.format_mask"
                        + " FROM report_field_binding b LEFT JOIN pay_component c ON"
                        + " c.component_id = b.component_id LEFT JOIN field_source_registry f ON"
                        + " f.field_source_id = b.field_source_id WHERE b.report_version_id = ?"
                        + " ORDER BY b.field_order",
                (rs, i) -> {
                    ReportModelRules.Binding binding =
                            ReportModelRules.Binding.valueOf(rs.getString(2));
                    String reference =
                            switch (binding) {
                                case COMPONENT -> rs.getString(3);
                                case FIELD_SOURCE -> rs.getString(4) + "." + rs.getString(5);
                                case AGGREGATE -> rs.getString(6);
                                case LITERAL, DERIVED -> rs.getString(7);
                            };
                    return new Field(rs.getString(1), binding, reference, rs.getString(8));
                },
                version);
    }

    private List<Control> controlsOf(long version) {
        return jdbc.query(
                "SELECT control_label, reconciles_against, tolerance_amount, is_release_gate FROM"
                        + " report_control_total WHERE report_version_id = ? ORDER BY"
                        + " control_def_id",
                (rs, i) ->
                        new Control(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getBigDecimal(3),
                                "Y".equals(rs.getString(4))),
                version);
    }

    private List<Distribution> distributionOf(long version) {
        return jdbc.query(
                "SELECT consumer_name, consumer_type, delivery_channel, schedule_expression FROM"
                        + " report_distribution WHERE report_version_id = ? ORDER BY"
                        + " distribution_id",
                (rs, i) ->
                        new Distribution(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                version);
    }

    private Long componentId(String payCode) {
        try {
            return pay.component(Integer.parseInt(payCode))
                    .map(PayConfiguration.Component::componentId)
                    .orElse(null);
        } catch (NumberFormatException notAPayCode) {
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
        } catch (FciException outsideTheGrammar) {
            return false;
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private long definition(String key) {
        return jdbc
                .queryForList(
                        "SELECT report_def_id FROM report_definition WHERE report_key = ?",
                        Long.class,
                        key == null ? "" : key)
                .stream()
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static FciException invalid(String defect) {
        return new FciException(ReportErrorCode.VERSION, new Object[] {defect}, List.of(), null);
    }
}
