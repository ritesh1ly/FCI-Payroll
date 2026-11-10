package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * X-06 Payroll Query Management (SRS-G-11; SRS v0.5 use case "Raise and Resolve a Payroll Query").
 *
 * <p>Who may do what (SRS §14.4: "raise: emp, maker, ldh; resolve: checker"), each checked on the
 * member's record, so data scope always applies:
 *
 * <ul>
 *   <li>raise (X-06 CREATE): a member on their own record through eSeva; the Payroll Maker and the
 *       Labour Dealing Hand on records in their scope;
 *   <li>respond (X-06 EDIT): the Payroll Maker;
 *   <li>resolve (X-06 APPROVE): the Payroll Checker; for a query about a computed amount, a Checker
 *       other than the responder. A query not about a computed amount may also be resolved by the
 *       Maker who answered it (see the OPEN-QUESTION below).
 * </ul>
 *
 * Every raise, response, resolution and closure is written to the audit trail in the same
 * transaction ("Every query, each status change and the resolution are kept in the effective-dated
 * audit trail").
 */
@Service
@Loggable
public class PayrollQueryService {

    public static final String SCREEN = "X-06";
    static final String ENTITY = "payroll_query";
    private static final int MAX_TEXT = 4000;

    /** A query as the screen shows it. */
    public record QueryView(
            Long id,
            String reference,
            Long payrollId,
            String eis,
            String memberName,
            String payrollYymm,
            String channel,
            String queryText,
            boolean concernsComputedAmount,
            String status,
            String raisedBy,
            Instant raisedAt,
            String makerResponse,
            String respondedBy,
            Instant respondedAt,
            String resolutionText,
            String resolvedBy,
            Instant resolvedAt) {}

    private final GeneralRepositories.Queries queries;
    private final PayrollRecords payroll;
    private final EmployeeDirectory employees;
    private final Authorization policy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private final ExportReader exportReader;

    public PayrollQueryService(
            ExportReader exportReader,
            GeneralRepositories.Queries queries,
            PayrollRecords payroll,
            EmployeeDirectory employees,
            Authorization policy,
            AuditService audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.exportReader = exportReader;
        this.queries = queries;
        this.payroll = payroll;
        this.employees = employees;
        this.policy = policy;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Raises a query against a Payroll ID. The Payroll ID must be a result of a run of record
     * (ACTUAL); a dry-run result is not "a payroll record" the member was paid on.
     */
    @Transactional
    public QueryView raise(
            AccessContext context, Long payrollId, String text, boolean computedAmount) {
        PayrollRecords.Result result =
                payroll.result(payrollId)
                        .filter(r -> "ACTUAL".equals(r.runType()))
                        .orElseThrow(() -> new FciException(GeneralErrorCode.QUERY_PAYROLL_ID));
        EmployeeDirectory.Member member =
                employees
                        .member(result.employeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // eSeva: own record only; staff: the member's unit must be in the data scope.
        policy.requireEmployeeRecord(context, SCREEN, "CREATE", member.id(), member.locationId());
        PayrollQuery query =
                queries.save(
                        PayrollQuery.raise(
                                member.id(),
                                result.cycleInstanceId(),
                                result.payrollResultId(),
                                context.isEseva()
                                        ? PayrollQuery.Channel.ESEVA
                                        : PayrollQuery.Channel.STAFF,
                                Inputs.multilineText(text, MAX_TEXT, GeneralErrorCode.QUERY_TEXT),
                                computedAmount,
                                context.loginId(),
                                clock.instant()));
        audited(context, query, "query_status", null, query.getStatus().name(), "Query raised");
        if (context.isEseva()) {
            // SRS-G-11 / SRS-17-18 (plan 14 phase 14d, D14-7): the member's query in the eSeva log.
            events.publishEvent(
                    new SelfServiceEvents.Submitted(
                            member.id(),
                            SelfServiceEvents.Type.PAYROLL_QUERY,
                            ENTITY,
                            query.getId(),
                            null,
                            null,
                            null,
                            "Query on " + result.payrollYymm() + " (payroll " + payrollId + ")",
                            null,
                            context.loginId()));
        }
        return view(query);
    }

    /** The Payroll Maker records (or updates) the response; the query is IN_PROGRESS. */
    @Transactional
    public QueryView respond(
            AccessContext context, Long queryId, String response, boolean computedAmount) {
        PayrollQuery query = authorised(context, queryId, "EDIT");
        String before = query.getStatus().name();
        boolean computedBefore = query.concernsComputedAmount();
        String responseBefore = query.getMakerResponse();
        query.respond(
                Inputs.multilineText(response, MAX_TEXT, GeneralErrorCode.QUERY_TEXT),
                computedAmount,
                context.loginId(),
                clock.instant());
        queries.save(query);
        audited(
                context,
                query,
                "query_status",
                before,
                query.getStatus().name(),
                "Maker response recorded");
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        ENTITY, query.getId(), SelfServiceEvents.Status.ROUTED, context.loginId()));
        // SRS-20-2: a changed field is recorded with its old and new value, so a second response
        // keeps the first one and the computed-amount flag shows who set it.
        if (!Objects.equals(responseBefore, query.getMakerResponse())) {
            audited(
                    context,
                    query,
                    "maker_response",
                    responseBefore,
                    query.getMakerResponse(),
                    "Maker response recorded");
        }
        if (computedBefore != query.concernsComputedAmount()) {
            audited(
                    context,
                    query,
                    "concerns_computed_amount",
                    computedBefore ? "Y" : "N",
                    query.concernsComputedAmount() ? "Y" : "N",
                    "Marked as concerning a computed amount: the Payroll Checker confirms it");
        }
        return view(query);
    }

    /**
     * Resolves the query and the system closes it in the same act: "the query moves to Resolved.
     * The system closes the query once resolution is confirmed." Both transitions are audited.
     */
    @Transactional
    public QueryView resolve(AccessContext context, Long queryId, String resolution) {
        PayrollQuery query = load(queryId);
        // OPEN-QUESTION (tracker Q-X06-1): the SRS names only the Checker's confirmation of a
        // computed-amount query. For any other query the source does not say who confirms; the
        // Maker who answered it (X-06 EDIT) or a Checker (X-06 APPROVE) may resolve it here.
        boolean byChecker = can(context, query, "APPROVE");
        if (!byChecker && !can(context, query, "EDIT")) {
            authorise(
                    context, query,
                    "APPROVE"); // neither Checker nor Maker here: the precise denial
        }
        // A Maker in scope reaches the entity, which refuses a computed-amount query with the
        // rule's
        // own message (QRY-403-CHECKER) rather than a bare permission error.
        query.resolve(
                Inputs.multilineText(resolution, MAX_TEXT, GeneralErrorCode.QUERY_TEXT),
                context.loginId(),
                byChecker,
                clock.instant());
        audited(
                context,
                query,
                "query_status",
                PayrollQuery.Status.IN_PROGRESS.name(),
                PayrollQuery.Status.RESOLVED.name(),
                "Query resolved");
        query.close();
        queries.save(query);
        audited(
                context,
                query,
                "query_status",
                PayrollQuery.Status.RESOLVED.name(),
                PayrollQuery.Status.CLOSED.name(),
                "Closed by the system once resolution was confirmed");
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        ENTITY,
                        query.getId(),
                        SelfServiceEvents.Status.ACTIONED,
                        context.loginId()));
        return view(query);
    }

    /** One query, if the session may view its member's record. */
    @Transactional(readOnly = true)
    public QueryView get(AccessContext context, Long queryId) {
        return view(authorised(context, queryId, "VIEW"));
    }

    /** Rows PayrollQueryService#export would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, PayrollQuery.Status status) {
        if (context.isEseva()) {
            policy.requireEmployeeRecord(context, SCREEN, "VIEW", context.employeeId(), null);
            return own(context, status).size();
        }
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        return exportReader.count(page -> queries.findInScope(scope, status, page));
    }

    /**
     * The query list as an export (SRS-G-13): every query the session may view, uncapped, newest
     * first. Runs in one read-only snapshot (SERIALIZABLE), batch by batch, and checks the rows
     * written against the rows counted in that snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, PayrollQuery.Status status, ExportSink sink)
            throws IOException {
        if (context.isEseva()) {
            // An eSeva member: the own queries only (a short list, read at once).
            policy.requireEmployeeRecord(context, SCREEN, "VIEW", context.employeeId(), null);
            List<QueryView> own = own(context, status);
            sink.header(
                    "Query reference",
                    "Payroll ID",
                    "EIS",
                    "Member",
                    "Period",
                    "Channel",
                    "Computed amount",
                    "Status",
                    "Raised by",
                    "Raised at",
                    "Query",
                    "Maker response",
                    "Resolution");
            for (QueryView q : own) {
                sink.row(
                        q.reference(),
                        q.payrollId(),
                        q.eis(),
                        q.memberName(),
                        q.payrollYymm(),
                        q.channel(),
                        q.concernsComputedAmount() ? "Y" : "N",
                        q.status(),
                        q.raisedBy(),
                        q.raisedAt(),
                        q.queryText(),
                        q.makerResponse(),
                        q.resolutionText());
            }
            ExportSink.requireComplete(own.size(), sink);
            return;
        }
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        sink.header(
                "Query reference",
                "Payroll ID",
                "EIS",
                "Member",
                "Period",
                "Channel",
                "Computed amount",
                "Status",
                "Raised by",
                "Raised at",
                "Query",
                "Maker response",
                "Resolution");
        long expected =
                exportReader.readAll(
                        page -> queries.findInScope(scope, status, page),
                        rows -> {
                            for (PayrollQuery row : rows) {
                                QueryView q = view(row);
                                sink.row(
                                        q.reference(),
                                        q.payrollId(),
                                        q.eis(),
                                        q.memberName(),
                                        q.payrollYymm(),
                                        q.channel(),
                                        q.concernsComputedAmount() ? "Y" : "N",
                                        q.status(),
                                        q.raisedBy(),
                                        q.raisedAt(),
                                        q.queryText(),
                                        q.makerResponse(),
                                        q.resolutionText());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    @Transactional(readOnly = true)
    public Paged<QueryView> list(AccessContext context, PayrollQuery.Status status, PageSpec spec) {
        if (context.isEseva()) {
            // A member's own queries: a short list, paged after the status filter.
            policy.requireEmployeeRecord(context, SCREEN, "VIEW", context.employeeId(), null);
            return Paged.slice(own(context, status), spec);
        }
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        return Paged.of(queries.findInScope(scope, status, spec.pageable())).map(this::view);
    }

    /**
     * X-06 tiles: the queries the session may view, counted by status over its whole scope (an
     * eSeva member: the own queries).
     */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(AccessContext context) {
        Map<String, Long> out = new java.util.TreeMap<>();
        for (PayrollQuery.Status s : PayrollQuery.Status.values()) {
            out.put(s.name(), 0L);
        }
        if (context.isEseva()) {
            policy.requireEmployeeRecord(context, SCREEN, "VIEW", context.employeeId(), null);
            own(context, null).forEach(q -> out.merge(q.status(), 1L, Long::sum));
            return out;
        }
        policy.require(context, SCREEN, "VIEW");
        for (Object[] row : queries.countByStatus(Scopes.of(context))) {
            out.put(((PayrollQuery.Status) row[0]).name(), ((Number) row[1]).longValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- internals

    /** An eSeva member's own queries, newest first, of one status or all (a short list). */
    private List<QueryView> own(AccessContext context, PayrollQuery.Status status) {
        return queries.findByEmployeeIdOrderByRaisedAtDesc(context.employeeId()).stream()
                .filter(q -> status == null || q.getStatus() == status)
                .map(this::view)
                .toList();
    }

    private PayrollQuery load(Long queryId) {
        return queries.findById(queryId)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private PayrollQuery authorised(AccessContext context, Long queryId, String action) {
        PayrollQuery query = load(queryId);
        authorise(context, query, action);
        return query;
    }

    private void authorise(AccessContext context, PayrollQuery query, String action) {
        policy.requireEmployeeRecord(
                context, SCREEN, action, query.getEmployeeId(), locationOf(query));
    }

    /**
     * A probe without side effects: {@code canAt} answers without recording a denial, whereas a
     * caught {@code require...} would write an out-of-scope security event for a mere check.
     */
    private boolean can(AccessContext context, PayrollQuery query, String action) {
        return !context.isEseva() && policy.canAt(context, SCREEN, action, locationOf(query));
    }

    private Long locationOf(PayrollQuery query) {
        return employees.locationOf(query.getEmployeeId()).orElse(null);
    }

    private void audited(
            AccessContext context,
            PayrollQuery query,
            String field,
            String before,
            String after,
            String reason) {
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY, String.valueOf(query.getId()), field, before, after, reason)
                        .module(SCREEN));
    }

    private QueryView view(PayrollQuery q) {
        EmployeeDirectory.Member member = employees.member(q.getEmployeeId()).orElse(null);
        // DDL payroll_query.payroll_result_ref is nullable (a query migrated or raised without a
        // Payroll ID): shown without one, never a failed list.
        Long resultId =
                q.getPayrollResultRef() == null ? null : Long.valueOf(q.getPayrollResultRef());
        String yymm =
                resultId == null
                        ? null
                        : payroll.result(resultId)
                                .map(PayrollRecords.Result::payrollYymm)
                                .orElse(null);
        return new QueryView(
                q.getId(),
                reference(q),
                resultId,
                member == null ? null : member.eis(),
                member == null ? null : member.name(),
                yymm,
                q.getChannel().name(),
                q.getQueryText(),
                q.concernsComputedAmount(),
                q.getStatus().name(),
                q.getRaisedBy(),
                q.getRaisedAt(),
                q.getMakerResponse(),
                q.getRespondedBy(),
                q.getRespondedAt(),
                q.getResolutionText(),
                q.getResolvedBy(),
                q.getResolvedAt());
    }

    /** Query Reference (X-06 field table: system-generated, unique): QRY-<id>, zero-padded. */
    static String reference(PayrollQuery q) {
        return String.format("QRY-%08d", q.getId());
    }
}
