package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.VersionChain;
import in.gov.fci.payroll.configuration.business.model.AllowanceCeilingNorm;
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
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-15 Allowance &amp; Reimbursement Ceiling Norms (SRS-16-14; DDL v4.0 {@code
 * allowance_ceiling_norm}): one effective-dated ceiling per claim type, never overlapping. Claim
 * type and eligibility category come from the M-16 lists CLAIM_TYPE and ELIGIBILITY_CATEGORY, which
 * FCI fills (none seeded); periodicity from the DDL list. The HQ Rule Administrator proposes (route
 * M15_CREATE), a Payroll Checker approves. The claim engine (I-03, module 10) pays the lesser of
 * claimed and ceiling and must refuse an unconfirmed norm (DDL: "an unseeded ceiling silently
 * over-pays"). Each version is linked to its Active M-25 circular (SRS-G-38, US-GAP-211).
 */
@Service
@Loggable
public class CeilingNormService {

    static final String SCREEN = "M-15";
    public static final String ENTITY = "allowance_ceiling_norm";
    static final String CLAIM_TYPE = "CLAIM_TYPE";
    static final String ELIGIBILITY = "ELIGIBILITY_CATEGORY";
    static final String BASIS = "CEILING_BASIS";

    /** SRS l.1421: Periodicity is sourced from M-16; the value must also pass the DDL CHECK. */
    static final String PERIODICITY_LIST = "ALLOWANCE_PERIODICITY";

    /** {@code periodicity} (DDL CHECK). */
    // OPEN-QUESTION: Q-04-9 - the SRS M-15 field table sources periodicity from M-16 with three
    // values; the DDL CHECK allows these five. Kept to the CHECK until FCI decides.
    static final Set<String> PERIODICITY =
            Set.of("MONTHLY", "QUARTERLY", "ANNUAL", "BLOCK_YEAR_4", "ONE_TIME_PER_EVENT");

    /** {@code member_scope} (DDL CHECK). */
    static final Set<String> MEMBER_SCOPE = Set.of("EMPLOYEE", "LABOUR", "BOTH");

    public record NormVersion(
            Long id,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String ceilingBasis,
            BigDecimal ceilingValue,
            String periodicity,
            String eligibilityCategory,
            String memberScope,
            boolean confirmed,
            String status,
            Long approvalId,
            Long circularOrderId) {}

    public record NormRow(
            String claimType, String name, NormVersion current, NormVersion pending) {}

    /** The drawn tiles of M-15 (Wireframes v6.1; API-04-15). */
    public record NormCounts(long claimTypes, long percentageRules, long pendingApproval) {}

    public record ProposedNorm(
            LocalDate effectiveFrom,
            String ceilingBasis,
            BigDecimal ceilingValue,
            String periodicity,
            String eligibilityCategory,
            String memberScope,
            boolean confirmed,
            String reason,
            Long circularOrderId) {}

    private final ConfigurationRepositories.CeilingNorms norms;
    private final ReferenceData reference;
    private final ApprovalStatuses statuses;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;
    private final MasterAudit audit;
    private final Circulars circulars;
    private final ConfigurationRepositories.CircularLinks links;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other master services
    public CeilingNormService(
            ConfigurationRepositories.CeilingNorms norms,
            ReferenceData reference,
            ApprovalStatuses statuses,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            MasterAudit audit,
            Circulars circulars,
            ConfigurationRepositories.CircularLinks links) {
        this.norms = norms;
        this.reference = reference;
        this.statuses = statuses;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
        this.audit = audit;
        this.circulars = circulars;
        this.links = links;
    }

    /**
     * The tiles: the claim types with a norm, those whose ceiling in force is on a percentage basis
     * (a CEILING_BASIS code naming a percentage, as the drawing tests it) and those with a version
     * waiting for the Checker.
     */
    @Transactional(readOnly = true)
    public NormCounts counts(AccessContext context) {
        List<NormRow> rows = list(context);
        long percentage =
                rows.stream()
                        .filter(
                                r ->
                                        r.current() != null
                                                && r.current().ceilingBasis() != null
                                                && r.current()
                                                        .ceilingBasis()
                                                        .toUpperCase(Locale.ROOT)
                                                        .contains("PERCENT"))
                        .count();
        long pending = rows.stream().filter(r -> r.pending() != null).count();
        long configured = rows.stream().filter(r -> r.current() != null).count();
        return new NormCounts(configured, percentage, pending);
    }

    /**
     * SRS-16-14: one row per claim type of the M-16 list, plus any claim type that has norms but
     * has since left the list (its history stays visible).
     */
    @Transactional(readOnly = true)
    public List<NormRow> list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Map<String, String> names = new LinkedHashMap<>();
        reference
                .list(CLAIM_TYPE)
                .ifPresent(l -> l.values().forEach(v -> names.put(v.code(), v.label())));
        Map<String, List<AllowanceCeilingNorm>> byType = new LinkedHashMap<>();
        for (AllowanceCeilingNorm n : norms.findAllByOrderByClaimTypeAscVersionNoDesc()) {
            byType.computeIfAbsent(n.getClaimType(), k -> new ArrayList<>()).add(n);
            names.putIfAbsent(n.getClaimType(), n.getClaimType());
        }
        LocalDate today = settings.today();
        Map<String, Long> circularOf = circulars();
        List<NormRow> rows = new ArrayList<>();
        names.forEach(
                (code, name) -> {
                    VersionChain<AllowanceCeilingNorm> chain =
                            chain(byType.getOrDefault(code, List.of()));
                    rows.add(
                            new NormRow(
                                    code,
                                    name,
                                    chain.inForceOn(today)
                                            .map(n -> view(n, chain, circularOf))
                                            .orElse(null),
                                    chain.pending()
                                            .map(n -> view(n, chain, circularOf))
                                            .orElse(null)));
                });
        return rows;
    }

    @Transactional(readOnly = true)
    public List<NormVersion> history(AccessContext context, String claimType) {
        policy.require(context, SCREEN, "VIEW");
        List<AllowanceCeilingNorm> versions = norms.findByClaimTypeOrderByVersionNoDesc(claimType);
        VersionChain<AllowanceCeilingNorm> chain = chain(versions);
        Map<String, Long> circularOf = circulars();
        return versions.stream().map(n -> view(n, chain, circularOf)).toList();
    }

    /** SRS-16-14: proposes a ceiling version for a claim type. */
    @Transactional
    public NormVersion propose(
            AccessContext context, String claimType, ProposedNorm request, OtpProof otp) {
        if (request == null || request.effectiveFrom() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        requireValue(CLAIM_TYPE, claimType);
        validate(request);
        requireValue(PERIODICITY_LIST, request.periodicity());
        requireValue(ELIGIBILITY, request.eligibilityCategory());
        // OPEN-QUESTION: Q-04-4 - SRS-16-14 resolves the ceiling basis from the rule engine
        // (module 06, not built); until then it is optional and, when given, from CEILING_BASIS.
        if (blank(request.ceilingBasis()) != null) {
            requireValue(BASIS, request.ceilingBasis());
        }
        List<AllowanceCeilingNorm> versions = norms.findByClaimTypeOrderByVersionNoDesc(claimType);
        chain(versions).requireFreeDate(request.effectiveFrom());
        // SRS-G-38 (US-GAP-211): a norm revision is linked to its Active M-25 circular.
        circulars.requireActive(request.circularOrderId());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                claimType,
                                ActionType.CREATE,
                                policy.isAtHeadquarters(context) ? context.locationId() : null,
                                "{\"claimType\":\""
                                        + claimType
                                        + "\",\"from\":\""
                                        + request.effectiveFrom()
                                        + "\"}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        AllowanceCeilingNorm saved =
                norms.save(
                        AllowanceCeilingNorm.propose(
                                claimType,
                                blank(request.ceilingBasis()),
                                request.ceilingValue(),
                                request.periodicity(),
                                request.eligibilityCategory(),
                                blank(request.memberScope()) == null
                                        ? "BOTH"
                                        : request.memberScope(),
                                VersionChain.nextNumber(
                                        versions.stream()
                                                .map(AllowanceCeilingNorm::getVersionNo)
                                                .toList()),
                                request.effectiveFrom(),
                                request.confirmed(),
                                item.id()));
        circulars.link(
                context.loginId(),
                request.circularOrderId(),
                Circulars.LinkType.MASTER_ENTRY,
                ENTITY,
                String.valueOf(saved.getCeilingNormId()),
                SCREEN);
        return view(
                saved,
                new VersionChain<>(List.of(saved), Map.of(item.id(), item.status())),
                Map.of(String.valueOf(saved.getCeilingNormId()), request.circularOrderId()));
    }

    /**
     * SRS-16-14: a ceiling above zero (12,2), a periodicity of the DDL list and, if given, a member
     * scope of the DDL list.
     */
    static void validate(ProposedNorm p) {
        BigDecimal value = p.ceilingValue();
        boolean ok =
                value != null
                        && value.signum() > 0
                        && value.scale() <= 2
                        && value.precision() - value.scale() <= 10
                        && p.periodicity() != null
                        && PERIODICITY.contains(p.periodicity())
                        && (blank(p.memberScope()) == null
                                || MEMBER_SCOPE.contains(p.memberScope()));
        if (!ok) {
            throw new FciException(ApiErrorCode.MASTER_INVALID_CEILING);
        }
    }

    private void requireValue(String list, String code) {
        if (!reference.contains(list, code)) {
            throw new FciException(
                    ApiErrorCode.MASTER_UNKNOWN_VALUE,
                    new Object[] {list, code == null ? "" : code},
                    List.of(),
                    null);
        }
    }

    /** An approved norm ends the version it replaces the day before (SRS-16-14: no overlap). */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)) {
            return;
        }
        if (!decision.approved()) {
            // Wave D review 6 (SRS-G-38): a norm that never took effect keeps no Active M-25
            // "where used" link.
            norms.findByApprovalId(decision.approvalId())
                    .ifPresent(
                            proposed ->
                                    circulars.cancelLinks(
                                            decision.checkerLoginId(),
                                            ENTITY,
                                            String.valueOf(proposed.getCeilingNormId()),
                                            SCREEN,
                                            "Norm version "
                                                    + decision.outcome()
                                                            .name()
                                                            .toLowerCase(java.util.Locale.ROOT)));
            return;
        }
        norms.findByApprovalId(decision.approvalId())
                .ifPresent(
                        proposed -> {
                            List<AllowanceCeilingNorm> closed =
                                    chain(
                                                    norms.findByClaimTypeOrderByVersionNoDesc(
                                                            proposed.getClaimType()))
                                            .closeBefore(proposed, norms::save);
                            norms.flush();
                            audit.applied(
                                    decision,
                                    ENTITY,
                                    proposed.getClaimType(),
                                    SCREEN,
                                    proposed.getEffectiveFrom(),
                                    closed.isEmpty()
                                            ? null
                                            : MasterImages.ceilingNorm(closed.get(0)),
                                    MasterImages.ceilingNorm(proposed),
                                    closed.stream()
                                            .map(AllowanceCeilingNorm::getEffectiveTo)
                                            .toList(),
                                    decision.reason());
                        });
    }

    private VersionChain<AllowanceCeilingNorm> chain(List<AllowanceCeilingNorm> versions) {
        return new VersionChain<>(versions, statuses.of(VersionChain.approvalIds(versions)));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** US-GAP-211: the circular each norm version is linked to, by norm id (active links). */
    private Map<String, Long> circulars() {
        Map<String, Long> out = new HashMap<>();
        links.findByLinkedEntity(ENTITY).stream()
                .filter(l -> "ACTIVE".equals(l.getLinkStatus()))
                .forEach(l -> out.put(l.getLinkedKey(), l.getCircularOrderId()));
        return out;
    }

    private static NormVersion view(
            AllowanceCeilingNorm n,
            VersionChain<AllowanceCeilingNorm> chain,
            Map<String, Long> circularOf) {
        var s = chain.statusOf(n);
        return new NormVersion(
                n.getCeilingNormId(),
                n.getVersionNo(),
                n.getEffectiveFrom(),
                n.getEffectiveTo(),
                n.getCeilingBasis(),
                n.getCeilingValue(),
                n.getPeriodicity(),
                n.getEligibilityCategory(),
                n.getMemberScope(),
                n.isIsConfirmed(),
                s == null ? "NONE" : s.name(),
                n.getApprovalId(),
                circularOf.get(String.valueOf(n.getCeilingNormId())));
    }
}
