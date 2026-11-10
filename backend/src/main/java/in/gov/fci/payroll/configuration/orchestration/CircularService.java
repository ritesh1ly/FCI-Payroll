package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.MasterText;
import in.gov.fci.payroll.configuration.business.PayrollType;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.business.model.CircularOrderLink;
import in.gov.fci.payroll.configuration.business.model.CircularOrderScope;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-25 Circular / Order Master (SRS-G-38; DDL v4.0 {@code circular_order}, {@code
 * circular_order_scope}, {@code circular_order_link}): a general order is recorded once with its
 * copy (PDF) and linked wherever it is used. Creating, superseding and withdrawing go through
 * maker-checker at the maker's own office (routes M25_CREATE, M25_EDIT); approval of a superseding
 * circular sets the one it replaces to SUPERSEDED in the same transaction; a withdrawn circular
 * takes no new link and its existing links stay. Every user with access views and searches
 * circulars (they are general orders, not personal data). The circular number is unique across FCI
 * (DDL {@code uk_circular_order_number}; stricter than SRS "per issuing authority", KB CF-20).
 */
@Service
@Loggable
public class CircularService implements Circulars {

    static final String SCREEN = "M-25";
    public static final String ENTITY = "circular_order";

    /** The approval entity of a withdrawal (its key is the circular id). */
    public static final String WITHDRAWAL = "circular_withdrawal";

    static final String AUTHORITY = "ISSUING_AUTHORITY";
    static final String CATEGORY = "CIRCULAR_CATEGORY";
    static final String DOCUMENT_TYPE = "CIRCULAR";

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    public record ScopeView(String scopeType, String payrollType, Long locationId, String office) {}

    public record CircularView(
            Long id,
            String number,
            LocalDate date,
            String issuingAuthority,
            Long issuingLocationId,
            String subject,
            String category,
            boolean appliesAllOffices,
            List<String> payrollTypes,
            List<ScopeView> offices,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long supersedesId,
            String supersedesNumber,
            Long documentId,
            String status,
            String statusReason,
            Long approvalId,
            String createdBy,
            Instant createdAt,
            Instant changedAt,
            int whereUsed) {}

    /** What the maker enters (SRS-G-38 fields). */
    public record NewCircular(
            String number,
            LocalDate date,
            String issuingAuthority,
            String subject,
            String category,
            List<String> payrollTypes,
            boolean appliesAllOffices,
            List<Long> officeIds,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long supersedesId,
            String reason) {}

    /** The order copy, as uploaded. */
    public record OrderCopy(String fileName, byte[] content) {}

    /** One item a circular drives (SRS-G-38 "Where used"). */
    public record UseView(
            String kind,
            String entity,
            String key,
            String screen,
            String status,
            Instant linkedAt) {}

    public record SearchQuery(
            String text, String category, String status, LocalDate from, LocalDate to) {}

    private final ConfigurationRepositories.Circulars circulars;
    private final ConfigurationRepositories.CircularScopes scopes;
    private final ConfigurationRepositories.CircularLinks links;
    private final ConfigurationRepositories.Operations operations;
    private final ConfigurationRepositories.RateSets rateSets;
    private final ReferenceData reference;
    private final OrgDirectory org;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final SystemParameters settings;
    private final AuditService audit;
    private final Clock clock;

