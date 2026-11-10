package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsGateway;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.HrmsReply.Outcome;
import in.gov.fci.payroll.integration.config.HrmsConsoleProperties;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** SRS-INT-33, R-PII: the time a console call took is reported in milliseconds. */
class HrmsConsoleServiceMutationGapsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void SRS_INT_33_theElapsedMillisIsTheRealTimeTheGatewayTook() {
        HrmsGateway gateway = mock(HrmsGateway.class);
        AccessContext context = mock(AccessContext.class);
        when(context.loginId()).thenReturn("admin");
        when(gateway.call(any(), any(), any(), any()))
                .thenAnswer(
                        i -> {
                            Thread.sleep(150);
                            return new HrmsReply(
                                    Outcome.ACCEPTED, 200, null, "ok", null, List.of(), false, 1);
                        });
        HrmsConsoleService service =
                new HrmsConsoleService(
                        gateway,
                        new HrmsConsoleProperties(true),
                        mock(Authorization.class),
                        mock(AuditService.class));
        long elapsed =
                service.call(
                                context,
                                HrmsApi.GET_LOAN_STATUS.code(),
                                new HrmsConsoleService.CallRequest(null, null, "Check"))
                        .elapsedMillis();
        assertThat(elapsed).isBetween(140L, 2000L);
    }

    @Test
    void SRS_INT_33_dataOfExactlyTheLimitIsKeptWhole() {
        String exact = "a".repeat(HrmsConsoleService.DATA_LIMIT - 2);
        String shown = HrmsConsoleService.data(JSON.readTree("\"" + exact + "\""));
        assertThat(shown).hasSize(HrmsConsoleService.DATA_LIMIT);
        String over = HrmsConsoleService.data(JSON.readTree("\"" + exact + "b\""));
        assertThat(over).hasSize(HrmsConsoleService.DATA_LIMIT);
    }
}
