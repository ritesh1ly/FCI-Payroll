package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.ComponentRules;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.InputPatterns;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * M-07 Component Catalogue - Earnings &amp; Deductions (plan 06 phase 06d; SRS M-07 use case and
 * field tables, SRS-19-1, SRS-G-42, FAP-14; DDL {@code pay_component}, {@code
 * pay_component_engine}, {@code component_usage}): the Rule Administrator at Headquarters defines,
 * revises or retires a pay component; the Rule Approver approves (routes M07_CREATE, M07_EDIT); the
 * change lives in its approval item until then (plan 06 D-7).
 *
 * <ul>
 *   <li>a pay code is unique and never reused, a retired one and one on the retired list ({@code
 *       retired_pay_code}) included (SRS-G-42);
 *   <li>the basis names a rule of the catalogue (M-08) that exists (SRS M-07 alternate flow);
 *   <li>the payroll types are its engines: a labour-only code is never on the staff engine
 *       (FAP-14); a revision adds payroll types and never removes one (R-DEL: an engine row is
 *       never deleted; plan 06 D-8);
 *   <li>a retired code is end-dated, never deleted, and put on the retired list; its last day is
 *       today or earlier (D-9).
 * </ul>
 *
 * The natural account is checked for its form only: its activeness in the APPS chart of accounts
 * waits for the APPS feed (module 23; plan 06 D-10). "Subject to LWP" (SRS-LV-15, BR-LEAVE-007) is
 * the column V077_1 adds (US-GAP-298; the target DDL has none, CF-29): N unless marked.
 */
@Service
@Loggable
public class ComponentCatalogueService {

    public static final String SCREEN = "M-07";
    public static final String ENTITY = "pay_component";

    /** A catalogue entry as M-07 lists it. */
    public record ComponentRow(
            int payCode,
            String name,
            String type,
            String payslipGroup,
            String basisRuleKey,
            String source,
            String naturalAccount,
            String taxable,
            boolean inCpfBase,
            List<String> payrollTypes,
            boolean labourOnly,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean retired,
            int versionNo,
            boolean pending,
            boolean subjectToLwp,
            String eligibilityRuleKey) {

        /** A row with no eligibility flag (SRS l.1909: not mandatory). */
        public ComponentRow(
                int payCode,
                String name,
                String type,
                String payslipGroup,
                String basisRuleKey,
                String source,
                String naturalAccount,
                String taxable,
                boolean inCpfBase,
                List<String> payrollTypes,
                boolean labourOnly,
                LocalDate effectiveFrom,
                LocalDate effectiveTo,
                boolean retired,
                int versionNo,
                boolean pending,
                boolean subjectToLwp) {
            this(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    labourOnly,
                    effectiveFrom,
                    effectiveTo,
                    retired,
                    versionNo,
                    pending,
                    subjectToLwp,
                    null);
        }

        /** A row that does not carry the LWP attribute (not subject to LWP). */
        public ComponentRow(
                int payCode,
                String name,
                String type,
                String payslipGroup,
                String basisRuleKey,
                String source,
                String naturalAccount,
                String taxable,
                boolean inCpfBase,
                List<String> payrollTypes,
                boolean labourOnly,
                LocalDate effectiveFrom,
                LocalDate effectiveTo,
                boolean retired,
                int versionNo,
                boolean pending) {
            this(
                    payCode,
                    name,
                    type,
                    payslipGroup,
                    basisRuleKey,
                    source,
                    naturalAccount,
                    taxable,
                    inCpfBase,
                    payrollTypes,
                    labourOnly,
                    effectiveFrom,
                    effectiveTo,
                    retired,
                    versionNo,
                    pending,
                    false);
        }
    }

    /** Where a component is used (SRS M-07 "Where Used"). */
    public record Usage(String consumerType, String consumerKey, String note) {}

