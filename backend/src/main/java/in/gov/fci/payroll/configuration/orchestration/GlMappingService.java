package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterVersioning;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-24 Accounting &amp; Bank Mapping, on {@code gl_account_mapping} (target DDL v4.0 S23) for the
 * pay components of {@code pay_component} (S07): the natural account of each pay code,
 * effective-dated, maker-checker (workflow action M24_CREATE). This screen maintains the all-units
 * mapping ({@code org_unit_id} NULL); a pay code without a mapping in force is flagged, because
 * v4.0 holds such a posting on the exceptions worklist instead of a suspense account.
 */
@Service
@Loggable
public class GlMappingService {

    static final String SCREEN = "M-24";
    public static final String ENTITY = "gl_account_mapping";
    private static final Pattern ACCOUNT = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,19}$");

    public record MappingVersion(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String naturalAccount,
            String status,
            Long approvalId) {}

    public record ComponentRow(
            int payCode,
            String name,
            String type,
            String payslipGroup,
            boolean retired,
            MappingVersion current,
            MappingVersion pending) {}

    public record ProposedMapping(LocalDate effectiveFrom, String naturalAccount, String reason) {}

    private final ConfigurationRepositories.PayComponents components;
    private final ConfigurationRepositories.GlMappings mappings;
    private final ApprovalStatuses approvalStatuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;

    public GlMappingService(
            ConfigurationRepositories.PayComponents components,
            ConfigurationRepositories.GlMappings mappings,
            ApprovalStatuses approvalStatuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings) {
        this.components = components;
        this.mappings = mappings;
        this.approvalStatuses = approvalStatuses;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<ComponentRow> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<PayComponent> all = components.findAllByOrderByLegacyPayCode();
        List<GlAccountMapping> rows =
                mappings.findByComponentIdInOrderByComponentIdAscVersionNoDesc(
                        all.stream().map(PayComponent::getComponentId).toList());
        Map<Long, ApprovalStatus> status = statuses(rows);
        Map<Long, List<GlAccountMapping>> byComponent = new HashMap<>();
        rows.stream()
                .filter(m -> m.getOrgUnitId() == null)
                .forEach(
                        m ->
                                byComponent
                                        .computeIfAbsent(m.getComponentId(), k -> new ArrayList<>())
                                        .add(m));
        LocalDate today = settings.today();
        List<ComponentRow> result = new ArrayList<>();
        for (PayComponent c : all) {
            List<GlAccountMapping> versions =
                    byComponent.getOrDefault(c.getComponentId(), List.of());
            GlAccountMapping current =
                    versions.stream()
                            .filter(
                                    m ->
                                            status.get(m.getApprovalId()) == ApprovalStatus.APPROVED
                                                    && m.coversDay(today))
                            .findFirst()
                            .orElse(null);
            GlAccountMapping pending =
                    versions.stream()
                            .filter(
                                    m ->
                                            status.get(m.getApprovalId())
                                                    == ApprovalStatus.PENDING_APPROVAL)
                            .findFirst()
                            .orElse(null);
            result.add(
                    new ComponentRow(
                            c.getLegacyPayCode(),
                            c.getComponentName(),
                            c.getComponentType(),
                            c.getPayslipGroup(),
                            c.isIsRetired(),
                            view(current, status),
                            view(pending, status)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<MappingVersion> history(AccessContext context, int payCode) {
        policy.require(context, SCREEN, "VIEW");
        PayComponent component = component(payCode);
        List<GlAccountMapping> versions =
                mappings.findByComponentIdOrderByVersionNoDesc(component.getComponentId()).stream()
                        .filter(m -> m.getOrgUnitId() == null)
                        .toList();
        Map<Long, ApprovalStatus> status = statuses(versions);
        return versions.stream().map(m -> view(m, status)).toList();
    }

    @Transactional
    public MappingVersion propose(
            AccessContext context, int payCode, ProposedMapping request, OtpProof otp) {
        PayComponent component = component(payCode);
        if (request == null
                || request.effectiveFrom() == null
                || request.naturalAccount() == null
                || !ACCOUNT.matcher(request.naturalAccount().strip()).matches()
                || component.isIsRetired()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<GlAccountMapping> history =
                mappings.findByComponentIdOrderByVersionNoDesc(component.getComponentId()).stream()
                        .filter(m -> m.getOrgUnitId() == null)
                        .toList();
        Map<Long, ApprovalStatus> status = statuses(history);
        MasterVersioning.requireFreeDate(
                request.effectiveFrom(),
                history.stream()
                        .filter(m -> status.get(m.getApprovalId()) == ApprovalStatus.APPROVED)
                        .map(GlAccountMapping::getEffectiveFrom)
                        .toList(),
                history.stream().map(GlAccountMapping::getEffectiveFrom).toList());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(payCode),
                                ActionType.CREATE,
                                policy.isAtHeadquarters(context) ? context.locationId() : null,
                                "{\"payCode\":"
                                        + payCode
                                        + ",\"naturalAccount\":\""
                                        + request.naturalAccount().strip()
                                        + "\"}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        int next = history.isEmpty() ? 1 : history.get(0).getVersionNo() + 1;
        GlAccountMapping saved =
                mappings.save(
                        GlAccountMapping.propose(
                                component.getComponentId(),
                                null,
                                null,
                                request.naturalAccount().strip(),
                                next,
                                request.effectiveFrom(),
                                item.id()));
        return view(saved, Map.of(item.id(), item.status()));
    }

    private PayComponent component(int payCode) {
        return components
                .findByLegacyPayCode(payCode)
                .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
    }

    private Map<Long, ApprovalStatus> statuses(List<GlAccountMapping> rows) {
        return approvalStatuses.of(
                rows.stream()
                        .map(GlAccountMapping::getApprovalId)
                        .filter(Objects::nonNull)
                        .toList());
    }

    private static MappingVersion view(GlAccountMapping m, Map<Long, ApprovalStatus> status) {
        if (m == null) {
            return null;
        }
        ApprovalStatus s = status.get(m.getApprovalId());
        return new MappingVersion(
                m.getMappingId(),
                m.getVersionNo(),
                m.getEffectiveFrom(),
                m.getEffectiveTo(),
                m.getNaturalAccount(),
                s == null ? "NONE" : s.name(),
                m.getApprovalId());
    }
}
