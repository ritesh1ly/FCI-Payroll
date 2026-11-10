package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/**
 * X-05 Exception worklist (SRS-G-9): no exception is cleared without a recorded cause; an accepted
 * exception is accepted with the Checker's reason as given (none is refused by the entry); the
 * module filter takes a blank as no filter and a padded code as the code.
 */
class ExceptionServiceTest {

    private final GeneralRepositories.Exceptions exceptions =
            mock(GeneralRepositories.Exceptions.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final ExceptionService service =
            new ExceptionService(
                    mock(ExportReader.class),
                    exceptions,
                    mock(EmployeeDirectory.class),
                    org,
                    policy,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC));

    @Test
    void SRS_G_9_noExceptionIsClearedWithoutACause() {
        for (String cause : new String[] {null, " "}) {
            assertThatThrownBy(() -> service.clear("job", "NEG_NET", 9L, 7L, null, cause))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(exceptions);
    }

    @Test
    void SRS_G_9_anAcceptanceCarriesTheCheckersReasonAsGiven() {
        when(context.loginId()).thenReturn("checker@x");
        ExceptionEntry entry = mock(ExceptionEntry.class);
        when(entry.getStatus()).thenReturn(ExceptionEntry.Status.OPEN);
        when(entry.getEmployeeId()).thenReturn(null);
        when(entry.getRaisedAt()).thenReturn(Instant.parse("2026-10-20T06:00:00Z"));
        when(entry.getSeverity()).thenReturn(ExceptionEntry.Severity.WARNING);
        when(exceptions.findById(5L)).thenReturn(Optional.of(entry));
        when(exceptions.countVisible(eq(5L), any())).thenReturn(1L);
        service.accept(context, 5L, null);
        verify(entry).accept("checker@x", null);
    }

    @Test
    void SRS_G_9_aBlankModuleIsNoFilterAndAPaddedOneIsTheCode() {
        stubLists();
        service.worklist(context, ExceptionService.Filter.none(), new PageSpec(0, 20));
        service.worklist(context, filter(" ", null, null, null), new PageSpec(0, 20));
        service.worklist(context, filter(" PAY ", null, null, null), new PageSpec(0, 20));
        verify(exceptions, times(2))
                .findInScope(
                        any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
        verify(exceptions)
                .findInScope(
                        any(), eq("PAY"), isNull(), isNull(), isNull(), isNull(), isNull(), any());
        verify(exceptions, times(0)).countVisible(anyLong(), any());
    }

    private static ExceptionService.Filter filter(
            String module, String period, Long office, String code) {
        return new ExceptionService.Filter(module, null, null, null, period, office, code);
    }

    private void stubLists() {
        when(exceptions.findInScope(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        List<Object[]> tiles = new ArrayList<>();
        tiles.add(new Object[] {0L, 0L, 0L, 0L});
        when(exceptions.tileCounts(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(tiles);
    }

    /** SRS l.604: the period and the condition reach the query; blanks are no filter. */
    @Test
    void SRS_G_9_thePeriodAndTheConditionFilterTheWorklistAndBlanksAreNone() {
        when(context.dataScope()).thenReturn(Set.of(10L));
        stubLists();
        service.worklist(context, filter(null, "202610", null, " NEG_NET "), new PageSpec(0, 20));
        service.worklist(context, filter(null, " ", null, ""), new PageSpec(0, 20));
        verify(exceptions)
                .findInScope(
                        eq(Set.of(10L)),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("202610"),
                        eq("NEG_NET"),
                        any());
        verify(exceptions)
                .findInScope(
                        eq(Set.of(10L)),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any());
    }

    /**
     * SRS l.604 office scope: an office of the session's scope narrows the read to it and the
     * offices below it that the session covers; an office outside the scope is refused as not found
     * and nothing is read.
     */
    @Test
    void SRS_G_9_anOfficeFilterNarrowsTheScopeAndAnOfficeOutsideItIsNotFound() {
        when(context.dataScope()).thenReturn(Set.of(10L, 11L, 12L));
        when(org.subtree(11L)).thenReturn(new LinkedHashSet<>(List.of(11L, 12L, 99L)));
        stubLists();
        service.worklist(context, filter(null, null, 11L, null), new PageSpec(0, 20));
        verify(policy).requireRecordAt(context, "X-05", "VIEW", 11L);
        verify(exceptions)
                .findInScope(
                        eq(Set.of(11L, 12L)),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any());

        // An office with no part of the session's scope below it matches nothing.
        when(org.subtree(13L)).thenReturn(new LinkedHashSet<>(List.of(13L)));
        service.worklist(context, filter(null, null, 13L, null), new PageSpec(0, 20));
        verify(exceptions)
                .findInScope(
                        eq(Set.of(-1L)),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        any());

        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(context, "X-05", "VIEW", 77L);
        assertThatThrownBy(
                        () ->
                                service.groups(
                                        context,
                                        filter(null, null, 77L, null),
                                        new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        verify(exceptions, never())
                .groupsInScope(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * SRS-G-9, SRS l.634: a grouped row carries the members affected over the whole scope; the
     * owner is shown only when every item has the same one; flags follow any item.
     */
    @Test
    void SRS_G_9_aConditionCountsItsMembersAndShowsACommonOwnerOnly() {
        when(context.dataScope()).thenReturn(Set.of(10L));
        stubLists();
        Instant first = Instant.parse("2026-10-01T06:00:00Z");
        List<Object[]> rows = new ArrayList<>();
        rows.add(
                new Object[] {
                    "C-02",
                    "NEG_NET",
                    25L,
                    25L,
                    1L,
                    "PAYROLL_MAKER",
                    "PAYROLL_MAKER",
                    25L,
                    25L,
                    3L,
                    "M-01",
                    "Net pay below floor",
                    first
                });
        // Owners differ: none shown. One item without an owner: none shown. Sums over no rows
        // (the database answers null) are zero.
        rows.add(
                new Object[] {
                    null,
                    "UPSTREAM",
                    4L,
                    0L,
                    null,
                    "HRMS",
                    "PAYROLL_MAKER",
                    4L,
                    null,
                    null,
                    null,
                    "Cycle level",
                    first
                });
        rows.add(
                new Object[] {
                    "I-01",
                    "ATT",
                    3L,
                    3L,
                    0L,
                    "PAYROLL_MAKER",
                    "PAYROLL_MAKER",
                    2L,
                    3L,
                    0L,
                    "I-01",
                    "Attendance",
                    first
                });
        when(exceptions.groupsInScope(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(rows));
        when(exceptions.modulesInScope(any())).thenReturn(List.of("C-02", "I-01"));
        ExceptionService.Groups groups =
                service.groups(context, ExceptionService.Filter.none(), new PageSpec(0, 20));
        assertThat(groups.modules()).containsExactly("C-02", "I-01");
        ExceptionService.GroupView neg = groups.items().get(0);
        assertThat(neg.items()).isEqualTo(25);
        assertThat(neg.members()).isEqualTo(25);
        assertThat(neg.blocksG2()).isTrue();
        assertThat(neg.owner()).isEqualTo("PAYROLL_MAKER");
        assertThat(neg.resolvableInPayroll()).isTrue();
        assertThat(neg.actionable()).isTrue();
        assertThat(neg.firstRaisedAt()).isEqualTo(first);
        ExceptionService.GroupView upstream = groups.items().get(1);
        assertThat(upstream.moduleCode()).isNull();
        assertThat(upstream.owner()).isNull();
        assertThat(upstream.blocksG2()).isFalse();
        assertThat(upstream.resolvableInPayroll()).isFalse();
        assertThat(upstream.actionable()).isFalse();
        assertThat(upstream.members()).isZero();
        ExceptionService.GroupView partly = groups.items().get(2);
        assertThat(partly.owner()).as("one item has no owner").isNull();
        assertThat(partly.blocksG2()).isFalse();
        assertThat(partly.actionable()).isFalse();
        verify(exceptions)
                .groupsInScope(
                        eq(Set.of(10L)),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(ExceptionEntry.unsettledStatuses()),
                        eq(ExceptionEntry.Severity.BLOCKING),
                        any());
    }

    /** SRS l.604: the office filter offers the session's offices by code; none without a scope. */
    @Test
    void SRS_G_9_theOfficeFilterOffersTheSessionsOfficesByCode() {
        OrgLocation b = mock(OrgLocation.class);
        when(b.getId()).thenReturn(2L);
        when(b.getCode()).thenReturn("DH001");
        when(b.getName()).thenReturn("Delhi");
        when(b.getType()).thenReturn(LocationType.values()[0]);
        OrgLocation a = mock(OrgLocation.class);
        when(a.getId()).thenReturn(1L);
        when(a.getCode()).thenReturn("CH001");
        when(a.getName()).thenReturn("Chennai");
        when(a.getType()).thenReturn(LocationType.values()[0]);
        when(context.dataScope()).thenReturn(Set.of(1L, 2L));
        when(org.locations(Set.of(1L, 2L))).thenReturn(List.of(b, a));
        assertThat(service.offices(context))
                .extracting(ExceptionService.OfficeOption::code)
                .containsExactly("CH001", "DH001");
        when(context.dataScope()).thenReturn(Set.of());
        assertThat(service.offices(context)).isEmpty();
    }
}
