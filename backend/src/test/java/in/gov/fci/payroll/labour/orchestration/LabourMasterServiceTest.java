package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import in.gov.fci.payroll.organisation.business.model.OrgDesignation;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 Labour Master checks, dropdowns and effects (plan 20 phase 20a; SRS-2-30, SRS-2-27; LC-9,
 * LC-10; D20-3): each defect is named, the dropdowns come from the cached organisation, an approval
 * writes the person, and identity numbers are masked.
 */
class LabourMasterServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long DEPOT = 7L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final OrgMasters masters = mock(OrgMasters.class);
    private final LabourDepots depots = mock(LabourDepots.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final LabourMasterService service =
            new LabourMasterService(
                    jdbc,
                    mock(NamedParameterJdbcTemplate.class),
                    policy,
                    org,
                    masters,
                    depots,
                    approvals,
                    persons,
                    parameters,
                    audit,
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
    }

    private static LabourMasterService.Request request(String reason, Long depot, String ifsc) {
        return new LabourMasterService.Request(
                "DEPT_LABOUR",
                "Ram",
                "Kumar",
                null,
                "M",
                LocalDate.of(1990, 1, 1),
                LocalDate.of(2015, 7, 1),
                3L,
                depot,
                "DMOPK1234K",
                ifsc,
                "000012345678",
                "9811000001",
                false,
                null,
                null,
                null,
                reason);
    }

    private void designation(Integer n) {
        when(jdbc.queryForObject(
                        contains("FROM org_designation"), eq(Integer.class), any(Object[].class)))
                .thenReturn(n);
    }

    private void branch(boolean found) {
        when(jdbc.queryForList(contains("FROM bank_branch"), eq(Long.class), any(Object[].class)))
                .thenReturn(found ? List.of(9L) : List.of());
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @Test
    void SRS_2_30_anEntryNeedsAReasonADepotOfTheOfficeADesignationOfItsTypeAndAKnownIfsc() {
        defect(() -> service.create(context, null), "ENTRY");
        defect(() -> service.create(context, request(" ", DEPOT, "SBIN0DEMO01")), "REASON");
        defect(() -> service.create(context, request(null, DEPOT, "SBIN0DEMO01")), "REASON");
        defect(
                () -> service.create(context, request("x".repeat(501), DEPOT, "SBIN0DEMO01")),
                "REASON");
        when(org.location(anyLong())).thenReturn(Optional.empty());
        defect(() -> service.create(context, request("New", null, "SBIN0DEMO01")), "DEPOT");
        wire();
        when(org.cycleUnitOf(DEPOT)).thenReturn(Optional.empty());
        defect(() -> service.create(context, request("New", DEPOT, "SBIN0DEMO01")), "DEPOT_OFFICE");
        wire();
        designation(null);
        defect(() -> service.create(context, request("New", DEPOT, "SBIN0DEMO01")), "DESIGNATION");
        designation(0);
        defect(() -> service.create(context, request("New", DEPOT, "SBIN0DEMO01")), "DESIGNATION");
        designation(1);
        branch(false);
        defect(() -> service.create(context, request("New", DEPOT, "SBIN0DEMO01")), "IFSC");
    }

    @Test
    void LC_9_aPanAlreadyHeldIsADuplicateAndAFreeOneGoesToTheChecker() throws Exception {
        designation(1);
        branch(true);
        when(jdbc.queryForObject(
                        contains("WHERE pan_number = ?"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        assertThatThrownBy(() -> service.create(context, request("New", DEPOT, "sbin0demo01")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("PAN"));
        when(jdbc.queryForObject(
                        contains("WHERE pan_number = ?"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        service.create(context, request(" New ", DEPOT, "sbin0demo01"));
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        assertThat(json.readTree(sent.getValue().payload()).path("ifsc").asString())
                .isEqualTo("SBIN0DEMO01");
    }

    @Test
    void SRS_2_27_theDropdownsAreTheDepotsInScopeAndTheLabourDesignationsInCodeOrder() {
        OrgLocation d = mock(OrgLocation.class);
        when(d.getId()).thenReturn(DEPOT);
        when(d.getCode()).thenReturn("DP-DL01");
        when(d.getName()).thenReturn("Okhla");
        when(depots.of(context, false, TODAY)).thenReturn(List.of(d));
        OrgDesignation hl = designation("HL", "DEPT_LABOUR", 3L);
        OrgDesignation sdr = designation("SDR", "DEPT_LABOUR", 4L);
        OrgDesignation staff = designation("AM", "STAFF", 5L);
        when(masters.designationsById()).thenReturn(Map.of(4L, sdr, 5L, staff, 3L, hl));
        LabourMasterService.Options o = service.options(context);
        assertThat(o.depots())
                .containsExactly(new LabourMasterService.Option(DEPOT, "DP-DL01", "Okhla", null));
        assertThat(o.designations())
                .extracting(LabourMasterService.Option::code)
                .containsExactly("HL", "SDR");
    }

    private static OrgDesignation designation(String code, String family, long id) {
        OrgDesignation d = mock(OrgDesignation.class);
        when(d.getCode()).thenReturn(code);
        when(d.getFamily()).thenReturn(family);
        when(d.getId()).thenReturn(id);
        when(d.getName()).thenReturn(code);
        return d;
    }

    // BMAD TR-035 follow-up (SRS l.754 / l.802, BR-SEC-001 / 002): the PAN shows its last four
    // only, through the shared PiiMasks, as in every other module.
    @Test
    void R_PII_aPanAndAnAccountAreMaskedWhateverTheirLength() {
        assertThat(LabourMasterService.maskPan(null)).isNull();
        assertThat(LabourMasterService.maskPan(" ")).isNull();
        assertThat(LabourMasterService.maskPan("DMOPK1234K")).isEqualTo("******234K");
        assertThat(LabourMasterService.maskPan("SHORT")).isEqualTo("****");
        assertThat(LabourMasterService.maskPan("DMOPK1234KX"))
                .as("eleven characters")
                .isEqualTo("****");
        assertThat(LabourMasterService.maskTail(null)).isNull();
        assertThat(LabourMasterService.maskTail(" ")).isNull();
        assertThat(LabourMasterService.maskTail("1234")).isEqualTo("****");
        assertThat(LabourMasterService.maskTail("123456")).isEqualTo("**3456");
    }

    private ApprovalDecided decided(
            String entity, ApprovalStatus outcome, String key, String payload) {
        return new ApprovalDecided(
                51L, "M-02", entity, key, outcome, null, payload, "checker", Instant.now(), null);
    }

    @Test
    void SRS_2_30_onlyAnApprovedEntryIsAppliedAndAnUnreadableOneFails() throws Exception {
        String payload = json.writeValueAsString(request("New", DEPOT, "SBIN0DEMO01"));
        service.on(decided("labour_transfer", ApprovalStatus.APPROVED, "NEW-1", payload));
        service.on(decided(LabourMasterService.ENTITY, ApprovalStatus.REJECTED, "NEW-1", payload));
        verify(persons, never()).create(any(), any());
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                LabourMasterService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "NEW-1",
                                                "{")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void D20_3_anApprovedAmendmentWritesThePersonWithoutARetirementDateWhileTheAgeIsUnset()
            throws Exception {
        branch(true);
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of(Map.of("LABOUR_NUMBER", "DPT-000001")));
        String payload = json.writeValueAsString(request("Mobile", DEPOT, "SBIN0DEMO01"));
        service.on(decided(LabourMasterService.ENTITY, ApprovalStatus.APPROVED, "41", payload));
        ArgumentCaptor<LabourPersons.Person> person =
                ArgumentCaptor.forClass(LabourPersons.Person.class);
        verify(persons).amend(eq(41L), person.capture(), eq("checker"));
        assertThat(person.getValue().retirementDate()).isNull();
        assertThat(person.getValue().cpfCode()).as("no PF, no CPF code").isNull();
        assertThat(person.getValue().casualLabour()).isFalse();
        verify(jdbc)
                .update(
                        contains("UPDATE labour_profile SET pf_applicable_flag"),
                        eq("N"),
                        any(),
                        any(),
                        eq(41L));
        when(jdbc.queryForList(
                        contains("FROM labour_profile p JOIN employee"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(
                        () -> service.amend(context, 99L, request("Mobile", DEPOT, "SBIN0DEMO01")))
                .isInstanceOf(FciException.class);
    }

    @Test
    void aMasterEntryThatCannotBeWrittenIsAnError() {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "no"));
        LabourMasterService failing =
                new LabourMasterService(
                        jdbc,
                        mock(NamedParameterJdbcTemplate.class),
                        policy,
                        org,
                        masters,
                        depots,
                        approvals,
                        persons,
                        parameters,
                        audit,
                        broken);
        designation(1);
        branch(true);
        assertThatThrownBy(() -> failing.create(context, request("New", DEPOT, "SBIN0DEMO01")))
                .isInstanceOf(IllegalStateException.class);
    }
}
