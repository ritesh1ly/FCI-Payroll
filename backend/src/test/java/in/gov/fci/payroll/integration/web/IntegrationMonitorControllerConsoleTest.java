package in.gov.fci.payroll.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.orchestration.HrmsApiCatalogService;
import in.gov.fci.payroll.integration.orchestration.HrmsConsoleService;
import in.gov.fci.payroll.integration.orchestration.MonitorService;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

/** SRS-INT-33; screen X-03: the HRMS API console route, its gate and its delegation. */
class IntegrationMonitorControllerConsoleTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final HrmsConsoleService console = mock(HrmsConsoleService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final IntegrationMonitorController controller =
            new IntegrationMonitorController(
                    contexts,
                    mock(MonitorService.class),
                    mock(HrmsApiCatalogService.class),
                    mock(Paging.class),
                    mock(ExportStreaming.class),
                    console);

    private static Method handler() throws NoSuchMethodException {
        return IntegrationMonitorController.class.getMethod(
                "callHrmsApi", String.class, HrmsConsoleService.CallRequest.class);
    }

    @Test
    void SRS_INT_33_theRouteIsAPostToTheApiCallPath() throws Exception {
        PostMapping mapping = handler().getAnnotation(PostMapping.class);
        assertThat(mapping.value()).containsExactly("/v1/integration/hrms-apis/{code}/call");
    }

    @Test
    void SRS_INT_33_onlyX03EditMayCall() throws Exception {
        PreAuthorize gate = handler().getAnnotation(PreAuthorize.class);
        assertThat(gate).isNotNull();
        assertThat(gate.value()).isEqualTo("@authz.gate('X-03','EDIT')");
    }

    @Test
    void SRS_INT_33_theCodeIsLowerCaseSnakeUpTo60() throws Exception {
        Pattern pattern = handler().getParameters()[0].getAnnotation(Pattern.class);
        assertThat(pattern.regexp()).isEqualTo("^[a-z0-9_]{1,60}$");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern.regexp());
        assertThat(p.matcher("get_loan_status").matches()).isTrue();
        assertThat(p.matcher("a".repeat(60)).matches()).isTrue();
        assertThat(p.matcher("a".repeat(61)).matches()).isFalse();
        assertThat(p.matcher("GET_LOAN").matches()).isFalse();
        assertThat(p.matcher("a-b").matches()).isFalse();
        assertThat(p.matcher("").matches()).isFalse();
    }

    @Test
    void SRS_INT_33_theCallIsDelegatedWithTheCurrentAccessAndAnswered() {
        when(contexts.current()).thenReturn(context);
        HrmsConsoleService.CallRequest request =
                new HrmsConsoleService.CallRequest(Map.of("a", "b"), null, "why");
        HrmsConsoleService.CallResult result =
                new HrmsConsoleService.CallResult(
                        "get_loan_status",
                        "GET",
                        Outcome.ACCEPTED,
                        200,
                        null,
                        null,
                        List.of(),
                        false,
                        1,
                        3L,
                        null);
        when(console.call(context, "get_loan_status", request)).thenReturn(result);
        ResponseEntity<HrmsConsoleService.CallResult> answer =
                controller.callHrmsApi("get_loan_status", request);
        assertThat(answer.getBody()).isSameAs(result);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        verify(console).call(context, "get_loan_status", request);
    }
}
