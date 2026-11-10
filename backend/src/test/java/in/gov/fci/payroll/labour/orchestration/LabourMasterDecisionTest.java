package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 Labour Master (SRS-2-27, SRS-2-30): the Checker's approval writes the person and the
 * profile: a member not under PF keeps no CPF code on either, a blank CPF code or UAN is none, and
 * casual labour is set only when asked; the list names a member without a last name by the first
 * name and a depot without a code as none.
 */
class LabourMasterDecisionTest {

    private static final long DEPOT = 41L;

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final LabourMasterService service =
            new LabourMasterService(
                    db.jdbc,
                    db.named,
                    mock(Authorization.class),
                    org,
                    mock(OrgMasters.class),
                    mock(LabourDepots.class),
                    mock(MakerChecker.class),
                    persons,
                    parameters,
                    mock(AuditService.class),
                    json);

    LabourMasterDecisionTest() {
        OrgLocation office = mock(OrgLocation.class);
        when(office.getZoneId()).thenReturn(1L);
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.of(office));
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        when(persons.create(any(), anyString())).thenReturn(7L);
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 1));
    }

    private ApprovalDecided approved(String key, boolean pf, String cpf, Boolean casual) {
        LabourMasterService.Request r =
                new LabourMasterService.Request(
                        "DEPT_LABOUR",
                        "Ram",
                        "Kumar",
                        null,
                        "M",
                        LocalDate.of(1990, 1, 1),
                        LocalDate.of(2015, 7, 1),
                        3L,
                        DEPOT,
                        null,
                        null,
                        null,
                        null,
                        pf,
                        cpf,
                        " ",
                        casual,
                        "New gang member");
        return new ApprovalDecided(
                9L,
                "M-02",
                LabourMasterService.ENTITY,
                key,
                ApprovalStatus.APPROVED,
                null,
                json.writeValueAsString(r),
                "checker@x",
                Instant.parse("2026-10-28T06:00:00Z"));
    }

    @Test
    void SRS_2_30_aMemberNotUnderPfKeepsNoCpfCodeAndCasualOnlyWhenAsked() {
        service.on(approved("NEW-1", false, "DL/41/7", null));
        ArgumentCaptor<LabourPersons.Person> person = ArgumentCaptor.captor();
        verify(persons).create(person.capture(), eq("checker@x"));
        assertThat(person.getValue().cpfCode()).isNull();
        assertThat(person.getValue().casualLabour()).isFalse();
        assertThat(person.getValue().retirementDate()).isNull();
        assertThat(db.updates("INSERT INTO labour_profile"))
                .singleElement()
                .satisfies(
                        u ->
                                assertThat(u.args())
                                        .containsExactly(
                                                7L,
                                                "DPT-000001",
                                                "DEPT_LABOUR",
                                                DEPOT,
                                                "N",
                                                "DL/41/7",
                                                null,
                                                Date.valueOf(LocalDate.of(2015, 7, 1))));
    }

    @Test
    void SRS_2_30_anAmendmentUnderPfKeepsTheCodeAndABlankUanIsNone() {
        db.rows(
                "FROM labour_profile p JOIN employee e ON",
                row("labour_number", "DL0007", "payroll_type", "DEPT_LABOUR"));
        service.on(approved("7", true, " DL/41/7 ", true));
        ArgumentCaptor<LabourPersons.Person> person = ArgumentCaptor.captor();
        verify(persons).amend(anyLong(), person.capture(), eq("checker@x"));
        assertThat(person.getValue().cpfCode()).isEqualTo(" DL/41/7 ");
        assertThat(person.getValue().casualLabour()).isTrue();
        assertThat(db.updates("UPDATE labour_profile SET pf_applicable_flag"))
                .singleElement()
                .satisfies(u -> assertThat(u.args()).containsExactly("Y", "DL/41/7", null, 7L));
    }

    @Test
    void SRS_2_27_aMemberWithoutALastNameAndADepotWithoutACodeAreListed() {
        db.rows(
                "ORDER BY p.labour_number, e.employee_id OFFSET",
                row(
                        "employee_id",
                        7L,
                        "labour_number",
                        "DL0007",
                        "first_name",
                        "Ram",
                        "last_name",
                        null,
                        "payroll_type",
                        "DEPT_LABOUR",
                        "designation_name",
                        "Handler",
                        "location_code",
                        null,
                        "location_name",
                        null,
                        "labour_status",
                        "ACTIVE",
                        "comp_joining_date",
                        Date.valueOf(LocalDate.of(2015, 7, 1)),
                        "retirement_date",
                        null,
                        "pan_number",
                        null,
                        "bank_account_number",
                        null,
                        "casual_labour_flag",
                        "N"),
                row(
                        "employee_id", 8L,
                        "labour_number", "DL0008",
                        "first_name", "Shyam",
                        "last_name", " ",
                        "payroll_type", "DEPT_LABOUR",
                        "designation_name", "Handler",
                        "location_code", "DP-DL01",
                        "location_name", "Okhla",
                        "labour_status", "ACTIVE",
                        "comp_joining_date", Date.valueOf(LocalDate.of(2016, 7, 1)),
                        "retirement_date", Date.valueOf(LocalDate.of(2050, 6, 30)),
                        "pan_number", null,
                        "bank_account_number", null,
                        "casual_labour_flag", "Y"));
        List<LabourMasterService.Row> rows =
                service.page(mock(AccessContext.class), " ", null, new PageSpec(0, 20)).items();
        assertThat(rows).extracting(LabourMasterService.Row::name).containsExactly("Ram", "Shyam");
        assertThat(rows)
                .extracting(LabourMasterService.Row::depot)
                .containsExactly(null, "DP-DL01 - Okhla");
    }
}