    /** The catalogue filters of M-07; {@code term} is the drawn "Search in list". */
    public record Filter(
            String componentClass,
            String type,
            String source,
            String taxable,
            String inCpfBase,
            String term) {
        public Filter(
                String componentClass,
                String type,
                String source,
                String taxable,
                String inCpfBase) {
            this(componentClass, type, source, taxable, inCpfBase, null);
        }
    }

    private final ConfigurationRepositories.PayComponents components;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SystemParameters settings;

    public ComponentCatalogueService(
            ConfigurationRepositories.PayComponents components,
            MakerChecker approvals,
            Authorization policy,
            JdbcTemplate jdbc,
            ObjectMapper json,
            SystemParameters settings) {
        this.components = components;
        this.approvals = approvals;
        this.policy = policy;
        this.jdbc = jdbc;
        this.json = json;
        this.settings = settings;
    }

    /** The catalogue, by pay code, filtered as M-07 offers. */
    @Transactional(readOnly = true)
    public List<ComponentRow> list(AccessContext context, Filter f) {
        policy.require(context, SCREEN, "VIEW");
        Map<Long, List<String>> engines = engines();
        Set<String> pending = pendingCodes();
        List<ComponentRow> out = new ArrayList<>();
        for (PayComponent c : components.findAllByOrderByLegacyPayCode()) {
            ComponentRow row = row(c, engines, pending);
            if (matches(row, f)) {
                out.add(row);
            }
        }
        return out;
    }

    /** The rows the export would write (the size check before streaming). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter f) {
        policy.require(context, SCREEN, "EXPORT");
        return list(context, f).size();
    }

    /** The catalogue as filtered, one row per pay code (ACT Export; API-06-1). */
    @Transactional(readOnly = true)
    public void export(AccessContext context, Filter f, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        sink.header(
                "pay_code",
                "component",
                "type",
                "driver_basis",
                "source_of_value",
                "eligibility_flag",
                "taxable",
                "in_cpf_base",
                "subject_to_lwp",
                "payroll_types",
                "natural_account",
                "effective_from",
                "effective_to",
                "retired");
        for (ComponentRow r : list(context, f)) {
            sink.row(
                    r.payCode(),
                    r.name(),
                    r.type(),
                    r.basisRuleKey(),
                    r.source(),
                    r.eligibilityRuleKey(),
                    r.taxable(),
                    r.inCpfBase() ? "Y" : "N",
                    r.subjectToLwp() ? "Y" : "N",
                    String.join(" ", r.payrollTypes()),
                    r.naturalAccount(),
                    r.effectiveFrom(),
                    r.effectiveTo(),
                    r.retired() ? "Y" : "N");
        }
    }

    /** SRS M-07 "Where Used": every registered consumer and every list that names the code. */
    @Transactional(readOnly = true)
    public List<Usage> whereUsed(AccessContext context, int payCode) {
        policy.require(context, SCREEN, "VIEW");
        PayComponent c = component(payCode);
        List<Usage> out = new ArrayList<>();
        jdbc.query(
                "SELECT consumer_type, consumer_key, binding_note FROM component_usage WHERE"
                        + " component_id = ? ORDER BY consumer_type, consumer_key",
                rs -> {
                    out.add(new Usage(rs.getString(1), rs.getString(2), rs.getString(3)));
                },
                c.getComponentId());
        jdbc.query(
                "SELECT l.list_code, v.value_code FROM reference_value v JOIN reference_list l ON"
                    + " l.list_id = v.list_id WHERE v.numeric_attribute = ? ORDER BY l.list_code,"
                    + " v.value_code",
                rs -> {
                    out.add(new Usage("REFERENCE_LIST", rs.getString(1), rs.getString(2)));
                },
                payCode);
        return out;
    }

