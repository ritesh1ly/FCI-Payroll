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
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.LabourPersons;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.persistence.GeneratedKeysStub;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-02 labour name / date-of-birth correction refusals and effects (plan 20; SRS-2-59, BR-LABM-003;
 * D20-18).
 */
class LabourCorrectionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final long LABOUR = 11L;
    private static final LabourCorrectionService.Document PAGE =
            new LabourCorrectionService.Document("page.pdf", new byte[] {1});

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final LabourPersons persons = mock(LabourPersons.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AuditService audit = mock(AuditService.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AccessContext context = mock(AccessContext.class);
    private final LabourCorrectionService service = service(json);

    private LabourCorrectionService service(ObjectMapper mapper) {
        return new LabourCorrectionService(
                jdbc,
                mock(Authorization.class),
                org,
                approvals,
                persons,
                documents,
                parameters,
                audit,
                mapper,
                Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC));
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.of(58));
        Map<String, Object> labour = new HashMap<>();
        labour.put("LABOUR_NUMBER", "DPT-000011");
        labour.put("DEPOT_LOCATION_ID", 7L);
        labour.put("FIRST_NAME", "Ram");
        labour.put("LAST_NAME", null);
        labour.put("BIRTH_DATE", Date.valueOf("1990-01-15"));
        labour.put("COMP_JOINING_DATE", Date.valueOf("2015-07-01"));
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of(labour));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(3L);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(office));
        when(jdbc.queryForList(
                        contains("list_code = 'CORRECTION_REASON'"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of("AS_PER_SERVICE_BOOK", "AS_PER_ORDER"));
        GeneratedKeysStub.answer(jdbc, "INSERT INTO identity_correction_request", 81L);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(documents.attach(any(), any())).thenReturn(view);
        when(approvals.submit(any(), any(Submission.class), any()))
                .thenReturn(mock(SubmittedApproval.class));
    }

    private static LabourCorrectionService.Correction name(String first, String last) {
        return new LabourCorrectionService.Correction(
                "NAME",
                first,
                last,
                null,
                "AS_PER_SERVICE_BOOK",
                null,
                null,
                TODAY,
                "Area Manager",
                TODAY);
    }

    private static LabourCorrectionService.Correction dob(LocalDate birth) {
        return new LabourCorrectionService.Correction(
                "DOB",
                null,
                null,
                birth,
                "AS_PER_ORDER",
                "Matric certificate",
                "EST/1",
                TODAY,
                "GM",
                TODAY);
    }

    private static LabourCorrectionService.Correction with(
            String type,
            String reason,
            String text,
            String ref,
            LocalDate orderDate,
            String authority,
            LocalDate effective) {
        return new LabourCorrectionService.Correction(
                type, "Ram", "Yadav", null, reason, text, ref, orderDate, authority, effective);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(String.valueOf(e.getMessageArgs()[0]))
                                        .startsWith(expected));
    }

    @Test
    void SRS_2_59_eachPartOfTheCorrectionIsChecked() {
        defect(() -> service.correct(context, LABOUR, null, PAGE), "TYPE");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with(null, "AS_PER_ORDER", null, "R", TODAY, "A", TODAY),
                                PAGE),
                "TYPE");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("GENDER", "AS_PER_ORDER", null, "R", TODAY, "A", TODAY),
                                PAGE),
                "TYPE");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", null, null, "R", TODAY, "A", TODAY),
                                PAGE),
                "REASON");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "TYPO", null, "R", TODAY, "A", TODAY),
                                PAGE),
                "REASON");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with(
                                        "NAME",
                                        "AS_PER_ORDER",
                                        "x".repeat(1001),
                                        "R",
                                        TODAY,
                                        "A",
                                        TODAY),
                                PAGE),
                "REASON_TEXT");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", "नाम", "R", TODAY, "A", TODAY),
                                PAGE),
                "REASON_TEXT");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", null, "R", TODAY, "A", null),
                                PAGE),
                "EFFECTIVE_DATE");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", null, "R", TODAY, " ", TODAY),
                                PAGE),
                "ORDER");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", null, "R", null, "A", TODAY),
                                PAGE),
                "ORDER");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", null, null, TODAY, "A", TODAY),
                                PAGE),
                "ORDER");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                with("NAME", "AS_PER_ORDER", " ok ", "R", TODAY, "A", TODAY),
                                null),
                "DOCUMENT");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                name("Ram", "Yadav"),
                                new LabourCorrectionService.Document("p", null)),
                "DOCUMENT");
        defect(
                () ->
                        service.correct(
                                context,
                                LABOUR,
                                name("Ram", "Yadav"),
                                new LabourCorrectionService.Document("p", new byte[0])),
                "DOCUMENT");
    }

    @Test
    void SRS_2_59_aNameIsLettersOnlyAndDifferentAndOneOfAKindIsOpenAtATime() throws Exception {
        defect(() -> service.correct(context, LABOUR, name("9am", null), PAGE), "NAME");
        defect(() -> service.correct(context, LABOUR, name("Ram", "X$"), PAGE), "NAME");
        defect(() -> service.correct(context, LABOUR, name("Ram", " "), PAGE), "UNCHANGED");
        when(jdbc.queryForObject(
                        contains("request_status IN"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        defect(() -> service.correct(context, LABOUR, name("Ram", "Yadav"), PAGE), "PENDING");
        when(jdbc.queryForObject(
                        contains("request_status IN"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        service.correct(context, LABOUR, name("Ram Kishan", "Yadav"), PAGE);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        assertThat(json.readTree(sent.getValue().payload()).path("firstName").asString())
                .isEqualTo("Ram Kishan");
        assertThat(sent.getValue().workflowAction()).isEqualTo("M02_CORRECTION");
    }

    @Test
    void SRS_2_59_aDateOfBirthIsPastBeforeJoiningConfiguredAndAPastRetirementGoesToHq() {
        defect(() -> service.correct(context, LABOUR, dob(null), PAGE), "BIRTH_DATE");
        defect(() -> service.correct(context, LABOUR, dob(TODAY.plusDays(1)), PAGE), "BIRTH_DATE");
        defect(
                () -> service.correct(context, LABOUR, dob(LocalDate.of(2015, 7, 1)), PAGE),
                "BIRTH_DATE");
        defect(
                () -> service.correct(context, LABOUR, dob(LocalDate.of(1990, 1, 15)), PAGE),
                "UNCHANGED");
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.empty());
        assertThatThrownBy(
                        () ->
                                service.correct(
                                        context, LABOUR, dob(LocalDate.of(1991, 3, 10)), PAGE))
                .isInstanceOf(FciException.class);
        when(parameters.configured(SystemParameters.Code.EPS_CUTOFF_AGE))
                .thenReturn(OptionalLong.of(58));
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.empty());
        assertThatThrownBy(
                        () ->
                                service.correct(
                                        context, LABOUR, dob(LocalDate.of(1991, 3, 10)), PAGE))
                .isInstanceOf(FciException.class);
        when(parameters.configured(SystemParameters.Code.RETIREMENT_AGE))
                .thenReturn(OptionalLong.of(60));
        service.correct(context, LABOUR, dob(LocalDate.of(1960, 5, 10)), PAGE);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals).submit(eq(context), sent.capture(), eq(null));
        assertThat(sent.getValue().workflowAction()).isEqualTo("M02_CORRECTION_HQ");
    }

    @Test
    void SRS_2_59_aRowThatCannotBeReadBackOrAnUnknownLabourOrOfficeFails() {
        GeneratedKeysStub.answer(jdbc, "INSERT INTO identity_correction_request", null);
        assertThatThrownBy(() -> service.correct(context, LABOUR, name("Ram", "Yadav"), PAGE))
                .isInstanceOf(IllegalStateException.class);
        when(org.cycleUnitOf(7L)).thenReturn(Optional.empty());
        defect(() -> service.correct(context, LABOUR, name("Ram", "Yadav"), PAGE), "DEPOT_OFFICE");
        when(jdbc.queryForList(contains("AND p.labour_status = 'ACTIVE'"), any(Object[].class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.correct(context, LABOUR, name("Ram", "Yadav"), PAGE))
                .isInstanceOf(FciException.class);
    }

    private ApprovalDecided decided(
            String entity, ApprovalStatus outcome, String payload, String reason) {
        return new ApprovalDecided(
                91L,
                "M-02",
                entity,
                "81",
                outcome,
                reason,
                payload,
                "checker",
                Instant.now(),
                null);
    }

    private void request(String type, String requested) {
        Map<String, Object> r = new HashMap<>();
        r.put("EMPLOYEE_ID", LABOUR);
        r.put("CORRECTION_TYPE", type);
        r.put("REQUESTED_VALUE", requested);
        r.put("RECALCULATED_RETIREMENT_DATE", Date.valueOf("2051-03-31"));
        when(jdbc.queryForMap(contains("FROM identity_correction_request"), any(Object[].class)))
                .thenReturn(r);
    }

    @Test
    void SRS_2_59_anApprovedCorrectionChangesThePersonARejectedOneDoesNot() throws Exception {
        service.on(decided("labour_profile", ApprovalStatus.APPROVED, null, null));
        request("NAME", "Ram Yadav");
        service.on(
                decided(
                        LabourCorrectionService.ENTITY,
                        ApprovalStatus.REJECTED,
                        null,
                        "Page unclear"));
        verify(persons, never()).correct(anyLong(), any(), any(), any(), any());
        service.on(
                decided(
                        LabourCorrectionService.ENTITY,
                        ApprovalStatus.APPROVED,
                        json.writeValueAsString(new LabourCorrectionService.Name("Ram", "Yadav")),
                        null));
        verify(persons).correct(LABOUR, "Ram", "Yadav", null, null);
        assertThatThrownBy(
                        () ->
                                service.on(
                                        decided(
                                                LabourCorrectionService.ENTITY,
                                                ApprovalStatus.APPROVED,
                                                "{",
                                                null)))
                .isInstanceOf(IllegalStateException.class);
        request("DOB", "1991-03-10");
        service.on(decided(LabourCorrectionService.ENTITY, ApprovalStatus.APPROVED, null, null));
        verify(persons)
                .correct(LABOUR, null, null, LocalDate.of(1991, 3, 10), LocalDate.of(2051, 3, 31));
    }

    @Test
    void aCorrectionThatCannotBeWrittenIsAnError() {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any()))
                .thenThrow(new tools.jackson.core.exc.StreamWriteException(null, "no"));
        assertThatThrownBy(
                        () -> service(broken).correct(context, LABOUR, name("Ram", "Yadav"), PAGE))
                .isInstanceOf(IllegalStateException.class);
    }
}