    public CircularService(
            ConfigurationRepositories.Circulars circulars,
            ConfigurationRepositories.CircularScopes scopes,
            ConfigurationRepositories.CircularLinks links,
            ConfigurationRepositories.Operations operations,
            ConfigurationRepositories.RateSets rateSets,
            ReferenceData reference,
            OrgDirectory org,
            DocumentStore documents,
            MakerChecker approvals,
            Authorization policy,
            SystemParameters settings,
            AuditService audit,
            Clock clock) {
        this.circulars = circulars;
        this.scopes = scopes;
        this.links = links;
        this.operations = operations;
        this.rateSets = rateSets;
        this.reference = reference;
        this.org = org;
        this.documents = documents;
        this.approvals = approvals;
        this.policy = policy;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** An office a circular can be scoped to (SRS-G-38 "Applicable Region / Office"). */
    public record OfficeOption(Long id, String code, String name, String type) {}

    /** The offices in force today, for the maker's "applicable offices" choice. */
    @Transactional(readOnly = true)
    public List<OfficeOption> offices(AccessContext context) {
        policy.require(context, SCREEN, "CREATE");
        LocalDate today = settings.today();
        return org.locations(org.all()).stream()
                .filter(l -> l.isActiveOn(today))
                .sorted(java.util.Comparator.comparing(OrgLocation::getCode))
                .map(l -> new OfficeOption(l.getId(), l.getCode(), l.getName(), l.getType().name()))
                .toList();
    }

    /** SRS-G-38: search by number or subject text, category, status and date range. */
    @Transactional(readOnly = true)
    public List<CircularView> search(AccessContext context, SearchQuery query) {
        policy.require(context, SCREEN, "VIEW");
        SearchQuery q = query == null ? new SearchQuery(null, null, null, null, null) : query;
        String text =
                q.text() == null || q.text().isBlank()
                        ? null
                        : "%" + Likes.escape(q.text().strip().toLowerCase(Locale.ROOT)) + "%";
        return views(
                circulars.search(text, blank(q.category()), blank(q.status()), q.from(), q.to()));
    }

    /** SRS M-25 action Export: how many rows the export of this search writes. */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, SearchQuery query) {
        policy.require(context, SCREEN, "EXPORT");
        return search(context, query).size();
    }

