package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.ActorDirectory;
import in.gov.fci.payroll.audit.api.ApprovalOutcomes;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.ChangeType;
import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.audit.business.model.AuditEvent;
import in.gov.fci.payroll.audit.orchestration.RecordHistoryService.HistoryRow;
import in.gov.fci.payroll.audit.orchestration.RecordHistoryService.Person;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** X-04 record resolution and the as-of rule (SRS-20-3, SRS-20-6, SC-03-0016). */
class RecordHistoryServiceTest {

    private final Authorization policy = mock(Authorization.class);

    {
        // A reader with the right; RBAC-SEC-141 is proved where it is taken away.
        when(policy.can(any(), anyString(), anyString())).thenReturn(true);
    }

    private final AuditRepositories.Audit audit = mock(AuditRepositories.Audit.class);
    private final AuditService auditWriter = mock(AuditService.class);

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "a@x",
                    "A",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static final LocalDate APR = LocalDate.of(2026, 4, 1);

    private RecordHistoryService service(RecordHistorySource... sources) {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new RecordHistoryService(
                List.of(sources),
                audit,
                policy,
                mock(ExportReader.class),
                none.getBeanProvider(ApprovalOutcomes.class),
                none.getBeanProvider(ActorDirectory.class),
                auditWriter,
                none.getBeanProvider(AttachmentPresence.class));
    }

    private static RecordHistorySource source(String entity, HistoryRecord record) {
        return RecordHistorySource.of(
                entity, key -> key.equals(record.key()) ? Optional.of(record) : Optional.empty());
    }

    private static HistoryRecord versioned(Long locationId, Version... versions) {
        return HistoryRecord.ofVersions(
                "rate_set", "HRA", "M-22", locationId, List.of("HRA"), List.of(versions));
    }

    @Test
    void SC_03_0016_anUnknownTypeABlankKeyOrAKeyWithoutARecordIsNotFound() {
        RecordHistoryService service = service(source("rate_set", versioned(null)));
        for (String[] probe :
                new String[][] {
                    {"no_such", "HRA"}, {"rate_set", " "}, {"rate_set", "NONE"}, {null, "x"}
                }) {
            assertThatThrownBy(() -> service.resolve(context, probe[0], probe[1], "VIEW"))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
        }
        verify(policy, never()).require(any(), anyString(), anyString());
    }

