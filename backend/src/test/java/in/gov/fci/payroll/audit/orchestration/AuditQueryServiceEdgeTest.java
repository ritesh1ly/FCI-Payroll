package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.ActorDirectory;
import in.gov.fci.payroll.audit.business.AuditObjectGroups;
import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;

/**
 * A-05 Audit Trail (SRS-G-12, SRS-G-13): a search names a range that ends after it starts; blank
 * filters are none; Headquarters reads the national trail; a row recorded without a change type
 * shows none, on screen and in the export.
 */
class AuditQueryServiceEdgeTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-10T00:00:00Z");

    private final ExportReader exportReader = mock(ExportReader.class);
    private final AuditRepositories.Audit audit = mock(AuditRepositories.Audit.class);
    private final Authorization policy = mock(Authorization.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final AccessContext context = mock(AccessContext.class);
    private final RecordHistoryService history = mock(RecordHistoryService.class);
    private final AuditRepositories.LegacyAudit legacy = mock(AuditRepositories.LegacyAudit.class);
    private final AuditRepositories.RetentionClasses retention =
            mock(AuditRepositories.RetentionClasses.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ActorDirectory> actors = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AttachmentPresence> documents = mock(ObjectProvider.class);

    private final AuditQueryService service =
            new AuditQueryService(
                    exportReader,
                    audit,
                    policy,
                    properties,
                    history,
                    legacy,
                    retention,
                    actors,
                    documents,
                    Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    AuditQueryServiceEdgeTest() {
        when(properties.audit()).thenReturn(new ApiProperties.Audit(31));
        when(history.enrich(any()))
                .thenAnswer(
                        call ->
                                ((List<AuditEvent>) call.getArgument(0))
                                        .stream().map(AuditQueryServiceEdgeTest::detail).toList());
    }

    private static RecordHistoryService.HistoryRow detail(AuditEvent e) {
        return new RecordHistoryService.HistoryRow(
                1L,
                Instant.EPOCH,
                "o",
                "f",
                null,
                null,
                "POSTED",
                "CHANGE",
                null,
                "r",
                null,
                null,
                null,
                null,
                null,
                null,
                "NATIVE",
                null);
    }

    private static AuditEvent untyped() {
        AuditEvent e = mock(AuditEvent.class);
        when(e.getChangeType()).thenReturn(null);
        when(e.getEntityName()).thenReturn("employee");
        return e;
    }

    @Test
    void SRS_G_12_aSearchNamesARangeThatEndsAfterItStarts() {
        for (AuditQueryService.Filter bad :
                List.of(
                        new AuditQueryService.Filter(null, TO, null, null, null),
                        new AuditQueryService.Filter(FROM, null, null, null, null),
                        new AuditQueryService.Filter(FROM, FROM, null, null, null),
                        new AuditQueryService.Filter(TO, FROM, null, null, null))) {
            assertThatThrownBy(() -> service.search(context, bad, 0, 20))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(ApiErrorCode.AUDIT_RANGE));
        }
    }

    @Test
    void SRS_G_12_headquartersReadsTheNationalTrailAndBlankFiltersAreNone() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        AuditEvent row = untyped();
        when(audit.search(
                        any(), any(), any(), any(), any(), any(), anyInt(), any(), anyInt(), any(),
                        anyInt(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        AuditQueryService.PageView page =
                service.search(
                        context, new AuditQueryService.Filter(FROM, TO, " ", null, " pay "), 0, 20);
        verify(audit)
                .search(
                        eq(FROM),
                        eq(TO),
                        isNull(),
                        isNull(),
                        eq("%pay%"),
                        isNull(),
                        eq(0),
                        any(),
                        eq(0),
                        any(),
                        eq(0),
                        any(),
                        any());
        assertThat(page.items().get(0).changeType()).isNull();
    }

    @Test
    void SRS_G_13_anUntypedRowIsExportedWithoutAChangeType() throws Exception {
        when(exportReader.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            ExportReader.Batch<AuditEvent> batch = call.getArgument(1);
                            batch.handle(List.of(untyped()));
                            return 1L;
                        });
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        service.export(context, new AuditQueryService.Filter(FROM, TO, null, null, null), sink);
        // Only the change type is asserted: a mock answers 0 for the numeric ids.
        verify(sink)
                .row(
                        any(),
                        any(),
                        any(),
                        eq("employee"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        isNull(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    /** SRS l.1226 (US-03-06): a search term becomes a case-insensitive, escaped "contains". */
    @Test
    void SRS_20_2_aSearchTermIsLowerCasedEscapedAndWrapped() {
        assertThat(AuditQueryService.contains(null)).isNull();
        assertThat(AuditQueryService.contains("   ")).isNull();
        assertThat(AuditQueryService.contains(" Circular ")).isEqualTo("%circular%");
        assertThat(AuditQueryService.contains("APP_USER")).isEqualTo("%app\\_user%");
        assertThat(AuditQueryService.contains("50%")).isEqualTo("%50\\%%");
        assertThat(AuditQueryService.contains("a\\b")).isEqualTo("%a\\\\b%");
        assertThat(AuditQueryService.contains("a!b")).isEqualTo("%a!b%");
        assertThat(AuditQueryService.contains("x")).isEqualTo("%x%");
    }

    /** SRS-G-12: an office other than Headquarters reads only the rows of its own data scope. */
    @Test
    void SRS_G_12_anotherOfficeReadsOnlyItsScopedTrail() {
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        when(audit.search(
                        any(), any(), any(), any(), any(), any(), anyInt(), any(), anyInt(), any(),
                        anyInt(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        service.search(context, new AuditQueryService.Filter(FROM, TO, null, null, null), 0, 20);
        verify(audit)
                .search(
                        eq(FROM), eq(TO), isNull(), isNull(), isNull(), isNull(), eq(0), any(),
                        eq(0), any(), eq(1), any(), any());
    }

    /** SRS l.1226: a drawn object group narrows to its entities; an invented one is refused. */
    @Test
    void SRS_l1226_anObjectGroupNarrowsToItsEntitiesAndATamperedOneIsRefused() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(audit.search(
                        any(), any(), any(), any(), any(), any(), anyInt(), any(), anyInt(), any(),
                        anyInt(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        service.search(
                context,
                new AuditQueryService.Filter(
                        FROM, TO, null, null, null, "WORK_SLIPS", null, "Slip"),
                0,
                20);
        verify(audit)
                .search(
                        eq(FROM),
                        eq(TO),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("%slip%"),
                        eq(1),
                        eq(AuditObjectGroups.entities("WORK_SLIPS")),
                        eq(0),
                        any(),
                        eq(0),
                        any(),
                        any());
        assertThatThrownBy(
                        () ->
                                service.search(
                                        context,
                                        new AuditQueryService.Filter(
                                                FROM,
                                                TO,
                                                null,
                                                null,
                                                null,
                                                "EVERYTHING",
                                                null,
                                                null),
                                        0,
                                        20))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    /**
     * X-04 All users: System is the application's actors, a role group its holders; nobody = no
     * row.
     */
    @Test
    void wireframe_aUserGroupNarrowsToItsLoginsAndAGroupNobodyHoldsFindsNothing() {
        ActorDirectory directory = mock(ActorDirectory.class);
        when(actors.getIfAvailable()).thenReturn(directory);
        when(directory.loginsOf("MAKER")).thenReturn(java.util.Set.of("maker.delhi@fci-demo.test"));
        when(directory.loginsOf("CHECKER")).thenReturn(java.util.Set.of());
        when(audit.search(
                        any(), any(), any(), any(), any(), any(), anyInt(), any(), anyInt(), any(),
                        anyInt(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        for (String group : List.of("MAKER", "CHECKER", "SYSTEM")) {
            service.search(
                    context,
                    new AuditQueryService.Filter(FROM, TO, null, null, null, null, group, null),
                    0,
                    20);
        }
        verify(audit)
                .search(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(0),
                        any(),
                        eq(1),
                        eq(List.of("maker.delhi@fci-demo.test")),
                        anyInt(),
                        any(),
                        any());
        verify(audit)
                .search(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(0),
                        any(),
                        eq(1),
                        eq(List.of("-")),
                        anyInt(),
                        any(),
                        any());
        verify(audit)
                .search(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(0),
                        any(),
                        eq(1),
                        eq(List.of("SYSTEM", "system", "batch")),
                        anyInt(),
                        any(),
                        any());
        assertThatThrownBy(
                        () ->
                                service.search(
                                        context,
                                        new AuditQueryService.Filter(
                                                FROM, TO, null, null, null, null, "ROOT", null),
                                        0,
                                        20))
                .isInstanceOf(FciException.class);
    }

    /** RBAC-SEC-118: 31 days is taken, the 32nd is refused. */
    @Test
    void RBAC_SEC_118_theWindowEndsAtThirtyOneDays() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(audit.search(
                        any(), any(), any(), any(), any(), any(), anyInt(), any(), anyInt(), any(),
                        anyInt(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        Instant to31 = FROM.plus(java.time.Duration.ofDays(31));
        service.search(context, new AuditQueryService.Filter(FROM, to31, null, null, null), 0, 20);
        assertThatThrownBy(
                        () ->
                                service.search(
                                        context,
                                        new AuditQueryService.Filter(
                                                FROM,
                                                FROM.plus(java.time.Duration.ofDays(32)),
                                                null,
                                                null,
                                                null),
                                        0,
                                        20))
                .isInstanceOf(FciException.class);
    }

    /** SRS-20-8: another office counts within its scope, and reads no count of the legacy table. */
    @Test
    void SRS_20_8_theTilesStayInTheReadersScopeAndALegacyCountIsHeadquartersOnly() {
        when(audit.countInWindow(any(), any(), anyInt(), any())).thenReturn(5L);
        when(retention.findByCode("NATIVE_AUDIT")).thenReturn(java.util.Optional.empty());
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        AuditQueryService.Summary office = service.summary(context);
        assertThat(office.rowsThisCycle()).isEqualTo(5L);
        assertThat(office.legacyRows()).isNull();
        assertThat(office.retentionSet()).isFalse();
        verify(audit).countInWindow(any(), any(), eq(1), any());
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        when(legacy.count()).thenReturn(9L);
        assertThat(service.summary(context).legacyRows()).isEqualTo(9L);
    }

    /** SRS l.1248, SRS-20-8: an order opens only from a row the reader's search would return. */
    @Test
    void SRS_l1248_anOrderOpensOnlyFromARowInTheReadersScope() {
        AuditEvent elsewhere = mock(AuditEvent.class);
        when(elsewhere.getLocationId()).thenReturn(99L);
        when(audit.findById(7L)).thenReturn(java.util.Optional.of(elsewhere));
        when(policy.isAtHeadquarters(context)).thenReturn(false);
        assertThatThrownBy(() -> service.orderDocument(context, "10.0.0.1", 7L, 91L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
        AttachmentPresence store = mock(AttachmentPresence.class);
        when(documents.getIfAvailable()).thenReturn(store);
        when(elsewhere.getEntityName()).thenReturn("employee");
        when(elsewhere.getEntityKey()).thenReturn("104882");
        when(context.loginId()).thenReturn("hq@fci-demo.test");
        AttachmentPresence.FileContent file =
                new AttachmentPresence.FileContent("o.pdf", "application/pdf", new byte[] {1});
        when(store.content("hq@fci-demo.test", "10.0.0.1", "employee", "104882", 91L))
                .thenReturn(file);
        when(policy.isAtHeadquarters(context)).thenReturn(true);
        assertThat(service.orderDocument(context, "10.0.0.1", 7L, 91L)).isSameAs(file);
    }
}
