package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SRS-20-3, SRS-20-29 mutation gaps of the A-08 route history: the record key, the OTP flag of the
 * image, a key that starts with the separator, and the audit keys (route key plus every version
 * id).
 */
class WorkflowHistoryMutationGapsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static WorkflowAction route(String code, long id, int version, OtpGatedAction otp) {
        WorkflowAction a =
                WorkflowAction.propose(
                        code,
                        "Off-cycle",
                        "GENERAL",
                        "I-20",
                        WorkflowAction.OfficeLevel.DO,
                        2,
                        otp,
                        false,
                        null,
                        version,
                        DAY);
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    void SRS_20_3_theKeyIsTheActionCodeAtTheOfficeLevel() {
        assertThat(WorkflowHistory.key(route("I20_CREATE", 1L, 1, null)))
                .isEqualTo("I20_CREATE@DO");
    }

    @Test
    void SRS_20_29_theImageSaysWhetherAnOtpIsRequired() {
        assertThat(
                        WorkflowHistory.image(
                                        route(
                                                "I20_CREATE",
                                                1L,
                                                1,
                                                OtpGatedAction.SUPPLEMENTARY_POST_LOCK),
                                        List.of())
                                .get("otp_required"))
                .isEqualTo("Y");
        assertThat(
                        WorkflowHistory.image(route("I20_CREATE", 1L, 1, null), List.of())
                                .get("otp_required"))
                .isEqualTo("N");
    }

    @Test
    void SRS_20_3_theAuditKeysAreTheRouteKeyAndEveryVersionId() {
        WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
        WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
        WorkflowAction approved = route("I20_CREATE", 1L, 1, null);
        approved.approve(DAY);
        WorkflowAction pending = route("I20_CREATE", 2L, 2, null);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "I20_CREATE", WorkflowAction.OfficeLevel.DO))
                .thenReturn(List.of(pending, approved));
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());
        RecordHistorySource source = new WorkflowHistory().workflowRouteHistory(actions, levels);

        RecordHistorySource.HistoryRecord record = source.find("I20_CREATE@DO").orElseThrow();

        assertThat(record.key()).isEqualTo("I20_CREATE@DO");
        assertThat(record.auditKeys()).containsExactly("I20_CREATE@DO", "2", "1");
    }

    @Test
    void SRS_20_3_aKeyThatStartsWithTheSeparatorIsNoRouteEvenWhenAnEmptyCodeHasRows() {
        WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
        WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
        WorkflowAction approved = route("", 1L, 1, null);
        approved.approve(DAY);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "", WorkflowAction.OfficeLevel.DO))
                .thenReturn(List.of(approved));
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());
        RecordHistorySource source = new WorkflowHistory().workflowRouteHistory(actions, levels);
        assertThat(source.find("@DO")).isEmpty();
    }
}
