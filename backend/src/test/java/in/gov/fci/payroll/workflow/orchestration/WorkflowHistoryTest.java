package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowLevel;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A-08 routes on X-04 (SRS-20-3, SRS-20-29): the image names the route's OTP action and each
 * level's role and office (none when a level names no office); only the approved (ACTIVE) versions
 * are the history; a key without an office level, or with an unknown one, is no route.
 */
class WorkflowHistoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private static WorkflowAction route(long id, int version, OtpGatedAction otp) {
        WorkflowAction a =
                WorkflowAction.propose(
                        "I20_CREATE",
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
    void SRS_20_29_theImageNamesTheOtpActionAndALevelWithoutAnOffice() {
        WorkflowAction a = route(1L, 1, OtpGatedAction.SUPPLEMENTARY_POST_LOCK);
        Map<String, String> image =
                WorkflowHistory.image(
                        a,
                        List.of(
                                WorkflowLevel.of(1L, 2, "PAYROLL_CHECKER", null, true),
                                WorkflowLevel.of(
                                        1L,
                                        1,
                                        "PAYROLL_MAKER",
                                        WorkflowAction.OfficeLevel.DO,
                                        false)));
        assertThat(image.get("otp_action_code")).isEqualTo("SUPPLEMENTARY_POST_LOCK");
        assertThat(image.get("level_2")).endsWith("PAYROLL_CHECKER@ OTP");
        assertThat(image.get("level_1")).endsWith("PAYROLL_MAKER@DO");
    }

    @Test
    void SRS_20_3_onlyApprovedVersionsAreTheHistoryAndABadKeyIsNoRoute() {
        WorkflowRepositories.Actions actions = mock(WorkflowRepositories.Actions.class);
        WorkflowRepositories.Levels levels = mock(WorkflowRepositories.Levels.class);
        WorkflowAction approved = route(1L, 1, null);
        approved.approve(DAY);
        WorkflowAction pending = route(2L, 2, null);
        when(actions.findByActionCodeAndOfficeLevelOrderByVersionNoDesc(
                        "I20_CREATE", WorkflowAction.OfficeLevel.DO))
                .thenReturn(List.of(pending, approved));
        when(levels.findByWorkflowActionIdIn(any())).thenReturn(List.of());
        RecordHistorySource source = new WorkflowHistory().workflowRouteHistory(actions, levels);
        assertThat(source.find("I20_CREATE@DO").orElseThrow().versions())
                .extracting(RecordHistorySource.Version::versionNo)
                .containsExactly(1);
        assertThat(source.find("I20_CREATE")).isEmpty();
        assertThat(source.find("I20_CREATE@MOON")).isEmpty();
        assertThat(source.find("OTHER@DO")).isEmpty();
    }

    @Test
    void SRS_20_29_theImageNamesWhetherTheRouteNeedsAnAttachment() {
        WorkflowAction plain = route(1L, 1, null);
        assertThat(WorkflowHistory.image(plain, List.of()).get("attachment_required"))
                .isEqualTo("N");
        WorkflowAction withOrder =
                WorkflowAction.propose(
                        "L01_SANCTION",
                        "Loan sanction",
                        "LOANS",
                        "L-01",
                        WorkflowAction.OfficeLevel.DO,
                        2,
                        null,
                        true,
                        48,
                        1,
                        DAY);
        Map<String, String> image = WorkflowHistory.image(withOrder, List.of());
        assertThat(image.get("attachment_required")).isEqualTo("Y");
        assertThat(image.get("target_tat_hours")).isEqualTo("48");
    }
}
