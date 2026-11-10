package in.gov.fci.payroll.employee.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.HrmsRecords;
import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters.FieldGroup;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters.MemberIdRow;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters.OrderTask;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters.RegisterRow;
import in.gov.fci.payroll.employee.orchestration.EmployeeRegisters.StaffNumberRow;
import in.gov.fci.payroll.employee.persistence.EmployeeRepository;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The M-01 and I-21 histories and registers as stored (OD-121; SRS-1-28, SRS-1-67, SRS-1-71,
 * SRS-1-76, SRS-16-7, SRS-G-30): an old Member ID or staff number is kept and shown with its zone
 * or office (none when not recorded), restricted values stay masked, only a task still waiting
 * counts its days, a pay-effect note is listed once per group, and a unit without offices binds
 * none.
 */
class EmployeeRegistersCoverageGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T05:00:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final EmployeeRepository repository = mock(EmployeeRepository.class);
    private final HrmsChangeService changes = mock(HrmsChangeService.class);
    private final DocumentStore documents = mock(DocumentStore.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HrmsRecords> hrms = mock(ObjectProvider.class);

    private final AccessContext context =
            new AccessContext(
                    1L,
                    "est@x",
                    "Establishment",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));
    private final Employee employee = mock(Employee.class);

    private EmployeeRegisters registers(NamedParameterJdbcTemplate named) {
        return new EmployeeRegisters(
                named,
                policy,
                org,
                employees,
                repository,
                changes,
                documents,
                hrms,
                Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private final EmployeeRegisters registers = registers(db.named);

    @BeforeEach
    void record() {
        when(employee.getId()).thenReturn(5L);
        when(employee.getEmpNum()).thenReturn("104882");
        when(employee.getLocationId()).thenReturn(10L);
        when(employee.getJoiningDate()).thenReturn(LocalDate.parse("2004-01-02"));
        when(repository.findByEmpNum("104882")).thenReturn(Optional.of(employee));
        OrgZone north = mock(OrgZone.class);
        when(north.getName()).thenReturn("North Zone");
        when(org.zone(3L)).thenReturn(Optional.of(north));
        OrgLocation delhi = mock(OrgLocation.class);
        when(delhi.displayLabel()).thenReturn("DO001 - Delhi");
        when(org.location(10L)).thenReturn(Optional.of(delhi));
        OrgLocation agra = mock(OrgLocation.class);
        when(agra.displayLabel()).thenReturn("DO002 - Agra");
        when(org.location(11L)).thenReturn(Optional.of(agra));
    }

    @Test
    void SRS_1_76_everyMemberIdIsShownWithItsZoneAndTheOldOneIsKept() {
        db.rows(
                "FROM cpf_member_id_history",
                row(
                        "ID_TYPE", "CPF",
                        "IDENTIFIER_VALUE", "NZ/1234",
                        "CATEGORY_CODE", "A",
                        "ZONE_ID", 3L,
                        "EFFECTIVE_FROM", Date.valueOf("2004-01-02"),
                        "EFFECTIVE_TO", Date.valueOf("2020-03-31"),
                        "ID_STATUS", "SUPERSEDED",
                        "SUPERSEDE_REASON", "Zone transfer"),
                row(
                        "ID_TYPE",
                        "CPF",
                        "IDENTIFIER_VALUE",
                        "EZ/77",
                        "CATEGORY_CODE",
                        "A",
                        "ZONE_ID",
                        null,
                        "EFFECTIVE_FROM",
                        Date.valueOf("2020-04-01"),
                        "EFFECTIVE_TO",
                        null,
                        "ID_STATUS",
                        "ACTIVE",
                        "SUPERSEDE_REASON",
                        null),
                row(
                        "ID_TYPE",
                        "EPS",
                        "IDENTIFIER_VALUE",
                        "EPS/9",
                        "CATEGORY_CODE",
                        null,
                        "ZONE_ID",
                        99L,
                        "EFFECTIVE_FROM",
                        Date.valueOf("2020-04-01"),
                        "EFFECTIVE_TO",
                        null,
                        "ID_STATUS",
                        "ACTIVE",
                        "SUPERSEDE_REASON",
                        null));

        List<MemberIdRow> rows = registers.memberIds(context, "104882");

        assertThat(rows)
                .containsExactly(
                        new MemberIdRow(
                                "CPF",
                                "NZ/1234",
                                "A",
                                "North Zone",
                                LocalDate.of(2004, 1, 2),
                                LocalDate.of(2020, 3, 31),
                                "SUPERSEDED",
                                "Zone transfer"),
                        new MemberIdRow(
                                "CPF",
                                "EZ/77",
                                "A",
                                null,
                                LocalDate.of(2020, 4, 1),
                                null,
                                "ACTIVE",
                                null),
                        new MemberIdRow(
                                "EPS",
                                "EPS/9",
                                null,
                                null,
                                LocalDate.of(2020, 4, 1),
                                null,
                                "ACTIVE",
                                null));
        verify(employees).get(context, "104882");
    }

    @Test
    void SRS_16_7_aReissuedStaffNumberStartsFromTheOriginalAndKeepsEachChange() {
        db.rows(
                "FROM employee_number_history",
                row(
                        "OLD_EMP_NUM",
                        "99001",
                        "NEW_EMP_NUM",
                        "104882",
                        "FROM_LOCATION_ID",
                        11L,
                        "TO_LOCATION_ID",
                        10L,
                        "CHANGE_DATE",
                        Date.valueOf("2015-06-01"),
                        "CHANGE_EVENT",
                        "REISSUE"),
                row(
                        "OLD_EMP_NUM",
                        "104882",
                        "NEW_EMP_NUM",
                        "104882",
                        "FROM_LOCATION_ID",
                        null,
                        "TO_LOCATION_ID",
                        12L,
                        "CHANGE_DATE",
                        Timestamp.from(NOW),
                        "CHANGE_EVENT",
                        "TRANSFER"));

        List<StaffNumberRow> rows = registers.staffNumbers(context, "104882");

        assertThat(rows)
                .containsExactly(
                        new StaffNumberRow(
                                null,
                                "99001",
                                null,
                                "DO002 - Agra",
                                LocalDate.of(2004, 1, 2),
                                "ORIGINAL"),
                        new StaffNumberRow(
                                "99001",
                                "104882",
                                "DO002 - Agra",
                                "DO001 - Delhi",
                                LocalDate.of(2015, 6, 1),
                                "REISSUE"),
                        new StaffNumberRow(
                                "104882",
                                "104882",
                                null,
                                null,
                                LocalDate.of(2026, 11, 7),
                                "TRANSFER"));
    }

    @Test
    void SRS_G_30_anOrderIsStoredWithTheRecordAndReadBackByItsNumber() {
        HrmsChangeService.OrderCopy copy = mock(HrmsChangeService.OrderCopy.class);
        when(copy.documentType()).thenReturn("SANCTION_ORDER");
        when(copy.reference()).thenReturn("FCI/EST/1");
        when(copy.orderDate()).thenReturn(LocalDate.of(2026, 11, 1));
        when(copy.issuingAuthority()).thenReturn("RO Delhi");
        when(copy.fileName()).thenReturn("order.pdf");
        when(copy.content()).thenReturn(new byte[] {1, 2});
        DocumentStore.AttachmentView stored = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(eq("est@x"), any())).thenReturn(stored);

        assertThat(registers.attachOrder(context, " 104882 ", copy)).isSameAs(stored);

        verify(policy).requireEmployeeRecord(context, "M-01", "FLAG", 5L, 10L);
        ArgumentCaptor<DocumentStore.NewAttachment> sent =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(documents).attach(eq("est@x"), sent.capture());
        DocumentStore.NewAttachment a = sent.getValue();
        assertThat(a.entityKey()).isEqualTo("104882");
        assertThat(a.documentTypeCode()).isEqualTo("SANCTION_ORDER");
        assertThat(a.referenceNumber()).isEqualTo("FCI/EST/1");
        assertThat(a.orderDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(a.issuingAuthority()).isEqualTo("RO Delhi");
        assertThat(a.fileName()).isEqualTo("order.pdf");
        assertThat(a.content()).containsExactly(1, 2);
        assertThat(a.containsPii()).isTrue();

        registers.order(context, "::1", "104882", 7L);
        verify(documents).read("est@x", "::1", a.entityName(), "104882", 7L);
    }

    @Test
    void SRS_G_30_noNumberIsNoRecord() {
        when(repository.findByEmpNum("")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registers.attachOrder(context, null, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    private static Map<String, Object> change(
            String kind, String reference, Instant received, String field, String status) {
        return row(
                "KIND",
                kind,
                "REFERENCE",
                reference,
                "RECEIVED",
                received == null ? null : Timestamp.from(received),
                "EMP_NUM",
                "104882",
                "FIRST_NAME",
                "Asha",
                "LAST_NAME",
                "",
                "FIELD_CODE",
                field,
                "FIELD_LABEL",
                field + " label",
                "CURRENT_VALUE",
                "ABCPS1234K",
                "NEW_VALUE",
                "ABCPT5678L",
                "HRMS_REQUEST_ID",
                "HR-1",
                "EFFECTIVE_DATE",
                Date.valueOf("2026-11-01"),
                "ORDER_REF",
                "ORD-1",
                "MAKER_USER",
                "maker",
                "CHECKER_USER",
                null,
                "STATUS",
                status);
    }

    @Test
    void SRS_1_71_onlyATaskStillWaitingCountsItsDaysAndAPanStaysMasked() {
        when(changes.scopeUnit(context, "VIEW")).thenReturn(null);
        Instant received = Instant.parse("2026-11-02T05:00:00Z");
        db.value("SELECT COUNT(*) FROM (", 4L);
        db.rows(
                "SELECT * FROM (",
                change("TASK", "T-1", received, "PAN", "PENDING_CHECKER"),
                change("TASK", "T-2", received, "BASIC_PAY", "APPROVED"),
                change("TASK", "T-3", null, "BASIC_PAY", "PENDING_CHECKER"),
                change("REQUEST", "R-1", received, "BASIC_PAY", "PENDING_CHECKER"));

        Paged<RegisterRow> page = registers.register(context, null, new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items())
                .extracting(RegisterRow::daysPending)
                .containsExactly(5L, null, null, null);
        RegisterRow pan = page.items().get(0);
        assertThat(pan.oldValue()).doesNotContain("PS1234").isNotEqualTo("ABCPS1234K");
        assertThat(pan.newValue()).doesNotContain("PT5678");
        assertThat(pan.employee()).isEqualTo("Asha");
        assertThat(pan.effective()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(pan.received()).isEqualTo(received);
        assertThat(page.items().get(1).oldValue()).isEqualTo("ABCPS1234K");
        assertThat(page.items().get(2).received()).isNull();
    }

    @Test
    void SRS_22_8_aUnitWithNoOfficesBindsNoneAndTheSearchIsEscaped() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        when(changes.scopeUnit(context, "VIEW")).thenReturn(40L);
        when(org.subtree(40L)).thenReturn(Set.of());

        registers(named).register(context, " 50%_A ", new PageSpec(0, 20));
        registers(named).register(context, "  ", new PageSpec(0, 20));

        ArgumentCaptor<MapSqlParameterSource> sent =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named, times(2)).queryForObject(anyString(), sent.capture(), eq(Long.class));
        MapSqlParameterSource first = sent.getAllValues().get(0);
        assertThat(first.getValue("all")).isEqualTo("N");
        assertThat(first.getValue("units")).isEqualTo(Set.of(-1L));
        assertThat((String) first.getValue("q"))
                .startsWith("%50")
                .endsWith("a%")
                .isNotEqualTo("%50%_a%");
        assertThat(sent.getAllValues().get(1).getValue("q")).isNull();
    }

    @Test
    void SRS_1_28_eachPayEffectNoteIsListedOncePerGroup() {
        db.rows(
                "FROM hrms_pay_effect_field",
                row("FIELD_GROUP", "SERVICE_PAY", "FIELD_LABEL", "Basic", "PAY_EFFECT_NOTE", "Pay"),
                row("FIELD_GROUP", "SERVICE_PAY", "FIELD_LABEL", "Grade", "PAY_EFFECT_NOTE", "Pay"),
                row("FIELD_GROUP", "SERVICE_PAY", "FIELD_LABEL", "Band", "PAY_EFFECT_NOTE", null),
                row("FIELD_GROUP", "EXIT", "FIELD_LABEL", "Exit date", "PAY_EFFECT_NOTE", "F&F"));

        List<FieldGroup> groups = registers.fieldGroups(context);

        assertThat(groups)
                .containsExactly(
                        new FieldGroup(
                                "SERVICE_PAY", List.of("Basic", "Grade", "Band"), List.of("Pay")),
                        new FieldGroup("EXIT", List.of("Exit date"), List.of("F&F")));
    }

    @Test
    void SRS_1_67_theTasksOfAnOrderAreListedWithRestrictedValuesMasked() {
        when(changes.scopeUnit(context, "VIEW")).thenReturn(null);
        when(changes.params((Long) null, HrmsChangeService.Filter.none()))
                .thenReturn(new MapSqlParameterSource().addValue("all", "Y"));
        db.value("SELECT COUNT(*) FROM hrms_change_task t", 2L);
        db.rows(
                "SELECT t.hrms_change_task_id",
                row(
                        "HRMS_CHANGE_TASK_ID", 71L,
                        "TASK_NO", "T-71",
                        "EMP_NUM", "104882",
                        "FIRST_NAME", "Asha",
                        "LAST_NAME", "Devi",
                        "CURRENT_VALUE", "123456789012",
                        "NEW_VALUE", "210987654321",
                        "TASK_STATUS", "PENDING_CHECKER",
                        "FIELD_CODE", "AADHAAR"),
                row(
                        "HRMS_CHANGE_TASK_ID", 72L,
                        "TASK_NO", "T-72",
                        "EMP_NUM", "104883",
                        "FIRST_NAME", "Ravi",
                        "LAST_NAME", null,
                        "CURRENT_VALUE", "40000",
                        "NEW_VALUE", "42000",
                        "TASK_STATUS", "APPROVED",
                        "FIELD_CODE", "BASIC_PAY"));

        Paged<OrderTask> page = registers.orderTasks(context, 9L, new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(2);
        OrderTask aadhaar = page.items().get(0);
        assertThat(aadhaar.id()).isEqualTo(71L);
        assertThat(aadhaar.taskNo()).isEqualTo("T-71");
        assertThat(aadhaar.eis()).isEqualTo("104882");
        assertThat(aadhaar.employee()).isEqualTo("Asha Devi");
        assertThat(aadhaar.fromValue()).endsWith("9012").doesNotContain("12345678");
        assertThat(aadhaar.toValue()).endsWith("4321").doesNotContain("21098765");
        assertThat(aadhaar.status()).isEqualTo("PENDING_CHECKER");
        assertThat(aadhaar.fieldCode()).isEqualTo("AADHAAR");
        OrderTask basic = page.items().get(1);
        assertThat(basic.employee()).isEqualTo("Ravi");
        assertThat(basic.fromValue()).isEqualTo("40000");
        assertThat(basic.toValue()).isEqualTo("42000");
    }

    @Test
    void SRS_1_67_aTileTheDatabaseLeavesEmptyCountsZero() {
        java.util.HashMap<String, Object> tiles = new java.util.HashMap<>();
        tiles.put("RECEIVED", 4L);
        tiles.put("reissued", 1L);
        tiles.put("WITH_CHECKER", null);
        db.rows("AS with_checker", tiles);

        EmployeeRegisters.TransferSummary s = registers.transferSummary(context);

        assertThat(s.period()).isEqualTo("2026-11");
        assertThat(s.received()).isEqualTo(4);
        assertThat(s.reissued()).isEqualTo(1);
        assertThat(s.withChecker()).isZero();
    }
}
