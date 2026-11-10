package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-05 Exceptions &amp; Holds Worklist (SRS-G-9; SRS v0.5 use case "Work the Cross-Module
 * Exceptions Worklist"; DDL {@code payroll_exception}).
 *
 * <p>Two faces:
 *
 * <ul>
 *   <li>{@link ExceptionWorklist} for the finding modules: raise (deduplicated) and clear with a
 *       recorded cause;
 *   <li>the screen: the worklist of the session's data scope with its four tiles, assign an owner
 *       (X-05 EDIT, Payroll Checker) and accept a non-blocking gate-G2 item with a reason (X-05
 *       APPROVE, Payroll Checker), CSV export (X-05 EXPORT).
 * </ul>
 *
 * Every change of an entry is audited in the same transaction; nothing is ever deleted.
 */
@Service
@Loggable
public class ExceptionService implements ExceptionWorklist {

    public static final String SCREEN = "X-05";
    static final String ENTITY = "payroll_exception";

    /**
     * One worklist row. {@code ageDays} is how long it has been on the list ("visible with its
     * age").
     */
    public record ExceptionView(
            Long id,
            Long cycleInstanceId,
            String eis,
            String memberName,
            String moduleCode,
            String exceptionCode,
            String severity,
            String blocksGate,
            String entityName,
            String fieldName,
            String fieldSource,
            boolean resolvableInPayroll,
            String correctionScreen,
            String ownerRoleCode,
            String detail,
            String status,
            String acceptedBy,
            String acceptedReason,
            Instant raisedAt,
            long ageDays,
            Instant resolvedAt) {}

    /**
     * The four tiles of X-05: open exceptions; items blocking the lock at G2 ("must clear or be
     * accepted"); accepted with reason; upstream (HRMS / EBS, "cannot be fixed in Payroll").
     */
    public record Tiles(long open, long blockingG2, long acceptedWithReason, long upstream) {}

    /**
     * The worklist's tiles (over the whole filtered scope) and one page of its rows (the shared
     * page shape: items, page, size, total; docs/plan/X-pagination.md).
     */
    public record Worklist(
            Tiles tiles, List<ExceptionView> items, int page, int size, long total) {}

    /**
     * The worklist filters (SRS l.604: engine, period, office scope, exception class; SRS-G-9).
     * Each is optional: {@code module} the exception class, {@code period} a payroll month {@code
     * YYYYMM} (the cycle's), {@code office} an office of the session's scope (it and the offices
     * below it), {@code code} one condition (the opened group), {@code cycle} one run (C-08). The
     * engine (payroll type) is not a filter: payroll_exception keeps none (CLR-72, PENDING F-05-3).
     */
    public record Filter(
            String module,
            ExceptionEntry.Status status,
            ExceptionEntry.Severity severity,
            Long cycle,
            String period,
            Long office,
            String code) {

        /** No filter at all. */
        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null);
        }
    }

    /**
     * One condition of the worklist (module + exception code) over the whole filtered scope (SRS
     * l.634 "Count - Members affected"): {@code items} entries about {@code members} distinct
     * members (cycle-level entries have none); {@code blocksG2} an unsettled blocking item holds
     * gate G2; {@code owner} the common owner, null when the items differ or none is set; {@code
     * actionable} an unsettled item is resolvable in Payroll.
     */
    public record GroupView(
            String moduleCode,
            String exceptionCode,
            long items,
            long members,
            boolean blocksG2,
            String owner,
            boolean resolvableInPayroll,
            boolean actionable,
            String correctionScreen,
            String detail,
            Instant firstRaisedAt) {}

    /** The worklist by condition: its tiles, one page of conditions, the classes in scope. */
    public record Groups(
            Tiles tiles,
            List<GroupView> items,
            int page,
            int size,
            long total,
            List<String> modules) {}

    /** An office the worklist may be filtered to (the session's data scope). */
    public record OfficeOption(Long id, String code, String name, String type) {}

    private final GeneralRepositories.Exceptions exceptions;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final Authorization policy;
    private final AuditService audit;
    private final Clock clock;

    private final ExportReader exportReader;

    public ExceptionService(
            ExportReader exportReader,
            GeneralRepositories.Exceptions exceptions,
            EmployeeDirectory employees,
            OrgDirectory org,
            Authorization policy,
            AuditService audit,
            Clock clock) {
        this.exportReader = exportReader;
        this.exceptions = exceptions;
        this.employees = employees;
        this.org = org;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- producers
    // (ExceptionWorklist)

    @Override
    @Transactional
    public Long raise(String actor, NewEntry n) {
        // The same condition, still unsettled, is not raised twice (a re-run finds it again).
        List<ExceptionEntry> existing =
                exceptions.findCondition(
                        n.exceptionCode(),
                        n.cycleInstanceId(),
                        n.employeeId(),
                        n.fieldName(),
                        Set.of(
                                ExceptionEntry.Status.OPEN,
                                ExceptionEntry.Status.IN_PROGRESS,
                                ExceptionEntry.Status.ROUTED_UPSTREAM));
        if (!existing.isEmpty()) {
            return existing.get(0).getId();
        }
        ExceptionEntry saved =
                exceptions.save(
                        ExceptionEntry.raise(
                                n.cycleInstanceId(),
                                n.employeeId(),
                                n.moduleCode(),
                                n.exceptionCode(),
                                ExceptionEntry.Severity.valueOf(n.severity()),
                                n.blocksGate(),
                                n.hrmsChangeTaskId(),
                                n.entityName(),
                                n.fieldName(),
                                n.fieldSource() == null
                                        ? null
                                        : ExceptionEntry.FieldSource.valueOf(n.fieldSource()),
                                n.correctionScreen(),
                                n.detail(),
                                clock.instant()));
        audited(
                actor,
                saved,
                "exception_status",
                null,
                saved.getStatus().name(),
                "Raised by " + n.moduleCode());
        return saved.getId();
    }

    @Override
    @Transactional
    public int clear(
            String actor,
            String exceptionCode,
            Long cycleInstanceId,
            Long employeeId,
            String fieldName,
            String cause) {
        if (cause == null || cause.isBlank()) {
            // "No exception is closed without a recorded cause."
            throw new IllegalArgumentException("A cause is required to clear an exception");
        }
        List<ExceptionEntry> open =
                exceptions.findCondition(
                        exceptionCode,
                        cycleInstanceId,
                        employeeId,
                        fieldName,
                        Set.of(
                                ExceptionEntry.Status.OPEN,
                                ExceptionEntry.Status.IN_PROGRESS,
                                ExceptionEntry.Status.ROUTED_UPSTREAM));
        for (ExceptionEntry e : open) {
            String before = e.getStatus().name();
            e.clear(clock.instant());
            exceptions.save(e);
            // Who cleared it and when are the audit row's actor and time; the cause is its reason.
            audited(actor, e, "exception_status", before, e.getStatus().name(), cause);
        }
        return open.size();
    }

    // ---------------------------------------------------------------- the screen

    /**
     * The worklist of the session's scope with optional filters (module, status, severity, cycle,
     * period, office, condition; C-08 = cycle + G2). Tiles count the same filtered scope.
     */
    @Transactional(readOnly = true)
    public Worklist worklist(AccessContext context, Filter filter, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = scope(context, filter);
        Page<ExceptionEntry> rows = find(scope, filter, spec.pageable());
        return new Worklist(
                tiles(scope, filter),
                views(rows.getContent(), clock.instant()),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements());
    }

    /**
     * SRS-G-9, SRS l.634: the worklist by condition, grouped and counted by the database over the
     * whole filtered scope (never over one page of rows), one page of conditions; the tiles of the
     * same filters and the exception classes present in the session's scope.
     */
    @Transactional(readOnly = true)
    public Groups groups(AccessContext context, Filter filter, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = scope(context, filter);
        Page<Object[]> rows =
                exceptions.groupsInScope(
                        scope,
                        blank(filter.module()),
                        filter.status(),
                        filter.severity(),
                        filter.cycle(),
                        blank(filter.period()),
                        blank(filter.code()),
                        ExceptionEntry.unsettledStatuses(),
                        ExceptionEntry.Severity.BLOCKING,
                        spec.pageable());
        return new Groups(
                tiles(scope, filter),
                rows.getContent().stream().map(ExceptionService::group).toList(),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements(),
                exceptions.modulesInScope(Scopes.of(context)));
    }

    /**
     * The items of one condition of a run, for C-08 ({@link ComputationExceptions}), which has
     * checked its own right and scope: the same view as the worklist, never a second store.
     */
    Paged<ExceptionView> items(Set<Long> scope, Filter filter, PageSpec spec) {
        Page<ExceptionEntry> rows = find(scope, filter, spec.pageable());
        return new Paged<>(
                views(rows.getContent(), clock.instant()),
                rows.getNumber(),
                rows.getSize(),
                rows.getTotalElements());
    }

    /** The offices of the session's data scope, by code (the worklist's office filter). */
    @Transactional(readOnly = true)
    public List<OfficeOption> offices(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return org.locations(Scopes.of(context)).stream()
                .sorted(Comparator.comparing(OrgLocation::getCode))
                .map(l -> new OfficeOption(l.getId(), l.getCode(), l.getName(), l.getType().name()))
                .toList();
    }

    /**
     * The offices a filtered read covers: the session's data scope, narrowed to an office of it and
     * the offices below it. An office outside the scope is "not found", as a record there.
     */
    private Set<Long> scope(AccessContext context, Filter filter) {
        Set<Long> scope = Scopes.of(context);
        if (filter.office() == null) {
            return scope;
        }
        policy.requireRecordAt(context, SCREEN, "VIEW", filter.office());
        Set<Long> narrowed = new LinkedHashSet<>(org.subtree(filter.office()));
        narrowed.retainAll(scope);
        return narrowed.isEmpty() ? Scopes.nothing() : narrowed;
    }

    private Page<ExceptionEntry> find(Set<Long> scope, Filter filter, Pageable page) {
        return exceptions.findInScope(
                scope,
                blank(filter.module()),
                filter.status(),
                filter.severity(),
                filter.cycle(),
                blank(filter.period()),
                blank(filter.code()),
                page);
    }

    private Tiles tiles(Set<Long> scope, Filter filter) {
        Object[] counts =
                exceptions
                        .tileCounts(
                                scope,
                                blank(filter.module()),
                                filter.status(),
                                filter.severity(),
                                filter.cycle(),
                                blank(filter.period()),
                                blank(filter.code()),
                                ExceptionEntry.unsettledStatuses(),
                                ExceptionEntry.Status.ACCEPTED)
                        .get(0);
        return new Tiles(
                number(counts[0]), number(counts[1]), number(counts[2]), number(counts[3]));
    }

    /** One grouped row as the worklist shows it (column order of {@code groupsInScope}). */
    private static GroupView group(Object[] r) {
        long items = number(r[2]);
        String low = (String) r[5];
        boolean oneOwner = low != null && low.equals(r[6]) && number(r[7]) == items;
        return new GroupView(
                (String) r[0],
                (String) r[1],
                items,
                number(r[3]),
                number(r[4]) > 0,
                oneOwner ? low : null,
                number(r[8]) > 0,
                number(r[9]) > 0,
                (String) r[10],
                (String) r[11],
                (Instant) r[12]);
    }

    /** A count or sum of the database (null for a sum over no rows). */
    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /**
     * The rows as the worklist shows them; the page's members in one lookup (deep profile
     * 2026-10-27: one lookup per member made a page of 100 cost 100 round trips).
     */
    private List<ExceptionView> views(List<ExceptionEntry> rows, Instant now) {
        Map<Long, EmployeeDirectory.Member> members =
                employees.members(
                        rows.stream()
                                .map(ExceptionEntry::getEmployeeId)
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList());
        return rows.stream().map(e -> view(e, members.get(e.getEmployeeId()), now)).toList();
    }

    /** Assigns an owner (SRS-G-9: "the Payroll Checker or the Administrator may assign"). */
    @Transactional
    public ExceptionView assign(AccessContext context, Long id, String owner) {
        ExceptionEntry e = authorised(context, id, "EDIT");
        String before = e.getOwnerRoleCode();
        String beforeStatus = e.getStatus().name();
        e.assign(owner);
        exceptions.save(e);
        audited(context.loginId(), e, "owner_role_code", before, owner, "Assigned");
        if (!beforeStatus.equals(e.getStatus().name())) {
            audited(
                    context.loginId(),
                    e,
                    "exception_status",
                    beforeStatus,
                    e.getStatus().name(),
                    "Assigned");
        }
        return view(e, employees.member(e.getEmployeeId()).orElse(null), clock.instant());
    }

    /**
     * Accepts a non-blocking gate-G2 exception with a reason instead of a correction (SRS-G-9). The
     * acceptance is audited and the item stays visible as "Accepted with Reason".
     */
    @Transactional
    public ExceptionView accept(AccessContext context, Long id, String reason) {
        ExceptionEntry e = authorised(context, id, "APPROVE");
        String before = e.getStatus().name();
        e.accept(context.loginId(), reason == null ? null : reason.strip());
        exceptions.save(e);
        audited(
                context.loginId(),
                e,
                "exception_status",
                before,
                e.getStatus().name(),
                e.getAcceptedReason());
        return view(e, employees.member(e.getEmployeeId()).orElse(null), clock.instant());
    }

    /** Rows ExceptionService#export would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, Filter filter) {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = scope(context, filter);
        return exportReader.count(page -> find(scope, filter, page));
    }

    /**
     * The filtered worklist as an export (SRS-G-13): every entry in scope, uncapped, oldest first
     * as on screen; never PAN or bank data (not on the row). Runs in one read-only snapshot
     * (SERIALIZABLE), batch by batch, and checks the rows written against the rows counted in that
     * snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, Filter filter, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = scope(context, filter);
        sink.header(
                "Id",
                "Module",
                "Exception",
                "Severity",
                "Blocks gate",
                "EIS",
                "Member",
                "Field",
                "Source",
                "Resolvable in Payroll",
                "Fix where",
                "Owner",
                "Status",
                "Raised at",
                "Age (days)",
                "Detail");
        long expected =
                exportReader.readAll(
                        page -> find(scope, filter, page),
                        rows -> {
                            for (ExceptionView v : views(rows, clock.instant())) {
                                sink.row(
                                        v.id(),
                                        v.moduleCode(),
                                        v.exceptionCode(),
                                        v.severity(),
                                        v.blocksGate(),
                                        v.eis(),
                                        v.memberName(),
                                        v.fieldName(),
                                        v.fieldSource(),
                                        v.resolvableInPayroll() ? "Y" : "N",
                                        v.correctionScreen(),
                                        v.ownerRoleCode(),
                                        v.status(),
                                        v.raisedAt(),
                                        v.ageDays(),
                                        v.detail());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    // ---------------------------------------------------------------- internals

    /** The entry, if the session's role and scope cover it (the member's or the cycle's unit). */
    private ExceptionEntry authorised(AccessContext context, Long id, String action) {
        ExceptionEntry e =
                exceptions
                        .findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Set<Long> scope = Scopes.of(context);
        // Out of scope is "not found": the worklist query decides visibility and the same rule
        // applies here.
        if (exceptions.countVisible(id, scope) == 0) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        Long location =
                e.getEmployeeId() == null
                        ? null
                        : employees.locationOf(e.getEmployeeId()).orElse(null);
        if (location != null) {
            policy.requireRecordAt(context, SCREEN, action, location);
        } else {
            policy.require(context, SCREEN, action);
        }
        return e;
    }

    private void audited(
            String actor,
            ExceptionEntry e,
            String field,
            String before,
            String after,
            String reason) {
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTITY, String.valueOf(e.getId()), field, before, after, reason)
                        .module(SCREEN));
    }

    private static ExceptionView view(ExceptionEntry e, EmployeeDirectory.Member m, Instant now) {
        long age = Duration.between(e.getRaisedAt(), now).toDays();
        return new ExceptionView(
                e.getId(),
                e.getCycleInstanceId(),
                m == null ? null : m.eis(),
                m == null ? null : m.name(),
                e.getModuleCode(),
                e.getExceptionCode(),
                e.getSeverity().name(),
                e.getBlocksGate(),
                e.getEntityName(),
                e.getFieldName(),
                e.getFieldSource() == null ? null : e.getFieldSource().name(),
                e.isResolvableInPayroll(),
                e.getCorrectionScreen(),
                e.getOwnerRoleCode(),
                e.getDetail(),
                e.getStatus().name(),
                e.getAcceptedBy(),
                e.getAcceptedReason(),
                e.getRaisedAt(),
                Math.max(0, age),
                e.getResolvedAt());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
