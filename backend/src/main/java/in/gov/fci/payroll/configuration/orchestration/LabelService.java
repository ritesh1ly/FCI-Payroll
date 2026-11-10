package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-26 Label Master - Hindi, English &amp; Local Names (SRS-G-39, SRS-G-40, SRS-16-24; DDL v4.0
 * {@code label_master}): every label by key, language and scope. Headquarters maintains the Global
 * labels (route M26_CREATE: System Administrator, Payroll Checker); a Region or Office user
 * proposes a local name for a master value marked localisable and the Regional Office checker
 * approves it (M26_LOCAL); Headquarters withdraws a local name (M26_EDIT), after which the standard
 * name shows again. A label is shown in the SRS-G-39 order (user's language and office, region,
 * global; then English global, {@link #resolve}). Calculations and stored data always use the code,
 * never the label.
 */
@Service
@Loggable
public class LabelService {

    static final String SCREEN = "M-26";
    public static final String ENTITY = "label_master";

    /** The approval entity of a withdrawal (its key is the label id). */
    public static final String WITHDRAWAL = "label_withdrawal";

    static final String LANGUAGES = "LANGUAGE";
    static final String ENGLISH = "EN";
    static final String HINDI = "HI";
    static final int MAX_TEXT = 500;
    // OPEN-QUESTION: CLR-139 - SRS-G-39 states no row limit for a bulk upload; 500 is as built.
    static final int MAX_ROWS = 500;

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.-]{1,60}$");

    public record LabelView(
            Long id,
            String key,
            String type,
            String masterEntity,
            String masterCode,
            String language,
            String scope,
            Long scopeLocationId,
            String scopeOffice,
            String text,
            boolean localisable,
            int versionNo,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            Long approvalId,
            String changedBy) {}

    public record ProposedLabel(
            String key,
            String type,
            String masterEntity,
            String masterCode,
            String language,
            String scope,
            String text,
            boolean localisable,
            LocalDate effectiveFrom,
            String reason) {}

    public record LabelQuery(String text, String language, String scope, String status) {}

    /** The label shown for a key (SRS-G-39 order), and which version it came from. */
    public record Resolved(String key, String text, String language, String scope, Long labelId) {}

    private final ConfigurationRepositories.Labels labels;
    private final ReferenceData reference;
    private final OrgDirectory org;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;
    private final AuditService audit;
    private final Clock clock;

    public LabelService(
            ConfigurationRepositories.Labels labels,
            ReferenceData reference,
            OrgDirectory org,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            AuditService audit,
            Clock clock) {
        this.labels = labels;
        this.reference = reference;
        this.org = org;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * SRS-G-39: search a label by key or text ("let search find a record by any of its labels").
     */
    @Transactional(readOnly = true)
    public List<LabelView> search(AccessContext context, LabelQuery query) {
        policy.require(context, SCREEN, "VIEW");
        LabelQuery q = query == null ? new LabelQuery(null, null, null, null) : query;
        String text =
                q.text() == null || q.text().isBlank()
                        ? null
                        : "%" + Likes.escape(q.text().strip().toLowerCase(Locale.ROOT)) + "%";
        return labels
                .search(
                        text,
                        blank(q.language()),
                        blank(q.scope()),
                        blank(q.status()),
                        PageRequest.of(0, MAX_ROWS))
                .stream()
                .map(this::view)
                .toList();
    }

    /** SRS M-26 "Missing translations": Active English Global labels without Active Hindi text. */
    @Transactional(readOnly = true)
    public List<LabelView> missingHindi(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return labels.missingHindi(PageRequest.of(0, MAX_ROWS)).stream().map(this::view).toList();
    }

    /** SRS-G-39: the label shown for {@code key} in {@code language} at the user's office. */
    @Transactional(readOnly = true)
    public Resolved resolve(AccessContext context, String key, String language) {
        policy.require(context, SCREEN, "VIEW");
        return resolve(
                        labels.findByLabelKeyOrderByVersionNoDesc(key),
                        language == null ? ENGLISH : language,
                        context.locationId(),
                        region(context.locationId()).orElse(null),
                        settings.today())
                .map(
                        l ->
                                new Resolved(
                                        key,
                                        l.getLabelText(),
                                        l.getLanguageCode(),
                                        l.getScopeLevel(),
                                        l.getLabelId()))
                .orElse(new Resolved(key, null, null, null, null));
    }

    /**
     * SRS-G-39 order: user's language and office; user's language and region; user's language,
     * Global; English, Global. Only Active labels in force on {@code day}.
     */
    public static Optional<LabelMaster> resolve(
            List<LabelMaster> versions, String language, Long office, Long region, LocalDate day) {
        List<LabelMaster> live =
                versions.stream()
                        .filter(l -> l.status() == LabelMaster.Status.ACTIVE && l.coversDay(day))
                        .toList();
        return pick(live, language, LabelMaster.Scope.OFFICE, office)
                .or(() -> pick(live, language, LabelMaster.Scope.REGION, region))
                .or(() -> pick(live, language, LabelMaster.Scope.GLOBAL, null))
                .or(() -> pick(live, ENGLISH, LabelMaster.Scope.GLOBAL, null));
    }

    private static Optional<LabelMaster> pick(
            List<LabelMaster> live, String language, LabelMaster.Scope scope, Long location) {
        if (scope != LabelMaster.Scope.GLOBAL && location == null) {
            return Optional.empty();
        }
        return live.stream()
                .filter(
                        l ->
                                l.getLanguageCode().equals(language)
                                        && l.getScopeLevel().equals(scope.name())
                                        && Objects.equals(l.getScopeLocationId(), location))
                .findFirst();
    }

    /**
     * SRS-G-39: proposes a label version. Checks the key, type and text; the language from the M-16
     * list LANGUAGE; a master-value label names its master and code ({@code
     * chk_label_master_value}); a Region / Office label only for a master value whose Global label
     * is localisable, at the proposer's own region or office ({@code chk_label_master_local});
     * unique for key, language and scope on a date, and after the version in force.
     */
    @Transactional
    public LabelView propose(AccessContext context, ProposedLabel request, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        Checked c = validate(request);
        if (!reference.contains(LANGUAGES, c.language())) {
            throw new FciException(
                    ApiErrorCode.MASTER_UNKNOWN_VALUE,
                    new Object[] {LANGUAGES, c.language()},
                    List.of(),
                    null);
        }
        LocalDate today = settings.today();
        List<LabelMaster> versions = labels.findByLabelKeyOrderByVersionNoDesc(c.key());
        Long location = null;
        if (c.scope() != LabelMaster.Scope.GLOBAL) {
            location = localScope(context, c.scope());
            boolean localisable =
                    versions.stream()
                            .anyMatch(
                                    l ->
                                            l.status() == LabelMaster.Status.ACTIVE
                                                    && l.coversDay(today)
                                                    && l.getScopeLevel().equals("GLOBAL")
                                                    && l.isIsLocalisable()
                                                    && "MASTER_VALUE".equals(l.getLabelType()));
            if (!localisable) {
                throw new FciException(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
            }
        }
        Long scopeLocation = location;
        List<LabelMaster> same =
                versions.stream()
                        .filter(
                                l ->
                                        l.getLanguageCode().equals(c.language())
                                                && l.getScopeLevel().equals(c.scope().name())
                                                && Objects.equals(
                                                        l.getScopeLocationId(), scopeLocation))
                        .toList();
        for (LabelMaster l : same) {
            boolean live =
                    l.status() == LabelMaster.Status.ACTIVE
                            || l.status() == LabelMaster.Status.PENDING_APPROVAL;
            if (live && !c.from().isAfter(l.getEffectiveFrom())) {
                throw new FciException(
                        ApiErrorCode.LABEL_TAKEN,
                        new Object[] {c.key(), c.language(), l.getEffectiveFrom()},
                        List.of(),
                        null);
            }
        }
        boolean global = c.scope() == LabelMaster.Scope.GLOBAL;
        LabelMaster saved =
                labels.save(
                        LabelMaster.propose(
                                new LabelMaster.Particulars(
                                        c.key(),
                                        c.type(),
                                        c.masterEntity(),
                                        c.masterCode(),
                                        c.language(),
                                        c.scope(),
                                        scopeLocation,
                                        c.text(),
                                        // chk_label_master_local: a local name is a row of a
                                        // localisable master value.
                                        !global || c.localisable(),
                                        c.from()),
                                versions.stream()
                                                .mapToInt(LabelMaster::getVersionNo)
                                                .max()
                                                .orElse(0)
                                        + 1,
                                context.loginId(),
                                request.reason(),
                                clock.instant()));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                global ? "M26_CREATE" : "M26_LOCAL",
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                pendingKey(c.key(), c.language(), c.scope().name(), scopeLocation),
                                ActionType.CREATE,
                                global
                                        ? (policy.isAtHeadquarters(context)
                                                ? context.locationId()
                                                : null)
                                        : context.locationId(),
                                null,
                                c.from(),
                                request.reason()),
                        otp);
        saved.submitted(item.id());
        labels.save(saved);
        return view(saved);
    }

    /** The approval key of a label slot: one pending change per key, language and scope. */
    static String pendingKey(String key, String language, String scope, Long location) {
        return key + "|" + language + "|" + scope + (location == null ? "" : "|" + location);
    }

    /** A proposal that passed the checks of {@link #validate}. */
    record Checked(
            String key,
            LabelMaster.Type type,
            String masterEntity,
            String masterCode,
            String language,
            LabelMaster.Scope scope,
            String text,
            boolean localisable,
            LocalDate from) {}

    /** SRS-G-39 field checks (no database). */
    static Checked validate(ProposedLabel r) {
        if (r == null || r.effectiveFrom() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        LabelMaster.Type type = enumOf(LabelMaster.Type.class, r.type());
        LabelMaster.Scope scope =
                r.scope() == null || r.scope().isBlank()
                        ? LabelMaster.Scope.GLOBAL
                        : enumOf(LabelMaster.Scope.class, r.scope());
        String text = r.text() == null ? "" : r.text().strip();
        boolean masterValue = type == LabelMaster.Type.MASTER_VALUE;
        boolean ok =
                r.key() != null
                        && KEY.matcher(r.key()).matches()
                        && type != null
                        && scope != null
                        && r.language() != null
                        && CODE.matcher(r.language()).matches()
                        && !text.isEmpty()
                        && text.length() <= MAX_TEXT
                        && text.codePoints().noneMatch(Character::isISOControl)
                        && (!masterValue
                                || (r.masterEntity() != null
                                        && CODE.matcher(r.masterEntity()).matches()
                                        && r.masterCode() != null
                                        && CODE.matcher(r.masterCode()).matches()));
        if (!ok) {
            throw new FciException(ApiErrorCode.LABEL_INVALID);
        }
        if (scope != LabelMaster.Scope.GLOBAL && !masterValue) {
            throw new FciException(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
        }
        return new Checked(
                r.key(),
                type,
                masterValue ? r.masterEntity() : null,
                masterValue ? r.masterCode() : null,
                r.language(),
                scope,
                text,
                r.localisable() && masterValue,
                r.effectiveFrom());
    }

    /** A local name belongs to the proposer's own region (REGION) or office (OFFICE). */
    private Long localScope(AccessContext context, LabelMaster.Scope scope) {
        if (policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
        }
        Long location =
                scope == LabelMaster.Scope.REGION
                        ? region(context.locationId()).orElse(null)
                        : context.locationId();
        if (location == null) {
            throw new FciException(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
        }
        return location;
    }

    private Optional<Long> region(Long office) {
        return org.ancestorOrSelf(office, LocationType.RO).map(OrgLocation::getId);
    }

    /** SRS M-26: Headquarters withdraws a local name; the standard name shows again. */
    @Transactional
    public LabelView withdraw(AccessContext context, Long id, String reason, OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        LabelMaster label =
                labels.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (label.status() != LabelMaster.Status.ACTIVE) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        if ("GLOBAL".equals(label.getScopeLevel())) {
            throw new FciException(ApiErrorCode.LABEL_SCOPE_NOT_ALLOWED);
        }
        approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "EDIT",
                        WITHDRAWAL,
                        String.valueOf(id),
                        ActionType.UPDATE,
                        policy.isAtHeadquarters(context) ? context.locationId() : null,
                        null,
                        null,
                        reason),
                otp);
        return view(label);
    }

    /** SRS-G-39 bulk download by language and scope (Active and pending labels). */
    public long exportSize(AccessContext context, String language, String scope) {
        return exportRows(context, language, scope).size();
    }

    public void export(AccessContext context, String language, String scope, ExportSink sink)
            throws IOException {
        List<LabelView> rows = exportRows(context, language, scope);
        sink.header(
                "label_key",
                "label_type",
                "master_entity",
                "master_code",
                "language",
                "scope",
                "scope_office",
                "label_text",
                "localisable",
                "effective_from",
                "effective_to",
                "status");
        for (LabelView l : rows) {
            sink.row(
                    l.key(),
                    l.type(),
                    l.masterEntity(),
                    l.masterCode(),
                    l.language(),
                    l.scope(),
                    l.scopeOffice(),
                    l.text(),
                    l.localisable() ? "Y" : "N",
                    l.effectiveFrom(),
                    l.effectiveTo(),
                    l.status());
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    private List<LabelView> exportRows(AccessContext context, String language, String scope) {
        policy.require(context, SCREEN, "EXPORT");
        return search(context, new LabelQuery(null, language, scope, null)).stream()
                .filter(l -> !"REJECTED".equals(l.status()))
                .toList();
    }

    /**
     * SRS M-26 bulk upload: each row is checked and submitted like a single change, all or none (a
     * row that fails stops the upload and nothing is submitted).
     *
     * @return the labels submitted
     */
    @Transactional
    public List<LabelView> upload(
            AccessContext context, List<ProposedLabel> rows, String reason, OtpProof otp) {
        // SRS M-26: "Bulk upload (Administrator)"; each row is still a proposal (CREATE).
        policy.require(context, SCREEN, "EDIT");
        policy.require(context, SCREEN, "CREATE");
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        Set<String> slots = new HashSet<>();
        for (ProposedLabel r : rows) {
            Checked c = validate(r);
            if (!slots.add(c.key() + "|" + c.language() + "|" + c.scope())) {
                throw new FciException(
                        ApiErrorCode.LABEL_TAKEN,
                        new Object[] {c.key(), c.language(), c.from()},
                        List.of(),
                        null);
            }
        }
        List<LabelView> out = new ArrayList<>();
        for (ProposedLabel r : rows) {
            out.add(
                    propose(
                            context,
                            new ProposedLabel(
                                    r.key(),
                                    r.type(),
                                    r.masterEntity(),
                                    r.masterCode(),
                                    r.language(),
                                    r.scope(),
                                    r.text(),
                                    r.localisable(),
                                    r.effectiveFrom(),
                                    reason),
                            otp));
        }
        return out;
    }

    /**
     * The checker's final decision. An approved version becomes ACTIVE and supersedes the version
     * in force for the same key, language and scope the day before it starts; a rejected or
     * returned one is REJECTED. An approved withdrawal end-dates the label today. A label of a
     * reference value refreshes the reference cache. Audited in the decision's transaction.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(ENTITY)) {
            labels.findByApprovalId(decision.approvalId()).ifPresent(l -> apply(decision, l));
        } else if (decision.concerns(WITHDRAWAL) && decision.approved()) {
            labels.findById(Long.valueOf(decision.entityKey()))
                    .filter(l -> l.status() == LabelMaster.Status.ACTIVE)
                    .ifPresent(
                            l -> {
                                LocalDate today = settings.today();
                                LocalDate lastDay =
                                        l.getEffectiveFrom().isAfter(today.minusDays(1))
                                                ? l.getEffectiveFrom()
                                                : today.minusDays(1);
                                change(
                                        decision,
                                        l,
                                        () ->
                                                l.withdraw(
                                                        lastDay,
                                                        decision.checkerLoginId(),
                                                        reasonOf(decision),
                                                        clock.instant()));
                                refresh(decision, l);
                            });
        }
    }

    private void apply(ApprovalDecided decision, LabelMaster proposed) {
        if (decision.approved()) {
            for (LabelMaster previous :
                    labels.findByLabelKeyOrderByVersionNoDesc(proposed.getLabelKey())) {
                if (!previous.getLabelId().equals(proposed.getLabelId())
                        && previous.status() == LabelMaster.Status.ACTIVE
                        && previous.getLanguageCode().equals(proposed.getLanguageCode())
                        && previous.getScopeLevel().equals(proposed.getScopeLevel())
                        && Objects.equals(
                                previous.getScopeLocationId(), proposed.getScopeLocationId())
                        && previous.getEffectiveFrom().isBefore(proposed.getEffectiveFrom())) {
                    change(
                            decision,
                            previous,
                            () ->
                                    previous.supersede(
                                            proposed.getEffectiveFrom(),
                                            decision.checkerLoginId(),
                                            clock.instant()));
                }
            }
            labels.flush();
        }
        change(
                decision,
                proposed,
                () ->
                        proposed.decided(
                                decision.approved(), decision.checkerLoginId(), clock.instant()));
        refresh(decision, proposed);
    }

    private void refresh(ApprovalDecided decision, LabelMaster label) {
        if (ReferenceData.LABEL_ENTITY.equals(label.getMasterEntity())) {
            reference.changed(decision.checkerLoginId());
        }
    }

    private void change(ApprovalDecided decision, LabelMaster label, Runnable transition) {
        Map<String, String> before = MasterImages.label(label);
        transition.run();
        labels.save(label);
        audit.recordAll(
                decision.checkerLoginId(),
                FieldChanges.between(
                                ENTITY,
                                String.valueOf(label.getLabelId()),
                                before,
                                MasterImages.label(label),
                                reasonOf(decision))
                        .stream()
                        .map(
                                e ->
                                        e.approval(decision.approvalId())
                                                .module(SCREEN)
                                                .effective(label.getEffectiveFrom()))
                        .toList());
    }

    private static String reasonOf(ApprovalDecided decision) {
        return decision.reason() == null || decision.reason().isBlank()
                ? "Approved"
                : decision.reason();
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

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private LabelView view(LabelMaster l) {
        return new LabelView(
                l.getLabelId(),
                l.getLabelKey(),
                l.getLabelType(),
                l.getMasterEntity(),
                l.getMasterCode(),
                l.getLanguageCode(),
                l.getScopeLevel(),
                l.getScopeLocationId(),
                org.location(l.getScopeLocationId()).map(OrgLocation::displayLabel).orElse(null),
                l.getLabelText(),
                l.isIsLocalisable(),
                l.getVersionNo(),
                l.getEffectiveFrom(),
                l.getEffectiveTo(),
                l.getLabelStatus(),
                l.getApprovalId(),
                l.getChangedBy());
    }
}
