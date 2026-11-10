package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * W-01 Approvals Inbox as Wireframes v6.1 draws it (X-02, OD-121; SRS-22-4, SRS-G-13): the four
 * tiles over the whole scope - Pending Approval, Past target turnaround, Approved This Cycle,
 * Returned - the modules of the open items (the "All modules" filter) and the export of the queue.
 * The scope is the inbox's: the session's roles at the offices of its data scope; "this cycle" is
 * the calendar month of the business date (IST), the payroll period the workspace shows. Counts
 * come from {@code workflow_inbox_item} (open tasks, past-target flag of the housekeeping) and
 * {@code approval_queue} (decided items), never from one page of rows.
 */
@Service
@Loggable
public class InboxOverviewService {

    public static final String SCREEN = "W-01";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** The tiles and the module filter of the inbox. */
    public record Summary(
            long pending,
            long pastTarget,
            long approvedThisCycle,
            long returned,
            List<String> modules) {}

    private final WorkflowRepositories.Inbox inbox;
    private final WorkflowRepositories.Approvals approvals;
    private final Authorization policy;
    private final ExportReader exportReader;
    private final Clock clock;

    public InboxOverviewService(
            WorkflowRepositories.Inbox inbox,
            WorkflowRepositories.Approvals approvals,
            Authorization policy,
            ExportReader exportReader,
            Clock clock) {
        this.inbox = inbox;
        this.approvals = approvals;
        this.policy = policy;
        this.exportReader = exportReader;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Set<String> roles = context.roleCodes();
        Set<Long> scope = context.dataScope();
        if (roles.isEmpty() || scope.isEmpty()) {
            return new Summary(0, 0, 0, 0, List.of());
        }
        WorkflowInboxItem.Status open = WorkflowInboxItem.Status.PENDING;
        Instant since =
                LocalDate.now(clock.withZone(BUSINESS_ZONE))
                        .withDayOfMonth(1)
                        .atStartOfDay(BUSINESS_ZONE)
                        .toInstant();
        boolean national = policy.isAtHeadquarters(context);
        return new Summary(
                inbox.countOpen(open, roles, scope),
                inbox.countOpenPastTarget(open, roles, scope),
                approvals.countDecidedSince(ApprovalStatus.APPROVED, since, national, scope),
                approvals.countDecidedSince(ApprovalStatus.RETURNED, since, national, scope),
                List.copyOf(inbox.openModules(open, roles, scope)));
    }

    /** The rows an export carries (checked against the download size before streaming). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        policy.require(context, SCREEN, "EXPORT");
        if (context.roleCodes().isEmpty() || context.dataScope().isEmpty()) {
            return 0;
        }
        return exportReader.count(page -> openPage(context, page));
    }

    /**
     * The open queue as an export (SRS-G-13): every item in scope, soonest due first as on screen,
     * no personal data beyond the raising user's login. One read-only snapshot, batch by batch, the
     * rows written checked against the rows counted.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        sink.header(
                "Item",
                "Module",
                "Record",
                "Key",
                "Action",
                "Raised by",
                "Raised at",
                "Value / Impact",
                "Target due",
                "Past target",
                "Level");
        if (context.roleCodes().isEmpty() || context.dataScope().isEmpty()) {
            ExportSink.requireComplete(0, sink);
            return;
        }
        long expected =
                exportReader.readAll(
                        page -> openPage(context, page),
                        tasks -> {
                            Map<Long, ApprovalItem> items = new HashMap<>();
                            for (ApprovalItem item :
                                    approvals.findAllById(
                                            tasks.stream()
                                                    .map(WorkflowInboxItem::getApprovalId)
                                                    .toList())) {
                                items.put(item.getId(), item);
                            }
                            for (WorkflowInboxItem task : tasks) {
                                ApprovalItem item = items.get(task.getApprovalId());
                                sink.row(
                                        task.getApprovalId(),
                                        item == null ? null : item.getModuleCode(),
                                        item == null ? null : item.getEntityName(),
                                        item == null ? null : item.getEntityKey(),
                                        item == null ? null : item.getActionType(),
                                        item == null ? null : item.getSubmittedBy(),
                                        item == null ? null : item.getSubmittedAt(),
                                        item == null ? null : item.getImpactValue(),
                                        task.getSlaDueAt(),
                                        task.isBreached() ? "Y" : "N",
                                        item == null
                                                ? null
                                                : item.getCurrentLevel()
                                                        + " / "
                                                        + item.getLevelCount());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    private Page<WorkflowInboxItem> openPage(AccessContext context, Pageable page) {
        return inbox.findOpen(
                WorkflowInboxItem.Status.PENDING, context.roleCodes(), context.dataScope(), page);
    }
}
