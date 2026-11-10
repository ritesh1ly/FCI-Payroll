package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.labour.business.LabourRecordRules.Person;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService.Document;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService.Nomination;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService.Reinstatement;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 reinstatement and nominees: the order's parts, an empty nomination, the Payroll Checker's
 * decisions either way, with and without a reason, and an unreadable payload (plan 22 phase 22c;
 * NWNP Reinstate Details, BR-FNF-014; D22-7, D22-8).
 */
class LabourRecordServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourRecordService service =
            new LabourRecordService(
                    jdbc,
                    mock(Authorization.class),
                    org,
                    approvals,
                    persons,
                    documents,
                    parameters,
                    audit,
                    JsonMapper.builder().build());

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "L30704");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("LABOUR_STATUS", "SEPARATED");
        labour.put("EFFECTIVE_TO", Date.valueOf(TODAY.minusDays(10)));
        when(jdbc.queryForList(contains("FROM labour_profile p"), eq(33L)))
                .thenReturn(List.of(labour));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    private static Reinstatement order(LocalDate orderDate, String authority) {
        return new Reinstatement(TODAY, null, orderDate, authority, "Appellate order");
    }

    @Test
    void reinstateDetails_theOrderNeedsItsFileDateAndAuthority() {
        Document pdf = new Document("order.pdf", new byte[] {1});
        defect(() -> service.reinstate(context, 33L, null, pdf), "ORDER");
        defect(() -> service.reinstate(context, 33L, order(TODAY, "AM"), null), "ORDER");
        defect(
                () ->
                        service.reinstate(
                                context, 33L, order(TODAY, "AM"), new Document("o.pdf", null)),
                "ORDER");
        defect(
                () ->
                        service.reinstate(
                                context,
                                33L,
                                order(TODAY, "AM"),
                                new Document("o.pdf", new byte[0])),
                "ORDER");
        defect(() -> service.reinstate(context, 33L, order(null, "AM"), pdf), "ORDER");
        defect(() -> service.reinstate(context, 33L, order(TODAY, null), pdf), "ORDER");
        defect(() -> service.reinstate(context, 33L, order(TODAY, " "), pdf), "ORDER");
        defect(() -> service.nominate(context, 33L, null), "EMPTY");
    }

    @Test
    void nomineeDetails_aNominationWithoutListsHasEmptyLists() {
        Nomination n = new Nomination(null, null, "Form 2");
        assertThat(n.nominees()).isEmpty();
        assertThat(n.dependents()).isEmpty();
    }

    private static ApprovalDecided decided(
            String entity, ApprovalStatus outcome, String reason, String payload) {
        return new ApprovalDecided(
                51L,
                "M-02",
                entity,
                "33",
                outcome,
                reason,
                payload,
                "ddo.delhi",
                Instant.now(),
                null);
    }

    @Test
    void reinstateDetails_aReturnedReinstatementLeavesTheMemberSeparated() {
        service.on(decided(LabourRecordService.REINSTATEMENT, ApprovalStatus.REJECTED, null, "{}"));
        verify(persons, never()).reinstate(anyLong(), any(), anyString());
        verify(audit)
                .record(
                        eq("ddo.delhi"),
                        argThat(
                                e ->
                                        "SEPARATED".equals(e.newValue())
                                                && "SEPARATED".equals(e.reason())));
        service.on(decided("rate_set", ApprovalStatus.APPROVED, null, "{}"));
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                LabourRecordService.REINSTATEMENT,
                                                ApprovalStatus.APPROVED,
                                                "ok",
                                                "not json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unreadable Reinstatement");
    }

    @Test
    void nomineeDetails_anApprovedSetIsWrittenWithOrWithoutBirthDatesAndARejectedOneIsNot() {
        String payload =
                "{\"nominees\":[{\"name\":\"Sita\",\"relationship\":\"SPOUSE\",\"birthDate\":null,\"share\":100,\"nominee\":true}],"
                    + "\"dependents\":[{\"name\":\"Kiran\",\"relationship\":\"SON\",\"birthDate\":\"2015-01-01\",\"share\":null,\"nominee\":false},{\"name\":\"Asha\",\"relationship\":\"DAUGHTER\",\"birthDate\":null,\"share\":null,\"nominee\":true}],\"reason\":\"Form"
                    + " 2\"}";
        service.on(decided(LabourRecordService.NOMINATION, ApprovalStatus.APPROVED, null, payload));
        verify(jdbc)
                .update(
                        contains("INSERT INTO pf_nominee"),
                        eq(33L),
                        eq("Sita"),
                        eq("SPOUSE"),
                        isNull(),
                        eq(new BigDecimal("100")),
                        any(),
                        eq(51L));
        verify(jdbc)
                .update(
                        contains("INSERT INTO employee_dependent"),
                        eq(33L),
                        eq("Asha"),
                        eq("DAUGHTER"),
                        isNull(),
                        eq("Y"),
                        any());
        verify(jdbc)
                .update(
                        contains("INSERT INTO employee_dependent"),
                        eq(33L),
                        eq("Kiran"),
                        eq("SON"),
                        eq(Date.valueOf("2015-01-01")),
                        eq("N"),
                        any());
        service.on(
                decided(
                        LabourRecordService.NOMINATION,
                        ApprovalStatus.REJECTED,
                        "Shares wrong",
                        "{}"));
        verify(audit)
                .record(
                        eq("ddo.delhi"),
                        argThat(
                                e ->
                                        "REJECTED".equals(e.newValue())
                                                && "Shares wrong".equals(e.reason())));
        assertThat(new Person("x", "SON", null, null, false).nominee()).isFalse();
    }

    @Test
    void reinstateDetails_aBlankOrderReferenceIsStoredAsNoneAndAGivenOneStripped() {
        Document pdf = new Document("order.pdf", new byte[] {1});
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        service.reinstate(
                context, 33L, new Reinstatement(TODAY, " ", TODAY, " AM ", "Appellate order"), pdf);
        service.reinstate(
                context,
                33L,
                new Reinstatement(TODAY, " ORD-1 ", TODAY, "AM", "Appellate order"),
                pdf);
        ArgumentCaptor<DocumentStore.NewAttachment> attached =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents, times(2)).attach(any(), attached.capture());
        assertThat(attached.getAllValues().get(0).referenceNumber()).isNull();
        assertThat(attached.getAllValues().get(1).referenceNumber()).isEqualTo("ORD-1");
    }

    @Test
    void reinstateDetails_anApprovalWithoutAReasonIsAuditedWithItsOutcome() {
        service.on(
                decided(
                        LabourRecordService.REINSTATEMENT,
                        ApprovalStatus.APPROVED,
                        null,
                        "{\"date\":\"2026-10-28\",\"orderDate\":\"2026-10-28\","
                                + "\"issuingAuthority\":\"AM\",\"reason\":\"Appellate order\"}"));
        verify(persons).reinstate(33L, TODAY, "ddo.delhi");
        verify(audit)
                .record(
                        eq("ddo.delhi"),
                        argThat(e -> "ACTIVE".equals(e.newValue()) && "ACTIVE".equals(e.reason())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nomineeDetails_aBirthDateIsShownOnlyWhenRecorded() throws Exception {
        ResultSet undated = mock(ResultSet.class);
        ResultSet dated = mock(ResultSet.class);
        when(dated.getDate(3)).thenReturn(Date.valueOf("2015-01-01"));
        when(jdbc.query(contains("FROM pf_nominee"), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            RowMapper<Person> m = call.getArgument(1);
                            return List.of(m.mapRow(undated, 0), m.mapRow(dated, 1));
                        });
        when(jdbc.query(contains("FROM employee_dependent"), any(RowMapper.class), any()))
                .thenAnswer(
                        call -> {
                            RowMapper<Person> m = call.getArgument(1);
                            return List.of(m.mapRow(undated, 0), m.mapRow(dated, 1));
                        });
        LabourRecordService.Nominations n = service.nominations(context, 33L);
        assertThat(n.nominees())
                .extracting(Person::birthDate)
                .containsExactly(null, LocalDate.of(2015, 1, 1));
        assertThat(n.dependents())
                .extracting(Person::birthDate)
                .containsExactly(null, LocalDate.of(2015, 1, 1));
        assertThat(n.pending()).isFalse();
    }
}