    /**
     * SRS M-25 action Export: the register as filtered, with the code and the label of each coded
     * value (SRS-G-39 "exports carry both code and label"); the status is the DDL code.
     */
    @Transactional(readOnly = true)
    public void export(AccessContext context, SearchQuery query, ExportSink sink)
            throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        List<CircularView> rows = search(context, query);
        sink.header(
                "circular_number",
                "circular_date",
                "issuing_authority",
                "issuing_authority_label",
                "subject",
                "category",
                "category_label",
                "payroll_types",
                "offices",
                "effective_from",
                "effective_to",
                "supersedes",
                "document",
                "where_used",
                "status");
        for (CircularView c : rows) {
            sink.row(
                    c.number(),
                    c.date(),
                    c.issuingAuthority(),
                    labelOf(AUTHORITY, c.issuingAuthority()),
                    c.subject(),
                    c.category(),
                    labelOf(CATEGORY, c.category()),
                    String.join(" ", c.payrollTypes()),
                    c.appliesAllOffices()
                            ? "ALL"
                            : c.offices().stream()
                                    .map(CircularService::office)
                                    .collect(Collectors.joining("; ")),
                    c.effectiveFrom(),
                    c.effectiveTo(),
                    c.supersedesNumber(),
                    c.documentId() == null ? "N" : "Y",
                    c.whereUsed(),
                    c.status());
        }
        ExportSink.requireComplete(rows.size(), sink);
    }

    /** The English label of a reference value, for an export beside its code (SRS-G-39). */
    private String labelOf(String list, String code) {
        return reference
                .list(list)
                .flatMap(l -> l.values().stream().filter(v -> v.code().equals(code)).findFirst())
                .map(ReferenceData.Value::label)
                .orElse(null);
    }

    /** The office name of a scope row, or its id where the location is unknown. */
    private static String office(ScopeView scope) {
        return scope.office() == null ? String.valueOf(scope.locationId()) : scope.office();
    }

    @Transactional(readOnly = true)
    public CircularView get(AccessContext context, Long id) {
        policy.require(context, SCREEN, "VIEW");
        return views(List.of(find(id))).get(0);
    }

    /**
     * SRS-G-38 home page: the Active circulars in force today that apply to the user's office (all
     * offices, or an office on the path from the user's office up to Headquarters, with its
     * descendants).
     */
    @Transactional(readOnly = true)
    public List<CircularView> current(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = settings.today();
        Set<Long> path = path(context.locationId());
        List<CircularView> active =
                search(context, new SearchQuery(null, null, "ACTIVE", null, null)).stream()
                        .filter(c -> inForce(c, today))
                        .toList();
        Map<Long, List<CircularOrderScope>> offices =
                active.isEmpty()
                        ? Map.of()
                        : scopes
                                .findByCircularOrderIdIn(
                                        active.stream().map(CircularView::id).toList())
                                .stream()
                                .filter(s -> "LOCATION".equals(s.getScopeType()))
                                .collect(
                                        Collectors.groupingBy(
                                                CircularOrderScope::getCircularOrderId));
        return active.stream()
                .filter(
                        c ->
                                c.appliesAllOffices()
                                        || applies(
                                                offices.getOrDefault(c.id(), List.of()),
                                                context.locationId(),
                                                path))
                .toList();
    }

    /** In force on {@code day}: started (or no start) and not ended. */
    static boolean inForce(CircularView c, LocalDate day) {
        return (c.effectiveFrom() == null || !day.isBefore(c.effectiveFrom()))
                && (c.effectiveTo() == null || !day.isAfter(c.effectiveTo()));
    }

    /**
     * Whether an office scope covers the user's office: the office itself, or an office above it
     * that includes its descendants.
     */
    static boolean applies(List<CircularOrderScope> offices, Long office, Set<Long> path) {
        return offices.stream()
                .anyMatch(
                        s ->
                                Objects.equals(s.getLocationId(), office)
                                        || (s.isIncludeDescendants()
                                                && path.contains(s.getLocationId())));
    }

    /** The user's office and every office above it. */
    private Set<Long> path(Long office) {
        Set<Long> path = new LinkedHashSet<>();
        Long current = office;
        while (current != null && path.add(current)) {
            current = org.location(current).map(OrgLocation::getParentId).orElse(null);
        }
        return path;
    }

    /**
     * SRS-G-38: records a new (or superseding) circular with its order copy and submits it. Checks:
     * mandatory fields, an issuing authority and category of their M-16 lists, at least one payroll
     * type, all offices or at least one known office, a PDF copy, a number not yet recorded and,
     * when superseding, an Active circular to replace.
     */
    @Transactional
    public CircularView record(
            AccessContext context, NewCircular request, OrderCopy copy, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        if (request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String number = MasterText.required("m25.number", request.number(), 60);
        String subject = MasterText.requiredText("m25.subject", request.subject(), 500);
        requireValue(AUTHORITY, request.issuingAuthority());
        requireValue(CATEGORY, request.category());
        List<String> types = payrollTypes(request.payrollTypes());
        List<Long> offices = offices(request);
        if (request.date() == null
                || request.effectiveFrom() == null
                || (request.effectiveTo() != null
                        && request.effectiveTo().isBefore(request.effectiveFrom()))) {
            throw new FciException(ApiErrorCode.CIRCULAR_INVALID);
        }
        if (copy == null || copy.content() == null || !isPdf(copy.content())) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(context.loginId(), ENTITY, number, SCREEN, "order copy (PDF)");
            throw new FciException(ApiErrorCode.CIRCULAR_DOCUMENT_REQUIRED);
        }
        if (circulars.findByCircularNumber(number).isPresent()) {
            throw new FciException(
                    ApiErrorCode.CIRCULAR_NUMBER_TAKEN, new Object[] {number}, List.of(), null);
        }
        if (request.supersedesId() != null) {
            requireActive(request.supersedesId());
        }
        Instant now = clock.instant();
        CircularOrder circular =
                circulars.save(
                        CircularOrder.record(
                                new CircularOrder.Particulars(
                                        number,
                                        request.date(),
                                        request.issuingAuthority(),
                                        context.locationId(),
                                        subject,
                                        request.category(),
                                        request.appliesAllOffices(),
                                        request.effectiveFrom(),
                                        request.effectiveTo(),
                                        request.supersedesId()),
                                context.loginId(),
                                now));
        Long id = circular.getCircularOrderId();
        types.forEach(t -> scopes.save(CircularOrderScope.payrollType(id, t, null)));
        offices.forEach(o -> scopes.save(CircularOrderScope.location(id, o, true)));
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                String.valueOf(id),
                                DOCUMENT_TYPE,
                                number,
                                request.date(),
                                request.issuingAuthority(),
                                copy.fileName(),
                                copy.content(),
                                false));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                String.valueOf(id),
                                ActionType.CREATE,
                                context.locationId(),
                                "{\"circular\":\""
                                        + number.replace("\\", "\\\\").replace("\"", "\\\"")
                                        + "\",\"supersedes\":"
                                        + request.supersedesId()
                                        + "}",
                                request.effectiveFrom(),
                                request.reason()),
                        otp);
        circular.submitted(item.id(), attached.id());
        circulars.save(circular);
        return views(List.of(circular)).get(0);
    }

    /**
     * SRS-G-38 withdraw: the maker withdraws an Active circular with a reason; the checker
     * approves.
     */
    @Transactional
    public CircularView withdraw(AccessContext context, Long id, String reason, OtpProof otp) {
        policy.require(context, SCREEN, "EDIT");
        CircularOrder circular = find(id);
        requireActive(circular);
        approvals.submit(
                context,
                new Submission(
                        SCREEN,
                        "EDIT",
                        WITHDRAWAL,
                        String.valueOf(id),
                        ActionType.UPDATE,
                        context.locationId(),
                        null,
                        null,
                        reason),
                otp);
        return views(List.of(circular)).get(0);
    }

    /** SRS-G-38 "Where used": every linked rule, master entry and transaction. */
    @Transactional(readOnly = true)
    public List<UseView> whereUsed(AccessContext context, Long id) {
        policy.require(context, SCREEN, "VIEW");
        find(id);
        List<UseView> uses = new ArrayList<>();
        links.findByCircularOrderIdOrderByLinkedAtDesc(id)
                .forEach(
                        l ->
                                uses.add(
                                        new UseView(
                                                l.getLinkType(),
                                                l.getLinkedEntity(),
                                                l.getLinkedKey(),
                                                l.getScreenCode(),
                                                l.getLinkStatus(),
                                                l.getLinkedAt())));
        Set<String> linked =
                uses.stream().map(u -> u.entity() + "|" + u.key()).collect(Collectors.toSet());
        operations
                .findByCircularOrderIdOrderByOperationCodeAscVersionNoDesc(id)
                .forEach(
                        o -> {
                            if (!linked.contains(
                                    OperationService.ENTITY + "|" + o.getOperationCode())) {
                                uses.add(
                                        new UseView(
                                                Circulars.LinkType.MASTER_ENTRY.name(),
                                                OperationService.ENTITY,
                                                o.getOperationCode(),
                                                OperationService.SCREEN,
                                                "ACTIVE",
                                                null));
                            }
                        });
        rateSets.findByCircularOrderIdOrderByRateSetCodeAscVersionNoDesc(id)
                .forEach(
                        r ->
                                uses.add(
                                        new UseView(
                                                Circulars.LinkType.MASTER_ENTRY.name(),
                                                "rate_set",
                                                r.getRateSetCode() + " v" + r.getVersionNo(),
                                                null,
                                                r.getApprovalState(),
                                                null)));
        return uses;
    }

    /**
     * SRS M-25 field Where Used (a count and a list): how many items {@link #whereUsed} lists for
     * each circular, read in one go for a whole list (no query per row).
     */
    private Map<Long, Integer> useCounts(List<Long> ids) {
        Map<Long, Integer> counts = new HashMap<>();
        Set<String> linked = new java.util.HashSet<>();
        links.findByCircularOrderIdIn(ids)
                .forEach(
                        l -> {
                            counts.merge(l.getCircularOrderId(), 1, Integer::sum);
                            linked.add(
                                    l.getCircularOrderId()
                                            + "|"
                                            + l.getLinkedEntity()
                                            + "|"
                                            + l.getLinkedKey());
                        });
        operations
                .findByCircularOrderIdIn(ids)
                .forEach(
                        o -> {
                            String key =
                                    o.getCircularOrderId()
                                            + "|"
                                            + OperationService.ENTITY
                                            + "|"
                                            + o.getOperationCode();
                            if (!linked.contains(key)) {
                                counts.merge(o.getCircularOrderId(), 1, Integer::sum);
                            }
                        });
        rateSets.findByCircularOrderIdIn(ids)
                .forEach(r -> counts.merge(r.getCircularOrderId(), 1, Integer::sum));
        return counts;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> numbers(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> numbers = new HashMap<>();
        circulars
                .findByCircularOrderIdIn(ids)
                .forEach(o -> numbers.put(o.getCircularOrderId(), o.getCircularNumber()));
        return numbers;
    }

    /** The order copy of a circular (the download is logged by the document store). */
    @Transactional
    public DocumentStore.Content document(AccessContext context, Long id, String sourceIp) {
        policy.require(context, SCREEN, "VIEW");
        CircularOrder circular = find(id);
        if (circular.getDocumentAttachmentId() == null) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return documents.read(
                context.loginId(),
                sourceIp,
                ENTITY,
                String.valueOf(id),
                circular.getDocumentAttachmentId());
    }

    @Override
    @Transactional(readOnly = true)
    public void requireActive(Long circularId) {
        if (circularId == null) {
            // Wave D review 6: a missing circular is a required field, not a circular "null".
            throw new FciException(ApiErrorCode.CIRCULAR_REQUIRED);
        }
        CircularOrder circular = circulars.findById(circularId).orElse(null);
        if (circular == null) {
            throw new FciException(
                    ApiErrorCode.CIRCULAR_NOT_ACTIVE,
                    new Object[] {String.valueOf(circularId), "UNKNOWN"},
                    List.of(),
                    null);
        }
        requireActive(circular);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireActive(Long circularId, String category) {
        requireActive(circularId);
        CircularOrder circular = circulars.findById(circularId).orElseThrow();
        if (!category.equals(circular.getCategoryCode())) {
            throw new FciException(
                    ApiErrorCode.CIRCULAR_WRONG_CATEGORY,
                    new Object[] {
                        circular.getCircularNumber(),
                        String.valueOf(circular.getCategoryCode()),
                        category
                    },
                    List.of(),
                    null);
        }
    }

    private static void requireActive(CircularOrder circular) {
        if (!circular.isActive()) {
            throw new FciException(
                    ApiErrorCode.CIRCULAR_NOT_ACTIVE,
                    new Object[] {circular.getCircularNumber(), circular.getCircularStatus()},
                    List.of(),
                    null);
        }
    }

    @Override
    @Transactional
    public void link(
            String actor,
            Long circularId,
            LinkType type,
            String entity,
            String key,
            String screen) {
        requireActive(circularId);
        links.save(
                CircularOrderLink.link(
                        circularId,
                        CircularOrderLink.Type.valueOf(type.name()),
                        entity,
                        key,
                        screen,
                        actor,
                        clock.instant()));
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(circularId),
                                "link",
                                null,
                                entity + ":" + key,
                                "Linked on " + screen)
                        .module(screen));
    }

    @Override
    @Transactional
    public int cancelLinks(String actor, String entity, String key, String screen, String reason) {
        int cancelled = 0;
        for (CircularOrderLink l :
                links.findByLinkedEntityAndLinkedKeyOrderByCircularLinkId(entity, key)) {
            if (!"ACTIVE".equals(l.getLinkStatus())) {
                continue;
            }
            l.cancel(actor, reason, clock.instant());
            links.save(l);
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    ENTITY,
                                    String.valueOf(l.getCircularOrderId()),
                                    "link",
                                    entity + ":" + key,
                                    null,
                                    reason)
                            .module(screen));
            cancelled++;
        }
        return cancelled;
    }

    /**
     * The checker's final decision. A circular's own approval makes it ACTIVE (and the one it
     * supersedes SUPERSEDED, if still Active) or REJECTED; an approved withdrawal makes it
     * WITHDRAWN. Audited in the same transaction.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(ENTITY)) {
            circulars
                    .findByApprovalId(decision.approvalId())
                    .ifPresent(c -> applyCircular(decision, c));
        } else if (decision.concerns(WITHDRAWAL) && decision.approved()) {
            circulars
                    .findById(Long.valueOf(decision.entityKey()))
                    .filter(CircularOrder::isActive)
                    .ifPresent(
                            c ->
                                    change(
                                            decision,
                                            c,
                                            () ->
                                                    c.withdraw(
                                                            reasonOf(decision),
                                                            decision.checkerLoginId(),
                                                            clock.instant())));
        }
    }

    private void applyCircular(ApprovalDecided decision, CircularOrder circular) {
        change(
                decision,
                circular,
                () ->
                        circular.decided(
                                decision.approved(),
                                decision.reason(),
                                decision.checkerLoginId(),
                                clock.instant()));
        if (decision.approved() && circular.getSupersedesCircularId() != null) {
            circulars
                    .findById(circular.getSupersedesCircularId())
                    .filter(CircularOrder::isActive)
                    .ifPresent(
                            old ->
                                    change(
                                            decision,
                                            old,
                                            () ->
                                                    old.supersede(
                                                            decision.checkerLoginId(),
                                                            clock.instant())));
        }
    }

    private void change(ApprovalDecided decision, CircularOrder circular, Runnable transition) {
        Map<String, String> before = MasterImages.circular(circular);
        transition.run();
        circulars.save(circular);
        audit.recordAll(
                decision.checkerLoginId(),
                FieldChanges.between(
                                ENTITY,
                                String.valueOf(circular.getCircularOrderId()),
                                before,
                                MasterImages.circular(circular),
                                reasonOf(decision))
                        .stream()
                        .map(e -> e.approval(decision.approvalId()).module(SCREEN))
                        .toList());
    }

    private static String reasonOf(ApprovalDecided decision) {
        return decision.reason() == null || decision.reason().isBlank()
                ? "Approved"
                : decision.reason();
    }

    private CircularOrder find(Long id) {
        return Optional.ofNullable(id)
                .flatMap(circulars::findById)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
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

    private static List<String> payrollTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new FciException(ApiErrorCode.CIRCULAR_INVALID);
        }
        Set<String> types = new LinkedHashSet<>();
        for (String code : requested) {
            PayrollType type = PayrollType.of(code);
            if (type == null) {
                throw new FciException(ApiErrorCode.CIRCULAR_INVALID);
            }
            types.add(type.name());
        }
        return List.copyOf(types);
    }

    private List<Long> offices(NewCircular request) {
        List<Long> ids = request.officeIds() == null ? List.of() : request.officeIds();
        if (request.appliesAllOffices() != ids.isEmpty()) {
            throw new FciException(ApiErrorCode.CIRCULAR_INVALID);
        }
        Set<Long> offices = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || org.location(id).isEmpty()) {
                throw new FciException(ApiErrorCode.CIRCULAR_INVALID);
            }
            offices.add(id);
        }
        return List.copyOf(offices);
    }

    /** The content is a PDF (its signature), whatever the file is called (SRS-G-38: PDF). */
    static boolean isPdf(byte[] content) {
        return content.length > PDF_MAGIC.length
                && Arrays.equals(Arrays.copyOf(content, PDF_MAGIC.length), PDF_MAGIC);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<CircularView> views(List<CircularOrder> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> numbers = new java.util.HashMap<>();
        List<Long> superseded =
                rows.stream()
                        .map(CircularOrder::getSupersedesCircularId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (!superseded.isEmpty()) {
            circulars
                    .findByCircularOrderIdIn(superseded)
                    .forEach(o -> numbers.put(o.getCircularOrderId(), o.getCircularNumber()));
        }
        List<Long> ids = rows.stream().map(CircularOrder::getCircularOrderId).toList();
        Map<Long, List<CircularOrderScope>> scopeOf =
                scopes.findByCircularOrderIdIn(ids).stream()
                        .collect(Collectors.groupingBy(CircularOrderScope::getCircularOrderId));
        Map<Long, Integer> used = useCounts(ids);
        return rows.stream()
                .map(
                        c -> {
                            List<CircularOrderScope> own =
                                    scopeOf.getOrDefault(c.getCircularOrderId(), List.of());
                            String supersedes =
                                    c.getSupersedesCircularId() == null
                                            ? null
                                            : numbers.get(c.getSupersedesCircularId());
                            return new CircularView(
                                    c.getCircularOrderId(),
                                    c.getCircularNumber(),
                                    c.getCircularDate(),
                                    c.getIssuingAuthorityCode(),
                                    c.getIssuingLocationId(),
                                    c.getSubject(),
                                    c.getCategoryCode(),
                                    c.isAppliesAllOffices(),
                                    own.stream()
                                            .filter(s -> "PAYROLL_TYPE".equals(s.getScopeType()))
                                            .map(CircularOrderScope::getPayrollType)
                                            .toList(),
                                    own.stream()
                                            .filter(s -> "LOCATION".equals(s.getScopeType()))
                                            .map(
                                                    s ->
                                                            new ScopeView(
                                                                    s.getScopeType(),
                                                                    null,
                                                                    s.getLocationId(),
                                                                    org.location(s.getLocationId())
                                                                            .map(
                                                                                    OrgLocation
                                                                                            ::displayLabel)
                                                                            .orElse(null)))
                                            .toList(),
                                    c.getEffectiveFrom(),
                                    c.getEffectiveTo(),
                                    c.getSupersedesCircularId(),
                                    supersedes,
                                    c.getDocumentAttachmentId(),
                                    c.getCircularStatus(),
                                    c.getStatusReason(),
                                    c.getApprovalId(),
                                    c.getCreatedBy(),
                                    c.getCreatedAt(),
                                    c.getChangedAt(),
                                    used.getOrDefault(c.getCircularOrderId(), 0));
                        })
                .toList();
    }
}
