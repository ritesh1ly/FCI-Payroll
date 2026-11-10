package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.business.model.SystemParameter;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-07 System Parameters (ADM-SYS-001) on the rule engine of target DDL v4.0: a change is a new
 * {@code rule_version} of the parameter's SETTING rule, submitted for a Headquarters Payroll
 * Checker's approval (workflow action A07_EDIT); only an approved version is in force ({@link
 * SystemParameters}). A version is dated: it takes effect on the day of approval, or the next free
 * day if the parameter already has a version dated today (one version per rule and date,
 * uk_rule_version_eff). Session settings apply to sessions started after that.
 */
@Service
@Loggable
public class SystemParameterService {

    static final String MODULE = "A-07";
    public static final String ENTITY = "rule_version";

    public record PendingChange(
            long value,
            Long approvalId,
            String requestedBy,
            Instant requestedAt,
            LocalDate effectiveFrom,
            String reason) {}

    /**
     * One A-07 setting. {@code value}, {@code version}, {@code effectiveFrom} and {@code changedBy}
     * are null for a setting FCI has not configured yet (defined, never seeded).
     */
    public record ParameterView(
            String code,
            String group,
            String valueType,
            Long value,
            long minValue,
            long maxValue,
            String unit,
            boolean enforced,
            Integer version,
            LocalDate effectiveFrom,
            String changedBy,
            PendingChange pending) {}

    public record ChangeRequest(String code, Long value, String reason) {}

    private final ConfigurationRepositories.RuleVersions versions;
    private final SystemParameters current;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final Clock clock;

    public SystemParameterService(
            ConfigurationRepositories.RuleVersions versions,
            SystemParameters current,
            MakerChecker approvals,
            Authorization policy,
            Clock clock) {
        this.versions = versions;
        this.current = current;
        this.approvals = approvals;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ParameterView> list(AccessContext context) {
        policy.require(context, MODULE, "VIEW");
        Map<String, SystemParameter> inForce = current.current();
        List<ParameterView> result = new ArrayList<>();
        current.definitions().stream()
                .sorted(
                        java.util.Comparator.comparing(SystemParameters.Definition::group)
                                .thenComparing(SystemParameters.Definition::code))
                .forEach(
                        d -> {
                            SystemParameter p = inForce.get(d.code());
                            result.add(p == null ? unseeded(d) : view(p));
                        });
        return result;
    }

    /** Proposes a new value; in force only after an HQ checker approves it. */
    @Transactional
    public ParameterView propose(AccessContext context, ChangeRequest request, OtpProof otp) {
        if (request == null || request.code() == null || request.value() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        // A setting with a value in force, or one defined but not configured yet (its first value).
        SystemParameters.Definition definition =
                current.definitions().stream()
                        .filter(d -> d.code().equals(request.code()))
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        SystemParameter active = current.current().get(request.code());
        long value = request.value();
        if (value < definition.minValue() || value > definition.maxValue()) {
            throw new FciException(
                    ApiErrorCode.PARAMETER_OUT_OF_RANGE,
                    new Object[] {definition.minValue(), definition.maxValue()},
                    List.of(),
                    null);
        }
        if (active != null && value == active.value()) {
            throw new FciException(ApiErrorCode.PARAMETER_UNCHANGED);
        }
        List<RuleVersion> history = versions.findByRuleIdOrderByVersionNoDesc(definition.ruleId());
        // HQ-only rule (workflow action A07_EDIT, office level HQ): the session must be at
        // Headquarters.
        Long hq = policy.isAtHeadquarters(context) ? context.locationId() : null;
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                MODULE,
                                "EDIT",
                                ENTITY,
                                definition.code(),
                                ActionType.UPDATE,
                                hq,
                                "{\"code\":\""
                                        + definition.code()
                                        + "\",\"from\":"
                                        + (active == null ? "null" : String.valueOf(active.value()))
                                        + ",\"to\":"
                                        + value
                                        + "}",
                                null,
                                request.reason()),
                        otp);
        int next = history.isEmpty() ? 1 : history.get(0).getVersionNo() + 1;
        versions.save(
                RuleVersion.propose(
                        definition.ruleId(),
                        next,
                        nextFreeDay(history, current.today()),
                        BigDecimal.valueOf(value),
                        context.loginId(),
                        item.id(),
                        clock.instant()));
        current.evict();
        return list(context).stream()
                .filter(v -> v.code().equals(definition.code()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * The first day from {@code from} on which the rule has no version yet (one version per rule
     * and date).
     */
    static LocalDate nextFreeDay(List<RuleVersion> history, LocalDate from) {
        LocalDate day = from;
        for (RuleVersion v : history) {
            if (!v.getEffectiveFrom().isBefore(day)) {
                day = v.getEffectiveFrom().plusDays(1);
            }
        }
        return day;
    }

    /** A setting defined but not configured: no value, only its bounds and a pending proposal. */
    private ParameterView unseeded(SystemParameters.Definition d) {
        return new ParameterView(
                d.code(),
                d.group(),
                d.valueType(),
                null,
                d.minValue(),
                d.maxValue(),
                d.unit(),
                d.enforced(),
                null,
                null,
                null,
                pending(d.ruleId()));
    }

    private PendingChange pending(Long ruleId) {
        return versions.findByRuleIdOrderByVersionNoDesc(ruleId).stream()
                .filter(v -> v.getState() == RuleVersion.State.PENDING_APPROVAL)
                .findFirst()
                .map(
                        v ->
                                new PendingChange(
                                        v.getScalarValue().longValueExact(),
                                        v.getApprovalId(),
                                        v.getCreatedBy(),
                                        v.getCreatedAt(),
                                        v.getEffectiveFrom(),
                                        null))
                .orElse(null);
    }

    private ParameterView view(SystemParameter p) {
        return new ParameterView(
                p.code(),
                p.group(),
                p.valueType().name(),
                p.value(),
                p.minValue(),
                p.maxValue(),
                p.unit(),
                p.enforced(),
                p.versionNo(),
                p.effectiveFrom(),
                p.changedBy(),
                pending(p.ruleId()));
    }
}