    /** A new pay component for the Rule Approver (route M07_CREATE). */
    @Transactional
    public SubmittedApproval define(
            AccessContext context, ComponentRules.Definition d, String reason) {
        policy.require(context, SCREEN, "CREATE");
        check(d);
        Integer retiredList =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM retired_pay_code WHERE legacy_pay_code = ?",
                        Integer.class,
                        d.payCode());
        if (components.findByLegacyPayCode(d.payCode()).isPresent()
                || (retiredList != null && retiredList > 0)) {
            throw new FciException(
                    ApiErrorCode.COMPONENT_CODE_TAKEN, new Object[] {d.payCode()}, List.of(), null);
        }
        return submit(
                context,
                "CREATE",
                "DEFINE",
                d.withSubjectToLwp(Boolean.TRUE.equals(d.subjectToLwp())),
                reason);
    }

    /** A revision of a pay component in force (route M07_CREATE). */
    @Transactional
    public SubmittedApproval revise(
            AccessContext context, ComponentRules.Definition d, String reason) {
        policy.require(context, SCREEN, "CREATE");
        check(d);
        PayComponent c = component(d.payCode());
        if (c.isIsRetired()) {
            throw invalid("a retired pay code is not revised");
        }
        List<String> current = engines().getOrDefault(c.getComponentId(), List.of());
        if (!d.payrollTypes().containsAll(current)) {
            throw invalid("a revision adds payroll types and never removes one (retire the code)");
        }
        // US-GAP-298: a revision that does not name the LWP attribute keeps the current one.
        boolean lwp = d.subjectToLwp() == null ? c.isLwpSubjectFlag() : d.subjectToLwp();
        return submit(context, "CREATE", "REVISE", d.withSubjectToLwp(lwp), reason);
    }

    /** A pay code retired after {@code lastDay} (route M07_EDIT). */
    @Transactional
    public SubmittedApproval retire(
            AccessContext context, int payCode, LocalDate lastDay, String reason) {
        policy.require(context, SCREEN, "EDIT");
        PayComponent c = component(payCode);
        if (c.isIsRetired()) {
            throw invalid("the pay code is retired already");
        }
        if (lastDay == null || lastDay.isBefore(c.getEffectiveFrom())) {
            throw invalid("the last day is on or after the component's effective-from date");
        }
        // Plan 06 D-9: is_retired takes hold on approval, so a code is retired once it has stopped;
        // a future end is not scheduled.
        if (lastDay.isAfter(settings.today())) {
            throw invalid("the last day is today or earlier: a future retirement is not scheduled");
        }
        requireNoPending(payCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "RETIRE");
        payload.put("payCode", payCode);
        payload.put("lastDay", lastDay.toString());
        payload.put("reason", reason(reason));
        return approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "EDIT",
                        ENTITY,
                        String.valueOf(payCode),
                        ActionType.UPDATE,
                        context.locationId(),
                        json.writeValueAsString(payload),
                        lastDay.plusDays(1),
                        reason(reason)),
                (OtpProof) null);
    }

    private SubmittedApproval submit(
            AccessContext context,
            String action,
            String change,
            ComponentRules.Definition d,
            String reason) {
        if (d.basisRuleKey() != null) {
            Integer known =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM rule_definition WHERE rule_key = ?",
                            Integer.class,
                            d.basisRuleKey());
            if (known == null || known == 0) {
                throw invalid(
                        "the basis rule " + d.basisRuleKey() + " is not in the catalogue (M-08)");
            }
        }
        requireNoPending(d.payCode());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", change);
        payload.put("payCode", d.payCode());
        payload.put("name", d.name().strip());
        payload.put("type", d.type());
        payload.put("payslipGroup", d.payslipGroup());
        payload.put("basisRuleKey", d.basisRuleKey());
        payload.put("eligibilityRuleKey", d.eligibilityRuleKey());
        payload.put("source", d.source());
        payload.put("naturalAccount", d.naturalAccount());
        payload.put("taxable", d.taxable());
        payload.put("inCpfBase", d.inCpfBase());
        payload.put("subjectToLwp", Boolean.TRUE.equals(d.subjectToLwp()));
        payload.put("payrollTypes", new TreeSet<>(d.payrollTypes()));
        payload.put("effectiveFrom", d.effectiveFrom().toString());
        return approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        action,
                        ENTITY,
                        String.valueOf(d.payCode()),
                        "DEFINE".equals(change) ? ActionType.CREATE : ActionType.UPDATE,
                        context.locationId(),
                        json.writeValueAsString(payload),
                        d.effectiveFrom(),
                        reason(reason)),
                (OtpProof) null);
    }

    private static void check(ComponentRules.Definition d) {
        if (d == null) {
            throw invalid("the component is missing");
        }
        ComponentRules.defect(d)
                .ifPresent(
                        why -> {
                            throw invalid(why);
                        });
    }

    private void requireNoPending(int payCode) {
        if (pendingCodes().contains(String.valueOf(payCode))) {
            throw new FciException(
                    ApiErrorCode.COMPONENT_PENDING, new Object[] {payCode}, List.of(), null);
        }
    }

    private Set<String> pendingCodes() {
        return new TreeSet<>(
                jdbc.queryForList(
                        "SELECT entity_key FROM approval_queue WHERE entity_name = ? AND status IN"
                                + " ('PENDING_APPROVAL', 'PENDING_ATTACHMENT')",
                        String.class,
                        ENTITY));
    }

    private Map<Long, List<String>> engines() {
        Map<Long, List<String>> out = new HashMap<>();
        jdbc.query(
                "SELECT component_id, payroll_type FROM pay_component_engine ORDER BY component_id,"
                        + " payroll_type",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return out;
    }

    private PayComponent component(int payCode) {
        return components
                .findByLegacyPayCode(payCode)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static ComponentRow row(
            PayComponent c, Map<Long, List<String>> engines, Set<String> pending) {
        return new ComponentRow(
                c.getLegacyPayCode(),
                c.getComponentName(),
                c.getComponentType(),
                c.getPayslipGroup(),
                c.getBasisRuleKey(),
                c.getSourceCode(),
                c.getGlNaturalAccount(),
                c.getTaxableFlag(),
                c.isInCpfBaseFlag(),
                List.copyOf(engines.getOrDefault(c.getComponentId(), List.of())),
                c.isLabourSpecificFlag(),
                c.getEffectiveFrom(),
                c.getEffectiveTo(),
                c.isIsRetired(),
                c.getVersionNo() == null ? 1 : c.getVersionNo(),
                pending.contains(String.valueOf(c.getLegacyPayCode())),
                c.isLwpSubjectFlag(),
                c.getEligibilityRuleKey());
    }

    static boolean matches(ComponentRow r, Filter f) {
        if (f == null) {
            return true;
        }
        if ("STAFF".equals(f.componentClass()) && r.labourOnly()) {
            return false;
        }
        if ("LABOUR".equals(f.componentClass()) && !r.labourOnly()) {
            return false;
        }
        return (f.type() == null || f.type().equals(r.type()))
                && (f.source() == null || f.source().equals(r.source()))
                && (f.taxable() == null || f.taxable().equals(r.taxable()))
                && (f.inCpfBase() == null || f.inCpfBase().equals(r.inCpfBase() ? "Y" : "N"))
                && searched(r, f.term());
    }

    /** The drawn "Search in list": the code or the name, without regard to case. */
    private static boolean searched(ComponentRow r, String term) {
        if (term == null || term.isBlank()) {
            return true;
        }
        String wanted = term.strip().toLowerCase(Locale.ROOT);
        return String.valueOf(r.payCode()).contains(wanted)
                || r.name().toLowerCase(Locale.ROOT).contains(wanted);
    }

    static String reason(String reason) {
        if (reason == null
                || reason.isBlank()
                || reason.length() > 500
                || !InputPatterns.isPrintableText(reason)) {
            throw invalid("the reason is required, printable, at most 500 characters");
        }
        return reason.strip();
    }

    private static FciException invalid(String why) {
        return new FciException(
                ApiErrorCode.COMPONENT_DEFINITION, new Object[] {why}, List.of(), null);
    }
}