    @Test
    void SRS_20_29_theRecordsOwnScreenOpensItWithoutA05AndAnOfficeRecordIsCheckedAtItsOffice() {
        when(policy.can(context, "A-05", "VIEW")).thenReturn(false);
        when(policy.can(context, "M-22", "VIEW")).thenReturn(true);
        RecordHistoryService national = service(source("rate_set", versioned(null)));
        assertThat(national.resolve(context, "rate_set", " HRA ", "VIEW")).isNotNull();

        // RBAC-SEC-141: no right reads as the unknown key does, so nothing says the record exists.
        when(policy.can(context, "M-22", "VIEW")).thenReturn(false);
        assertThatThrownBy(() -> national.resolve(context, "rate_set", "HRA", "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));

        when(policy.can(context, "A-05", "VIEW")).thenReturn(true);
        RecordHistoryService atOffice = service(source("rate_set", versioned(42L)));
        atOffice.resolve(context, "rate_set", "HRA", "VIEW");
        // A record looked up by its key: outside the scope it reads as an unknown key (AUD-404).
        verify(policy)
                .requireRecordAt(context, "A-05", "VIEW", 42L, ApiErrorCode.AUDIT_RECORD_NOT_FOUND);
    }

    @Test
    void SRS_20_6_SRS_DM_3_overlappingVersionsAreRefusedNamingThem() {
        when(policy.can(any(), anyString(), anyString())).thenReturn(true);
        RecordHistoryService service =
                service(
                        source(
                                "rate_set",
                                versioned(
                                        null,
                                        new Version(1, APR, null, 1L, Map.of("v", "1")),
                                        new Version(2, APR, null, 2L, Map.of("v", "2")))));
        assertThatThrownBy(() -> service.asOf(context, "rate_set", "HRA", APR))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ApiErrorCode.AUDIT_VERSIONS_OVERLAP);
                            assertThat(e.getMessageArgs()).containsExactly("1, 2");
                        });
    }

    @Test
    void SRS_20_6_aVersionedRecordWithNoApprovedVersionYetIsNotInForce() {
        RecordHistoryService service = service(source("rate_set", versioned(null)));
        RecordHistoryService.AsOfView view = service.asOf(context, "rate_set", "HRA", APR);
        assertThat(view.status()).isEqualTo("NOT_IN_FORCE");
        assertThat(view.versioned()).isTrue();
        assertThat(view.values()).isEmpty();
        verify(audit, never()).findByEntityNameAndEntityKeyIn(anyString(), any());
    }

    /**
     * SRS-20-6 (US-GAP-132): an as-of date in a gap between two versions returns no version - not
     * the one before, not the one after; the version fields are empty.
     */
    @Test
    void SRS_20_6_anAsOfDateInAGapBetweenVersionsReturnsNoOtherVersion() {
        LocalDate firstEnds = APR.plusMonths(1).minusDays(1);
        RecordHistoryService service =
                service(
                        source(
                                "rate_set",
                                versioned(
                                        null,
                                        new Version(1, APR, firstEnds, 1L, Map.of("rate", "8")),
                                        new Version(
                                                2,
                                                APR.plusMonths(3),
                                                null,
                                                2L,
                                                Map.of("rate", "9")))));
        RecordHistoryService.AsOfView gap =
                service.asOf(context, "rate_set", "HRA", firstEnds.plusDays(1));
        assertThat(gap.status()).isEqualTo("NOT_IN_FORCE");
        assertThat(gap.versionNo()).isNull();
        assertThat(gap.effectiveFrom()).isNull();
        assertThat(gap.approvalId()).isNull();
        assertThat(gap.values()).isEmpty();
        assertThat(service.asOf(context, "rate_set", "HRA", APR.minusDays(1)).versionNo()).isNull();
        assertThat(service.asOf(context, "rate_set", "HRA", firstEnds).versionNo()).isEqualTo(1);
    }

    @Test
    void SRS_20_3_aRecordKeptAsOneRowIsRebuiltFromItsAuditRowsAndADateIsRequired() {
        HistoryRecord row =
                HistoryRecord.ofCurrent("employee", "104882", "M-01", 10L, Map.of("mobile", "9"));
        when(audit.findByEntityNameAndEntityKeyIn(eq("employee"), any())).thenReturn(List.of());
        RecordHistoryService service = service(source("employee", row));
        RecordHistoryService.AsOfView view = service.asOf(context, "employee", "104882", APR);
        assertThat(view.status()).isEqualTo("FOUND");
        assertThat(view.versioned()).isFalse();
        assertThat(view.values()).containsEntry("mobile", "9");
        assertThatThrownBy(() -> service.asOf(context, "employee", "104882", null))
                .isInstanceOf(FciException.class);
    }

    /** SRS-20-3, SC-03-0017: a row kept in place did not exist before its effective-from. */
    @Test
    void SRS_20_3_aRowKeptInPlaceIsNotInForceBeforeItsEffectiveFrom() {
        HistoryRecord value =
                HistoryRecord.ofCurrent(
                        "reference_value",
                        "CIRCULAR_CATEGORY.DA",
                        "M-16",
                        null,
                        Map.of("value_label_en", "Dearness Allowance"),
                        APR);
        when(audit.findByEntityNameAndEntityKeyIn(eq("reference_value"), any()))
                .thenReturn(List.of());
        RecordHistoryService service = service(source("reference_value", value));
        assertThat(
                        service.asOf(
                                        context,
                                        "reference_value",
                                        "CIRCULAR_CATEGORY.DA",
                                        APR.minusDays(1))
                                .status())
                .isEqualTo("NOT_IN_FORCE");
        // From its effective-from on, the row as it stands.
        RecordHistoryService.AsOfView on =
                service.asOf(context, "reference_value", "CIRCULAR_CATEGORY.DA", APR);
        assertThat(on.status()).isEqualTo("FOUND");
        assertThat(on.values()).containsEntry("value_label_en", "Dearness Allowance");
    }

    @Test
    void SRS_20_24_aPointInTimeQueryIsLoggedWithRequesterScopeAndDateAndARefusedOneIsNot() {
        RecordHistoryService service = service(source("rate_set", versioned(null)));
        service.asOf(context, "rate_set", "HRA", APR);
        verify(auditWriter)
                .recordIndependently(
                        eq("a@x"),
                        org.mockito.ArgumentMatchers.argThat(
                                e ->
                                        e.entityName().equals("point_in_time_query")
                                                && e.entityKey().equals("rate_set/HRA")
                                                && e.fieldName().equals("ENQUIRY")
                                                && e.newValue()
                                                        .equals("office=10;groups=;asOf=2026-04-01")
                                                && e.moduleCode().equals("A-05")
                                                && !e.reason().isBlank()));
        org.mockito.Mockito.clearInvocations(auditWriter);
        assertThatThrownBy(() -> service.asOf(context, "rate_set", "NONE", APR))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.asOf(context, "rate_set", "HRA", null))
                .isInstanceOf(FciException.class);
        verify(auditWriter, never()).recordIndependently(any(), any());
    }

    @Test
    void SRS_20_24_aLongRecordKeyIsCutToTheLogKeyLength() {
        String key = "K".repeat(120);
        HistoryRecord row = HistoryRecord.ofCurrent("employee", key, "M-01", 10L, Map.of());
        when(audit.findByEntityNameAndEntityKeyIn(eq("employee"), any())).thenReturn(List.of());
        service(source("employee", row)).asOf(context, "employee", key, APR);
        verify(auditWriter)
                .recordIndependently(
                        eq("a@x"),
                        org.mockito.ArgumentMatchers.argThat(e -> e.entityKey().length() == 100));
        String exact = "K".repeat(90);
        HistoryRecord fits = HistoryRecord.ofCurrent("employee", exact, "M-01", 10L, Map.of());
        service(source("employee", fits)).asOf(context, "employee", exact, APR);
        verify(auditWriter)
                .recordIndependently(
                        eq("a@x"),
                        org.mockito.ArgumentMatchers.argThat(
                                e -> e.entityKey().equals("employee/" + exact)));
    }

    @Test
    void twoSourcesForOneRecordTypeAreAWiringError() {
        RecordHistorySource one = source("rate_set", versioned(null));
        assertThatThrownBy(() -> service(one, one)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRecordTypesAreListedInOrderForA05Readers() {
        RecordHistoryService service =
                service(
                        source("rule_version", versioned(null)),
                        source("employee", versioned(null)));
        assertThat(service.types(context)).containsExactly("employee", "rule_version");
        verify(policy).require(context, "A-05", "VIEW");
    }

    // ---------------------------------------------------------------- who changed it (2026-10-23)

    private static final Instant T0 = Instant.parse("2026-04-01T04:30:00Z");

    /** Seeded: no approval, by a user the directory knows. */
    private final AuditEvent seeded =
            AuditEvent.of(
                    "rate_set",
                    "HRA",
                    "rate",
                    null,
                    "8",
                    "seed.admin",
                    T0,
                    APR,
                    "Initial",
                    null,
                    ChangeType.INITIAL_VALUE,
                    null,
                    "M-22",
                    null);

    /** Approved: maker and checker from the approval; the checker is not in the directory. */
    private final AuditEvent approved =
            AuditEvent.of(
                    "rate_set",
                    "HRA",
                    "rate",
                    "8",
                    "9",
                    "checker.hq@x",
                    T0.plusSeconds(60),
                    APR,
                    "Revision",
                    77L,
                    ChangeType.CHANGE,
                    "10.0.0.1",
                    "M-22",
                    null);

    /** An approval id whose item is not found: treated as a change without approval. */
    private final AuditEvent orphan =
            AuditEvent.of(
                    "rate_set",
                    "HRA",
                    "rate",
                    "9",
                    "10",
                    "batch.node",
                    T0.plusSeconds(120),
                    APR,
                    "Migrated",
                    78L,
                    ChangeType.CHANGE,
                    null,
                    "M-22",
                    null);

    private final ApprovalOutcomes outcomes =
            ids ->
                    ids.contains(77L)
                            ? Map.of(
                                    77L,
                                    new ApprovalOutcomes.Outcome(
                                            77L,
                                            "APPROVED",
                                            "maker.hq@x",
                                            "checker.hq@x",
                                            T0.plusSeconds(60),
                                            "M-22"))
                            : Map.of();

    private final ActorDirectory directory =
            new ActorDirectory() {
                @Override
                public Map<String, Actor> actors(java.util.Collection<String> logins) {
                    Map<String, Actor> known = new java.util.HashMap<>();
                    if (logins.contains("maker.hq@x")) {
                        known.put("maker.hq@x", new Actor("maker.hq@x", "A. Maker", "100001"));
                    }
                    if (logins.contains("seed.admin")) {
                        known.put("seed.admin", new Actor("seed.admin", "Seed Admin", null));
                    }
                    return known;
                }
            };

    /** A reader that hands the whole first page to the batch, as one batch. */
    private static ExportReader reader() throws Exception {
        ExportReader reader = mock(ExportReader.class);
        when(reader.readAll(any(), any()))
                .thenAnswer(
                        i -> {
                            java.util.function.Function<Pageable, Page<AuditEvent>> query =
                                    i.getArgument(0);
                            ExportReader.Batch<AuditEvent> batch = i.getArgument(1);
                            Page<AuditEvent> page = query.apply(PageRequest.of(0, 100));
                            batch.handle(page.getContent());
                            return page.getTotalElements();
                        });
        return reader;
    }

    private RecordHistoryService withDirectory(ExportReader reader, RecordHistorySource source) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("approvalOutcomes", outcomes);
        beans.addBean("actorDirectory", directory);
        return new RecordHistoryService(
                List.of(source),
                audit,
                policy,
                reader,
                beans.getBeanProvider(ApprovalOutcomes.class),
                beans.getBeanProvider(ActorDirectory.class),
                auditWriter,
                beans.getBeanProvider(AttachmentPresence.class));
    }

    private void rowsOfHra(AuditEvent... events) {
        when(audit.findByEntityNameAndEntityKeyInOrderByChangedAtAscIdAsc(
                        eq("rate_set"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(events)));
    }

    /** Records what an export writes. */
    private static final class Recorded implements ExportSink {
        final List<List<Object>> rows = new ArrayList<>();

        @Override
        public void header(String... titles) {}

        @Override
        public void row(Object... cells) {
            rows.add(Arrays.asList(cells));
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {}
    }

    @Test
    void SRS_20_29_eachRowNamesTheApprovalsMakerAndCheckerElseTheUserWhoChangedIt()
            throws Exception {
        rowsOfHra(seeded, approved, orphan);
        RecordHistoryService service = withDirectory(reader(), source("rate_set", versioned(null)));

        List<HistoryRow> rows = service.history(context, "rate_set", "HRA", 0, 50).items();

        assertThat(rows.get(0).maker()).isEqualTo(new Person("seed.admin", "Seed Admin", null));
        assertThat(rows.get(0).checker()).isNull();
        assertThat(rows.get(0).approvalStatus()).isNull();
        assertThat(rows.get(0).changeType()).isEqualTo("INITIAL_VALUE");
        assertThat(rows.get(1).maker()).isEqualTo(new Person("maker.hq@x", "A. Maker", "100001"));
        assertThat(rows.get(1).checker())
                .as("not in the directory: the login alone")
                .isEqualTo(new Person("checker.hq@x", null, null));
        assertThat(rows.get(1).approvalStatus()).isEqualTo("APPROVED");
        assertThat(rows.get(2).maker()).isEqualTo(new Person("batch.node", null, null));
        assertThat(rows.get(2).approvalId()).isEqualTo(78L);
        assertThat(rows.get(2).approvalStatus()).isNull();
        assertThat(rows).allSatisfy(r -> assertThat(r.origin()).isEqualTo("NATIVE"));
    }

    /**
     * SRS-20-2 with the X-04 field table (l.1253-1259, US-GAP-129): every native row carries its
     * Transaction ID (unique, the row id), Object and Key, IP Address of the acting user, Maker and
     * Checker with name and Employee No., and its Status / Outcome; a row recorded without an
     * address (a batch step) shows none rather than an invented one.
     */
    @Test
    void SRS_20_2_eachRowCarriesTheX04MandatoryFields() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(seeded, "id", 41L);
        org.springframework.test.util.ReflectionTestUtils.setField(approved, "id", 42L);
        rowsOfHra(seeded, approved);
        RecordHistoryService service = withDirectory(reader(), source("rate_set", versioned(null)));

        List<HistoryRow> rows = service.history(context, "rate_set", "HRA", 0, 50).items();

        HistoryRow change = rows.get(1);
        assertThat(change.objectKey()).isEqualTo("rate_set · HRA");
        assertThat(change.field()).isEqualTo("rate");
        assertThat(change.oldValue()).isEqualTo("8");
        assertThat(change.newValue()).isEqualTo("9");
        assertThat(change.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(change.outcome()).isEqualTo("POSTED");
        assertThat(change.reason()).isEqualTo("Revision");
        assertThat(change.effectiveDate()).isEqualTo(APR);
        assertThat(change.changedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(change.maker().employeeNumber()).isEqualTo("100001");
        assertThat(change.checker().loginId()).isEqualTo("checker.hq@x");
        assertThat(rows.get(0).sourceIp()).isNull();
        assertThat(rows).extracting(HistoryRow::transactionId).containsExactly(41L, 42L);
    }

    @Test
    void SRS_20_29_withoutTheApprovalAndUserModulesEachRowNamesTheLoginThatChangedIt() {
        rowsOfHra(seeded, approved);
        List<HistoryRow> rows =
                service(source("rate_set", versioned(null)))
                        .history(context, "rate_set", "HRA", 0, 50)
                        .items();
        assertThat(rows)
                .extracting(HistoryRow::maker)
                .containsExactly(
                        new Person("seed.admin", null, null),
                        new Person("checker.hq@x", null, null));
        assertThat(rows).extracting(HistoryRow::checker).containsOnlyNulls();
    }

    @Test
    void SRS_20_29_theChangeHistoryReportNamesEachPersonWithTheirLoginAndEmployeeNumber()
            throws Exception {
        rowsOfHra(seeded, approved);
        RecordHistoryService service = withDirectory(reader(), source("rate_set", versioned(null)));
        Recorded sink = new Recorded();

        service.exportHistory(context, "rate_set", "HRA", sink);

        assertThat(sink.rows).hasSize(2);
        // maker, maker_employee_no, checker, checker_employee_no are columns 11 to 14.
        assertThat(sink.rows.get(0).subList(11, 15))
                .containsExactly("Seed Admin (seed.admin)", null, null, null);
        assertThat(sink.rows.get(1).subList(11, 15))
                .containsExactly("A. Maker (maker.hq@x)", "100001", "checker.hq@x", null);
        assertThat(sink.rows.get(1).get(15)).as("the source address").isEqualTo("10.0.0.1");
    }

    @Test
    void SRS_20_29_anExtractOnADateWithoutAVersionInForceStillNamesTheRecordAndItsState()
            throws Exception {
        RecordHistoryService service =
                service(
                        source(
                                "rate_set",
                                versioned(
                                        null, new Version(1, APR, null, 1L, Map.of("rate", "8")))));
        Recorded before = new Recorded();
        service.exportAsOf(context, "rate_set", "HRA", APR.minusDays(1), before);
        assertThat(before.rows)
                .containsExactly(
                        Arrays.asList(
                                "rate_set",
                                "HRA",
                                APR.minusDays(1),
                                "NOT_IN_FORCE",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

        Recorded inForce = new Recorded();
        service.exportAsOf(context, "rate_set", "HRA", APR, inForce);
        assertThat(inForce.rows)
                .containsExactly(
                        Arrays.asList(
                                "rate_set", "HRA", APR, "FOUND", 1, APR, null, 1L, "rate", "8"));
    }

    @Test
    void SC_03_0016_aMissingKeyIsNotFound() {
        RecordHistoryService service = service(source("rate_set", versioned(null)));
        assertThatThrownBy(() -> service.resolve(context, "rate_set", null, "VIEW"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.AUDIT_RECORD_NOT_FOUND));
    }

    /**
     * SRS-20-3: a row kept in place is rebuilt from its initial value and its later changes; a
     * change after the as-of date is undone, a field the record no longer has is ignored.
     */
    @Test
    void SRS_20_3_aRowKeptInPlaceIsRebuiltFromItsInitialValueAndItsChanges() {
        HistoryRecord row =
                HistoryRecord.ofCurrent("employee", "104882", "M-01", 10L, Map.of("mobile", "9"));
        AuditEvent initial =
                AuditEvent.of(
                        "employee",
                        "104882",
                        "mobile",
                        null,
                        "8",
                        "seed.admin",
                        T0,
                        APR,
                        "Initial",
                        null,
                        ChangeType.INITIAL_VALUE,
                        null,
                        "M-01",
                        null);
        AuditEvent later =
                AuditEvent.of(
                        "employee",
                        "104882",
                        "mobile",
                        "8",
                        "9",
                        "maker@x",
                        T0.plusSeconds(86_400L * 40),
                        APR.plusDays(40),
                        "Changed",
                        null,
                        ChangeType.CHANGE,
                        null,
                        "M-01",
                        null);
        AuditEvent dropped =
                AuditEvent.of(
                        "employee",
                        "104882",
                        "fax",
                        null,
                        "1",
                        "seed.admin",
                        T0,
                        APR,
                        "Initial",
                        null,
                        ChangeType.INITIAL_VALUE,
                        null,
                        "M-01",
                        null);
        org.springframework.test.util.ReflectionTestUtils.setField(initial, "id", 51L);
        org.springframework.test.util.ReflectionTestUtils.setField(later, "id", 52L);
        org.springframework.test.util.ReflectionTestUtils.setField(dropped, "id", 53L);
        when(audit.findByEntityNameAndEntityKeyIn(eq("employee"), any()))
                .thenReturn(List.of(initial, later, dropped));
        RecordHistoryService service = service(source("employee", row));

        RecordHistoryService.AsOfView before =
                service.asOf(context, "employee", "104882", APR.plusDays(10));
        assertThat(before.status()).isEqualTo("FOUND");
        assertThat(before.values()).containsEntry("mobile", "8").doesNotContainKey("fax");
        assertThat(service.asOf(context, "employee", "104882", APR.plusDays(41)).values())
                .containsEntry("mobile", "9");
    }
}
