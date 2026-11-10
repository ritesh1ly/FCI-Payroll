package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterVersioning;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.ReferenceValue;
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
import java.math.BigDecimal;
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
 * M-23 Other Parameters, Ceilings, Deputation &amp; Shift, on {@code other_parameter} (target DDL
 * v4.0 S19): effective-dated, the Rule Administrator maintains, the Rule Approver approves at
 * Headquarters (workflow action M23_CREATE). {@code other_parameter} has no state column: a version
 * is in force when its approval is APPROVED and its dates cover today; an approval ends the
 * previous version the day before. An unconfirmed or unseeded parameter is shown as such: v4.0
 * warns that an unseeded ceiling silently over-pays.
 */
@Service
@Loggable
public class OtherParameterService {

    static final String SCREEN = "M-23";
    public static final String ENTITY = "other_parameter";
    static final String LIST = "OTHER_PARAMETER";

    /**
     * M-23 draws this row but no rate of it is held in Payroll: the amount is received from HRMS
     * and deducted as received (SRS M-23 table, FRS §2.3; R-SRC: refused by the API).
     */
    public static final String MHS_CONTRIBUTION = "MHS_CONTRIBUTION";

    /** HH:MM from 00:00 to 23:59, and 24:00 for the end of the day (never 24:30). */
    private static final Pattern TIME = Pattern.compile("^(([01]\\d|2[0-3]):[0-5]\\d|24:00)$");

    public record ParameterVersion(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal numericValue,
            BigDecimal ceilingValue,
            BigDecimal percentValue,
            String windowFromTime,
            String windowToTime,
            Integer instalmentCount,
            String payrollTypeScope,
            boolean confirmed,
            String status,
            Long approvalId) {}

    public record ParameterRow(
            String code,
            String name,
            Integer payCode,
            ParameterVersion current,
            ParameterVersion pending,
            boolean hrmsOwned) {}

    public record ProposedParameter(
            LocalDate effectiveFrom,
            BigDecimal numericValue,
            BigDecimal ceilingValue,
            BigDecimal percentValue,
            String windowFromTime,
            String windowToTime,
            Integer instalmentCount,
            String payrollTypeScope,
            boolean confirmed,
            String reason) {}

    private final ConfigurationRepositories.ReferenceValues catalogue;
    private final ConfigurationRepositories.OtherParameters parameters;
    private final ApprovalStatuses approvalStatuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;

