package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.MasterText;
import in.gov.fci.payroll.configuration.business.VersionChain;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
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
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-27 Operation Master (SRS-G-41; DDL v4.0 {@code operation_master}): the controlled list every
 * labour work-slip picks its operation from (free text is refused by the work-slip screens, module
 * 20). Each version names its code (A10, unique, never reused for another operation), its fixed
 * standard name (local names come from M-26), the labour payrolls it applies to, whether it is
 * bag-based, its rate or norm link and its Active governing circular (M-25). The HQ Rule
 * Administrator proposes (routes M27_CREATE, M27_EDIT for end-dating), a Payroll Checker approves;
 * an approved version is linked to its circular (where used). End-dated, never deleted; saved
 * work-slips keep the operation.
 */
@Service
@Loggable
public class OperationService {

    static final String SCREEN = "M-27";
    public static final String ENTITY = "operation_master";

    /** The approval entity of an end-dating (its key is the operation code). */
    public static final String END_DATE = "operation_end_date";

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,9}$");

    public record OperationVersion(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String name,
            boolean departmental,
            boolean dps,
            boolean nwnp,
            boolean bagBased,
            String normType,
            String rateNormLink,
            boolean dusting,
            Integer allowancePayCode,
            boolean presentButNoWork,
            Long circularId,
            String status,
            Long approvalId,
            String circularNumber) {}

    /**
     * One operation code. SRS-G-39 / SRS-G-40 (NEW-G1-06): {@code shownName} is the M-26 name the
     * user sees (own language, office, region, Global) where it differs from the standard name of
     * the version in force, so the screen shows "local (standard)"; null when there is none.
     */
    public record OperationRow(
            String code, OperationVersion current, OperationVersion pending, String shownName) {}

    public record ProposedOperation(
            LocalDate effectiveFrom,
            String name,
            boolean departmental,
            boolean dps,
            boolean nwnp,
            boolean bagBased,
            String normType,
            String rateNormLink,
            boolean dusting,
            Integer allowancePayCode,
            boolean presentButNoWork,
            Long circularId,
            String reason) {}

    private final ConfigurationRepositories.Operations operations;
    private final ConfigurationRepositories.PayComponents components;
    private final Circulars circulars;
    private final ApprovalStatuses statuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;
    private final MasterAudit audit;
    private final in.gov.fci.payroll.configuration.api.MasterLabels names;

    @org.springframework.beans.factory.annotation.Autowired
    public OperationService(
            ConfigurationRepositories.Operations operations,
            ConfigurationRepositories.PayComponents components,
            Circulars circulars,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            MasterAudit audit,
            in.gov.fci.payroll.configuration.api.MasterLabels names) {
        this.operations = operations;
        this.components = components;
        this.circulars = circulars;
        this.statuses = statuses;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
        this.audit = audit;
        this.names = names;
    }

    /** Without the M-26 names (standard names only; a search by code or name). */
    public OperationService(
            ConfigurationRepositories.Operations operations,
            ConfigurationRepositories.PayComponents components,
            Circulars circulars,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            MasterAudit audit) {
        this(operations, components, circulars, statuses, approvals, policy, settings, audit, null);
    }

    /** SRS-G-41: every operation code with the version in force today and any pending one. */
    @Transactional(readOnly = true)
    public List<OperationRow> list(AccessContext context) {
        return list(context, null, null);
    }

    /**
     * SRS-G-41 with SRS-G-39: the operations whose code, standard name or any Active M-26 label
     * contains {@code text} (ignoring case; every operation without text), each with the name the
     * user sees in {@code language} (EN / HI) at the session's office.
     */
    @Transactional(readOnly = true)
    public List<OperationRow> list(AccessContext context, String text, String language) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, String> shown =
                names == null
                        ? Map.of()
                        : names.shown(
                                in.gov.fci.payroll.configuration.api.MasterLabels.OPERATION,
                                language,
                                context.locationId());
        String needle =
                text == null || text.isBlank() ? null : text.strip().toLowerCase(Locale.ROOT);
        java.util.Set<String> byLabel =
                needle == null || names == null
                        ? java.util.Set.of()
                        : names.matching(
                                in.gov.fci.payroll.configuration.api.MasterLabels.OPERATION,
                                needle);
        Map<String, List<OperationMaster>> byCode = new LinkedHashMap<>();
        operations
                .findAllByOrderByOperationCodeAscVersionNoDesc()
                .forEach(
                        o ->
                                byCode.computeIfAbsent(o.getOperationCode(), k -> new ArrayList<>())
                                        .add(o));
        LocalDate today = settings.today();
        // SRS-G-41 (M-27 column Circular): the numbers read once for the whole list.
        Map<Long, String> circularNumbers =
                circulars.numbers(
                        byCode.values().stream()
                                .flatMap(List::stream)
                                .map(OperationMaster::getCircularOrderId)
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList());
        List<OperationRow> rows = new ArrayList<>();
        byCode.forEach(
                (code, versions) -> {
                    VersionChain<OperationMaster> chain = chain(versions);
                    OperationVersion current =
                            chain.inForceOn(today)
                                    .map(o -> view(o, chain, circularNumbers))
                                    .orElse(null);
                    OperationVersion pending =
                            chain.pending().map(o -> view(o, chain, circularNumbers)).orElse(null);
                    String standard = current != null ? current.name() : null;
                    if (needle != null
                            && !code.toLowerCase(Locale.ROOT).contains(needle)
                            && !versions.get(0)
                                    .getOperationName()
                                    .toLowerCase(Locale.ROOT)
                                    .contains(needle)
                            && !byLabel.contains(code)) {
                        return;
                    }
                    String local = shown.get(code);
                    rows.add(
                            new OperationRow(
                                    code,
                                    current,
                                    pending,
                                    local == null || local.equals(standard) ? null : local));
                });
        return rows;
    }

    /** SRS M-27 action Export: how many rows the export of this search writes. */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, String text, String language) {
        policy.require(context, SCREEN, "EXPORT");
        return list(context, text, language).size();
    }

    /**
     * SRS M-27 action Export: the master as filtered, one row per operation with the version in
     * force (or the pending one of a new code), the code and the name of each row (SRS-G-39).
     */
    @Transactional(readOnly = true)
    public void export(AccessContext context, String text, String language, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        List<OperationRow> rows = list(context, text, language);
        sink.header(
                "operation_code",
                "standard_name",
                "local_name",
                "departmental",
                "dps",
                "nwnp",
                "bag_based",
                "norm_type",
                "rate_norm_link",
                "circular",
                "effective_from",
                "effective_to",
                "status");
        for (OperationRow row : rows) {
            OperationVersion v = row.current() != null ? row.current() : row.pending();
            sink.row(
                    row.code(),
                    v.name(),
                    row.shownName(),
                    yesNo(v.departmental()),
                    yesNo(v.dps()),
                    yesNo(v.nwnp()),
                    yesNo(v.bagBased()),
                    v.normType(),
                    v.rateNormLink(),
                    v.circularNumber(),
                    v.effectiveFrom(),
                    v.effectiveTo(),
                    v.status());
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    private static String yesNo(boolean value) {
        return value ? "Y" : "N";
    }

    @Transactional(readOnly = true)
    public List<OperationVersion> history(AccessContext context, String code) {
        policy.require(context, SCREEN, "VIEW");
        List<OperationMaster> versions = operations.findByOperationCodeOrderByVersionNoDesc(code);
        VersionChain<OperationMaster> chain = chain(versions);
        return versions.stream().map(o -> view(o, chain)).toList();
    }

    /**
     * SRS-G-41: proposes a new operation (version 1) or a new version of one. The standard name
     * never changes across versions (OPERATION_NAME_FIXED).
     */
    @Transactional
    public OperationVersion propose(
            AccessContext context, String code, ProposedOperation request, OtpProof otp) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new FciException(
                    ApiErrorCode.MASTER_INVALID_TEXT,
                    new Object[] {"m27.code", 10},
                    List.of(),
                    null);
        }
        if (request == null || request.effectiveFrom() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String name = MasterText.required("m27.name", request.name(), 100);
        Particulars p = validate(request);
        List<OperationMaster> versions = operations.findByOperationCodeOrderByVersionNoDesc(code);
        if (!versions.isEmpty()) {
            String fixed = versions.get(versions.size() - 1).getOperationName();
            if (!fixed.equals(name)) {
                throw new FciException(
                        ApiErrorCode.OPERATION_NAME_FIXED,
                        new Object[] {code, fixed},
                        List.of(),
                        null);
            }
        }
        chain(versions).requireFreeDate(request.effectiveFrom());
        circulars.requireActive(request.circularId());
        Long allowance = allowanceComponent(request, p.dusting());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                code,
                                ActionType.CREATE,
                                hqLocation(context),
                                "{\"operation\":\""
                                        + code
                                        + "\",\"from\":\""
                                        + request.effectiveFrom()
                                        + "\"}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        OperationMaster saved =
                operations.save(
                        OperationMaster.propose(
                                code,
                                new OperationMaster.Particulars(
                                        name,
                                        request.departmental(),
                                        request.dps(),
                                        request.nwnp(),
                                        request.bagBased(),
                                        p.normType(),
                                        p.link(),
                                        p.dusting(),
                                        allowance,
                                        request.presentButNoWork(),
                                        request.circularId()),
                                VersionChain.nextNumber(
                                        versions.stream()
                                                .map(OperationMaster::getVersionNo)
                                                .toList()),
                                request.effectiveFrom(),
                                item.id()));
        return view(saved, new VersionChain<>(List.of(saved), Map.of(item.id(), item.status())));
    }

    /** The checked enumerations of a proposal. */
    record Particulars(
            OperationMaster.NormType normType,
            OperationMaster.RateNormLink link,
            boolean dusting) {}

    /**
     * SRS-G-41 and the DDL checks: at least one labour payroll ({@code chk_operation_payroll}); a
     * rate or norm link of the DDL list (SRS: mandatory); a bag-based operation links a norm slab
     * (SRS-G-41 "a bag-based operation has a bag-weight band and slab link"); Dusting names its
     * allowance pay code ({@code chk_operation_dusting}) and is not Present But No Work ({@code
     * chk_operation_kind}).
     */
    static Particulars validate(ProposedOperation r) {
        OperationMaster.RateNormLink link =
                enumOf(OperationMaster.RateNormLink.class, r.rateNormLink());
        OperationMaster.NormType norm =
                r.normType() == null || r.normType().isBlank()
                        ? null
                        : enumOf(OperationMaster.NormType.class, r.normType());
        // OPEN-QUESTION: Q-04-3 - SRS-G-41 fills a bag-weight band "from the master", but
        // operation_master has no band column (DDL v4.0); the band and its slab tables come with
        // the
        // rule engine (module 06 / 20). Only the slab link is checked here.
        boolean ok =
                link != null
                        && (r.normType() == null || r.normType().isBlank() || norm != null)
                        && (r.departmental() || r.dps() || r.nwnp())
                        && (!r.bagBased() || link == OperationMaster.RateNormLink.NORM_SLAB)
                        && (!r.dusting() || r.allowancePayCode() != null)
                        && !(r.dusting() && r.presentButNoWork());
        if (!ok) {
            throw new FciException(ApiErrorCode.OPERATION_INVALID);
        }
        return new Particulars(norm, link, r.dusting());
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String code) {
        if (code == null) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(code)) {
                return e;
            }
        }
        return null;
    }

    /** The Dusting allowance's pay component, an active M-07 entry; null when not Dusting. */
    private Long allowanceComponent(ProposedOperation r, boolean dusting) {
        if (!dusting && r.allowancePayCode() == null) {
            return null;
        }
        PayComponent c =
                components
                        .findByLegacyPayCode(r.allowancePayCode())
                        .filter(x -> x.isActiveOn(settings.today()))
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                ApiErrorCode.MASTER_COMPONENT_INVALID,
                                                new Object[] {r.allowancePayCode()},
                                                List.of(),
                                                null));
        return c.getComponentId();
    }

    /**
     * SRS-G-41: end-dates the operation in force (it stays on saved work-slips and in history);
     * effective once approved.
     */
    @Transactional
    public OperationRow endDate(
            AccessContext context, String code, LocalDate lastDay, String reason, OtpProof otp) {
        if (lastDay == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<OperationMaster> versions = operations.findByOperationCodeOrderByVersionNoDesc(code);
        VersionChain<OperationMaster> chain = chain(versions);
        OperationMaster open =
                versions.stream()
                        .filter(o -> chain.isApproved(o) && o.getEffectiveTo() == null)
                        .findFirst()
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (lastDay.isBefore(open.getEffectiveFrom())) {
            throw new FciException(
                    ApiErrorCode.MASTER_DATE_NOT_AFTER,
                    new Object[] {open.getEffectiveFrom()},
                    List.of(),
                    null);
        }
        approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "EDIT",
                        END_DATE,
                        code,
                        ActionType.UPDATE,
                        hqLocation(context),
                        "{\"lastDay\":\"" + lastDay + "\"}",
                        lastDay,
                        reason),
                otp);
        return list(context).stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    /**
     * The checker's final decision. An approved version ends the one it replaces the day before and
     * is linked to its circular (SRS-G-38 where used); an approved end-dating closes the open
     * version on its last day. Audited in the decision's transaction.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved()) {
            return;
        }
        if (decision.concerns(ENTITY)) {
            operations
                    .findByApprovalId(decision.approvalId())
                    .ifPresent(o -> applyVersion(decision, o));
        } else if (decision.concerns(END_DATE)) {
            applyEndDate(decision);
        }
    }

    private void applyVersion(ApprovalDecided decision, OperationMaster proposed) {
        List<OperationMaster> closed =
                chain(
                                operations.findByOperationCodeOrderByVersionNoDesc(
                                        proposed.getOperationCode()))
                        .closeBefore(proposed, operations::save);
        operations.flush();
        circulars.link(
                decision.checkerLoginId(),
                proposed.getCircularOrderId(),
                Circulars.LinkType.MASTER_ENTRY,
                ENTITY,
                proposed.getOperationCode(),
                SCREEN);
        audit.applied(
                decision,
                ENTITY,
                proposed.getOperationCode(),
                SCREEN,
                proposed.getEffectiveFrom(),
                closed.isEmpty() ? null : MasterImages.operation(closed.get(0)),
                MasterImages.operation(proposed),
                closed.stream().map(OperationMaster::getEffectiveTo).toList(),
                decision.reason());
    }

    private void applyEndDate(ApprovalDecided decision) {
        LocalDate lastDay = decision.payload() == null ? null : lastDayOf(decision.payload());
        if (lastDay == null) {
            throw new IllegalStateException("unreadable end-date proposal");
        }
        List<OperationMaster> versions =
                operations.findByOperationCodeOrderByVersionNoDesc(decision.entityKey());
        VersionChain<OperationMaster> chain = chain(versions);
        versions.stream()
                .filter(o -> chain.isApproved(o) && o.getEffectiveTo() == null)
                .findFirst()
                .ifPresent(
                        open -> {
                            open.endBefore(lastDay.plusDays(1));
                            operations.save(open);
                            operations.flush();
                            audit.applied(
                                    decision,
                                    ENTITY,
                                    open.getOperationCode(),
                                    SCREEN,
                                    lastDay,
                                    null,
                                    Map.of(),
                                    List.of(lastDay),
                                    decision.reason());
                        });
    }

    /** The {@code lastDay} of the payload this service wrote (an ISO date, nothing else). */
    static LocalDate lastDayOf(String payload) {
        int at = payload.indexOf("\"lastDay\":\"");
        if (at < 0) {
            return null;
        }
        int start = at + "\"lastDay\":\"".length();
        int end = payload.indexOf('"', start);
        try {
            return end < 0 ? null : LocalDate.parse(payload.substring(start, end));
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private Long hqLocation(AccessContext context) {
        return policy.isAtHeadquarters(context) ? context.locationId() : null;
    }

    private VersionChain<OperationMaster> chain(List<OperationMaster> versions) {
        return new VersionChain<>(versions, statuses.of(VersionChain.approvalIds(versions)));
    }

    private OperationVersion view(OperationMaster o, VersionChain<OperationMaster> chain) {
        return view(
                o,
                chain,
                o.getCircularOrderId() == null
                        ? Map.of()
                        : circulars.numbers(List.of(o.getCircularOrderId())));
    }

    private OperationVersion view(
            OperationMaster o,
            VersionChain<OperationMaster> chain,
            Map<Long, String> circularNumbers) {
        var s = chain.statusOf(o);
        Integer payCode =
                o.getAllowanceComponentId() == null
                        ? null
                        : components
                                .findById(o.getAllowanceComponentId())
                                .map(PayComponent::getLegacyPayCode)
                                .orElse(null);
        return new OperationVersion(
                o.getOperationId(),
                o.getVersionNo(),
                o.getEffectiveFrom(),
                o.getEffectiveTo(),
                o.getOperationName(),
                o.isAppliesDeptLabour(),
                o.isAppliesDpsLabour(),
                o.isAppliesNwnpLabour(),
                o.isIsBagBased(),
                o.getNormType(),
                o.getRateNormLink(),
                o.isIsDusting(),
                payCode,
                o.isIsPresentButNoWork(),
                o.getCircularOrderId(),
                s == null ? "NONE" : s.name(),
                o.getApprovalId(),
                o.getCircularOrderId() == null
                        ? null
                        : circularNumbers.get(o.getCircularOrderId()));
    }
}
