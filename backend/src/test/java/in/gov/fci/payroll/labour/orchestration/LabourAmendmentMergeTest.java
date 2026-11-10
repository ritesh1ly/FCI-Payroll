package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.api.OrgMasters;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 amendment (SRS-2-30 "keep the old value in force"; SRS-2-59; R-PII): a field the amendment
 * does not carry keeps its value, an optional one sent empty is cleared, an amendment that changes
 * nothing is refused, the kept fields are read from the record at approval, and the amendment form
 * opens with the record masked and within the scope.
 */
class LabourAmendmentMergeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long DEPOT = 7L;
    private static final long MEMBER = 41L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final LabourMasterService service =
            new LabourMasterService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    policy,
                    org,
                    mock(OrgMasters.class),
                    mock(LabourDepots.class),
                    approvals,
                    persons,
                    parameters,
                    mock(AuditService.class),
                    json);

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        OrgLocation depot = mock(OrgLocation.class);
        when(depot.getId()).thenReturn(DEPOT);
        when(depot.getType()).thenReturn(LocationType.DEPOT);
        when(org.location(DEPOT)).thenReturn(Optional.of(depot));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(office.getZoneId()).thenReturn(1L);
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.of(office));
        when(jdbc.queryForObject(
                        contains("FROM org_designation"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForList(contains("FROM bank_branch"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(9L));
        stands(record());
    }

    /** The member as it stands: PAN, father, gender, mobile, PF with CPF code and UAN all set. */
    private static Map<String, Object> record() {
        Map<String, Object> r = new HashMap<>();
        r.put("LABOUR_NUMBER", "DPT-000041");
        r.put("PAYROLL_TYPE", "DEPT_LABOUR");
        r.put("DEPOT_LOCATION_ID", DEPOT);
        r.put("PF_APPLICABLE_FLAG", "Y");
        r.put("CPF_CODE", "DL/77");
        r.put("UAN_NUMBER", "100200300400");
        r.put("DESIGNATION_ID", 3L);
        r.put("FIRST_NAME", "Ram");
        r.put("LAST_NAME", "Kumar");
        r.put("FATHER_NAME", "Shyam Lal");
        r.put("GENDER", "M");
        r.put("BIRTH_DATE", Date.valueOf("1990-01-01"));
        r.put("COMP_JOINING_DATE", Date.valueOf("2015-07-01"));
        r.put("PAN_NUMBER", "DMOPK1234K");
        r.put("MOBILE_NUMBER", "9811000001");
        r.put("BANK_ACCOUNT_NUMBER", "000012345678");
        r.put("CASUAL_LABOUR_FLAG", "N");
        r.put("IFSC_CODE", "SBIN0DEMO01");
        return r;
    }

    private void stands(Map<String, Object> r) {
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of(r));
    }

    /** An amendment carrying only the given fields (the rest absent: null). */
    private static LabourMasterService.Request change(
            String bankAccount, String mobile, String fatherName, Boolean pf, String reason) {
        return new LabourMasterService.Request(
                null,
                null,
                null,
                fatherName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                bankAccount,
                mobile,
                pf,
                null,
                null,
                null,
                reason);
    }

    private JsonNode submitted() throws Exception {
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        return json.readTree(sent.getValue().payload());
    }

    private static void defect(Runnable call, String expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_2_30_anAmendmentOfTheBankAccountKeepsThePanPfCpfCodeUanAndEveryOtherField()
            throws Exception {
        service.amend(context, MEMBER, change("000099998888", null, null, null, " New account "));
        JsonNode p = submitted();
        assertThat(p.path("bankAccount").asString()).isEqualTo("000099998888");
        assertThat(p.path("panNumber").asString()).isEqualTo("DMOPK1234K");
        assertThat(p.path("fatherName").asString()).isEqualTo("Shyam Lal");
        assertThat(p.path("gender").asString()).isEqualTo("M");
        assertThat(p.path("mobile").asString()).isEqualTo("9811000001");
        assertThat(p.path("pfApplicable").asBoolean()).isTrue();
        assertThat(p.path("cpfCode").asString()).isEqualTo("DL/77");
        assertThat(p.path("uanNumber").asString()).isEqualTo("100200300400");
        assertThat(p.path("ifsc").asString()).isEqualTo("SBIN0DEMO01");
        assertThat(p.path("joiningDate").asString()).isEqualTo("2015-07-01");
        assertThat(p.path("casualLabour").asBoolean()).isFalse();
        assertThat(p.path("firstName").asString()).isEqualTo("Ram");
        assertThat(p.path("designationId").asLong()).isEqualTo(3L);
        assertThat(p.path("reason").asString()).isEqualTo("New account");
    }

    @Test
    void SRS_2_30_anOptionalFieldSentEmptyIsClearedAndThePfFlagCanBeSwitchedOff() throws Exception {
        service.amend(context, MEMBER, change(null, "", " ", false, "Mobile surrendered"));
        JsonNode p = submitted();
        assertThat(p.path("mobile").isNull()).isTrue();
        assertThat(p.path("fatherName").isNull()).isTrue();
        assertThat(p.path("pfApplicable").asBoolean()).isFalse();
        assertThat(p.path("bankAccount").asString()).as("absent: kept").isEqualTo("000012345678");
    }

    @Test
    void SRS_2_30_anAmendmentThatChangesNothingIsRefusedEvenWhenItRepeatsTheValuesInForce() {
        defect(
                () -> service.amend(context, MEMBER, change(null, null, null, null, "No change")),
                "NO_CHANGE");
        // The values in force sent again (one with a different case or spacing) change nothing.
        defect(
                () ->
                        service.amend(
                                context,
                                MEMBER,
                                change("000012345678", "9811000001", " Shyam Lal ", true, "Same")),
                "NO_CHANGE");
        defect(() -> service.amend(context, MEMBER, null), "ENTRY");
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void SRS_2_30_anEmptyRequiredFieldIsStillRefusedAndAnUnknownMemberIsNotFound() {
        defect(
                () -> service.amend(context, MEMBER, change("", null, null, null, "Clear account")),
                "BANK_ACCOUNT");
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(
                        () ->
                                service.amend(
                                        context,
                                        99L,
                                        change("000099998888", null, null, null, "x")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
    }

    @Test
    void SRS_2_59_atApprovalTheNameBirthDateDesignationAndDepotAreTheRecordsAsTheyStandThen()
            throws Exception {
        // Approved meanwhile: a name correction and a designation change.
        Map<String, Object> now = record();
        now.put("FIRST_NAME", "Raam");
        now.put("LAST_NAME", null);
        now.put("DESIGNATION_ID", 4L);
        now.put("BIRTH_DATE", Date.valueOf("1991-02-02"));
        stands(now);
        String payload =
                json.writeValueAsString(
                        new LabourMasterService.Request(
                                "DEPT_LABOUR",
                                "Ram",
                                "Kumar",
                                "Shyam Lal",
                                "M",
                                LocalDate.of(1990, 1, 1),
                                LocalDate.of(2015, 7, 1),
                                3L,
                                DEPOT,
                                "DMOPK1234K",
                                "SBIN0DEMO01",
                                "000099998888",
                                "9811000001",
                                true,
                                "DL/77",
                                "100200300400",
                                false,
                                "New account"));
        service.on(
                new ApprovalDecided(
                        51L,
                        "M-02",
                        LabourMasterService.ENTITY,
                        String.valueOf(MEMBER),
                        ApprovalStatus.APPROVED,
                        null,
                        payload,
                        "checker",
                        Instant.now(),
                        null));
        ArgumentCaptor<LabourPersons.Person> person =
                ArgumentCaptor.forClass(LabourPersons.Person.class);
        verify(persons).amend(eq(MEMBER), person.capture(), eq("checker"));
        LabourPersons.Person written = person.getValue();
        assertThat(written.firstName()).isEqualTo("Raam");
        assertThat(written.lastName()).isNull();
        assertThat(written.designationId()).isEqualTo(4L);
        assertThat(written.birthDate()).isEqualTo(LocalDate.of(1991, 2, 2));
        assertThat(written.bankAccountNumber()).isEqualTo("000099998888");
        assertThat(written.cpfCode()).isEqualTo("DL/77");
        verify(jdbc)
                .update(
                        contains("UPDATE labour_profile SET pf_applicable_flag"),
                        eq("Y"),
                        eq("DL/77"),
                        eq("100200300400"),
                        eq(MEMBER));
    }

    @Test
    void R_PII_theAmendmentFormOpensWithTheRecordMaskedAndOutOfScopeIsNotFound() {
        LabourMasterService.Detail d = service.detail(context, MEMBER);
        assertThat(d.labourCode()).isEqualTo("DPT-000041");
        assertThat(d.panMasked()).isEqualTo("******234K");
        assertThat(d.bankAccountMasked()).isEqualTo("********5678");
        assertThat(d.mobileMasked()).isEqualTo("******0001");
        assertThat(d.ifsc()).isEqualTo("SBIN0DEMO01");
        assertThat(d.pfApplicable()).isTrue();
        assertThat(d.cpfCode()).isEqualTo("DL/77");
        assertThat(d.uanNumber()).isEqualTo("100200300400");
        assertThat(d.fatherName()).isEqualTo("Shyam Lal");
        assertThat(d.casualLabour()).isFalse();
        assertThat(d.joiningDate()).isEqualTo(LocalDate.of(2015, 7, 1));
        verify(policy).requireRecordAt(context, "M-02", "VIEW", DEPOT);

        Map<String, Object> bare = record();
        bare.put("PAN_NUMBER", null);
        bare.put("MOBILE_NUMBER", null);
        bare.put("BIRTH_DATE", null);
        bare.put("DESIGNATION_ID", null);
        stands(bare);
        LabourMasterService.Detail empty = service.detail(context, MEMBER);
        assertThat(empty.panMasked()).isNull();
        assertThat(empty.mobileMasked()).isNull();
        assertThat(empty.birthDate()).isNull();
        assertThat(empty.designationId()).isNull();

        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(any(), eq("M-02"), eq("VIEW"), anyLong());
        assertThatThrownBy(() -> service.detail(context, MEMBER)).isInstanceOf(FciException.class);
    }
}
