package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.export.ExportIncompleteException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * W-01 as drawn (OD-121; SRS-22-4, SRS-G-13): the tiles count over the whole inbox scope, "this
 * cycle" starts on the first of the business month (IST), a session without roles or offices sees
 * zeros, and the export carries every open item or is refused as incomplete.
 */
class InboxOverviewServiceTest {

    // 2026-10-31 23:30 IST is 2026-10-31T18:00Z: the cycle starts 2026-10-01 00:00 IST, not in
    // November as a UTC calendar would say an hour later.
    private static final Instant NOW = Instant.parse("2026-10-31T18:00:00Z");
    private static final Instant CYCLE_START = Instant.parse("2026-09-30T18:30:00Z");

    private final WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final Authorization policy = mock(Authorization.class);
    private final ExportReader reader = mock(ExportReader.class);
    private final AccessContext context = mock(AccessContext.class);
    private final InboxOverviewService service =
            new InboxOverviewService(
                    inbox, approvals, policy, reader, Clock.fixed(NOW, ZoneOffset.UTC));

    private void scoped() {
        when(context.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(context.dataScope()).thenReturn(Set.of(40L, 41L));
    }

    @Test
    void SRS_22_4_theTilesCountTheOpenTasksPastTargetAndTheDecisionsOfTheBusinessMonth() {
        scoped();
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        Set<String> roles = Set.of("PAYROLL_CHECKER");
        Set<Long> scope = Set.of(40L, 41L);
        when(inbox.countOpen(WorkflowInboxItem.Status.PENDING, roles, scope)).thenReturn(14L);
        when(inbox.countOpenPastTarget(WorkflowInboxItem.Status.PENDING, roles, scope))
                .thenReturn(3L);
        when(approvals.countDecidedSince(ApprovalStatus.APPROVED, CYCLE_START, false, scope))
                .thenReturn(188L);
        when(approvals.countDecidedSince(ApprovalStatus.RETURNED, CYCLE_START, false, scope))
                .thenReturn(22L);
        when(inbox.openModules(WorkflowInboxItem.Status.PENDING, roles, scope))
                .thenReturn(List.of("I-04", "M-01"));

        InboxOverviewService.Summary summary = service.summary(context);

        verify(policy).require(context, "W-01", "VIEW");
        assertThat(summary)
                .isEqualTo(
                        new InboxOverviewService.Summary(14, 3, 188, 22, List.of("I-04", "M-01")));
    }

    @Test
    void SRS_22_4_atHeadquartersTheDecisionsOfEveryOfficeCount() {
        scoped();
        when(policy.isAtHeadquarters(context)).thenReturn(true);

        service.summary(context);

        verify(approvals)
                .countDecidedSince(eq(ApprovalStatus.APPROVED), eq(CYCLE_START), eq(true), any());
    }

    @Test
    void SRS_22_4_aSessionWithoutRolesOrOfficesSeesZerosAndAsksNothing() {
        when(context.roleCodes()).thenReturn(Set.of());
        when(context.dataScope()).thenReturn(Set.of(40L));

        assertThat(service.summary(context))
                .isEqualTo(new InboxOverviewService.Summary(0, 0, 0, 0, List.of()));
        verifyNoInteractions(inbox, approvals);

        when(context.roleCodes()).thenReturn(Set.of("PAYROLL_CHECKER"));
        when(context.dataScope()).thenReturn(Set.of());
        assertThat(service.summary(context).pending()).isZero();
        assertThat(service.exportSize(context)).isZero();
        verifyNoInteractions(reader);
    }

    @Test
    void SRS_G_13_theExportSizeIsTheOpenQueueOfTheScope() {
        scoped();
        when(reader.count(any())).thenReturn(41L);

        assertThat(service.exportSize(context)).isEqualTo(41L);
        verify(policy).require(context, "W-01", "EXPORT");
    }

    private static WorkflowInboxItem task(long approvalId, boolean breached) {
        WorkflowInboxItem task = mock(WorkflowInboxItem.class);
        when(task.getApprovalId()).thenReturn(approvalId);
        when(task.isBreached()).thenReturn(breached);
        when(task.getSlaDueAt()).thenReturn(Instant.parse("2026-10-22T04:30:00Z"));
        return task;
    }

    private static ApprovalItem item(long id, String module) {
        ApprovalItem item = mock(ApprovalItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getModuleCode()).thenReturn(module);
        when(item.getEntityName()).thenReturn("off_cycle_payment");
        when(item.getEntityKey()).thenReturn("OC-1");
        when(item.getActionType()).thenReturn(ActionType.CREATE);
        when(item.getSubmittedBy()).thenReturn("maker.delhi@fci-demo.test");
        when(item.getSubmittedAt()).thenReturn(Instant.parse("2026-10-19T04:30:00Z"));
        when(item.getImpactValue()).thenReturn(new BigDecimal("125000.50"));
        when(item.getCurrentLevel()).thenReturn(2);
        when(item.getLevelCount()).thenReturn(3);
        return item;
    }

    /** A sink that keeps what was written. */
    private static final class MemorySink implements ExportSink {
        final List<Object[]> rows = new ArrayList<>();
        String[] header;

        @Override
        public void header(String... titles) {
            header = titles;
        }

        @Override
        public void row(Object... cells) {
            rows.add(cells);
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {}
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_13_theExportWritesEveryOpenItemWithItsRecordAndIsCompleteOrRefused()
            throws IOException {
        scoped();
        WorkflowInboxItem first = task(41L, false);
        WorkflowInboxItem second = task(42L, true);
        ApprovalItem record = item(41L, "I-20");
        when(approvals.findAllById(any())).thenReturn(List.of(record));
        when(reader.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            ExportReader.Batch<WorkflowInboxItem> batch = call.getArgument(1);
                            batch.handle(List.of(first, second));
                            return 2L;
                        });
        MemorySink sink = new MemorySink();

        service.export(context, sink);

        verify(policy).require(context, "W-01", "EXPORT");
        assertThat(sink.header)
                .startsWith("Item", "Module", "Record", "Key", "Action", "Raised by");
        assertThat(sink.rows).hasSize(2);
        assertThat(sink.rows.get(0))
                .containsExactly(
                        41L,
                        "I-20",
                        "off_cycle_payment",
                        "OC-1",
                        ActionType.CREATE,
                        "maker.delhi@fci-demo.test",
                        Instant.parse("2026-10-19T04:30:00Z"),
                        new BigDecimal("125000.50"),
                        Instant.parse("2026-10-22T04:30:00Z"),
                        "N",
                        "2 / 3");
        // A task whose record is gone still exports its id, its due date and its past-target flag.
        assertThat(sink.rows.get(1)[0]).isEqualTo(42L);
        assertThat(sink.rows.get(1)[1]).isNull();
        assertThat(sink.rows.get(1)[9]).isEqualTo("Y");

        // Fewer rows written than counted in the snapshot: never delivered as complete.
        // Re-stubbing with when() would run the answer above with null arguments: doReturn instead.
        doReturn(3L).when(reader).readAll(any(), any());
        assertThatThrownBy(() -> service.export(context, new MemorySink()))
                .isInstanceOf(ExportIncompleteException.class);
    }

    @Test
    void SRS_G_13_anExportOfAnEmptyScopeIsTheHeaderAlone() throws IOException {
        when(context.roleCodes()).thenReturn(Set.of());
        when(context.dataScope()).thenReturn(Set.of());
        MemorySink sink = new MemorySink();

        service.export(context, sink);

        assertThat(sink.header).isNotNull();
        assertThat(sink.rows).isEmpty();
        verifyNoInteractions(reader);
    }
}