    public OtherParameterService(
            ConfigurationRepositories.ReferenceValues catalogue,
            ConfigurationRepositories.OtherParameters parameters,
            ApprovalStatuses approvalStatuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings) {
        this.catalogue = catalogue;
        this.parameters = parameters;
        this.approvalStatuses = approvalStatuses;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<ParameterRow> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        List<ReferenceValue> entries = catalogue.findByListCodes(List.of(LIST));
        List<OtherParameter> all =
                parameters.findByParameterCodeInOrderByParameterCodeAscVersionNoDesc(
                        entries.stream().map(ReferenceValue::getValueCode).toList());
        Map<Long, ApprovalStatus> status = statuses(all);
        Map<String, List<OtherParameter>> byCode = new HashMap<>();
        all.forEach(
                p -> byCode.computeIfAbsent(p.getParameterCode(), k -> new ArrayList<>()).add(p));
        LocalDate today = settings.today();
        List<ParameterRow> rows = new ArrayList<>();
        for (ReferenceValue entry : entries) {
            List<OtherParameter> versions = byCode.getOrDefault(entry.getValueCode(), List.of());
            OtherParameter current =
                    versions.stream()
                            .filter(
                                    p ->
                                            status.get(p.getApprovalId()) == ApprovalStatus.APPROVED
                                                    && p.coversDay(today))
                            .findFirst()
                            .orElse(null);
            OtherParameter pending =
                    versions.stream()
                            .filter(
                                    p ->
                                            status.get(p.getApprovalId())
                                                    == ApprovalStatus.PENDING_APPROVAL)
                            .findFirst()
                            .orElse(null);
            rows.add(
                    new ParameterRow(
                            entry.getValueCode(),
                            entry.getValueLabelEn(),
                            entry.getNumericAttribute() == null
                                    ? null
                                    : entry.getNumericAttribute().intValue(),
                            view(current, status),
                            view(pending, status),
                            MHS_CONTRIBUTION.equals(entry.getValueCode())));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<ParameterVersion> history(AccessContext context, String code) {
        policy.require(context, SCREEN, "VIEW");
        entry(code);
        List<OtherParameter> versions = parameters.findByParameterCodeOrderByVersionNoDesc(code);
        Map<Long, ApprovalStatus> status = statuses(versions);
        return versions.stream().map(p -> view(p, status)).toList();
    }

    @Transactional
    public ParameterVersion propose(
            AccessContext context, String code, ProposedParameter request, OtpProof otp) {
        ReferenceValue entry = entry(code);
        if (MHS_CONTRIBUTION.equals(code)) {
            throw new FciException(ApiErrorCode.MASTER_HRMS_OWNED);
        }
        if (request == null || request.effectiveFrom() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        validate(request);
        List<OtherParameter> history = parameters.findByParameterCodeOrderByVersionNoDesc(code);
        Map<Long, ApprovalStatus> status = statuses(history);
        MasterVersioning.requireFreeDate(
                request.effectiveFrom(),
                history.stream()
                        .filter(p -> status.get(p.getApprovalId()) == ApprovalStatus.APPROVED)
                        .map(OtherParameter::getEffectiveFrom)
                        .toList(),
                history.stream().map(OtherParameter::getEffectiveFrom).toList());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                code,
                                ActionType.CREATE,
                                policy.isAtHeadquarters(context) ? context.locationId() : null,
                                "{\"parameter\":\""
                                        + code
                                        + "\",\"from\":\""
                                        + request.effectiveFrom()
                                        + "\"}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        int next = history.isEmpty() ? 1 : history.get(0).getVersionNo() + 1;
        OtherParameter saved =
                parameters.save(
                        OtherParameter.propose(
                                code,
                                entry.getValueLabelEn(),
                                request.numericValue(),
                                request.ceilingValue(),
                                request.percentValue(),
                                blankToNull(request.windowFromTime()),
                                blankToNull(request.windowToTime()),
                                request.instalmentCount(),
                                blankToNull(request.payrollTypeScope()),
                                next,
                                request.effectiveFrom(),
                                request.confirmed(),
                                item.id()));
        return view(saved, Map.of(item.id(), item.status()));
    }

    /**
     * At least one value; amounts and counts not negative; a percentage at most 100; times as
     * HH:MM.
     */
    static void validate(ProposedParameter p) {
        boolean anyValue =
                p.numericValue() != null
                        || p.ceilingValue() != null
                        || p.percentValue() != null
                        // A blank time (the form's empty field) is no value.
                        || blankToNull(p.windowFromTime()) != null
                        || p.instalmentCount() != null;
        boolean ok =
                anyValue
                        && (p.numericValue() == null || p.numericValue().signum() >= 0)
                        && (p.ceilingValue() == null || p.ceilingValue().signum() >= 0)
                        && (p.percentValue() == null
                                || (p.percentValue().signum() >= 0
                                        && p.percentValue().compareTo(BigDecimal.valueOf(100))
                                                <= 0))
                        && (p.instalmentCount() == null || p.instalmentCount() > 0)
                        && (blankToNull(p.windowFromTime()) == null
                                || TIME.matcher(p.windowFromTime()).matches())
                        && (blankToNull(p.windowToTime()) == null
                                || TIME.matcher(p.windowToTime()).matches());
        if (!ok) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private ReferenceValue entry(String code) {
        return catalogue.findByListCodes(List.of(LIST)).stream()
                .filter(v -> v.getValueCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new FciException(ApiErrorCode.MASTER_NOT_IN_CATALOGUE));
    }

    private Map<Long, ApprovalStatus> statuses(List<OtherParameter> rows) {
        return approvalStatuses.of(
                rows.stream().map(OtherParameter::getApprovalId).filter(Objects::nonNull).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ParameterVersion view(OtherParameter p, Map<Long, ApprovalStatus> status) {
        if (p == null) {
            return null;
        }
        ApprovalStatus s = status.get(p.getApprovalId());
        return new ParameterVersion(
                p.getParameterId(),
                p.getVersionNo(),
                p.getEffectiveFrom(),
                p.getEffectiveTo(),
                p.getNumericValue(),
                p.getCeilingValue(),
                p.getPercentValue(),
                p.getWindowFromTime(),
                p.getWindowToTime(),
                p.getInstalmentCount(),
                p.getPayrollTypeScope(),
                p.isIsConfirmed(),
                s == null ? "NONE" : s.name(),
                p.getApprovalId());
    }
}
