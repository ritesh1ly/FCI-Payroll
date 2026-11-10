package in.gov.fci.payroll.labour.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.labour.business.LabourRecordRules.Person;
import in.gov.fci.payroll.labour.orchestration.LabourRecordService;
import in.gov.fci.payroll.labour.web.LabourRecordController.NominationBody;
import in.gov.fci.payroll.labour.web.LabourRecordController.PersonBody;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * M-02 Nominee and Dependent Details (API-22-2; D22-8): a nominee keeps its share and is always a
 * nominee; a dependent has no share and is a nominee only when it says so.
 */
class LabourRecordControllerBranchGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final LabourRecordService records = mock(LabourRecordService.class);
    private final LabourRecordController controller = new LabourRecordController(contexts, records);

    @Test
    void nomineeDetails_aDependentIsANomineeOnlyWhenItSaysSo() {
        AccessContext context = mock(AccessContext.class);
        when(contexts.current()).thenReturn(context);
        when(records.nominate(eq(context), eq(33L), any()))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));
        LocalDate born = LocalDate.of(2015, 1, 1);

        var answer =
                controller.nominate(
                        33L,
                        new NominationBody(
                                List.of(
                                        new PersonBody(
                                                "Sita", "WIFE", null, new BigDecimal("100"), null)),
                                List.of(
                                        new PersonBody(
                                                "Asha",
                                                "DAUGHTER",
                                                born,
                                                new BigDecimal("10"),
                                                Boolean.TRUE),
                                        new PersonBody("Kiran", "SON", born, null, Boolean.FALSE),
                                        new PersonBody("Ravi", "SON", born, null, null)),
                                "Form 2"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<LabourRecordService.Nomination> sent =
                ArgumentCaptor.forClass(LabourRecordService.Nomination.class);
        verify(records).nominate(eq(context), eq(33L), sent.capture());
        assertThat(sent.getValue().nominees())
                .containsExactly(new Person("Sita", "WIFE", null, new BigDecimal("100"), true));
        assertThat(sent.getValue().dependents())
                .extracting(Person::nominee)
                .containsExactly(true, false, false);
        assertThat(sent.getValue().dependents()).extracting(Person::share).containsOnlyNulls();
    }
}
